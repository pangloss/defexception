(ns com.rpl.defexception.impl
  (:require [clojure.string :as string])
  (:import [clojure.asm MethodVisitor
            ClassVisitor ClassWriter Opcodes Type]
           [clojure.asm.commons GeneratorAdapter Method]))

(defn- forward-constructor [^ClassWriter cw ^Type t ^Method constr]
  (doto (GeneratorAdapter. Opcodes/ACC_PUBLIC constr nil nil cw)
    (.loadThis)
    (.loadArgs)
    (.invokeConstructor t constr)
    (.returnValue)
    (.endMethod)))

(defn- define-class [^clojure.lang.DynamicClassLoader cl ^String name ^ClassWriter cw]
  (let [klass (.defineClass cl name (.toByteArray cw) nil)]
    (when *compile-files*
      (clojure.lang.Compiler/writeClassFile
       (string/replace (munge (.getName klass)) "." "/")
       (.toByteArray cw)))
    klass))

(defn- mk-ex-info-class [exception-class-name]
  #_(prn :making exception-class-name)
  (let [cw (ClassWriter. (+ ClassWriter/COMPUTE_MAXS ClassWriter/COMPUTE_FRAMES))
        ex-info-type (Type/getType clojure.lang.ExceptionInfo)
        internal-name
        (string/replace exception-class-name "." "/")]
    (.visit cw
            ;; this allows compatibility back to Clojure 1.4
            ;; and is = clojure.asm.Opcodes/V1_7
            (inc clojure.asm.Opcodes/V1_6)
            (+ Opcodes/ACC_SUPER Opcodes/ACC_PUBLIC)
            internal-name
            nil
            (.getInternalName ex-info-type)
            (into-array String []))
    (forward-constructor cw ex-info-type (Method/getMethod "void <init> (String, clojure.lang.IPersistentMap)"))
    (forward-constructor cw ex-info-type (Method/getMethod "void <init> (String, clojure.lang.IPersistentMap, Throwable)"))
    (.visitEnd cw)
    (define-class
      (clojure.lang.DynamicClassLoader.)
      exception-class-name
      cw)))

(defn load-or-mk-ex-info-class [exception-class-name]
  (try (clojure.lang.RT/classForName exception-class-name)
       (catch ClassNotFoundException e
         (mk-ex-info-class exception-class-name))))

(def ^:private ex-info-const
  (memoize
   (fn [^Class klass cause?]
     (let [arg-types [String clojure.lang.IPersistentMap]]
       (.getConstructor
        klass
        (into-array Class
                    (if cause?
                      (conj arg-types Throwable)
                      arg-types)))))))

(defn make-ex
  "use reflection to instantiate the exception class"
  [^Class klass msg data cause]
  (let [constr ^java.lang.reflect.Constructor (ex-info-const klass cause)
        args [msg (or data {})]]
    (.newInstance constr
                  (object-array
                   (if cause
                     (conj args cause)
                     args)))))

(defn hyphenate [sym]
  (-> (name sym)
      (string/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      (string/replace #"([a-z\d])([A-Z])" "$1-$2")
      (string/replace #"_" "-")
      (string/lower-case)))
