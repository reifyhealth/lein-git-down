{"git"
 (do (require '[lein-git-down.plugin])
     (require '[lein-git-down.git-wagon])
     #(lein-git-down.git-wagon/gen lein-git-down.plugin/git-wagon-properties))}
