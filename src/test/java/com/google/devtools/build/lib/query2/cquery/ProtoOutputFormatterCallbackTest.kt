// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.cquery

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Test for cquery's proto output format.
 * 
 * 
 * TODO(blaze-configurability): refactor all cquery output format tests to consolidate duplicate
 * infrastructure.
 */
@RunWith(TestParameterInjector::class)
class ProtoOutputFormatterCallbackTest : ConfiguredTargetQueryTest() {
    private var options: CqueryOptions? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private val events: MutableList<com.google.devtools.build.lib.events.Event?> =
        java.util.ArrayList<com.google.devtools.build.lib.events.Event?>()

    @Before
    fun setUpCqueryOptions() {
        this.options = com.google.devtools.common.options.Options.getDefaults<O>(CqueryOptions::class.java)
        options.setIncludeToolDeps(false)
        options.setIncludeImplicitDeps(false)
        options.setIncludeNoDepDeps(false)
        // TODO(bazel-team): reduce the confusion about these two seemingly similar settings.
        // options.aspectDeps impacts how proto and similar output formatters output aspect results.
        // Setting.INCLUDE_ASPECTS impacts whether or not aspect dependencies are included when
        // following target deps. See CommonQueryOptions for further flag details.
        options.setAspectDeps(com.google.devtools.build.lib.query2.query.aspectresolvers.AspectResolver.Mode.OFF)
        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS)
        options.setProtoIncludeConfigurations(true)
        options.setProtoIncludeRuleInputsAndOutputs(true)
        this.reporter = com.google.devtools.build.lib.events.Reporter(
            EventBusEventHandler.createWithNewEventBus(),
            com.google.devtools.build.lib.events.EventHandler { e: com.google.devtools.build.lib.events.Event? ->
                events.add(
                    e
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelectInAttribute() {
        val depsRule: MockRule =
            MockRule {
                MockRule.define(
                    "my_rule",
                    { builder, env ->
                        builder
                            .add(attr("deps", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                    })
            }
        val ruleClassProvider: ConfiguredRuleClassProvider? = setRuleClassProviders(depsRule).build()
        helper.useRuleClassProvider(ruleClassProvider)

        writeFile(
            "test/BUILD",
            """
        my_rule(
            name = "my_rule",
            deps = select({
                ":garfield": [
                    "lasagna.java",
                    "naps.java",
                ],
                "//conditions:default": ["mondays.java"],
            }),
        )

        config_setting(
            name = "garfield",
            values = {"foo": "cat"},
        )
        
        """.trimIndent()
        )

        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        var myRuleProto: AnalysisProtosV2.ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                getProtoOutput<T?>("//test:my_rule", AnalysisProtosV2.CqueryResult.parser())
                    .getResultsList()
            )
        var attributes: MutableList<Build.Attribute> = myRuleProto.getTarget().getRule().getAttributeList()
        for (attribute in attributes) {
            if (!attribute.getName().equals("deps")) {
                continue
            }
            assertThat(attribute.getStringListValueCount()).isEqualTo(1)
            assertThat(attribute.getStringListValue(0)).isEqualTo("//test:mondays.java")
            break
        }

        getHelper().useConfiguration("--foo=cat")
        myRuleProto =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                getProtoOutput<T?>("//test:my_rule", AnalysisProtosV2.CqueryResult.parser())
                    .getResultsList()
            )
        attributes = myRuleProto.getTarget().getRule().getAttributeList()
        for (attribute in attributes) {
            if (!attribute.getName().equals("deps")) {
                continue
            }
            assertThat(attribute.getStringListValueCount()).isEqualTo(2)
            assertThat(attribute.getStringListValue(0)).isEqualTo("//test:lasagna.java")
            assertThat(attribute.getStringListValue(1)).isEqualTo("//test:naps.java")
            break
        }
    }

    @org.junit.Test
    @Suppress("deprecation") // only use for tests
    @Throws(java.lang.Exception::class)
    fun testConfigurations() {
        options.setTransitions(Transitions.LITE)

        val ruleWithPatch: MockRule =
            MockRule {
                MockRule.define(
                    "my_rule",
                    { builder, env ->
                        builder.add(
                            attr("deps", LABEL_LIST)
                                .allowedFileTypes(FileTypeSet.ANY_FILE)
                                .cfg(ExecutionTransitionFactory.createFactory())
                        )
                    })
            }
        val parentRuleClass: MockRule =
            MockRule {
                MockRule.define(
                    "parent_rule",
                    { builder, env ->
                        builder
                            .add(attr("deps", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                            .add(attr("srcs", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                    })
            }

        val ruleClassProvider: ConfiguredRuleClassProvider? =
            setRuleClassProviders(ruleWithPatch, parentRuleClass, this.simpleRule).build()
        helper.useRuleClassProvider(ruleClassProvider)

        writeFile(
            "test/BUILD",
            """
        parent_rule(
            name = "parent_rule",
            srcs = ["parent.source"],
            deps = [":transition_rule"],
        )

        my_rule(
            name = "transition_rule",
            deps = [
                ":dep",
                ":patched",
            ],
        )

        simple_rule(name = "dep")
        
        """.trimIndent()
        )

        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val cqueryResult: AnalysisProtosV2.CqueryResult =
            getProtoOutput<T>("deps(//test:parent_rule)", AnalysisProtosV2.CqueryResult.parser())
        val configurations: MutableList<Configuration> = cqueryResult.getConfigurationsList()

        val resultsList: MutableList<AnalysisProtosV2.ConfiguredTarget> = cqueryResult.getResultsList()

        val parentRuleProto: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(resultsList, "//test:parent_rule")
        val keyedTargets: MutableSet<CqueryNode?> = eval("deps(//test:parent_rule)")

        val parentRule: CqueryNode = getKeyedTargetByLabel(keyedTargets, "//test:parent_rule")
        assertThat(parentRuleProto.getConfiguration().getChecksum())
            .isEqualTo(parentRule.getConfigurationChecksum())

        val parentConfiguration: Configuration =
            getConfigurationForId(configurations, parentRuleProto.getConfigurationId())
        assertThat(parentConfiguration.getChecksum()).isEqualTo(parentRule.getConfigurationChecksum())
        assertThat(parentConfiguration)
            .ignoringFieldDescriptors(
                Configuration.getDescriptor().findFieldByName("checksum"),
                Configuration.getDescriptor().findFieldByName("id"),
                Configuration.getDescriptor().findFieldByName("fragments"),
                Configuration.getDescriptor().findFieldByName("id"),
                Configuration.getDescriptor().findFieldByName("fragment_options")
            )
            .isEqualTo(
                Configuration.newBuilder()
                    .setMnemonic("k8-fastbuild")
                    .setPlatformName("k8")
                    .setIsTool(false)
                    .build()
            )

        val fragmentsList: MutableList<Fragment?> = parentConfiguration.getFragmentsList()

        Truth.assertThat(fragmentsList.stream().map<Any?>(Fragment::getName)).isInOrder()
        Truth.assertThat(fragmentsList)
            .contains(
                Fragment.newBuilder()
                    .setName("com.google.devtools.build.lib.rules.cpp.CppConfiguration")
                    .addFragmentOptionNames("com.google.devtools.build.lib.rules.cpp.CppOptions")
                    .build()
            )

        val fragmentOptionsList: MutableList<FragmentOptions> = parentConfiguration.getFragmentOptionsList()
        Truth.assertThat(fragmentOptionsList.stream().map<Any?>(FragmentOptions::getName)).isInOrder()

        val appleFragmentOptions: FragmentOptions =
            fragmentOptionsList.stream()
                .filter { fo: FragmentOptions ->
                    fo.getName()
                        .equals(
                            "com.google.devtools.build.lib.rules.apple.AppleCommandLineOptions"
                        )
                }
                .findFirst()
                .get()
        assertThat(appleFragmentOptions.getName())
            .isEqualTo("com.google.devtools.build.lib.rules.apple.AppleCommandLineOptions")
        com.google.common.truth.Subject.contains(
            Option.newBuilder().setName("apple_platform_type").setValue("macos").build()
        )

        assertThat(appleFragmentOptions.getOptionsList().stream().map(Option::getName)).isInOrder()

        val cppFragmentOptions: FragmentOptions =
            fragmentOptionsList.stream()
                .filter { fo: FragmentOptions ->
                    fo.getName().equals("com.google.devtools.build.lib.rules.cpp.CppOptions")
                }
                .findFirst()
                .get()
        assertThat(cppFragmentOptions.getName())
            .isEqualTo("com.google.devtools.build.lib.rules.cpp.CppOptions")
        com.google.common.truth.Subject.contains(
            Option.newBuilder().setName("dynamic_mode").setValue("DEFAULT").build()
        )

        assertThat(cppFragmentOptions.getOptionsList().stream().map(Option::getName)).isInOrder()

        val transitionRuleProto: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(resultsList, "//test:transition_rule")
        val transitionRule: CqueryNode = getKeyedTargetByLabel(keyedTargets, "//test:transition_rule")
        assertThat(transitionRuleProto.getConfiguration().getChecksum())
            .isEqualTo(transitionRule.getConfigurationChecksum())

        val transitionConfiguration: Configuration =
            getConfigurationForId(configurations, transitionRuleProto.getConfigurationId())
        assertThat(transitionConfiguration.getChecksum())
            .isEqualTo(transitionRule.getConfigurationChecksum())

        val depRuleProto: AnalysisProtosV2.ConfiguredTarget = getRuleProtoByName(resultsList, "//test:dep")
        val depRuleConfiguration: Configuration =
            getConfigurationForId(configurations, depRuleProto.getConfigurationId())
        assertThat(depRuleConfiguration.getPlatformName()).isEqualTo("k8")
        assertThat(depRuleConfiguration.getMnemonic()).matches("k8-opt-exec.*")
        assertThat(depRuleConfiguration.getIsTool()).isTrue()

        val depRule: CqueryNode = getKeyedTargetByLabel(keyedTargets, "//test:dep")

        assertThat(depRuleProto.getConfiguration().getChecksum())
            .isEqualTo(depRule.getConfigurationChecksum())

        // Assert the proto checksums for targets in different configurations are not the same.
        assertThat(depRuleConfiguration.getChecksum())
            .isNotEqualTo(transitionConfiguration.getChecksum())
        // Targets without a configuration have a configuration_id of 0.
        val fileTargetProto: AnalysisProtosV2.ConfiguredTarget =
            resultsList.stream()
                .filter { result: AnalysisProtosV2.ConfiguredTarget ->
                    result.getTarget().getSourceFile().getName().equals("//test:patched")
                }
                .findAny()
                .orElseThrow()
        assertThat(fileTargetProto.getConfigurationId()).isEqualTo(0)

        assertThat(parentRuleProto.getTarget().getRule().getConfiguredRuleInputList())
            .containsExactly( // Targets whose deps have no transitions should appear with identifical configuration
                // information to their parent:
                ConfiguredRuleInput.newBuilder()
                    .setLabel("//test:transition_rule")
                    .setConfigurationChecksum(parentRuleProto.getConfiguration().getChecksum())
                    .setConfigurationId(parentRuleProto.getConfigurationId())
                    .build(),  // Source file deps have no configurations:
                ConfiguredRuleInput.newBuilder().setLabel("//test:parent.source").build()
            )

        // Targets with deps with transitions should show distinct configurations.
        val patchedConfiguredRuleInput: ConfiguredRuleInput? =
            ConfiguredRuleInput.newBuilder().setLabel("//test:patched").build()
        val depConfiguredRuleInput: ConfiguredRuleInput? =
            ConfiguredRuleInput.newBuilder()
                .setLabel("//test:dep")
                .setConfigurationChecksum(depRuleProto.getConfiguration().getChecksum())
                .setConfigurationId(depRuleProto.getConfigurationId())
                .build()
        val configuredRuleInputs: MutableList<ConfiguredRuleInput?>? =
            transitionRuleProto.getTarget().getRule().getConfiguredRuleInputList()
        Truth.assertThat(configuredRuleInputs)
            .containsAtLeast(patchedConfiguredRuleInput, depConfiguredRuleInput)
    }

    @org.junit.Test
    @Suppress("deprecation") // only use for tests
    @TestParameters(
        "{bepCpuFromPlatform: False, platformToCpuMap: '', platformName: 'cpu_val'}",
        "{bepCpuFromPlatform: False, platformToCpuMap: 'new_cpu_name', platformName: 'cpu_val'}",
        "{bepCpuFromPlatform: True, platformToCpuMap: '', platformName: 'x86_64'}",
        "{bepCpuFromPlatform: True, platformToCpuMap: 'new_cpu_name', platformName: 'new_cpu_name'}"
    )
    @Throws(java.lang.Exception::class)
    fun testConfigurationCPU(
        bepCpuFromPlatform: String?, platformToCpuMap: String, platformName: String?
    ) {
        options.setTransitions(Transitions.NONE)

        val args: MutableList<String?> = java.util.ArrayList<String?>()
        args.add("--cpu=cpu_val")
        args.add("--host_cpu=cpu_val")
        args.add("--platforms=" + TestConstants.PLATFORM_LABEL)
        args.add("--host_platform=" + TestConstants.PLATFORM_LABEL)
        args.add("--incompatible_bep_cpu_from_platform=" + bepCpuFromPlatform)
        if (!platformToCpuMap.isEmpty()) {
            args.add(
                ("--experimental_override_platform_cpu_name="
                        + TestConstants.PLATFORM_LABEL
                        + "="
                        + platformToCpuMap)
            )
        }
        getHelper().useConfiguration(*args.toTypedArray<String?>())

        writeFile(
            "test/defs.bzl",
            """
        def _my_rule_impl(ctx):
            return []

        my_rule = rule(
            implementation = _my_rule_impl,
            attrs = {'dep': attr.label(cfg = "exec")},
        )
        
        """.trimIndent()
        )
        writeFile(
            "test/BUILD",
            """
        load(":defs.bzl", "my_rule")
        my_rule(name = "my_rule", dep = ":dep")
        my_rule(name = "dep")
        
        """.trimIndent()
        )

        val cqueryResult: AnalysisProtosV2.CqueryResult =
            getProtoOutput<T>("deps(//test:my_rule)", AnalysisProtosV2.CqueryResult.parser())
        val configurations: MutableList<Configuration> = cqueryResult.getConfigurationsList()
        val resultsList: MutableList<AnalysisProtosV2.ConfiguredTarget> = cqueryResult.getResultsList()

        val myRuleProto: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(resultsList, "//test:my_rule")
        val ruleConfiguration: Configuration =
            getConfigurationForId(configurations, myRuleProto.getConfigurationId())
        assertThat(ruleConfiguration.getChecksum())
            .isEqualTo(myRuleProto.getConfiguration().getChecksum())
        assertThat(ruleConfiguration)
            .ignoringFieldDescriptors(
                Configuration.getDescriptor().findFieldByName("checksum"),
                Configuration.getDescriptor().findFieldByName("mnemonic"),
                Configuration.getDescriptor().findFieldByName("id"),
                Configuration.getDescriptor().findFieldByName("fragments"),
                Configuration.getDescriptor().findFieldByName("fragment_options")
            )
            .isEqualTo(
                Configuration.newBuilder().setPlatformName(platformName).setIsTool(false).build()
            )

        val depRuleProto: AnalysisProtosV2.ConfiguredTarget = getRuleProtoByName(resultsList, "//test:dep")
        val depConfiguration: Configuration =
            getConfigurationForId(configurations, depRuleProto.getConfigurationId())
        assertThat(depConfiguration.getChecksum())
            .isEqualTo(depRuleProto.getConfiguration().getChecksum())
        assertThat(depConfiguration)
            .ignoringFieldDescriptors(
                Configuration.getDescriptor().findFieldByName("checksum"),
                Configuration.getDescriptor().findFieldByName("mnemonic"),
                Configuration.getDescriptor().findFieldByName("id"),
                Configuration.getDescriptor().findFieldByName("fragments"),
                Configuration.getDescriptor().findFieldByName("fragment_options")
            )
            .isEqualTo(
                Configuration.newBuilder().setPlatformName(platformName).setIsTool(true).build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun configuredRuleInputsFromAspects() {
        options.setTransitions(Transitions.LITE)
        writeFile(
            "test/BUILD",
            """
        load(":defs.bzl", "my_rule")
        my_rule(
            name = "parent",
            deps = [":child"],
        )
        my_rule(name = "child")
        my_rule(name = "aspect_exec_config_dep")
        my_rule(name = "aspect_same_config_dep")
        
        """.trimIndent()
        )
        writeFile(
            "test/defs.bzl",
            """
        my_aspect = aspect(
            implementation = lambda target, ctx: [],
            attr_aspects = ["deps"],
            attrs = {
                "_aspect_exec_deps": attr.label_list(
                    cfg = "exec",
                    default = [":aspect_exec_config_dep"]
                ),
                "_aspect_deps": attr.label_list(default = [":aspect_same_config_dep"]),
            }
        )
        my_rule = rule(
            implementation = lambda ctx: [],
            attrs = { "deps": attr.label_list(aspects = [my_aspect]) }
        )
        
        """.trimIndent()
        )

        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.INCLUDE_ASPECTS)
        val cqueryResult: AnalysisProtosV2.CqueryResult =
            getProtoOutput<T>("deps(//test:parent)", AnalysisProtosV2.CqueryResult.parser())
        val configurations: MutableList<Configuration?>? = cqueryResult.getConfigurationsList()
        Truth.assertThat(configurations).hasSize(3) // Target config, exec config, host platform config.

        val resultsList: MutableList<AnalysisProtosV2.ConfiguredTarget> = cqueryResult.getResultsList()
        val parentRuleProto: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(resultsList, "//test:parent")
        val directDepProto: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(resultsList, "//test:child")
        val aspectDepSameConfigProto: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(resultsList, "//test:aspect_same_config_dep")
        val aspectDepExecConfigProto: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(resultsList, "//test:aspect_exec_config_dep")

        assertThat(parentRuleProto.getTarget().getRule().getConfiguredRuleInputList())
            .containsAtLeast(
                ConfiguredRuleInput.newBuilder()
                    .setLabel("//test:child")
                    .setConfigurationChecksum(
                        getConfigurationForId(
                            cqueryResult.getConfigurationsList(),
                            directDepProto.getConfigurationId()
                        )
                            .getChecksum()
                    )
                    .setConfigurationId(directDepProto.getConfigurationId())
                    .build(),
                ConfiguredRuleInput.newBuilder()
                    .setLabel("//test:aspect_same_config_dep")
                    .setConfigurationChecksum(
                        getConfigurationForId(
                            cqueryResult.getConfigurationsList(),
                            aspectDepSameConfigProto.getConfigurationId()
                        )
                            .getChecksum()
                    )
                    .setConfigurationId(aspectDepSameConfigProto.getConfigurationId())
                    .build(),
                ConfiguredRuleInput.newBuilder()
                    .setLabel("//test:aspect_exec_config_dep")
                    .setConfigurationChecksum(
                        getConfigurationForId(
                            cqueryResult.getConfigurationsList(),
                            aspectDepExecConfigProto.getConfigurationId()
                        )
                            .getChecksum()
                    )
                    .setConfigurationId(aspectDepExecConfigProto.getConfigurationId())
                    .build()
            )

        assertThat(parentRuleProto.getConfigurationId()).isEqualTo(directDepProto.getConfigurationId())
        assertThat(parentRuleProto.getConfigurationId())
            .isEqualTo(aspectDepSameConfigProto.getConfigurationId())
        assertThat(parentRuleProto.getConfigurationId())
            .isNotEqualTo(aspectDepExecConfigProto.getConfigurationId())
    }

    /** Tests an alias's output.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasOutput() {
        writeFile(
            "fake_licenses/BUILD",
            """
        load("//test:defs.bzl", "my_rule")
        my_rule(name = "license")
        
        """.trimIndent()
        )
        writeFile(
            "test/BUILD",
            """
        load(":defs.bzl", "my_rule")
        package(
            default_applicable_licenses = ["//fake_licenses:license"],
        )
        alias(
            name = "my_alias",
            actual = ":my_target",
        )
        my_rule(name = "my_target")
        
        """.trimIndent()
        )
        writeFile(
            "test/defs.bzl",
            """
        my_rule = rule(
            implementation = lambda ctx: [],
            attrs = {},
        )
        
        """.trimIndent()
        )

        options.setTransitions(Transitions.LITE)
        val cqueryResult: AnalysisProtosV2.CqueryResult =
            getProtoOutput<T>("deps(//test:my_alias)", AnalysisProtosV2.CqueryResult.parser())

        val aliasProto: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(cqueryResult.getResultsList(), "//test:my_alias")
        val actualProto: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(cqueryResult.getResultsList(), "//test:my_target")
        val actualLicense: AnalysisProtosV2.ConfiguredTarget =
            getRuleProtoByName(cqueryResult.getResultsList(), "//fake_licenses:license")

        // Expect the alias's "name" field references the alias's label, not its actual.
        assertThat(aliasProto.getTarget().getRule().getName()).isEqualTo("//test:my_alias")
        assertThat(aliasProto.getTarget().getRule().getRuleInputList())
            .containsExactly("//test:my_target")
        assertThat(aliasProto.getTarget().getRule().getConfiguredRuleInputList())
            .containsAtLeast(
                ConfiguredRuleInput.newBuilder()
                    .setLabel("//test:my_target")
                    .setConfigurationChecksum(
                        getConfigurationForId(
                            cqueryResult.getConfigurationsList(), actualProto.getConfigurationId()
                        )
                            .getChecksum()
                    )
                    .setConfigurationId(actualProto.getConfigurationId())
                    .build(),
                ConfiguredRuleInput.newBuilder()
                    .setLabel("//fake_licenses:license") // Don't use the aliases' configuration because top-level aliases include test
                    // configuration, which all non-test deps trim out.
                    .setConfigurationChecksum(
                        getConfigurationForId(
                            cqueryResult.getConfigurationsList(),
                            actualLicense.getConfigurationId()
                        )
                            .getChecksum()
                    )
                    .setConfigurationId(actualLicense.getConfigurationId())
                    .build()
            )
    }

    /** Tests output where one of the deps is an alias.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputOnAliasDep() {
        writeFile(
            "test/BUILD",
            """
        load(":defs.bzl", "my_rule")
        my_rule(
            name = "my_target",
            deps = [":my_alias"],
        )
        alias(
            name = "my_alias",
            actual = ":my_child",
        )
        my_rule(name = "my_child")
        
        """.trimIndent()
        )
        writeFile(
            "test/defs.bzl",
            """
        my_rule = rule(
            implementation = lambda ctx: [],
            attrs = { "deps": attr.label_list() },
        )
        
        """.trimIndent()
        )

        options.setTransitions(Transitions.LITE)
        val cqueryResult: AnalysisProtosV2.CqueryResult =
            getProtoOutput<T>("deps(//test:my_target)", AnalysisProtosV2.CqueryResult.parser())
        val targetRule: Build.Rule =
            getRuleProtoByName(cqueryResult.getResultsList(), "//test:my_target").getTarget().getRule()

        com.google.common.truth.Subject.contains("//test:my_alias")
        assertThat(targetRule.getRuleInputList()).doesNotContain("//test:my_child")
        com.google.common.truth.Subject.contains("//test:my_alias")
        assertThat(targetRule.getConfiguredRuleInputList().stream().map({ s -> s.getLabel() }))
            .doesNotContain("//test:my_child")
    }

    private fun getKeyedTargetByLabel(keyedTargets: MutableSet<CqueryNode?>, label: String): CqueryNode {
        return com.google.common.collect.Iterables.getOnlyElement<CqueryNode>(
            keyedTargets.stream()
                .filter { t: CqueryNode? -> label == t.getLabel().getCanonicalForm() }
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<CqueryNode?>()))
    }

    private fun getConfigurationForId(configurations: MutableList<Configuration>, id: Int): Configuration {
        return configurations.stream().filter { c: Configuration -> c.getId() === id }.findAny().orElseThrow()
    }

    private fun getRuleProtoByName(
        resultsList: MutableList<AnalysisProtosV2.ConfiguredTarget>, s: String
    ): AnalysisProtosV2.ConfiguredTarget {
        return resultsList.stream()
            .filter { result: AnalysisProtosV2.ConfiguredTarget -> s == result.getTarget().getRule().getName() }
            .findAny()
            .orElseThrow()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAlias() {
        val ruleClassProvider: ConfiguredRuleClassProvider? = setRuleClassProviders(this.simpleRule).build()
        helper.useRuleClassProvider(ruleClassProvider)

        writeFile(
            "test/BUILD",
            """
        simple_rule(name = "my_rule")

        alias(
            name = "my_alias",
            actual = ":my_rule",
        )
        
        """.trimIndent()
        )

        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val alias: AnalysisProtosV2.ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                getProtoOutput<T?>("//test:my_alias", AnalysisProtosV2.CqueryResult.parser())
                    .getResultsList()
            )

        assertThat(alias.getTarget().getRule().getName()).isEqualTo("//test:my_alias")
        assertThat(alias.getTarget().getRule().getRuleInputCount()).isEqualTo(1)
        assertThat(alias.getTarget().getRule().getRuleInput(0)).isEqualTo("//test:my_rule")
    }

    /* See b/209787345 for context. */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAlias_withSelect() {
        val ruleClassProvider: ConfiguredRuleClassProvider? = setRuleClassProviders(this.simpleRule).build()
        helper.useRuleClassProvider(ruleClassProvider)

        writeFile(
            "test/BUILD",
            """
        alias(
            name = "my_alias_rule",
            actual = select({
                ":config1": ":target1",
                "//conditions:default": ":target2",
            }),
        )

        config_setting(
            name = "config1",
            values = {"foo": "woof"},
        )

        simple_rule(name = "target1")

        simple_rule(name = "target2")
        
        """.trimIndent()
        )
        getHelper().useConfiguration("--foo=woof")
        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)

        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val myAliasRuleProto: MutableList<AnalysisProtosV2.ConfiguredTarget?> =
            getProtoOutput<T?>(
                "deps(//test:my_alias_rule)",
                AnalysisProtosV2.CqueryResult.parser()
            )
                .getResultsList()

        val depNames: MutableList<String?> = java.util.ArrayList<String?>(myAliasRuleProto.size)
        myAliasRuleProto.forEach(
            java.util.function.Consumer { configuredTarget: AnalysisProtosV2.ConfiguredTarget? ->
                depNames.add(
                    configuredTarget.getTarget().getRule().getName()
                )
            })
        Truth.assertThat(depNames) // The alias also includes platform info since aliases with select() trigger toolchain
            // resolution. We're not interested in those here.
            .containsAtLeast("//test:my_alias_rule", "//test:config1", "//test:target1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllOutputFormatsEquivalentToProtoOutput() {
        val depsRule: MockRule =
            MockRule {
                MockRule.define(
                    "my_rule",
                    { builder, env -> builder.add(attr("deps", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)) })
            }
        val ruleClassProvider: ConfiguredRuleClassProvider? = setRuleClassProviders(depsRule).build()
        helper.useRuleClassProvider(ruleClassProvider)

        writeFile(
            "test/BUILD",
            """
        my_rule(
            name = "my_rule",
            deps = [
                "lasagna.java",
                "naps.java",
            ],
        )
        
        """.trimIndent()
        )
        val prototype: AnalysisProtosV2.CqueryResult = AnalysisProtosV2.CqueryResult.getDefaultInstance()
        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val protoOutput: AnalysisProtosV2.CqueryResult? =
            getProtoOutput<T?>("//test:*", prototype.getParserForType())

        val textprotoOutput: AnalysisProtosV2.CqueryResult? =
            getProtoFromTextprotoOutput("//test:*", prototype)

        val jsonprotoOutput: AnalysisProtosV2.CqueryResult? =
            getProtoFromJsonprotoOutput("//test:*", prototype)

        val streamedProtoOutput: com.google.common.collect.ImmutableList<AnalysisProtosV2.CqueryResult> =
            getStreamedProtoOutput<T>("//test:*", prototype.getParserForType())
        val combinedStreamedProtoBuilder: AnalysisProtosV2.CqueryResult.Builder =
            AnalysisProtosV2.CqueryResult.newBuilder()
        for (result in streamedProtoOutput) {
            if (!result.getResultsList().isEmpty()) {
                combinedStreamedProtoBuilder.addAllResults(result.getResultsList())
            }
            if (!result.getConfigurationsList().isEmpty()) {
                combinedStreamedProtoBuilder.addAllConfigurations(result.getConfigurationsList())
            }
        }

        assertThat(textprotoOutput).ignoringRepeatedFieldOrder().isEqualTo(protoOutput)
        assertThat(jsonprotoOutput).ignoringRepeatedFieldOrder().isEqualTo(protoOutput)
        assertThat(combinedStreamedProtoBuilder.build())
            .ignoringRepeatedFieldOrder()
            .isEqualTo(protoOutput)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllOutputFormatsEquivalentToProtoOutput_noIncludeConfigurations() {
        options.setProtoIncludeConfigurations(false)
        val depsRule: MockRule =
            MockRule {
                MockRule.define(
                    "my_rule",
                    { builder, env -> builder.add(attr("deps", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)) })
            }
        val ruleClassProvider: ConfiguredRuleClassProvider? = setRuleClassProviders(depsRule).build()
        helper.useRuleClassProvider(ruleClassProvider)

        writeFile(
            "test/BUILD",
            """
        my_rule(
            name = "my_rule",
            deps = [
                "lasagna.java",
                "naps.java",
            ],
        )
        
        """.trimIndent()
        )
        val prototype: Build.QueryResult = Build.QueryResult.getDefaultInstance()
        helper.setQuerySettings(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
        val protoOutput: Build.QueryResult? = getProtoOutput<T?>("//test:*", prototype.getParserForType())

        val textprotoOutput: Build.QueryResult? = getProtoFromTextprotoOutput("//test:*", prototype)

        val jsonprotoOutput: Build.QueryResult? = getProtoFromJsonprotoOutput("//test:*", prototype)

        val streamedProtoOutput: com.google.common.collect.ImmutableList<Build.QueryResult> =
            getStreamedProtoOutput<T>("//test:*", prototype.getParserForType())
        val combinedStreamedProtoBuilder: Build.QueryResult.Builder = Build.QueryResult.newBuilder()
        for (result in streamedProtoOutput) {
            if (!result.getTargetList().isEmpty()) {
                combinedStreamedProtoBuilder.addAllTarget(result.getTargetList())
            }
        }

        assertThat(textprotoOutput).ignoringRepeatedFieldOrder().isEqualTo(protoOutput)
        assertThat(jsonprotoOutput).ignoringRepeatedFieldOrder().isEqualTo(protoOutput)
        assertThat(combinedStreamedProtoBuilder.build())
            .ignoringRepeatedFieldOrder()
            .isEqualTo(protoOutput)
    }

    private val simpleRule: MockRule
        get() = MockRule { MockRule.define("simple_rule") }

    @Throws(java.lang.Exception::class)
    private fun <T : Message?> getProtoOutput(queryExpression: String?, parser: com.google.protobuf.Parser<T?>): T? {
        val `in`: java.io.InputStream = queryAndGetInputStream(queryExpression, OutputType.BINARY)
        return parser.parseFrom(`in`, ExtensionRegistry.getEmptyRegistry())
    }

    @Throws(java.lang.Exception::class)
    private fun <T : Message?> getStreamedProtoOutput(
        queryExpression: String?, parser: com.google.protobuf.Parser<T?>
    ): com.google.common.collect.ImmutableList<T?> {
        val `in`: java.io.InputStream = queryAndGetInputStream(queryExpression, OutputType.DELIMITED_BINARY)
        val builder: com.google.common.collect.ImmutableList.Builder<T?> =
            com.google.common.collect.ImmutableList.Builder<T?>()
        var result: T?
        while ((parser.parseDelimitedFrom(`in`, ExtensionRegistry.getEmptyRegistry()).also { result = it }) != null) {
            builder.add(result)
        }
        return builder.build()
    }

    @Throws(java.lang.Exception::class)
    private fun <T : Message?> getProtoFromTextprotoOutput(queryExpression: String?, prototype: T?): T? {
        val `in`: java.io.InputStream = queryAndGetInputStream(queryExpression, OutputType.TEXT)
        val builder: Message.Builder = prototype.newBuilderForType()
        TextFormat.getParser().merge(java.io.InputStreamReader(`in`, java.nio.charset.StandardCharsets.UTF_8), builder)
        val message = builder.build() as T?
        return message
    }

    @Throws(java.lang.Exception::class)
    private fun <T : Message?> getProtoFromJsonprotoOutput(queryExpression: String?, prototype: T?): T? {
        val `in`: java.io.InputStream = queryAndGetInputStream(queryExpression, OutputType.JSON)
        val builder: Message.Builder = prototype.newBuilderForType()
        JsonFormat.parser().merge(java.io.InputStreamReader(`in`, java.nio.charset.StandardCharsets.UTF_8), builder)
        val message = builder.build() as T?
        return message
    }

    @Throws(java.lang.Exception::class)
    private fun queryAndGetInputStream(queryExpression: String?, outputType: OutputType?): java.io.InputStream {
        val expression: QueryExpression =
            com.google.devtools.build.lib.query2.engine.QueryParser.parse(queryExpression, getDefaultFunctions())
        val targetPatternSet: MutableSet<String?> = LinkedHashSet<String?>()
        expression.collectTargetPatterns(targetPatternSet)
        val env: PostAnalysisQueryEnvironment<CqueryNode?> =
            (helper as ConfiguredTargetQueryHelper).getPostAnalysisQueryEnvironment(targetPatternSet)
        val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val callback: ProtoOutputFormatterCallback =
            ProtoOutputFormatterCallback(
                reporter,
                options,
                out,
                getHelper().getSkyframeExecutor(),
                env.getAccessor(),
                options
                    .getAspectDeps()
                    .createResolver(getHelper().getPackageManager(), NullEventHandler.INSTANCE),
                outputType,
                LabelPrinter.legacy()
            )
        env.evaluateQuery(expression, callback)
        return ByteArrayInputStream(out.toByteArray())
    }
}
