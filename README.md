# lein-count

Use this instead of `cloc` to count lines of clojure code.

[Rationale]

This is a leiningen plugin to count lines of clojure code. It also counts "nodes" of
code, which is a potentially better, though less familiar, metric.

Unlike `cloc` and other tools, lein-count uses [clojure.tools.reader][ctr] to
actually parse your code to decide which lines count. This means `(comment ...)`
forms as well as `#_(reader-ignored forms)` are not counted.

[ctr]: https://github.com/clojure/tools.reader





## Usage

FIXME: Use this for user-level plugins:

Put `[lein-count "0.1.0-SNAPSHOT"]` into the `:plugins` vector of your `:user`
profile.

FIXME: Use this for project-level plugins:

Put `[lein-count "0.1.0-SNAPSHOT"]` into the `:plugins` vector of your project.clj.

FIXME: and add an example usage that actually makes sense:

    $ lein count




## Counting artifacts

To run it on an artifact, you can pass an argument.  For example:

```
$ lein succinct :artifact [mount "0.1.11"]

<<insert output here>>

$ lein cloc :artifact [com.stuartsierra/component "0.3.2"]

<<insert output here>>
```






## License

Source Copyright © 2017 [Aaron Iba](http://aaroniba.net/)

Distributed under the Eclipse Public License, the same as Clojure uses.
