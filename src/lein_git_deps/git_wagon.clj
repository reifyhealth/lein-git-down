(ns lein-git-deps.git-wagon
  (:refer-clojure :exclude [resolve])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.gitlibs :as git]
            [clojure.tools.gitlibs.impl :as git-impl]
            [lein-git-deps.impl.pom :as pom]
            [leiningen.core.main :as lein]
            [leiningen.core.project :as project])
  (:import (com.jcraft.jsch.agentproxy ConnectorFactory RemoteIdentityRepository)
           (com.jcraft.jsch JSch Session UserInfo)
           (java.io File FileInputStream)
           (java.security MessageDigest)
           (org.apache.maven.wagon AbstractWagon TransferFailedException ResourceDoesNotExistException)
           (org.apache.maven.wagon.events TransferEvent)
           (org.apache.maven.wagon.repository Repository)
           (org.apache.maven.wagon.resource Resource)
           (org.eclipse.jgit.api TransportConfigCallback)
           (org.eclipse.jgit.api.errors InvalidRemoteException)
           (org.eclipse.jgit.errors NoRemoteRepositoryException)
           (org.eclipse.jgit.transport SshTransport JschConfigSessionFactory)))

;;
;; Helpers
;;

(defn get-in-as-dir
  [m ks default]
  (if-let [d (get-in m ks)]
    (string/replace d #"^(?!/)" "/")
    default))

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

(defn to-dep
  [[lib {:keys [git/url sha mvn/version classifier exclusions]}]]
  (if url
    (let [parts (string/split url #"/")]
      {:group    (penultimate parts)
       :artifact (-> parts last (string/split #"\.") first)
       :version  sha})
    {:group      (or (namespace lib) (name lib))
     :artifact   (name lib)
     :version    version
     :classifier classifier
     :exclusions (map (fn [x] {:group (namespace x) :aritfact (name x)})
                      exclusions)}))

(defmethod resolve-pom! :tools-deps
  [[_ ^File deps-edn]]
  (let [{:keys [paths deps]} (edn/read-string (slurp deps-edn))
        proj-name (.. deps-edn getParentFile getParentFile getName)]
    (pom/gen-pom
      {:group        proj-name
       :artifact     proj-name
       :version      "0.1.0"
       :source-path  (or (first paths) "src")
       :dependencies (map to-dep deps)}
      (io/file (.getParentFile deps-edn) "pom.xml"))))

(defn resolve-default-pom!
  [{:keys [mvn-coords version project-root default-src-root default-resource-root]}]
  (lein/warn
    (str  "Could not find known build tooling so generating simple pom. "
          "Transitive dependencies will NOT be resolved. "
          "(Hint: supported manifests: project.clj, pom.xml, and deps.edn)"))
  (pom/gen-pom
    {:group          (namespace mvn-coords)
     :artifact       (name mvn-coords)
     :version        version
     :source-path    default-src-root
     :resource-paths [default-resource-root]}
    (io/file project-root "pom.xml")))

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

(defn parse-pom
  [^File pom-file]
  (let [{:keys [artifactId groupId version build]} (pom/parse-pom pom-file)
        source-directory (or (:sourceDirectory build) "src")
        resource-paths   (get-in build [:resources :resource])]
    (cond-> {:name artifactId
             :group groupId
             :version version
             :source-paths [source-directory]}
            (vector? resource-paths) (assoc :resource-paths (mapv :directory resource-paths))
            (map? resource-paths)    (assoc :resource-paths [(:directory resource-paths)]))))

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

;; This and the custom `procure` & `resolve` fns are a temporary solution to fix
;; a problem in JSCH that will cause failures if using an encrypted (password
;; protected) SSH key to connect to a private repository. Once gitlibs is
;; [patched](https://dev.clojure.org/jira/browse/TDEPS-49), this can be removed.

(def ssh-callback
  (delay
    (let [factory (doto (ConnectorFactory/getDefault)
                    (.setPreferredUSocketFactories "jna,nc"))
          connector (.createConnector factory)]
      (JSch/setConfig "PreferredAuthentications" "publickey")
      (reify TransportConfigCallback
        (configure [_ transport]
          (.setSshSessionFactory ^SshTransport transport
            (proxy [JschConfigSessionFactory] []
              (configure [_host session]
                (.setUserInfo
                  ^Session session
                  (proxy [UserInfo] []
                    (getPassword [])
                    (promptYesNo [_] true)
                    (getPassphrase [])
                    (promptPassphrase [_] true)
                    (promptPassword [_] true)
                    (showMessage [_]))))
              (getJSch [hc fs]
                (doto (proxy-super getJSch hc fs)
                  (.setIdentityRepository
                    (RemoteIdentityRepository. connector)))))))))))

(defn procure
  [uri mvn-coords rev]
  (with-redefs [git-impl/ssh-callback ssh-callback]
    (git/procure uri mvn-coords rev)))

(defn resolve
  [uri version]
  (with-redefs [git-impl/ssh-callback ssh-callback]
    (git/resolve uri version)))

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
  [properties mvn-coords]
  (str
    (get properties :base-uri)
    "/"
    (get-in properties [:deps mvn-coords :coordinates] mvn-coords)))

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
  (or (resolve uri version)
      (throw (Exception.
               (format
                 "Could not resolve version '%s' as valid rev in repository"
                 version)))))

(defn proxy-openConnectionInternal
  [^AbstractWagon this properties]
  (let [^Repository repo (.getRepository this)
        protocol (if (= :ssh (get-in @properties [:protocols (.getId repo)]))
                   "ssh://git@"
                   "https://")
        host (.getHost repo)
        port (if (pos? (.getPort repo)) (str ":" (.getPort repo)) "")]
    (swap! properties assoc :base-uri (str protocol host port))))

(defn proxy-resourceExists
  [properties resource-name]
  (try
    (let [{:keys [mvn-coords version]} (parse-resource resource-name)]
      (boolean
        (normalize-version (git-uri @properties mvn-coords) version)))
    (catch Throwable _ false)))

(defn proxy-get
  [^AbstractWagon this properties resource-name ^File destination]
  (let [resource (Resource. resource-name)]
    (.fireGetInitiated this resource destination)
    (.fireGetStarted this resource destination)
    (try
      (let [{:keys [mvn-coords version] :as dep} (parse-resource resource-name)
            git-uri (git-uri @properties mvn-coords)
            version (normalize-version git-uri version)
            manifest-root (get-in-as-dir
                            @properties [:deps mvn-coords :manifest-root] "")
            project-root (-> git-uri
                             (procure mvn-coords version)
                             (str manifest-root))
            src-root (get-in-as-dir
                       @properties [:deps mvn-coords :src-root] "src")
            resource-root (get-in-as-dir
                            @properties
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
      (catch InvalidRemoteException e
        (.fireTransferError this resource e TransferEvent/REQUEST_GET)
        (if (instance? NoRemoteRepositoryException (.getCause e))
          (do (lein/warn (str "Could not find remote git repository. "
                              "Did you add the git coordinates to "
                              "`:git-deps` in project.clj?"))
              (throw (ResourceDoesNotExistException.
                       (.getMessage (.getCause e)) e)))
          (throw (ResourceDoesNotExistException. (.getMessage e) e))))
      (catch Throwable e
        (.fireTransferError this resource e TransferEvent/REQUEST_GET)
        (throw (TransferFailedException. (.getMessage e) e))))
    (.postProcessListeners this resource destination TransferEvent/REQUEST_GET)
    (.fireGetCompleted this resource destination)))

(defn gen
  [properties]
  (proxy [AbstractWagon] []
    (openConnectionInternal []
      (proxy-openConnectionInternal this properties))
    (resourceExists [resource-name]
      (proxy-resourceExists properties resource-name))
    (get [resource-name destination]
      (proxy-get this properties resource-name destination))
    (closeConnection [])
    (getIfNewer [_resource-name _file _version]
      (throw (UnsupportedOperationException.
               "The wagon you are using has not implemented getIfNewer()")))
    (put [_destination _resource-name]
      (throw (UnsupportedOperationException.
               "The wagon you are using has not implemented put()")))))
