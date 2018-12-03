(ns lein-git-down.plugin
  (:require [clojure.string :as string]
            [leiningen.core.main :as lein]))

(defonce git-wagon-properties (atom {}))

(defn- get-repo-protocols
  [repositories]
  (into {}
        (map #(vector (first %) (or (-> % second :protocol) :https)))
        repositories))

(defn- namespaced-symbol?
  [x]
  (boolean
    (and (symbol? x)
         (namespace x))))

(defn- validate-property-map
  [errors {:keys [coordinates manifest-root src-root resource-root] :as m}]
  (cond-> errors

          (and (contains? m :coordinates)
               (not (namespaced-symbol? coordinates)))
          (conj (format
                  ":coordinates should be a namespaced symbol, received: '%s'"
                  coordinates))

          (and (contains? m :manifest-root)
               (not (string? manifest-root)))
          (conj (format
                  ":manifest-root should be a string, received: '%s'"
                  manifest-root))

          (and (contains? m :src-root)
               (not (string? src-root)))
          (conj (format
                  ":src-root should be a string, received: '%s'"
                  src-root))

          (and (contains? m :resource-root)
               (not (string? resource-root)))
          (conj (format
                  ":resource-root should be a string, received: '%s'"
                  resource-root))))

(defn- validate-properties
  [git-down]
  (if-let [errors (seq (reduce validate-property-map [] (vals git-down)))]
    (lein/warn
      (str "Found errors validating `git-down` properties:\n  - "
           (string/join "\n  - " errors)))
    git-down))

(defn- get-deps-properties
  [git-down]
  (validate-properties
    (into {}
          (map (fn [[k v]]
                 (if (namespace k) [k v] [(symbol (name k) (name k)) v])))
          git-down)))

(defn inject-properties
  [{:keys [git-down repositories] :as project}]
  (when-not (contains? @git-wagon-properties :monkeypatch-tools-gitlibs)
    (swap! git-wagon-properties assoc
      :monkeypatch-tools-gitlibs
      (boolean (get project :monkeypatch-tools-gitlibs true))))
  (swap! git-wagon-properties
         #(merge-with merge %
            {:protocols  (get-repo-protocols repositories)
             :deps      (get-deps-properties git-down)}))
  project)
