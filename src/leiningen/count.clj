(ns leiningen.count
  (:refer-clojure :exclude [count])
  (:require [aiba.lein-count.core :as lc]
            [clojure.java.io :as io]
            [leiningen.core.main :refer [info warn]]))

(defn count [project & args]
  (let [dirs (concat (:source-paths project)
                     (when-let [cljsbuild (:cljsbuild project)]
                       (mapcat :source-paths (:builds cljsbuild))))]
    (info "Examining dirs" (pr-str (map #(lc/relative-path-str (io/file %)) dirs)))
    (lc/print-report (lc/metrics dirs) {:info info :warn warn})))

(comment
  (def p {:source-paths ["./src"]})
  (count p)

  (def p {:source-paths ["./src"]
          :cljsbuild {:builds [{:source-paths ["./test-data"]}]}})
  (count p)
  )
