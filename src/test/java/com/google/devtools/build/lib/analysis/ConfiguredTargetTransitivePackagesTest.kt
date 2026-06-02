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

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/** Test that checks collected list of transitive targets of configured targets.  */
@RunWith(JUnit4::class)
class ConfiguredTargetTransitivePackagesTest : AnalysisTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUpToolsConfigMock() {
        MockProtoSupport.setup(mockToolsConfig)
    }

    override fun allowExternalRepositories(): Boolean {
        // Transitive packages are only stored when external repositories are enabled.
        return true
    }

    @Throws(java.lang.Exception::class)
    private fun assertTransitiveClosureOfTargetContainsPackages(
        target: String?, config: BuildConfigurationValue?, vararg packages: String?
    ) {
        val ctValue: ConfiguredTargetValue? =
            SkyframeExecutorTestUtils.getExistingConfiguredTargetValue(
                skyframeExecutor, Label.parseCanonical(target), config
            )
        val packageNames: com.google.common.collect.ImmutableSet<String?>? =
            ctValue.getTransitivePackages().toList().stream()
                .map({ pkgMetadata -> pkgMetadata.packageIdentifier().toString() })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        Truth.assertThat(packageNames)
            .containsAtLeastElementsIn(com.google.common.collect.Sets.newHashSet<String?>(*packages))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleConfiguredTarget() {
        scratch.file("a/BUILD", "filegroup(name = 'a', srcs = [ '//a/b:b' ])")
        scratch.file("a/b/BUILD", "filegroup(name = 'b', srcs = [ '//c:c', '//d:d'] )")
        scratch.file("c/BUILD", "filegroup(name = 'c')")
        scratch.file("d/BUILD", "filegroup(name = 'd')")

        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update("//a:a").getTargetsToBuild())
        val config: BuildConfigurationValue? = getConfiguration(target)

        assertTransitiveClosureOfTargetContainsPackages("//a:a", config, "a", "a/b", "c", "d")
        assertTransitiveClosureOfTargetContainsPackages("//a/b:b", config, "a/b", "c", "d")
        assertTransitiveClosureOfTargetContainsPackages("//c:c", config, "c")
        assertTransitiveClosureOfTargetContainsPackages("//d:d", config, "d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackagesFromAspects() {
        setRulesAvailableInTests(TestAspects.BASE_RULE, TestAspects.EXTRA_ATTRIBUTE_ASPECT_RULE)
        scratch.file("extra/BUILD", "base(name = 'extra')")
        scratch.file(
            "a/c/BUILD",
            """
        rule_with_extra_deps_aspect(
            name = "foo",
            foo = [":bar"],
        )

        base(name = "bar")
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update("//a/c:foo").getTargetsToBuild())
        val config: BuildConfigurationValue? = getConfiguration(target)

        // We expect 'extra' package because rule_with_extra_deps adds an aspect on attribute 'foo' with
        // '//extra:extra' dependency.
        assertTransitiveClosureOfTargetContainsPackages("//a/c:foo", config, "a/c", "extra")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetsWithConfiguration() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a', srcs = [ 'some.cpp' ])"
        )

        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update("//a:a").getTargetsToBuild())
        val config: BuildConfigurationValue? = getConfiguration(target)

        // We expect to get the mock crosstool in transitive dependencies, because it's required for c++
        // configuration.
        assertTransitiveClosureOfTargetContainsPackages(
            "//a:a",
            config,
            "a",
            PackageIdentifier.create(
                TestConstants.TOOLS_REPOSITORY,
                PathFragment.create(TestConstants.MOCK_CC_CROSSTOOL_PATH)
            )
                .toString()
        )
    }
}
