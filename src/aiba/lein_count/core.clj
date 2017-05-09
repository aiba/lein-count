(ns aiba.lein-count.core
  (:require [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.walk :as walk])
  (:import clojure.lang.ExceptionInfo))

(defn count-form? [x]
  (not (and (list? x)
            (= (first x) 'comment))))

(defn all-meta [form]
  (let [data (atom [])]
    (walk/prewalk (fn [x]
                    (when (count-form? x)
                      (swap! data conj {:meta (meta x)
                                        :form x})
                      x))
                  form)
    @data))

(defn read-all-forms [f]
  (let [rdr (rt/indexing-push-back-reader (slurp f))
        EOF (Object.)
        opts {:eof EOF}]
    (binding [reader/*alias-map* identity]  ;; don't need accurate alias resolving
      (loop [ret []]
        (let [form (reader/read opts rdr)]
          (if (= EOF form)
            ret
            (recur (conj ret form))))))))

(defn metrics [f]
  (try
    (let [m (->> f (read-all-forms) (mapcat all-meta))]
      {:nodes (count m)
       :lines (->> m
                   (map :meta)
                   (mapcat (juxt :line :end-line))
                   (remove nil?)
                   (distinct)
                   (count))})
    (catch ExceptionInfo e
      {:error (ex-data e)})))

(comment
  (metrics "./src/aiba/lein_count/core.clj")
  (metrics "./test-data/aliased_ns_kw.clj")
  (metrics "./test-data/fn_doc.clj")
  )
