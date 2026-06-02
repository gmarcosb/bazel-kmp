// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.bazel.rules

import com.google.devtools.build.lib.actions.ActionEnvironment

/** A rule class provider implementing the rules Bazel knows.  */
object BazelRuleClassProvider {
    private val FALLBACK_SHELL: PathFragment? = PathFragment.create("/bin/bash")

    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    val SHELL_EXECUTABLES: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.util.OS?, PathFragment?> =
        com.google.common.collect.ImmutableMap.builder<com.google.devtools.build.lib.util.OS?, PathFragment?>()
            .put(com.google.devtools.build.lib.util.OS.WINDOWS, PathFragment.create("c:/msys64/usr/bin/bash.exe"))
            .put(com.google.devtools.build.lib.util.OS.FREEBSD, PathFragment.create("/usr/local/bin/bash"))
            .put(com.google.devtools.build.lib.util.OS.OPENBSD, PathFragment.create("/usr/local/bin/bash"))
            .put(com.google.devtools.build.lib.util.OS.LINUX, PathFragment.create("/bin/bash"))
            .put(com.google.devtools.build.lib.util.OS.DARWIN, PathFragment.create("/bin/bash"))
            .put(com.google.devtools.build.lib.util.OS.UNKNOWN, FALLBACK_SHELL)
            .buildOrThrow()

    @com.google.common.annotations.VisibleForTesting
    fun getDefaultPathFromOptions(options: ShellConfiguration.Options): PathFragment? {
        if (options.getShellExecutable() != null) {
            return options.getShellExecutable()
        }

        // Honor BAZEL_SH env variable for backwards compatibility.
        val path: String? = java.lang.System.getenv("BAZEL_SH")
        if (path != null) {
            return PathFragment.create(StringEncoding.platformToInternal(path))
        }
        return null
    }

    @com.google.common.annotations.VisibleForTesting
    fun getShellExecutableForOs(
        os: com.google.devtools.build.lib.util.OS?,
        options: ShellConfiguration.Options
    ): PathFragment? {
        // TODO(ulfjack): instead of using the OS Bazel runs on, we need to use the exec platform,
        // which may be different for remote execution. For now, this can be overridden with
        // --shell_executable, so at least there's a workaround.
        return if (getDefaultPathFromOptions(options) != null)
            getDefaultPathFromOptions(options)
        else
            SHELL_EXECUTABLES.getOrDefault(os, FALLBACK_SHELL)
    }

    @kotlin.jvm.JvmField
    val SHELL_ACTION_ENV: java.util.function.Function<BuildOptions?, ActionEnvironment?> =
        java.util.function.Function { options: BuildOptions? ->
            if (options.hasNoConfig()) {
                return@Function ActionEnvironment.EMPTY
            }
            val strictActionEnv: Boolean = options.get(StrictActionEnvOptions::class.java).getUseStrictActionEnv()
            val os: com.google.devtools.build.lib.util.OS? = com.google.devtools.build.lib.util.OS.getCurrent()
            // TODO(ulfjack): instead of using the OS Bazel runs on, we need to use the exec platform,
            // which may be different for remote execution. For now, this can be overridden with
            // --shell_executable, so at least there's a workaround.
            val shellExecutable: PathFragment? =
                getShellExecutableForOs(os, options.get(ShellConfiguration.Options::class.java))

            val env: TreeMap<String?, String?> = TreeMap<String?, String?>()

            // All entries in the builder that have a value of null inherit the value from the client
            // environment, which is only known at execution time - we don't want to bake the client env
            // into the configuration since any change to the configuration requires rerunning the full
            // analysis phase.
            if (!strictActionEnv) {
                env.put("LD_LIBRARY_PATH", null)
            }

            if (strictActionEnv) {
                env.put("PATH", pathOrDefault(os, null, shellExecutable))
            } else if (os == com.google.devtools.build.lib.util.OS.WINDOWS) {
                // TODO(ulfjack): We want to add the MSYS root to the PATH, but that prevents us from
                // inheriting PATH from the client environment. For now we use System.getenv even though
                // that is incorrect. We should enable strict_action_env by default and then remove this
                // code, but that change may break Windows users who are relying on the MSYS root being in
                // the PATH.
                var pathEnv: String? = java.lang.System.getenv("PATH")
                if (pathEnv != null) {
                    pathEnv = StringEncoding.platformToInternal(pathEnv)
                }
                env.put("PATH", pathOrDefault(os, pathEnv, shellExecutable))
            } else {
                // The previous implementation used System.getenv (which uses the server's environment),
                // and fell back to a hard-coded "/bin:/usr/bin" if PATH was not set.
                env.put("PATH", null)
            }

            // Signal Unicode support to locale-aware tools. A sandboxed environment
            // without any locale variable set would typically be interpreted as an
            // ASCII-only setup, which may not support special characters in filenames.
            env.put("LC_CTYPE", "C.UTF-8")

            // Shell environment variables specified via options take precedence over the
            // ones inherited from the fragments. In the long run, these fragments will
            // be replaced by appropriate default rc files anyway.
            for (envVar in options.get(CoreOptions::class.java).getActionEnvironment()) {
                when (envVar) {
                    -> env.put(name, value)
                    -> env.put(name, null)
                    -> env.remove(name)
                }
            }

            if (!BuildConfigurationValue.runfilesEnabled(options.get(CoreOptions::class.java))) {
                // Setting this environment variable is for telling the binary running
                // in a Bazel action when to use runfiles library or runfiles tree.
                // The downside is that it will discard cache for all actions once
                // --enable_runfiles changes, but this also prevents wrong caching result if a binary
                // behaves differently with and without runfiles tree.
                env.put("RUNFILES_MANIFEST_ONLY", "1")
            }
            ActionEnvironment.split(env)
        }

    /** Convenience wrapper around [.setup] that returns a final ConfiguredRuleClassProvider.  */ // Used by the build encyclopedia generator.
    @kotlin.jvm.JvmStatic
    fun create(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        setup(builder)
        return builder.build()
    }

    /** Adds this class's definitions to a builder.  */
    fun setup(builder: ConfiguredRuleClassProvider.Builder) {
        builder.setToolsRepository(RepositoryName.Companion.BAZEL_TOOLS)
        builder.setBuiltinsBzlZipResource(
            ResourceFileLoader.resolveResource(BazelRuleClassProvider::class.java, "builtins_bzl.zip")
        )
        builder.setBuiltinsBzlPackagePathInSource("src/main/starlark/builtins_bzl")

        for (ruleSet in RULE_SETS) {
            ruleSet.init(builder)
        }
    }

    @kotlin.jvm.JvmField
    val BAZEL_SETUP: RuleSet = object : RuleSet() {
        public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
            ShellConfiguration.injectShellExecutableFinder(
                { obj: BazelRuleClassProvider?, options: ShellConfiguration.Options -> getDefaultPathFromOptions(options) },
                SHELL_EXECUTABLES
            )
            builder
                .setPrelude("//tools/build_rules:prelude_bazel")
                .setRunfilesPrefix("_main")
                .setPrerequisiteValidator(BazelPrerequisiteValidator())
                .setActionEnvironmentProvider(SHELL_ACTION_ENV)
                .addUniversalConfigurationFragment(ShellConfiguration::class.java)
                .addUniversalConfigurationFragment(PlatformConfiguration::class.java)
                .addUniversalConfigurationFragment(StrictActionEnvConfiguration::class.java)
                .addConfigurationOptions(CoreOptions::class.java)

            builder.addStarlarkBuiltinsInternal(CcStarlarkInternal.NAME, CcStarlarkInternal())

            // Add the package() function.
            // TODO(bazel-team): Factor this into a group of similar BUILD definitions, or add a more
            // convenient way of obtaining a BuiltinFunction than addMethods().
            val symbols: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            net.starlark.java.eval.Starlark.addMethods(symbols, PackageCallable.INSTANCE)
            for (entry in symbols.buildOrThrow().entrySet()) {
                builder.addBuildFileToplevel(entry.getKey(), entry.getValue())
            }
        }
    }

    @kotlin.jvm.JvmField
    val PROTO_RULES: RuleSet = object : RuleSet() {
        public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
            builder.addConfigurationFragment(ProtoConfiguration::class.java)
            builder.addBzlToplevel("proto_common_do_not_use", BazelProtoCommon.INSTANCE)
        }

        public override fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
            return com.google.common.collect.ImmutableList.of<E?>(CoreRules.INSTANCE)
        }
    }

    @kotlin.jvm.JvmField
    val ANDROID_RULES: RuleSet = object : RuleSet() {
        private val allowedRepositories: com.google.common.collect.ImmutableSet<PackageIdentifier?> =
            com.google.common.collect.ImmutableSet.of<PackageIdentifier?>(
                PackageIdentifier.Companion.createUnchecked(
                    "rules_android",
                    ""
                )
            )

        public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
            builder.addConfigurationFragment(AndroidConfiguration::class.java)
            builder.addConfigurationFragment(BazelAndroidConfiguration::class.java)

            builder.addBzlToplevel(
                "android_common",
                ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
                    BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
                    AndroidStarlarkCommon(),
                    allowedRepositories
                )
            )
        }

        public override fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
            return com.google.common.collect.ImmutableList.of<E?>(
                CoreRules.INSTANCE,
                CcRules.Companion.INSTANCE,
                JavaRules.Companion.INSTANCE
            )
        }
    }

    @kotlin.jvm.JvmField
    val PYTHON_RULES: RuleSet = object : RuleSet() {
        val allowedRepositories: com.google.common.collect.ImmutableSet<PackageIdentifier?> =
            com.google.common.collect.ImmutableSet.of<PackageIdentifier?>(
                PackageIdentifier.Companion.createUnchecked("_builtins", ""),
                PackageIdentifier.Companion.createUnchecked("bazel_tools", ""),
                PackageIdentifier.Companion.createUnchecked("rules_python", ""),
                PackageIdentifier.Companion.createUnchecked("", "tools/build_defs/python")
            )

        public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
            // This symbol is overridden by exports.bzl
            builder.addBzlToplevel(
                "py_internal",
                ContextGuardedValue.onlyInAllowedRepos(net.starlark.java.eval.Starlark.NONE, allowedRepositories)
            )
            builder.addStarlarkBuiltinsInternal(PyBuiltins.NAME, BazelPyBuiltins())
        }

        public override fun requires(): com.google.common.collect.ImmutableList<RuleSet?> {
            return com.google.common.collect.ImmutableList.of<E?>(CoreRules.INSTANCE, CcRules.Companion.INSTANCE)
        }
    }

    val PACKAGING_RULES: RuleSet = object : RuleSet() {
        public override fun init(builder: ConfiguredRuleClassProvider.Builder) {
            builder.addBzlToplevel("PackageSpecificationInfo", PackageSpecificationProvider.PROVIDER)
        }
    }

    private val RULE_SETS: com.google.common.collect.ImmutableSet<RuleSet> =
        com.google.common.collect.ImmutableSet.of<E>(
            BAZEL_SETUP,
            CoreRules.INSTANCE,
            GenericRules.Companion.INSTANCE,
            ConfigRules.INSTANCE,
            PlatformRules.INSTANCE,
            PROTO_RULES,
            CcRules.Companion.INSTANCE,
            JavaRules.Companion.INSTANCE,
            ANDROID_RULES,
            PYTHON_RULES,
            ObjcRules.Companion.INSTANCE,
            TestingSupportRules.INSTANCE,
            PACKAGING_RULES,  // This rule set is a little special: it needs to depend on every configuration fragment
            // that has Make variables, so we put it last.
            ToolchainRules.Companion.INSTANCE
        )

    @com.google.common.annotations.VisibleForTesting
    fun pathOrDefault(os: com.google.devtools.build.lib.util.OS?, path: String?, sh: PathFragment?): String {
        // TODO(ulfjack): The default PATH should be set from the exec platform, which may be different
        // from the local machine. For now, this can be overridden with --action_env=PATH=<value>, so
        // at least there's a workaround.

        // On the BSDs system package manager binaries, and importantly bash, end
        // up in /usr/local/bin, so we need to include that in the default PATH. On
        // other Unix platforms we want to exclude /usr/local/bin which commonly
        // holds user installed tools making things less hermetic.

        if (os == com.google.devtools.build.lib.util.OS.FREEBSD || os == com.google.devtools.build.lib.util.OS.OPENBSD) {
            return "/bin:/usr/bin:/sbin:/usr/sbin:/usr/local/bin"
        } else if (os != com.google.devtools.build.lib.util.OS.WINDOWS) {
            return "/bin:/usr/bin:/sbin:/usr/sbin"
        }

        var newPath = ""
        // Attempt to compute the MSYS root (the real Windows path of "/") from `sh`.
        if (sh != null && sh.getParentDirectory() != null) {
            newPath = sh.getParentDirectory().getPathString()
            if (sh.getParentDirectory().endsWith(PathFragment.create("usr/bin"))) {
                newPath +=
                    ";" + sh.getParentDirectory().getParentDirectory().replaceName("bin").getPathString()
            } else if (sh.getParentDirectory().endsWith(PathFragment.create("bin"))) {
                newPath +=
                    ";" + sh.getParentDirectory().replaceName("usr").getRelative("bin").getPathString()
            }
            newPath = newPath.replace('/', '\\')
        }
        // On Windows, the following dirs should always be available in PATH:
        //   C:\Windows
        //   C:\Windows\System32
        //   C:\Windows\System32\WindowsPowerShell\v1.0
        // They are similar to /bin:/usr/bin, which makes the basic tools on the platform available.
        var systemRoot: String? = java.lang.System.getenv("SYSTEMROOT")
        if (com.google.common.base.Strings.isNullOrEmpty(systemRoot)) {
            systemRoot = "C:\\Windows"
        } else {
            systemRoot = StringEncoding.platformToInternal(systemRoot)
        }
        newPath += ";" + systemRoot
        newPath += ";" + systemRoot + "\\System32"
        newPath += ";" + systemRoot + "\\System32\\WindowsPowerShell\\v1.0"
        if (path != null) {
            newPath += ";" + path
        }
        return newPath
    }

    /** Command-line options.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class StrictActionEnvOptions : FragmentOptions() {
        @kotlin.jvm.JvmField
        @get:com.google.devtools.common.options.Option(
            name = "incompatible_strict_action_env",
            oldName = "experimental_strict_action_env",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
            help = """
            If true, Bazel uses an environment with a static value for PATH and does not
            inherit `LD_LIBRARY_PATH`. Use `--action_env=ENV_VARIABLE` if you want to
            inherit specific environment variables from the client, but note that doing so
            can prevent cross-user caching if a shared cache is used.
            
            """.trimIndent()
        )
        abstract var useStrictActionEnv: Boolean
    }

    /**
     * [com.google.devtools.build.lib.skyframe.config.BuildConfigurationFunction] constructs
     * [BuildOptions] out of the options required by the registered fragments. We create and
     * register this fragment exclusively to ensure [StrictActionEnvOptions] is always
     * available.
     */
    @RequiresOptions(options = [StrictActionEnvOptions::class])
    class StrictActionEnvConfiguration(buildOptions: BuildOptions?) : Fragment()
}
