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

import com.google.devtools.build.lib.skyframe.serialization.analysis.AlwaysMatch.ALWAYS_MATCH_RESULT

@RunWith(TestParameterInjector::class)
class NestedMatchMemoizingLookupTest {
    private val executor: java.util.concurrent.Executor = ForkJoinPool(THREAD_COUNT)
    private val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>())
    private val fileOpMatches: FileOpMatchMemoizingLookup =
        FileOpMatchMemoizingLookup(executor, changes, ConcurrentHashMap<K?, V?>())
    private val lookup: NestedMatchMemoizingLookup =
        NestedMatchMemoizingLookup(executor, fileOpMatches, ConcurrentHashMap<K?, V?>())

    @org.junit.Test
    fun matchingNested_inRangeValidityHorizon_matches() {
        changes.registerFileChange("abc/def", 100)

        val key: NestedDependencies = createNestedDependencies(FileDependencies.builder("abc/def").build())
        assertThat(getLookupResult(key, 99)).isEqualTo(AnalysisMatch(100))
    }

    @org.junit.Test
    fun matchingNested_outOfRangeValidityHorizon_doesNotMatch() {
        changes.registerFileChange("abc/def", 100)

        val key: NestedDependencies = createNestedDependencies(FileDependencies.builder("abc/def").build())
        assertThat(getLookupResult(key, 100)).isEqualTo(NO_MATCH_RESULT)
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
        Truth.assertThat(getLookupResult(key, validityHorizon)).isEqualTo(expectedResult)
    }

    @org.junit.Test
    @TestParameters("{validityHorizon: 98, expectedAnalysisMatch: 99, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 99, expectedAnalysisMatch: 100, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 100, expectedAnalysisMatch: 101, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 101, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 102}")
    @TestParameters("{validityHorizon: 102, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 2147483647}")
    @Throws(java.lang.Exception::class)
    fun matchingNested_withAsyncDependencies_aggregatesDependencies(
        validityHorizon: Int, expectedAnalysisMatch: Int, expectedSourceMatch: Int
    ) {
        // This test covers the futures handling code path in AggregatingFutureFileOpMatchResult.
        changes.registerFileChange("dep/a", 99)
        changes.registerFileChange("dep/b", 100)
        changes.registerFileChange("dep/c", 101)
        changes.registerFileChange("src/a", 102)

        val depA: ControllableFileDependencies = ControllableFileDependencies(
            com.google.common.collect.ImmutableList.of<String?>("dep/a"),
            com.google.common.collect.ImmutableList.of<AvailableFileDependencies?>()
        )
        val depB: ControllableFileDependencies = ControllableFileDependencies(
            com.google.common.collect.ImmutableList.of<String?>("dep/b"),
            com.google.common.collect.ImmutableList.of<AvailableFileDependencies?>()
        )
        val depC: ControllableFileDependencies = ControllableFileDependencies(
            com.google.common.collect.ImmutableList.of<String?>("dep/c"),
            com.google.common.collect.ImmutableList.of<AvailableFileDependencies?>()
        )
        val srcA: ControllableFileDependencies = ControllableFileDependencies(
            com.google.common.collect.ImmutableList.of<String?>("src/a"),
            com.google.common.collect.ImmutableList.of<AvailableFileDependencies?>()
        )
        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(depA, depB, depC),
                com.google.common.collect.ImmutableList.of<E?>(srcA)
            )

        val pool: ForkJoinPool = ForkJoinPool(4) // one for each dependency and source
        pool.execute(
            java.lang.Runnable {
                val unused: Any? = getLookupResult(depA, validityHorizon)
            })
        pool.execute(
            java.lang.Runnable {
                val unused: Any? = getLookupResult(depB, validityHorizon)
            })
        pool.execute(
            java.lang.Runnable {
                val unused: Any? = getLookupResult(depC, validityHorizon)
            })
        pool.execute(
            java.lang.Runnable {
                val unused: Any? = getLookupResult(srcA, validityHorizon)
            })

        // Waits for all the dependency threads to take ownership of their entries.
        depA.awaitEarliestMatchEntered()
        depB.awaitEarliestMatchEntered()
        depC.awaitEarliestMatchEntered()
        srcA.awaitEarliestMatchEntered()

        val lookupResult: FutureNestedMatchResult =
            lookup.getValueOrFuture(key, validityHorizon) as FutureNestedMatchResult
        assertThat(lookupResult.isDone()).isFalse()

        // The lookupResult cannot complete until all the dependencies complete. Releases the
        // dependencies.
        depA.enable()
        depB.enable()
        depC.enable()
        srcA.enable()

        val expectedResult: NestedMatchResult? =
            createNestedMatchResult(expectedAnalysisMatch, expectedSourceMatch)
        assertThat(lookupResult.get()).isEqualTo(expectedResult)
    }

    @org.junit.Test
    @TestParameters("{validityHorizon: 98, expectedAnalysisMatch: 99, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 99, expectedAnalysisMatch: 100, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 100, expectedAnalysisMatch: 101, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 101, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 102}")
    fun matchingNested_withNestedDependencies_aggregatesDependencies(
        validityHorizon: Int, expectedAnalysisMatch: Int, expectedSourceMatch: Int
    ) {
        changes.registerFileChange("dep/a", 99)
        changes.registerFileChange("dep/b", 100)
        changes.registerFileChange("dep/c", 101)
        changes.registerFileChange("src/a", 102)

        val nestedDep: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(
                    FileDependencies.builder("dep/b").build(),
                    FileDependencies.builder("dep/c").build()
                ),
                com.google.common.collect.ImmutableList.of<E?>(FileDependencies.builder("src/a").build())
            )

        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(FileDependencies.builder("dep/a").build(), nestedDep),
                com.google.common.collect.ImmutableList.of<E?>()
            )

        val expectedResult: NestedMatchResult? =
            createNestedMatchResult(expectedAnalysisMatch, expectedSourceMatch)
        Truth.assertThat(getLookupResult(key, validityHorizon)).isEqualTo(expectedResult)
    }

    @org.junit.Test
    @TestParameters("{validityHorizon: 98, expectedAnalysisMatch: 99, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 99, expectedAnalysisMatch: 100, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 100, expectedAnalysisMatch: 101, expectedSourceMatch: 2147483647}")
    @TestParameters("{validityHorizon: 101, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 102}")
    @TestParameters("{validityHorizon: 102, expectedAnalysisMatch: 2147483647, expectedSourceMatch: 2147483647}")
    @Throws(java.lang.Exception::class)
    fun matchingNested_withAsyncNestedDependencies_aggregatesDependencies(
        validityHorizon: Int, expectedAnalysisMatch: Int, expectedSourceMatch: Int
    ) {
        changes.registerFileChange("dep/a", 99)
        changes.registerFileChange("dep/b", 100)
        changes.registerFileChange("dep/c", 101)
        changes.registerFileChange("src/a", 102)

        val nestedDep: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(
                    FileDependencies.builder("dep/b").build(),
                    FileDependencies.builder("dep/c").build()
                ),
                com.google.common.collect.ImmutableList.of<E?>(FileDependencies.builder("src/a").build())
            )

        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(FileDependencies.builder("dep/a").build(), nestedDep),
                com.google.common.collect.ImmutableList.of<E?>()
            )

        val expectedResult: NestedMatchResult? =
            createNestedMatchResult(expectedAnalysisMatch, expectedSourceMatch)

        // Spawns THREAD_COUNT threads to test parallel nested dependency lookups.
        val executor: ForkJoinPool = ForkJoinPool(THREAD_COUNT)
        val latch: CountDownLatch = CountDownLatch(THREAD_COUNT)
        for (i in 0..<THREAD_COUNT) {
            executor.execute(
                java.lang.Runnable {
                    when (lookup.getValueOrFuture(key, validityHorizon)) {
                        -> assertThat(value).isEqualTo(expectedResult)
                        -> try {
                            assertThat(future.get()).isEqualTo(expectedResult)
                        } catch (e: java.lang.Exception) {
                            if (e is java.lang.InterruptedException) {
                                java.lang.Thread.currentThread().interrupt()
                            }
                            throw java.lang.AssertionError(e)
                        }
                    }
                    latch.countDown()
                })
        }
        latch.await()
    }

    @org.junit.Test
    fun createNestedMatchResult_analysisVersionNoMatch_sourceVersionPositive_sourceMatch() {
        val result: NestedMatchResult? = createNestedMatchResult(NO_MATCH, 5)
        assertThat(result).isEqualTo(SourceMatch(5))
    }

    @org.junit.Test
    fun createNestedMatchResult_analysisVersionLessEqualSourceVersion_analysisMatch() {
        val result: NestedMatchResult? = createNestedMatchResult(10, 20)
        assertThat(result).isEqualTo(AnalysisMatch(10))
    }

    @org.junit.Test
    fun createNestedMatchResult_analysisVersionGreaterSourceVersion_analysisNonNoMatch() {
        val result: NestedMatchResult? = createNestedMatchResult(20, 5)
        assertThat(result).isEqualTo(AnalysisAndSourceMatch(20, 5))
    }

    @org.junit.Test
    fun createNestedMatchResult_analysisVersionGreaterSourceVersion_analysisAndSourceMatch() {
        val result: NestedMatchResult? = createNestedMatchResult(20, 10)
        assertThat(result).isEqualTo(AnalysisAndSourceMatch(20, 10))
    }

    @org.junit.Test
    fun createNestedMatchResult_analysisVersionEqualSourceVersion_analysisMatch() {
        val result: NestedMatchResult? = createNestedMatchResult(10, 10)
        assertThat(result).isEqualTo(AnalysisMatch(10))
    }

    @org.junit.Test
    fun createNestedMatchResult_analysisVersionNoMatch_sourceVersionNoMatch_noMatchResult() {
        val result: NestedMatchResult? = createNestedMatchResult(NO_MATCH, NO_MATCH)
        assertThat(result).isEqualTo(NO_MATCH_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidation_missingNested() {
        val missingNested: NestedDependencies? = NestedDependencies.newMissingInstance()

        val lookupResult: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            lookup.getValueOrFuture(missingNested, 99)

        assertThat(lookupResult).isEqualTo(ALWAYS_MATCH_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidation_missingAnalysisDependency_file() {
        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(
                    createAvailableFileDependencies("dep/a"), FileDependencies.newMissingInstance()
                ),
                com.google.common.collect.ImmutableList.of<E?>(createAvailableFileDependencies("src/a"))
            )

        Truth.assertThat(getLookupResult(key, 99)).isEqualTo(ALWAYS_MATCH_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidation_missingAnalysisDependency_listing() {
        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(
                    createAvailableFileDependencies("dep/a"), ListingDependencies.newMissingInstance()
                ),
                com.google.common.collect.ImmutableList.of<E?>(createAvailableFileDependencies("src/a"))
            )

        Truth.assertThat(getLookupResult(key, 99)).isEqualTo(ALWAYS_MATCH_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidation_missingSourceDependency() {
        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(createAvailableFileDependencies("dep/a")),
                com.google.common.collect.ImmutableList.of<E?>(
                    createAvailableFileDependencies("src/a"), FileDependencies.newMissingInstance()
                )
            )

        Truth.assertThat(getLookupResult(key, 99)).isEqualTo(ALWAYS_MATCH_RESULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidation_allMissing() {
        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedDependencies.from(
                com.google.common.collect.ImmutableList.of<E?>(FileDependencies.newMissingInstance()),
                com.google.common.collect.ImmutableList.of<E?>(FileDependencies.newMissingInstance())
            )

        Truth.assertThat(getLookupResult(key, 99)).isEqualTo(ALWAYS_MATCH_RESULT)
    }

    private fun getLookupResult(key: NestedDependencies?, validityHorizon: Int): NestedMatchResult? {
        try {
            when (lookup.getValueOrFuture(key, validityHorizon)) {
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

    private fun getLookupResult(key: FileOpDependency?, validityHorizon: Int): FileOpMatchResult? {
        try {
            when (fileOpMatches.getValueOrFuture(key, validityHorizon)) {
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

        private fun createNestedDependencies(fileDependency: FileDependencies?): NestedDependencies {
            return NestedDependencies.from(
                arrayOf<FileSystemDependencies?>(fileDependency), NestedDependencies.EMPTY_SOURCES
            )
        }

        private fun createAvailableFileDependencies(path: String?): AvailableFileDependencies? {
            return FileDependencies.builder(path).build() as AvailableFileDependencies?
        }
    }
}
