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

(defn path-matches-artifact? [p [artifact version]]
  (let [s (let [v (string/split artifact #"\/")
                [group id] (case (clojure.core/count v)
                             1 [nil (first v)]
                             2 v
                             (throw (ex-info "Malformed artifact" {:artifact artifact})))]
            (str "/"
                 (when group
                   (str (string/replace group #"\." "/")
                        "/"))
                 id "/" version "/"
                 id "-" version ".jar"))]
    (string/ends-with? p s)))

(defn ^:private artifact-jar [[a b]]
  (when (and a b)
    (try
      (let [project {:repositories [["central" {:url "https://repo1.maven.org/maven2/"
                                                :snapshots false}]
                                    ["clojars" {:url "https://clojars.org/repo/"}]]
                     :dependencies [[(symbol a) b]]}
            jars (lcp/resolve-managed-dependencies
                  :dependencies :managed-dependencies project)]
        (->> jars
             (filter #(path-matches-artifact? % [a b]))
             (first)))
      (catch ExceptionInfo e
        (warn "Exception retreiving artifact" [a b])
        (warn (.getMessage e))))))

(defn all-files-or-dirs [project args]
  (cond
    (empty? args)                (all-source-paths project)
    (= (first args) ":artifact") (when-let [j (artifact-jar (rest args))]
                                   [j])
    :else                        args))

(defn ^:no-project-needed count
  "Count lines of code.

  USAGE: lein count [:by-file]
         lein count [:by-file] <FILE|DIR>
         lein count [:by-file] :artifact GROUP/ID VERSION"
  [project & args]
  (let [[by-file args] (if (= (first args) ":by-file")
                         [true (rest args)]
                         [false args])
        files-or-dirs  (->> (all-files-or-dirs project args)
                            (distinct)
                            (filter (fn [f]
                                      (or (.exists (io/file f))
                                          (do (warn "Skipping non-existent file or directory:" f)
                                              false)))))]
    (info "Examining" (pr-str (map #(lc/relative-path-str (io/file %))
                                   files-or-dirs)))
    (lc/print-report (lc/metrics files-or-dirs)
                     {:info    info
                      :warn    warn
                      :by-file by-file})))

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
  (count nil "/tmp/doesnt-exist")
  (count nil "/tmp/doesnt-exist" "./src")
  (count nil "/tmp/doesnt-exist" "./src" "./src" "./src")
  )

(comment
  (count nil ":artifact" "com.gfredericks/vcr-clj" "0.4.14")
  (count nil ":by-file" ":artifact" "com.gfredericks/vcr-clj" "0.4.143")
  (count nil ":artifact" "org.clojure/core.async" "0.3.442")
  (count nil ":artifact" "ring/ring-core" "1.6.0")
  (count nil ":artifact" "medley" "1.0.0")
  (count nil ":artifact" "lein-count" "1.0.0")
  (count nil ":artifact" "lein-count" "1.0.1")
  (count nil ":artifact" "lein-count" "1.0.2")
  (count nil ":artifact" "lein-count" "1.0.3")
  (count nil ":artifact" "lein-count" "1.0.4")
  )
