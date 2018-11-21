(ns lein-git-deps.plugin)

(defonce git-wagon-overrides (atom {}))

(defn inject-properties
  [{:keys [git-deps repositories] :as project}]
  ;; TODO: Validate git-deps
  #_(reset! git-wagon-overrides git-deps)
  (swap! git-wagon-overrides assoc
         :protocols (into {} (map #(vector (first %) (or (-> % second :protocol) :https))) repositories)
         :deps (into {} (map (fn [[k v]] (if (namespace k) [k v] [(symbol (name k) (name k)) v]))) git-deps))
  #_(when-let [insecure-repos (->> repositories
                                 (filter (comp :insecure? second))
                                 seq)]
    (swap! git-wagon-overrides assoc :insecure-repos (into #{} (map first) insecure-repos)))
  project)
