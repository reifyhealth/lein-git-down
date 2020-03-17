(ns lein-git-down.plugin-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest testing is]]
            [clojure.tools.gitlibs :as git]
            [leiningen.core.main :as lein])
  (:import (java.util.concurrent TimeUnit)
           (java.util Scanner)
           (java.io File)))

(def m2-root
  (->> (string/split (System/getProperty "java.class.path")
                     (re-pattern File/pathSeparator))
       (some #(when (.contains % ".m2") %))
       (re-find #"/.*/\.m2/repository")
       io/file))

(def deps-root
  (io/file (git/cache-dir)))

(def pomegranate-path
  "com/cemerick/pomegranate/a8d0ef79d6cbbd9392dbe7f824e32dc60d46e0c0")

(def cheshire-path
  "cheshire/cheshire/c79ebaa3f56c365a1810f80617c80a3b62999701")

(def demo-deps-path
  "demo-deps/demo-deps/19d387dc11d804ab955207a263dfba5dbd15bf2c")

(def fc4-framework-path
  "FundingCircle/fc4-framework/c0a9777d3bb908651a0fd4f3dd151277fa10ff93")

(def clj-time-path
  "clj-time/clj-time/66ea91e68583e7ee246d375859414b9a9b7aba57")

(def cljfmt-path
  "cljfmt/cljfmt/806e43b7a7d4e22b831d796f107f135d8efc986a")

(def test-project-path
  "test-project/target")

(defn clean-up-artifacts!
  "Deletes all gitlibs and m2 artifacts from the local filesystem to prepare
  for testing"
  []
  (doseq [path [pomegranate-path
                cheshire-path
                demo-deps-path
                fc4-framework-path
                clj-time-path
                cljfmt-path
                test-project-path]
          root [m2-root deps-root]]
    (let [dir (io/file root path)]
      (->> (file-seq dir)
           reverse
           (run! #(when (.exists %) (.delete %)))))))

(defn run-command!
  [cmd ^File run-in-dir]
  (let [process (.. (ProcessBuilder. (into-array String cmd))
                    (directory run-in-dir)
                    (redirectErrorStream true)
                    start)
        result  (StringBuilder.)]
    (with-open [scanner (Scanner. (.getInputStream process))]
      (while (.hasNextLine scanner)
        (.append result (.nextLine scanner))
        (.append result "\n")))
    (.waitFor process 60000 TimeUnit/MILLISECONDS)
    (lein/info (str result))))

(defn get-pomegranate-jar
  []
  (io/file m2-root
           pomegranate-path
           "pomegranate-a8d0ef79d6cbbd9392dbe7f824e32dc60d46e0c0.jar"))

(defn get-cheshire-jar
  []
  (io/file m2-root
           cheshire-path
           "cheshire-c79ebaa3f56c365a1810f80617c80a3b62999701.jar"))

(defn get-demo-deps-jar
  []
  (io/file m2-root
           demo-deps-path
           "demo-deps-19d387dc11d804ab955207a263dfba5dbd15bf2c.jar"))

(defn get-fc4-framework-jar
  []
  (io/file m2-root
           fc4-framework-path
           "fc4-framework-c0a9777d3bb908651a0fd4f3dd151277fa10ff93.jar"))

(defn get-clj-time-jar
  []
  (io/file m2-root
           clj-time-path
           "clj-time-66ea91e68583e7ee246d375859414b9a9b7aba57.jar"))

(defn get-test-project-jar
  []
  (io/file "test-project/target/test-project-0.1.0.jar"))

(deftest resolve-git-deps
  (testing "Resolution of git dependencies"

    (testing "from scratch"
      (lein/info "Cleaning up test artifacts for re-import...")
      (clean-up-artifacts!)
      (lein/info "Running `lein jar` in `test-project`...")
      (run-command! ["lein" "jar"] (io/file "test-project"))

      (testing "for pom based project"
        (let [^File jar (get-pomegranate-jar)]
          (is (.exists jar))))

      (testing "for lein based project"
        (let [^File jar (get-cheshire-jar)]
          (is (.exists jar))))

      (testing "for deps based project"
        (let [^File jar (get-demo-deps-jar)]
          (is (.exists jar))))

      (testing "for deps projects with transitive git deps"
        (let [^File jar (get-fc4-framework-jar)]
          (is (.exists jar))))

      (testing "for multiple project"
        (let [^File jar (get-clj-time-jar)]
          (is (.exists jar))))

      (testing "for the test project jar"
        (let [^File jar (get-test-project-jar)]
          (is (.exists jar)))))

    (testing "without cleaning"
      (lein/info "Running `lein jar` in `test-project`...")
      (run-command! ["lein" "jar"] (io/file "test-project"))

      (testing "for pom based project"
        (let [^File jar (get-pomegranate-jar)]
          (is (.exists jar))))

      (testing "for lein based project"
        (let [^File jar (get-cheshire-jar)]
          (is (.exists jar))))

      (testing "for deps based project"
        (let [^File jar (get-demo-deps-jar)]
          (is (.exists jar))))

      (testing "for deps projects with transitive git deps"
        (let [^File jar (get-fc4-framework-jar)]
          (is (.exists jar))))

      (testing "for multiple project"
        (let [^File jar (get-clj-time-jar)]
          (is (.exists jar))))

      (testing "for the test project jar"
        (let [^File jar (get-test-project-jar)]
          (is (.exists jar)))))))
