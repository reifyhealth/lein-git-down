(ns git-wagon
  (:gen-class
    :name com.reifyhealth.maven.wagon.GitWagon
    :extends org.apache.maven.wagon.AbstractWagon
    :state properties
    :init init
    :constructors {[clojure.lang.IDeref] []})
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.gitlibs :as git]
            [leiningen.core.main :as lein]
            [leiningen.core.project :as project])
  (:import (java.io File FileInputStream)
           (java.security MessageDigest)
           (org.apache.maven.wagon Wagon AbstractWagon TransferFailedException)
           (org.apache.maven.wagon.events TransferEvent)
           (org.apache.maven.wagon.repository Repository)
           (org.apache.maven.wagon.resource Resource)))

;;
;; Helpers
;;

(defn get-property
  ([this k]
   (get-property this k nil))
  ([this k default]
   (get @(.properties this) k default)))

(defn get-in-property
  ([this ks]
   (get-in-property this ks nil))
  ([this ks default]
   (get-in @(.properties this) ks default)))

(defn get-in-property-as-dir
  [this ks default]
  (if-let [d (get-in-property this ks)]
    (if (.startsWith d "/") d (str "/" d))
    default))

(defn put-property
  [this k v]
  (swap! (.properties this) assoc k v))

(defn nth-last
  [n coll]
  (last (drop-last n coll)))

(defn penultimate
  [coll]
  (nth-last 1 coll))

;;
;; Resolve POM
;;

(defmulti resolve-pom! first)

(defmethod resolve-pom! :maven
  [[_ pom]]
  (io/file pom))

(defmethod resolve-pom! :leiningen
  [[_ project]]
  (io/file (lein/apply-task "pom" (project/read (str project)) [])))

(defmethod resolve-pom! :tools-deps
  [[_ deps]]
  ;; TODO!
  (throw (UnsupportedOperationException.)))

(defmethod resolve-pom! :boot
  [[_ build]]
  ;; TODO!
  (throw (UnsupportedOperationException.)))

(defn resolve-default-pom!
  [dep]
  ;; TODO!
  (throw (UnsupportedOperationException.)))

;;
;; Resolve JAR
;;

(defmulti resolve-jar! first)

(defmethod resolve-jar! :leiningen
  [[_ project]]
  (io/file
    (get
      (lein/apply-task "jar" (project/read (str project)) [])
      [:extension "jar"])))

(defmethod resolve-jar! :tools-deps
  [[_ deps]]
  ;; TODO!
  (throw (UnsupportedOperationException.)))

(defmethod resolve-jar! :boot
  [[_ build]]
  ;; TODO!
  (throw (UnsupportedOperationException.)))

(defmethod resolve-jar! :maven
  [[_ build]]
  ;; TODO!
  (throw (UnsupportedOperationException.)))

(defn resolve-default-jar!
  [dep]
  ;; TODO!
  (throw (UnsupportedOperationException.)))

;;
;; Get Resource
;;

(defmulti get-resource!
  #(if (:checksum %) :checksum (-> % :extension keyword)))

(defn file-as-bytes
  [^File f]
  (let [a (byte-array (.length f))]
    (with-open [is (FileInputStream. f)]
      (.read is a)
      a)))

(defn calculate-checksum
  [bytes instance]
  (.toString
    (BigInteger. 1 (.digest instance bytes))
    16))

(defmethod get-resource! :checksum
  [{:keys [destination checksum]}]
  (let [digest-instance (MessageDigest/getInstance (.toUpperCase checksum))]
    (spit destination
          (-> (.getAbsolutePath destination)
              (string/split #"\.sha1")
              first
              (str ".part")
              io/file
              file-as-bytes
              (calculate-checksum digest-instance)))))

(defmethod get-resource! :pom
  [{:keys [destination manifests] :as dep}]
  (let [pom (condp #(find %2 %1) manifests
              :maven      :>> resolve-pom!
              :leiningen  :>> resolve-pom!
              :tools-deps :>> resolve-pom!
              :boot       :>> resolve-pom!
              (resolve-default-pom! dep))]
    (io/copy pom destination)))

(defmethod get-resource! :jar
  [{:keys [destination manifests] :as dep}]
  (let [jar (condp #(find %2 %1) manifests
              :leiningen  :>> resolve-jar!
              :tools-deps :>> resolve-jar!
              :boot       :>> resolve-jar!
              :maven      :>> resolve-jar!
              (resolve-default-jar! dep))]
    (io/copy jar destination)))

;;
;; Extend AbstractWagon
;;

(defn -init
  [d]
  [[] (or d (atom {}))])

(defn -openConnectionInternal
  [this]
  (let [^Repository repo (.getRepository ^Wagon this)
        protocol (if (= :ssh (get-in-property this [:protocols (.getId repo)]))
                   "ssh://git@"
                   "https://")
        host (.getHost repo)
        port (if (pos? (.getPort repo)) (str ":" (.getPort repo)) "")]
    (put-property this :base-uri (str protocol host port))))

(defn -closeConnection [this])

(defn -getIfNewer
  [this resource file version]
  (throw (UnsupportedOperationException. "Get if Newer not supported for Git Wagon")))

(defn -put
  [this file resource]
  (throw (UnsupportedOperationException. "Put not supported for Git Wagon")))

(defn parse-resource
  [resource]
  (let [extensions (string/split resource #"\.")
        [extension
         checksum] (if (#{"pom" "jar"} (penultimate extensions))
                     ((juxt penultimate last) extensions)
                     [(last extensions) nil])
        path-split (string/split resource #"/")
        version    (penultimate path-split)
        group      (string/join "." (drop-last 3 path-split))
        artifact   (nth-last 2 path-split)]
    {:extension  extension
     :checksum   checksum
     :version    version
     :mvn-coords (symbol group artifact)}))

(defn git-uri
  [this mvn-coords]
  (str
    (get-property this :base-uri)
    "/"
    (get-in-property this [:deps mvn-coords :coordinates] mvn-coords)))

(defn get-manifests
  [project-root]
  (set/rename-keys
    (into {}
          (comp (filter (comp #{"pom.xml" "project.clj" "deps.edn" "build.boot"}
                              #(.getName %)))
                (map (juxt #(.getName %) identity)))
          (.listFiles (io/file project-root)))
    {"pom.xml"     :maven
     "project.clj" :leiningen
     "deps.edn"    :tools-deps
     "build.boot"  :boot}))

(defn -get
  [^AbstractWagon this resource-name ^File destination]
  (let [resource (Resource. resource-name)]
    (.fireGetInitiated this resource destination)
    (.fireGetStarted this resource destination)
    (try
      (let [{:keys [mvn-coords version] :as dep} (parse-resource resource-name)
            manifest-root (get-in-property-as-dir
                            this [:deps mvn-coords :manifest-root] "")
            project-root (-> (git-uri this mvn-coords)
                             (git/procure mvn-coords version)
                             (str manifest-root))
            src-root (str project-root
                          (get-in-property-as-dir
                            this [:deps mvn-coords :src-root] "/src"))
            manifests (get-manifests project-root)]
        (-> dep
            (assoc :project-root     project-root
                   :default-src-root src-root
                   :manifests        manifests
                   :destination      destination)
            get-resource!))
      (catch Exception e
        (.fireTransferError this resource e TransferEvent/REQUEST_GET)
        (.printStackTrace e)
        (throw (TransferFailedException. (.getMessage e) e))))
    (.postProcessListeners this resource destination TransferEvent/REQUEST_GET)
    (.fireGetCompleted this resource destination)))
