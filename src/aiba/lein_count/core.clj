(ns aiba.lein-count.core
  (:require [clojure.tools.reader :as ctr]
            [clojure.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.walk :refer [prewalk]]))

(defn count-form? [x]
  (not (and (list? x)
            (= (first x) 'comment))))

(defn all-meta [form]
  (let [data (atom [])]
    (prewalk (fn [x]
               (when (count-form? x)
                 (swap! data conj {:meta (meta x)
                                   :form x})
                 x))
             form)
    @data))

(defn read-all-forms [f]
  (let [rdr (indexing-push-back-reader (slurp f))
        EOF (Object.)
        opts {:eof EOF}]
    (loop [ret []]
      (let [form (ctr/read opts rdr)]
        (if (= EOF form)
          ret
          (recur (conj ret form)))))))

(defn metrics [f]
  (let [m (->> f (read-all-forms) (mapcat all-meta))]
    {:nodes (count m)
     :lines (->> m
                 (map :meta)
                 (mapcat (juxt :line :end-line))
                 (remove nil?)
                 (distinct)
                 (count))}))

(comment

  (metrics "./src/aiba/cloc/core.clj")

  )
