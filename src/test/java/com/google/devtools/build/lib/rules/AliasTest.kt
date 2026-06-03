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
package com.google.devtools.build.lib.rules

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Unit tests for the `alias` rule.  */
@RunWith(JUnit4::class)
class AliasTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "a",
            srcs = ["a.cc"],
        )

        alias(
            name = "b",
            actual = "a",
        )
        
        """.trimIndent()
        )

        val b: ConfiguredTarget = getConfiguredTarget("//a:b")
        assertThat(CcInfo.get(b).getCcCompilationContext()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasToInputFile() {
        scratch.file(
            "a/BUILD",
            """
        exports_files(["a"])

        alias(
            name = "b",
            actual = "a",
        )
        
        """.trimIndent()
        )

        val b: ConfiguredTarget = getConfiguredTarget("//a:b")
        assertThat(ActionsTestUtil.baseArtifactNames(getFilesToBuild(b))).containsExactly("a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visibilityIsOverriddenAndIsOkay() {
        scratch.file(
            "a/BUILD",
            "filegroup(name='a', visibility=['//b:__pkg__'])"
        )
        scratch.file(
            "b/BUILD",
            "alias(name='b', actual='//a:a', visibility=['//visibility:public'])"
        )
        scratch.file(
            "c/BUILD",
            "filegroup(name='c', srcs=['//b:b'])"
        )

        getConfiguredTarget("//c:c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visibilityIsOverriddenAndIsError() {
        scratch.file(
            "a/BUILD",
            "filegroup(name='a', visibility=['//visibility:public'])"
        )
        scratch.file(
            "b/BUILD",
            "alias(name='b', actual='//a:a', visibility=['//visibility:private'])"
        )
        scratch.file(
            "c/BUILD",
            "filegroup(name='c', srcs=['//b:b'])"
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//c:c")
        assertContainsEvent(
            "alias '//b:b' referring to target '//a:a' is not visible from\ntarget '//c:c'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun visibilityIsOverriddenAndIsErrorAfterMultipleAliases() {
        scratch.file(
            "a/BUILD",
            "filegroup(name='a', visibility=['//visibility:public'])"
        )
        scratch.file(
            "b/BUILD",
            "alias(name='b', actual='//a:a', visibility=['//visibility:public'])"
        )
        scratch.file(
            "c/BUILD",
            "alias(name='c', actual='//b:b', visibility=['//visibility:private'])"
        )
        scratch.file(
            "d/BUILD",
            "filegroup(name='d', srcs=['//c:c'])"
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//d:d")
        assertContainsEvent(
            "alias '//c:c' referring to target '//a:a' through '//b:b' "
                    + "is not visible from\ntarget '//d:d'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAliasWithPrivateVisibilityAccessibleFromSamePackage() {
        scratch.file("a/BUILD", "exports_files(['af'])")
        scratch.file(
            "b/BUILD",
            """
        package(default_visibility = ["//visibility:private"])

        alias(
            name = "al",
            actual = "//a:af",
        )

        filegroup(
            name = "ta",
            srcs = [":al"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//b:ta")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAliasCycle() {
        scratch.file(
            "a/BUILD",
            """
        alias(
            name = "a",
            actual = ":b",
        )

        alias(
            name = "b",
            actual = ":c",
        )

        alias(
            name = "c",
            actual = ":a",
        )

        filegroup(
            name = "d",
            srcs = [":c"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//a:d")
        assertContainsEvent("cycle in dependency graph")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAliasedInvalidDependency() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "a",
            deps = [":b"],
        )

        alias(
            name = "b",
            actual = ":c",
        )

        filegroup(name = "c")
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//a:a")
        assertContainsEvent("alias '//a:b' referring to filegroup rule '//a:c' is misplaced here")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectPropagation() {
        writeConfigTransitionTestFiles()
        scratch.file(
            "test/aspect.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(target, ctx):
            if not target[MyInfo]:
                fail("missing MyInfo")
            if target[MyInfo].config != ctx.configuration:
                fail("mismatched configs")
            return MyInfo(
                origin = "aspect",
                config = target[MyInfo].config,
            )

        MyAspect = aspect(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            String.format(
                """
            alias(
                name = "simple_alias",
                actual = "//test/starlark:test",
            )

            alias(
                name = "selecting_alias",
                actual = select(
                  {"%s": ":simple_alias"}
                ),
            )
            
            """.trimIndent(),
                TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:x86_64"
            )
        )

        // Set --platforms so we can test alias :selecting_alias that selects on the CPU.
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)

        // 1. Query "actual" target to establish reference values to compare to below. Make some basic
        // assertions that tie aspect's config to underlying target.
        var analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test/starlark:test"),
                com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%MyAspect"),
                true,
                1,
                true,
                eventBus
            )
        assertThat(analysisResult.getTargetsToBuild()).hasSize(1)
        assertThat(analysisResult.getAspectsMap()).hasSize(1)

        val actualTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        var aspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val actualKey: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().keySet())
        assertThat(actualKey.getBaseConfiguredTargetKey().getConfigurationKey())
            .isEqualTo(actualTarget.getConfigurationKey())
        assertThat(getMyInfoFromTarget(aspect).getValue("origin")).isEqualTo("aspect")
        val actualConfig: BuildConfigurationValue =
            getMyInfoFromTarget(aspect).getValue("config") as BuildConfigurationValue
        assertThat(actualKey.getBaseConfiguredTargetKey().getConfigurationKey().getOptions().checksum())
            .isEqualTo(actualConfig.checksum())

        // 2. Query :simple_alias and assert that its aspect value is the same as above.
        analysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:simple_alias"),
                com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%MyAspect"),
                true,
                1,
                true,
                eventBus
            )
        assertThat(analysisResult.getTargetsToBuild()).hasSize(1)
        assertThat(analysisResult.getAspectsMap()).hasSize(1)

        val alias: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        assertThat(alias.getActual()).isEqualTo(actualTarget)
        // Alias and actual must have different configs for this test to be meaningful
        assertThat(alias.getConfigurationKey()).isNotEqualTo(alias.getActual().getConfigurationKey())
        val aspectKey: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().keySet())
        assertThat(aspectKey.getBaseConfiguredTargetKey().getConfigurationKey())
            .isEqualTo(alias.getConfigurationKey())

        aspect = com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        assertThat(getMyInfoFromTarget(aspect).getValue("origin")).isEqualTo("aspect")
        // We should be seeing actual's config here
        assertThat(getMyInfoFromTarget(aspect).getValue("config")).isEqualTo(actualConfig)

        // 3. Do the same with :selecting_alias, which is an indirect alias through :simple_alias.
        // This alias also uses a (non-trivial) select to resolve its actual.
        analysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:selecting_alias"),
                com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%MyAspect"),
                true,
                1,
                true,
                eventBus
            )
        assertThat(analysisResult.getTargetsToBuild()).hasSize(1)
        assertThat(analysisResult.getAspectsMap()).hasSize(1)

        val indirectAlias: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        assertThat(indirectAlias.getActual()).isEqualTo(actualTarget)
        assertThat(indirectAlias.getConfigurationKey()).isEqualTo(alias.getConfigurationKey())

        aspect = com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        assertThat(getMyInfoFromTarget(aspect).getValue("origin")).isEqualTo("aspect")
        assertThat(getMyInfoFromTarget(aspect).getValue("config")).isEqualTo(actualConfig)
    }

    @Throws(java.lang.Exception::class)
    private fun writeAllowlistFile() {
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = "function_transition_allowlist",
            packages = [
                "//test/...",
            ],
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    fun setupMyInfo() {
        scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()")
        scratch.file("myinfo/BUILD")
    }

    @Throws(java.lang.Exception::class)
    private fun writeConfigTransitionTestFiles() {
        writeAllowlistFile()
        setupMyInfo()
        getAnalysisMock().ccSupport().setupCcToolchainConfigForCpu(mockToolsConfig, "armeabi-v7a")
        scratch.file(
            "test/starlark/my_rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")

        def transition_func(settings, attr):
            return [
                {"//command_line_option:cpu": "k8"},
                {"//command_line_option:cpu": "armeabi-v7a"},
            ]

        my_transition = transition(
            implementation = transition_func,
            inputs = [],
            outputs = ["//command_line_option:cpu"],
        )

        def impl(ctx):
            print(ctx.label, ctx.configuration)
            return MyInfo(
                config = ctx.configuration,
                attr_deps = ctx.split_attr.deps,
                attr_dep = ctx.split_attr.dep,
            )

        my_rule = rule(
            implementation = impl,
            attrs = {
                "deps": attr.label_list(cfg = my_transition),
                "dep": attr.label(cfg = my_transition),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//test/starlark:my_rule.bzl", "my_rule")

        my_rule(
            name = "test",
            dep = ":main1",
            deps = [
                ":main1",
                ":main2",
            ],
        )

        cc_binary(
            name = "main1",
            srcs = ["main1.c"],
        )

        cc_binary(
            name = "main2",
            srcs = ["main2.c"],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assertNoLicensesAttribute() {
        scratch.file(
            "a/BUILD",
            """
        filegroup(name = "a")

        alias(
            name = "b",
            actual = ":a",
            licenses = ["unencumbered"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//a:b")
        assertContainsEvent("no such attribute 'licenses' in 'alias' rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun passesTargetTypeCheck() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "a",
            srcs = ["a.cc"],
            deps = [":b"],
        )

        alias(
            name = "b",
            actual = ":c",
        )

        cc_library(
            name = "c",
            srcs = ["c.cc"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packageGroupInAlias() {
        scratch.file(
            "a/BUILD",
            """
        package_group(
            name = "a",
            packages = ["//a"],
        )

        alias(
            name = "b",
            actual = ":a",
        )

        filegroup(
            name = "c",
            srcs = [":b"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//a:c")
        assertContainsEvent(
            "in actual attribute of alias rule //a:b: package group '//a:a' is misplaced here"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasedFile() {
        scratch.file(
            "a/BUILD",
            """
        exports_files(["a"])

        alias(
            name = "b",
            actual = "a",
        )

        filegroup(
            name = "c",
            srcs = [":b"],
        )
        
        """.trimIndent()
        )

        val c: ConfiguredTarget = getConfiguredTarget("//a:c")
        assertThat(
            ActionsTestUtil.baseArtifactNames(
                c.getProvider(FileProvider::class.java).getFilesToBuild()
            )
        )
            .containsExactly("a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasedConfigSetting() {
        scratch.file(
            "a/BUILD",
            """
        filegroup(
            name = "a",
            srcs = select({
                ":b": ["f1"],
                "//conditions:default": ["f2"],
            }),
        )

        alias(
            name = "b",
            actual = ":c",
        )

        config_setting(
            name = "c",
            values = {"define": "foo=bar"},
        )
        
        """.trimIndent()
        )

        useConfiguration("--define=foo=bar")
        getConfiguredTarget("//a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasedTestSuiteDep() {
        scratch.file(
            "a/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='a', srcs=['a.sh'])"
        )
        scratch.file(
            "b/BUILD",
            """
        alias(
            name = "b",
            testonly = 1,
            actual = "//a:a",
        )

        test_suite(
            name = "c",
            tests = [":b"],
        )
        
        """.trimIndent()
        )

        val c: ConfiguredTarget = getConfiguredTarget("//b:c")
        val runfiles: NestedSet<Artifact?>? =
            c.getProvider(RunfilesProvider::class.java).getDataRunfiles().getAllArtifacts()
        com.google.common.truth.Subject.contains("a.sh")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRedirectChasing() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "alias(name='cc', actual='" + TestConstants.PLATFORM_LABEL + "')",
            "cc_library(name='a', srcs=['a.cc'])"
        )

        useConfiguration("--platforms=" + "//a:cc")
        getConfiguredTarget("//a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoActual() {
        checkError("a", "a", "missing value for mandatory attribute 'actual'", "alias(name='a')")
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun getMyInfoFromTarget(configuredAspect: ConfiguredAspect): StructImpl? {
            val key: Provider.Key =
                Key(
                    keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo"
                )
            return configuredAspect.get(key) as StructImpl?
        }
    }
}
