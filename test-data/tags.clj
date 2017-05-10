(ns data.tags)

(def x #js {:a 1
            :b 2
            :c 3
            :d 4})

#another-tag [1
              2
              3
              4
              5
              (fn [x]
                (+ x
                   1
                   :foo
                   :bar
                   [:baz]
                   3
                   4))]
