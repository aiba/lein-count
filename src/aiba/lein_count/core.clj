(ns aiba.lein-count.core
  (:require [aiba.lein-count.constant-wrapping-reader :as reader]
            [aiba.lein-count.utils
             :refer
             [distinct-by-first map-vals relative-path-str returning]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.walk :as walk]
            [doric.core :as doric])
  (:import clojure.lang.ExceptionInfo
           java.util.jar.JarFile))

;; Analyzing code ——————————————————————————————————————————————————————————————————

(defn count-form? [x]
  (not (and (list? x)
            (= (first x) 'comment))))

(defn all-meta [form]
  (let [data (atom [])]
    (walk/prewalk (fn [x]
                    (when (count-form? x)
                      (if (reader/constant? x)
                        (do (swap! data conj {:form (:value x)
                                              :meta (:loc-info x)})
                            (:value x))
                        (do (swap! data conj {:form x
                                              :meta (meta x)})
                            x))))
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
              reader/*wrap-constants* true
              reader/*read-eval* :skip]
      (loop [ret []]
        (let [form (reader/read opts rdr)]
          (if (= EOF form)
            ret
            (recur (conj ret form))))))))

(defn file-metrics [data]
  (-> (try
        (let [m (->> (:content data) (read-all-forms) (mapcat all-meta))]
          {:ext (-> (:path data) (string/split #"\.") last)
           :nodes (count m)
           :lines (->> m
                       (map :meta)
                       (mapcat (juxt :line :end-line))
                       (remove nil?)
                       (distinct)
                       (count))})
        (catch ExceptionInfo e
          {:error e}))
      (assoc :path (:path data))))

(defn source-path? [^String p]
  (boolean
   (some #(string/ends-with? p %) ["clj" "cljs" "cljc"])))

(defn read-files [path]
  (let [f (io/file path)]
    (assert (.exists f) (str "Doesn't exist: " path))
    (cond
      (.isDirectory f)
      (->> (file-seq f)
           (filter (fn [f]
                     (and (.isFile f) (source-path? (.getName f)))))
           (map (fn [f]
                  {:path (relative-path-str f)
                   :content (slurp f)})))

      (string/ends-with? (.getName f) ".jar")
      (let [jf (JarFile. f)]
        (->> (.entries jf)
             (iterator-seq)
             (map (fn [e]
                    (let [path (.getName e)]
                      (when (and (not (.isDirectory e))
                                 (source-path? path)
                                 (not (string/starts-with? path "META-INF/"))
                                 (not (= path "project.clj")))
                        {:path path
                         :content (slurp (.getInputStream jf e))}))))
             (remove nil?)))

      ;; This file was specifically asked for so read it regardless of extension
      :else
      [{:path (relative-path-str f)
        :content (slurp f)}])))

;; each path is a string pointing to either a file, a dir, or a jar
(defn metrics [paths]
  (->> paths
       (mapcat read-files)
       (distinct-by-first :path)
       (map file-metrics)))

;; Generating ASCII report —————————————————————————————————————————————————————————

(def columns [{:name :ext   :align :left}
              {:name :path  :align :left :title "File"}
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
                                  (map #(dissoc % :ext :path) $)
                                  (apply merge-with + $)
                                  (assoc $ :files (count ms)))))
                    (seq)
                    (map #(assoc (val %) :ext (key %))))
        totals (assoc (->> by-ext (map #(dissoc % :ext)) (apply merge-with +))
                      :ext "SUM:")]
    (ascii-table (concat (sort-by #(get % :lines -1) > by-ext)
                         [(dash-row by-ext)]
                         [totals]))))

(defn table-by-file [fms]
  (let [totals (assoc (->> fms (map #(dissoc % :ext :path)) (apply merge-with +))
                      :path "SUM:")]
    (ascii-table (concat (sort-by #(get % :lines -1) > fms)
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

  (print-report (metrics ["/Users/aiba/git"]))

  (print-report (metrics ["/Users/aiba/oss/clojurescript/src/main"]))
  (print-report (metrics ["/Users/aiba/oss/clojure"]))

  (print-report (metrics ["/Users/aiba/oss"]))

  (print-report (metrics ["./src" "./test-data"])
                {:by-file true})

  (print-report (metrics ["./src" "./test-data"]))

  (= (metrics ["/tmp/re-frame-realword-example-app/src"])
     (metrics (repeat 4 "/tmp/re-frame-realword-example-app/src")))
  )
