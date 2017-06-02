(ns aiba.lein-count.utils
  (:require [clojure.string :as string])
  (:import java.io.File))

(defn map-vals [f m]
  (reduce-kv (fn [r k v] (assoc r k (f v))) {} m))

(defn relative-path-str [^File f]
  (let [wd (let [x (System/getProperty "user.dir")]
             (if (string/ends-with? x File/separator)
               x
               (str x File/separator)))
        wd-prefix (re-pattern (str "^" (string/re-quote-replacement wd)))]
    (-> (.getAbsolutePath f)
        (string/replace wd-prefix ""))))

(defn distinct-by-first
  ([f]
   (fn [rf]
     (let [seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [k (f input)]
            (if (contains? @seen k)
              result
              (do (vswap! seen conj k)
                  (rf result input)))))))))
  ([f coll]
   (sequence (distinct-by-first f) coll)))
