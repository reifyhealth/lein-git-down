# lein-git-down

A [Leiningen](https://leiningen.org/) plugin for resolving Clojure(Script) dependencies from a Git repository.

## Usage

Add the plugin to the `:plugins` vector of your `project.clj`:

```clojure
:plugins [[reifyhealth/lein-git-down "0.3.5"]]
```

If you have dependency specific configurations (see below), add the plugin's `inject-properties` function to your `:middleware` vector:

```clojure
:middleware [lein-git-down.plugin/inject-properties]
```

Finally, configure your remote Git repository in the `:repositories` vector. Can optionally specify the protocol to use for connecting with the repository. Options are `:https` (default; for public repositories) and `:ssh` (for private repositories). Supports any Git remote conforming repository provider (eg: GitHub, GitLab, Bitbucket).  For example, if you want to retrieve dependencies from public and private repositories in GitHub, you would add:

```clojure
:repositories [["public-github" {:url "git://github.com"}]
               ["private-github" {:url "git://github.com" :protocol :ssh}]]
```

Now, simply add the `rev` (Commit SHA or Tag) you want to pull in from the remote Git repository as the dependency's version and everything should "just work" as if you were pulling it from Maven Central or Clojars. Swap the `rev` back to the Maven version and the artifact will be resolved as it always has.

### Configuration

The plugin provides some optional configuration properties. First, add the `inject-properties` function to your middleware as specified above, then add a `:git-down` key to your project definition. It should point to a map where the dependency's Maven coordinates symbol is the key and a map of properties is the value, for example:

```clojure
:git-down {group/artifact {:property "value"}}
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
    :plugins [[reifyhealth/lein-git-down "0.3.5"]]
    ;; Add the middleware to parse the custom configurations
    :middleware [lein-git-down.plugin/inject-properties]
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
    :git-down {cheshire {:coordinates dakrone/cheshire}})
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

One caveat to the above articles, the SSH library used by this plugin does not support private keys in OpenSSH format, which is now the default format used by `ssh-keygen`. You will need to generate your key pair in PEM format using the `-m PEM` flag. This plugin has been patched to skip any keys in an unsupported format instead of failing, but the key used to access the remote git repository must be in a supported format even if others in your environment are not.

Side note: by default the plugin overrides an internal implementation in tools.gitlibs to provide the above fix for skipping unsupported keys and to provide a fix for an error that occurs if your private key has a password. You can opt-out of this override by specifying `:monkeypatch-tools-gitlibs false` at the top level of your `project.clj` file.

#### SSH Config

The jsch library does not support all HostKeyAlgorithms, Ciphers, MACs, and KexAlgorithms and will warn you about unsupported configurations in your `~/.ssh/config` file. If you do want to configure these and not rely on the defaults, then the below is an example snippet that is fully supported:

```
Host github.com
  HostKeyAlgorithms ecdsa-sha2-nistp256-cert-v01@openssh.com,ecdsa-sha2-nistp384-cert-v01@openssh.com,ecdsa-sha2-nistp521-cert-v01@openssh.com,ssh-ed25519-cert-v01@openssh.com,rsa-sha2-512-cert-v01@openssh.com,rsa-sha2-256-cert-v01@openssh.com,ssh-rsa-cert-v01@openssh.com,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-ed25519,rsa-sha2-512,rsa-sha2-256,ssh-rsa
  Ciphers aes128-ctr,aes192-ctr,aes256-ctr
  MACs hmac-sha2-256,hmac-sha1
  KexAlgorithms ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521,diffie-hellman-group-exchange-sha256,diffie-hellman-group14-sha1
```

## Rationale

At a high level, the rationale is essentially the same as that of [`tools.deps`](https://clojure.org/reference/deps_and_cli):

> Clojure build tools have traditionally taken the approach of wrapping the Maven ecosystem to gain access to Java libraries. However, they have also forced this approach on Clojure code as well, requiring a focus on artifacts that must be built and deployed (which Clojure does not require).

The ability to resolve dependencies via a remote Git repository opens up many previously unavailable workflows. For example:

- Quick testing of a non-released version of a dependency (all you need is the source in a remote Git repository)
- As the primary artifact repository for your internal dependencies. Instead of maintaining (or paying for) a private Maven repository just pull the code in from your SVN.
- Stop using [SemVer](https://semver.org/), and instead use the commit SHA as your dependency's version, which encodes explicitly what code is being used as opposed to an (often) arbitrary version number.

While adding Git as a an option for resolving dependencies adds considerable flexibility, there is still a rich and mature ecosystem built around Maven for the subtleties and nuance of managing a complex dependency graph. This plugin seeks to bring in the best of both worlds by opening up Git as a remote repository option, but doing it "native" to the tooling by leveraging the Maven protocol. Under the covers, the plugin implements a [Maven Wagon](https://maven.apache.org/wagon/), to "translate" between the Git protocol and the Maven protocol. This allows all of the standard Leiningen dependency tooling that relies on Maven to "just work" (eg: `lein deps :tree`).

There are several other tools that are available. Below is a brief, non-exhaustive rundown of these tools and how this project is different:

- **tools.deps** is a fantastic addition to the Clojure tooling ecosystem and served as an inspiration behind this project. Its purpose, however, is slightly orthogonal in that it focuses on a new way of managing Clojure projects in addition to supporting the command line tools. Alternatively, this plugin allows existing Leiningen projects to continue to use the same build tooling while adding in the previously missing feature of native Git dependency resolution. Additionally, we support multiple manifest types for dependencies, not just `deps.edn` files.
- The [**lein-tools-deps**](https://github.com/RickMoynihan/lein-tools-deps) plugin is another nice project, which allows Leiningen users to include dependencies from a `deps.edn` in their Leiningen project. It takes the approach of dropping the `:dependencies` specified in the `project.clj` and instead replacing them with those resolved by `tools.deps`. This provides a nice hybrid approach, however, it means that Leiningen built-in dependency tooling will not work as expected. It also means maintaining at least two build files: the `project.clj` and a `deps.edn`.
- The [**lein-git-deps**](https://github.com/tobyhede/lein-git-deps) plugin has a similar purpose as this one. The primary difference is that `lein-git-deps` simply clones the remote repository locally and adds it to the class path. It does not resolve any transitive dependencies of the remote repository and also does not support native Maven dependency tooling.
- [**lein-voom**](https://github.com/LonoCloud/lein-voom) is another interesting plugin. It too has a slightly different use case in that it supports a "SNAPSHOT" like workflow using a Git repository as a substitution for Maven maintaining mutating iterative versions. While its focus is different, it also requires the user to already have the remote repository cloned onto their local machine and to update it manually with new downstream versions. Additionally, it only supports Leiningen projects for remote dependencies.

All of these listed are great projects and may be what solves your problem. If they are, use them! We found that we needed a slightly different approach and so created this project. We hope it is useful and furthers the larger discussion around continually improving development workflows and tools.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md)

## License

Source Copyright © 2018 Reify Health, Inc.

Distributed under the MIT License.  See the file LICENSE.
