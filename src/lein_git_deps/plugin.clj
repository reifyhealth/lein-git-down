(ns lein-git-deps.plugin)

(defonce git-wagon-properties (atom {}))

(defn- get-repo-protocols
  [repositories]
  (into {}
        (map #(vector (first %) (or (-> % second :protocol) :https)))
        repositories))

(defn- get-deps-properties
  [git-deps]
  (into {}
        (map (fn [[k v]]
               (if (namespace k) [k v] [(symbol (name k) (name k)) v])))
        git-deps))

(defn inject-properties
  [{:keys [git-deps repositories] :as project}]
  ;; TODO: Validate git-deps
  (swap! git-wagon-properties assoc
         :protocols (get-repo-protocols repositories)
         :deps (get-deps-properties git-deps))
  project)
