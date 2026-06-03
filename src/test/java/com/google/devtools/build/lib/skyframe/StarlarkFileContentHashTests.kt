// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * Tests for the hash code calculated for Starlark RuleClasses based on the transitive closure of
 * the imports of their respective definition StarlarkEnvironments.
 */
@RunWith(JUnit4::class)
class StarlarkFileContentHashTests : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        scratch.file("foo/BUILD")
        scratch.file("bar/BUILD")
        scratch.file("helper/BUILD")

        scratch.file(
            "helper/ext.bzl",
            """
        def rule_impl(ctx):
            return None
        
        """.trimIndent()
        )

        scratch.file(
            "foo/ext.bzl",
            """
        load("//helper:ext.bzl", "rule_impl")

        foo1 = rule(implementation = rule_impl)
        foo2 = rule(implementation = rule_impl)
        
        """.trimIndent()
        )

        scratch.file(
            "bar/ext.bzl",
            """
        load("//helper:ext.bzl", "rule_impl")

        bar1 = rule(implementation = rule_impl)
        
        """.trimIndent()
        )

        scratch.file(
            "pkg/BUILD",
            """
        load("//bar:ext.bzl", "bar1")
        load("//foo:ext.bzl", "foo1", "foo2")

        foo1(name = "foo1")

        foo2(name = "foo2")

        bar1(name = "bar1")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHashInvariance() {
        Truth.assertThat(getHash("pkg", "foo1")).isEqualTo(getHash("pkg", "foo1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHashInvarianceAfterOverwritingFileWithSameContents() {
        val bar1 = getHash("pkg", "bar1")
        scratch.overwriteFile(
            "bar/ext.bzl",
            """
        load("//helper:ext.bzl", "rule_impl")

        bar1 = rule(implementation = rule_impl)
        
        """.trimIndent()
        )
        invalidatePackages()
        Truth.assertThat(getHash("pkg", "bar1")).isEqualTo(bar1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHashSameForRulesDefinedInSameFile() {
        Truth.assertThat(getHash("pkg", "foo2")).isEqualTo(getHash("pkg", "foo1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHashNotSameForRulesDefinedInDifferentFiles() {
        assertNotEquals(getHash("pkg", "foo1"), getHash("pkg", "bar1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImmediateFileChangeChangesHash() {
        val bar1 = getHash("pkg", "bar1")
        scratch.overwriteFile(
            "bar/ext.bzl",
            """
        load("//helper:ext.bzl", "rule_impl")
        # Some comments to change file hash

        bar1 = rule(implementation = rule_impl)
        
        """.trimIndent()
        )
        invalidatePackages()
        assertNotEquals(bar1, getHash("pkg", "bar1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveFileChangeChangesHash() {
        val bar1 = getHash("pkg", "bar1")
        val foo1 = getHash("pkg", "foo1")
        val foo2 = getHash("pkg", "foo2")
        scratch.overwriteFile(
            "helper/ext.bzl",
            """
        # Some comments to change file hash
        def rule_impl(ctx):
            return None
        
        """.trimIndent()
        )
        invalidatePackages()
        assertNotEquals(bar1, getHash("pkg", "bar1"))
        assertNotEquals(foo1, getHash("pkg", "foo1"))
        assertNotEquals(foo2, getHash("pkg", "foo2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileChangeDoesNotAffectRulesDefinedOutsideOfTransitiveClosure() {
        val foo1 = getHash("pkg", "foo1")
        val foo2 = getHash("pkg", "foo2")
        scratch.overwriteFile(
            "bar/ext.bzl",
            """
        load("//helper:ext.bzl", "rule_impl")
        # Some comments to change file hash

        bar1 = rule(implementation = rule_impl)
        
        """.trimIndent()
        )
        invalidatePackages()
        Truth.assertThat(getHash("pkg", "foo1")).isEqualTo(foo1)
        Truth.assertThat(getHash("pkg", "foo2")).isEqualTo(foo2)
    }

    /**
     * Returns the hash code of the rule target defined by the pkg and the target name parameters.
     * Asserts that the targets and it's Starlark dependencies were loaded properly.
     */
    @Throws(java.lang.Exception::class)
    private fun getHash(pkg: String?, name: String?): String {
        val packageOptions: PackageOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
        packageOptions.setDefaultVisibility(RuleVisibility.PUBLIC)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        getSkyframeExecutor()
            .preparePackageLoading(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                ),
                packageOptions,
                parseBuildLanguageOptions(),
                UUID.randomUUID(),
                com.google.common.collect.ImmutableMap.of<String?, String?>(),
                QuiescingExecutorsImpl.forTesting(),
                TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
            )
        skyframeExecutor.setActionEnv(com.google.common.collect.ImmutableMap.of<String?, String?>())
        val pkgLookupKey: SkyKey? = PackageIdentifier.createInMainRepo(pkg)
        val result: EvaluationResult<PackageValue?> =
            SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), pkgLookupKey,  /*keepGoing=*/false, reporter
            )
        assertThat(result.hasError()).isFalse()
        val targets: MutableCollection<Target> = result.get(pkgLookupKey).getPackage().getTargets().values()
        for (target in targets) {
            if (target.getName().equals(name)) {
                val hash: ByteArray = (target as Rule).getRuleClassObject().ruleDefinitionEnvironmentDigest
                return com.google.common.io.BaseEncoding.base16().lowerCase().encode(hash) // hexify
            }
        }
        throw java.lang.IllegalStateException("target not found: " + name)
    }

    companion object {
        private fun assertNotEquals(hash: String, hash2: String?) {
            Truth.assertThat(hash == hash2).isFalse()
        }
    }
}
