// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.analysis.BlazeVersionInfo

/** Tests for [BazelModuleResolutionFunction].  */
@RunWith(JUnit4::class)
class BazelModuleResolutionFunctionTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBazelInvalidCompatibility() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.overwriteFile(
            "MODULE.bazel", "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0dd'])"
        )
        invalidatePackages(false)

        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        assertThat(result.hasError()).isTrue()
        assertContainsEvent("invalid version argument '>5.1.0dd'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleBazelCompatibilityFailure() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.4'])"
        )
        invalidatePackages(false)

        setBlazeVersion("5.1.4")
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        assertThat(result.hasError()).isTrue()
        assertContainsEvent(
            "Bazel version 5.1.4 is not compatible with module \"<root>\" (bazel_compatibility:"
                    + " [>5.1.0, <5.1.4])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBazelCompatibilityWarning() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.4'])"
        )
        skyframeExecutor.injectExtraPrecomputedValues(
            com.google.common.collect.ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE,
                    BazelCompatibilityMode.WARNING
                )
            )
        )
        invalidatePackages(false)

        setBlazeVersion("5.1.4")
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        assertThat(result.hasError()).isFalse()
        assertContainsEvent(
            "Bazel version 5.1.4 is not compatible with module \"<root>\" (bazel_compatibility:"
                    + " [>5.1.0, <5.1.4])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisablingBazelCompatibility() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.4'])"
        )
        skyframeExecutor.injectExtraPrecomputedValues(
            com.google.common.collect.ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE,
                    BazelCompatibilityMode.OFF
                )
            )
        )
        invalidatePackages(false)

        setBlazeVersion("5.1.4")
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        assertThat(result.hasError()).isFalse()
        assertDoesNotContainEvent(
            "Bazel version 5.1.4 is not compatible with module \"<root>\" (bazel_compatibility:"
                    + " [>5.1.0, <5.1.4])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBazelCompatibilitySuccess() {
        setupModulesForCompatibility()

        setBlazeVersion("5.1.4-pre.20220421.3")
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )
        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBazelCompatibilityFailure() {
        setupModulesForCompatibility()

        setBlazeVersion("5.1.5rc444")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        assertThat(result.hasError()).isTrue()
        assertContainsEvent(
            "Bazel version 5.1.5rc444 is not compatible with module \"b@1.0\" (bazel_compatibility:"
                    + " [<=5.1.4, -5.1.2])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRcIsCompatibleWithReleaseRequirement() {
        scratch.overwriteFile(
            "MODULE.bazel", "module(name='mod', version='1.0', bazel_compatibility=['>=6.4.0'])"
        )
        invalidatePackages(false)

        setBlazeVersion("6.4.0rc1")
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrereleaseIsNotCompatibleWithReleaseRequirement() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.overwriteFile(
            "MODULE.bazel", "module(name='mod', version='1.0', bazel_compatibility=['>=6.4.0'])"
        )
        invalidatePackages(false)

        setBlazeVersion("6.4.0-pre-1")
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        assertThat(result.hasError()).isTrue()
        assertContainsEvent(
            "Bazel version 6.4.0-pre-1 is not compatible with module \"<root>\" (bazel_compatibility:"
                    + " [>=6.4.0])"
        )
    }

    private fun setBlazeVersion(version: String) {
        BlazeVersionInfo.setBuildInfoForTesting(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                BlazeVersionInfo.BUILD_LABEL,
                version
            )
        )
    }

    @Throws(java.lang.Exception::class)
    private fun setupModulesForCompatibility() {
        /* Root depends on "a" which depends on "b"
       The only versions that would work with root, a and b compatibility constrains are between
       -not including- 5.1.2 and 5.1.4.
       Ex: 5.1.3rc44, 5.1.3, 5.1.4-pre22.44
    */
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='mod', version='1.0', bazel_compatibility=['>5.1.0', '<5.1.6'])",
            "bazel_dep(name = 'a', version = '1.0')"
        )

        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("a", "1.0"),
                "module(name='a', version='1.0', bazel_compatibility=['>=5.1.2', '-5.1.4']);",
                "bazel_dep(name='b', version='1.0')"
            )
            .addModule(
                BzlmodTestUtil.createModuleKey("b", "1.0"),
                "module(name='b', version='1.0', bazel_compatibility=['<=5.1.4', '-5.1.2']);"
            )
        invalidatePackages(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testYankedVersionCheckSuccess() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        setupModulesForYankedVersion()
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        assertThat(result.hasError()).isTrue()
        com.google.common.truth.Subject.contains(
            "Yanked version detected in your resolved dependency graph: b@1.0, for the reason: 1.0"
                    + " is a bad version!"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testYankedVersionCheckIgnoredByAll() {
        skyframeExecutor.injectExtraPrecomputedValues(
            com.google.common.collect.ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, com.google.common.collect.ImmutableList.of<E?>("all")
                )
            )
        )
        setupModulesForYankedVersion()
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )
        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testYankedVersionCheckIgnoredBySpecific() {
        skyframeExecutor.injectExtraPrecomputedValues(
            com.google.common.collect.ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, com.google.common.collect.ImmutableList.of<E?>("b@1.0")
                )
            )
        )
        setupModulesForYankedVersion()
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )
        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadYankedVersionFormat() {
        skyframeExecutor.injectExtraPrecomputedValues(
            com.google.common.collect.ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS, com.google.common.collect.ImmutableList.of<E?>("b+1.0")
                )
            )
        )
        setupModulesForYankedVersion()
        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )
        assertThat(result.hasError()).isTrue()
        com.google.common.truth.Subject.contains(
            "Parsing command line flag --allow_yanked_versions=b+1.0 failed, module versions must"
                    + " be of the form '<module name>@<version>'"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun setupModulesForYankedVersion() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='mod', version='1.0')",
            "bazel_dep(name = 'a', version = '1.0')"
        )

        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("a", "1.0"),
                "module(name='a', version='1.0');",
                "bazel_dep(name='b', version='1.0')"
            )
            .addModule(BzlmodTestUtil.createModuleKey("b", "1.0"), "module(name='b', version='1.0');")
            .addYankedVersion(
                "b",
                com.google.common.collect.ImmutableMap.of<K?, V?>(Version.parse("1.0"), "1.0 is a bad version!")
            )
        invalidatePackages(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun overrideOnNonexistentModule() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='mod', version='1.0')",
            "bazel_dep(name = 'a', version = '1.0')",
            "bazel_dep(name = 'b', version = '1.1')",
            "local_path_override(module_name='d', path='whatevs')"
        )

        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("a", "1.0"),
                "module(name='a', version='1.0')",
                "bazel_dep(name='b', version='1.0')"
            )
            .addModule(BzlmodTestUtil.createModuleKey("c", "1.0"), "module(name='c', version='1.0')")
            .addModule(BzlmodTestUtil.createModuleKey("c", "1.1"), "module(name='c', version='1.1')")
            .addModule(
                BzlmodTestUtil.createModuleKey("b", "1.0"),
                "module(name='b', version='1.0')",
                "bazel_dep(name='c', version='1.1')"
            )
            .addModule(
                BzlmodTestUtil.createModuleKey("b", "1.1"),
                "module(name='b', version='1.1')",
                "bazel_dep(name='c', version='1.0')"
            )
        invalidatePackages(false)

        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        assertThat(result.hasError()).isTrue()
        com.google.common.truth.Subject.contains("the root module specifies overrides on nonexistent module(s): d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrintBehavior() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "module(name='mod', version='1.0')",
            "print('hello from root module')",
            "bazel_dep(name = 'a', version = '1.0')",
            "bazel_dep(name = 'b', version = '1.1')",
            "single_version_override(module_name = 'b', version = '1.1')",
            "local_path_override(module_name='a', path='a')"
        )
        scratch.file(
            "a/MODULE.bazel",
            "module(name='a', version='1.0')",
            "print('hello from overridden a')",
            "bazel_dep(name='b', version='1.0')"
        )

        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("a", "1.0"),
                "module(name='a', version='1.0')",
                "print('hello from a@1.0')",
                "bazel_dep(name='b', version='1.0')"
            )
            .addModule(BzlmodTestUtil.createModuleKey("c", "1.0"), "module(name='c', version='1.0')")
            .addModule(BzlmodTestUtil.createModuleKey("c", "1.1"), "module(name='c', version='1.1')")
            .addModule(
                BzlmodTestUtil.createModuleKey("b", "1.0"),
                "module(name='b', version='1.0')",
                "bazel_dep(name='c', version='1.1')",
                "print('hello from b@1.0')"
            )
            .addModule(
                BzlmodTestUtil.createModuleKey("b", "1.1"),
                "module(name='b', version='1.1')",
                "bazel_dep(name='c', version='1.0')",
                "print('hello from b@1.1')"
            )
        invalidatePackages(false)

        SkyframeExecutorTestUtils.evaluate<T?>(
            skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
        )

        assertContainsEvent("hello from root module")
        assertContainsEvent("hello from overridden a")
        assertDoesNotContainEvent("hello from a@1.0")
        assertDoesNotContainEvent("hello from b@1.0")
        assertDoesNotContainEvent("hello from b@1.1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nodep_unfulfilled() {
        scratch.overwriteFile(
            "MODULE.bazel",
            """
        bazel_dep(name='b',version='1.0')
        bazel_dep(name='c',version='1.0',repo_name=None)
        
        """.trimIndent()
        )

        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("b", "1.0"),
                "module(name='b', version='1.0');bazel_dep(name='d', version='1.0')"
            )
            .addModule(
                BzlmodTestUtil.createModuleKey("c", "1.0"),
                "module(name='c', version='1.0');bazel_dep(name='d',version='1.1')"
            )
            .addModule(BzlmodTestUtil.createModuleKey("d", "1.0"), "module(name='d', version='1.0')")
            .addModule(BzlmodTestUtil.createModuleKey("d", "1.1"), "module(name='d', version='1.1')")
        invalidatePackages(false)

        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val depGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            result.get(BazelModuleResolutionValue.KEY).getResolvedDepGraph()
        assertThat(depGraph).doesNotContainKey(BzlmodTestUtil.createModuleKey("c", "1.0"))
        assertThat(depGraph.get(BzlmodTestUtil.createModuleKey("b", "1.0")).getDeps().get("d").version())
            .isEqualTo(Version.parse("1.0"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nodep_fulfilled() {
        scratch.overwriteFile(
            "MODULE.bazel",
            """
        bazel_dep(name='b',version='1.0')
        bazel_dep(name='c',version='1.0')
        
        """.trimIndent()
        )

        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("b", "1.0"),
                "module(name='b', version='1.0');bazel_dep(name='d', version='1.0')"
            )
            .addModule(
                BzlmodTestUtil.createModuleKey("c", "1.0"),
                "module(name='c', version='1.0');bazel_dep(name='d',version='1.1',repo_name=None)"
            )
            .addModule(BzlmodTestUtil.createModuleKey("d", "1.0"), "module(name='d', version='1.0')")
            .addModule(BzlmodTestUtil.createModuleKey("d", "1.1"), "module(name='d', version='1.1')")
        invalidatePackages(false)

        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val depGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            result.get(BazelModuleResolutionValue.KEY).getResolvedDepGraph()
        assertThat(depGraph).containsKey(BzlmodTestUtil.createModuleKey("d", "1.1"))
        assertThat(depGraph.get(BzlmodTestUtil.createModuleKey("b", "1.0")).getDeps().get("d").version())
            .isEqualTo(Version.parse("1.1"))
        assertThat(depGraph.get(BzlmodTestUtil.createModuleKey("c", "1.0")).getDeps()).doesNotContainKey("d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nodep_fulfilledDevDep() {
        scratch.overwriteFile(
            "MODULE.bazel",
            """
        bazel_dep(name='b',version='1.0')
        bazel_dep(name='c',version='1.1',dev_dependency=True)
        
        """.trimIndent()
        )

        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("b", "1.0"),
                "module(name='b', version='1.0');bazel_dep(name='c', version='1.0')"
            )
            .addModule(BzlmodTestUtil.createModuleKey("c", "1.0"), "module(name='c', version='1.0')")
            .addModule(BzlmodTestUtil.createModuleKey("c", "1.1"), "module(name='c', version='1.1')")
        invalidatePackages(false)

        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val depGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            result.get(BazelModuleResolutionValue.KEY).getResolvedDepGraph()
        assertThat(depGraph).containsKey(BzlmodTestUtil.createModuleKey("c", "1.1"))
        assertThat(depGraph.get(BzlmodTestUtil.createModuleKey("b", "1.0")).getDeps().get("c").version())
            .isEqualTo(Version.parse("1.1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nodep_wouldBeFulfilledIfNonDevDep() {
        scratch.overwriteFile(
            "MODULE.bazel",
            """
        bazel_dep(name='b',version='1.0')
        bazel_dep(name='c',version='1.0')
        
        """.trimIndent()
        )

        registry
            .addModule(
                BzlmodTestUtil.createModuleKey("b", "1.0"),
                "module(name='b', version='1.0');bazel_dep(name='d', version='1.0')"
            )
            .addModule(
                BzlmodTestUtil.createModuleKey("c", "1.0"),
                "module(name='c', version='1.0')",
                "bazel_dep(name='d',version='1.1',repo_name=None,dev_dependency=True)"
            )
            .addModule(BzlmodTestUtil.createModuleKey("d", "1.0"), "module(name='d', version='1.0')")
            .addModule(BzlmodTestUtil.createModuleKey("d", "1.1"), "module(name='d', version='1.1')")
        invalidatePackages(false)

        val result: EvaluationResult<BazelModuleResolutionValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                skyframeExecutor, BazelModuleResolutionValue.KEY, false, reporter
            )

        if (result.hasError()) {
            org.junit.Assert.fail(result.getError().toString())
        }
        val depGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            result.get(BazelModuleResolutionValue.KEY).getResolvedDepGraph()
        assertThat(depGraph).doesNotContainKey(BzlmodTestUtil.createModuleKey("d", "1.1"))
        assertThat(depGraph.get(BzlmodTestUtil.createModuleKey("b", "1.0")).getDeps().get("d").version())
            .isEqualTo(Version.parse("1.0"))
        assertThat(depGraph.get(BzlmodTestUtil.createModuleKey("c", "1.0")).getDeps()).doesNotContainKey("d")
    }
}
