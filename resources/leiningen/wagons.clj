{"git"
 (do (require '[lein-git-deps.plugin])
     #(com.reifyhealth.maven.wagon.GitWagon.
        lein-git-deps.plugin/git-wagon-properties))}
