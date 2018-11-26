(ns git-wagon
  (:gen-class
    :name com.reifyhealth.maven.wagon.GitWagon
    :extends org.apache.maven.wagon.AbstractWagon
    :state properties
    :init init
    :constructors {[clojure.lang.IDeref] []})
  (:require [clojure.data.xml :as xml]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.deps.alpha.gen.pom :as tools-deps-pom]
            [clojure.tools.gitlibs :as git]
            [leiningen.core.main :as lein]
            [leiningen.core.project :as project])
  (:import (java.io File FileInputStream)
           (java.nio.file Files Paths StandardCopyOption CopyOption)
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
  [[_ ^File deps]]
  (let [proj-name (.. deps getParentFile getParentFile getName)
        temp-pom  (io/file (.getParentFile deps) proj-name "pom.xml")]
    (io/make-parents temp-pom)
    (tools-deps-pom/sync-pom
      (edn/read-string (slurp deps))
      (.getParentFile temp-pom))
    (.toFile
      (Files/move
        (Paths/get (.toURI temp-pom))
        (Paths/get (.toURI (io/file (.getParent deps) "pom.xml")))
        (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])))))

(xml/alias-uri 'pom "http://maven.apache.org/POM/4.0.0")

(defn resolve-default-pom!
  [{:keys [mvn-coords version project-root]}]
  (lein/warn
    (str  "Could not find known build tooling so generating simple pom. "
          "Transitive dependencies will NOT be resolved. "
          "(Hint: supported manifests: project.clj, pom.xml, and deps.edn)"))
  (let [pom (xml/sexp-as-element
              [::pom/project
               {:xmlns "http://maven.apache.org/POM/4.0.0"
                (keyword "xmlns:xsi") "http://www.w3.org/2001/XMLSchema-instance"
                (keyword "xsi:schemaLocation") "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"}
               [::pom/modelVersion "4.0.0"]
               [::pom/groupId (namespace mvn-coords)]
               [::pom/artifactId (name mvn-coords)]
               [::pom/version version]
               [::pom/name (name mvn-coords)]])
        pom-file (io/file project-root "pom.xml")]
    (spit pom-file (xml/indent-str pom))
    pom-file))

;;
;; Resolve JAR
;;

(defn lein-jar
  [project]
  (io/file
    (get
      (lein/apply-task "jar" (project/read (str project)) [])
      [:extension "jar"])))

(defn gen-project
  [{:keys [name group version source-paths resource-paths]} ^File destination]
  (spit destination
    (cond-> ()
            (not-empty resource-paths) (conj resource-paths :resource-paths)
            (not-empty source-paths)   (conj source-paths :source-paths)
            true (conj version (symbol group name) 'defproject)))
  destination)

(declare parse-content*)

(defn parse-element*
  [element]
  (hash-map
     (-> element :tag name keyword)
     (-> element :content parse-content*)))

(defn parse-content*
  [content]
  (if (= 1 (count content))
     (first content)
     (->> content
          (filter map?)
          (map parse-element*)
          (apply merge-with
                 #(cond (vector? %1) (conj %1 %2)
                        (not (nil? %1)) (vector %1 %2)
                        :default %2)))))

(defn parse-pom
  [^File pom-file]
  (let [{{:keys [artifactId groupId version build]} :project}
        (->> (io/reader pom-file)
             xml/parse
             parse-element*)
        source-directory (or (:sourceDirectory build) "src")
        resource-paths (mapv :directory (get-in build [:resources :resource]))]
    (cond-> {:name artifactId
             :group groupId
             :version version
             :source-paths [source-directory]}
            resource-paths (assoc :resource-paths resource-paths))))

(defmulti resolve-jar! first)

(defmethod resolve-jar! :leiningen
  [[_ project]]
  (lein-jar project))

(defmethod resolve-jar! :tools-deps
  [[_ deps]]
  (-> (.getParentFile deps)
      (io/file "pom.xml")
      parse-pom
      (gen-project (io/file (.getParentFile deps) "project.clj"))
      lein-jar))

(defmethod resolve-jar! :maven
  [[_ pom]]
  (-> pom
      parse-pom
      (gen-project (io/file (.getParentFile pom) "project.clj"))
      lein-jar))

(defn resolve-default-jar!
  [{:keys [mvn-coords
           version
           project-root
           default-src-root
           default-resource-root]}]
  (-> {:name (name mvn-coords)
       :group (namespace mvn-coords)
       :version version
       :source-paths [default-src-root]
       :resource-paths [default-resource-root]}
      (gen-project (io/file project-root "project.clj"))
      lein-jar))

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
              (resolve-default-pom! dep))]
    (io/copy pom destination)))

(defmethod get-resource! :jar
  [{:keys [destination manifests] :as dep}]
  (let [jar (condp #(find %2 %1) manifests
              :leiningen  :>> resolve-jar!
              :maven      :>> resolve-jar!
              :tools-deps :>> resolve-jar!
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
          (comp (filter (comp #{"pom.xml" "project.clj" "deps.edn"}
                              #(.getName %)))
                (map (juxt #(.getName %) identity)))
          (.listFiles (io/file project-root)))
    {"pom.xml"     :maven
     "project.clj" :leiningen
     "deps.edn"    :tools-deps}))

(defn normalize-version
  [uri version]
  (or (git/resolve uri version)
      (throw (Exception.
               (format
                 "Could not resolve version '%s' as valid rev in repository"
                 version)))))

(defn -get
  [^AbstractWagon this resource-name ^File destination]
  (let [resource (Resource. resource-name)]
    (.fireGetInitiated this resource destination)
    (.fireGetStarted this resource destination)
    (try
      (let [{:keys [mvn-coords version] :as dep} (parse-resource resource-name)
            git-uri (git-uri this mvn-coords)
            version (normalize-version git-uri version)
            manifest-root (get-in-property-as-dir
                            this [:deps mvn-coords :manifest-root] "")
            project-root (-> git-uri
                             (git/procure mvn-coords version)
                             (str manifest-root))
            src-root (get-in-property-as-dir
                       this [:deps mvn-coords :src-root] "src")
            resource-root (get-in-property-as-dir
                            this
                            [:deps mvn-coords :resource-root]
                            "resources")
            manifests (get-manifests project-root)]
        (-> dep
            (assoc :project-root          project-root
                   :default-src-root      src-root
                   :default-resource-root resource-root
                   :manifests             manifests
                   :destination           destination
                   :version               version)
            get-resource!))
      (catch Exception e
        (.fireTransferError this resource e TransferEvent/REQUEST_GET)
        (throw (TransferFailedException. (.getMessage e) e))))
    (.postProcessListeners this resource destination TransferEvent/REQUEST_GET)
    (.fireGetCompleted this resource destination)))
