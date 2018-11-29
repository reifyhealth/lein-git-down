(defproject lein-git-deps "0.1.0-alpha"
  :description "A Leiningen plugin for resolving Clojure(Script) dependencies from a Git repository"
  :url "http://github.com/reifyhealth/lein-git-deps"
  :license {:name "MIT"}
  :dependencies [[org.clojure/tools.gitlibs "0.2.64"
                  :exclusions [org.apache.httpcomponents/httpclient
                               org.slf4j/slf4j-api]]
                 [leiningen "2.8.1" :scope "provided"]])
