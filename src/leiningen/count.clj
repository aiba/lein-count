(ns leiningen.count
  (:refer-clojure :exclude [count])
  (:require [aiba.lein-count.core :as lc]
            [leiningen.core.main :refer [info warn]]))

(defn count [project & args]
  (let [dirs (:source-paths project)]
    (info "Examining dirs" (pr-str dirs) "\n")
    (lc/print-report (lc/metrics dirs) {:info info :warn warn})))

(comment
  (def p {:source-paths ["./src"]})
  (count p)
  )
