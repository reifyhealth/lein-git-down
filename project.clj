(defproject lein-git-deps "0.1.0"
  :description "A Leiningen plugin for resolving Clojure(Script) dependencies from a Git repository"
  :url "http://github.com/reifyhealth/lein-git-deps"
  :license {:name "MIT"}
  :dependencies [[org.clojure/tools.gitlibs "0.2.64"
                  :exclusions [org.apache.httpcomponents/httpclient
                               org.slf4j/slf4j-api]]
                 [org.clojure/data.xml "0.2.0-alpha5"
                  :exclusions [org.clojure/clojure]]
                 [org.clojure/tools.deps.alpha "0.5.460"
                  :exclusions [*/*]]
                 [leiningen "2.8.1" :scope "provided"]]
  :jar-exclusions [#"bultitude/.*" #"cemerick/.*" #"clojure/tools/.*" #"dynapath/.*" #"leiningen/core/.*"]
  :aot [git-wagon])
