(defproject lein-git-deps "0.1.0"
  :description "A Leiningen plugin for resolving Clojure(Script) dependencies from a Git repository"
  :url "http://github.com/reifyhealth/lein-git-deps"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/tools.gitlibs "0.2.64"]
                 [leiningen "2.8.1" :scope "provided"]]
  :jar-exclusions [#"bultitude/.*" #"cemerick/.*" #"clojure/.*" #"dynapath/.*" #"leiningen/core/.*"]
  :aot [git-wagon])
