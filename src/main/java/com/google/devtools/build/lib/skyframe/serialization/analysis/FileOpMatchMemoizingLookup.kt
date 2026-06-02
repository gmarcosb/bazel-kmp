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

import com.google.devtools.build.lib.concurrent.QuiescingFuture

/**
 * Matches [FileOpDependency] instances representing cached value dependencies against [ ][.changes], containing file system content changes.
 * 
 * 
 * The `validityHorizon` (VH) parameter of [.getValueOrFuture] has subtle semantics,
 * but works correctly, even in the presence of multiple overlapping nodes at different versions and
 * VH values. See [VersionedChangesValidator] and [VersionedChanges] for more details.
 */
internal class FileOpMatchMemoizingLookup
    (
    executor: java.util.concurrent.Executor,
    changes: VersionedChanges?,
    map: ConcurrentMap<FileOpDependency?, FileOpMatchResultOrFuture?>?
) : AbstractValueOrFutureMap<FileOpDependency?, FileOpMatchResultOrFuture?, FileOpMatchResult?, FutureFileOpMatchResult?>(
    map,
    java.util.function.BiFunction { key: FileOpDependency?, consumer: java.util.function.BiConsumer<FileOpDependency?, FileOpMatchResult?>? ->
        FutureFileOpMatchResult(
            key,
            consumer
        )
    },
    FutureFileOpMatchResult::class.java
) {
    private val changes: VersionedChanges?
    private val executor: java.util.concurrent.Executor

    init {
        this.changes = changes
        this.executor = executor
    }

    fun changes(): VersionedChanges? {
        return changes
    }

    fun getValueOrFuture(key: FileOpDependency?, validityHorizon: Int): FileOpMatchResultOrFuture? {
        val result: FileOpMatchResultOrFuture? = getOrCreateValueForSubclasses(key)
        if (result is FutureFileOpMatchResult && result.tryTakeOwnership()) {
            try {
                return populateFutureFileOpMatchResult(validityHorizon, result)
            } finally {
                result.verifyComplete()
            }
        }
        return result
    }

    private fun populateFutureFileOpMatchResult(
        validityHorizon: Int, ownedFuture: FutureFileOpMatchResult
    ): FileOpMatchResultOrFuture? {
        return when (ownedFuture.key()) {
            -> aggregateAnyAdditionalFileDependencies(
                file.findEarliestMatch(changes, validityHorizon), file, validityHorizon, ownedFuture
            )

            -> ownedFuture.completeWith(AlwaysMatch.ALWAYS_MATCH_RESULT)
            -> {
                // Matches the listing (files inside the directory changed).
                val version: Int = listing.findEarliestMatch(changes, validityHorizon)
                // Then matches the directory itself.
                val realDirectory: AvailableFileDependencies = listing.realDirectory()
                aggregateAnyAdditionalFileDependencies(
                    min(version, realDirectory.findEarliestMatch(changes, validityHorizon)),
                    realDirectory,
                    validityHorizon,
                    ownedFuture
                )
            }

            -> ownedFuture.completeWith(AlwaysMatch.ALWAYS_MATCH_RESULT)
        }
    }

    private fun aggregateAnyAdditionalFileDependencies(
        baseVersion: Int,
        file: AvailableFileDependencies,
        validityHorizon: Int,
        ownedFuture: FutureFileOpMatchResult
    ): FileOpMatchResultOrFuture? {
        if (file.dependencyCount === 0) {
            return ownedFuture.completeWith(FileOpMatchResult.Companion.create(baseVersion))
        }
        val aggregator = AggregatingFutureFileOpMatchResult(baseVersion, executor)
        for (i in 0..<file.dependencyCount) {
            aggregator.addDependency(getValueOrFuture(file.getDependency(i), validityHorizon))
        }
        aggregator.notifyAllDependenciesAdded()
        return ownedFuture.completeWith(aggregator)
    }

    private class AggregatingFutureFileOpMatchResult
        (version: Int, executor: java.util.concurrent.Executor) :
        QuiescingFuture<FileOpMatchResult?>(com.google.common.util.concurrent.MoreExecutors.directExecutor()),
        com.google.common.util.concurrent.FutureCallback<FileOpMatchResult?> {
        private val executor: java.util.concurrent.Executor

        @kotlin.concurrent.Volatile
        private var result: FileOpMatchResult

        fun addDependency(resultOrFuture: FileOpMatchResultOrFuture) {
            when (resultOrFuture) {
                -> updateResult(match)
                -> {
                    increment()
                    com.google.common.util.concurrent.Futures.addCallback<FileOpMatchResult?>(
                        future,
                        this as com.google.common.util.concurrent.FutureCallback<FileOpMatchResult?>,
                        executor
                    )
                }
            }
        }

        fun notifyAllDependenciesAdded() {
            decrement()
        }

        fun updateResult(newResult: FileOpMatchResult) {
            var snapshot: FileOpMatchResult
            do {
                snapshot = result
            } while (newResult.version() < snapshot.version()
                && !RESULT_HANDLE.compareAndSet(this, snapshot, newResult)
            )
        }

        protected val value: FileOpMatchResult
            get() = result

        /**
         * Implementation of [<].
         * 
         */
        @Deprecated("only for {@link #addDependency} futures callback processing.")
        override fun onSuccess(result: FileOpMatchResult) {
            updateResult(result)
            decrement()
        }

        /**
         * Implementation of [<].
         * 
         */
        @Deprecated("only for {@link #addDependency} futures callback processing.")
        override fun onFailure(t: Throwable) {
            notifyException(t)
        }

        init {
            this.executor = executor
            this.result = FileOpMatchResult.Companion.create(version)
        }

        companion object {
            private val RESULT_HANDLE: java.lang.invoke.VarHandle

            init {
                try {
                    RESULT_HANDLE =
                        java.lang.invoke.MethodHandles.lookup()
                            .findVarHandle(
                                AggregatingFutureFileOpMatchResult::class.java, "result", FileOpMatchResult::class.java
                            )
                } catch (e: java.lang.ReflectiveOperationException) {
                    throw java.lang.ExceptionInInitializerError(e)
                }
            }
        }
    }
}
