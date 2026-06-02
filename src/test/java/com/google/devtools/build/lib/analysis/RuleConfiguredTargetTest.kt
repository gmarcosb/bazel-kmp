// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.skyframe.serialization.SerializationRegistrySetupHelpers.initializeAnalysisCodecRegistryBuilder

/**
 * A test for rule ConfiguredTargets.
 */
@RunWith(JUnit4::class)
class RuleConfiguredTargetTest : BuildViewTestCase() {
    @Throws(java.lang.Exception::class)
    private fun configure(ruleLabel: String?): ConfiguredTarget? {
        return getConfiguredTarget(ruleLabel)
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(DummyTestFragment::class.java)
        return builder
            .addRuleDefinition(LiarRuleWithNativeProvider())
            .addRuleDefinition(LiarRuleWithStarlarkProvider())
            .build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smokeNonexistentFailure() {
        scratch.file("a/BUILD", "")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//a:a")
        assertContainsEvent("target 'a' not declared in package 'a'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureEnabledOnCommandLine() {
        useConfiguration("--features=feature")
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a')"
        )
        val features: com.google.common.collect.ImmutableSet<String?>? = getRuleContext(configure("//a")).getFeatures()
        Truth.assertThat(features).contains("feature")
        Truth.assertThat(features).doesNotContain("other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetIgnoresHostFeatures() {
        useConfiguration("--features=feature", "--host_features=host_feature")
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a')"
        )
        val features: com.google.common.collect.ImmutableSet<String?>? = getRuleContext(configure("//a")).getFeatures()
        Truth.assertThat(features).contains("feature")
        Truth.assertThat(features).doesNotContain("host_feature")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHostFeatures() {
        useConfiguration("--features=feature", "--host_features=host_feature")
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a')"
        )
        val features: com.google.common.collect.ImmutableSet<String?>? =
            getRuleContext(getConfiguredTarget("//a", getExecConfiguration())).getFeatures()
        Truth.assertThat(features).contains("host_feature")
        Truth.assertThat(features).doesNotContain("feature")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureDisabledOnCommandLine() {
        useConfiguration("--features=-feature")
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a')"
        )
        val disabledFeatures: com.google.common.collect.ImmutableSet<String?>? =
            getRuleContext(configure("//a")).getDisabledFeatures()
        Truth.assertThat(disabledFeatures).contains("feature")
        Truth.assertThat(disabledFeatures).doesNotContain("other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureEnabledInPackage() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = ["feature"])

        cc_library(name = "a")
        
        """.trimIndent()
        )
        val features: com.google.common.collect.ImmutableSet<String?>? = getRuleContext(configure("//a")).getFeatures()
        Truth.assertThat(features).contains("feature")
        Truth.assertThat(features).doesNotContain("other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureDisableddInPackage() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = ["-feature"])

        cc_library(name = "a")
        
        """.trimIndent()
        )
        val disabledFeatures: com.google.common.collect.ImmutableSet<String?>? =
            getRuleContext(configure("//a")).getDisabledFeatures()
        Truth.assertThat(disabledFeatures).contains("feature")
        Truth.assertThat(disabledFeatures).doesNotContain("other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureEnabledInRule() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a', features = ['feature'])"
        )
        val features: com.google.common.collect.ImmutableSet<String?>? = getRuleContext(configure("//a")).getFeatures()
        Truth.assertThat(features).contains("feature")
        Truth.assertThat(features).doesNotContain("other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureDisabledInRule() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a', features = ['-feature'])"
        )
        val disabledFeatures: com.google.common.collect.ImmutableSet<String?>? =
            getRuleContext(configure("//a")).getDisabledFeatures()
        Truth.assertThat(disabledFeatures).contains("feature")
        Truth.assertThat(disabledFeatures).doesNotContain("other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeaturesInPackageOverrideFeaturesFromCommandLine() {
        useConfiguration("--features=feature")
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = ["-feature"])

        cc_library(name = "a")
        
        """.trimIndent()
        )
        val ruleContext: RuleContext = getRuleContext(configure("//a"))
        val features: com.google.common.collect.ImmutableSet<String?>? = ruleContext.getFeatures()
        val disabledFeatures: com.google.common.collect.ImmutableSet<String?>? = ruleContext.getDisabledFeatures()
        Truth.assertThat(features).doesNotContain("feature")
        Truth.assertThat(disabledFeatures).contains("feature")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeaturesInRuleOverrideFeaturesFromCommandLine() {
        useConfiguration("--features=feature")
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a', features = ['-feature'])"
        )
        val ruleContext: RuleContext = getRuleContext(configure("//a"))
        val features: com.google.common.collect.ImmutableSet<String?>? = ruleContext.getFeatures()
        val disabledFeatures: com.google.common.collect.ImmutableSet<String?>? = ruleContext.getDisabledFeatures()
        Truth.assertThat(features).doesNotContain("feature")
        Truth.assertThat(disabledFeatures).contains("feature")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeaturesInRuleOverrideFeaturesFromPackage() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = [
            "a",
            "-b",
            "c",
        ])

        cc_library(
            name = "a",
            features = [
                "b",
                "-c",
                "d",
            ],
        )
        
        """.trimIndent()
        )
        val ruleContext: RuleContext = getRuleContext(configure("//a"))
        val features: com.google.common.collect.ImmutableSet<String?>? = ruleContext.getFeatures()
        val disabledFeatures: com.google.common.collect.ImmutableSet<String?>? = ruleContext.getDisabledFeatures()
        Truth.assertThat(features).containsAtLeast("a", "b", "d")
        Truth.assertThat(disabledFeatures).contains("c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeaturesDisabledFromCommandLineOverrideAll() {
        useConfiguration("--features=-package_feature", "--features=-rule_feature")
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = ["package_feature"])

        cc_library(
            name = "a",
            features = ["rule_feature"],
        )
        
        """.trimIndent()
        )
        val ruleContext: RuleContext = getRuleContext(configure("//a"))
        val features: com.google.common.collect.ImmutableSet<String?>? = ruleContext.getFeatures()
        val disabledFeatures: com.google.common.collect.ImmutableSet<String?>? = ruleContext.getDisabledFeatures()
        Truth.assertThat(features).doesNotContain("package_feature")
        Truth.assertThat(features).doesNotContain("rule_feature")
        Truth.assertThat(disabledFeatures).contains("package_feature")
        Truth.assertThat(disabledFeatures).contains("rule_feature")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExperimentalDependenciesOnThirdPartyExperimentalAllowed() {
        scratch.file(
            "third_party/experimental/p1/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["unencumbered"])

        exports_files(["p1.cc"])

        cc_library(name = "p1")
        
        """.trimIndent()
        )
        scratch.file(
            "experimental/p2/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        exports_files(["p2.cc"])

        cc_library(
            name = "p2",
            deps = ["//third_party/experimental/p1"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//experimental/p2:p2") // No errors.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testThirdPartyExperimentalDependenciesOnExperimentalAllowed() {
        scratch.file(
            "experimental/p1/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        exports_files(["p1.cc"])

        cc_library(name = "p1")
        
        """.trimIndent()
        )
        scratch.file(
            "third_party/experimental/p2/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        licenses(["unencumbered"])

        exports_files(["p2.cc"])

        cc_library(
            name = "p2",
            deps = ["//experimental/p1"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//third_party/experimental/p2:p2") // No errors.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependencyOnTestOnlyAllowed() {
        scratch.file(
            "testonly/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "testutil",
            testonly = 1,
            srcs = ["testutil.cc"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "util/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "util",
            srcs = ["util.cc"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "cc/common/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        # testonly=1 -> testonly=1
        cc_library(
            name = "lib1",
            testonly = 1,
            srcs = ["foo1.cc"],
            deps = ["//testonly:testutil"],
        )

        # testonly=0 -> testonly=0
        cc_library(
            name = "lib2",
            testonly = 0,
            srcs = ["foo2.cc"],
            deps = ["//util"],
        )

        # testonly=1 -> testonly=0
        cc_library(
            name = "lib3",
            testonly = 1,
            srcs = ["foo3.cc"],
            deps = [":lib2"],
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//cc/common:lib1") // No errors.
        getConfiguredTarget("//cc/common:lib2") // No errors.
        getConfiguredTarget("//cc/common:lib3") // No errors.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependsOnTestOnlyDisallowed() {
        scratch.file(
            "testonly/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "testutil",
            testonly = 1,
            srcs = ["testutil.cc"],
        )
        
        """.trimIndent()
        )
        checkError(
            "cc/error",
            "cclib",  // error:
            "non-test target '//cc/error:cclib' depends on testonly target '//testonly:testutil' and "
                    + "doesn't have testonly attribute set",  // build file: testonly=0 -> testonly=1
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'cclib',",
            "           srcs  = ['foo.cc'],",
            "           deps = ['//testonly:testutil'],",
            "           testonly = 0)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependsOnTestOnlyOutputFileDisallowed() {
        scratch.file(
            "testonly/BUILD",
            """
        genrule(
            name = "testutil",
            testonly = 1,
            srcs = [],
            outs = ["testutil.cc"],
            cmd = "touch testutil.cc",
        )
        
        """.trimIndent()
        )
        checkError(
            "cc/error",
            "cclib",  // error:
            ("non-test target '//cc/error:cclib' depends on the output file target"
                    + " '//testonly:testutil.cc' of a testonly rule //testonly:testutil and doesn't have"
                    + " testonly attribute set"),  // build file: testonly=0 -> testonly=1
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'cclib',",
            "           srcs  = ['//testonly:testutil.cc'],",
            "           testonly = 0)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependenceOnDeprecatedRule() {
        scratch.file(
            "p/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='p', deps=['//q'])"
        )
        scratch.file(
            "q/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='q', deprecation='Obsolete!')"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        val p: ConfiguredTarget? = getConfiguredTarget("//p")
        Truth.assertThat(view.hasErrors(p)).isFalse()
        assertContainsEvent(
            "target '//p:p' depends on deprecated target '//q:q':"
                    + " Obsolete!"
        )
        Truth.assertThat(eventCollector.count()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependenceOnDeprecatedRuleEmptyExplanation() {
        scratch.file(
            "p/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='p', deps=['//q'])"
        )
        scratch.file(
            "q/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='q', deprecation='')"
        ) // explicitly specified; still counts!

        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        val p: ConfiguredTarget? = getConfiguredTarget("//p")
        Truth.assertThat(view.hasErrors(p)).isFalse()
        assertContainsEvent("target '//p:p' depends on deprecated target '//q:q'")
        Truth.assertThat(eventCollector.count()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoWarningWhenDeprecatedDependsOnDeprecatedRule() {
        scratch.file(
            "foo/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name='foo', srcs=['foo.java'], deps=['//bar:bar'])"
        )
        scratch.file(
            "bar/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name='bar', srcs=['bar.java'], deps=['//baz:baz'], deprecation='BAR')"
        )
        scratch.file(
            "baz/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name='baz', srcs=['baz.java'], deprecation='BAZ')"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        getConfiguredTarget("//foo")
        assertContainsEvent(
            "target '//foo:foo' depends on deprecated "
                    + "target '//bar:bar': BAR"
        )
        assertDoesNotContainEvent(
            "target '//bar:bar' depends on deprecated "
                    + "target '//baz:baz': BAZ"
        )
        Truth.assertThat(eventCollector.count()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttributeErrorContainsLocationOfRule() {
        val e: com.google.devtools.build.lib.events.Event =
            checkError(
                "x",
                "x",  // error:
                BuildViewTestCase.Companion.getErrorNonExistingTarget(
                    "srcs",
                    "java_library",
                    "//x:x",
                    "//x:a.cc"
                ),  // build file:
                "load('@rules_java//java:defs.bzl', 'java_library')",
                "# blank line",
                "java_library(name = 'x',",
                "           srcs = ['a.cc'])"
            )
        Truth.assertThat(e.getLocation().toString()).isEqualTo("/workspace/x/BUILD:3:13")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJavatestsIsTestonly() {
        scratch.file(
            "java/x/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name='x', exports=['//javatests/y'])"
        )
        scratch.file(
            "javatests/y/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name='y')"
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect warning
        val target: ConfiguredTarget? = getConfiguredTarget("//java/x")
        assertContainsEvent(
            "non-test target '//java/x:x' depends on testonly target"
                    + " '//javatests/y:y' and doesn't have testonly attribute set"
        )
        Truth.assertThat(view.hasErrors(target)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependenceOfJavaProductionCodeOnTestPackageGroups() {
        scratch.file(
            "java/banana/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "banana",
            visibility = ["//javatests/plantain:chips"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "javatests/plantain/BUILD",
            """
        package_group(
            name = "chips",
            packages = ["//javatests/plantain"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//java/banana")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnexpectedSourceFileInDeps() {
        scratch.file("x/y.java", "foo")
        checkError(
            "x",
            "x",
            BuildViewTestCase.Companion.getErrorMsgMisplacedFiles("deps", "java_library", "//x:x", "//x:y.java"),
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name='x', srcs=['x.java'], deps=['y.java'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnexpectedButExistingSourceFileDependency() {
        scratch.file("x/y.java")
        checkError(
            "x",
            "x",
            BuildViewTestCase.Companion.getErrorMsgMisplacedFiles("deps", "java_library", "//x:x", "//x:y.java"),
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name='x', srcs=['x.java'], deps=['y.java'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArtifactForImplicitOutput() {
        scratch.file(
            "java/x/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_binary')",
            "java_binary(name='x', srcs=['x.java'])"
        )

        val javaBinary: ConfiguredTarget? = getConfiguredTarget("//java/x:x")
        val classJarArtifact: Artifact? = getFileConfiguredTarget("//java/x:x.jar").getArtifact()
        // Checks if the deploy jar is generated
        getFileConfiguredTarget("//java/x:x_deploy.jar").getArtifact()

        assertThat(BuildViewTestCase.Companion.getOutputGroup(javaBinary, OutputGroupInfo.FILES_TO_COMPILE).toList())
            .containsExactly(classJarArtifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelfEdgeInRule() {
        scratch.file(
            "x/BUILD",

            "genrule(name='x', srcs=['x'], outs=['out'], cmd=':')"
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        getConfiguredTarget("//x")
        assertContainsSelfEdgeEvent("//x:x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNegativeShardCount() {
        checkError(
            "foo",
            "bar",
            "Must not be negative.",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='bar', srcs=['mockingbird.sh'], shard_count=-1)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExcessiveShardCount() {
        checkError(
            "foo",
            "bar",
            "indicative of poor test organization",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name='bar', srcs=['mockingbird.sh'], shard_count=51)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonexistingTargetErrorMsg() {
        checkError(
            "foo",
            "foo",
            BuildViewTestCase.Companion.getErrorNonExistingTarget("deps", "cc_binary", "//foo:foo", "//foo:nonesuch"),
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo',",
            "srcs = ['foo.cc'],",
            "deps = [':nonesuch'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRulesDontProvideRequiredFragmentsByDefault() {
        scratch.file(
            "a/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        config_setting(
            name = "config",
            values = {"start_end_lib": "1"},
        )

        foo_library(
            name = "pylib",
            srcs = ["pylib.py"],
        )

        foo_library(
            name = "a",
            srcs = ["A.cc"],
            deps = [":pylib"],
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//a:a").getProvider(RequiredConfigFragmentsProvider::class.java))
            .isNull()
        assertThat(getConfiguredTarget("//a:config").getProvider(RequiredConfigFragmentsProvider::class.java))
            .isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun findArtifactByOutputLabel_twoOutputsWithSameBasename() {
        scratch.file(
            "foo/BUILD", "genrule(name = 'gen', outs = ['sub/out', 'out'], cmd = 'touch $(OUTS)')"
        )
        val foo: RuleConfiguredTarget? = getConfiguredTarget("//foo:gen") as RuleConfiguredTarget?
        assertThat(
            foo.findArtifactByOutputLabel(Label.parseCanonical("//foo:sub/out"))
                .getRepositoryRelativePath()
                .getPathString()
        )
            .isEqualTo("foo/sub/out")
        assertThat(
            foo.findArtifactByOutputLabel(Label.parseCanonical("//foo:out"))
                .getRepositoryRelativePath()
                .getPathString()
        )
            .isEqualTo("foo/out")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNativeRuleAttrSetToNoneFails() {
        setBuildLanguageOptions("--incompatible_fail_on_unknown_attributes")
        scratch.file(
            "p/BUILD",  //
            "genrule(name = 'genrule', srcs = ['a.java'], outs = ['b'], cmd = '', bat = None)"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getTarget("//p:genrule")

        assertContainsEvent("no such attribute 'bat' in 'genrule' rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNativeRuleAttrSetToNoneDoesntFails() {
        setBuildLanguageOptions("--noincompatible_fail_on_unknown_attributes")
        scratch.file(
            "p/BUILD",  //
            "genrule(name = 'genrule', srcs = ['a.java'], outs = ['b'], cmd = '', bat = None)"
        )

        getTarget("//p:genrule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkRuleAttrSetToNoneFails() {
        setBuildLanguageOptions("--incompatible_fail_on_unknown_attributes")
        scratch.file(
            "p/rule.bzl",
            """
        def _impl(ctx):
            pass

        my_rule = rule(_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":rule.bzl", "my_rule")

        my_rule(
            name = "my_target",
            bat = None,
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getTarget("//p:my_target")

        assertContainsEvent("no such attribute 'bat' in 'my_rule' rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkRuleAttrSetToNoneDoesntFail() {
        setBuildLanguageOptions("--noincompatible_fail_on_unknown_attributes")
        scratch.file(
            "p/rule.bzl",
            """
        def _impl(ctx):
            pass

        my_rule = rule(_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load(":rule.bzl", "my_rule")

        my_rule(
            name = "my_target",
            bat = None,
        )
        
        """.trimIndent()
        )

        getTarget("//p:my_target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNativeRuleNotReturnNativeAdvertisedProviderFail() {
        scratch.file(
            "p/BUILD",
            """
        liar_rule_with_native_provider(
            name = "my_target",
          )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val unused: ConfiguredTarget? = configure("//p:my_target")

        assertContainsEvent(
            "in liar_rule_with_native_provider rule //p:my_target: rule advertised the 'FooProvider'"
                    + " provider, but this provider was not among those returned"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNativeRuleNotReturnStarlarkAdvertisedProviderFail() {
        scratch.file(
            "p/BUILD",
            """
        liar_rule_with_starlark_provider(
            name = "my_target",
          )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val unused: ConfiguredTarget? = configure("//p:my_target")

        assertContainsEvent(
            "in liar_rule_with_starlark_provider rule //p:my_target: rule advertised the 'STARLARK_P1'"
                    + " provider, but this provider was not among those returned"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        scratch.file(
            "foo/BUILD", "genrule(name = 'gen', outs = ['sub/out', 'out'], cmd = 'touch $(OUTS)')"
        )
        val original: RuleConfiguredTarget? = getConfiguredTarget("//foo:gen") as RuleConfiguredTarget?

        // TODO: b/364831651 - consider factoring out the ObjectCodecs setup to a common location.
        val deserialized: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            roundTripWithSkyframe(
                ObjectCodecs(
                    initializeAnalysisCodecRegistryBuilder(
                        getRuleClassProvider(),
                        makeReferenceConstants(
                            directories,
                            getRuleClassProvider(),
                            directories.getWorkspace().getBaseName()
                        )
                    )
                        .build(),
                    com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
                        .put<ArtifactSerializationContext?>(
                            ArtifactSerializationContext::class.java,
                            getSkyframeExecutor().getSkyframeBuildView().getArtifactFactory()
                            ::getSourceArtifact
                        )
                        .put<RuleClassProvider?>(
                            RuleClassProvider::class.java,
                            getRuleClassProvider()
                        ) // We need a RootCodecDependencies but don't care about the likely roots.
                        .put<Root.RootCodecDependencies?>(
                            Root.RootCodecDependencies::class.java,
                            RootCodecDependencies()
                        ) // This is needed to determine TargetData for a ConfiguredTarget during
                        // serialization.
                        .put<PrerequisitePackageFunction?>(
                            PrerequisitePackageFunction::class.java,
                            getSkyframeExecutor()::getExistingPackage
                        )
                        .put<T?>(
                            BuildOptions::class.java,
                            BuildOptions.getDefaultBuildOptionsForFragments(
                                com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java)
                            )
                        )
                        .build()
                ),
                FingerprintValueService.createForTesting(),
                { key ->
                    try {
                        return@roundTripWithSkyframe getSkyframeExecutor().getEvaluator().getExistingValue(key)
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(e)
                    }
                },
                original
            )
        assertThat(dumpStructureWithEquivalenceReduction(original))
            .isEqualTo(dumpStructureWithEquivalenceReduction(deserialized))
    }
}
