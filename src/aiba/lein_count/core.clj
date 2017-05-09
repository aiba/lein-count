(ns aiba.lein-count.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.walk :as walk]
            [doric.core :as doric])
  (:import clojure.lang.ExceptionInfo
           java.io.File))

;; Utils ———————————————————————————————————————————————————————————————————————————

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
          {:error (dissoc (ex-data e) :file)}))
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

(defn dash-row [rows]
  (reduce (fn [ret k]
            (assoc ret k (apply str
                                (repeat (->> rows
                                             (map #(count (str (get % k))))
                                             (apply max 4 (count (name k))))
                                        "_"))))
          {}
          (keys (first rows))))

(defn table-by-ext [fms]
  (let [by-ext (tally-by-ext fms)
        totals (assoc (->> by-ext (map #(dissoc % :ext)) (apply merge-with +))
                      :ext "SUM:")]
    (doric/table [{:name :ext   :align :right}
                  {:name :files :align :right}
                  {:name :lines :align :right}
                  {:name :nodes :align :right}]
                 (concat (sort-by #(get % :nodes -1) > by-ext)
                         [(dash-row by-ext)]
                         [totals]))))

(defn table-by-file [fms]
  (let [totals (assoc (->> fms (map #(dissoc % :ext :file)) (apply merge-with +))
                      :file "SUM:")]
    (doric/table [{:name :file  :align :left}
                  {:name :lines :align :right}
                  {:name :nodes :align :right}]
                 (concat (sort-by #(get % :nodes -1) > fms)
                         [(dash-row fms)]
                         [totals]))))

(defn print-report [ms & [opts]]
  (let [info (get opts :info println)
        warn (get opts :warn println)
        errs (->> ms
                  (filter :error)
                  (map (fn [x] (assoc (:error x) :file (:file x)))))
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

  (print-report (metrics ["./src" "./test-data" "/Users/aiba/git/scratch"])
                {})

  (print-report (metrics ["./src" "./test-data" "/Users/aiba/git/scratch"])
                {:by-file true})

  )
