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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Action

/** Analysis caching tests.  */
@RunWith(JUnit4::class)
class AnalysisCachingTest : AnalysisCachingTestBase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        useConfiguration()
    }

    @Throws(java.lang.Exception::class)
    override fun useConfiguration(vararg args: String?) {
        super.useConfiguration(
            *com.google.common.collect.ObjectArrays.concat<String?>(
                args,
                "--experimental_google_legacy_api"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleCleanAnalysis() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:A")
        val javaTest: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        assertThat(javaTest).isNotNull()
        assertThat(JavaInfo.getProvider<T?>(JavaSourceJarsProvider::class.java, javaTest)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTickTock() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )

        java_test(
            name = "B",
            srcs = ["B.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:A")
        update("//java/a:B")
        update("//java/a:A")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFullyCached() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:A")
        val old: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        update("//java/a:A")
        val current: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        assertThat(current).isSameInstanceAs(old)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubsetCached() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )

        java_test(
            name = "B",
            srcs = ["B.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:A", "//java/a:B")
        val old: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        update("//java/a:A")
        val current: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        assertThat(current).isSameInstanceAs(old)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependencyChanged() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
            deps = ["//java/b"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "java/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["B.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:A")
        val old: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        scratch.overwriteFile(
            "java/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["C.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:A")
        val current: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        assertThat(current).isNotSameInstanceAs(old)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectHintsChanged() {
        scratch.file(
            "foo/rule.bzl",
            """
        def _rule_impl(ctx):
            return []

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {
                "deps": attr.label_list(),
                "srcs": attr.label_list(allow_files = True),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load("//foo:rule.bzl", "my_rule")

        my_rule(
            name = "foo",
            deps = [":bar"],
        )

        my_rule(
            name = "bar",
            aspect_hints = ["//aspect_hint:hint"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "aspect_hint/BUILD",
            """
        load("//foo:rule.bzl", "my_rule")

        my_rule(
            name = "hint",
            srcs = ["baz.h"],
        )
        
        """.trimIndent()
        )

        update("//foo:foo")
        val old: ConfiguredTarget = getConfiguredTarget("//foo:foo")
        scratch.overwriteFile(
            "aspect_hint/BUILD",
            """
        load("//foo:rule.bzl", "my_rule")

        my_rule(
            name = "hint",
            srcs = ["qux.h"],
        )
        
        """.trimIndent()
        )
        update("//foo:foo")
        val current: ConfiguredTarget = getConfiguredTarget("//foo:foo")

        assertThat(current).isNotSameInstanceAs(old)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelChanged() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
            deps = ["//java/b"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "java/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["B.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:A")
        val old: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        scratch.overwriteFile(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:A")
        val current: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        assertThat(current).isNotSameInstanceAs(old)
    }

    // Regression test for:
    // "action conflict detection is incorrect if conflict is in non-top-level configured targets".
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConflictInDependencyImpliesTopLevelTargetFailure() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67529176): conflicts not detected.
            return
        }
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "conflict_non_top_level/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")

        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )

        cc_binary(
            name = "foo",
            data = ["_objs/x/foo.o"],
            deps = ["x"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//conflict_non_top_level:foo")
        assertContainsEvent("file 'conflict_non_top_level/_objs/x/foo.o' " + AnalysisCachingTestBase.CONFLICT_MSG)
        assertThat(getAnalysisResult().getTargetsToBuild()).isEmpty()
    }

    /**
     * Generating the same output from two targets is ok if we build them on successive builds and
     * invalidate the first target before we build the second target. This is a strictly weaker test
     * than if we didn't invalidate the first target, but since Skyframe can't pass then, this test
     * could be useful for it. Actually, since Skyframe makes multiple update calls, it manages to
     * unregister actions even when it shouldn't, and so this test can incorrectly pass. However,
     * `SkyframeExecutorTest#testNoActionConflictWithInvalidatedTarget` tests it more
     * rigorously.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoActionConflictWithInvalidatedTarget() {
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "conflict/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        update("//conflict:x")
        val conflict: ConfiguredTarget = getConfiguredTarget("//conflict:x")
        val oldAction: Action = getGeneratingAction(getBinArtifact("_objs/x/foo.o", conflict))
        assertThat(oldAction.getOwner().getLabel().toString()).isEqualTo("//conflict:x")
        scratch.overwriteFile(
            "conflict/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        # Rename target.
        cc_library(
            name = "newx",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        update(defaultFlags(), "//conflict:_objs/x/foo.o")
        val objsConflict: ConfiguredTarget = getConfiguredTarget("//conflict:_objs/x/foo.o")
        val newAction: Action = getGeneratingAction(getBinArtifact("_objs/x/foo.o", objsConflict))
        assertThat(newAction.getOwner().getLabel().toString()).isEqualTo("//conflict:_objs/x/foo.o")
    }

    /** Generating the same output from multiple actions is causing an error.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConflictCausesError() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67529176): conflicts not detected.
            return
        }
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "conflict/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")

        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//conflict:x", "//conflict:_objs/x/foo.o")
        assertContainsEvent("file 'conflict/_objs/x/foo.o' " + AnalysisCachingTestBase.CONFLICT_MSG)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoActionConflictErrorAfterClearedAnalysis() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67529176): conflicts not detected.
            return
        }
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "conflict/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")

        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//conflict:x", "//conflict:_objs/x/foo.o")
        // We want to force a "dropConfiguredTargetsNow" operation, which won't inform the
        // invalidation receiver about the dropped configured targets.
        skyframeExecutor.clearAnalysisCache(
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.common.collect.ImmutableSet.of<E?>()
        )
        assertContainsEvent("file 'conflict/_objs/x/foo.o' " + AnalysisCachingTestBase.CONFLICT_MSG)
        eventCollector.clear()
        scratch.overwriteFile(
            "conflict/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "x",
            srcs = ["baz.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//conflict:x", "//conflict:_objs/x/foo.o")
        assertNoEvents()
    }

    /**
     * For two conflicting actions whose primary inputs are different, no list diff detail should be
     * part of the output.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictingArtifactsErrorWithNoListDetail() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67529176): conflicts not detected.
            return
        }
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "conflict/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")

        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//conflict:x", "//conflict:_objs/x/foo.o")

        assertContainsEvent("file 'conflict/_objs/x/foo.o' " + AnalysisCachingTestBase.CONFLICT_MSG)
        assertDoesNotContainEvent("MandatoryInputs")
        assertDoesNotContainEvent("Outputs")
    }

    /**
     * For two conflicted actions whose primary inputs are the same, list diff (max 5) should be part
     * of the output.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictingArtifactsWithListDetail() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67529176): conflicts not detected.
            return
        }
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "conflict/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='x', srcs=['foo1.cc'])",
            "genrule(name = 'foo', outs=['_objs/x/foo1.o'], srcs=['foo1.cc', 'foo2.cc', "
                    + "'foo3.cc', 'foo4.cc', 'foo5.cc', 'foo6.cc'], cmd='', output_to_bindir=1)"
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//conflict:x", "//conflict:foo")

        val event: com.google.devtools.build.lib.events.Event =
            assertContainsEvent("file 'conflict/_objs/x/foo1.o' " + AnalysisCachingTestBase.CONFLICT_MSG)
        assertContainsEvent("MandatoryInputs")
        assertContainsEvent("Outputs")

        // Validate that maximum of 5 artifacts in MandatoryInputs are part of output.
        val pattern: java.util.regex.Pattern = java.util.regex.Pattern.compile("\tconflict\\/foo[2-6].cc")
        val matcher: java.util.regex.Matcher = pattern.matcher(event.getMessage())
        var matchCount = 0
        while (matcher.find()) {
            matchCount++
        }

        Truth.assertWithMessage(
            "Event does not contain expected number of file conflicts:\n%s", event.getMessage()
        )
            .that(matchCount)
            .isEqualTo(5)
    }

    /**
     * The current action conflict detection code will only mark one of the targets as having an
     * error, and with multi-threaded analysis it is not deterministic which one that will be.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConflictMarksTargetInvalid() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67529176): conflicts not detected.
            return
        }
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "conflict/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")

        cc_library(
            name = "x",
            srcs = ["foo.cc"],
        )

        cc_binary(
            name = "_objs/x/foo.o",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler) // expect errors
        val successfulAnalyses: Int =
            update(
                defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING),
                "//conflict:x",
                "//conflict:_objs/x/foo.pic.o"
            )
                .getTargetsToBuild()
                .size()
        Truth.assertThat(successfulAnalyses).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasConflict() {
        scratch.file(
            "conflict/conflict.bzl",
            """
        def _conflict(ctx):
            file = ctx.actions.declare_file("single_file")
            ctx.actions.write(output = file, content = ctx.attr.name)
            return [DefaultInfo(files = depset([file]))]

        my_rule = rule(implementation = _conflict)
        
        """.trimIndent()
        )
        scratch.file(
            "conflict/BUILD",
            """
        load(":conflict.bzl", "my_rule")

        my_rule(name = "conflict1")

        my_rule(name = "conflict2")

        alias(
            name = "aliased",
            actual = ":conflict2",
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//conflict:conflict1", "//conflict:aliased") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionConflictFromSameTarget() {
        scratch.file(
            "conflict/conflict.bzl",
            """
        def _conflict(ctx):
            file = ctx.actions.declare_file("single_file")
            ctx.actions.write(output = file, content = "a")
            ctx.actions.write(output = file, content = "b")
            return [DefaultInfo(files = depset([file]))]

        my_rule = rule(implementation = _conflict)
        
        """.trimIndent()
        )
        scratch.file(
            "conflict/BUILD",
            """
        load(":conflict.bzl", "my_rule")

        my_rule(name = "conflict")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//conflict") })
        assertContainsEvent("file 'conflict/single_file' is generated by these conflicting actions:")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun actionConflictWithDependentRule() {
        scratch.file(
            "conflict/conflict.bzl",
            """
        def _dep(ctx):
            file = ctx.actions.declare_file("file")
            ctx.actions.write(output = file, content = "")
            return [DefaultInfo(files = depset([file]))]

        dep_rule = rule(implementation = _dep)

        def _top(ctx):
            file = ctx.file.src
            ctx.actions.write(output = file, content = "")
            return [DefaultInfo(files = depset([file]))]

        top_rule = rule(
            implementation = _top,
            attrs = {"src": attr.label(mandatory = True, allow_single_file = True)},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "conflict/BUILD",
            """
        load(":conflict.bzl", "dep_rule", "top_rule")

        top_rule(
            name = "top",
            src = ":dep",
        )

        dep_rule(name = "dep")
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//conflict:top") })

        assertContainsEvent(
            "in top_rule rule //conflict:top: File 'conflict/file' is produced by action 'Writing file"
                    + " conflict/file' but is already generated by rule //conflict:dep"
        )
    }

    /** BUILD file involved in BUILD-file cycle is changed  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildFileInCycleChanged() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67412276): cycles not properly handled.
            return
        }
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
            deps = ["//java/b"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "java/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["B.java"],
            deps = ["//java/c"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "java/c/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "c",
            srcs = ["C.java"],
            deps = ["//java/b"],
        )
        
        """.trimIndent()
        )
        // expect error
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//java/a:A")
        val old: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        // drop dependency on from b to c
        scratch.overwriteFile(
            "java/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "b",
            srcs = ["B.java"],
        )
        
        """.trimIndent()
        )
        eventCollector.clear()
        reporter.addHandler(FoundationTestCase.failFastHandler)
        update("//java/a:A")
        val current: ConfiguredTarget = getConfiguredTarget("//java/a:A")
        assertThat(current).isNotSameInstanceAs(old)
    }

    private fun assertNoTargetsVisited() {
        val analyzedTargets: MutableSet<*> = getSkyframeEvaluatedTargetKeys()
        Truth.assertWithMessage(analyzedTargets.toString()).that(analyzedTargets.size()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSecondRunAllCacheHits() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:A")
        update("//java/a:A")
        assertNoTargetsVisited()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependencyAllCacheHits() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "x",
            srcs = ["A.java"],
            deps = ["y"],
        )

        java_library(
            name = "y",
            srcs = ["B.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:x")
        val oldAnalyzedTargets: MutableSet<*> = getSkyframeEvaluatedTargetKeys()
        Truth.assertThat(oldAnalyzedTargets.size()).isAtLeast(2) // could be greater due to implicit deps
        Truth.assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:x")).isEqualTo(1)
        Truth.assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:y")).isEqualTo(1)

        update("//java/a:y")
        Truth.assertThat(getSkyframeEvaluatedTargetKeys()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSupersetNotAllCacheHits() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        # It's important that all targets are of the same rule class, otherwise the second update
        # call might analyze more than one extra target because of potential implicit dependencies.
        java_library(
            name = "x",
            srcs = ["A.java"],
            deps = ["y"],
        )

        java_library(
            name = "y",
            srcs = ["B.java"],
            deps = ["z"],
        )

        java_library(
            name = "z",
            srcs = ["C.java"],
        )
        
        """.trimIndent()
        )
        update("//java/a:y")
        val oldAnalyzedTargets: MutableSet<*> = getSkyframeEvaluatedTargetKeys()
        Truth.assertThat(oldAnalyzedTargets.size()).isAtLeast(3) // could be greater due to implicit deps
        Truth.assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:x")).isEqualTo(0)
        Truth.assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:y")).isEqualTo(1)
        update("//java/a:x")
        val newAnalyzedTargets: MutableSet<*> = getSkyframeEvaluatedTargetKeys()
        // Source target and x.
        Truth.assertThat(newAnalyzedTargets).hasSize(2)
        Truth.assertThat(countObjectsPartiallyMatchingRegex(newAnalyzedTargets, "//java/a:x")).isEqualTo(1)
        Truth.assertThat(countObjectsPartiallyMatchingRegex(newAnalyzedTargets, "//java/a:y")).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraActions() {
        scratch.file(
            "java/com/google/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name='a', srcs=['A.java'])
        
        """.trimIndent()
        )
        scratch.file(
            "java/com/google/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name='b', srcs=['B.java'])
        
        """.trimIndent()
        )
        scratch.file(
            "extra/BUILD",
            """
        extra_action(
            name = "extra",
            cmd = "",
            out_templates = ["${'$'}(OWNER_LABEL_DIGEST)_${'$'}(ACTION_ID).tst"],
        )

        action_listener(
            name = "listener",
            extra_actions = [":extra"],
            mnemonics = ["Javac"],
        )
        
        """.trimIndent()
        )

        useConfiguration("--experimental_action_listener=//extra:listener")
        update("//java/com/google/a:a")
        update("//java/com/google/b:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraActionsCaching() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name='a', srcs=['A.java'])
        
        """.trimIndent()
        )
        scratch.file(
            "extra/BUILD",
            """
        extra_action(
            name = "extra",
            cmd = "echo ${'$'}(EXTRA_ACTION_FILE)",
            out_templates = ["${'$'}(OWNER_LABEL_DIGEST)_${'$'}(ACTION_ID).tst"],
        )

        action_listener(
            name = "listener",
            extra_actions = [":extra"],
            mnemonics = ["Javac"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--experimental_action_listener=//extra:listener")

        update("//java/a:a")
        getConfiguredTarget("//java/a:a")

        scratch.overwriteFile(
            "extra/BUILD",
            """
        extra_action(
            name = "extra",
            # <-- change here
            cmd = "echo ${'$'}(BUG)",
            out_templates = ["${'$'}(OWNER_LABEL_DIGEST)_${'$'}(ACTION_ID).tst"],
        )

        action_listener(
            name = "listener",
            extra_actions = [":extra"],
            mnemonics = ["Javac"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val e: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { update("//java/a:a") })
        assertThat(e).hasMessageThat().contains("Analysis of target '//java/a:a' failed")
        assertContainsEvent("$(BUG) not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationCachingWithWarningReplay() {
        useConfiguration("--strip=always", "--copt=-g")
        update()
        assertContainsEvent("Debug information will be generated and then stripped away")
        eventCollector.clear()
        update()
        assertContainsEvent("Debug information will be generated and then stripped away")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSkyframeCacheInvalidationBuildFileChange() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        
        """.trimIndent()
        )
        val aTarget = "//java/a:A"
        update(aTarget)
        val firstCT: ConfiguredTarget = getConfiguredTarget(aTarget)

        scratch.overwriteFile(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["B.java"],
        )
        
        """.trimIndent()
        )

        update(aTarget)
        val updatedCT: ConfiguredTarget = getConfiguredTarget(aTarget)
        assertThat(updatedCT).isNotSameInstanceAs(firstCT)

        update(aTarget)
        val updated2CT: ConfiguredTarget = getConfiguredTarget(aTarget)
        assertThat(updated2CT).isSameInstanceAs(updatedCT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSkyframeDifferentPackagesInvalidation() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "A",
            srcs = ["A.java"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "java/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "B",
            srcs = ["B.java"],
        )
        
        """.trimIndent()
        )

        val aTarget = "//java/a:A"
        update(aTarget)
        val oldAConfTarget: ConfiguredTarget = getConfiguredTarget(aTarget)
        val bTarget = "//java/b:B"
        update(bTarget)
        val oldBConfTarget: ConfiguredTarget = getConfiguredTarget(bTarget)

        scratch.overwriteFile(
            "java/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(
            name = "B",
            srcs = ["C.java"],
        )
        
        """.trimIndent()
        )

        update(aTarget)
        // Check that 'A' was not invalidated because 'B' was modified and invalidated.
        val newAConfTarget: ConfiguredTarget = getConfiguredTarget(aTarget)
        val newBConfTarget: ConfiguredTarget = getConfiguredTarget(bTarget)

        assertThat(newAConfTarget).isSameInstanceAs(oldAConfTarget)
        assertThat(newBConfTarget).isNotSameInstanceAs(oldBConfTarget)
    }

    private fun countObjectsPartiallyMatchingRegex(
        elements: Iterable<out Any>, toStringMatching: String?
    ): Int {
        var toStringMatching = toStringMatching
        toStringMatching = ".*" + toStringMatching + ".*"
        var result = 0
        for (o in elements) {
            if (o.toString().matches(toStringMatching)) {
                ++result
            }
        }
        return result
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetSkyframeEvaluatedTargetKeysOmitsCachedTargets() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "x",
            srcs = ["A.java"],
            deps = [
                "w",
                "z",
            ],
        )

        java_library(
            name = "y",
            srcs = ["B.java"],
            deps = [
                "w",
                "z",
            ],
        )

        java_library(
            name = "z",
            srcs = ["C.java"],
        )

        java_library(
            name = "w",
            srcs = ["D.java"],
        )
        
        """.trimIndent()
        )

        update("//java/a:x")
        val oldAnalyzedTargets: MutableSet<*> = getSkyframeEvaluatedTargetKeys()
        Truth.assertThat(oldAnalyzedTargets.size()).isAtLeast(2) // could be greater due to implicit deps
        Truth.assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:x")).isEqualTo(1)
        Truth.assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:y")).isEqualTo(0)
        Truth.assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:z")).isEqualTo(1)
        Truth.assertThat(countObjectsPartiallyMatchingRegex(oldAnalyzedTargets, "//java/a:w")).isEqualTo(1)

        // Unless the build is not fully cached, we get notified about newly evaluated targets, as well
        // as cached top-level targets. For the two tests above to work correctly, we need to ensure
        // that getSkyframeEvaluatedTargetKeys() doesn't return these.
        update("//java/a:x", "//java/a:y", "//java/a:z")
        assertNumberOfAnalyzedConfigurationsOfTargets(
            com.google.common.collect.ImmutableMap.builder<String?, Int?>()
                .put("//java/a:y", 1) // Newly requested.
                .put("//java/a:B.java", 1)
                .put("//java/a:z", 0) // Fully cached.
                .buildOrThrow()
        )
    }

    /** Test options class for testing diff-based analysis cache resetting.  */
    @OptionsClass
    abstract class DiffResetOptions : FragmentOptions() {
        @com.google.devtools.common.options.Option(
            name = "probably_irrelevant",
            defaultValue = "(unset)",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.UNKNOWN],
            help = "This option is irrelevant to non-uses_irrelevant targets and is trimmed from them."
        )
        abstract fun getProbablyIrrelevantOption(): String?

        abstract fun setProbablyIrrelevantOption(value: String?)

        @com.google.devtools.common.options.Option(
            name = "also_irrelevant",
            defaultValue = "(unset)",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.UNKNOWN],
            help = "This option is irrelevant to non-uses_irrelevant targets and is trimmed from them."
        )
        abstract fun getAlsoIrrelevantOption(): String?

        abstract fun setAlsoIrrelevantOption(value: String?)

        @com.google.devtools.common.options.Option(
            name = "definitely_relevant",
            defaultValue = "(unset)",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.UNKNOWN],
            help = "This option is not trimmed and is used by all targets."
        )
        abstract fun getDefinitelyRelevantOption(): String?

        @com.google.devtools.common.options.Option(
            name = "also_relevant",
            defaultValue = "(unset)",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.UNKNOWN],
            help = "This option is not trimmed and is used by all targets."
        )
        abstract fun getAlsoRelevantOption(): String?

        @com.google.devtools.common.options.Option(
            name = "host_relevant",
            defaultValue = "(unset)",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.UNKNOWN],
            help = "This option is not trimmed and is used by all host targets."
        )
        abstract fun getHostRelevantOption(): String?

        companion object {
            val PROBABLY_IRRELEVANT_OPTION: OptionDefinition =
                OptionsParser.getOptionDefinitionByName(DiffResetOptions::class.java, "probably_irrelevant")
            val ALSO_IRRELEVANT_OPTION: OptionDefinition =
                OptionsParser.getOptionDefinitionByName(DiffResetOptions::class.java, "also_irrelevant")
            val CLEAR_IRRELEVANT: PatchTransition = object : PatchTransition() {
                public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
                    return com.google.common.collect.ImmutableSet.of<E?>(DiffResetOptions::class.java)
                }

                public override fun patch(
                    options: BuildOptionsView,
                    eventHandler: com.google.devtools.build.lib.events.EventHandler?
                ): BuildOptions {
                    if (options.underlying().hasNoConfig()) {
                        return options.underlying()
                    }
                    val cloned: BuildOptionsView = options.clone()
                    cloned.get(DiffResetOptions::class.java).setProbablyIrrelevantOption("(cleared)")
                    cloned.get(DiffResetOptions::class.java).setAlsoIrrelevantOption("(cleared)")
                    return cloned.underlying()
                }
            }
        }
    }

    /** Test fragment.  */
    @StarlarkBuiltin(name = "test_diff_fragment", doc = "fragment for testing differy fragments")
    @RequiresOptions(options = [DiffResetOptions::class])
    class DiffResetFragment(buildOptions: BuildOptions?) : Fragment(), StarlarkValue

    @Throws(java.lang.Exception::class)
    private fun setupDiffResetTesting() {
        val optionsThatCanChange: com.google.common.collect.ImmutableSet<OptionDefinition?> =
            com.google.common.collect.ImmutableSet.of<OptionDefinition?>(
                DiffResetOptions.Companion.PROBABLY_IRRELEVANT_OPTION, DiffResetOptions.Companion.ALSO_IRRELEVANT_OPTION
            )
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(DiffResetFragment::class.java)
        builder.overrideShouldInvalidateCacheForOptionDiffForTesting(
            { newOptions, changedOption, oldValue, newValue -> !optionsThatCanChange.contains(changedOption) })
        builder.overrideTrimmingTransitionFactoryForTesting(
            object : TransitionFactory() {
                public override fun create(ruleData: RuleTransitionData): ConfigurationTransition {
                    if (ruleData.rule().getRuleClassObject().getName().equals("uses_irrelevant")) {
                        return NoTransition.INSTANCE
                    }
                    return DiffResetOptions.Companion.CLEAR_IRRELEVANT
                }

                public override fun transitionType(): TransitionType {
                    return TransitionType.RULE
                }
            })
        useRuleClassProvider(builder.build())
        scratch.file(
            "test/lib.bzl",
            """
        def _empty_impl(ctx):
            pass

        normal_lib = rule(
            implementation = _empty_impl,
            fragments = ["test_diff_fragment"],
            attrs = {
                "deps": attr.label_list(),
                "host_deps": attr.label_list(cfg = "exec"),
            },
        )
        uses_irrelevant = rule(
            implementation = _empty_impl,
            fragments = ["test_diff_fragment"],
            attrs = {
                "deps": attr.label_list(),
                "host_deps": attr.label_list(cfg = "exec"),
            },
        )
        
        """.trimIndent()
        )
        update()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheNotClearedWhenOptionsStaySame() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            deps = [":shared"],
        )

        normal_lib(name = "shared")
        
        """.trimIndent()
        )
        useConfiguration("--definitely_relevant=Testing")
        update("//test:top")
        update("//test:top")
        assertNoTargetsVisited()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearedWhenNonAllowedOptionsChange() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            deps = [":shared"],
        )

        normal_lib(name = "shared")
        
        """.trimIndent()
        )
        useConfiguration("--definitely_relevant=Test 1")
        update("//test:top")
        useConfiguration("--definitely_relevant=Test 2")
        update("//test:top")
        useConfiguration("--definitely_relevant=Test 1")
        update("//test:top")
        // these targets needed to be reanalyzed even though we built them in this configuration
        // just a moment ago
        assertNumberOfAnalyzedConfigurationsOfTargets(
            com.google.common.collect.ImmutableMap.builder<String?, Int?>()
                .put("//test:top", 1)
                .put("//test:shared", 1)
                .buildOrThrow()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheNotClearedForExecWhenNonExecOptionsChange() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            host_deps = [":shared"],
        )

        normal_lib(name = "shared")
        
        """.trimIndent()
        )
        useConfiguration("--host_relevant=Test 1")
        update("//test:top")
        useConfiguration("--host_relevant=Test 2")
        update("//test:top")
        // //test:shared is in the exec configuration, and --host_relevant is not part of the exec
        // configuration. Therefore, //test:shared is not reanalyzed, even though //test:top is.
        assertNumberOfAnalyzedConfigurationsOfTargets(
            com.google.common.collect.ImmutableMap.builder<String?, Int?>()
                .put("//test:top", 1)
                .put("//test:shared", 0)
                .buildOrThrow()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearedForExecWhenExecOptionsChange() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            host_deps = [":shared"],
        )

        normal_lib(name = "shared")
        
        """.trimIndent()
        )
        // --host_compilation_mode is part of the exec configuration.
        useConfiguration("--host_compilation_mode=opt")
        update("//test:top")
        useConfiguration("--host_compilation_mode=dbg")
        update("//test:top")

        // Now, //test:shared is reanalyzed, because --host_compilation_mode is part of the exec
        // configuration.
        assertNumberOfAnalyzedConfigurationsOfTargets(
            com.google.common.collect.ImmutableMap.builder<String?, Int?>()
                .put("//test:top", 1)
                .put("//test:shared", 1)
                .buildOrThrow()
        )

        // Return to the original configuration and check that the cache is not cleared.
        useConfiguration("--host_compilation_mode=opt")
        update("//test:top")
        // these targets needed to be reanalyzed even though we built them in this configuration
        // just a moment ago
        assertNumberOfAnalyzedConfigurationsOfTargets(
            com.google.common.collect.ImmutableMap.builder<String?, Int?>()
                .put("//test:top", 1)
                .put("//test:shared", 1)
                .buildOrThrow()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheNotClearedWhenAllowedOptionsChange() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib", "uses_irrelevant")

        uses_irrelevant(
            name = "top",
            deps = [":shared"],
        )

        normal_lib(name = "shared")
        
        """.trimIndent()
        )
        useConfiguration("--definitely_relevant=Testing", "--probably_irrelevant=Test 1")
        update("//test:top")
        useConfiguration("--definitely_relevant=Testing", "--probably_irrelevant=Test 2")
        update("//test:top")
        // the shared library got to reuse the cached value, while the entry point had to be rebuilt in
        // the new configuration
        assertNumberOfAnalyzedConfigurationsOfTargets(
            com.google.common.collect.ImmutableMap.builder<String?, Int?>()
                .put("//test:top", 1)
                .put("//test:shared", 0)
                .buildOrThrow()
        )
        useConfiguration("--definitely_relevant=Testing", "--probably_irrelevant=Test 1")
        update("//test:top")
        // now we're back to the old configuration with no cache clears, so no work needed to be done
        assertNumberOfAnalyzedConfigurationsOfTargets(
            com.google.common.collect.ImmutableMap.builder<String?, Int?>()
                .put("//test:top", 0)
                .put("//test:shared", 0)
                .buildOrThrow()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheNotClearedWhenRedundantDefinesChange() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--define=a=1", "--define=a=2")
        update("//test:top")
        useConfiguration("--define=a=2")
        update("//test:top")
        assertNumberOfAnalyzedConfigurationsOfTargets(
            com.google.common.collect.ImmutableMap.of<String?, Int?>(
                "//test:top",
                0
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noCacheClearMessageAfterCleanWithSameOptions() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration()
        update("//test:top")
        cleanSkyframe()
        eventCollector.clear()
        update("//test:top")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noCacheClearMessageAfterCleanWithDifferentOptions() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--definitely_relevant=before")
        update("//test:top")
        cleanSkyframe()
        useConfiguration("--definitely_relevant=after")
        eventCollector.clear()
        update("//test:top")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noCacheClearMessageAfterDiscardAnalysisCacheThenCleanWithSameOptions() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--discard_analysis_cache")
        update("//test:top")
        cleanSkyframe()
        eventCollector.clear()
        update("//test:top")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noCacheClearMessageAfterDiscardAnalysisCacheThenCleanWithChangedOptions() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--definitely_relevant=before", "--discard_analysis_cache")
        update("//test:top")
        cleanSkyframe()
        useConfiguration("--definitely_relevant=after", "--discard_analysis_cache")
        eventCollector.clear()
        update("//test:top")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageAfterDiscardAnalysisCacheBuild() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration(
            "--max_config_changes_to_show=-1",
            "--probably_irrelevant=yeah",
            "--discard_analysis_cache"
        )
        update("//test:top")
        eventCollector.clear()
        update("//test:top")
        assertContainsEvent("--discard_analysis_cache")
        assertDoesNotContainEvent("Build option")
        assertContainsEvent("discarding analysis cache")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noCacheClearMessageAfterNonDiscardAnalysisCacheBuild() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--max_config_changes_to_show=-1", "--discard_analysis_cache")
        update("//test:top")
        useConfiguration("--max_config_changes_to_show=-1")
        update("//test:top")
        eventCollector.clear()
        update("//test:top")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noCacheClearMessageAfterIrrelevantOptionChanges() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--max_config_changes_to_show=-1", "--probably_irrelevant=old")
        update("//test:top")
        useConfiguration("--max_config_changes_to_show=-1", "--probably_irrelevant=new")
        eventCollector.clear()
        update("//test:top")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noCacheClearMessageAfterIrrelevantOptionChangesWithDiffDisabled() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--max_config_changes_to_show=0", "--probably_irrelevant=old")
        update("//test:top")
        useConfiguration("--max_config_changes_to_show=0", "--probably_irrelevant=new")
        eventCollector.clear()
        update("//test:top")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageAfterChangingPlatform() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration(
            "--max_config_changes_to_show=-1", "--platforms=" + TestConstants.PLATFORM_LABEL
        )
        update("//test:top")
        useConfiguration(
            "--max_config_changes_to_show=-1", "--platforms=" + TestConstants.PIII_PLATFORM_LABEL
        )
        eventCollector.clear()
        update("//test:top")
        assertDoesNotContainEvent("--discard_analysis_cache")
        assertContainsEvent("Build option --platforms has changed, " + CACHE_DISCARD_WARNING)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageAfterSingleRelevantOptionChanges() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--max_config_changes_to_show=-1", "--definitely_relevant=old")
        update("//test:top")
        useConfiguration("--max_config_changes_to_show=-1", "--definitely_relevant=new")
        eventCollector.clear()
        update("//test:top")
        assertDoesNotContainEvent("--discard_analysis_cache")
        assertContainsEvent("Build option --definitely_relevant has changed, " + CACHE_DISCARD_WARNING)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageDoesNotIncludeIrrelevantOptions() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration(
            "--max_config_changes_to_show=-1",
            "--definitely_relevant=old",
            "--probably_irrelevant=old",
            "--also_irrelevant=old"
        )
        update("//test:top")
        useConfiguration(
            "--max_config_changes_to_show=-1",
            "--definitely_relevant=new",
            "--probably_irrelevant=new",
            "--also_irrelevant=new"
        )
        eventCollector.clear()
        update("//test:top")
        assertDoesNotContainEvent("--discard_analysis_cache")
        assertContainsEvent("Build option --definitely_relevant has changed, " + CACHE_DISCARD_WARNING)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageDoesNotIncludeUnchangedOptions() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration(
            "--max_config_changes_to_show=-1", "--definitely_relevant=old", "--also_relevant=fixed"
        )
        update("//test:top")
        useConfiguration(
            "--max_config_changes_to_show=-1", "--definitely_relevant=new", "--also_relevant=fixed"
        )
        eventCollector.clear()
        update("//test:top")
        assertDoesNotContainEvent("--discard_analysis_cache")
        assertContainsEvent("Build option --definitely_relevant has changed, " + CACHE_DISCARD_WARNING)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageAfterRelevantOptionChangeWithDiffDisabled() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--max_config_changes_to_show=0", "--definitely_relevant=old")
        update("//test:top")
        useConfiguration("--max_config_changes_to_show=0", "--definitely_relevant=new")
        eventCollector.clear()
        update("//test:top")
        assertDoesNotContainEvent("--discard_analysis_cache")
        assertContainsEvent("Build options have changed, " + CACHE_DISCARD_WARNING)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageAfterTwoRelevantOptionsChange() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration(
            "--max_config_changes_to_show=-1", "--definitely_relevant=old", "--also_relevant=old"
        )
        update("//test:top")
        useConfiguration(
            "--max_config_changes_to_show=-1", "--definitely_relevant=new", "--also_relevant=new"
        )
        eventCollector.clear()
        update("//test:top")
        assertDoesNotContainEvent("--discard_analysis_cache")
        assertContainsEvent(
            "Build options --also_relevant and --definitely_relevant have changed, "
                    + CACHE_DISCARD_WARNING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageAfterMultipleRelevantOptionsChange() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration(
            "--max_config_changes_to_show=-1",
            "--definitely_relevant=old",
            "--also_relevant=old",
            "--host_relevant=old"
        )
        update("//test:top")
        useConfiguration(
            "--max_config_changes_to_show=-1",
            "--definitely_relevant=new",
            "--also_relevant=new",
            "--host_relevant=new"
        )
        eventCollector.clear()
        update("//test:top")
        assertDoesNotContainEvent("--discard_analysis_cache")
        assertContainsEvent(
            "Build options --also_relevant, --definitely_relevant, and --host_relevant have changed, "
                    + CACHE_DISCARD_WARNING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageAfterMultipleRelevantOptionsChangeWithDiffLimit() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration(
            "--max_config_changes_to_show=2",
            "--definitely_relevant=old",
            "--also_relevant=old",
            "--host_relevant=old"
        )
        update("//test:top")
        useConfiguration(
            "--max_config_changes_to_show=2",
            "--definitely_relevant=new",
            "--also_relevant=new",
            "--host_relevant=new"
        )
        eventCollector.clear()
        update("//test:top")
        assertDoesNotContainEvent("--discard_analysis_cache")
        assertContainsEvent(
            "Build options --also_relevant, --definitely_relevant, and 1 more have changed, "
                    + CACHE_DISCARD_WARNING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageAfterMultipleRelevantOptionsChangeWithSingleDiffLimit() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration(
            "--max_config_changes_to_show=1",
            "--definitely_relevant=old",
            "--also_relevant=old",
            "--host_relevant=old"
        )
        update("//test:top")
        useConfiguration(
            "--max_config_changes_to_show=1",
            "--definitely_relevant=new",
            "--also_relevant=new",
            "--host_relevant=new"
        )
        eventCollector.clear()
        update("//test:top")
        assertDoesNotContainEvent("--discard_analysis_cache")
        assertContainsEvent(
            "Build options --also_relevant and 2 more have changed, " + CACHE_DISCARD_WARNING
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheClearMessageAfterDiscardAnalysisCacheBuildWithRelevantOptionChanges() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration(
            "--max_config_changes_to_show=-1", "--discard_analysis_cache", "--definitely_relevant=old"
        )
        update("//test:top")
        useConfiguration(
            "--max_config_changes_to_show=-1", "--discard_analysis_cache", "--definitely_relevant=new"
        )
        eventCollector.clear()
        update("//test:top")
        assertContainsEvent("--discard_analysis_cache")
        assertDoesNotContainEvent("Build option")
        assertContainsEvent("discarding analysis cache")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun throwsIfAnalysisCacheIsDiscardedWhenOptionSet_nativeOption() {
        setupDiffResetTesting()
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(
            name = "top",
            host_deps = [":exec"],
        )

        normal_lib(name = "exec")
        
        """.trimIndent()
        )
        useConfiguration("--definitely_relevant=old")

        // Set up the analysis cache
        update("//test:top")

        // Check if things work if the build options are not changed
        useConfiguration("--noallow_analysis_cache_discard", "--definitely_relevant=old")
        update("//test:top")
        val topTargetBefore: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor
                .getEvaluator()
                .getExistingValue(
                    ConfiguredTargetKey.builder()
                        .setLabel(Label.parseCanonicalUnchecked("//test:top"))
                        .setConfiguration(getTargetConfiguration())
                        .build()
                )
        assertThat(topTargetBefore).isNotNull()

        // Check if an error is raised when the build options are changed. Do it twice because
        // had already had a bug that the second invocation erroneously worked. See
        // https://github.com/bazelbuild/bazel/issues/23491 .
        useConfiguration("--noallow_analysis_cache_discard", "--definitely_relevant=new")
        var t: Throwable = org.junit.Assert.assertThrows<T>(
            InvalidConfigurationException::class.java,
            org.junit.function.ThrowingRunnable { update("//test:top") })
        Truth.assertThat(t.getMessage().contains("analysis cache would have been discarded")).isTrue()
        val topTargetAfter: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor
                .getEvaluator()
                .getExistingValue(
                    ConfiguredTargetKey.builder()
                        .setLabel(Label.parseCanonicalUnchecked("//test:top"))
                        .setConfiguration(getTargetConfiguration())
                        .build()
                )
        assertThat(topTargetAfter).isSameInstanceAs(topTargetBefore)

        t = org.junit.Assert.assertThrows<T>(
            InvalidConfigurationException::class.java,
            org.junit.function.ThrowingRunnable { update("//test:top") })
        Truth.assertThat(t.getMessage()).contains("analysis cache would have been discarded")

        // Check if going back to the original configuration works.
        useConfiguration("--noallow_analysis_cache_discard", "--definitely_relevant=old")
        update("//test:top")

        // Now check if removing --noallow_analysis_cache_discard in fact allows discarding the cache.
        useConfiguration("--definitely_relevant=new")
        update("//test:top")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun throwsIfAnalysisCacheIsDiscardedWhenOptionSet_starlarkFlag() {
        setupDiffResetTesting()
        scratch.file(
            "test_flags/build_setting.bzl",
            """
        bool_flag = rule(
            implementation = lambda ctx: [],
            build_setting = config.bool(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test_flags/BUILD",
            """
        load(":build_setting.bzl", "bool_flag")

        bool_flag(
            name = "my_flag",
            build_setting_default = False,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "normal_lib")

        normal_lib(name = "top")
        
        """.trimIndent()
        )
        useConfiguration("--no//test_flags:my_flag")

        // Set up the analysis cache
        update("//test:top")

        // Check if things work if the build options are not changed
        useConfiguration("--noallow_analysis_cache_discard", "--no//test_flags:my_flag")
        update("//test:top")

        // Check if an error is raised when the build options are changed. Do it twice because
        // had already had a bug that the second invocation erroneously worked. See
        // https://github.com/bazelbuild/bazel/issues/23491 .
        useConfiguration("--noallow_analysis_cache_discard", "--//test_flags:my_flag")
        var t: Throwable = org.junit.Assert.assertThrows<T>(
            InvalidConfigurationException::class.java,
            org.junit.function.ThrowingRunnable { update("//test:top") })
        Truth.assertThat(t.getMessage()).contains("analysis cache would have been discarded")

        t = org.junit.Assert.assertThrows<T>(
            InvalidConfigurationException::class.java,
            org.junit.function.ThrowingRunnable { update("//test:top") })
        Truth.assertThat(t).hasMessageThat().contains("analysis cache would have been discarded")

        // Check if going back to the original configuration works.
        useConfiguration("--noallow_analysis_cache_discard", "--no//test_flags:my_flag")
        update("//test:top")

        // Now check if removing --noallow_analysis_cache_discard in fact allows discarding the cache.
        useConfiguration("--//test_flags:my_flag")
        update("//test:top")
    }

    companion object {
        private val CACHE_DISCARD_WARNING = ("discarding analysis cache (this can be expensive, see"
                + " https://bazel.build/advanced/performance/iteration-speed).")
    }
}
