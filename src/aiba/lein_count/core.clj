(ns aiba.lein-count.core
  (:require [aiba.lein-count.constant-wrapping-reader :as reader]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.walk :as walk]
            [doric.core :as doric])
  (:import clojure.lang.ExceptionInfo
           java.io.File))

;; Utils ———————————————————————————————————————————————————————————————————————————

(defmacro returning [x & body]
  `(do ~@body ~x))

(defn map-vals [f m]
  (reduce-kv (fn [r k v] (assoc r k (f v))) {} m))

(defn relative-path-str [^File f]
  (let [wd (let [x (System/getProperty "user.dir")]
             (if (string/ends-with? x "/")
               x
               (str x "/")))]
    (-> (.getAbsolutePath f)
        (string/replace (re-pattern (str "^" wd)) ""))))

;; Analyzing code ——————————————————————————————————————————————————————————————————

(defn count-form? [x]
  (not (and (list? x)
            (= (first x) 'comment))))

(defn all-meta [form]
  (let [data (atom [])]
    (walk/prewalk (fn [x]
                    (when (count-form? x)
                      (returning x
                        (swap! data conj (if (reader/constant? x)
                                           {:form (:value x)
                                            :meta (:loc-info x)}
                                           {:form x
                                            :meta (meta x)})))))
                  form)
    @data))

(defn read-all-forms [^String s]
  (let [rdr (rt/indexing-push-back-reader s)
        EOF (Object.)
        opts {:eof EOF
              :read-cond :allow
              :features #{:clj :cljs :cljr}}]
    (binding [reader/*alias-map* identity ;; don't need accurate alias resolving
              reader/*default-data-reader-fn* (fn [tag x] x)
              reader/*wrap-constants* true]
      (loop [ret []]
        (let [form (reader/read opts rdr)]
          (if (= EOF form)
            ret
            (recur (conj ret form))))))))

(defn file-metrics [^File f]
  (-> (try
        (let [m (->> f (slurp) (read-all-forms) (mapcat all-meta))]
          {:ext (-> (.getName f) (string/split #"\.") last)
           :nodes (count m)
           :lines (->> m
                       (map :meta)
                       (mapcat (juxt :line :end-line))
                       (remove nil?)
                       (distinct)
                       (count))})
        (catch ExceptionInfo e
          {:error e}))
      (assoc :file (relative-path-str f))))

(defn metrics [files-or-dirs]
  (->> files-or-dirs
       (map io/file)
       (mapcat file-seq)
       (filter (fn [f]
                 (and (not (.isDirectory f))
                      (some #(.endsWith (.getName f) %) ["clj" "cljs" "cljc"]))))
       (map file-metrics)))

;; Generating ASCII report —————————————————————————————————————————————————————————

(def columns [{:name :ext   :align :right}
              {:name :file  :align :left}
              {:name :files :align :right}
              {:name :lines :align :right :title "Lines of Code"}
              {:name :nodes :align :right}])

(defn ascii-table [rows]
  (let [ks (-> rows first keys set)]
    (doric/table (filter #(contains? ks (:name %)) columns)
                 rows)))

(defn dash-row [rows]
  (reduce (fn [ret k]
            (let [col (->> columns (filter #(= (:name %) k)) first)]
              (assoc ret k (apply str
                                  (repeat (->> rows
                                               (map #(count (str (get % k))))
                                               (apply max
                                                      4
                                                      (count (name (:name col)))
                                                      (count (get col :title ""))))
                                          "_")))))
          {}
          (keys (first rows))))

(defn table-by-ext [fms]
  (let [by-ext (->> fms
                    (group-by :ext)
                    (map-vals (fn [ms]
                                (as-> ms $
                                  (map #(dissoc % :ext :file) $)
                                  (apply merge-with + $)
                                  (assoc $ :files (count ms)))))
                    (seq)
                    (map #(assoc (val %) :ext (key %))))
        totals (assoc (->> by-ext (map #(dissoc % :ext)) (apply merge-with +))
                      :ext "SUM:")]
    (ascii-table (concat (sort-by #(get % :nodes -1) > by-ext)
                         [(dash-row by-ext)]
                         [totals]))))

(defn table-by-file [fms]
  (let [totals (assoc (->> fms (map #(dissoc % :ext :file)) (apply merge-with +))
                      :file "SUM:")]
    (ascii-table (concat (sort-by #(get % :nodes -1) > fms)
                         [(dash-row fms)]
                         [totals]))))

(defn print-report [ms & [opts]]
  (let [info (get opts :info println)
        warn (get opts :warn println)
        errs (->> ms
                  (filter :error)
                  (map (fn [x] (merge (ex-data (:error x))
                                     x
                                     {:error (.getMessage (:error x))}))))
        fms (remove :error ms)]
    (info "Found" (count ms) "source files.")
    (when (seq errs)
      (warn "Encountered" (count errs) "reader errors:")
      (doseq [e errs]
        (warn (pr-str e))))
    (when (seq fms)
      (info "")
      (info (if (:by-file opts)
              (table-by-file fms)
              (table-by-ext fms))))))

;; Testing —————————————————————————————————————————————————————————————————————————

(comment

  (print-report (metrics ["/Users/aiba/git/gambit/proj/src"]))

  (print-report (metrics ["/Users/aiba/git"]))

  (print-report (metrics ["./src" "./test-data"])
                {:by-file true})

  (print-report (metrics ["./src" "./test-data" "/Users/aiba/git/scratch"])
                {:by-file true})

  (print-report (metrics ["./test-data/tags.clj"]))
  (print-report (metrics ["./test-data/constants.clj"]))

  (let [f (io/file "./test-data/test1.clj")
        m (mapcat all-meta
                  (read-all-forms (slurp f)))]
    (->> m
         (map :meta)
         (remove nil?)
         (map (juxt :line :end-line))
         ))

  )
