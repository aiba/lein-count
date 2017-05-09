(ns aiba.lein-count.core
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.walk :as walk])
  (:import clojure.lang.ExceptionInfo
           java.io.File))

(defn relative-path-str [^File f]
  (-> (.getAbsolutePath f)
      (string/replace (re-pattern (str "^" (System/getProperty "user.dir"))) "")
      (string/replace #"^[\/\.]+" "")))

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

(defn file-metrics [^File f]
  (-> (try
        (let [m (->> f (read-all-forms) (mapcat all-meta))]
          {:ext (-> (.getName f) (string/split #"\.") last)
           :nodes (count m)
           :lines (->> m
                       (map :meta)
                       (mapcat (juxt :line :end-line))
                       (remove nil?)
                       (distinct)
                       (count))})
        (catch ExceptionInfo e
          {:error (ex-data e)}))
      (assoc :file (relative-path-str f))))

(defn metrics [dirs]
  (->> dirs
       (map io/file)
       (mapcat file-seq)
       (filter (fn [f]
                 (and (not (.isDirectory f))
                      (some #(.endsWith (.getName f) %) ["clj" "cljs" "cljc"]))))
       (map file-metrics)))

(defn print-report [ms & [opts]]
  (let [info (get opts :info println)
        warn (get opts :warn (partial println "WARN:"))]
    (info (count ms) "files.")
    (info (with-out-str
            (pprint/print-table [:file :ext :lines :nodes]
                                (sort-by #(get % :nodes -1) > ms))))))

(comment
  (print-report
   (metrics ["./src" "./test-data" "/Users/aiba/git/scratch"]))
  )
