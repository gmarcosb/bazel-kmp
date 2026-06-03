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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [PrepareDepsOfTargetsUnderDirectoryFunction].  */
@RunWith(JUnit4::class)
class PrepareDepsOfPatternFunctionTest : BuildViewTestCase() {
    @Throws(java.lang.InterruptedException::class)
    private fun getEvaluationResult(key: SkyKey): EvaluationResult<PrepareDepsOfPatternValue?> {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SequencedSkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(reporter)
                .build()
        val evaluationResult: EvaluationResult<PrepareDepsOfPatternValue?> =
            skyframeExecutor.getEvaluator()
                .evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)
        com.google.common.base.Preconditions.checkState(!evaluationResult.hasError())
        return evaluationResult
    }

    @org.junit.Test
    fun testUnparsablePattern() {
        // Given an string that can't be parsed,
        val unparsablePattern = "Not a//parsable/.../pattern/..//"
        val unparsablePatternList: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(unparsablePattern)

        // When PrepareDepsOfPatternValue.keys is called with that string as an argument,
        val keysAndExceptionsResult: PrepareDepsOfPatternSkyKeysAndExceptions =
            createPrepDepsKeysMaybe(unparsablePatternList)

        // Then it returns a wrapped TargetParsingException.
        assertThat(keysAndExceptionsResult.values).isEmpty()
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<Any?>(keysAndExceptionsResult.exceptions).originalPattern
        )
            .isEqualTo(unparsablePattern)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleTargetPatternEvaluationAndTransitiveLoading() {
        evaluatePatternAndCheckTransitiveLoading("//a",  /*adExists=*/false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetsBelowDirectoryPatternEvaluationAndTransitiveLoading() {
        evaluatePatternAndCheckTransitiveLoading("//a/...",  /*adExists=*/true)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, LabelSyntaxException::class)
    private fun evaluatePatternAndCheckTransitiveLoading(pattern: String, adExists: Boolean) {
        // Given a package "a" with a genrule "a" that depends on a target "b.txt" in a created
        // package "b", and a package "c" with a genrule "c", and a package "a/d" with a genrule "d".
        createPackages()

        // When PrepareDepsOfPatternFunction is evaluated for the provided pattern,
        val key: SkyKey = createPrepDepsKey(pattern)
        val evaluationResult: EvaluationResult<PrepareDepsOfPatternValue?> = getEvaluationResult(key)
        val graph: WalkableGraph =
            com.google.common.base.Preconditions.checkNotNull<T>(evaluationResult.getWalkableGraph())

        // Then the result is not null,
        com.google.common.base.Preconditions.checkNotNull<T?>(evaluationResult.get(key))

        // And the TransitiveTraversalValue for "a:a" is evaluated,
        val aaKey: SkyKey = TransitiveTraversalValue.key(Label.parseCanonical("@//a:a"))
        Truth.assertThat(WalkableGraphUtils.exists(aaKey, graph)).isTrue()

        // And that TransitiveTraversalValue depends on "b:b.txt".
        val depsOfAa: Iterable<SkyKey?>? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                graph.getDirectDeps(
                    com.google.common.collect.ImmutableList.of<E?>(
                        aaKey
                    )
                ).values()
            )
        val bTxtKey: SkyKey? = TransitiveTraversalValue.key(Label.parseCanonical("@//b:b.txt"))
        Truth.assertThat(depsOfAa).contains(bTxtKey)

        // And the TransitiveTraversalValue for "b:b.txt" is evaluated.
        Truth.assertThat(WalkableGraphUtils.exists(bTxtKey, graph)).isTrue()

        // And the TransitiveTraversalValue for "c:c" is NOT evaluated.
        val ccKey: SkyKey? = TransitiveTraversalValue.key(Label.parseCanonical("@//c:c"))
        Truth.assertThat(WalkableGraphUtils.exists(ccKey, graph)).isFalse()

        // And the TransitiveTraversalValue for "a/d:d" is or is not evaluated depending on the provided
        // expectation.
        val adKey: SkyKey? = TransitiveTraversalValue.key(Label.parseCanonical("@//a/d:d"))
        Truth.assertThat(WalkableGraphUtils.exists(adKey, graph)).isEqualTo(adExists)
    }

    /**
     * Creates a package "a" with a genrule "a" that depends on a target "b.txt" in a created package
     * "b", and a package "c" with a genrule "c", and a package "a/d" with a genrule "d".
     */
    @Throws(IOException::class)
    private fun createPackages() {
        scratch.file("a/BUILD", "genrule(name='a', cmd='', srcs=['//b:b.txt'], outs=['a.out'])")
        scratch.file("b/BUILD", "exports_files(['b.txt'])")
        scratch.file("c/BUILD", "genrule(name='c', cmd='', srcs=['c.txt'], outs=['c.out'])")
        scratch.file("a/d/BUILD", "genrule(name='d', cmd='', srcs=['d.txt'], outs=['d.out'])")
    }

    companion object {
        private fun createPrepDepsKeysMaybe(
            patterns: com.google.common.collect.ImmutableList<String?>?
        ): PrepareDepsOfPatternSkyKeysAndExceptions {
            return PrepareDepsOfPatternValue.keys(patterns, TargetPattern.defaultParser())
        }

        private fun createPrepDepsKey(pattern: String): SkyKey {
            val keysAndExceptions: PrepareDepsOfPatternSkyKeysAndExceptions =
                PrepareDepsOfPatternValue.keys(
                    com.google.common.collect.ImmutableList.of<E?>(pattern),
                    TargetPattern.defaultParser()
                )
            assertThat(keysAndExceptions.exceptions).isEmpty()
            return com.google.common.collect.Iterables.getOnlyElement<Any?>(keysAndExceptions.values).getSkyKey()
        }
    }
}
