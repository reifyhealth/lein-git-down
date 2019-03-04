(ns lein-git-down.impl.git
  (:refer-clojure :exclude [resolve])
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.gitlibs :as git]
            [clojure.tools.gitlibs.impl :as git-impl]
            [leiningen.core.main :as lein]
            [robert.hooke :as hooke])
  (:import (com.jcraft.jsch JSch Session UserInfo ConfigRepository ConfigRepository$Config KeyPair)
           (com.jcraft.jsch.agentproxy ConnectorFactory RemoteIdentityRepository)
           (org.eclipse.jgit.api TransportConfigCallback Git)
           (org.eclipse.jgit.transport SshTransport JschConfigSessionFactory OpenSshConfig)
           (java.io File)))

;; This namespace provides patched `procure` & `resolve` functions that fix
;; issues in JGit's implementation of JSCH. The first is a problem that causes
;; failure if using an encrypted (password protected) SSH key to connect to a
;; private repository. There is a [patch](https://dev.clojure.org/jira/browse/TDEPS-49)
;; submitted to gitlibs for this. The second is how JSCH handles keys in an
;; unsupported format. Without the patch it will fail at the first key it
;; encounters that it does not recognize, which is undesirable as the user
;; may have another key configured that will work. The patched impl below
;; will print a warn message and move on to the next key.

(def ^:dynamic *monkeypatch-tools-gitlibs* true)

(defn valid-key?
  [jsch v]
  (try
    (KeyPair/load ^JSch jsch v)
    true
    (catch Throwable e
      (let [m (if (.startsWith (.getMessage e) "invalid privatekey")
                "The private key file is in an unsupported format."
                (.getMessage e))]
        (lein/warn "Exception processing private key," v ":"
                   m "Skipping..."))
      false)))

(defn get-unsupported-algorithms
  [^Session session k]
  (into #{}
        (filter #(nil? (JSch/getConfig %)))
        (string/split (or (.getConfig session k) "") #",")))

(defn check-algorithms
  "Jsch fails with an NPE when an unsupported algorithm is configured in the
  ssh config file. This provides the user with more helpful information as to
  the cause of the error."
  [^Session session]
  (let [kex (get-unsupported-algorithms session "kex")
        cph (get-unsupported-algorithms session "cipher.c2s")
        mac (get-unsupported-algorithms session "mac.c2s")
        msg (str "Detected unsupported algorithm(s) configured for the '%s' "
                 "property in ssh config: %s")]
    (when (not-empty kex) (lein/warn (format msg "KexAlgorithms" kex)))
    (when (not-empty cph) (lein/warn (format msg "Ciphers" cph)))
    (when (not-empty mac) (lein/warn (format msg "MACs" mac)))))

(def ssh-callback
  (delay
    (let [factory (doto (ConnectorFactory/getDefault)
                    (.setPreferredUSocketFactories "jna,nc"))
          connector (.createConnector factory)]
      (JSch/setConfig "PreferredAuthentications" "publickey")
      (reify TransportConfigCallback
        (configure [_ transport]
          (.setSshSessionFactory
            ^SshTransport transport
            (proxy [JschConfigSessionFactory] []
              (configure [_host session]
                (check-algorithms session)
                (.setUserInfo
                  ^Session session
                  (proxy [UserInfo] []
                    (getPassword [])
                    (promptYesNo [_] true)
                    (getPassphrase [])
                    (promptPassphrase [_] true)
                    (promptPassword [_] true)
                    (showMessage [_]))))
              (getJSch [_hc fs]
                (let [jsch (proxy-super createDefaultJSch fs)]
                  (doto jsch
                    (.setIdentityRepository
                      (RemoteIdentityRepository. connector))
                    (.setConfigRepository
                      (let [osc (OpenSshConfig/get fs)]
                        (proxy [ConfigRepository] []
                          (getConfig [host-name]
                            (let [oscc (.getConfig osc host-name)]
                              (proxy [ConfigRepository$Config] []
                                (getHostname [] (.getHostname oscc))
                                (getUser [] (.getUser oscc))
                                (getPort [] (.getPort oscc))
                                (getValue [key] (.getValue oscc key))
                                (getValues [key]
                                  (let [vs (.getValues oscc key)]
                                    (if (= key "IdentityFile")
                                      (into-array String
                                        (filter (partial valid-key? jsch) vs))
                                      vs)))))))))))))))))))

(defn dev-null-hook
  [_ & _])

(defn procure
  "Monkey patches gitlibs/procure to resolve some JSCH issues unless explicitly
  told not to."
  [uri mvn-coords rev]
  (hooke/with-scope
    (hooke/add-hook #'git-impl/printerrln #'dev-null-hook)
    (if *monkeypatch-tools-gitlibs*
      (with-redefs [git-impl/ssh-callback ssh-callback]
        (git/procure uri mvn-coords rev))
      (git/procure uri mvn-coords rev))))

(defn resolve
  "Monkey patches gitlibs/resolve to resolve some JSCH issues unless explicitly
  told not to."
  [uri version]
  (hooke/with-scope
    (hooke/add-hook #'git-impl/printerrln #'dev-null-hook)
    (if *monkeypatch-tools-gitlibs*
      (with-redefs [git-impl/ssh-callback ssh-callback]
        (git/resolve uri version))
      (git/resolve uri version))))

(defn init
  "Initializes a fresh git repository at `project-dir` and sets HEAD to the
  provided rev, which allows tooling to retrieve the correct HEAD commit in
  the gitlibs repo directory. Returns the .git directory."
  [^File project-dir rev]
  (let [git-dir (.. (Git/init)
                    (setDirectory project-dir)
                    call
                    getRepository
                    getDirectory)]
    (spit (io/file git-dir "HEAD") rev)
    git-dir))

(defn rm
  "Removes the .git directory returning a checkout back to just the checked
  out code."
  [^File git-dir]
  (when (and (.exists git-dir) (= ".git" (.getName git-dir)))
    (->> (file-seq git-dir)
         reverse
         (run! #(when (.exists %) (.delete %))))))
