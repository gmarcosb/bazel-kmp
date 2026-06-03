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
class FileOpMatchMemoizingLookupTest {
    private val executor: java.util.concurrent.Executor = ForkJoinPool(THREAD_COUNT)
    private val changes: VersionedChanges = VersionedChanges(com.google.common.collect.ImmutableList.of<E?>())
    private val lookup: FileOpMatchMemoizingLookup =
        FileOpMatchMemoizingLookup(executor, changes, ConcurrentHashMap<K?, V?>())

    @org.junit.Test
    fun matchEmptyChanges_noMatch() {
        assertThat(getLookupResult(FileDependencies.builder("test_path").build(), 0))
            .isEqualTo(NO_MATCH_RESULT)
    }

    @org.junit.Test
    fun matchingFileChange_inRangeValidityHorizon_matches() {
        changes.registerFileChange("abc/def", 100)

        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileDependencies.builder("abc/def").build()
        assertThat(getLookupResult(key, 99)).isEqualTo(FileOpMatch(100))
    }

    @org.junit.Test
    fun matchingFileChange_outOfRangeValidityHorizon_doesNotMatch() {
        changes.registerFileChange("abc/def", 100)

        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileDependencies.builder("abc/def").build()
        assertThat(getLookupResult(key, 100)).isEqualTo(NO_MATCH_RESULT)
    }

    @org.junit.Test
    @TestParameters("{validityHorizon: 98, expectedMatchVersion: 99}")
    @TestParameters("{validityHorizon: 99, expectedMatchVersion: 100}")
    @TestParameters("{validityHorizon: 100, expectedMatchVersion: 101}")
    @TestParameters("{validityHorizon: 101, expectedMatchVersion: 2147483647}")
    fun matchingFileChange_withDependencies_aggregatesDependencies(
        validityHorizon: Int, expectedMatchVersion: Int
    ) {
        changes.registerFileChange("dep/a", 99)
        changes.registerFileChange("dep/b", 100)
        changes.registerFileChange("dep/c", 101)

        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileDependencies.builder("abc/def")
                .addDependency(createFileDependencies("dep/a"))
                .addDependency(createFileDependencies("dep/b"))
                .addDependency(createFileDependencies("dep/c"))
                .build()

        val expectedResult: FileOpMatch? =
            if (expectedMatchVersion == VersionedChanges.NO_MATCH)
                NO_MATCH_RESULT
            else
                FileOpMatch(expectedMatchVersion)
        assertThat(getLookupResult(key, validityHorizon)).isEqualTo(expectedResult)
    }

    @org.junit.Test
    @TestParameters("{validityHorizon: 98, expectedMatchVersion: 99}")
    @TestParameters("{validityHorizon: 99, expectedMatchVersion: 100}")
    @TestParameters("{validityHorizon: 100, expectedMatchVersion: 101}")
    @TestParameters("{validityHorizon: 101, expectedMatchVersion: 2147483647}")
    @Throws(java.lang.Exception::class)
    fun matchingFileChange_withAsyncDependencies_aggregatesDependencies(
        validityHorizon: Int, expectedMatchVersion: Int
    ) {
        // This test covers the futures handling code path in AggregatingFutureFileOpMatchResult.
        changes.registerFileChange("dep/a", 99)
        changes.registerFileChange("dep/b", 100)
        changes.registerFileChange("dep/c", 101)

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
        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FileDependencies.builder("abc/def")
                .addDependency(depA)
                .addDependency(depB)
                .addDependency(depC)
                .build()

        java.lang.Thread(
            java.lang.Runnable {
                val unused: FileOpMatchResult? = getLookupResult(depA, validityHorizon)
            })
            .start()
        java.lang.Thread(
            java.lang.Runnable {
                val unused: FileOpMatchResult? = getLookupResult(depB, validityHorizon)
            })
            .start()
        java.lang.Thread(
            java.lang.Runnable {
                val unused: FileOpMatchResult? = getLookupResult(depC, validityHorizon)
            })
            .start()

        // Waits for all the dependency threads have taken ownership of their entries.
        depA.awaitEarliestMatchEntered()
        depB.awaitEarliestMatchEntered()
        depC.awaitEarliestMatchEntered()

        val lookupResult: FutureFileOpMatchResult =
            lookup.getValueOrFuture(key, validityHorizon) as FutureFileOpMatchResult
        assertThat(lookupResult.isDone()).isFalse()

        // The lookupResult cannot complete until all the dependencies complete. Releases the
        // dependencies.
        depA.enable()
        depB.enable()
        depC.enable()

        val expectedResult: FileOpMatch? =
            if (expectedMatchVersion == VersionedChanges.NO_MATCH)
                NO_MATCH_RESULT
            else
                FileOpMatch(expectedMatchVersion)
        assertThat(lookupResult.get()).isEqualTo(expectedResult)
    }

    @org.junit.Test
    fun matchingListing_matchesContainedFileChange() {
        changes.registerFileChange("dir/a", 100)

        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ListingDependencies.from(FileDependencies.builder("dir").build())
        assertThat(getLookupResult(key, 99)).isEqualTo(FileOpMatch(100))
    }

    @org.junit.Test
    fun matchListingChange_matchesDirectoryChange() {
        changes.registerFileChange("dir", 100)

        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ListingDependencies.from(FileDependencies.builder("dir").build())
        assertThat(getLookupResult(key, 99)).isEqualTo(FileOpMatch(100))
    }

    @org.junit.Test
    fun matchListingChange_matchesDependencyChange() {
        changes.registerFileChange("dep/a", 100)

        val key: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ListingDependencies.from(
                FileDependencies.builder("dir").addDependency(createFileDependencies("dep/a")).build()
            )

        assertThat(getLookupResult(key, 99)).isEqualTo(FileOpMatch(100))
    }

    @org.junit.Test
    fun invalidation_missingFile() {
        val missingFile: FileDependencies? = FileDependencies.newMissingInstance()

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            lookup.getValueOrFuture(missingFile, 99)
        assertThat(result).isEqualTo(ALWAYS_MATCH_RESULT)

        val cachedResult: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            lookup.getValueOrFuture(missingFile, 99)
        assertThat(cachedResult).isEqualTo(ALWAYS_MATCH_RESULT)
    }

    @org.junit.Test
    fun invalidation_missingListing() {
        val missingListing: ListingDependencies? = ListingDependencies.newMissingInstance()

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            lookup.getValueOrFuture(missingListing, 99)
        assertThat(result).isEqualTo(ALWAYS_MATCH_RESULT)

        val cachedResult: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            lookup.getValueOrFuture(missingListing, 99)
        assertThat(cachedResult).isEqualTo(ALWAYS_MATCH_RESULT)
    }

    private fun getLookupResult(key: FileOpDependency?, validityHorizon: Int): FileOpMatchResult? {
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

    companion object {
        private const val THREAD_COUNT = 10
        private fun createFileDependencies(path: String?): AvailableFileDependencies? {
            // This cast is necessary because the builder returns the base FileDependencies type, while the
            // methods under test require the more specific AvailableFileDependencies type. This is fine for
            // the test.
            return FileDependencies.builder(path).build() as AvailableFileDependencies?
        }
    }
}
