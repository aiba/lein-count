
;;----------------------------------------------------------------------------------------
;; NOTE: this constant-wrapping reader verison is based on
;; https://dev.clojure.org/jira/browse/TRDR-42
;; Thank you Thomas Heller.
;;
;; I made further modifications to read-keyword to enable constant-wrapping of
;; keywords, and fixed has-feature? and read-arg to support wrapped constants.
;; -- aiba
;;
;; ----------------------------------------------------------------------------------------


;;   Copyright (c) Nicola Mometto, Rich Hickey & contributors.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "A clojure reader in clojure"
      :author "Bronsa"}
    aiba.lein-count.constant-wrapping-reader
  (:refer-clojure :exclude [read read-line read-string char
                            default-data-readers *default-data-reader-fn*
                            *read-eval* *data-readers* *suppress-read*])
  (:require [clojure.tools.reader.reader-types :refer
             [read-char reader-error unread peek-char indexing-reader?
              get-line-number get-column-number get-file-name string-push-back-reader
              log-source]]
            [clojure.tools.reader.impl.utils :refer :all] ;; [char ex-info? whitespace? numeric? desugar-meta]
            [clojure.tools.reader.impl.commons :refer :all]
            [clojure.tools.reader.default-data-readers :as data-readers])
  (:import (clojure.lang PersistentHashSet IMeta
                         RT Symbol Reflector Var IObj
                         PersistentVector IRecord Namespace)
           java.lang.reflect.Constructor
           (java.util regex.Pattern List LinkedList)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare ^:private read*
         macros dispatch-macros
         ^:dynamic *read-eval*
         ^:dynamic *data-readers*
         ^:dynamic *default-data-reader-fn*
         ^:dynamic *suppress-read*
         default-data-readers)

(def ^:dynamic *wrap-constants* false)

(defn ^:private ns-name* [x]
  (if (instance? Namespace x)
    (name (ns-name x))
    (name x)))

(defn- macro-terminating? [ch]
  (case ch
    (\" \; \@ \^ \` \~ \( \) \[ \] \{ \} \\) true
    false))

(defn starting-line-col-info [rdr]
  (when (indexing-reader? rdr)
    [(get-line-number rdr) (int (dec (int (get-column-number rdr))))]))

(defn ending-line-col-info [rdr]
  (when (indexing-reader? rdr)
    [(get-line-number rdr) (get-column-number rdr)]))

(defn loc-info
  ([loc-start rdr]
    (loc-info loc-start rdr 0))
  ([[start-line start-column :as loc-start] rdr start-col-offset]
   (when loc-start
     (merge
       (when-let [file (get-file-name rdr)]
         {:file file})
       (let [[end-line end-column] (ending-line-col-info rdr)]
         {:line start-line
          :column (+ start-column start-col-offset)
          :end-line end-line
          :end-column end-column})))))

(defrecord Constant [loc-info value])

(defn constant? [x]
  (instance? Constant x))

(defn const-val [x]
  (if (constant? x)
    (:value x)
    x))

(defn wrap-constant [loc-start rdr constant]
  (if-not *wrap-constants*
    constant
    (Constant. (loc-info loc-start rdr) constant)))

(defn- ^String read-token
  "Read in a single logical token from the reader"
  [rdr initch]
  (if-not initch
    (reader-error rdr "EOF while reading")
    (loop [sb (StringBuilder.) ch initch]
      (if (or (whitespace? ch)
              (macro-terminating? ch)
              (nil? ch))
        (do (when ch
              (unread rdr ch))
            (str sb))
        (recur (.append sb ch) (read-char rdr))))))

(declare read-tagged)

(defn- read-dispatch
  [rdr _ opts pending-forms]
  (if-let [ch (read-char rdr)]
    (if-let [dm (dispatch-macros ch)]
      (dm rdr ch opts pending-forms)
      (read-tagged (doto rdr (unread ch)) ch opts pending-forms)) ;; ctor reader is implemented as a tagged literal
    (reader-error rdr "EOF while reading character")))

(defn- read-unmatched-delimiter
  [rdr ch opts pending-forms]
  (reader-error rdr "Unmatched delimiter " ch))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; readers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-regex
  [rdr ch opts pending-forms]
  (let [loc-start (starting-line-col-info rdr)
        sb (StringBuilder.)]
    (loop [ch (read-char rdr)]
      (if (identical? \" ch)
        (wrap-constant loc-start rdr (Pattern/compile (str sb)))
        (if (nil? ch)
          (reader-error rdr "EOF while reading regex")
          (do
            (.append sb ch )
            (when (identical? \\ ch)
              (let [ch (read-char rdr)]
                (if (nil? ch)
                  (reader-error rdr "EOF while reading regex"))
                (.append sb ch)))
            (recur (read-char rdr))))))))

(defn- read-unicode-char
  ([^String token ^long offset ^long length ^long base]
   (let [l (+ offset length)]
     (when-not (== (count token) l)
       (throw (IllegalArgumentException. (str "Invalid unicode character: \\" token))))
     (loop [i offset uc 0]
       (if (== i l)
         (char uc)
         (let [d (Character/digit (int (nth token i)) (int base))]
           (if (== d -1)
             (throw (IllegalArgumentException. (str "Invalid digit: " (nth token i))))
             (recur (inc i) (long (+ d (* uc base))))))))))

  ([rdr initch base length exact?]
   (let [base (long base)
         length (long length)]
     (loop [i 1 uc (long (Character/digit (int initch) (int base)))]
       (if (== uc -1)
         (throw (IllegalArgumentException. (str "Invalid digit: " initch)))
         (if-not (== i length)
           (let [ch (peek-char rdr)]
             (if (or (whitespace? ch)
                     (macros ch)
                     (nil? ch))
               (if exact?
                 (throw (IllegalArgumentException.
                         (str "Invalid character length: " i ", should be: " length)))
                 (char uc))
               (let [d (Character/digit (int ch) (int base))]
                 (read-char rdr)
                 (if (== d -1)
                   (throw (IllegalArgumentException. (str "Invalid digit: " ch)))
                   (recur (inc i) (long (+ d (* uc base))))))))
           (char uc)))))))

(def ^:private ^:const upper-limit (int \uD7ff))
(def ^:private ^:const lower-limit (int \uE000))

(defn- read-char**
  "Read in a character literal"
  [rdr backslash opts pending-forms]
  (let [ch (read-char rdr)]
    (if-not (nil? ch)
      (let [token (if (or (macro-terminating? ch)
                          (whitespace? ch))
                    (str ch)
                    (read-token rdr ch))
            token-len (count token)]
        (cond

         (== 1 token-len)  (Character/valueOf (nth token 0))

         (= token "newline") \newline
         (= token "space") \space
         (= token "tab") \tab
         (= token "backspace") \backspace
         (= token "formfeed") \formfeed
         (= token "return") \return

         (.startsWith token "u")
         (let [c (read-unicode-char token 1 4 16)
               ic (int c)]
           (if (and (> ic upper-limit)
                    (< ic lower-limit))
             (reader-error rdr "Invalid character constant: \\u" (Integer/toString ic 16))
             c))

         (.startsWith token "o")
         (let [len (dec token-len)]
           (if (> len 3)
             (reader-error rdr "Invalid octal escape sequence length: " len)
             (let [uc (read-unicode-char token 1 len 8)]
               (if (> (int uc) 0377)
                 (reader-error rdr "Octal escape sequence must be in range [0, 377]")
                 uc))))

         :else (reader-error rdr "Unsupported character: \\" token)))
      (reader-error rdr "EOF while reading character"))))

(defn- read-char*
  [rdr backslash opts pending-forms]
  (let [loc-start (starting-line-col-info rdr)
        char (read-char** rdr backslash opts pending-forms)]
    (wrap-constant loc-start rdr char)
    ))

(defonce ^:private READ_EOF (Object.))
(defonce ^:private READ_FINISHED (Object.))

(def ^:dynamic *read-delim* false)
(defn- ^PersistentVector read-delimited
  "Reads and returns a collection ended with delim"
  [delim rdr opts pending-forms]
  (let [[start-line start-column] (starting-line-col-info rdr)
        delim (char delim)]
    (binding [*read-delim* true]
      (loop [a (transient [])]
        (let [form (read* rdr false READ_EOF delim opts pending-forms)]
          (if (identical? form READ_FINISHED)
            (persistent! a)
            (if (identical? form READ_EOF)
              (reader-error rdr "EOF while reading"
                            (when start-line
                              (str ", starting at line " start-line " and column " start-column)))
              (recur (conj! a form)))))))))

(defn- read-list
  "Read in a list, including its location if the reader is an indexing reader"
  [rdr _ opts pending-forms]
  (let [loc-start (starting-line-col-info rdr)
        the-list (read-delimited \) rdr opts pending-forms)]
    (with-meta (if (empty? the-list)
                 '()
                 (clojure.lang.PersistentList/create the-list))
      (loc-info loc-start rdr))))

(defn- read-vector
  "Read in a vector, including its location if the reader is an indexing reader"
  [rdr _ opts pending-forms]
  (let [loc-start (starting-line-col-info rdr)
        the-vector (read-delimited \] rdr opts pending-forms)]
    (with-meta the-vector
      (loc-info loc-start rdr))))

(defn- read-map
  "Read in a map, including its location if the reader is an indexing reader"
  [rdr _ opts pending-forms]
  (let [loc-start (starting-line-col-info rdr)
        the-map (read-delimited \} rdr opts pending-forms)
        map-count (count the-map)]
    (when (odd? map-count)
      (reader-error rdr "Map literal must contain an even number of forms"))
    (with-meta
      (if (zero? map-count)
        {}
        (RT/map (to-array the-map)))
      (loc-info loc-start rdr))))

(defn- read-number
  [rdr initch]
  (let [loc-start (starting-line-col-info rdr)]
    (loop [sb (doto (StringBuilder.) (.append initch))
           ch (read-char rdr)]
      (if (or (whitespace? ch) (macros ch) (nil? ch))
        (let [s (str sb)]
          (unread rdr ch)
          (or (when-let [num (match-number s)]
                (wrap-constant loc-start rdr num))
              (reader-error rdr "Invalid number format [" s "]")))
        (recur (doto sb (.append ch)) (read-char rdr))))))

(defn- escape-char [sb rdr]
  (let [ch (read-char rdr)]
    (case ch
      \t "\t"
      \r "\r"
      \n "\n"
      \\ "\\"
      \" "\""
      \b "\b"
      \f "\f"
      \u (let [ch (read-char rdr)]
           (if (== -1 (Character/digit (int ch) 16))
             (reader-error rdr "Invalid unicode escape: \\u" ch)
             (read-unicode-char rdr ch 16 4 true)))
      (if (numeric? ch)
        (let [ch (read-unicode-char rdr ch 8 3 false)]
          (if (> (int ch) 0337)
            (reader-error rdr "Octal escape sequence must be in range [0, 377]")
            ch))
        (reader-error rdr "Unsupported escape character: \\" ch)))))

(defn- read-string*
  [reader _ opts pending-forms]
  (let [loc-start (starting-line-col-info reader)]
    (loop [sb (StringBuilder.)
           ch (read-char reader)]
      (case ch
        nil (reader-error reader "EOF while reading string")
        \\ (recur (doto sb (.append (escape-char sb reader)))
             (read-char reader))
        \" (wrap-constant loc-start reader (str sb))
        (recur (doto sb (.append ch)) (read-char reader))))))

(defn- read-symbol
  [rdr initch]
  (let [loc-start (starting-line-col-info rdr)]
    (when-let [token (read-token rdr initch)]
      (case token

        ;; special symbols
        "nil" (wrap-constant loc-start rdr nil)
        "true" (wrap-constant loc-start rdr true)
        "false" (wrap-constant loc-start rdr false)
        "/" '/ ;; FIXME: attach meta?
        "NaN" (wrap-constant loc-start rdr Double/NaN)
        "-Infinity" (wrap-constant loc-start rdr Double/NEGATIVE_INFINITY)
        ("Infinity" "+Infinity") (wrap-constant loc-start rdr Double/POSITIVE_INFINITY)

        (or (when-let [p (parse-symbol token)]
              (with-meta (symbol (p 0) (p 1))
                (loc-info loc-start rdr)))
            (reader-error rdr "Invalid token: " token))))))

(def ^:dynamic *alias-map*
  "Map from ns alias to ns, if non-nil, it will be used to resolve read-time
   ns aliases instead of (ns-aliases *ns*).

   Defaults to nil"
  nil)

(defn- resolve-ns [sym]
  (or ((or *alias-map*
           (ns-aliases *ns*)) sym)
      (find-ns sym)))

(defn- read-keyword
  [reader initch opts pending-forms]
  (let [loc-start (starting-line-col-info reader)
        wrap (partial wrap-constant loc-start reader)
        ch (read-char reader)]
    (if-not (whitespace? ch)
      (let [token (read-token reader ch)
            s (parse-symbol token)]
        (if s
          (let [^String ns (s 0)
                ^String name (s 1)]
            (if (identical? \: (nth token 0))
              (if ns
                (let [ns (resolve-ns (symbol (subs ns 1)))]
                  (if ns
                    (wrap (keyword (str ns) name))
                    (reader-error reader "Invalid token: :" token)))
                (wrap (keyword (str *ns*) (subs name 1))))
              (wrap (keyword ns name))))
          (reader-error reader "Invalid token: :" token)))
      (reader-error reader "Invalid token: :"))))

(defn- wrapping-reader
  "Returns a function which wraps a reader in a call to sym"
  [sym]
  (fn [rdr _ opts pending-forms]
    (list sym (read* rdr true nil opts pending-forms))))

(defn- read-meta
  "Read metadata and return the following object with the metadata applied"
  [rdr _ opts pending-forms]
  (log-source rdr
    (let [[line column] (starting-line-col-info rdr)
          m (desugar-meta (read* rdr true nil opts pending-forms))]
      (when-not (map? m)
        (reader-error rdr "Metadata must be Symbol, Keyword, String or Map"))
      (let [o (read* rdr true nil opts pending-forms)]
        (if (instance? IMeta o)
          (let [m (if (and line (seq? o))
                    (assoc m :line line :column column)
                    m)]
            (if (instance? IObj o)
              (with-meta o (merge (meta o) m))
              (reset-meta! o m)))
          (reader-error rdr "Metadata can only be applied to IMetas"))))))

(defn- read-set
  [rdr _ opts pending-forms]
  (let [loc-start (starting-line-col-info rdr)
        the-set (PersistentHashSet/createWithCheck (read-delimited \} rdr opts pending-forms))]
    (with-meta the-set
      (loc-info loc-start rdr -1)
      )))

(defn- read-discard
  "Read and discard the first object from rdr"
  [rdr _ opts pending-forms]
  (doto rdr
    (read* true nil opts pending-forms)))

(def ^:private RESERVED_FEATURES #{:else :none})

(defn- has-feature?
  [rdr feature opts]
  (let [feature (const-val feature)]
    (if (keyword? feature)
      (or (= :default feature) (contains? (get opts :features) feature))
      (reader-error rdr (str "Feature should be a keyword: " feature)))))

(defn- check-eof-error
  [form rdr ^long first-line]
  (when (identical? form READ_EOF)
    (if (< first-line 0)
      (reader-error rdr "EOF while reading")
      (reader-error rdr "EOF while reading, starting at line " first-line))))

(defn- check-reserved-features
  [rdr form]
  (when (get RESERVED_FEATURES form)
    (reader-error rdr (str "Feature name " form " is reserved"))))

(defn- check-invalid-read-cond
  [form rdr ^long first-line]
  (when (identical? form READ_FINISHED)
    (if (< first-line 0)
      (reader-error rdr "read-cond requires an even number of forms")
      (reader-error rdr (str "read-cond starting on line " first-line " requires an even number of forms")))))

(defn- read-suppress
  "Read next form and suppress. Return nil or READ_FINISHED."
  [first-line rdr opts pending-forms]
  (binding [*suppress-read* true]
    (let [form (read* rdr false READ_EOF \) opts pending-forms)]
      (check-eof-error form rdr first-line)
      (when (identical? form READ_FINISHED)
        READ_FINISHED))))

(def ^:private NO_MATCH (Object.))

(defn- match-feature
  "Read next feature. If matched, read next form and return.
   Otherwise, read and skip next form, returning READ_FINISHED or nil."
  [first-line rdr opts pending-forms]
  (let [feature (read* rdr false READ_EOF \) opts pending-forms)]
    (check-eof-error feature rdr first-line)
    (if (= feature READ_FINISHED)
      READ_FINISHED
      (do
        (check-reserved-features rdr feature)
        (if (has-feature? rdr feature opts)
          ;; feature matched, read selected form
          (doto (read* rdr false READ_EOF \) opts pending-forms)
            (check-eof-error rdr first-line)
            (check-invalid-read-cond rdr first-line))
          ;; feature not matched, ignore next form
          (or (read-suppress first-line rdr opts pending-forms)
              NO_MATCH))))))

(defn- read-cond-delimited
  [rdr splicing opts pending-forms]
  (let [first-line (if (indexing-reader? rdr) (get-line-number rdr) -1)
        result (loop [matched NO_MATCH
                      finished nil]
                 (cond
                  ;; still looking for match, read feature+form
                  (identical? matched NO_MATCH)
                  (let [match (match-feature first-line rdr opts pending-forms)]
                    (if (identical? match READ_FINISHED)
                      READ_FINISHED
                      (recur match nil)))

                  ;; found match, just read and ignore the rest
                  (not (identical? finished READ_FINISHED))
                  (recur matched (read-suppress first-line rdr opts pending-forms))

                  :else
                  matched))]
    (if (identical? result READ_FINISHED)
      rdr
      (if splicing
        (if (instance? List result)
          (do
            (.addAll ^List pending-forms 0 ^List result)
            rdr)
          (reader-error rdr "Spliced form list in read-cond-splicing must implement java.util.List."))
        result))))

(defn- read-cond
  [rdr _ opts pending-forms]
  (when (not (and opts (#{:allow :preserve} (:read-cond opts))))
    (throw (RuntimeException. "Conditional read not allowed")))
  (if-let [ch (read-char rdr)]
    (let [splicing (= ch \@)
          ch (if splicing (read-char rdr) ch)]
      (when splicing
        (when-not *read-delim*
          (reader-error rdr "cond-splice not in list")))
      (if-let [ch (if (whitespace? ch) (read-past whitespace? rdr) ch)]
        (if (not= ch \()
          (throw (RuntimeException. "read-cond body must be a list"))
          (binding [*suppress-read* (or *suppress-read* (= :preserve (:read-cond opts)))]
            (if *suppress-read*
              (reader-conditional (read-list rdr ch opts pending-forms) splicing)
              (read-cond-delimited rdr splicing opts pending-forms))))
        (reader-error rdr "EOF while reading character")))
    (reader-error rdr "EOF while reading character")))

(def ^:private ^:dynamic arg-env)

(defn- garg
  "Get a symbol for an anonymous ?argument?"
  [^long n]
  (symbol (str (if (== -1 n) "rest" (str "p" n))
               "__" (RT/nextID) "#")))

(defn- read-fn
  [rdr _ opts pending-forms]
  (if (thread-bound? #'arg-env)
    (throw (IllegalStateException. "Nested #()s are not allowed")))
  (binding [arg-env (sorted-map)]
    (let [form (read* (doto rdr (unread \()) true nil opts pending-forms) ;; this sets bindings
          rargs (rseq arg-env)
          args (if rargs
                 (let [higharg (long (key ( first rargs)))]
                   (let [args (loop [i 1 args (transient [])]
                                (if (> i higharg)
                                  (persistent! args)
                                  (recur (inc i) (conj! args (or (get arg-env i)
                                                                 (garg i))))))
                         args (if (arg-env -1)
                                (conj args '& (arg-env -1))
                                args)]
                     args))
                 [])]
      (list 'fn* args form))))

(defn- register-arg
  "Registers an argument to the arg-env"
  [n]
  (if (thread-bound? #'arg-env)
    (if-let [ret (arg-env n)]
      ret
      (let [g (garg n)]
        (set! arg-env (assoc arg-env n g))
        g))
    (throw (IllegalStateException. "Arg literal not in #()")))) ;; should never hit this

(declare read-symbol)

(defn- read-arg
  [rdr pct opts pending-forms]
  (if-not (thread-bound? #'arg-env)
    (read-symbol rdr pct)
    (let [ch (peek-char rdr)]
      (cond
        (or (whitespace? ch)
            (macro-terminating? ch)
            (nil? ch))
        (register-arg 1)

        (identical? ch \&)
        (do (read-char rdr)
            (register-arg -1))

        :else
        (let [n (const-val (read* rdr true nil opts pending-forms))]
          (if-not (integer? n)
            (throw (IllegalStateException. "Arg literal must be %, %& or %integer"))
            (register-arg n)))))))

(defn- read-eval
  "Evaluate a reader literal"
  [rdr _ opts pending-forms]
  (when-not *read-eval*
    (reader-error rdr "#= not allowed when *read-eval* is false"))
  (eval (read* rdr true nil opts pending-forms)))

(def ^:private ^:dynamic gensym-env nil)

(defn- read-unquote
  [rdr comma opts pending-forms]
  (if-let [ch (peek-char rdr)]
    (if (identical? \@ ch)
      ((wrapping-reader 'clojure.core/unquote-splicing) (doto rdr read-char) \@ opts pending-forms)
      ((wrapping-reader 'clojure.core/unquote) rdr \~ opts pending-forms))))

(declare syntax-quote*)
(defn- unquote-splicing? [form]
  (and (seq? form)
       (= (first form) 'clojure.core/unquote-splicing)))

(defn- unquote? [form]
  (and (seq? form)
       (= (first form) 'clojure.core/unquote)))

(defn- expand-list
  "Expand a list by resolving its syntax quotes and unquotes"
  [s]
  (loop [s (seq s) r (transient [])]
    (if s
      (let [item (first s)
            ret (conj! r
                       (cond
                        (unquote? item)          (list 'clojure.core/list (second item))
                        (unquote-splicing? item) (second item)
                        :else                    (list 'clojure.core/list (syntax-quote* item))))]
        (recur (next s) ret))
      (seq (persistent! r)))))

(defn- flatten-map
  "Flatten a map into a seq of alternate keys and values"
  [form]
  (loop [s (seq form) key-vals (transient [])]
    (if s
      (let [e (first s)]
        (recur (next s) (-> key-vals
                          (conj! (key e))
                          (conj! (val e)))))
      (seq (persistent! key-vals)))))

(defn- register-gensym [sym]
  (if-not gensym-env
    (throw (IllegalStateException. "Gensym literal not in syntax-quote")))
  (or (get gensym-env sym)
      (let [gs (symbol (str (subs (name sym)
                                  0 (dec (count (name sym))))
                            "__" (RT/nextID) "__auto__"))]
        (set! gensym-env (assoc gensym-env sym gs))
        gs)))

(defn ^:dynamic resolve-symbol
  "Resolve a symbol s into its fully qualified namespace version"
  [s]
  (if (pos? (.indexOf (name s) "."))
    s ;; If there is a period, it is interop
    (if-let [ns-str (namespace s)]
      (let [ns (resolve-ns (symbol ns-str))]
        (if (or (nil? ns)
                (= (ns-name* ns) ns-str)) ;; not an alias
          s
          (symbol (ns-name* ns) (name s))))
      (if-let [o ((ns-map *ns*) s)]
        (if (class? o)
          (symbol (.getName ^Class o))
          (if (var? o)
            (symbol (-> ^Var o .ns ns-name*) (-> ^Var o .sym name))))
        (symbol (ns-name* *ns*) (name s))))))

(defn- add-meta [form ret]
  (if (and (instance? IObj form)
           (seq (dissoc (meta form) :line :column :end-line :end-column :file :source)))
    (list 'clojure.core/with-meta ret (syntax-quote* (meta form)))
    ret))

(defn- syntax-quote-coll [type coll]
  ;; We use sequence rather than seq here to fix http://dev.clojure.org/jira/browse/CLJ-1444
  ;; But because of http://dev.clojure.org/jira/browse/CLJ-1586 we still need to call seq on the form
  (let [res (list 'clojure.core/sequence
                  (list 'clojure.core/seq
                        (cons 'clojure.core/concat
                              (expand-list coll))))]
    (if type
      (list 'clojure.core/apply type res)
      res)))

(defn map-func
  "Decide which map type to use, array-map if less than 16 elements"
  [coll]
  (if (>= (count coll) 16)
    'clojure.core/hash-map
    'clojure.core/array-map))

(defn- syntax-quote* [form]
  (->>
   (cond
    (special-symbol? form) (list 'quote form)

    (symbol? form)
    (list 'quote
          (if (namespace form)
            (let [maybe-class ((ns-map *ns*)
                               (symbol (namespace form)))]
              (if (class? maybe-class)
                (symbol (.getName ^Class maybe-class) (name form))
                (resolve-symbol form)))
            (let [sym (name form)]
              (cond
               (.endsWith sym "#")
               (register-gensym form)

               (.startsWith sym ".")
               form

               (.endsWith sym ".")
               (let [csym (symbol (subs sym 0 (dec (count sym))))]
                 (symbol (.concat (name (resolve-symbol csym)) ".")))
               :else (resolve-symbol form)))))

    (unquote? form) (second form)
    (unquote-splicing? form) (throw (IllegalStateException. "unquote-splice not in list"))

    (coll? form)
    (cond

     (instance? IRecord form) form
     (map? form) (syntax-quote-coll (map-func form) (flatten-map form))
     (vector? form) (list 'clojure.core/vec (syntax-quote-coll nil form))
     (set? form) (syntax-quote-coll 'clojure.core/hash-set form)
     (or (seq? form) (list? form))
     (let [seq (seq form)]
       (if seq
         (syntax-quote-coll nil seq)
         '(clojure.core/list)))

     :else (throw (UnsupportedOperationException. "Unknown Collection type")))

    (or (keyword? form)
        (number? form)
        (char? form)
        (string? form)
        (nil? form)
        (instance? Boolean form)
        (instance? Pattern form))
    form

    :else (list 'quote form))
   (add-meta form)))

(defn- read-syntax-quote
  [rdr backquote opts pending-forms]
  (binding [gensym-env {}]
    (-> (read* rdr true nil opts pending-forms)
      syntax-quote*)))

(defn- read-namespaced-map
  [rdr _ opts pending-forms]
  (let [token (read-token rdr (read-char rdr))]
    (if-let [ns (cond
                  (= token ":")
                  (ns-name *ns*)

                  (= \: (first token))
                  (some-> token (subs 1) parse-symbol second' symbol resolve-ns ns-name)

                  :else
                  (some-> token parse-symbol second'))]

      (let [ch (read-past whitespace? rdr)]
        (if (identical? ch \{)
          (let [items (read-delimited \} rdr opts pending-forms)]
            (when (odd? (count items))
              (reader-error rdr "Map literal must contain an even number of forms"))
            (let [keys (take-nth 2 items)
                  vals (take-nth 2 (rest items))]
              (RT/map (to-array (mapcat list (namespace-keys (str ns) keys) vals)))))
          (reader-error rdr "Namespaced map must specify a map")))
      (reader-error rdr "Invalid token used as namespace in namespaced map: " token))))

(defn- macros [ch]
  (case ch
    \" read-string*
    \: read-keyword
    \; read-comment
    \' (wrapping-reader 'quote)
    \@ (wrapping-reader 'clojure.core/deref)
    \^ read-meta
    \` read-syntax-quote ;;(wrapping-reader 'syntax-quote)
    \~ read-unquote
    \( read-list
    \) read-unmatched-delimiter
    \[ read-vector
    \] read-unmatched-delimiter
    \{ read-map
    \} read-unmatched-delimiter
    \\ read-char*
    \% read-arg
    \# read-dispatch
    nil))

(defn- dispatch-macros [ch]
  (case ch
    \^ read-meta                ;deprecated
    \' (wrapping-reader 'var)
    \( read-fn
    \= read-eval
    \{ read-set
    \< (throwing-reader "Unreadable form")
    \" read-regex
    \! read-comment
    \_ read-discard
    \? read-cond
    \: read-namespaced-map
    nil))

(defn- read-ctor [rdr class-name opts pending-forms]
  (when-not *read-eval*
    (reader-error "Record construction syntax can only be used when *read-eval* == true"))
  (let [class (Class/forName (name class-name) false (RT/baseLoader))
        ch (read-past whitespace? rdr)] ;; differs from clojure
    (if-let [[end-ch form] (case ch
                             \[ [\] :short]
                             \{ [\} :extended]
                             nil)]
      (let [entries (to-array (read-delimited end-ch rdr opts pending-forms))
            numargs (count entries)
            all-ctors (.getConstructors class)
            ctors-num (count all-ctors)]
        (case form
          :short
          (loop [i 0]
            (if (>= i ctors-num)
              (reader-error rdr "Unexpected number of constructor arguments to " (str class)
                            ": got" numargs)
              (if (== (count (.getParameterTypes ^Constructor (aget all-ctors i)))
                      numargs)
                (Reflector/invokeConstructor class entries)
                (recur (inc i)))))
          :extended
          (let [vals (RT/map entries)]
            (loop [s (keys vals)]
              (if s
                (if-not (keyword? (first s))
                  (reader-error rdr "Unreadable ctor form: key must be of type clojure.lang.Keyword")
                  (recur (next s)))))
            (Reflector/invokeStaticMethod class "create" (object-array [vals])))))
      (reader-error rdr "Invalid reader constructor form"))))

(defn- read-tagged [rdr initch opts pending-forms]
  (let [tag (read* rdr true nil opts pending-forms)]
    (if-not (symbol? tag)
      (reader-error rdr "Reader tag must be a symbol"))
    (if *suppress-read*
      (tagged-literal tag (read* rdr true nil opts pending-forms))
      (if-let [f (or (*data-readers* tag)
                     (default-data-readers tag))]
        (f (const-val (read* rdr true nil opts pending-forms)))
        (if (.contains (name tag) ".")
          (read-ctor rdr tag opts pending-forms)
          (if-let [f *default-data-reader-fn*]
            (f tag (read* rdr true nil opts pending-forms))
            (reader-error rdr "No reader function for tag " (name tag))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *read-eval*
  "Defaults to true.

   ***WARNING***
   This setting implies that the full power of the reader is in play,
   including syntax that can cause code to execute. It should never be
   used with untrusted sources. See also: clojure.tools.reader.edn/read.

   When set to logical false in the thread-local binding,
   the eval reader (#=) and *record/type literal syntax* are disabled in read/load.
   Example (will fail): (binding [*read-eval* false] (read-string \"#=(* 2 21)\"))

   When set to :unknown all reads will fail in contexts where *read-eval*
   has not been explicitly bound to either true or false. This setting
   can be a useful diagnostic tool to ensure that all of your reads
   occur in considered contexts."
  true)

(def ^:dynamic *data-readers*
  "Map from reader tag symbols to data reader Vars.
   Reader tags without namespace qualifiers are reserved for Clojure.
   Default reader tags are defined in clojure.tools.reader/default-data-readers
   and may be overridden by binding this Var."
  {})

(def ^:dynamic *default-data-reader-fn*
  "When no data reader is found for a tag and *default-data-reader-fn*
   is non-nil, it will be called with two arguments, the tag and the value.
   If *default-data-reader-fn* is nil (the default value), an exception
   will be thrown for the unknown tag."
  nil)

(def ^:dynamic *suppress-read* false)

(def default-data-readers
  "Default map of data reader functions provided by Clojure.
   May be overridden by binding *data-readers*"
  {'inst #'data-readers/read-instant-date
   'uuid #'data-readers/default-uuid-reader})

(defn ^:private read*
  ([reader eof-error? sentinel opts pending-forms]
     (read* reader eof-error? sentinel nil opts pending-forms))
  ([reader eof-error? sentinel return-on opts pending-forms]
     (when (= :unknown *read-eval*)
       (reader-error "Reading disallowed - *read-eval* bound to :unknown"))
     (try
       (loop []
         (log-source reader
           (if (seq pending-forms)
             (.remove ^List pending-forms 0)
             (let [ch (read-char reader)]
               (cond
                (whitespace? ch) (recur)
                (nil? ch) (if eof-error? (reader-error reader "EOF") sentinel)
                (= ch return-on) READ_FINISHED
                (number-literal? reader ch) (read-number reader ch)
                :else (let [f (macros ch)]
                        (if f
                          (let [res (f reader ch opts pending-forms)]
                            (if (identical? res reader)
                              (recur)
                              res))
                          (read-symbol reader ch))))))))
       (catch Exception e
         (if (ex-info? e)
           (let [d (ex-data e)]
             (if (= :reader-exception (:type d))
               (throw e)
               (throw (ex-info (.getMessage e)
                               (merge {:type :reader-exception}
                                      d
                                      (if (indexing-reader? reader)
                                        {:line   (get-line-number reader)
                                         :column (get-column-number reader)
                                         :file   (get-file-name reader)}))
                               e))))
           (throw (ex-info (.getMessage e)
                           (merge {:type :reader-exception}
                                  (if (indexing-reader? reader)
                                    {:line   (get-line-number reader)
                                     :column (get-column-number reader)
                                     :file   (get-file-name reader)}))
                           e)))))))

(defn read
  "Reads the first object from an IPushbackReader or a java.io.PushbackReader.
   Returns the object read. If EOF, throws if eof-error? is true.
   Otherwise returns sentinel. If no stream is providen, *in* will be used.

   Opts is a persistent map with valid keys:
    :read-cond - :allow to process reader conditionals, or
                 :preserve to keep all branches
    :features - persistent set of feature keywords for reader conditionals
    :eof - on eof, return value unless :eofthrow, then throw.
           if not specified, will throw

   ***WARNING***
   Note that read can execute code (controlled by *read-eval*),
   and as such should be used only with trusted sources.

   To read data structures only, use clojure.tools.reader.edn/read

   Note that the function signature of clojure.tools.reader/read and
   clojure.tools.reader.edn/read is not the same for eof-handling"
  {:arglists '([] [reader] [opts reader] [reader eof-error? eof-value])}
  ([] (read *in* true nil))
  ([reader] (read reader true nil))
  ([{eof :eof :as opts :or {eof :eofthrow}} reader] (read* reader (= eof :eofthrow) eof nil opts (LinkedList.)))
  ([reader eof-error? sentinel] (read* reader eof-error? sentinel nil {} (LinkedList.))))

(defn read-string
  "Reads one object from the string s.
   Returns nil when s is nil or empty.

   ***WARNING***
   Note that read-string can execute code (controlled by *read-eval*),
   and as such should be used only with trusted sources.

   To read data structures only, use clojure.tools.reader.edn/read-string

   Note that the function signature of clojure.tools.reader/read-string and
   clojure.tools.reader.edn/read-string is not the same for eof-handling"
  ([s]
     (read-string {} s))
  ([opts s]
     (when (and s (not (identical? s "")))
       (read opts (string-push-back-reader s)))))

(defmacro syntax-quote
  "Macro equivalent to the syntax-quote reader macro (`)."
  [form]
  (binding [gensym-env {}]
    (syntax-quote* form)))
