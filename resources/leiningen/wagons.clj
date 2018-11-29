{"git"
 (do (require '[lein-git-deps.plugin])
     (require '[lein-git-deps.git-wagon])
     #(lein-git-deps.git-wagon/gen lein-git-deps.plugin/git-wagon-properties))}
