// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.packages.Attribute.attr

@RunWith(TestParameterInjector::class)
class ConvenienceSymlinkTest : BuildIntegrationTestCase() {
    /** test options to cause the output directory to change  */
    @OptionsClass
    abstract class PathTestOptions : FragmentOptions() {
        @get:com.google.devtools.common.options.Option(
            name = "output_directory_name",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
            defaultValue = "default"
        )
        abstract var outputDirectoryName: String?

        @get:com.google.devtools.common.options.Option(
            name = "useless_option",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "default"
        )
        abstract var uselessOption: String?
    }

    /** Test fragment.  */
    @RequiresOptions(options = [PathTestOptions::class])
    class PathTestConfiguration(buildOptions: BuildOptions) : Fragment() {
        private val outputDirectoryName: String?

        init {
            this.outputDirectoryName = buildOptions.get(PathTestOptions::class.java).getOutputDirectoryName()
        }

        @Throws(Fragment.OutputDirectoriesContext.AddToMnemonicException::class)
        public override fun processForOutputPathMnemonic(ctx: OutputDirectoriesContext) {
            ctx.markAsExplicitInOutputPathFor("output_directory_name")
            ctx.addToMnemonic(outputDirectoryName)
        }
    }

    private class PathAttributeTransitionFactory
        (private val newPath: String?) : TransitionFactory<AttributeTransitionData?> {
        public override fun create(data: AttributeTransitionData?): ConfigurationTransition {
            return object : PatchTransition() {
                public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
                    return com.google.common.collect.ImmutableSet.of<E?>(PathTestOptions::class.java)
                }

                public override fun patch(
                    options: BuildOptionsView,
                    eventHandler: com.google.devtools.build.lib.events.EventHandler?
                ): BuildOptions {
                    val clone: BuildOptionsView = options.clone()
                    clone.get(PathTestOptions::class.java).setOutputDirectoryName(newPath)
                    return clone.underlying()
                }
            }
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.ATTRIBUTE
        }
    }

    private class PathRuleTransitionFactory

        : TransitionFactory<RuleTransitionData?> {
        public override fun create(ruleData: RuleTransitionData): PatchTransition {
            val newPath: String? = NonconfigurableAttributeMapper.of(ruleData.rule()).get("path", STRING)
            return object : PatchTransition() {
                public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
                    return com.google.common.collect.ImmutableSet.of<E?>(PathTestOptions::class.java)
                }

                public override fun patch(
                    options: BuildOptionsView,
                    eventHandler: com.google.devtools.build.lib.events.EventHandler?
                ): BuildOptions {
                    val clone: BuildOptionsView = options.clone()
                    clone.get(PathTestOptions::class.java).setOutputDirectoryName(newPath)
                    return clone.underlying()
                }
            }
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.RULE
        }
    }

    private class UselessOptionTransition(private val newValue: String?) : PatchTransition {
        public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
            return com.google.common.collect.ImmutableSet.of<E?>(PathTestOptions::class.java)
        }

        public override fun patch(
            options: BuildOptionsView,
            eventHandler: com.google.devtools.build.lib.events.EventHandler?
        ): BuildOptions {
            val clone: BuildOptionsView = options.clone()
            clone.get(PathTestOptions::class.java).setUselessOption(newValue)
            return clone.underlying()
        }
    }

    private class UselessOptionTransitionFactory

        : TransitionFactory<RuleTransitionData?> {
        public override fun create(ruleData: RuleTransitionData): PatchTransition {
            return UselessOptionTransition(
                NonconfigurableAttributeMapper.of(ruleData.rule()).get("value", STRING)
            )
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.RULE
        }
    }

    private class PathTestRulesModule : BlazeModule() {
        public override fun registerActionContexts(
            registryBuilder: ModuleActionContextRegistry.Builder,
            env: CommandEnvironment?,
            buildRequest: BuildRequest?
        ) {
            // we need an implementation of FileWriteActionContext to get our file writes to succeed
            registryBuilder.register(FileWriteActionContext::class.java, FileWriteStrategy())
            // we need something to consume FileWriteActionContext or the registration will have no effect
            registryBuilder.restrictTo(FileWriteActionContext::class.java, "local")
        }

        public override fun initializeRuleClasses(builder: ConfiguredRuleClassProvider.Builder) {
            TestRuleModule.getModule().initializeRuleClasses(builder)

            val basicRule: MockRule =
                MockRule {
                    MockRule.define(
                        "basic_rule",
                        { ruleBuilder, env ->
                            ruleBuilder
                                .add(attr("deps", LABEL_LIST).allowedFileTypes())
                                .setImplicitOutputsFunction(
                                    ImplicitOutputsFunction.fromTemplates("%{name}.bin")
                                )
                        })
                }
            val incomingTransitionRule: MockRule =
                MockRule {
                    MockRule.define(
                        "incoming_transition_rule",
                        { ruleBuilder, env ->
                            ruleBuilder
                                .add(
                                    attr("path", STRING)
                                        .mandatory()
                                        .nonconfigurable("used in transition")
                                )
                                .add(attr("deps", LABEL_LIST).allowedFileTypes())
                                .setImplicitOutputsFunction(
                                    ImplicitOutputsFunction.fromTemplates("%{name}.bin")
                                )
                                .cfg(PathRuleTransitionFactory())
                        })
                }
            val incomingUnrelatedTransitionRule: MockRule =
                MockRule {
                    MockRule.define(
                        "incoming_unrelated_transition_rule",
                        { ruleBuilder, env ->
                            ruleBuilder
                                .add(
                                    attr("value", STRING)
                                        .mandatory()
                                        .nonconfigurable("used in transition")
                                )
                                .add(attr("deps", LABEL_LIST).allowedFileTypes())
                                .setImplicitOutputsFunction(
                                    ImplicitOutputsFunction.fromTemplates("%{name}.bin")
                                )
                                .cfg(UselessOptionTransitionFactory())
                        })
                }

            val outgoingTransitionRule: MockRule =
                MockRule {
                    MockRule.define(
                        "outgoing_transition_rule",
                        { ruleBuilder, env ->
                            ruleBuilder
                                .add(
                                    attr("deps", LABEL_LIST)
                                        .allowedFileTypes()
                                        .cfg(
                                            PathAttributeTransitionFactory(
                                                "set_by_outgoing_transition_rule"
                                            )
                                        )
                                )
                                .setImplicitOutputsFunction(
                                    ImplicitOutputsFunction.fromTemplates("%{name}.bin")
                                )
                        })
                }

            builder
                .addConfigurationFragment(PathTestConfiguration::class.java)
                .addRuleDefinition(basicRule)
                .addRuleDefinition(incomingTransitionRule)
                .addRuleDefinition(incomingUnrelatedTransitionRule)
                .addRuleDefinition(outgoingTransitionRule)
        }
    }

    @TestParameter
    var mergedAnalysisExecution: Boolean = false

    @Throws(java.lang.Exception::class)
    override fun setupOptions() {
        super.setupOptions()

        addOptions("--experimental_merged_skyframe_analysis_execution=" + mergedAnalysisExecution)
    }

    val rulesModule: BlazeModule
        get() = PathTestRulesModule()

    private val execRoot: Path
        get() = getBlazeWorkspace().getDirectories().getExecRoot(TestConstants.WORKSPACE_NAME)

    private val outputPath: Path
        get() = getBlazeWorkspace().getDirectories().getOutputPath(TestConstants.WORKSPACE_NAME)

    @get:Throws(IOException::class)
    private val convenienceSymlinks: com.google.common.collect.ImmutableMap<String?, Path?>
        /** Gets a mapping from the workspace-relative paths of symlinks to the paths they point to.  */
        get() = getWorkspace().getDirectoryEntries().stream()
            .filter(Path::isSymbolicLink)
            .collect(
                com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                    java.util.function.Function { path: T? -> path.relativeTo(getWorkspace()).toString() },
                    java.util.function.Function { path: T? ->
                        try {
                            return@toImmutableMap getWorkspace().getRelative(path.readSymbolicLinkUnchecked())
                        } catch (ex: IOException) {
                            throw java.lang.RuntimeException(ex)
                        }
                    })
            )

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sanityCheckFilesHaveNullConfigurations() {
        // Other tests in this file expect that files will have a null configuration.
        write("files/BUILD", "exports_files(['foo.txt', 'bar.txt'])")
        write("files/foo.txt", "This is just a test file to pretend to build.")
        write("files/bar.txt", "This is just a test file to pretend to build.")
        val result: BuildResult = buildTarget("//files:foo.txt", "//files:bar.txt")

        assertThat(
            result.getActualTargets().stream()
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                        java.util.function.Function { target: T? -> target.getLabel().toString() },
                        java.util.function.Function { target: T? -> java.util.Optional.ofNullable<T?>(target.getConfigurationKey()) })
                )
        )
            .containsExactly(
                "//files:foo.txt", java.util.Optional.empty<T?>(),
                "//files:bar.txt", java.util.Optional.empty<T?>()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sanityCheckOutputDirectory() {
        addOptions("--output_directory_name=set_by_flag", "--compilation_mode=fastbuild")

        write(
            "path/BUILD",
            """
        basic_rule(name = "from_flag")

        incoming_transition_rule(
            name = "from_transition",
            path = "set_by_transition",
        )

        incoming_unrelated_transition_rule(
            name = "unrelated_transition",
            value = "whatever",
        )

        outgoing_transition_rule(name = "outgoing_transition")
        
        """.trimIndent()
        )
        val result: BuildResult =
            buildTarget(
                "//path:from_flag",
                "//path:from_transition",
                "//path:unrelated_transition",
                "//path:outgoing_transition"
            )

        assertThat(
            result.getActualTargets().stream()
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                        java.util.function.Function { target: T? -> target.getLabel().toString() },
                        java.util.function.Function { target: T? ->
                            getConfigurationFromLastBuildResult(target)
                                .getOutputDirectory(RepositoryName.MAIN)
                                .getRoot()
                                .asPath()
                                .relativeTo(this.outputPath)
                                .toString()
                        })
                )
        )
            .containsExactly(
                "//path:from_flag", getTargetConfiguration().getCpu() + "-fastbuild-set_by_flag",
                "//path:from_transition",
                getTargetConfiguration().getCpu() + "-fastbuild-set_by_transition",
                "//path:unrelated_transition",
                getTargetConfiguration().getCpu() + "-fastbuild-set_by_flag-ST-040655c91309",
                "//path:outgoing_transition",
                getTargetConfiguration().getCpu() + "-fastbuild-set_by_flag"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildingNothing_unsetsSymlinks() {
        addOptions("--symlink_prefix=nothing-", "--incompatible_skip_genfiles_symlink=false")

        val config: Path = getOutputBase().getRelative("some-imaginary-config")
        // put symlinks at the convenience symlinks spots to simulate a prior build
        val binLink: Path = getWorkspace().getChild("nothing-bin")
        binLink.createSymbolicLink(config.getChild("bin"))
        val genfilesLink: Path = getWorkspace().getChild("nothing-genfiles")
        genfilesLink.createSymbolicLink(config.getChild("genfiles"))
        val testlogsLink: Path = getWorkspace().getChild("nothing-testlogs")
        testlogsLink.createSymbolicLink(config.getChild("testlogs"))

        buildTarget()

        // there should be nothing at any of the convenience symlinks which depend on configuration -
        // the symlinks put there during the simulated prior build should have been deleted
        assertThat(binLink.exists(Symlinks.NOFOLLOW)).isTrue()
        assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isTrue()
        assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isTrue()

        // the execroot and output path symlinks should have been created because they don't depend on
        // configuration, but no other symlinks should have been created
        Truth.assertThat(this.convenienceSymlinks)
            .containsExactly( // notably absent: nothing-bin, nothing-genfiles, nothing-testlogs
                // these were also not created under other names
                "nothing-bin",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/bin"),
                "nothing-genfiles",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/bin"),
                "nothing-testlogs",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/testlogs"),
                "nothing-" + TestConstants.WORKSPACE_NAME,
                this.execRoot,
                "nothing-out",
                this.outputPath
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildingOnlyTargetsWithNullConfigurations_unsetsSymlinks() {
        addOptions("--symlink_prefix=nulled-", "--incompatible_skip_genfiles_symlink=false")

        val config: Path = getOutputBase().getRelative("some-imaginary-config")
        // put symlinks at the convenience symlinks spots to simulate a prior build
        val binLink: Path = getWorkspace().getChild("nulled-bin")
        binLink.createSymbolicLink(config.getChild("bin"))
        val genfilesLink: Path = getWorkspace().getChild("nulled-genfiles")
        genfilesLink.createSymbolicLink(config.getChild("genfiles"))
        val testlogsLink: Path = getWorkspace().getChild("nulled-testlogs")
        testlogsLink.createSymbolicLink(config.getChild("testlogs"))

        write("files/BUILD", "exports_files(['foo.txt', 'bar.txt'])")
        write("files/foo.txt", "This is just a test file to pretend to build.")
        write("files/bar.txt", "This is just a test file to pretend to build.")
        buildTarget("//files:foo.txt", "//files:bar.txt")

        // there should be nothing at any of the convenience symlinks which depend on configuration -
        // the symlinks put there during the simulated prior build should have been deleted
        assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse()

        // the execroot and output path symlinks should have been created because they don't depend on
        // configuration, but no other symlinks should have been created
        Truth.assertThat(this.convenienceSymlinks)
            .containsExactly( // notably absent: nulled-bin, nulled-genfiles, nulled-testlogs
                // these were also not created under other names
                "nulled-" + TestConstants.WORKSPACE_NAME, this.execRoot, "nulled-out", this.outputPath
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildingTargetsWithDifferentOutputDirectories_unsetsSymlinksIfNoneAreTopLevel() {
        addOptions("--symlink_prefix=ambiguous-", "--incompatible_skip_genfiles_symlink=false")

        val config: Path = this.outputPath.getRelative("some-imaginary-config")
        // put symlinks at the convenience symlinks spots to simulate a prior build
        val binLink: Path = getWorkspace().getChild("ambiguous-bin")
        binLink.createSymbolicLink(config.getChild("bin"))
        val genfilesLink: Path = getWorkspace().getChild("ambiguous-genfiles")
        genfilesLink.createSymbolicLink(config.getChild("genfiles"))
        val testlogsLink: Path = getWorkspace().getChild("ambiguous-testlogs")
        testlogsLink.createSymbolicLink(config.getChild("testlogs"))

        write(
            "targets/BUILD",
            """
        incoming_transition_rule(
            name = "config1",
            path = "set_from_config1",
        )

        incoming_transition_rule(
            name = "config2",
            path = "set_from_config2",
        )
        
        """.trimIndent()
        )
        buildTarget("//targets:config1", "//targets:config2")

        // there should be nothing at any of the convenience symlinks which depend on configuration -
        // the symlinks put there during the simulated prior build should have been deleted
        assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse()

        // the execroot and output path symlinks should have been created because they don't depend on
        // configuration, but no other symlinks should have been created
        Truth.assertThat(this.convenienceSymlinks)
            .containsExactly( // notably absent: ambiguous-bin, ambiguous-genfiles, ambiguous-testlogs
                // these were also not created under other names
                "ambiguous-" + TestConstants.WORKSPACE_NAME,
                this.execRoot,
                "ambiguous-out",
                this.outputPath
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildingTargetsWithDifferentOutputDirectories_setsSymlinksIfAnyAreTopLevel() {
        addOptions(
            "--symlink_prefix=ambiguous-",
            "--incompatible_skip_genfiles_symlink=false",
            "--incompatible_merge_genfiles_directory=false",
            "--incompatible_skip_genfiles_symlink=false"
        )

        val config: Path = this.outputPath.getRelative("some-imaginary-config")
        // put symlinks at the convenience symlinks spots to simulate a prior build
        val binLink: Path = getWorkspace().getChild("ambiguous-bin")
        binLink.createSymbolicLink(config.getChild("bin"))
        val genfilesLink: Path = getWorkspace().getChild("ambiguous-genfiles")
        genfilesLink.createSymbolicLink(config.getChild("genfiles"))
        val testlogsLink: Path = getWorkspace().getChild("ambiguous-testlogs")
        testlogsLink.createSymbolicLink(config.getChild("testlogs"))

        write(
            "targets/BUILD",
            """
        basic_rule(name = "default")

        incoming_transition_rule(
            name = "config1",
            path = "set_from_config1",
        )
        
        """.trimIndent()
        )
        buildTarget("//targets:default", "//targets:config1")

        Truth.assertThat(this.convenienceSymlinks)
            .containsExactly(
                "ambiguous-bin",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/bin"),
                "ambiguous-genfiles",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/genfiles"),
                "ambiguous-testlogs",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-default/testlogs"),
                "ambiguous-" + TestConstants.WORKSPACE_NAME,
                this.execRoot,
                "ambiguous-out",
                this.outputPath
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildingTargetsWithSameConfiguration_setsSymlinks() {
        addOptions(
            "--symlink_prefix=same-",
            "--compilation_mode=fastbuild",
            "--incompatible_merge_genfiles_directory=false",
            "--incompatible_skip_genfiles_symlink=false"
        )

        write(
            "targets/BUILD",
            """
        incoming_transition_rule(
            name = "configged1",
            path = "configured",
        )

        incoming_transition_rule(
            name = "configged2",
            path = "configured",
        )
        
        """.trimIndent()
        )
        buildTarget("//targets:configged1", "//targets:configged2")

        Truth.assertThat(this.convenienceSymlinks)
            .containsExactly(
                "same-bin",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-configured/bin"),
                "same-genfiles",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-configured/genfiles"),
                "same-testlogs",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-configured/testlogs"),
                "same-" + TestConstants.WORKSPACE_NAME,
                this.execRoot,
                "same-out",
                this.outputPath
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildingSameConfigurationTargetsWithDifferentConfigurationDeps_setsSymlinks() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=united-",
            "--compilation_mode=fastbuild",
            "--incompatible_merge_genfiles_directory=false",
            "--incompatible_skip_genfiles_symlink=false"
        )

        write(
            "targets/BUILD",
            """
        outgoing_transition_rule(
            name = "configged1",
            deps = [":alternate1"],
        )

        outgoing_transition_rule(
            name = "configged2",
            deps = [":alternate2"],
        )

        basic_rule(name = "alternate1")

        incoming_transition_rule(
            name = "alternate2",
            path = "alternate_transition",
        )
        
        """.trimIndent()
        )
        buildTarget("//targets:configged1", "//targets:configged2")

        Truth.assertThat(this.convenienceSymlinks)
            .containsExactly(
                "united-bin",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/bin"),
                "united-genfiles",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/genfiles"),
                "united-testlogs",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/testlogs"),
                "united-" + TestConstants.WORKSPACE_NAME,
                this.execRoot,
                "united-out",
                this.outputPath
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentConfigurationSameOutputDirectory_setsSymlinks() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=unchanged-",
            "--compilation_mode=fastbuild",
            "--incompatible_merge_genfiles_directory=false",
            "--incompatible_skip_genfiles_symlink=false"
        )

        write(
            "targets/BUILD",
            """
        basic_rule(name = "from_flag")

        incoming_unrelated_transition_rule(
            name = "configged1",
            value = "one_transition",
        )

        incoming_unrelated_transition_rule(
            name = "configged2",
            value = "alternate_transition",
        )
        
        """.trimIndent()
        )
        buildTarget("//targets:from_flag", "//targets:configged1", "//targets:configged2")

        Truth.assertThat(this.convenienceSymlinks)
            .containsExactly(
                "unchanged-bin",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/bin"),
                "unchanged-genfiles",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/genfiles"),
                "unchanged-testlogs",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/testlogs"),
                "unchanged-" + TestConstants.WORKSPACE_NAME,
                this.execRoot,
                "unchanged-out",
                this.outputPath
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nullConfigurationWithOtherMatchingOutputDir_setsSymlinks() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=mixed-",
            "--compilation_mode=fastbuild",
            "--incompatible_merge_genfiles_directory=false",
            "--incompatible_skip_genfiles_symlink=false"
        )

        write(
            "targets/BUILD",
            """
        exports_files(["null"])

        basic_rule(name = "configured1")

        basic_rule(name = "configured2")
        
        """.trimIndent()
        )
        write("targets/null", "This is just a test file to pretend to build.")
        buildTarget("//targets:null", "//targets:configured1", "//targets:configured2")

        Truth.assertThat(this.convenienceSymlinks)
            .containsExactly(
                "mixed-bin",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/bin"),
                "mixed-genfiles",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/genfiles"),
                "mixed-testlogs",
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/testlogs"),
                "mixed-" + TestConstants.WORKSPACE_NAME,
                this.execRoot,
                "mixed-out",
                this.outputPath
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settingSymlinksReplacesSymlinksAlreadyPresent() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=replaced-",
            "--compilation_mode=fastbuild",
            "--incompatible_skip_genfiles_symlink=false"
        )

        val binLink: Path = getWorkspace().getChild("replaced-bin")
        val genfilesLink: Path = getWorkspace().getChild("replaced-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("replaced-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("replaced-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("replaced-out")

        val original: PathFragment? = this.outputPath.getRelative("original/destination").asFragment()
        binLink.createSymbolicLink(original)
        genfilesLink.createSymbolicLink(original)
        testlogsLink.createSymbolicLink(original)
        workspaceLink.createSymbolicLink(original)
        outLink.createSymbolicLink(original)

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        // Implicitly test for symlink-ness; readSymbolicLink would throw if they are not symlinks.
        assertThat(binLink.readSymbolicLink()).isNotEqualTo(original)
        assertThat(genfilesLink.readSymbolicLink()).isNotEqualTo(original)
        assertThat(testlogsLink.readSymbolicLink()).isNotEqualTo(original)
        assertThat(workspaceLink.readSymbolicLink()).isNotEqualTo(original)
        assertThat(outLink.readSymbolicLink()).isNotEqualTo(original)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settingSymlinksCreatesSymlinksIfNotAlreadyPresent() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=created-",
            "--compilation_mode=fastbuild",
            "--incompatible_skip_genfiles_symlink=false"
        )

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        assertThat(getWorkspace().getChild("created-bin").isSymbolicLink()).isTrue()
        assertThat(getWorkspace().getChild("created-genfiles").isSymbolicLink()).isTrue()
        assertThat(getWorkspace().getChild("created-testlogs").isSymbolicLink()).isTrue()
        assertThat(getWorkspace().getChild("created-" + TestConstants.WORKSPACE_NAME).isSymbolicLink())
            .isTrue()
        assertThat(getWorkspace().getChild("created-out").isSymbolicLink()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun genfilesLink_omittedWithIncompatibleFlag() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=prefix-",
            "--compilation_mode=fastbuild",
            "--incompatible_skip_genfiles_symlink=true"
        )

        // Simulate leftover symlink from prior build.
        val config: Path = this.outputPath.getRelative("some-imaginary-config")
        val genfilesLink: Path = getWorkspace().getChild("prefix-genfiles")
        genfilesLink.createSymbolicLink(config.getChild("genfiles"))

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        assertThat(getWorkspace().getChild("prefix-genfiles").isSymbolicLink()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun genfilesLink_presentWithoutIncompatibleFlag() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=prefix-",
            "--compilation_mode=fastbuild",
            "--incompatible_skip_genfiles_symlink=false"
        )

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        assertThat(getWorkspace().getChild("prefix-genfiles").isSymbolicLink()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settingSymlinksDoesNotReplaceNormalFilesAlreadyPresent() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=blocked-",
            "--compilation_mode=fastbuild"
        )

        val binLink: Path = getWorkspace().getChild("blocked-bin")
        val genfilesLink: Path = getWorkspace().getChild("blocked-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("blocked-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("blocked-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("blocked-out")

        FileSystemUtils.writeIsoLatin1(binLink, "this file is not a symlink")
        FileSystemUtils.writeIsoLatin1(genfilesLink, "this file is not a symlink")
        FileSystemUtils.writeIsoLatin1(testlogsLink, "this file is not a symlink")
        FileSystemUtils.writeIsoLatin1(workspaceLink, "this file is not a symlink")
        FileSystemUtils.writeIsoLatin1(outLink, "this file is not a symlink")

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        assertThat(binLink.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(genfilesLink.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(testlogsLink.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(workspaceLink.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(outLink.isFile(Symlinks.NOFOLLOW)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settingSymlinksDoesNotReplaceDirectoriesAlreadyPresent() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=blocked-",
            "--compilation_mode=fastbuild"
        )

        val binLink: Path = getWorkspace().getChild("blocked-bin")
        val genfilesLink: Path = getWorkspace().getChild("blocked-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("blocked-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("blocked-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("blocked-out")

        binLink.createDirectory()
        genfilesLink.createDirectory()
        testlogsLink.createDirectory()
        workspaceLink.createDirectory()
        outLink.createDirectory()

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        assertThat(binLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
        assertThat(genfilesLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
        assertThat(testlogsLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
        assertThat(workspaceLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
        assertThat(outLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settingSymlinksReplacesSymlinksEvenIfNotPointingInsideExecRoot() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=replaced-",
            "--compilation_mode=fastbuild",
            "--incompatible_merge_genfiles_directory=false",
            "--incompatible_skip_genfiles_symlink=false"
        )

        val binLink: Path = getWorkspace().getChild("replaced-bin")
        val genfilesLink: Path = getWorkspace().getChild("replaced-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("replaced-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("replaced-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("replaced-out")

        val original: Path? = getWorkspace().getRelative("/arbitrary/somewhere/else/in/the/filesystem")
        binLink.createSymbolicLink(original)
        genfilesLink.createSymbolicLink(original)
        testlogsLink.createSymbolicLink(original)
        workspaceLink.createSymbolicLink(original)
        outLink.createSymbolicLink(original)

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        // Implicitly test for symlink-ness; readSymbolicLink would throw if they are not symlinks.
        assertThat(binLink.readSymbolicLink())
            .isEqualTo(
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/bin")
                    .asFragment()
            )
        assertThat(genfilesLink.readSymbolicLink())
            .isEqualTo(
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/genfiles")
                    .asFragment()
            )
        assertThat(testlogsLink.readSymbolicLink())
            .isEqualTo(
                this.outputPath
                    .getRelative(getTargetConfiguration().getCpu() + "-fastbuild-from_flag/testlogs")
                    .asFragment()
            )
        assertThat(workspaceLink.readSymbolicLink()).isEqualTo(this.execRoot.asFragment())
        assertThat(outLink.readSymbolicLink()).isEqualTo(this.outputPath.asFragment())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settingSymlinksCreatesDirectoriesIfNeeded() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=created/",
            "--compilation_mode=fastbuild",
            "--incompatible_skip_genfiles_symlink=false"
        )

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        assertThat(getWorkspace().getChild("created").isDirectory()).isTrue()
        assertThat(getWorkspace().getRelative("created/bin").isSymbolicLink()).isTrue()
        assertThat(getWorkspace().getRelative("created/genfiles").isSymbolicLink()).isTrue()
        assertThat(getWorkspace().getRelative("created/testlogs").isSymbolicLink()).isTrue()
        assertThat(
            getWorkspace().getRelative("created/" + TestConstants.WORKSPACE_NAME).isSymbolicLink()
        )
            .isTrue()
        assertThat(getWorkspace().getRelative("created/out").isSymbolicLink()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settingSymlinksDoesNothingWhenParentExistsAndIsNotADirectory() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=blocked/",
            "--compilation_mode=fastbuild"
        )

        val parentDir: Path? = getWorkspace().getChild("blocked")
        FileSystemUtils.writeIsoLatin1(parentDir, "this file is not a directory")

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        assertThat(getWorkspace().getChild("blocked").isFile(Symlinks.NOFOLLOW)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settingSymlinksUsesExistingOrPopulatedParentDirectoryAsNormal() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=cooperating/",
            "--compilation_mode=fastbuild",
            "--incompatible_skip_genfiles_symlink=false"
        )
        write("target/BUILD", "basic_rule(name='target')")
        write("cooperating/original", "this file makes the directory come to life")
        buildTarget("//target:target")

        assertThat(getWorkspace().getChild("cooperating").isDirectory()).isTrue()
        assertThat(getWorkspace().getRelative("cooperating/bin").isSymbolicLink()).isTrue()
        assertThat(getWorkspace().getRelative("cooperating/genfiles").isSymbolicLink()).isTrue()
        assertThat(getWorkspace().getRelative("cooperating/testlogs").isSymbolicLink()).isTrue()
        assertThat(
            getWorkspace()
                .getRelative("cooperating/" + TestConstants.WORKSPACE_NAME)
                .isSymbolicLink()
        )
            .isTrue()
        assertThat(getWorkspace().getRelative("cooperating/out").isSymbolicLink()).isTrue()
        assertThat(getWorkspace().getRelative("cooperating/original").isFile()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun settingSymlinksIgnoresSymlinksWithDifferentPrefix() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=new-prefix-",
            "--compilation_mode=fastbuild"
        )
        val binLink: Path = getWorkspace().getChild("other-prefix-bin")
        val genfilesLink: Path = getWorkspace().getChild("other-prefix-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("other-prefix-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("other-prefix-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("other-prefix-out")

        val original: PathFragment? = this.outputPath.getRelative("original/destination").asFragment()
        binLink.createSymbolicLink(original)
        genfilesLink.createSymbolicLink(original)
        testlogsLink.createSymbolicLink(original)
        workspaceLink.createSymbolicLink(original)
        outLink.createSymbolicLink(original)

        write("target/BUILD", "basic_rule(name='target')")
        buildTarget("//target:target")

        // Implicitly test for symlink-ness; readSymbolicLink would throw if they are not symlinks.
        assertThat(binLink.readSymbolicLink()).isEqualTo(original)
        assertThat(genfilesLink.readSymbolicLink()).isEqualTo(original)
        assertThat(testlogsLink.readSymbolicLink()).isEqualTo(original)
        assertThat(workspaceLink.readSymbolicLink()).isEqualTo(original)
        assertThat(outLink.readSymbolicLink()).isEqualTo(original)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unsettingSymlinksRemovesConfigurationSymlinksIfAlreadyPresent() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=deleted-",
            "--compilation_mode=fastbuild",
            "--incompatible_skip_genfiles_symlink=false"
        )
        val binLink: Path = getWorkspace().getChild("deleted-bin")
        val genfilesLink: Path = getWorkspace().getChild("deleted-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("deleted-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("deleted-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("deleted-out")

        val config: Path = this.outputPath.getRelative("some-imaginary-config")
        // put symlinks at the convenience symlinks spots to simulate a prior build
        binLink.createSymbolicLink(config.getChild("bin"))
        genfilesLink.createSymbolicLink(config.getChild("genfiles"))
        testlogsLink.createSymbolicLink(config.getChild("testlogs"))

        write("file/BUILD", "exports_files(['file'])")
        write("file/file", "this is just a file to pretend to build")
        buildTarget("//file:file")

        assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse()

        assertThat(workspaceLink.isSymbolicLink()).isTrue()
        assertThat(outLink.isSymbolicLink()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unsettingSymlinksSucceedsIfNotAlreadyPresent() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=already-absent-",
            "--compilation_mode=fastbuild"
        )
        val binLink: Path = getWorkspace().getChild("already-absent-bin")
        val genfilesLink: Path = getWorkspace().getChild("already-absent-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("already-absent-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("already-absent-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("already-absent-out")

        write("file/BUILD", "exports_files(['file'])")
        write("file/file", "this is just a file to pretend to build")
        buildTarget("//file:file")

        assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse()

        assertThat(workspaceLink.isSymbolicLink()).isTrue()
        assertThat(outLink.isSymbolicLink()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unsettingSymlinksDoesNotRemoveNormalFilesAlreadyPresent() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=blocked-",
            "--compilation_mode=fastbuild"
        )
        val binLink: Path = getWorkspace().getChild("blocked-bin")
        val genfilesLink: Path = getWorkspace().getChild("blocked-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("blocked-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("blocked-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("blocked-out")

        FileSystemUtils.writeIsoLatin1(binLink, "this file is not a symlink")
        FileSystemUtils.writeIsoLatin1(genfilesLink, "this file is not a symlink")
        FileSystemUtils.writeIsoLatin1(testlogsLink, "this file is not a symlink")
        FileSystemUtils.writeIsoLatin1(workspaceLink, "this file is not a symlink")
        FileSystemUtils.writeIsoLatin1(outLink, "this file is not a symlink")

        write("file/BUILD", "exports_files(['file'])")
        write("file/file", "this is just a file to pretend to build")
        buildTarget("//file:file")

        assertThat(binLink.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(genfilesLink.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(testlogsLink.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(workspaceLink.isFile(Symlinks.NOFOLLOW)).isTrue()
        assertThat(outLink.isFile(Symlinks.NOFOLLOW)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unsettingSymlinksDoesNotRemoveDirectoriesAlreadyPresent() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=blocked-",
            "--compilation_mode=fastbuild"
        )
        val binLink: Path = getWorkspace().getChild("blocked-bin")
        val genfilesLink: Path = getWorkspace().getChild("blocked-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("blocked-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("blocked-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("blocked-out")

        binLink.createDirectory()
        genfilesLink.createDirectory()
        testlogsLink.createDirectory()
        workspaceLink.createDirectory()
        outLink.createDirectory()

        write("file/BUILD", "exports_files(['file'])")
        write("file/file", "this is just a file to pretend to build")
        buildTarget("//file:file")

        assertThat(binLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
        assertThat(genfilesLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
        assertThat(testlogsLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
        assertThat(workspaceLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
        assertThat(outLink.isDirectory(Symlinks.NOFOLLOW)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unsettingSymlinksRemovesSymlinksEvenIfNotPointingInsideExecRoot() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=deleted-",
            "--compilation_mode=fastbuild",
            "--incompatible_skip_genfiles_symlink=false"
        )
        val binLink: Path = getWorkspace().getChild("deleted-bin")
        val genfilesLink: Path = getWorkspace().getChild("deleted-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("deleted-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("deleted-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("deleted-out")

        val original: Path? = getWorkspace().getRelative("/arbitrary/somewhere/else/in/the/filesystem")
        binLink.createSymbolicLink(original)
        genfilesLink.createSymbolicLink(original)
        testlogsLink.createSymbolicLink(original)
        workspaceLink.createSymbolicLink(original)
        outLink.createSymbolicLink(original)

        write("file/BUILD", "exports_files(['file'])")
        write("file/file", "this is just a file to pretend to build")
        buildTarget("//file:file")

        assertThat(binLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(genfilesLink.exists(Symlinks.NOFOLLOW)).isFalse()
        assertThat(testlogsLink.exists(Symlinks.NOFOLLOW)).isFalse()

        assertThat(workspaceLink.isSymbolicLink()).isTrue()
        assertThat(outLink.isSymbolicLink()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unsettingSymlinksIgnoresSymlinksWithDifferentPrefix() {
        addOptions(
            "--output_directory_name=from_flag",
            "--symlink_prefix=new-prefix-",
            "--compilation_mode=fastbuild"
        )
        val binLink: Path = getWorkspace().getChild("other-prefix-bin")
        val genfilesLink: Path = getWorkspace().getChild("other-prefix-genfiles")
        val testlogsLink: Path = getWorkspace().getChild("other-prefix-testlogs")
        val workspaceLink: Path = getWorkspace().getChild("other-prefix-" + TestConstants.WORKSPACE_NAME)
        val outLink: Path = getWorkspace().getChild("other-prefix-out")

        val original: PathFragment? = this.outputPath.getRelative("original/destination").asFragment()
        binLink.createSymbolicLink(original)
        genfilesLink.createSymbolicLink(original)
        testlogsLink.createSymbolicLink(original)
        workspaceLink.createSymbolicLink(original)
        outLink.createSymbolicLink(original)

        write("file/BUILD", "exports_files(['file'])")
        write("file/file", "this is just a file to pretend to build")
        buildTarget("//file:file")

        // Implicitly test for symlink-ness; readSymbolicLink would throw if they are not symlinks.
        assertThat(binLink.readSymbolicLink()).isEqualTo(original)
        assertThat(genfilesLink.readSymbolicLink()).isEqualTo(original)
        assertThat(testlogsLink.readSymbolicLink()).isEqualTo(original)
        assertThat(workspaceLink.readSymbolicLink()).isEqualTo(original)
        assertThat(outLink.readSymbolicLink()).isEqualTo(original)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinkPrefix_specialNoCreateValue_doesNotCreateOrDeleteSymlinks() {
        addOptions("--symlink_prefix=/")

        write("foo/BUILD", "exports_files(['bar.txt'])")
        write("foo/bar.txt", "This is just a test file to pretend to build.")

        // This will be a preexisting symlink and when --symlink_prefix=/ is used, assert that this
        // preexisting symlink still exists.
        val binLink: Path = getWorkspace().getChild("blaze-" + TestConstants.WORKSPACE_NAME)
        binLink.createSymbolicLink(PathFragment.create("foo/"))

        buildTarget("//foo:bar.txt")

        val symlinks: com.google.common.collect.ImmutableMap<String?, Path?> = this.convenienceSymlinks
        Truth.assertThat(symlinks).containsKey("blaze-" + TestConstants.WORKSPACE_NAME)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun convenienceSymlinks_ignore_leaveSymlinksUntouched() {
        addOptions("--experimental_convenience_symlinks=ignore")

        write("foo/BUILD", "exports_files(['bar.txt'])")
        write("foo/bar.txt", "This is just a test file to pretend to build.")
        buildTarget("//foo:bar.txt")

        // This will be a preexisting symlink that will remain after the build
        val binLink: Path = getWorkspace().getChild("blaze-" + TestConstants.WORKSPACE_NAME)
        binLink.createSymbolicLink(PathFragment.create("foo/"))

        val symlinks: com.google.common.collect.ImmutableMap<String?, Path?> = this.convenienceSymlinks
        Truth.assertThat(symlinks).containsKey("blaze-" + TestConstants.WORKSPACE_NAME)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun convenienceSymlinks_normal_createSymlinks() {
        addOptions("--symlink_prefix=test-", "--experimental_convenience_symlinks=normal")

        write("foo/BUILD", "exports_files(['bar.txt'])")
        write("foo/bar.txt", "This is just a test file to pretend to build.")
        buildTarget("//foo:bar.txt")

        val symlinks: com.google.common.collect.ImmutableMap<String?, Path?> = this.convenienceSymlinks
        Truth.assertThat(symlinks).containsKey("test-" + TestConstants.WORKSPACE_NAME)
        Truth.assertThat(symlinks).containsKey("test-out")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun convenienceSymlinks_clean_deletesAndDoesNotCreateSymlinks() {
        addOptions("--symlink_prefix=test-", "--experimental_convenience_symlinks=clean")

        write("foo/BUILD", "exports_files(['bar.txt'])")
        write("foo/bar.txt", "This is just a test file to pretend to build.")

        // This will be a preexisting symlink that will be deleted after the build
        val binLink: Path = getWorkspace().getChild("test-" + TestConstants.WORKSPACE_NAME)
        binLink.createSymbolicLink(PathFragment.create("foo"))

        buildTarget("//foo:bar.txt")

        val symlinks: com.google.common.collect.ImmutableMap<String?, Path?> = this.convenienceSymlinks
        Truth.assertThat(symlinks).doesNotContainKey("test-" + TestConstants.WORKSPACE_NAME)
        Truth.assertThat(symlinks).doesNotContainKey("test-out")
    }
}
