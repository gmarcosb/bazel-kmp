// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.createNestedMatchResult

@RunWith(TestParameterInjector::class)
class VersionedChangesValidatorTest {
    private val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>())
    private val validator: VersionedChangesValidator = VersionedChangesValidator(ForkJoinPool(THREAD_COUNT), changes)

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun matchesFileOpDependency_noMatch() {
        changes.registerFileChange("abc/def", 100)
        Truth.assertThat(getMatchResult(FileDependencies.builder("abc/def").build(), 100))
            .isEqualTo(NO_MATCH_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun matchesFileOpDependency_match() {
        changes.registerFileChange("abc/def", 100)
        Truth.assertThat(getMatchResult(FileDependencies.builder("abc/def").build(), 99))
            .isEqualTo(FileOpMatch(100))
    }

    @org.junit.Test
    @TestParameters("{validityHorizon: 97, expectedAnalysisMatch: 99, expectedSourceMatch: 98}")
    @TestParameters("{validityHorizon: 98, expectedAnalysisMatch: 99, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 99, expectedAnalysisMatch: 100, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 100, expectedAnalysisMatch: 101, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 101, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 2147483647}")
    fun matchingNested_withDependencies_aggregatesDependencies(
        validityHorizon: Int, expectedAnalysisMatch: Int, expectedSourceMatch: Int
    ) {
        changes.registerFileChange("dep/a", 99)
        changes.registerFileChange("dep/b", 100)
        changes.registerFileChange("dep/c", 101)
        changes.registerFileChange("src/a", 98)

        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(
                    FileDependencies.builder("dep/a").build(),
                    FileDependencies.builder("dep/b").build(),
                    FileDependencies.builder("dep/c").build()
                ),
                com.google.common.collect.ImmutableList.of<E?>(FileDependencies.builder("src/a").build())
            )

        val expectedResult: NestedMatchResult? =
            createNestedMatchResult(expectedAnalysisMatch, expectedSourceMatch)
        Truth.assertThat(getMatchResult(key, validityHorizon)).isEqualTo(expectedResult)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentValidityHorizons_sameFileDependencies() {
        // This test case models Scenario 1 in the class comment of VersionedChangesValidator. It's not
        // mechanically interesting. The interesting constraints are properties of the MTSV and VH.
        changes.registerFileChange("shared", 100)
        changes.registerFileChange("dep/a", 101)
        changes.registerFileChange("dep/b", 102)

        val keyA: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(
                    FileDependencies.builder("shared").build(),
                    FileDependencies.builder("dep/a").build()
                ),
                com.google.common.collect.ImmutableList.of<E?>()
            )

        val keyB: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(
                    FileDependencies.builder("shared").build(),
                    FileDependencies.builder("dep/b").build()
                ),
                com.google.common.collect.ImmutableList.of<E?>()
            )

        // "A" has dependencies 'shared' and 'dep/a'. It was marked valid at VH 105 and has MTSV 101.
        // There are no invalidating changes. This marks 'shared' as NO_MATCH_RESULT.
        //
        // At the MTSV of 101, "shared" was at version 100. Since the VH is 105, it means there can't be
        // any changes in "shared" on the interval [101, 105].
        Truth.assertThat(getMatchResult(keyA, 105)).isEqualTo(NO_MATCH_RESULT)

        // "B" has dependencies 'shared' and 'dep/b'. It was marked clean at VH 110 and has MTSV 102.
        // There are no invalidating changes. It uses cached NO_MATCH_RESULT from "A"'s traversal.
        //
        // At the MTSV of 102, "shared" was at version 100. Since the VH is 110, it means there can't be
        // any changes in "shared" on the interval [101, 110].
        Truth.assertThat(getMatchResult(keyB, 110)).isEqualTo(NO_MATCH_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun staleCachedValue_ignoredForSameKeyButDifferentValidityHorizon() {
        // This test case models Scenario 2 in the class comment of VersionedChangesValidator.
        changes.registerFileChange("dep", 101)

        // Looks up 'dep' at version 100 and observes the invalidation at 101.
        val key1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileDependencies.builder("dep").build()
        Truth.assertThat(getMatchResult(key1, 100)).isEqualTo(FileOpMatch(101))

        // Looks up 'dep' at version 102 and does not observe the invalidation.
        //
        // Even though these keys are identical, the trick here is that FileDependencies is based on
        // reference equality. The references in the FileDependencyDeserializer will be different if the
        // (canonical) MTSVs are different.
        val key2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileDependencies.builder("dep").build()
        Truth.assertThat(getMatchResult(key2, 102)).isEqualTo(NO_MATCH_RESULT)
    }

    private fun getMatchResult(key: FileOpDependency?, validityHorizon: Int): FileOpMatchResult? {
        try {
            when (validator.matches(key, validityHorizon)) {
                -> return result
                -> return future.get()
            }
        } catch (e: java.lang.Exception) {
            if (e is java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
            }
            throw java.lang.AssertionError(e)
        }
    }

    private fun getMatchResult(key: NestedDependencies?, validityHorizon: Int): NestedMatchResult? {
        try {
            when (validator.matches(key, validityHorizon)) {
                -> return result
                -> return future.get()
            }
        } catch (e: java.lang.Exception) {
            if (e is java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
            }
            throw java.lang.AssertionError(e)
        }
    }

    companion object {
        private const val THREAD_COUNT = 10
    }
}
