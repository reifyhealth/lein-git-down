{"git"
 (do (require '[lein-git-deps.plugin])
     (require '[git-wagon])
     #(git-wagon/gen lein-git-deps.plugin/git-wagon-properties))}
