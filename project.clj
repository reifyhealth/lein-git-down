(defproject reifyhealth/lein-git-down "0.3.7"
  :description "A Leiningen plugin for resolving Clojure(Script) dependencies from a Git repository"
  :url "http://github.com/reifyhealth/lein-git-down"
  :license {:name "MIT"}
  :dependencies [[org.clojure/tools.gitlibs "0.2.64"
                  :exclusions [org.apache.httpcomponents/httpclient
                               org.slf4j/slf4j-api]]
                 [leiningen "2.8.1" :scope "provided"]]
  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                    :username :env/clojars_username
                                    :password :env/clojars_password
                                    :sign-releases false}]])
