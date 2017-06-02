(ns aiba.lein-count.core-test
  (:require [aiba.lein-count.core :as lc]
            [clojure.test :as t :refer [deftest testing is]]))

(defn file-metrics [relpath]
  (let [r (lc/metrics [(str "./test-data/" relpath)])]
    (is (= (count r) 1))
    (first r)))

(defmacro def-file-test [file-name expected]
  `(deftest ~(symbol file-name)
     (let [m# (file-metrics ~file-name)]
       (doseq [[k# v#] ~expected]
         (testing (str ~file-name "[" (name k#) "]")
           (is (= (get m# k#) v#)))))))

(def file-test-cases
  {"aliased_ns_kw.clj" {:lines 3 :nodes 10}
   "constants.clj"     {:lines 12 :nodes 20}
   "fn_doc.clj"        {:lines 5 :nodes 13}
   "just_a_ns.cljs"    {:lines 2 :nodes 9}
   "malformed.clj"     {:lines nil :nodes nil}
   "tags.clj"          {:lines 18 :nodes 39}
   "test1.clj"         {:lines 3 :nodes 12}})

(defmacro def-all-file-tests! []
  `(do
     ~@(for [[f m] file-test-cases]
         `(def-file-test ~f ~m))))

(def-all-file-tests!)
