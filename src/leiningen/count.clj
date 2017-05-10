(ns leiningen.count
  (:refer-clojure :exclude [count])
  (:require [aiba.lein-count.core :as lc]
            [clojure.java.io :as io]
            [leiningen.core.main :refer [info warn]]))

(defn ^:private all-source-paths [project]
  (when project
    (concat (:source-paths project)
            (when-let [cljsbuild (:cljsbuild project)]
              (mapcat :source-paths (:builds cljsbuild))))))

(defn ^:no-project-needed count
  "Count lines of code.

  USAGE: lein count [:by-file] <FILE|DIR>"
  [project & args]
  (let [[by-file args] (if (= (first args) ":by-file")
                         [true (rest args)]
                         [false args])
        files-or-dirs (if (seq args)
                        args
                        (all-source-paths project))]
    (if-not files-or-dirs
      (info "USAGE: lein count [:by-file] <FILE|DIR> ...")
      (do (info "Examining" (pr-str (map #(lc/relative-path-str (io/file %))
                                         files-or-dirs)))
          (lc/print-report (lc/metrics files-or-dirs)
                           {:info info
                            :warn warn
                            :by-file by-file})))))

(comment
  (def p {:source-paths ["./src"]})
  (count p)

  (def p {:source-paths ["./src"]
          :cljsbuild {:builds [{:source-paths ["./test-data"]}]}})

  (count p)
  (count p "./src")
  (count p ":by-file")
  (count p ":by-file" "./src")
  (count nil)
  (count nil ":by-file")
  (count nil "./src")
  (count nil ":by-file" "./src")
  (count nil ":by-file" "./test-data/malformed.clj")
  )
