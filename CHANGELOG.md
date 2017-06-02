# Change Log

## unreleased

* BUGFIX: previous versions were over-counting constant nodes in the AST.
* Added unit tests.

## 1.0.7

* Better path deduplication.

## 1.0.6

* Non-existent file or directory is nonfatal.  Will just print warning.

## 1.0.4

* Works on Windows.

## 1.0.3

* Fix double-counting of files when the same `:source-path` is specified more than
  once.

## 1.0.2

* Exclude `org.clojure/clojure` from project dependencies.

## 1.0.1

* Fix parsing of artifacts with periods in the name.

## 1.0.0

* Works on artifacts
* :by-file switch
* Fix for namespaced aliased keyword parsing.
* Documentation

## 0.0.1 - 2017-05-09

* Initial version working.
