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
package com.google.devtools.build.lib.query2.common

import com.google.devtools.build.lib.packages.RuleVisibility

@RunWith(JUnit4::class)
class QueryPreloadingTest : QueryPreloadingTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelVisitorDetectsMissingPackages() {
        reporter.removeHandler(failFastHandler) // expect errors

        scratch.file(
            "pkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'x', deps = ['//nopkg:y', 'z'])
        foo_library(name = 'z')
        
        """.trimIndent()
        )

        assertLabelsVisitedWithErrors(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x")
        )
        assertContainsEvent("no such package 'nopkg'")
    }

    /**
     * Tests that Blaze is resilient to changing symlinks between builds. This test is a more
     * "integrated" version of FilesystemValueCheckerTest#testDirtySymlink.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testChangingSymlink() {
        val path: Path? =
            scratch.file(
                "foo/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'foo')"
            )
        val sym1: Path = scratch.resolve(rootDirectory + "/sym1/BUILD")
        val sym2: Path? = scratch.resolve(rootDirectory + "/sym2/BUILD")
        val symlink: Path = scratch.resolve(rootDirectory + "/bar/BUILD")
        FileSystemUtils.ensureSymbolicLink(symlink, sym1)
        FileSystemUtils.ensureSymbolicLink(sym1, path)
        FileSystemUtils.ensureSymbolicLink(sym2, path)
        scratch.file(
            "unrelated/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'unrelated')"
        )
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//bar:foo"),
            com.google.common.collect.ImmutableSet.of<String?>("//bar:foo"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
        assertThat(sym1.delete()).isTrue()
        FileSystemUtils.ensureSymbolicLink(sym1, sym2)
        syncPackages()
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//unrelated:unrelated"),
            com.google.common.collect.ImmutableSet.of<String?>("//unrelated:unrelated"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
        assertThat(sym1.delete()).isTrue()
        FileSystemUtils.ensureSymbolicLink(sym1, path)
        assertThat(symlink.delete()).isTrue()
        scratch.file(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar')"
        )
        syncPackages()
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//bar:bar"),
            com.google.common.collect.ImmutableSet.of<String?>("//bar:bar"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailFastLoading() {
        reporter.removeHandler(failFastHandler) // expect errors

        val buildFile: Path =
            scratch.file(
                "pkg/BUILD",
                """
            load('//test_defs:foo_library.bzl', 'foo_library')
            foo_library(name = 'x', deps = ['z', 'z'])
            foo_library(name = 'z')
            
            """.trimIndent()
            )

        // We expect an error on "//pkg:x". However, we can still finish the evaluation and also return
        // "//pkg:z" even without keep_going.
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
        assertContainsEvent("Label '//pkg:z' is duplicated in the 'deps' attribute of rule 'x'")
        assertLabelsVisitedWithErrors(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x")
        )

        // Also make sure reloading works if the package has changed, but the names
        // of the targets have not.
        scratch.overwriteFile(
            "pkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'x', deps = ['z'])
        foo_library(name = 'z')
        
        """.trimIndent()
        )
        buildFile.setLastModifiedTime(buildFile.getLastModifiedTime() + 1)
        syncPackages()
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
        // Check stability (not redundant).
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewFailure() {
        reporter.removeHandler(failFastHandler) // expect errors

        val buildFile: Path =
            scratch.file(
                "pkg/BUILD",
                """
            load('//test_defs:foo_library.bzl', 'foo_library')
            foo_library(name = 'x', deps = ['z'])
            foo_library(name = 'z')
            
            """.trimIndent()
            )
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )

        scratch.overwriteFile(
            "pkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'x', deps = ['z', 'z'])
        foo_library(name = 'z')
        
        """.trimIndent()
        )
        buildFile.setLastModifiedTime(buildFile.getLastModifiedTime() + 1)
        syncPackages()
        // We expect an error on "//pkg:x". However, we can still finish the evaluation and also return
        // "//pkg:z" even without keep_going.
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
        // Check stability (not redundant).
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
        // Also check keep-going.
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            QueryPreloadingTestCase.Companion.KEEP_GOING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewTransitiveFailure() {
        reporter.removeHandler(failFastHandler) // expect errors

        val buildFile: Path =
            scratch.file(
                "pkg/BUILD",
                """
            load('//test_defs:foo_library.bzl', 'foo_library')
            foo_library(name = 'x', deps = ['z'])
            foo_library(name = 'z')
            
            """.trimIndent()
            )
        scratch.file(
            "pkg2/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'q', deps=['F','F'])
        foo_library(name = 'F')
        
        """.trimIndent()
        )
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )

        scratch.overwriteFile(
            "pkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'x', deps = ['z'])
        foo_library(name = 'z', deps = [ '//pkg2:q'])
        
        """.trimIndent()
        )
        buildFile.setLastModifiedTime(buildFile.getLastModifiedTime() + 1)
        syncPackages()

        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z", "//pkg2:q", "//pkg2:F"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            QueryPreloadingTestCase.Companion.KEEP_GOING
        )
        // Check stability (not redundant).
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z", "//pkg2:q", "//pkg2:F"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            QueryPreloadingTestCase.Companion.KEEP_GOING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddDepInNewPkg() {
        val buildFile: Path =
            scratch.file(
                "pkg/BUILD",
                """
            load('//test_defs:foo_library.bzl', 'foo_library')
            foo_library(name = 'x', deps = ['z'])
            foo_library(name = 'z')
            
            """.trimIndent()
            )
        scratch.file(
            "pkg2/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'q')"
        )

        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )

        scratch.overwriteFile(
            "pkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'x', deps = ['z', '//pkg2:q'])
        foo_library(name = 'z')
        
        """.trimIndent()
        )
        buildFile.setLastModifiedTime(buildFile.getLastModifiedTime() + 1)
        syncPackages()

        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z", "//pkg2:q"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
        // Check stability (not redundant).
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z", "//pkg2:q"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
    }

    // Regression test for: "IllegalArgumentException thrown during build."  This happened if "."
    // occurred in a label name segment.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDotLabelName() {
        scratch.file("pkg/BUILD", "exports_files(srcs = ['.', 'x/.'])")

        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:.", "//pkg:x/."),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:.", "//pkg:x/."),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )

        syncPackages()

        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:.", "//pkg:x/."),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:.", "//pkg:x/."),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelVisitorPlural() {
        reporter.removeHandler(failFastHandler) // expect errors

        scratch.file(
            "pkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'x', deps = ['//nopkg:y', 'z'])
        foo_library(name = 'z')
        foo_library(name = 'o', deps = ['//nopkg2:o'])
        
        """.trimIndent()
        )

        assertLabelsVisitedWithErrors(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z", "//pkg:o"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:o")
        )
        assertContainsEvent("no such package 'nopkg'")
        assertContainsEvent("no such package 'nopkg2'")
    }

    // Indirectly tests that there are dependencies between packages and their subpackages.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackageBoundaryAdd() {
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'x', deps = ['//foo:y/z'])
        foo_library(name = 'y/z')
        
        """.trimIndent()
        )

        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x", "//foo:y/z"),
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )

        scratch.file(
            "foo/y/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'z')"
        )
        syncPackages(
            ModifiedFileSet.builder()
                .modify(PathFragment.create("foo/y"))
                .modify(PathFragment.create("foo/y/BUILD"))
                .build()
        )

        reporter.removeHandler(failFastHandler) // expect errors
        assertLabelsVisitedWithErrors(
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x"),
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x")
        )
        assertContainsEvent("Label '//foo:y/z' crosses boundary of subpackage 'foo/y'")
    }

    // Indirectly tests that there are dependencies between packages and their subpackages.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubpackageBoundaryDelete() {
        reporter.removeHandler(failFastHandler) // expect errors
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'x', deps = ['//foo:y/z'])
        foo_library(name = 'y/z')
        
        """.trimIndent()
        )
        scratch.file(
            "foo/y/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'z')"
        )
        assertLabelsVisitedWithErrors(
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x"),
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x")
        )
        assertContainsEvent("Label '//foo:y/z' crosses boundary of subpackage 'foo/y'")

        scratch.deleteFile("foo/y/BUILD")
        syncPackages(ModifiedFileSet.builder().modify(PathFragment.create("foo/y/BUILD")).build())

        reporter.addHandler(failFastHandler) // don't expect errors
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x", "//foo:y/z"),
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterruptPending() {
        scratch.file("x/BUILD")
        java.lang.Thread.currentThread().interrupt()

        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable {
                assertLabelsVisitedWithErrors(
                    com.google.common.collect.ImmutableSet.of<String?>(
                        "//x:x"
                    ), com.google.common.collect.ImmutableSet.of<String?>("//x:BUILD")
                )
            })
    }

    // Regression test for "crash when // encountered in package name".
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleSlashInPackageName() {
        reporter.removeHandler(failFastHandler) // expect errors
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='x', deps=['//foo//y'])"
        )
        assertLabelsVisitedWithErrors(
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x"),
            com.google.common.collect.ImmutableSet.of<String?>("//foo:x")
        )
        assertContainsEvent(
            ("//foo:x: invalid label '//foo//y' in element 0 of attribute "
                    + "'deps' of 'foo_library': invalid package name 'foo//y': "
                    + "package names may not contain '//' path separators")
        )
    }

    // Regression test for "Bazel hangs on input of illegal rule".
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCrashInLoadPackageIsReportedEffectively() {
        reporter.removeHandler(failFastHandler)
        // Inject a NullPointerException into loadPackage().  This is triggered by
        // any ERROR event.
        reporter.addHandler(
            { event ->
                if (com.google.devtools.build.lib.events.EventKind.ERRORS.contains(event.getKind())) {
                    throw java.lang.NullPointerException("oops")
                }
            })

        // Visitation of //x reaches package "bad" by many paths.  The first time,
        // loadPackage() crashes (because of the injected NPE).  Previously,
        // on a subsequent visitation, the visitor would get livelocked due the
        // stale PendingEntry stuck in the PackageCache.  With the fix, the NPE is
        // thrown.
        scratch.file("bad/BUILD", "this is a bad build file")
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name='x',
                   deps=['//bad:a', '//bad:b', '//bad:c',
                         '//bad:d', '//bad:e', '//bad:f'])
        
        """.trimIndent()
        )

        try {
            // Used to get stuck.
            assertLabelsVisitedWithErrors(
                com.google.common.collect.ImmutableSet.of<String?>("//foo:x"),
                com.google.common.collect.ImmutableSet.of<String?>("//foo:x")
            )
            org.junit.Assert.fail() // unreachable
        } catch (npe: java.lang.NullPointerException) {
            // This is expected for legacy blaze.
        } catch (re: java.lang.RuntimeException) {
            // This is expected for Skyframe blaze.
            Truth.assertThat(re).hasCauseThat().isInstanceOf(java.lang.NullPointerException::class.java)
        }
    }

    // Regression test for: "Need better context for missing build file error due to
    // use in visibility rule".
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorMessageContainsTarget() {
        reporter.removeHandler(failFastHandler) // expect errors

        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        package_group(name = 'pkgs', includes = ['//not/a/package:pkgs'])
        foo_library(name = 'foo', visibility = [':pkgs'])
        
        """.trimIndent()
        )

        assertLabelsVisitedWithErrors(
            com.google.common.collect.ImmutableSet.of<String?>("//foo:foo", "//foo:pkgs"),
            com.google.common.collect.ImmutableSet.of<String?>("//foo:foo")
        )
        assertContainsEvent(
            "in target '//foo:pkgs', no such label '//not/a/package:pkgs': no "
                    + "such package 'not/a/package'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoing() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "parent/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'parent', deps = ['//child:child'])
        x = 1//0
        
        """.trimIndent()
        ) // dynamic error
        scratch.file(
            "child/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'child')
        x = 1//0
        
        """.trimIndent()
        ) // dynamic error
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//parent:parent", "//child:child"),
            com.google.common.collect.ImmutableSet.of<String?>("//parent:parent"),
            QueryPreloadingTestCase.Companion.KEEP_GOING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewBuildFileConflict() {
        reporter.removeHandler(failFastHandler) // expect errors
        scratch.file(
            "pkg/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'x', deps = ['//pkg2:q/sub'])"
        )
        scratch.file(
            "pkg2/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'q/sub')"
        )

        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg2:q/sub"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )

        scratch.file("pkg2/q/BUILD")
        syncPackages()

        assertLabelsVisitedWithErrors(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x")
        )
        assertContainsEvent("Label '//pkg2:q/sub' crosses boundary of subpackage 'pkg2/q'")
        assertContainsEvent("no such target '//pkg2:q/sub'")
        // Check stability (not redundant).
        assertLabelsVisitedWithErrors(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x")
        )
        assertContainsEvent("Label '//pkg2:q/sub' crosses boundary of subpackage 'pkg2/q'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithNoSubincludes() {
        val packageOptions: PackageOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
        packageOptions.setDefaultVisibility(RuleVisibility.PRIVATE)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(7)
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(BuildLanguageOptions::class.java).build()
        parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
        val options: BuildLanguageOptions? = parser.getOptions<O?>(BuildLanguageOptions::class.java)
        getSkyframeExecutor()
            .preparePackageLoading(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                ),
                packageOptions,
                options,
                UUID.randomUUID(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                QuiescingExecutorsImpl.forTesting(),
                TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
            )
        skyframeExecutor.setActionEnv(com.google.common.collect.ImmutableMap.of<K?, V?>())
        this.visitor = getSkyframeExecutor().getQueryTransitivePackagePreloader()
        scratch.file(
            "pkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'x', deps = ['z'])
        foo_library(name = 'z')
        
        """.trimIndent()
        )
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x", "//pkg:z"),
            com.google.common.collect.ImmutableSet.of<String?>("//pkg:x"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )

        scratch.file(
            "hassub/BUILD",
            """
        load('//sub:sub.bzl', 'fct')
        fct()
        
        """.trimIndent()
        )
        scratch.file("sub/BUILD", "exports_files(['sub'])")
        scratch.file(
            "sub/sub.bzl",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "def fct(): foo_library(name='zzz')"
        )

        assertLabelsVisited(
            com.google.common.collect.ImmutableSet.of<String?>("//hassub:zzz"),
            com.google.common.collect.ImmutableSet.of<String?>("//hassub:zzz"),
            !QueryPreloadingTestCase.Companion.KEEP_GOING
        )
    }
}
