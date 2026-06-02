// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.bazel.rules.BazelRuleClassProvider.pathOrDefault

/** Tests consistency of [BazelRuleClassProvider].  */
@RunWith(JUnit4::class)
class BazelRuleClassProviderTest : BuildViewTestCase() {
    @org.junit.Test
    fun coreConsistency() {
        checkModule(CoreRules.INSTANCE)
    }

    @org.junit.Test
    fun genericConsistency() {
        checkModule(GenericRules.INSTANCE)
    }

    @org.junit.Test
    fun configConsistency() {
        checkModule(ConfigRules.INSTANCE)
    }

    @org.junit.Test
    fun protoConsistency() {
        checkModule(BazelRuleClassProvider.PROTO_RULES)
    }

    @org.junit.Test
    fun cppConsistency() {
        checkModule(CcRules.INSTANCE)
    }

    @org.junit.Test
    fun javaConsistency() {
        checkModule(JavaRules.INSTANCE)
    }

    @org.junit.Test
    fun pythonConsistency() {
        checkModule(BazelRuleClassProvider.PYTHON_RULES)
    }

    @org.junit.Test
    fun androidConsistency() {
        checkModule(BazelRuleClassProvider.ANDROID_RULES)
    }

    @org.junit.Test
    fun objcConsistency() {
        checkModule(ObjcRules.INSTANCE)
    }

    @org.junit.Test
    fun toolchainConsistency() {
        checkModule(ToolchainRules.INSTANCE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun strictActionEnv() {
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            return
        }

        val options: BuildOptions? =
            BuildOptions.of(
                com.google.common.collect.ImmutableList.of<E?>(
                    CoreOptions::class.java, ShellConfiguration.Options::class.java, StrictActionEnvOptions::class.java
                ),
                "--experimental_strict_action_env",
                "--action_env=FOO=bar"
            )

        val env: ActionEnvironment = BazelRuleClassProvider.SHELL_ACTION_ENV.apply(options)
        assertThat(env.getFixedEnv()).containsEntry("PATH", "/bin:/usr/bin:/sbin:/usr/sbin")
        assertThat(env.getFixedEnv()).containsEntry("FOO", "bar")
    }

    @org.junit.Test
    fun pathOrDefaultOnUnix() {
        assertThat(
            pathOrDefault(
                com.google.devtools.build.lib.util.OS.LINUX,
                null,
                null
            )
        ).isEqualTo("/bin:/usr/bin:/sbin:/usr/sbin")
        assertThat(pathOrDefault(com.google.devtools.build.lib.util.OS.LINUX, "/not/bin", null))
            .isEqualTo("/bin:/usr/bin:/sbin:/usr/sbin")
        assertThat(pathOrDefault(com.google.devtools.build.lib.util.OS.OPENBSD, null, null))
            .isEqualTo("/bin:/usr/bin:/sbin:/usr/sbin:/usr/local/bin")
        assertThat(pathOrDefault(com.google.devtools.build.lib.util.OS.FREEBSD, null, null))
            .isEqualTo("/bin:/usr/bin:/sbin:/usr/sbin:/usr/local/bin")
        assertThat(
            pathOrDefault(
                com.google.devtools.build.lib.util.OS.DARWIN,
                null,
                null
            )
        ).isEqualTo("/bin:/usr/bin:/sbin:/usr/sbin")
    }

    @org.junit.Test
    fun pathOrDefaultOnWindows() {
        var defaultWindowsPath = ""
        var systemRoot: String? = java.lang.System.getenv("SYSTEMROOT")
        if (com.google.common.base.Strings.isNullOrEmpty(systemRoot)) {
            systemRoot = "C:\\Windows"
        }
        defaultWindowsPath += ";" + systemRoot
        defaultWindowsPath += ";" + systemRoot + "\\System32"
        defaultWindowsPath += ";" + systemRoot + "\\System32\\WindowsPowerShell\\v1.0"
        assertThat(pathOrDefault(com.google.devtools.build.lib.util.OS.WINDOWS, null, null)).isEqualTo(
            defaultWindowsPath
        )
        assertThat(pathOrDefault(com.google.devtools.build.lib.util.OS.WINDOWS, "C:/mypath", null))
            .isEqualTo(defaultWindowsPath + ";C:/mypath")
        assertThat(
            pathOrDefault(
                com.google.devtools.build.lib.util.OS.WINDOWS,
                "C:/mypath",
                PathFragment.create("D:/foo/shell")
            )
        )
            .isEqualTo("D:\\foo" + defaultWindowsPath + ";C:/mypath")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun optionsAlsoApplyToHost() {
        val options: BuildOptions = targetConfig.getOptions().clone()
        val strictActionEnvOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            options.get(StrictActionEnvOptions::class.java)
        if (strictActionEnvOptions == null) {
            // This Bazel build doesn't include StrictActionEnvOptions. Nothing to test.
            return
        }
        strictActionEnvOptions.setUseStrictActionEnv(true)

        val h: StrictActionEnvOptions =
            AnalysisTestUtil.execOptions(options, skyframeExecutor, reporter)
                .get(StrictActionEnvOptions::class.java)

        assertThat(h.useStrictActionEnv).isTrue()
    }

    @get:org.junit.Test
    val shellExecutableUnset: Unit
        get() {
            assertThat(
                determineShellExecutable(
                    com.google.devtools.build.lib.util.OS.LINUX,
                    null
                )
            )
                .isEqualTo(PathFragment.create("/bin/bash"))
            assertThat(
                determineShellExecutable(
                    com.google.devtools.build.lib.util.OS.FREEBSD,
                    null
                )
            )
                .isEqualTo(PathFragment.create("/usr/local/bin/bash"))
            assertThat(
                determineShellExecutable(
                    com.google.devtools.build.lib.util.OS.OPENBSD,
                    null
                )
            )
                .isEqualTo(PathFragment.create("/usr/local/bin/bash"))
            assertThat(
                determineShellExecutable(
                    com.google.devtools.build.lib.util.OS.WINDOWS,
                    null
                )
            )
                .isEqualTo(PathFragment.create("c:/msys64/usr/bin/bash.exe"))
        }

    @get:org.junit.Test
    val shellExecutableIfSet: Unit
        get() {
            val binBash: PathFragment? = PathFragment.create("/bin/bash")
            assertThat(
                determineShellExecutable(
                    com.google.devtools.build.lib.util.OS.LINUX,
                    binBash
                )
            )
                .isEqualTo(PathFragment.create("/bin/bash"))
            assertThat(
                determineShellExecutable(
                    com.google.devtools.build.lib.util.OS.FREEBSD,
                    binBash
                )
            )
                .isEqualTo(PathFragment.create("/bin/bash"))
            assertThat(
                determineShellExecutable(
                    com.google.devtools.build.lib.util.OS.OPENBSD,
                    binBash
                )
            )
                .isEqualTo(PathFragment.create("/bin/bash"))
            assertThat(
                determineShellExecutable(
                    com.google.devtools.build.lib.util.OS.WINDOWS,
                    binBash
                )
            )
                .isEqualTo(PathFragment.create("/bin/bash"))
        }

    companion object {
        private fun checkConfigConsistency(provider: ConfiguredRuleClassProvider) {
            // Check that every fragment required by a rule is present.
            val configurationFragments: FragmentClassSet = provider.getFragmentRegistry().getAllFragments()
            for (ruleClass in provider.getRuleClassMap().values()) {
                for (fragment in ruleClass.getConfigurationFragmentPolicy().getRequiredConfigurationFragments()) {
                    com.google.common.truth.Subject.contains(fragment)
                }
            }

            val configOptions: MutableSet<java.lang.Class<out FragmentOptions?>?>? =
                provider.getFragmentRegistry().getOptionsClasses()
            for (fragmentClass in configurationFragments) {
                // Check that every options class required for fragment creation is provided.
                for (options in Fragment.requiredOptions(fragmentClass)) {
                    Truth.assertThat(configOptions).contains(options)
                }
            }
        }

        private fun checkModule(top: RuleSet) {
            val builder: ConfiguredRuleClassProvider.Builder = Builder()
            builder.setToolsRepository(RepositoryName.BAZEL_TOOLS)
            val result: MutableSet<RuleSet> = HashSet<RuleSet>()
            result.add(BazelRuleClassProvider.BAZEL_SETUP)
            collectTransitiveClosure(result, top)
            for (module in result) {
                module.init(builder)
            }
            val provider: ConfiguredRuleClassProvider = builder.build()
            assertThat(provider).isNotNull()
            checkConfigConsistency(provider)
        }

        private fun collectTransitiveClosure(result: MutableSet<RuleSet>, module: RuleSet) {
            if (result.add(module)) {
                for (dep in module.requires()) {
                    collectTransitiveClosure(result, dep)
                }
            }
        }

        private fun determineShellExecutable(
            os: com.google.devtools.build.lib.util.OS?,
            executableOption: PathFragment?
        ): PathFragment {
            val options: ShellConfiguration.Options =
                com.google.devtools.common.options.Options.getDefaults<O>(ShellConfiguration.Options::class.java)
            options.setShellExecutable(executableOption)
            return BazelRuleClassProvider.getShellExecutableForOs(os, options)
        }
    }
}
