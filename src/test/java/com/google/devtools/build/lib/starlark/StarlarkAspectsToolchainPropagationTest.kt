// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests for Starlark aspects propagation to targets toolchain dependencies.  */
@RunWith(TestParameterInjector::class)
class StarlarkAspectsToolchainPropagationTest : AnalysisTestCase() {
    /**
     * Sets up 3 toolchain rules:
     * 
     * 
     * test_toolchain: has no attribute dependency and no advertised providers
     * 
     * 
     * test_toolchain_with_provider: has an advertised provider but no attribute dependency
     * 
     * 
     * test_toolchain_with_dep: has an attribute dependency but no advertised providers
     * 
     * 
     * We also set up 3 toolchain types:
     * 
     * 
     * toolchain_type_1: resolved by `foo` of rule `test_toolchain`
     * 
     * 
     * toolchain_type_2: resolved by `foo_with_provider` of rule `test_toolchain_with_provider`
     * 
     * 
     * toolchain_type_3: resolved by `foo_with_dep` of rule `test_toolchain_with_dep`
     * 
     * 
     * Toolchain `foo_for_all` resolved both toolchain_type_2 and toolchain_type_3
     */
    @Throws(java.lang.Exception::class)
    fun createToolchainsAndPlatforms() {
        scratch.overwriteFile(
            "rule/test_toolchain.bzl",
            """
        MyProvider = provider()

        def _impl(ctx):
            toolchain = platform_common.ToolchainInfo(
                tool = ctx.executable._tool,
                files_to_run = ctx.attr._tool[DefaultInfo].files_to_run,
            )
            my_provider = MyProvider(value = str(ctx.label))
            vars = platform_common.TemplateVariableInfo(ctx.attr.vars)
            return [toolchain, my_provider, vars]

        test_toolchain = rule(
            implementation = _impl,
            attrs = {
                "vars": attr.string_dict(),
                "_tool": attr.label(
                    default = "//toolchain:a_tool",
                    executable = True,
                    cfg = "exec",
                ),
            },
        )

        test_toolchain_with_provider = rule(
            implementation = _impl,
            attrs = {
                "vars": attr.string_dict(),
                "_tool": attr.label(
                    default = "//toolchain:a_tool",
                    executable = True,
                    cfg = "exec",
                ),
            },
            provides = [MyProvider]
        )

        test_toolchain_with_dep = rule(
            implementation = _impl,
            attrs = {
                "vars": attr.string_dict(),
                "_tool": attr.label(
                    default = "//toolchain:a_tool",
                    executable = True,
                    cfg = "exec",
                ),
                "toolchain_dep": attr.label(),
            },
        )

        

        """.trimIndent()
        )
        scratch.overwriteFile(
            "rule/BUILD",
            """
        exports_files(["test_toolchain/bzl"])

        toolchain_type(name = "toolchain_type_1")
        alias(name = "toolchain_type_1_alias", actual = ":toolchain_type_1")

        toolchain_type(name = "toolchain_type_2")

        toolchain_type(name = "toolchain_type_3")
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "toolchain/BUILD",
            """
        load("//rule:test_toolchain.bzl", "test_toolchain",
              "test_toolchain_with_provider", "test_toolchain_with_dep")

        genrule(
            name = "a_tool",
            outs = ["atool"],
            cmd = "",
            executable = True,
        )

        test_toolchain(
            name = "foo",
            vars = {
                "type_1_key": "type_1_value",
            },
        )

        toolchain(
            name = "foo_toolchain",
            toolchain = ":foo",
            toolchain_type = "//rule:toolchain_type_1",
        )

        test_toolchain_with_provider(
            name = "foo_with_provider",
            vars = {
                "type_2_key": "type_2_value",
            },
        )

        toolchain(
            name = "foo_toolchain_with_provider",
            toolchain = ":foo_with_provider",
            toolchain_type = "//rule:toolchain_type_2",
        )

        filegroup(name = "toolchain_dep")

        test_toolchain_with_dep(
            name = "foo_with_dep",
            toolchain_dep = ":toolchain_dep",
        )

        toolchain(
            name = "foo_toolchain_with_dep",
            toolchain = ":foo_with_dep",
            toolchain_type = "//rule:toolchain_type_3",
        )

        test_toolchain(name = "foo_for_all")

        toolchain(
            name = "foo_type_2",
            toolchain = ":foo_for_all",
            toolchain_type = "//rule:toolchain_type_2",
        )

        toolchain(
            name = "foo_type_3",
            toolchain = ":foo_for_all",
            toolchain_type = "//rule:toolchain_type_3",
        )

        toolchain(
            name = "foo_toolchain_exec_1",
            toolchain = ":foo",
            exec_compatible_with = ['//platforms:constraint_1'],
            toolchain_type = "//rule:toolchain_type_1",
        )

        toolchain(
            name = "foo_toolchain_exec_2",
            toolchain = ":foo",
            exec_compatible_with = ['//platforms:constraint_2'],
            toolchain_type = "//rule:toolchain_type_2",
        )
        
        """.trimIndent()
        )

        scratch.overwriteFile(
            "platforms/BUILD",
            """
        constraint_setting(name = "setting_1")
        constraint_setting(name = "setting_2")

        constraint_value(
            name = "constraint_1",
            constraint_setting = ":setting_1",
        )
        constraint_value(
            name = "constraint_2",
            constraint_setting = ":setting_2",
        )

        platform(
            name = "platform_1",
            constraint_values = [":constraint_1"],
        )
        platform(
            name = "platform_2",
            constraint_values = [":constraint_2"],
            exec_properties = {
                "watermelon.ripeness": "unripe",
                "watermelon.color": "red",
            },
        )
        
        """.trimIndent()
        )
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withToolchainTargetConstraints("@@//platforms:constraint_1")
                    .withToolchainExecConstraints("@@//platforms:constraint_1")
                    .withCpu("fake")
            )
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        createToolchainsAndPlatforms()
    }

    @org.junit.Test
    @TestParameters(
        "{autoExecGroups: True}", "{autoExecGroups: False}"
    )
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToToolchain_singleDepAdded(autoExecGroups: String?) {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
        )

        no_toolchain_aspect = aspect(
          implementation = _impl,
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val unused: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "//test:defs.bzl%toolchain_aspect", "//test:defs.bzl%no_toolchain_aspect"
                ),
                "//test:t1"
            )

        val toolchainAspect: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect"
                )
            )
        val toolchainAspectNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(toolchainAspect) })
                .findFirst()
                .orElse(null)
        assertThat(toolchainAspectNode).isNotNull()

        val noToolchainAspect: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%no_toolchain_aspect"
                )
            )
        val noToolchainAspectNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(noToolchainAspect) })
                .findFirst()
                .orElse(null)
        assertThat(noToolchainAspectNode).isNotNull()

        val toolchainAspectDirectDeps: com.google.common.collect.ImmutableSet<out Any> =
            com.google.common.collect.ImmutableSet.copyOf(
                com.google.common.collect.Iterables.filter(
                    toolchainAspectNode.getDirectDeps(),
                    SkyKey::class.java
                )
            )
        val noToolchainAspectDirectDeps: com.google.common.collect.ImmutableSet<out Any?> =
            com.google.common.collect.ImmutableSet.copyOf(
                com.google.common.collect.Iterables.filter(
                    noToolchainAspectNode.getDirectDeps(),
                    SkyKey::class.java
                )
            )

        // only one extra dependency is added for the toolchain propagating aspect
        Truth.assertThat(toolchainAspectDirectDeps.size - noToolchainAspectDirectDeps.size).isEqualTo(1)
        Truth.assertThat(toolchainAspectDirectDeps).containsAtLeastElementsIn(noToolchainAspectDirectDeps)

        // the extra dependency is the aspect application on the target's resolved toolchain
        val aspectOnToolchainDep: Any =
            com.google.common.collect.Iterables.getOnlyElement(
                com.google.common.collect.Sets.difference(toolchainAspectDirectDeps, noToolchainAspectDirectDeps)
            )
        Truth.assertThat(aspectOnToolchainDep).isInstanceOf(AspectKey::class.java)
        assertThat((aspectOnToolchainDep as AspectKey).getAspectName())
            .isEqualTo("//test:defs.bzl%toolchain_aspect")
        assertThat((aspectOnToolchainDep as AspectKey).getLabel().toString())
            .isEqualTo("//toolchain:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToExecGpToolchain_singleDepAdded() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
        )

        no_toolchain_aspect = aspect(
          implementation = _impl,
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          exec_groups = {"gp": exec_group(toolchains = ['//rule:toolchain_type_1'])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain")

        val unused: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "//test:defs.bzl%toolchain_aspect", "//test:defs.bzl%no_toolchain_aspect"
                ),
                "//test:t1"
            )

        val toolchainAspect: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect"
                )
            )
        val toolchainAspectNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(toolchainAspect) })
                .findFirst()
                .orElse(null)
        assertThat(toolchainAspectNode).isNotNull()

        val noToolchainAspect: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%no_toolchain_aspect"
                )
            )
        val noToolchainAspectNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(noToolchainAspect) })
                .findFirst()
                .orElse(null)
        assertThat(noToolchainAspectNode).isNotNull()

        val toolchainAspectDirectDeps: com.google.common.collect.ImmutableSet<out Any> =
            com.google.common.collect.ImmutableSet.copyOf(
                com.google.common.collect.Iterables.filter(
                    toolchainAspectNode.getDirectDeps(),
                    SkyKey::class.java
                )
            )
        val noToolchainAspectDirectDeps: com.google.common.collect.ImmutableSet<out Any?> =
            com.google.common.collect.ImmutableSet.copyOf(
                com.google.common.collect.Iterables.filter(
                    noToolchainAspectNode.getDirectDeps(),
                    SkyKey::class.java
                )
            )

        // only one extra dependency is added for the toolchain propagating aspect
        Truth.assertThat(toolchainAspectDirectDeps.size - noToolchainAspectDirectDeps.size).isEqualTo(1)
        Truth.assertThat(toolchainAspectDirectDeps).containsAtLeastElementsIn(noToolchainAspectDirectDeps)

        // the extra dependency is the aspect application on the target's resolved toolchain
        val aspectOnToolchainDep: Any =
            com.google.common.collect.Iterables.getOnlyElement(
                com.google.common.collect.Sets.difference(toolchainAspectDirectDeps, noToolchainAspectDirectDeps)
            )
        Truth.assertThat(aspectOnToolchainDep).isInstanceOf(AspectKey::class.java)
        assertThat((aspectOnToolchainDep as AspectKey).getAspectName())
            .isEqualTo("//test:defs.bzl%toolchain_aspect")
        assertThat((aspectOnToolchainDep as AspectKey).getLabel().toString())
            .isEqualTo("//toolchain:foo")
    }

    @org.junit.Test
    @TestParameters(
        "{autoExecGroups: True}", "{autoExecGroups: False}"
    )
    @Throws(java.lang.Exception::class)
    fun aspectHasToolchains_dependencyEdgeCreated(autoExecGroups: String?) {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
          toolchains = ['//rule:toolchain_type_2'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:foo_toolchain_with_provider",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        val toolchainAspect: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect"
                )
            )
        val toolchainAspectNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(toolchainAspect) })
                .findFirst()
                .orElse(null)
        assertThat(toolchainAspectNode).isNotNull()

        // A dependency edge is created from the aspect to its own toolchain but not to the target's
        // toolchain.
        val aspectConfiguredTargetDeps: Iterable<T> =
            com.google.common.collect.Iterables.transform<F?, T>(
                com.google.common.collect.Iterables.filter<T?>(
                    toolchainAspectNode.getDirectDeps(),
                    com.google.common.base.Predicate { d: T? -> d is ConfiguredTargetKey }),
                com.google.common.base.Function { d: F? -> (d as ConfiguredTargetKey).getLabel().toString() })
        Truth.assertThat(aspectConfiguredTargetDeps)
            .containsExactly("//toolchain:foo_with_provider", "//test:t1")
    }

    @org.junit.Test
    @TestParameters(
        "{autoExecGroups: True}", "{autoExecGroups: False}"
    )
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToToolchainUsingToolchainTypeAlias(autoExecGroups: String?) {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
        )

        no_toolchain_aspect = aspect(
          implementation = _impl,
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1_alias'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val unused: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "//test:defs.bzl%toolchain_aspect", "//test:defs.bzl%no_toolchain_aspect"
                ),
                "//test:t1"
            )

        val toolchainAspect: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect"
                )
            )
        val toolchainAspectNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(toolchainAspect) })
                .findFirst()
                .orElse(null)
        assertThat(toolchainAspectNode).isNotNull()

        val noToolchainAspect: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%no_toolchain_aspect"
                )
            )
        val noToolchainAspectNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(noToolchainAspect) })
                .findFirst()
                .orElse(null)
        assertThat(noToolchainAspectNode).isNotNull()

        val toolchainAspectDirectDeps: com.google.common.collect.ImmutableSet<out Any> =
            com.google.common.collect.ImmutableSet.copyOf(
                com.google.common.collect.Iterables.filter(
                    toolchainAspectNode.getDirectDeps(),
                    SkyKey::class.java
                )
            )
        val noToolchainAspectDirectDeps: com.google.common.collect.ImmutableSet<out Any?> =
            com.google.common.collect.ImmutableSet.copyOf(
                com.google.common.collect.Iterables.filter(
                    noToolchainAspectNode.getDirectDeps(),
                    SkyKey::class.java
                )
            )

        // only one extra dependency is added for the toolchain propagating aspect
        Truth.assertThat(toolchainAspectDirectDeps.size - noToolchainAspectDirectDeps.size).isEqualTo(1)
        Truth.assertThat(toolchainAspectDirectDeps).containsAtLeastElementsIn(noToolchainAspectDirectDeps)

        // the extra dependency is the aspect application on the target's resolved toolchain
        val aspectOnToolchainDep: Any =
            com.google.common.collect.Iterables.getOnlyElement(
                com.google.common.collect.Sets.difference(toolchainAspectDirectDeps, noToolchainAspectDirectDeps)
            )
        Truth.assertThat(aspectOnToolchainDep).isInstanceOf(AspectKey::class.java)
        assertThat((aspectOnToolchainDep as AspectKey).getAspectName())
            .isEqualTo("//test:defs.bzl%toolchain_aspect")
        assertThat((aspectOnToolchainDep as AspectKey).getLabel().toString())
            .isEqualTo("//toolchain:foo")
    }

    @org.junit.Test
    @TestParameters(
        "{autoExecGroups: True}", "{autoExecGroups: False}"
    )
    @Throws(java.lang.Exception::class)
    fun toolchainPropagationBasedOnAspectRequiredProviders(autoExecGroups: String?) {
        scratch.file(
            "test/defs.bzl",
            """
        load("//rule:test_toolchain.bzl", "MyProvider")

        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1', '//rule:toolchain_type_2'],
          required_providers = [MyProvider],
        )

        def _rule_impl(ctx):
          return [MyProvider()]

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
          exec_groups = {"gp": exec_group(toolchains = ['//rule:toolchain_type_2'])},
          provides = [MyProvider],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:foo_toolchain_with_provider",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        val aspectOnTarget: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect"
                )
            )
        val aspectOnTargetNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(aspectOnTarget) })
                .findFirst()
                .orElse(null)
        assertThat(aspectOnTargetNode).isNotNull()

        // aspect propagated only to //toolchain:foo_with_provider
        val aspectOnToolchain: T? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.filter(aspectOnTargetNode.getDirectDeps(), AspectKey::class.java)
            )
        assertThat(aspectOnToolchain.getLabel().toString()).isEqualTo("//toolchain:foo_with_provider")
        assertThat(aspectOnToolchain.getAspectName()).isEqualTo("//test:defs.bzl%toolchain_aspect")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToToolchainDeps() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_3'],
          attr_aspects = ['toolchain_dep'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          exec_groups = {"gp": exec_group(toolchains = ['//rule:toolchain_type_3'])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain_with_dep")

        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        val aspectOnTarget: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect"
                )
            )
        val aspectOnTargetNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(aspectOnTarget) })
                .findFirst()
                .orElse(null)
        assertThat(aspectOnTargetNode).isNotNull()

        val aspectOnToolchain: T? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.filter(aspectOnTargetNode.getDirectDeps(), AspectKey::class.java)
            )
        val aspectOnToolchainNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(aspectOnToolchain) })
                .findFirst()
                .orElse(null)
        assertThat(aspectOnToolchainNode).isNotNull()
        assertThat(aspectOnToolchain.getLabel().toString()).isEqualTo("//toolchain:foo_with_dep")

        val aspectOnToolchainDep: T? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.filter(aspectOnToolchainNode.getDirectDeps(), AspectKey::class.java)
            )
        assertThat(aspectOnToolchainDep.getLabel().toString()).isEqualTo("//toolchain:toolchain_dep")
        assertThat(aspectOnToolchainDep.getAspectName()).isEqualTo("//test:defs.bzl%toolchain_aspect")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun requiredAspectPropagatesToToolchain() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        required_aspect = aspect(implementation = _impl)

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
          requires = [required_aspect],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          exec_groups = {"gp": exec_group(toolchains = ['//rule:toolchain_type_1'])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain")

        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        val aspectOnTarget: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect"
                )
            )
        val aspectOnTargetNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(aspectOnTarget) })
                .findFirst()
                .orElse(null)
        assertThat(aspectOnTargetNode).isNotNull()

        val aspectsDeps: Iterable<T> =
            com.google.common.collect.Iterables.transform<F?, T>(
                com.google.common.collect.Iterables.filter(aspectOnTargetNode.getDirectDeps(), AspectKey::class.java),
                com.google.common.base.Function { k: F? -> k.getAspectName() + " on " + k.getLabel().toString() })
        Truth.assertThat(aspectsDeps).hasSize(3)
        // toolchain_aspect requires required_aspect so required_aspect will be propagated before
        // toolchain_aspect to //test:t1 and its toolchain
        Truth.assertThat(aspectsDeps)
            .containsExactly(
                "//test:defs.bzl%required_aspect on //test:t1",
                "//test:defs.bzl%toolchain_aspect on //toolchain:foo",
                "//test:defs.bzl%required_aspect on //toolchain:foo"
            )
    }

    @org.junit.Test
    @TestParameters(
        "{autoExecGroups: True}", "{autoExecGroups: False}"
    )
    @Throws(java.lang.Exception::class)
    fun aspectOnAspectPropagateToToolchain(autoExecGroups: String?) {
        scratch.file(
            "test/defs.bzl",
            """
        Prov1 = provider()
        Prov2 = provider()

        def _impl(target, ctx):
          return []

        def _impl_1(target, ctx):
          return [Prov1()]

        def _impl_2(target, ctx):
          return [Prov2()]

        toolchain_aspect_1 = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
          required_aspect_providers = [Prov1]
        )

        no_toolchain_aspect = aspect(
          implementation = _impl_1,
          provides = [Prov1],
          required_aspect_providers = [Prov2]
        )

        toolchain_aspect_2 = aspect(
          implementation = _impl_2,
          toolchains_aspects = ['//rule:toolchain_type_1'],
          provides = [Prov2],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val unused: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "//test:defs.bzl%toolchain_aspect_2",
                    "//test:defs.bzl%no_toolchain_aspect", "//test:defs.bzl%toolchain_aspect_1"
                ),
                "//test:t1"
            )

        val aspectOnTarget: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect_1"
                )
            )
        assertThat(aspectOnTarget.baseKeys).hasSize(1)
        assertThat(aspectOnTarget.baseKeys.get(0).getAspectName())
            .isEqualTo("//test:defs.bzl%no_toolchain_aspect")

        val aspectOnTargetNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(aspectOnTarget) })
                .findFirst()
                .orElse(null)
        assertThat(aspectOnTargetNode).isNotNull()

        val aspectsOnToolchain: Iterable<T> =
            com.google.common.collect.Iterables.transform<F?, T>(
                com.google.common.collect.Iterables.filter(aspectOnTargetNode.getDirectDeps(), AspectKey::class.java),
                com.google.common.base.Function { k: F? -> k.getAspectName() + " on " + k.getLabel().toString() })
        Truth.assertThat(aspectsOnToolchain).hasSize(4)
        // Only `toolchain_aspect_1` and `toolchain_aspect_2` are propagated to the toolchain
        Truth.assertThat(aspectsOnToolchain)
            .containsExactly(
                "//test:defs.bzl%toolchain_aspect_2 on //test:t1",
                "//test:defs.bzl%no_toolchain_aspect on //test:t1",
                "//test:defs.bzl%toolchain_aspect_1 on //toolchain:foo",
                "//test:defs.bzl%toolchain_aspect_2 on //toolchain:foo"
            )

        val toolchainAspect1: T? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.filter<T?>(
                    com.google.common.collect.Iterables.filter(
                        aspectOnTargetNode.getDirectDeps(),
                        AspectKey::class.java
                    ),
                    com.google.common.base.Predicate { k: T? ->
                        k.getAspectName().equals("//test:defs.bzl%toolchain_aspect_1")
                                && k.getLabel().toString().equals("//toolchain:foo")
                    })
            )
        // Since `toolchain_aspect_1` only depends on `no_toolchain_aspect`, it will have no base keys
        // when applied on the toolchain.
        assertThat(toolchainAspect1.baseKeys).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execGroupWithMultipleToolchainTypes_aspectsPropagateToRelevantTypes() {
        scratch.file(
            "test/defs.bzl",
            """
        Prov1 = provider()
        Prov2 = provider()

        def _impl(target, ctx):
          return []

        def _impl_1(target, ctx):
          return [Prov1()]

        def _impl_2(target, ctx):
          return [Prov2()]

        toolchain_aspect_0 = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
          required_aspect_providers = [[Prov1], [Prov2]]
        )

        toolchain_aspect_1 = aspect(
          implementation = _impl_1,
          toolchains_aspects = ['//rule:toolchain_type_3'],
          provides = [Prov1],
          required_aspect_providers = [Prov2]
        )

        toolchain_aspect_2 = aspect(
          implementation = _impl_2,
          toolchains_aspects = ['//rule:toolchain_type_1'],
          provides = [Prov2],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
          },
          exec_groups = {"gp": exec_group(
              toolchains = ['//rule:toolchain_type_1', '//rule:toolchain_type_3'])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:foo_toolchain_with_dep"
        )

        val unused: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "//test:defs.bzl%toolchain_aspect_2",
                    "//test:defs.bzl%toolchain_aspect_1", "//test:defs.bzl%toolchain_aspect_0"
                ),
                "//test:t1"
            )

        val aspectOnTarget: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect_0"
                )
            )
        val aspectOnTargetNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(aspectOnTarget) })
                .findFirst()
                .orElse(null)
        assertThat(aspectOnTargetNode).isNotNull()

        val aspectsOnToolchain: Iterable<T> =
            com.google.common.collect.Iterables.transform<F?, T>(
                com.google.common.collect.Iterables.filter(aspectOnTargetNode.getDirectDeps(), AspectKey::class.java),
                com.google.common.base.Function { k: F? -> k.getAspectName() + " on " + k.getLabel().toString() })
        Truth.assertThat(aspectsOnToolchain).hasSize(5)
        Truth.assertThat(aspectsOnToolchain)
            .containsExactly(
                "//test:defs.bzl%toolchain_aspect_1 on //test:t1",
                "//test:defs.bzl%toolchain_aspect_2 on //test:t1",  // toolchain_aspect_0 and toolchain_aspect_2 propagate to //toolchain:foo of
                // //rule:toolchain_type_1
                "//test:defs.bzl%toolchain_aspect_0 on //toolchain:foo",
                "//test:defs.bzl%toolchain_aspect_2 on //toolchain:foo",  // toolchain_aspect_1 propagates to //toolchain:foo_with_dep of //rule:toolchain_type_3
                "//test:defs.bzl%toolchain_aspect_1 on //toolchain:foo_with_dep"
            )

        val toolchainAspect1: T? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.filter<T?>(
                    com.google.common.collect.Iterables.filter(
                        aspectOnTargetNode.getDirectDeps(),
                        AspectKey::class.java
                    ),
                    com.google.common.base.Predicate { k: T? ->
                        k.getAspectName().equals("//test:defs.bzl%toolchain_aspect_0")
                                && k.getLabel().toString().equals("//toolchain:foo")
                    })
            )
        // Since `toolchain_aspect_0` depends on `toolchain_aspect_2` when applied on //toolchain:foo,
        assertThat(com.google.common.collect.Iterables.getOnlyElement<Any?>(toolchainAspect1.baseKeys).getAspectName())
            .isEqualTo("//test:defs.bzl%toolchain_aspect_2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainTypesResolvedToSameToolchain_aspectsPropagateToSameToolchain() {
        scratch.file(
            "test/defs.bzl",
            """
        prov = provider()

        def _impl(target, ctx):
          return []

        def _impl_1(target, ctx):
          return [prov()]

        toolchain_aspect_1 = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_2'],
          required_aspect_providers = [prov]
        )

        toolchain_aspect_2 = aspect(
          implementation = _impl_1,
          toolchains_aspects = ['//rule:toolchain_type_3'],
          provides = [prov],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          exec_groups = {"gp": exec_group(
              toolchains = ['//rule:toolchain_type_2', '//rule:toolchain_type_3'])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_type_2", "--extra_toolchains=//toolchain:foo_type_3"
        )

        val unused: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "//test:defs.bzl%toolchain_aspect_2", "//test:defs.bzl%toolchain_aspect_1"
                ),
                "//test:t1"
            )

        val aspectOnTarget: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect_1"
                )
            )
        val aspectOnTargetNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(aspectOnTarget) })
                .findFirst()
                .orElse(null)
        assertThat(aspectOnTargetNode).isNotNull()

        val aspectsOnToolchain: Iterable<T> =
            com.google.common.collect.Iterables.transform<F?, T>(
                com.google.common.collect.Iterables.filter(aspectOnTargetNode.getDirectDeps(), AspectKey::class.java),
                com.google.common.base.Function { k: F? -> k.getAspectName() + " on " + k.getLabel().toString() })
        Truth.assertThat(aspectsOnToolchain).hasSize(3)

        Truth.assertThat(aspectsOnToolchain)
            .containsExactly(
                "//test:defs.bzl%toolchain_aspect_2 on //test:t1",  // both aspects propagated to //toolchain:foo_for_all because it resolves both the
                // toolchain types
                "//test:defs.bzl%toolchain_aspect_1 on //toolchain:foo_for_all",
                "//test:defs.bzl%toolchain_aspect_2 on //toolchain:foo_for_all"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainTypesResolvedToSameToolchainDiffExecPlatform_aspectPropagateTwice() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          exec_groups = {
            "gp1": exec_group(
              toolchains = ['//rule:toolchain_type_1'],
              exec_compatible_with = ['//platforms:constraint_2']
              ),
            "gp2": exec_group(toolchains = ['//rule:toolchain_type_1'])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--extra_execution_platforms=//platforms:platform_1,//platforms:platform_2"
        )

        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        val aspectOnTarget: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect"
                )
            )
        val aspectOnTargetNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey().equals(aspectOnTarget) })
                .findFirst()
                .orElse(null)
        assertThat(aspectOnTargetNode).isNotNull()

        val aspectsOnToolchain: Iterable<T> =
            com.google.common.collect.Iterables.transform<F?, T>(
                com.google.common.collect.Iterables.filter(aspectOnTargetNode.getDirectDeps(), AspectKey::class.java),
                com.google.common.base.Function { k: F? ->
                    (k.getAspectName()
                            + " on "
                            + k.getLabel().toString()
                            + ", exec_platform: "
                            + k.getBaseConfiguredTargetKey().getExecutionPlatformLabel().toString())
                })
        Truth.assertThat(aspectsOnToolchain).hasSize(2)
        // aspect propagated twice on the same toolchain target but with different execution platform
        Truth.assertThat(aspectsOnToolchain)
            .containsExactly(
                "//test:defs.bzl%toolchain_aspect on //toolchain:foo, exec_platform:"
                        + " //platforms:platform_2",
                "//test:defs.bzl%toolchain_aspect on //toolchain:foo, exec_platform:"
                        + " //platforms:platform_1"
            )
    }

    @org.junit.Test
    @TestParameters(
        "{autoExecGroups: True}", "{autoExecGroups: False}"
    )
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToToolchain_providersCollected(autoExecGroups: String?) {
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()
        def _impl(target, ctx):
          target_res = "toolchain_aspect has param = " + ctx.attr.param
          target_res += " on " + str(target.label)
          if platform_common.ToolchainInfo in target:
            target_res += " with tool in ToolchainInfo = "
            target_res += str(target[platform_common.ToolchainInfo].tool)

          result = [target_res]
          if ctx.rule.toolchains and '//rule:toolchain_type_1' in ctx.rule.toolchains:
              result.extend(ctx.rule.toolchains['//rule:toolchain_type_1'][AspectProvider].value)
          return [AspectProvider(value = result)]

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
          attrs = {
            "param": attr.string(),
          },
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val analysisResult: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
                com.google.common.collect.ImmutableMap.of<String?, String?>("param", "xxx"),
                "//test:t1"
            )

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "AspectProvider"
            )

        val value: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (configuredAspect.get(providerKey) as StarlarkInfo).getValue("value")
        Truth.assertThat(value)
            .containsExactly(
                "toolchain_aspect has param = xxx on @@//test:t1",
                "toolchain_aspect has param = xxx on @@//toolchain:foo with tool in ToolchainInfo ="
                        + " <generated file toolchain/atool>"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToExecGpToolchain_providersCollected() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()

        def _impl(target, ctx):
          target_res = "toolchain_aspect has param = " + ctx.attr.param
          target_res += " on " + str(target.label)
          if platform_common.ToolchainInfo in target:
            target_res += " with tool in ToolchainInfo = "
            target_res += str(target[platform_common.ToolchainInfo].tool)

          result = [target_res]
          if ctx.rule.exec_groups and 'gp' in ctx.rule.exec_groups:
              result.extend(
                  ctx.
                  rule.
                  exec_groups['gp'].
                  toolchains['//rule:toolchain_type_1'][AspectProvider].value)

          return [AspectProvider(value = result)]

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
          attrs = {
            "param": attr.string(),
          },
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          exec_groups = {"gp": exec_group(toolchains = ['//rule:toolchain_type_1'])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain")

        val analysisResult: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
                com.google.common.collect.ImmutableMap.of<String?, String?>("param", "xxx"),
                "//test:t1"
            )

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "AspectProvider"
            )

        val value: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (configuredAspect.get(providerKey) as StarlarkInfo).getValue("value")
        Truth.assertThat(value)
            .containsExactly(
                "toolchain_aspect has param = xxx on @@//test:t1",
                "toolchain_aspect has param = xxx on @@//toolchain:foo with tool in"
                        + " ToolchainInfo = <generated file toolchain/atool>"
            )
    }

    @org.junit.Test
    @TestParameters(
        "{autoExecGroups: True}", "{autoExecGroups: False}"
    )
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToToolchainUsingAlias_providersCollected(autoExecGroups: String?) {
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()
        def _impl(target, ctx):
          target_res = "toolchain_aspect has param = " + ctx.attr.param
          target_res += " on " + str(target.label)
          if platform_common.ToolchainInfo in target:
            target_res += " with tool in ToolchainInfo = "
            target_res += str(target[platform_common.ToolchainInfo].tool)

          result = [target_res]
          if ctx.rule.toolchains and '//rule:toolchain_type_1_alias' in ctx.rule.toolchains:
              result.extend(
                  ctx.rule.toolchains['//rule:toolchain_type_1_alias'][AspectProvider].value)
          return [AspectProvider(value = result)]

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1_alias'],
          attrs = {
            "param": attr.string(),
          },
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1_alias'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val analysisResult: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
                com.google.common.collect.ImmutableMap.of<String?, String?>("param", "xxx"),
                "//test:t1"
            )

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "AspectProvider"
            )

        val value: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (configuredAspect.get(providerKey) as StarlarkInfo).getValue("value")
        Truth.assertThat(value)
            .containsExactly(
                "toolchain_aspect has param = xxx on @@//test:t1",
                "toolchain_aspect has param = xxx on @@//toolchain:foo with tool in ToolchainInfo ="
                        + " <generated file toolchain/atool>"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToToolchain_cannotSeeToolchainInfoOfDeps() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()
        def _impl(target, ctx):
          if ctx.rule.toolchains and '//rule:toolchain_type_1' in ctx.rule.toolchains:
              print(ctx.rule.toolchains['//rule:toolchain_type_1'][platform_common.ToolchainInfo])

          return [AspectProvider(value = [])]

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain")

        reporter.removeHandler(failFastHandler)
        try {
            val unused: @NotNull AnalysisResult = update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
                "//test:t1"
            )
        } catch (unused: java.lang.Exception) {
            // expect to fail
        }
        assertContainsEvent(
            "<ToolchainAspectsProviders for toolchain target: //toolchain:foo> doesn't contain declared"
                    + " provider 'ToolchainInfo'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDoesNotPropagatesToToolchain_cannotSeeTargetToolchains(
        @TestParameter autoExecGroups: Boolean
    ) {
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()
        def _impl(target, ctx):
          print(ctx.rule.toolchains['//rule:toolchain_type_1'][AspectProvider])
          return [AspectProvider(value = [])]

        non_toolchain_aspect = aspect(
          implementation = _impl,
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        reporter.removeHandler(failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable {
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%non_toolchain_aspect"),
                    "//test:t1"
                )
            })
        assertContainsEvent(
            "Error: <ToolchainAspectsProviders for toolchain target: //toolchain:foo> doesn't contain"
                    + " declared provider 'AspectProvider'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDoesNotPropagatesToToolchain_cannotSeeTargetExecGroups(
        @TestParameter autoExecGroups: Boolean
    ) {
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()
        def _impl(target, ctx):
          print(ctx.rule.exec_groups['gp'].toolchains['//rule:toolchain_type_1'][AspectProvider])
          return [AspectProvider(value = [])]

        non_toolchain_aspect = aspect(
          implementation = _impl,
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          exec_groups = {"gp": exec_group(toolchains = ['//rule:toolchain_type_1'])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        reporter.removeHandler(failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable {
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%non_toolchain_aspect"),
                    "//test:t1"
                )
            })
        assertContainsEvent(
            "Error: <ToolchainAspectsProviders for toolchain target: //toolchain:foo> doesn't contain"
                    + " declared provider 'AspectProvider'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun requiredAspectPropagatesToToolchain_providersCollected() {
        scratch.file(
            "test/defs.bzl",
            """
        ToolchainAspectProvider = provider()
        RequiredAspectProvider = provider()

        def _toolchain_aspect_impl(target, ctx):
          target_res = "toolchain_aspect on " + str(target.label)
          target_res += " can see required_aspect (" + target[RequiredAspectProvider].value + ")"

          result = [target_res]
          if ctx.rule.toolchains and '//rule:toolchain_type_1' in ctx.rule.toolchains:
              result.extend(
                  ctx.rule.toolchains['//rule:toolchain_type_1'][ToolchainAspectProvider].value)
          return [ToolchainAspectProvider(value = result)]

        def _required_aspect_impl(target, ctx):
          target_res = "required_aspect on " + str(target.label)
          if platform_common.ToolchainInfo in target:
            target_res += " with tool in ToolchainInfo = "
            target_res += str(target[platform_common.ToolchainInfo].tool)

          return [RequiredAspectProvider(value = target_res)]

        required_aspect = aspect(implementation = _required_aspect_impl)

        toolchain_aspect = aspect(
          implementation = _toolchain_aspect_impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
          requires = [required_aspect],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain")

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        val configuredAspect: ConfiguredAspect =
            analysisResult
                .getAspectsMap()
                .get(getAspectKeys("//test:t1", "//test:defs.bzl%toolchain_aspect").get(0))

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "ToolchainAspectProvider"
            )

        val value: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (configuredAspect.get(providerKey) as StarlarkInfo).getValue("value")
        Truth.assertThat(value)
            .containsExactly(
                "toolchain_aspect on @@//test:t1 can see required_aspect (required_aspect on"
                        + " @@//test:t1)",
                ("toolchain_aspect on @@//toolchain:foo can see required_aspect (required_aspect on"
                        + " @@//toolchain:foo with tool in ToolchainInfo = <generated file"
                        + " toolchain/atool>)")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToToolchainFromRule_providersCollected() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()
        RuleProvider = provider()

        def _impl(target, ctx):
          result = ["toolchain_aspect on " + str(target.label)]

          if ctx.rule.toolchains and '//rule:toolchain_type_3' in ctx.rule.toolchains:
              result.extend(
                  ctx.rule.toolchains['//rule:toolchain_type_3'][AspectProvider].value)

          if hasattr(ctx.rule.attr, 'toolchain_dep'):
              result.extend(ctx.rule.attr.toolchain_dep[AspectProvider].value)

          return [AspectProvider(value = result)]

        toolchain_aspect = aspect(
            implementation = _impl,
            toolchains_aspects = ['//rule:toolchain_type_3'],
            attr_aspects = ['toolchain_dep'],
        )

        def _rule_1_impl(ctx):
          return [RuleProvider(value = ctx.attr.rule_dep[AspectProvider].value)]

        r1 = rule(
          implementation = _rule_1_impl,
          attrs = {
            "rule_dep": attr.label(aspects = [toolchain_aspect]),
          },
        )

        def _rule_2_impl(ctx):
          pass

        r2 = rule(
          implementation = _rule_2_impl,
          toolchains = ['//rule:toolchain_type_3'],
        )

        

        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1', 'r2')
        r1(name = 't1', rule_dep = ':t2')
        r2(name = 't2')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain_with_dep")

        val analysisResult: @NotNull AnalysisResult = update("//test:t1")

        val configuredTarget: T? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "RuleProvider"
            )

        val value: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (configuredTarget.get(providerKey) as StarlarkInfo).getValue("value")
        Truth.assertThat(value)
            .containsExactly(
                "toolchain_aspect on @@//test:t2",
                "toolchain_aspect on @@//toolchain:foo_with_dep",
                "toolchain_aspect on @@//toolchain:toolchain_dep"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainAspectOnOutputFile_notPropagatedToDeps() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()
        def _impl(target, ctx):
          return [AspectProvider(val="hi")]

        toolchain_aspect = aspect(
            implementation = _impl,
            toolchains_aspects = ['//rule:toolchain_type_1'],
            attr_aspects = ['dep'],
        )

        def _rule_1_impl(ctx):
          if ctx.outputs.out:
            ctx.actions.write(ctx.outputs.out, 'hi')
          return []

        r1 = rule(
          implementation = _rule_1_impl,
          attrs = {
            "out": attr.output(),
            "dep": attr.label(),
          },
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1', out = 'my_out.txt', dep = ':t2')
        r1(name = 't2')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain")

        val unused: @NotNull AnalysisResult = update(
            com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
            "//test:my_out.txt"
        )

        // {@link AspectKey} is created for toolchain_aspect on the output file //test:my_out.txt but
        // the aspect is not applied (no returned providers) because the aspect cannot be applied to
        // output files. The aspect does not propagate to any of the generating rule dependencies.
        val nodes: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
                .filter(
                    { entry ->
                        entry.getKey() is AspectKey
                                && (entry.getKey() as AspectKey)
                            .getAspectClass()
                            .getName()
                            .equals("//test:defs.bzl%toolchain_aspect")
                    })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        assertThat(nodes).hasSize(1)

        val aspectKey: AspectKey = com.google.common.collect.Iterables.getOnlyElement<Any?>(nodes).getKey() as AspectKey
        assertThat(aspectKey.getLabel().toString()).isEqualTo("//test:my_out.txt")

        val aspectValue: ConfiguredAspect =
            com.google.common.collect.Iterables.getOnlyElement<Any?>(nodes).getValue() as ConfiguredAspect
        assertThat(aspectValue.getProviders().getProviderCount()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainAspectApplyToGeneratingRule_propagateToDeps() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
            implementation = _impl,
            toolchains_aspects = ['//rule:toolchain_type_1'],
            attr_aspects = ['dep'],
            apply_to_generating_rules = True,
        )

        def _rule_1_impl(ctx):
          if ctx.outputs.out:
            ctx.actions.write(ctx.outputs.out, 'hi')
          return []

        r1 = rule(
          implementation = _rule_1_impl,
          attrs = {
            "out": attr.output(),
            "dep": attr.label(),
          },
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1', out = 'my_out.txt', dep = ':t2')
        r1(name = 't2')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain")

        val unused: @NotNull AnalysisResult = update(
            com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
            "//test:my_out.txt"
        )

        val visitedTargets: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
                .filter(
                    { entry ->
                        entry.getKey() is AspectKey
                                && (entry.getKey() as AspectKey)
                            .getAspectClass()
                            .getName()
                            .equals("//test:defs.bzl%toolchain_aspect")
                    })
                .map({ e -> (e.getKey() as AspectKey).getLabel().toString() })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

        // toolchain_aspect is applied to the generating rule of the output file and propagated to its
        // attribute dependency and toolchain dependency.
        assertThat(visitedTargets)
            .containsExactly("//test:my_out.txt", "//test:t1", "//test:t2", "//toolchain:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainAspectApplyToFiles_notPropagatedToDeps() {
        val aspect: DepsVisitingFileAspect = DepsVisitingFileAspect("dep", "//rule:toolchain_type_1")
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<DepsVisitingFileAspect?>(aspect),
            com.google.common.collect.ImmutableList.of<RuleDefinition>()
        )
        scratch.file(
            "test/defs.bzl",
            """
        def _rule_1_impl(ctx):
          if ctx.outputs.out:
            ctx.actions.write(ctx.outputs.out, 'hi')
          return []

        r1 = rule(
          implementation = _rule_1_impl,
          attrs = {
            "out": attr.output(),
            "dep": attr.label(),
          },
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1', out = 'my_out.txt', dep = ':t2')
        r1(name = 't2')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain")

        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>(aspect.name), "//test:my_out.txt")

        // {@link DepsVisitingFileAspect} is only applied to //test:my_out.txt file therefore it does
        // not propagate to the dependencies of its generating rule.
        val nodes: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
                .filter(
                    { entry ->
                        entry.getKey() is AspectKey
                                && (entry.getKey() as AspectKey)
                            .getAspectClass()
                            .getName()
                            .equals(aspect.name)
                    })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        assertThat(nodes).hasSize(1)

        val aspectKey: AspectKey = com.google.common.collect.Iterables.getOnlyElement<Any?>(nodes).getKey() as AspectKey
        assertThat(aspectKey.getLabel().toString()).isEqualTo("//test:my_out.txt")

        val aspectValue: ConfiguredAspect =
            com.google.common.collect.Iterables.getOnlyElement<Any?>(nodes).getValue() as ConfiguredAspect
        val provider: StarlarkInfo =
            aspectValue.get(DepsVisitingFileAspect.PROVIDER.getKey()) as StarlarkInfo
        assertThat(provider.getValue("val")).isEqualTo("//test:my_out.txt")
    }

    @org.junit.Test
    @TestParameters(
        "{autoExecGroups: True}", "{autoExecGroups: False}"
    )
    @Throws(java.lang.Exception::class)
    fun toolchainAspectOnTargetWithoutToolchain_success(autoExecGroups: String?) {
        val ruleWithoutToolchain: MockRule =
            MockRule {
                MockRule.define(
                    "rule_without_toolchain",
                    { builder, env -> builder.toolchainResolutionMode(ToolchainResolutionMode.DISABLED) })
            }
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<NativeAspectClass>(),
            com.google.common.collect.ImmutableList.of<MockRule?>(ruleWithoutToolchain)
        )
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()

        def _aspect_impl(target, ctx):
          return [AspectProvider(val = 'toolchain_aspect on %s' % str(target.label))]

        toolchain_aspect = aspect(
            implementation = _aspect_impl,
            toolchains_aspects = ['//rule:toolchain_type_1'],
        )

        def _rule_impl(ctx):
          pass

        rule_with_toolchain = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'rule_with_toolchain')
        rule_with_toolchain(name = 'target_with_toolchain')
        rule_without_toolchain(name = 'target_without_toolchain')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val unused: @NotNull AnalysisResult = update(
            com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
            "//test:all"
        )

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "AspectProvider"
            )

        val aspectOnVisitedTargets: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
                .filter(
                    { entry ->
                        entry.getKey() is AspectKey
                                && (entry.getKey() as AspectKey)
                            .getAspectClass()
                            .getName()
                            .equals("//test:defs.bzl%toolchain_aspect")
                    })
                .map({ e -> e.getValue() as ConfiguredAspect? })
                .map({ a -> (a.get(providerKey) as StarlarkInfo).getValue("val") })
                .map({ v -> v as String? })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

        // aspect successfully propagates to the 2 targets in //test package and to the toolchain of
        // //test:target_with_toolchain
        assertThat(aspectOnVisitedTargets)
            .containsExactly(
                "toolchain_aspect on @@//test:target_with_toolchain",
                "toolchain_aspect on @@//test:target_without_toolchain",
                "toolchain_aspect on @@//toolchain:foo"
            )
    }

    @org.junit.Test
    @TestParameters(
        "{autoExecGroups: True}", "{autoExecGroups: False}"
    )
    @Throws(java.lang.Exception::class)
    fun requiredToolchainAspectOnTargetWithoutToolchain_success(autoExecGroups: String?) {
        val ruleWithoutToolchain: MockRule =
            MockRule {
                MockRule.define(
                    "rule_without_toolchain",
                    { builder, env -> builder.toolchainResolutionMode(ToolchainResolutionMode.DISABLED) })
            }
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<NativeAspectClass>(),
            com.google.common.collect.ImmutableList.of<MockRule?>(ruleWithoutToolchain)
        )
        scratch.file(
            "test/defs.bzl",
            """
        MainAspectProvider = provider()
        RequiredAspectProvider = provider()

        def _required_aspect_impl(target, ctx):
          target_res = "required_aspect on " + str(target.label)
          if platform_common.ToolchainInfo in target:
            target_res += " with tool in ToolchainInfo = "
            target_res += str(target[platform_common.ToolchainInfo].tool)

          result = [target_res]
          if ctx.rule.toolchains and '//rule:toolchain_type_1' in ctx.rule.toolchains:
              result.extend(
                  ctx.rule.toolchains['//rule:toolchain_type_1'][RequiredAspectProvider].val)
          return [RequiredAspectProvider(val = result)]

        required_aspect = aspect(
          implementation = _required_aspect_impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
        )

        def _main_aspect_impl(target, ctx):
          res = 'main_aspect on %s' % str(target.label)
          return [MainAspectProvider(
              main_aspect_val = res,
              required_aspect_val = target[RequiredAspectProvider].val)]

        main_aspect = aspect(
            implementation = _main_aspect_impl,
            requires = [required_aspect]
        )

        def _rule_impl(ctx):
          pass

        rule_with_toolchain = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'rule_with_toolchain')
        rule_with_toolchain(name = 'target_with_toolchain')
        rule_without_toolchain(name = 'target_without_toolchain')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%main_aspect"), "//test:all")

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "MainAspectProvider"
            )

        // results on //test:target_with_toolchain
        val aspectOnWithToolchainTarget: ConfiguredAspect =
            getToplevelConfiguredAspect(
                analysisResult, "//test:defs.bzl%main_aspect", "//test:target_with_toolchain"
            )

        var mainAspectValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (aspectOnWithToolchainTarget.get(providerKey) as StarlarkInfo).getValue("main_aspect_val")
        Truth.assertThat(mainAspectValue).isEqualTo("main_aspect on @@//test:target_with_toolchain")

        var requiredAspectValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (aspectOnWithToolchainTarget.get(providerKey) as StarlarkInfo)
                .getValue("required_aspect_val")
        Truth.assertThat(requiredAspectValue)
            .containsExactly(
                "required_aspect on @@//test:target_with_toolchain",
                "required_aspect on @@//toolchain:foo with tool in ToolchainInfo ="
                        + " <generated file toolchain/atool>"
            )

        // test:target_without_toolchain
        val aspectOnWithoutToolchainTarget: ConfiguredAspect =
            getToplevelConfiguredAspect(
                analysisResult, "//test:defs.bzl%main_aspect", "//test:target_without_toolchain"
            )

        mainAspectValue =
            (aspectOnWithoutToolchainTarget.get(providerKey) as StarlarkInfo)
                .getValue("main_aspect_val")
        Truth.assertThat(mainAspectValue)
            .isEqualTo("main_aspect on @@//test:target_without_toolchain")

        requiredAspectValue =
            (aspectOnWithoutToolchainTarget.get(providerKey) as StarlarkInfo)
                .getValue("required_aspect_val")
        Truth.assertThat(requiredAspectValue)
            .containsExactly("required_aspect on @@//test:target_without_toolchain")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectUsesBaseTargetToolchainsToConfigureTargetDepsWithDefaultExecGp_autoExecGps() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        my_aspect = aspect(
          implementation = _impl,
          toolchains = ['//rule:toolchain_type_1'],
          attr_aspects = ['_tool'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_2'],
          attrs = {
            "_tool": attr.label(default='//test:tool', cfg='exec'),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        r1(name = 't1')
        foo_binary(name = 'tool', srcs = ['test.sh'])
        
        """.trimIndent()
        )
        scratch.file("test/test.sh", "")
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain_exec_1,//toolchain:foo_toolchain_exec_2",
            "--extra_execution_platforms=//platforms:platform_1,//platforms:platform_2",
            "--incompatible_auto_exec_groups=True",
            "--incompatible_enable_cc_toolchain_resolution"
        )

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%my_aspect"), "//test:t1")

        val topLevelTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val topLevelTargetDeps: Iterable<SkyKey?> =
            getDirectDeps(ConfiguredTargetKey.fromConfiguredTarget(topLevelTarget))

        val toolDependencyFromTarget: ConfiguredTargetKey? =
            com.google.common.collect.Streams.stream<SkyKey?>(topLevelTargetDeps)
                .filter { e: SkyKey? -> isConfiguredTarget(e, "//test:tool") }
                .collect(com.google.common.collect.MoreCollectors.onlyElement<SkyKey?>()) as ConfiguredTargetKey?

        val aspectOnToolDependnecyKey: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:tool",
                    "//test:defs.bzl%my_aspect"
                )
            )

        // The aspect used the base target's toolchain to request the target's dependency, so the
        // two keys are equal.
        assertThat(toolDependencyFromTarget)
            .isEqualTo(aspectOnToolDependnecyKey.getBaseConfiguredTargetKey())

        // The //test:tool target is requested only once and its key contains the execution platform of
        // its parent's (//test:t1) toolchain
        val toolDependencyKey: com.google.common.collect.ImmutableList<ConfiguredTargetKey> =
            getConfiguredTargetKey("//test:tool")
        Truth.assertThat(toolDependencyKey).hasSize(1)

        // //test:tool gets the execution platform of the default exec gp, when automatic execution
        // groups are enabled, the default exec gp will have the basic execution platform.
        assertThat(
            toolDependencyKey
                .get(0)
                .getConfigurationKey()
                .getOptions()
                .get(PlatformOptions::class.java)
                .getPlatforms()
        )
            .containsExactly(Label.parseCanonicalUnchecked("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectUsesBaseTargetToolchainsToConfigureTargetDepsWithDefaultExecGp_noAutoExecGps() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        my_aspect = aspect(
          implementation = _impl,
          toolchains = ['//rule:toolchain_type_1'],
          attr_aspects = ['_tool'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_2'],
          attrs = {
            "_tool": attr.label(default='//test:tool', cfg='exec'),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        r1(name = 't1')
        foo_binary(name = 'tool', srcs = ['test.sh'])
        
        """.trimIndent()
        )
        scratch.file("test/test.sh", "")
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain_exec_1,//toolchain:foo_toolchain_exec_2",
            "--extra_execution_platforms=//platforms:platform_1,//platforms:platform_2",
            "--incompatible_auto_exec_groups=False",
            "--incompatible_enable_cc_toolchain_resolution"
        )

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%my_aspect"), "//test:t1")

        val topLevelTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val topLevelTargetDeps: Iterable<SkyKey?> =
            getDirectDeps(ConfiguredTargetKey.fromConfiguredTarget(topLevelTarget))

        val toolDependencyFromTarget: ConfiguredTargetKey? =
            com.google.common.collect.Streams.stream<SkyKey?>(topLevelTargetDeps)
                .filter { e: SkyKey? -> isConfiguredTarget(e, "//test:tool") }
                .collect(com.google.common.collect.MoreCollectors.onlyElement<SkyKey?>()) as ConfiguredTargetKey?

        val aspectOnToolDependnecyKey: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:tool",
                    "//test:defs.bzl%my_aspect"
                )
            )

        // The aspect used the base target's toolchain to request the target's dependency, so the
        // two keys are equal.
        assertThat(toolDependencyFromTarget)
            .isEqualTo(aspectOnToolDependnecyKey.getBaseConfiguredTargetKey())

        // The //test:tool target is requested only once and its key contains the execution platform of
        // its parent's (//test:t1) toolchain
        val toolDependencyKey: com.google.common.collect.ImmutableList<ConfiguredTargetKey> =
            getConfiguredTargetKey("//test:tool")
        Truth.assertThat(toolDependencyKey).hasSize(1)

        // //test:tool gets the execution platform of the default exec gp, when automatic execution
        // groups are disabled, the default exec gp will have the execution platform of the only
        // toolchain type it has.
        assertThat(
            toolDependencyKey
                .get(0)
                .getConfigurationKey()
                .getOptions()
                .get(PlatformOptions::class.java)
                .getPlatforms()
        )
            .containsExactly(Label.parseCanonicalUnchecked("//platforms:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectUsesBaseTargetToolchainsToConfigureTargetDepsWithCustomExecGp(
        @TestParameter autoExecGroups: Boolean
    ) {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        my_aspect = aspect(
          implementation = _impl,
          exec_groups = {"gp": exec_group(toolchains = ['//rule:toolchain_type_1'])},
          attr_aspects = ['_tool'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          exec_groups = {"gp": exec_group(toolchains = ['//rule:toolchain_type_2'])},
          attrs = {
            "_tool": attr.label(default='//test:tool', cfg = config.exec(exec_group = 'gp')),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        r1(name = 't1')
        foo_binary(name = 'tool', srcs = ['test.sh'])
        
        """.trimIndent()
        )
        scratch.file("test/test.sh", "")
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain_exec_1,//toolchain:foo_toolchain_exec_2",
            "--extra_execution_platforms=//platforms:platform_1,//platforms:platform_2",
            "--incompatible_auto_exec_groups=" + autoExecGroups,
            "--incompatible_enable_cc_toolchain_resolution"
        )

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%my_aspect"), "//test:t1")

        val topLevelTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val topLevelTargetDeps: Iterable<SkyKey?> =
            getDirectDeps(ConfiguredTargetKey.fromConfiguredTarget(topLevelTarget))

        val toolDependencyFromTarget: ConfiguredTargetKey? =
            com.google.common.collect.Streams.stream<SkyKey?>(topLevelTargetDeps)
                .filter { e: SkyKey? -> isConfiguredTarget(e, "//test:tool") }
                .collect(com.google.common.collect.MoreCollectors.onlyElement<SkyKey?>()) as ConfiguredTargetKey?

        val aspectOnToolDependnecyKey: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:tool",
                    "//test:defs.bzl%my_aspect"
                )
            )

        // The aspect used the base target's toolchain to request the target's dependency, so the
        // two keys are equal.
        assertThat(toolDependencyFromTarget)
            .isEqualTo(aspectOnToolDependnecyKey.getBaseConfiguredTargetKey())

        // The //test:tool target is requested only once and its key contains the execution platform of
        // the exec group 'gp' from its parent (//test:t1).
        val toolDependencyKey: com.google.common.collect.ImmutableList<ConfiguredTargetKey> =
            getConfiguredTargetKey("//test:tool")
        Truth.assertThat(toolDependencyKey).hasSize(1)
        assertThat(
            toolDependencyKey
                .get(0)
                .getConfigurationKey()
                .getOptions()
                .get(PlatformOptions::class.java)
                .getPlatforms()
        )
            .containsExactly(Label.parseCanonicalUnchecked("//platforms:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectAndRuleHaveDifferentExecutionPlatforms_buildSucceeds(
        @TestParameter autoExecGroups: Boolean
    ) {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          exec_groups = {"gp": exec_group(toolchains = ['//rule:toolchain_type_1'])},
          attrs = {
            "_tool": attr.label(default='//test:aspect_tool', cfg=config.exec(exec_group = 'gp')),
          },
          toolchains_aspects = ['//rule:toolchain_type_1'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          exec_groups = {
            "gp": exec_group(
              toolchains = ['//rule:toolchain_type_1'],
              exec_compatible_with = ['//platforms:constraint_2']
            )
          },
          attrs = {
            "_tool": attr.label(default='//test:rule_tool', cfg = config.exec(exec_group = 'gp')),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        r1(name = 't1')
        foo_binary(name = 'rule_tool', srcs = ['test.sh'])
        foo_binary(name = 'aspect_tool', srcs = ['test.sh'])
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--extra_execution_platforms=//platforms:platform_1,//platforms:platform_2",
            "--incompatible_auto_exec_groups=" + autoExecGroups,
            "--incompatible_enable_cc_toolchain_resolution"
        )

        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        // //test:rule_tool uses //platforms:platform_2
        val ruleTool: ConfiguredTargetKey? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTargetKey?>(getConfiguredTargetKey("//test:rule_tool"))
        assertThat(
            ruleTool.getConfigurationKey().getOptions().get(PlatformOptions::class.java).getPlatforms()
        )
            .containsExactly(Label.parseCanonicalUnchecked("//platforms:platform_2"))

        // //test:aspect_tool uses //platforms:platform_1
        val aspectTool: ConfiguredTargetKey? =
            com.google.common.collect.Iterables.getOnlyElement<ConfiguredTargetKey?>(getConfiguredTargetKey("//test:aspect_tool"))
        assertThat(
            aspectTool.getConfigurationKey().getOptions().get(PlatformOptions::class.java).getPlatforms()
        )
            .containsExactly(Label.parseCanonicalUnchecked("//platforms:platform_1"))

        // aspect propagates to the rule's toolchain (with //platforms:platform_2 execution platform)
        // not to its own toolchain
        val aspectOnTarget: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<AspectKey?>(
                getAspectKeys(
                    "//test:t1",
                    "//test:defs.bzl%toolchain_aspect"
                )
            )
        val aspectOnTargetDeps: Iterable<SkyKey?> = getDirectDeps(aspectOnTarget)

        val aspectsOnToolchain: Iterable<String> =
            com.google.common.collect.Iterables.transform<AspectKey?, String?>(
                com.google.common.collect.Iterables.filter<AspectKey?>(aspectOnTargetDeps, AspectKey::class.java),
                com.google.common.base.Function { k: AspectKey? ->
                    (k.getAspectName()
                            + " on "
                            + k.getLabel()
                            + ", exec_platform: "
                            + k.getBaseConfiguredTargetKey().getExecutionPlatformLabel())
                })
        Truth.assertThat(aspectsOnToolchain)
            .containsExactly(
                "//test:defs.bzl%toolchain_aspect on //toolchain:foo,"
                        + " exec_platform: //platforms:platform_2"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesToToolchain_seesToolchainLabel(@TestParameter autoExecGroups: Boolean) {
        scratch.file(
            "test/defs.bzl",
            """
        AspectProvider = provider()
        def _impl(target, ctx):
          if ctx.rule.toolchains and '//rule:toolchain_type_1' in ctx.rule.toolchains:
              return [
                AspectProvider(value = str(target.label) + ' has toolchain ' +
                  str(ctx.rule.toolchains['//rule:toolchain_type_1'].label))]
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "AspectProvider"
            )

        val value: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (configuredAspect.get(providerKey) as StarlarkInfo).getValue("value")
        Truth.assertThat(value).isEqualTo("@@//test:t1 has toolchain @@//toolchain:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectCollectsAttributeVars() {
        scratch.file(
            "test/defs.bzl",
            """
        VarProvider = provider(fields = ["aspect_vars", "rule_vars"])

        def _impl(target, ctx):
          aspect_vars = ctx.var
          rule_vars = ctx.rule.var
          return [VarProvider(aspect_vars = aspect_vars, rule_vars = rule_vars)]

        var_aspect = aspect(
          implementation = _impl,
          attrs = {
              "_toolchains": attr.label(default = '//test:aspect_vars'),
          },
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
        )

        def _var_supplier_impl(ctx):
          return [platform_common.TemplateVariableInfo(ctx.attr.vars)]

        var_supplier = rule(
          implementation = _var_supplier_impl,
          attrs = {
              "vars": attr.string_dict(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
load('//test:defs.bzl', 'r1', 'var_supplier')
r1(
    name = 't1',
    # The toolchains attribute also supplies make variables, separate from toolchain resolution.
    toolchains = [":rule_vars"],
)

var_supplier(
    name = "rule_vars",
    vars = {
        "rule_var_key": "rule_var_value",
    },
)

var_supplier(
    name = "aspect_vars",
    vars = {
        "aspect_var_key": "aspect_var_value",
    },
)

""".trimIndent()
        )
        useConfiguration()

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%var_aspect"), "//test:t1")

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "VarProvider"
            )

        val provider: StarlarkInfo = configuredAspect.get(providerKey) as StarlarkInfo

        // Check vars from the aspect itself.
        val rawAspectVars: Any? = provider.getValue("aspect_vars")
        Truth.assertThat(rawAspectVars).isInstanceOf(Dict::class.java)

        // This can't fail due to the above assertion, but makes the compiler happy.
        if (rawAspectVars is Dict<*, *>) {
            // This will have several standard keys as well, so don't check exact keys.
            // This will have several standard keys as well, so don't check exact keys.

            Truth.assertThat(rawAspectVars.keys).contains("aspect_var_key")
            Truth.assertThat(rawAspectVars.get("aspect_var_key")).isEqualTo("aspect_var_value")
            // Should not contain keys from the rule.
            Truth.assertThat(rawAspectVars.keys).doesNotContain("rule_var_key")
        }

        // Check vars from the underlying rule.
        val rawRuleVars: Any? = provider.getValue("rule_vars")
        Truth.assertThat(rawRuleVars).isInstanceOf(Dict::class.java)

        // This can't fail due to the above assertion, but makes the compiler happy.
        if (rawRuleVars is Dict<*, *>) {
            // This will have several standard keys as well, so don't check exact keys.

            Truth.assertThat(rawRuleVars.keys).contains("rule_var_key")
            Truth.assertThat(rawRuleVars.get("rule_var_key")).isEqualTo("rule_var_value")
            // Should not contain keys from the rule.
            Truth.assertThat(rawRuleVars.keys).doesNotContain("aspect_var_key")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectCollectsToolchainVars() {
        scratch.file(
            "test/defs.bzl",
            """
        VarProvider = provider(fields = ["aspect_vars", "rule_vars"])

        def _impl(target, ctx):
          aspect_vars = ctx.var
          rule_vars = ctx.rule.var
          return [VarProvider(aspect_vars = aspect_vars, rule_vars = rule_vars)]

        var_aspect = aspect(
          implementation = _impl,
          toolchains = ['//rule:toolchain_type_1'],
          toolchains_aspects = ['//rule:toolchain_type_2'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_2'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:foo_toolchain_with_provider"
        )

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%var_aspect"), "//test:t1")

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val providerKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "VarProvider"
            )

        val provider: StarlarkInfo = configuredAspect.get(providerKey) as StarlarkInfo

        // Check vars from the aspect itself.
        val rawAspectVars: Any? = provider.getValue("aspect_vars")
        Truth.assertThat(rawAspectVars).isInstanceOf(Dict::class.java)

        // This can't fail due to the above assertion, but makes the compiler happy.
        if (rawAspectVars is Dict<*, *>) {
            // This will have several standard keys as well, so don't check exact keys.

            Truth.assertThat(rawAspectVars.keys).contains("type_1_key")
            Truth.assertThat(rawAspectVars.get("type_1_key")).isEqualTo("type_1_value")
            // Should not contain keys from the rule.
            Truth.assertThat(rawAspectVars.keys).doesNotContain("type_2_key")
        }

        // Check vars from the underlying rule.
        val rawRuleVars: Any? = provider.getValue("rule_vars")
        Truth.assertThat(rawRuleVars).isInstanceOf(Dict::class.java)

        // This can't fail due to the above assertion, but makes the compiler happy.
        if (rawRuleVars is Dict<*, *>) {
            // This will have several standard keys as well, so don't check exact keys.

            Truth.assertThat(rawRuleVars.keys).contains("type_2_key")
            Truth.assertThat(rawRuleVars.get("type_2_key")).isEqualTo("type_2_value")
            // Should not contain keys from the aspect.
            Truth.assertThat(rawRuleVars.keys).doesNotContain("type_1_key")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun propagationPredicateAppliedOnToolchain_aspectPropagatedToSatisfyingToolchain(
        @TestParameter autoExecGroups: Boolean
    ) {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()

        def _propagation_predicate(ctx):
          if ctx.rule.label == Label('//toolchain:foo'):
            return False
          return True

        def _impl(target, ctx):
          res = ['my_aspect on ' + str(target.label)]
          toolchains_types = ['//rule:toolchain_type_1', '//rule:toolchain_type_2']

          for toolchain_type in toolchains_types:
            if toolchain_type in ctx.rule.toolchains:
              if AspectInfo in ctx.rule.toolchains[toolchain_type]:
                res.extend(ctx.rule.toolchains[toolchain_type][AspectInfo].res)

          return [AspectInfo(res = res)]

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['//rule:toolchain_type_1', '//rule:toolchain_type_2'],
          propagation_predicate = _propagation_predicate,
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1', '//rule:toolchain_type_2'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:foo_toolchain_with_provider",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        val aspectKeys: com.google.common.collect.ImmutableList<String> =
            getAspectKeys("//test:defs.bzl%toolchain_aspect")
        // Only the keys to the targets that satisfy the aspect's propagation predicate are present.
        Truth.assertThat(aspectKeys)
            .containsExactly(
                "//test:defs.bzl%toolchain_aspect on //test:t1",
                "//test:defs.bzl%toolchain_aspect on //toolchain:foo_with_provider"
            )

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        assertThat(
            getStarlarkProvider(configuredAspect, "//test:defs.bzl", "AspectInfo")
                .getValue("res", net.starlark.java.eval.Sequence::class.java)
        )
            .containsExactly(
                "my_aspect on @@//test:t1", "my_aspect on @@//toolchain:foo_with_provider"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainTypesFunc_invalidToolchainType_fails(@TestParameter autoExecGroups: Boolean) {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()

        def _toolchain_aspects(ctx):
          return [Label('@:')]

        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = _toolchain_aspects,
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        reporter.removeHandler(failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable {
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
                    "//test:t1"
                )
            })
        assertContainsEvent("invalid label in Label(): invalid repository name ':'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainTypesFunc_invalidReturnValue_fails() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()

        def _toolchains_aspects(ctx):
          return ['//rule:toolchain_type_1']

        def _impl(target, ctx):
          return []

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = _toolchains_aspects,
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain:foo_toolchain")

        reporter.removeHandler(failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable {
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
                    "//test:t1"
                )
            })
        assertContainsEvent("at index 0 of toolchains_aspects, got element of type string, want Label")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainTypesFunc_wildcardEnumeratesResolvedToolchains(
        @TestParameter autoExecGroups: Boolean
    ) {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()

        def _impl(target, ctx):
          res = ['my_aspect on ' + str(target.label)]
          for toolchain_type in ctx.rule.toolchains.toolchain_types():
            if AspectInfo in ctx.rule.toolchains[toolchain_type]:
              res.extend(ctx.rule.toolchains[toolchain_type][AspectInfo].res)
          return [AspectInfo(res = res)]

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = ['*'],
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1', '//rule:toolchain_type_2'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:foo_toolchain_with_provider",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"), "//test:t1")

        val aspectKeys: com.google.common.collect.ImmutableList<String> =
            getAspectKeys("//test:defs.bzl%toolchain_aspect")
        Truth.assertThat(aspectKeys)
            .containsExactly(
                "//test:defs.bzl%toolchain_aspect on //test:t1",
                "//test:defs.bzl%toolchain_aspect on //toolchain:foo",
                "//test:defs.bzl%toolchain_aspect on //toolchain:foo_with_provider"
            )

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        assertThat(
            getStarlarkProvider(configuredAspect, "//test:defs.bzl", "AspectInfo")
                .getValue("res", net.starlark.java.eval.Sequence::class.java)
        )
            .containsExactly(
                "my_aspect on @@//test:t1",
                "my_aspect on @@//toolchain:foo",
                "my_aspect on @@//toolchain:foo_with_provider"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolchainTypesFunc_propagateToSelectedTypes(@TestParameter autoExecGroups: Boolean) {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()

        def _toolchains_aspects(ctx):
          if ctx.rule.label == Label('//test:t1'):
            return [Label('//rule:toolchain_type_1')]
          elif ctx.rule.label == Label('//test:t2'):
            return [Label('//rule:toolchain_type_2')]
          return []

        def _impl(target, ctx):
          res = ['my_aspect on ' + str(target.label)]
          toolchains_types = ['//rule:toolchain_type_1', '//rule:toolchain_type_2']

          for toolchain_type in toolchains_types:
            if toolchain_type in ctx.rule.toolchains:
              if AspectInfo in ctx.rule.toolchains[toolchain_type]:
                res.extend(ctx.rule.toolchains[toolchain_type][AspectInfo].res)

          return [AspectInfo(res = res)]

        toolchain_aspect = aspect(
          implementation = _impl,
          toolchains_aspects = _toolchains_aspects,
        )

        def _rule_impl(ctx):
          pass

        r1 = rule(
          implementation = _rule_impl,
          toolchains = ['//rule:toolchain_type_1', '//rule:toolchain_type_2'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        r1(name = 't2')
        r1(name = 't3')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:foo_toolchain_with_provider",
            "--incompatible_auto_exec_groups=" + autoExecGroups
        )

        val analysisResult: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%toolchain_aspect"),
                "//test:t1",
                "//test:t2",
                "//test:t3"
            )

        val aspectKeys: com.google.common.collect.ImmutableList<String> =
            getAspectKeys("//test:defs.bzl%toolchain_aspect")
        Truth.assertThat(aspectKeys)
            .containsExactly(
                "//test:defs.bzl%toolchain_aspect on //test:t1",
                "//test:defs.bzl%toolchain_aspect on //test:t2",
                "//test:defs.bzl%toolchain_aspect on //test:t3",
                "//test:defs.bzl%toolchain_aspect on //toolchain:foo",
                "//test:defs.bzl%toolchain_aspect on //toolchain:foo_with_provider"
            )

        val t1AspectResult: net.starlark.java.eval.Sequence<*> =
            getAspectResult(
                analysisResult.getAspectsMap(), "toolchain_aspect", "//test:t1", "AspectInfo"
            )
        Truth.assertThat(t1AspectResult)
            .containsExactly("my_aspect on @@//test:t1", "my_aspect on @@//toolchain:foo")

        val t2AspectResult: net.starlark.java.eval.Sequence<*> =
            getAspectResult(
                analysisResult.getAspectsMap(), "toolchain_aspect", "//test:t2", "AspectInfo"
            )
        Truth.assertThat(t2AspectResult)
            .containsExactly(
                "my_aspect on @@//test:t2", "my_aspect on @@//toolchain:foo_with_provider"
            )

        val t3AspectResult: net.starlark.java.eval.Sequence<*> =
            getAspectResult(
                analysisResult.getAspectsMap(), "toolchain_aspect", "//test:t3", "AspectInfo"
            )
        Truth.assertThat(t3AspectResult).containsExactly("my_aspect on @@//test:t3")
    }

    @Throws(java.lang.Exception::class)
    private fun getAspectResult(
        aspectsMap: MutableMap<AspectKey?, ConfiguredAspect?>,
        aspectName: String?,
        targetLabel: String?,
        providerName: String?
    ): net.starlark.java.eval.Sequence<*> {
        for (entry in aspectsMap.entries) {
            val aspectClass: AspectClass? = entry.key.getAspectClass()
            if (aspectClass is StarlarkAspectClass) {
                val aspectExportedName: String = aspectClass.exportedName
                if (aspectExportedName == aspectName
                    && (targetLabel == null || entry.key.getLabel().toString().equals(targetLabel))
                ) {
                    return getStarlarkProvider(entry.value, "//test:defs.bzl", providerName)
                        .getValue("res", net.starlark.java.eval.Sequence::class.java)
                }
            }
        }
        throw java.lang.AssertionError("Aspect result not found for aspect: " + aspectName)
    }

    private fun getConfiguredTargetKey(targetLabel: String?): com.google.common.collect.ImmutableList<ConfiguredTargetKey> {
        return skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
            .filter({ n -> isConfiguredTarget(n.getKey(), targetLabel) })
            .map({ n -> n.getKey() as ConfiguredTargetKey? })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    @Throws(java.lang.Exception::class)
    private fun getDirectDeps(key: SkyKey?): Iterable<SkyKey?> {
        return skyframeExecutor
            .getEvaluator()
            .getExistingEntryAtCurrentlyEvaluatingVersion(key)
            .getDirectDeps()
    }

    private fun getAspectKeys(aspectLabel: String?): com.google.common.collect.ImmutableList<String> {
        return skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
            .filter(
                { entry ->
                    entry.getKey() is AspectKey
                            && (entry.getKey() as AspectKey).getAspectClass().getName().equals(aspectLabel)
                })
            .map({ e -> e.getKey() as AspectKey? })
            .map({ k -> k.getAspectClass().getName() + " on " + k.getLabel() })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    private fun getAspectKeys(
        targetLabel: String?,
        aspectLabel: String?
    ): com.google.common.collect.ImmutableList<AspectKey?> {
        return skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
            .filter(
                { entry ->
                    entry.getKey() is AspectKey
                            && (entry.getKey() as AspectKey).getAspectClass().getName().equals(aspectLabel)
                            && (entry.getKey() as AspectKey).getLabel().toString().equals(targetLabel)
                })
            .map({ e -> e.getKey() as AspectKey? })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    companion object {
        private fun isConfiguredTarget(key: SkyKey?, label: String?): Boolean {
            return key is ConfiguredTargetKey && key.getLabel().toString().equals(label)
        }

        private fun getToplevelConfiguredAspect(
            analysisResult: AnalysisResult, aspectName: String?, targetLabel: String?
        ): ConfiguredAspect {
            return com.google.common.collect.Iterables.getOnlyElement<T>(
                analysisResult.getAspectsMap().entrySet().stream()
                    .filter(
                        { e ->
                            e.getKey().getAspectName().equals(aspectName)
                                    && e.getKey().getLabel().toString().equals(targetLabel)
                        })
                    .map({ e -> e.getValue() })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
        }
    }
}
