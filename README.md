# lein-count

Use this instead of `cloc` to count lines of clojure code.

This is a [leiningen] plugin to count lines of clojure code. It also counts "nodes" of
code, which is a potentially better, though less familiar, metric.

[leiningen]: https://leiningen.org/

Unlike `cloc` and other tools, lein-count uses [clojure.tools.reader][ctr] to
actually parse your code to decide which lines count. This means `(comment ...)`
forms as well as `#_(reader-ignored forms)` are not counted.

[ctr]: https://github.com/clojure/tools.reader

## Usage

Add to `[aiba/lein-count "1.0.0"]` to the `:plugins` of a project or to your user profile.  I suggest adding to `~/.lein/profiles.clj`, since it's capable of running outside a project context.  So `lein count` becomes a system-wide tool.

Merge into `~/.lein/profiles.clj`:

```clojure
{:user {:plugins [[aiba/lein-count "1.0.0"]]}}
```

Now you can simply run `lein count` in a project.  Example:

```bash
$ cd ~/oss/clojurescript
$ lein count
Examining ("src/main/clojure" "src/main/cljs")
Found 62 source files.

|------+-------+---------------+--------|
| Ext  | Files | Lines of Code |  Nodes |
|------+-------+---------------+--------|
| cljs |    26 |         16245 | 202780 |
| cljc |    16 |          9535 | 171054 |
| clj  |    20 |          3838 |  56018 |
| ____ | _____ | _____________ | ______ |
| SUM: |    62 |         29618 | 429852 |
|------+-------+---------------+--------|
```

### :by-file

You can also use the `:by-file` switch to show individual file counts.

```
$ lein count :by-file
```

### Outside project context

You can specify any number of files or directories to be scanned for source files.  This works inside or outside a project context.

Examples:

```
$ lein count some_file.clj
$ lein count /tmp/dir-of-files
$ lein count :by-file /tmp/dir-of-files
```

### Artfiacts

Finally, `lein count` can take any maven artifact and examine it.

```
$ lein count :artifact ring/ring-core 1.6.0
$ lein count :by-file :artifact ring/ring-core 1.6.0
$ lein count :artifact reagent 0.6.1
$ lein count :by-file :artifact reagent 0.6.1
```

This is a potentially interesting way to evaluate which libraries to depend on.

## Implementation

## Known Issues


## License

Source Copyright © 2017 [Aaron Iba](http://aaroniba.net/)

Distributed under the Eclipse Public License, the same as Clojure uses.
