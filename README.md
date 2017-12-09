# lein-count

Use this instead of `cloc` to count lines of clojure code.

Unlike `cloc` and other tools, lein-count uses [clojure.tools.reader][ctr] to parse
code to decide which lines count. This means `(comment ...)` forms and
`#_(reader-ignored forms)` are not counted.

[Rationale](http://aaroniba.net/counting-clojure-code)

[ctr]: https://github.com/clojure/tools.reader

## Usage

Merge into `~/.lein/profiles.clj`:

```clojure
{:user {:plugins [[lein-count "1.0.8"]]}}
```

Now you can run `lein count` in a project.  Example:

```
$ cd ~/oss/clojurescript
$ lein count
Examining ("src/main/clojure" "src/main/cljs")
Found 62 source files.

|------+-------+---------------+--------|
| Ext  | Files | Lines of Code |  Nodes |
|------+-------+---------------+--------|
| cljs |    26 |         16247 |  84666 |
| cljc |    16 |          9567 |  73249 |
| clj  |    20 |          3843 |  19789 |
| ____ | _____ | _____________ |  _____ |
| SUM: |    62 |         29657 | 177704 |
|------+-------+---------------+--------|
```

### Outside a project

It also works outside a project if you specify which files or directories to be scanned.

```
$ lein count some_file.clj
$ lein count /tmp/dir-of-files
$ lein count :by-file /tmp/dir-of-files
```

### :by-file

You can also use the `:by-file` switch to show individual file counts.

```
$ cd ~/git/lein-count
$ lein count :by-file
Examining ("src")
Found 3 source files.

|------+----------------------------------------------------+---------------+-------|
| Ext  | File                                               | Lines of Code | Nodes |
|------+----------------------------------------------------+---------------+-------|
| clj  | ./src/aiba/lein_count/constant_wrapping_reader.clj |           829 |  4203 |
| clj  | ./src/aiba/lein_count/core.clj                     |           154 |   893 |
| clj  | ./src/leiningen/count.clj                          |            67 |   355 |
| clj  | ./src/aiba/lein_count/utils.clj                    |            30 |   172 |
| ____ | __________________________________________________ | _____________ | _____ |
|      | SUM:                                               |          1080 |  5623 |
|------+----------------------------------------------------+---------------+-------|
```

### Artifacts

`lein count` works on maven artifacts.

```
$ lein count :artifact ring/ring-core 1.6.0
Examining ("/Users/aiba/.m2/repository/ring/ring-core/1.6.0/ring-core-1.6.0.jar")
Found 26 source files.

|------+-------+---------------+-------|
| Ext  | Files | Lines of Code | Nodes |
|------+-------+---------------+-------|
| clj  |    26 |          1537 |  6261 |
| ____ | _____ | _____________ | _____ |
| SUM: |    26 |          1537 |  6261 |
|------+-------+---------------+-------|
```

More examples:

```
$ lein count :by-file :artifact ring/ring-core 1.6.0
$ lein count :artifact reagent 0.6.1
$ lein count :by-file :artifact reagent 0.6.1
```

This is a potentially interesting way to evaluate which libraries to depend on.

## Counting Nodes

You might notice that there is another column called "nodes". This is a potentially
more accurate measure of the "length" of code. Let's see if it's useful.

## Implementation

We use a modified version of [clojure.tools.reader][ctr] to parse the source files.
The modifications make the reader more lenient and also wrap constant values in
metadata so they get counted. Once a file is parsed, we simply count the unique
lines upon which a clojure form starts or ends.

If you find an example where this seems off, please file an github issue.

The node count is just the total number of parsed forms.

## Known Issues

Versions prior to 1.0.8 had a bug that overcounted constant nodes in the syntax tree
by a lot.  See #8.

## License

Source Copyright © 2017 [Aaron Iba](http://aaroniba.net/)

Distributed under the Eclipse Public License, the same as Clojure uses.
