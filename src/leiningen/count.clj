(ns leiningen.count
  (:refer-clojure :rename {count cnt})
  (:require [leiningen.core.main :refer [info]]
            [aiba.lein-count.core :as lc]
            [clojure.java.io :as io]))

(defn source-files [paths]
  (->> paths
       (map io/file)
       (mapcat file-seq)
       (filter (fn [f]
                 (and (not (.isDirectory f))
                      (some #(.endsWith (.getName f) %) ["clj" "cljs" "cljc"]))))))

(defn count [project & args]
  (let [src-paths (:source-paths project)
        src-files (source-files src-paths)]
    (info "Counting lines from" (pr-str src-paths))
    (info (cnt src-files) "files.")
    (doseq [f src-files
            :let [m (lc/metrics f)]]
      (info (.getName f) (pr-str m)))))

(comment
  (def p {:source-paths ["./src"]})
  (count p)
  )
