(def version (-> (slurp "../project.clj") read-string (nth 2)))

(defproject test-project "0.1.0"
  :description "Test project for lein-git-down"
  :plugins [[reifyhealth/lein-git-down #=(eval version)]]
  :middleware [lein-git-down.plugin/inject-properties]
  :git-down {cheshire {:coordinates dakrone/cheshire}
             demo-deps {:coordinates puredanger/demo-deps}
             com.cemerick/pomegranate {:coordinates cemerick/pomegranate}}
  :dependencies [[com.cemerick/pomegranate "a8d0ef79d6cbbd9392dbe7f824e32dc60d46e0c0"] ;; pom based
                 [cheshire "c79ebaa3f56c365a1810f80617c80a3b62999701"]                 ;; lein based
                 [demo-deps "19d387dc11d804ab955207a263dfba5dbd15bf2c"]                ;; deps based
                 [clj-time "66ea91e68583e7ee246d375859414b9a9b7aba57"]                 ;; multiple
                 [org.clojure/clojure "1.9.0"]]
  :repositories [["public-github" {:url "git://github.com" :protocol :https}]])
