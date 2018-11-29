# lein-git-deps

A [Leiningen](https://leiningen.org/) plugin for resolving Clojure(Script) dependencies from a Git repository.

## Usage

Add the plugin to the `:plugins` vector of your `project.clj`:

```clojure
:plugins [[lein-git-deps "0.1.0"]]
```

If you have dependency specific configurations (see below), add the plugin's `inject-properties` function to your `:middleware` vector:

```clojure
:middleware [lein-git-deps.plugin/inject-properties]
```

Finally, configure your remote Git repository in the `:repositories` vector. Can optionally specify the protocol to use for connecting with the repository. Options are `:https` (default; for public repositories) and `:ssh` (for private repositories). Supports any Git remote conforming repository provider (eg: GitHub, GitLab, Bitbucket).  For example, if you want to retrieve dependencies from public and private repositories in GitHub, you would add:

```clojure
:repositories [["public-github" {:url "git://github.com"}]
               ["private-github" {:url "git://github.com" :protocol :ssh}]]
```

Now, simply add the `rev` (Commit SHA or Tag) you want to pull in from the remote Git repository as the dependency's version and everything should "just work" as if you were pulling it from Maven Central or Clojars. Swap the `rev` back to the Maven version and the artifact will be resolved as it always has.

### Configuration

The plugin provides some optional configuration properties. First, add the `inject-properties` function to your middleware as specified above, then add a `git-deps` key to your project definition. It should point to a map where the dependency's Maven coordinates symbol is the key and a map of properties is the value, for example:

```clojure
:git-deps {group/artifact {:property "value"}}
```

The available properties are:

- `:coordinates`: this specifies the remote Git repository's coordinates for the dependency. By default, the dependency's Maven group/artifact coordinates are used, however, this often does not match the "owner" and "project" coordinates of a Git repository. This property provides the mapping.
- `:manifest-root`: this is the directory to look in that contains the project's manifest (eg: a `project.clj` or `pom.xml` file). This should be a relative path from the project's root. By default, the root of the project is used.
- `:src-root`: this information will be retrieved from the project's manifest, however, if there is not one, or there is one that is not supported, this configuration specifies the source files root directory. Should be relative to the `:manifest-root`. Default is `"src"`
- `:resource-root`: this information will be retrieved from the project's manifest, however, if there is not one, or there is one that is not supported, this configuration specifies the resource files root directory. Should be relative to the `:manifest-root`. Default is `"resources"`.

### Example

Below is an example `project.clj` that uses the plugin:

```clojure
(defproject test-project "0.1.0"
    :description "A test project"
    ;; Include the plugin
    :plugins [[lein-git-deps "0.1.0"]]
    ;; Add the middleware to parse the custom configurations
    :middleware [lein-git-deps.plugin/inject-properties]
    ;; Specify your dependencies. This is the same as any other project.clj and
    ;; should use the Maven coordinates. The version for Git resolution should be
    ;; a valid rev (Commit SHA, Tag, etc) in the repository. Note, that Clojure
    ;; core uses a release version and will be resolved via Maven Central.
    :dependencies [[clj-time "66ea91e68583e7ee246d375859414b9a9b7aba57"]
                   [cheshire "c79ebaa3f56c365a1810f80617c80a3b62999701"]
                   [org.clojure/clojure "1.9.0"]]
    ;; Specify the remote repository to use. Supports any Git remote conforming
    ;; repository provider (eg: GitHub, GitLab, Bitbucket)
    :repositories [["public-github" {:url "git://github.com"}]]
    ;; The `clj-time` repository uses the same Git coordinates on GitHub as its
    ;; Maven coordinates, so no need to add anything here. `cheshire`, however,
    ;; does not, so we need to add the Git coordinates to our configuration.
    :git-deps {cheshire {:coordinates dakrone/cheshire}})
```

### Transitive Dependencies

The plugin supports target projects that have one of the following manifests available:

- Leiningen `project.clj`
- Maven `pom.xml`
- tools.deps `deps.edn`

When a manifest is available, it allows the plugin to resolve the repository's transitive dependencies. If a target repository does not have one of the above manifests available, then it will be provided as a standalone project without any transitive dependencies.

The plugin does support a dependency's git transitive dependencies as well if they are specified in either a Leiningen `project.clj` (from a project that also uses this plugin) or a tools.deps `deps.edn`.

### Private Repositories (SSH Authentication)

As mentioned at the top of this section, when the repository has a `:protocol :ssh` value set the plugin will attempt to use SSH with Public Key Authentication to resolve the dependency from a private repository. Under the covers the code uses [jsch](http://www.jcraft.com/jsch/) with [jsch-agent-proxy](http://www.jcraft.com/jsch-agent-proxy/), which depends on `ssh-agent` running on the machine and properly configured with your keys. Both of the below links provide good information on how to create your SSH keys and get them loaded into `ssh-agent`:

- https://help.github.com/articles/generating-a-new-ssh-key-and-adding-it-to-the-ssh-agent/
- https://help.github.com/articles/working-with-ssh-key-passphrases/

## Rationale

The rationale is essentially the same as that of [`tools.deps`](https://clojure.org/reference/deps_and_cli):

> Clojure build tools have traditionally taken the approach of wrapping the Maven ecosystem to gain access to Java libraries. However, they have also forced this approach on Clojure code as well, requiring a focus on artifacts that must be built and deployed (which Clojure does not require).

The ability to resolve dependencies via a remote Git repository opens up many previously unavailable workflows. For example:

- Quick testing of a non-released version of a dependency (all you need is the source in a remote Git repository)
- As the primary artifact repository for your internal dependencies. Instead of maintaining (or paying for) a private Maven repository just pull the code in from your SVN.
- Stop using [SemVer](https://semver.org/), and instead use the commit SHA as your dependency's version, which encodes explicitly what code is being used as opposed to an (often) arbitrary version number.

The idea here is not new, so why not just use...

**tools.deps**

As noted, `tools.deps` was the inspiration behind this plugin and it is a fantastic addition to the Clojure tooling ecosystem. However, it does not support any build tooling for a project so users still may need to rely on an existing build tool or leverage the growing set of projects to provide this functionality. This may be fine for a new project, however, there are many existing projects that use Leiningen and could benefit from dependency resolution via a remote Git repository. 

Additionally, Leiningen has been around for almost a decade and has many features that will be difficult to replicate. Adding in support for Git dependencies seemed like a good fit as another tool in the Leiningen toolbox. 

Finally, tools.deps only supports remote projects that have `deps.edn` files. This plugin expands that support to also include other manifests as well.

**lein-tools-deps**

The [`lein-tools-deps`](https://github.com/RickMoynihan/lein-tools-deps) plugin is another nice addition, which allows Leiningen users to include dependencies from a `deps.edn` in their Leiningen project. It takes the approach of dropping the `:dependencies` specified in the `project.clj` and instead replacing them with those resolved by `tools.deps`. This provides a nice hybrid approach, however, the "cut and paste" approach means that Leiningen built-in dependency tooling will not work (eg: `lein deps :tree` just provides a flat list). 

Additionally, there are now two places to maintain configurations: the `project.clj` and the `deps.edn`. There may be good reason to maintain both (due to other tooling in your project), however, if the Git resolution feature is the primary motivator, this plugin provides another option.

Ultimately, the **TL;DR** is that we wanted the ability to pull in dependencies from a remote Git repository baked into Leiningen with a single place to maintain our build configurations.

## License

Source Copyright © 2018 Reify Health, Inc.

Distributed under the MIT License.  See the file LICENSE.
