(defproject lein-count "1.0.3"
  :description "Counts lines (and nodes) of clojure code"
  :url "https://github.com/aiba/lein-count"
  :license {:name "Eclipse Public License" :url "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in-leiningen true
  :dependencies [[doric "0.9.0"]
                 [org.clojure/tools.reader "1.0.0-beta4" :exclusions [org.clojure/clojure]]])
