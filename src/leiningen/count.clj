(ns leiningen.count
  (:refer-clojure :exclude [count])
  (:require [aiba.lein-count.core :as lc]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [leiningen.core.classpath :as lcp]
            [leiningen.core.main :refer [info warn]]
            [leiningen.help :as help])
  (:import clojure.lang.ExceptionInfo))

(defn ^:private all-source-paths [project]
  (when project
    (concat (:source-paths project)
            (when-let [cljsbuild (:cljsbuild project)]
              (mapcat :source-paths (:builds cljsbuild))))))

(defn ^:private artifact-jar [[a b]]
  (when (and a b)
    (try
      (let [project {:repositories [["central" {:url "https://repo1.maven.org/maven2/"
                                                :snapshots false}]
                                    ["clojars" {:url "https://clojars.org/repo/"}]]
                     :dependencies [[(symbol a) b]]}
            jars (lcp/resolve-managed-dependencies
                  :dependencies :managed-dependencies project)
            s (str (string/replace a #"\." "/")
                   "/" b "/"
                   (-> a (string/split #"\/") last)
                   "-" b ".jar")]
        (->> jars
             (filter #(string/ends-with? % s))
             (first)))
      (catch ExceptionInfo e
        (warn "Exception retreiving artifact" [a b])
        (warn (.getMessage e))))))

(defn ^:no-project-needed count
  "Count lines of code.

  USAGE: lein count [:by-file]
         lein count [:by-file] <FILE|DIR>
         lein count [:by-file] :artifact GROUP/ID VERSION"
  [project & args]
  (let [[by-file args] (if (= (first args) ":by-file")
                         [true (rest args)]
                         [false args])
        files-or-dirs  (cond
                         (empty? args)                (all-source-paths project)
                         (= (first args) ":artifact") (when-let [j (artifact-jar (rest args))]
                                                        [j])
                         :else                        args)
        missing        (some #(when-not (.exists (io/file %)) %) files-or-dirs)]
    (cond
      missing                (warn "File or directory not found:" missing)
      (empty? files-or-dirs) (help/help nil "count")
      :else                  (do (info "Examining" (pr-str (map #(lc/relative-path-str (io/file %))
                                                                files-or-dirs)))
                                 (lc/print-report (lc/metrics files-or-dirs)
                                                  {:info    info
                                                   :warn    warn
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

(comment
  (count nil ":artifact" "com.gfredericks/vcr-clj" "0.4.14")
  (count nil ":by-file" ":artifact" "com.gfredericks/vcr-clj" "0.4.143")
  )
