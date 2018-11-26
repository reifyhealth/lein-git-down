(defproject test-project "0.1.0"
  :description "Test project for lein-git-deps"
  :plugins [[lein-git-deps "0.1.0"]]
  :middleware [lein-git-deps.plugin/inject-properties]
  :git-deps {cheshire {:coordinates dakrone/cheshire}
             demo-deps {:coordinates puredanger/demo-deps}}
  :dependencies [[clj-time "66ea91e68583e7ee246d375859414b9a9b7aba57"]  ;; pom based
                 [cheshire "c79ebaa3f56c365a1810f80617c80a3b62999701"]  ;; lein based
                 [demo-deps "19d387dc11d804ab955207a263dfba5dbd15bf2c"] ;; deps based
                 [org.clojure/clojure "1.9.0"]]
  :repositories [["public-github" {:url "git://github.com" :protocol :https}]])
