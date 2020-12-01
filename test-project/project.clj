(def version (-> (slurp "../project.clj") read-string (nth 2)))

(defproject test-project "0.1.0"
  :description "Test project for lein-git-down"
  :plugins [[reifyhealth/lein-git-down #=(eval version)]]
  :middleware [lein-git-down.plugin/inject-properties]
  :git-down {cheshire {:coordinates dakrone/cheshire}
             demo-deps {:coordinates puredanger/demo-deps}
             com.cemerick/pomegranate {:coordinates cemerick/pomegranate}
             cljfmt {:coordinates weavejester/cljfmt :manifest-root "cljfmt"}}
  :dependencies [[com.cemerick/pomegranate "a8d0ef79d6cbbd9392dbe7f824e32dc60d46e0c0"]   ;; pom based
                 [cheshire "c79ebaa3f56c365a1810f80617c80a3b62999701"]                   ;; lein based
                 [demo-deps "19d387dc11d804ab955207a263dfba5dbd15bf2c"]                  ;; deps based
                 [FundingCircle/fc4-framework "c0a9777d3bb908651a0fd4f3dd151277fa10ff93" ;; deps w/ transitive git deps
                  ;; excluding since the build requires `clojure` executable
                  ;; which is not on our CI container
                  :exclusions [clj-chrome-devtools]]
                 [clj-time "66ea91e68583e7ee246d375859414b9a9b7aba57"]                   ;; multiple
                 [cljfmt "806e43b7a7d4e22b831d796f107f135d8efc986a"]                     ;; contains hooks
                 [org.clojure/clojure "1.9.0"]]
  :repositories [["public-github" {:url "git://github.com" :protocol :https}]])
