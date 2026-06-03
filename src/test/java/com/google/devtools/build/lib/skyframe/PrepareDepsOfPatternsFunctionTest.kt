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

import com.google.devtools.build.lib.bazel.bzlmod.BazelLockFileFunction

/** Tests for [com.google.devtools.build.lib.skyframe.PrepareDepsOfPatternsFunction].  */
@RunWith(JUnit4::class)
class PrepareDepsOfPatternsFunctionTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionLoadsTargetAndNotUnspecifiedTargets() {
        // Given a package "//foo" with independent target rules ":foo" and ":foo2",
        createFooAndFoo2( /* dependent= */false)

        // Given a target pattern sequence consisting of a single-target pattern for "//foo",
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//foo")

        // When PrepareDepsOfPatternsFunction successfully completes evaluation,
        val walkableGraph: WalkableGraph = getGraphFromPatternsEvaluation(patternSequence)

        // Then the graph contains a value for the target "@//foo:foo",
        assertValidValue(walkableGraph, getKeyForLabel(Label.create("@//foo", "foo")))

        // And the graph does not contain a value for the target "@//foo:foo2".
        Truth.assertThat(WalkableGraphUtils.exists(getKeyForLabel(Label.create("@//foo", "foo2")), walkableGraph))
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionLoadsTargetDependencies() {
        // Given a package "//foo" with target rules ":foo" and ":foo2",
        // And given ":foo" depends on ":foo2",
        createFooAndFoo2( /* dependent= */true)

        // Given a target pattern sequence consisting of a single-target pattern for "//foo",
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//foo")

        // When PrepareDepsOfPatternsFunction successfully completes evaluation,
        val walkableGraph: WalkableGraph = getGraphFromPatternsEvaluation(patternSequence)

        // Then the graph contains an entry for ":foo"'s dependency, ":foo2".
        assertValidValue(walkableGraph, getKeyForLabel(Label.create("@//foo", "foo2")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionExpandsTargetPatterns() {
        // Given a package "@//foo" with independent target rules ":foo" and ":foo2",
        createFooAndFoo2( /* dependent= */false)

        // Given a target pattern sequence consisting of a pattern for "//foo:*",
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//foo:*")

        // When PrepareDepsOfPatternsFunction successfully completes evaluation,
        val walkableGraph: WalkableGraph = getGraphFromPatternsEvaluation(patternSequence)

        // Then the graph contains an entry for ":foo" and ":foo2".
        assertValidValue(walkableGraph, getKeyForLabel(Label.create("@//foo", "foo")))
        assertValidValue(walkableGraph, getKeyForLabel(Label.create("@//foo", "foo2")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetParsingException() {
        // Given no packages, and a target pattern sequence referring to a non-existent target,
        val nonexistentTarget = "//foo:foo"
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(nonexistentTarget)

        // When PrepareDepsOfPatternsFunction completes evaluation,
        val walkableGraph: WalkableGraph = getGraphFromPatternsEvaluation(patternSequence)

        // Then the graph does not contain an entry for ":foo",
        Truth.assertThat(WalkableGraphUtils.exists(getKeyForLabel(Label.create("@//foo", "foo")), walkableGraph))
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependencyTraversalNoSuchPackageException() {
        // Given a package "//foo" with a target ":foo" that has a dependency on a non-existent target
        // "//bar:bar" in a non-existent package "//bar",
        createFooWithDependencyOnMissingBarPackage()

        // Given a target pattern sequence consisting of a single-target pattern for "//foo",
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//foo")

        // When PrepareDepsOfPatternsFunction completes evaluation,
        val walkableGraph: WalkableGraph = getGraphFromPatternsEvaluation(patternSequence)

        // Then the graph contains an entry for ":foo",
        assertValidValue(
            walkableGraph,
            getKeyForLabel(Label.create("@//foo", "foo")),  /* expectTransitiveException= */
            true
        )

        // And an entry with a NoSuchPackageException for "//bar:bar",
        val e: java.lang.Exception = assertException(walkableGraph, getKeyForLabel(Label.create("@//bar", "bar")))
        Truth.assertThat(e).isInstanceOf(NoSuchPackageException::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependencyTraversalNoSuchTargetException() {
        // Given a package "//foo" with a target ":foo" that has a dependency on a non-existent target
        // "//bar:bar" in an existing package "//bar",
        createFooWithDependencyOnBarPackageWithMissingTarget()

        // Given a target pattern sequence consisting of a single-target pattern for "//foo",
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//foo")

        // When PrepareDepsOfPatternsFunction completes evaluation,
        val walkableGraph: WalkableGraph = getGraphFromPatternsEvaluation(patternSequence)

        // Then the graph contains an entry for ":foo" which has both a value and an exception,
        assertValidValue(
            walkableGraph,
            getKeyForLabel(Label.create("@//foo", "foo")),  /* expectTransitiveException= */
            true
        )

        // And an entry with a NoSuchTargetException for "//bar:bar",
        val e: java.lang.Exception = assertException(walkableGraph, getKeyForLabel(Label.create("@//bar", "bar")))
        Truth.assertThat(e).isInstanceOf(NoSuchTargetException::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsingProblemsKeepGoing() {
        parsingProblem( /* keepGoing= */true)
    }

    /**
     * PrepareDepsOfPatternsFunction always keeps going despite any target pattern parsing errors, in
     * keeping with the original behavior of [WalkableGraph.WalkableGraphFactory.prepareAndGet],
     * which always used `keepGoing=true` during target pattern parsing because it was
     * responsible for ensuring that queries had a complete graph to work on.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsingProblemsNoKeepGoing() {
        parsingProblem( /* keepGoing= */false)
    }

    @Throws(java.lang.Exception::class)
    private fun parsingProblem(keepGoing: Boolean) {
        // Given a package "//foo" with target rule ":foo",
        createFooAndFoo2( /* dependent= */false)

        // Given a target pattern sequence consisting of a pattern with parsing problems followed by
        // a legit target pattern,
        val bogusPattern = "//foo/...."
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(bogusPattern, "//foo:foo")

        // When PrepareDepsOfPatternsFunction runs in the selected keep-going mode,
        val walkableGraph: WalkableGraph =
            getGraphFromPatternsEvaluation(patternSequence,  /* keepGoing= */keepGoing)

        // Then it skips evaluation of the malformed target pattern, but logs about it,
        assertContainsEvent("Skipping '" + bogusPattern + "': ")

        // And then the graph contains a value for the legit target pattern's target "@//foo:foo".
        Truth.assertThat(WalkableGraphUtils.exists(getKeyForLabel(Label.create("@//foo", "foo")), walkableGraph))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFunctionLoadsTargetFromExternalRepo() {
        writeBzlmodFiles()

        // Given a target pattern sequence consisting of a single-target pattern for "//rinne",
        val patternSequence: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("//rinne")

        // When PrepareDepsOfPatternsFunction successfully completes evaluation,
        val walkableGraph: WalkableGraph = getGraphFromPatternsEvaluation(patternSequence)

        // Then the graph contains a value for the target "@//rinne:rinne" and the dep
        // "@@repo+//a:x",
        assertValidValue(walkableGraph, getKeyForLabel(Label.create("//rinne", "rinne")))
        assertValidValue(walkableGraph, getKeyForLabel(Label.create("@repo+//a", "x")))
    }

    // Regression test for b/225877591 ("Unexpected missing value in PrepareDepsOfPatternsFunction
    // when there's both a dep with a cached cycle and another dep with an error").
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepOnCachedPDOPNodeThatItselfDependsOnBothCycleAndError() {
        scratch.file(
            "foo/BUILD",
            """
        load("//test_defs:foo_library.bzl", "foo_library")
        foo_library(
            name = "t1",
            deps = ["//foo:t2"],
        )

        foo_library(
            name = "t2",
            deps = [
                "//foo:t1",
                "//nope",
            ],
        )
        
        """.trimIndent()
        )
        val unusedJustWantSideEffectOfPrimingGraph: EvaluationResult<PrepareDepsOfPatternsValue?> =
            evaluate(com.google.common.collect.ImmutableList.of<String?>("//foo/..."),  /* keepGoing= */true)
        // The main property we're trying to test is that we don't crash (due to
        // PrepareDepsOfPatternsFunction's usage of BugReport.sendNonFatalBugReport). We get that by
        // virtue of control flow getting past this statement.
        val evaluationResult: EvaluationResult<PrepareDepsOfPatternsValue?> =
            evaluate(
                com.google.common.collect.ImmutableList.of<String?>(
                    "//foo/...",
                    "//doesnt:matter"
                ),  /* keepGoing= */true
            )
        val pdopsKey: SkyKey? =
            PrepareDepsOfPatternsValue.key(
                com.google.common.collect.ImmutableList.of<E?>("//foo/...", "//doesnt:matter"),
                PathFragment.EMPTY_FRAGMENT
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(evaluationResult)
            .hasErrorEntryForKeyThat(pdopsKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("no such package 'nope'")
        assertThat(evaluationResult.getError(pdopsKey).getCycleInfo().get(0).cycle)
            .containsExactly(Label.parseCanonical("//foo:t1"), Label.parseCanonical("//foo:t2"))
            .inOrder()
    }

    override fun extraPrecomputedValues(): com.google.common.collect.ImmutableList<Injected?>? {
        try {
            moduleRoot = scratch.dir("modules")
        } catch (e: IOException) {
            throw java.lang.IllegalStateException(e)
        }
        registry = FakeRegistry.DEFAULT_FACTORY.newFakeRegistry(moduleRoot.getPathString())
        return com.google.common.collect.ImmutableList.of<E?>(
            PrecomputedValue.injected(
                ModuleFileFunction.REGISTRIES, com.google.common.collect.ImmutableSet.of<E?>(registry.getUrl())
            ),
            PrecomputedValue.injected(
                RegistryFunction.MODULE_MIRRORS,
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            ),
            PrecomputedValue.injected(ModuleFileFunction.IGNORE_DEV_DEPS, false),
            PrecomputedValue.injected(
                ModuleFileFunction.INJECTED_REPOSITORIES,
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            ),
            PrecomputedValue.injected(
                BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES, CheckDirectDepsMode.WARNING
            ),
            PrecomputedValue.injected(
                YankedVersionsUtil.ALLOWED_YANKED_VERSIONS,
                com.google.common.collect.ImmutableList.of<E?>()
            ),
            PrecomputedValue.injected(
                BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE, BazelCompatibilityMode.ERROR
            ),
            PrecomputedValue.injected(BazelLockFileFunction.LOCKFILE_MODE, LockfileMode.UPDATE)
        )
    }

    // Helpers:
    @Throws(java.lang.InterruptedException::class)
    private fun evaluate(
        patternSequence: com.google.common.collect.ImmutableList<String?>?, keepGoing: Boolean
    ): EvaluationResult<PrepareDepsOfPatternsValue?> {
        val independentTarget: SkyKey =
            PrepareDepsOfPatternsValue.key(patternSequence, PathFragment.EMPTY_FRAGMENT)
        val singletonTargetPattern: com.google.common.collect.ImmutableList<SkyKey?> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(independentTarget)

        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(keepGoing)
                .setParallelism(BuildViewTestCase.Companion.LOADING_PHASE_THREADS)
                .setEventHandler(
                    com.google.devtools.build.lib.events.Reporter(
                        EventBusEventHandler.createWithNewEventBus(),
                        eventCollector
                    )
                )
                .build()
        return getSkyframeExecutor().getEvaluator().evaluate(singletonTargetPattern, evaluationContext)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getGraphFromPatternsEvaluation(patternSequence: com.google.common.collect.ImmutableList<String?>?): WalkableGraph {
        return getGraphFromPatternsEvaluation(patternSequence,  /* keepGoing= */true)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getGraphFromPatternsEvaluation(
        patternSequence: com.google.common.collect.ImmutableList<String?>?, keepGoing: Boolean
    ): WalkableGraph {
        val evaluationResult: EvaluationResult<PrepareDepsOfPatternsValue?> =
            evaluate(patternSequence, keepGoing)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(evaluationResult).hasNoError()
        return com.google.common.base.Preconditions.checkNotNull<T>(evaluationResult.getWalkableGraph())
    }

    @Throws(IOException::class)
    private fun createFooAndFoo2(dependent: Boolean) {
        val dependencyIfAny = if (dependent) "srcs = [':foo2']," else ""
        scratch.file(
            "foo/BUILD",
            "genrule(name = 'foo',",
            dependencyIfAny,
            "    outs = ['out.txt'],",
            "    cmd = 'touch $@')",
            "genrule(name = 'foo2',",
            "    outs = ['out2.txt'],",
            "    cmd = 'touch $@')"
        )
    }

    @Throws(IOException::class)
    private fun createFooWithDependencyOnMissingBarPackage() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["//bar"],
            outs = ["out.txt"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
    }

    @Throws(IOException::class)
    private fun createFooWithDependencyOnBarPackageWithMissingTarget() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["//bar"],
            outs = ["out.txt"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        scratch.file("bar/BUILD")
    }

    @Throws(java.lang.Exception::class)
    private fun writeBzlmodFiles() {
        scratch.overwriteFile(
            "MODULE.bazel", "bazel_dep(name= \"repo\", version=\"1.0\", repo_name=\"my_repo\")"
        )
        scratch.overwriteFile(
            "rinne/BUILD",
            """
        genrule(
            name = "rinne",
            srcs = ["@my_repo//a:x"],
            outs = ["out.txt"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        registry.addModule(
            ModuleKey("repo", Version.parse("1.0")), "module(name = \"repo\", version = \"1.0\")"
        )
        scratch.file(moduleRoot.getRelative("repo+1.0/REPO.bazel").getPathString(), "")
        scratch.file(
            moduleRoot.getRelative("repo+1.0/a/BUILD").getPathString(), "exports_files(['x'])"
        )
        invalidatePackages()
    }

    companion object {
        private fun getKeyForLabel(label: Label?): SkyKey {
            // Note that these tests used to look for TargetMarker SkyKeys before TargetMarker was
            // inlined in TransitiveTraversalFunction. Because TargetMarker is now inlined, it doesn't
            // appear in the graph. Instead, these tests now look for TransitiveTraversal keys.
            return TransitiveTraversalValue.key(label)
        }

        @Throws(java.lang.InterruptedException::class)
        private fun assertValidValue(graph: WalkableGraph, key: SkyKey?) {
            assertValidValue(graph, key,  /* expectTransitiveException= */false)
        }

        /**
         * A node in the walkable graph may have both a value and an exception. This happens when one of a
         * node's transitive dependencies throws an exception, but its parent recovers from it.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun assertValidValue(
            graph: WalkableGraph, key: SkyKey?, expectTransitiveException: Boolean
        ) {
            assertThat(graph.getValue(key)).isNotNull()
            if (expectTransitiveException) {
                assertThat(graph.getException(key)).isNotNull()
            } else {
                assertThat(graph.getException(key)).isNull()
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun assertException(graph: WalkableGraph, key: SkyKey?): java.lang.Exception {
            assertThat(graph.getValue(key)).isNull()
            val exception: java.lang.Exception = graph.getException(key)
            Truth.assertThat(exception).isNotNull()
            return exception
        }
    }
}
