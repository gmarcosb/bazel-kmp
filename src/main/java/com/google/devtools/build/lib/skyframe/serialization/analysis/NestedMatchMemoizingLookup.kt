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
 * Computes matching versions for [NestedDependencies] with memoization.
 * 
 * 
 * Uses a backing [FileOpMatchMemoizingLookup] instance for any [FileOpDependency]
 * lookups.
 * 
 * 
 * The `validityHorizon` (VH) parameter of [.getValueOrFuture] has subtle semantics,
 * but works correctly, even in the presence of multiple overlapping nodes at different versions and
 * VH values. See [VersionedChangesValidator] and [VersionedChanges] for more details.
 */
internal class NestedMatchMemoizingLookup
    (
    executor: java.util.concurrent.Executor,
    fileOpMatches: FileOpMatchMemoizingLookup,
    map: ConcurrentMap<NestedDependencies?, NestedMatchResultOrFuture?>?
) : AbstractValueOrFutureMap<NestedDependencies?, NestedMatchResultOrFuture?, NestedMatchResult?, FutureNestedMatchResult?>(
    map,
    java.util.function.BiFunction { key: NestedDependencies?, consumer: java.util.function.BiConsumer<NestedDependencies?, NestedMatchResult?>? ->
        FutureNestedMatchResult(
            key,
            consumer
        )
    },
    FutureNestedMatchResult::class.java
) {
    private val executor: java.util.concurrent.Executor
    private val fileOpMatches: FileOpMatchMemoizingLookup

    init {
        this.executor = executor
        this.fileOpMatches = fileOpMatches
    }

    fun getValueOrFuture(key: NestedDependencies?, validityHorizon: Int): NestedMatchResultOrFuture? {
        val result: NestedMatchResultOrFuture? = getOrCreateValueForSubclasses(key)
        if (result is FutureNestedMatchResult && result.tryTakeOwnership()) {
            try {
                return populateFutureNestedMatchResult(validityHorizon, result)
            } finally {
                result.verifyComplete()
            }
        }
        return result
    }

    private fun populateFutureNestedMatchResult(
        validityHorizon: Int, ownedFuture: FutureNestedMatchResult
    ): NestedMatchResultOrFuture? {
        return when (ownedFuture.key()) {
            -> {
                val aggregator = NestedFutureResultAggregator()
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                aggregator.notifyAllDependenciesAdded()
                ownedFuture.completeWith(aggregator)
            }

            -> ownedFuture.completeWith(AlwaysMatch.ALWAYS_MATCH_RESULT)
        }
    }

    private class NestedFutureResultAggregator

        : QuiescingFuture<NestedMatchResult?>(com.google.common.util.concurrent.MoreExecutors.directExecutor()) {
        @kotlin.concurrent.Volatile
        private var earliestAnalysisMatch: Int = VersionedChanges.Companion.NO_MATCH

        @kotlin.concurrent.Volatile
        private var earliestSourceMatch: Int = VersionedChanges.Companion.NO_MATCH

        fun addAnalysisResultOrFuture(resultOrFuture: FileOpMatchResultOrFuture) {
            when (resultOrFuture) {
                -> updateAnalysisVersionIfEarlier(result.version())
                -> {
                    increment()
                    com.google.common.util.concurrent.Futures.addCallback<FileOpMatchResult?>(
                        future,
                        object : ResultCallback<FileOpMatchResult?>() {
                            override fun processResult(result: FileOpMatchResult) {
                                updateAnalysisVersionIfEarlier(result.version())
                            }
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }
            }
        }

        fun addSourceResultOrFuture(resultOrFuture: FileOpMatchResultOrFuture) {
            when (resultOrFuture) {
                -> updateSourceVersionIfEarlier(result.version())
                -> {
                    increment()
                    com.google.common.util.concurrent.Futures.addCallback<FileOpMatchResult?>(
                        future,
                        object : ResultCallback<FileOpMatchResult?>() {
                            override fun processResult(result: FileOpMatchResult) {
                                updateSourceVersionIfEarlier(result.version())
                            }
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }
            }
        }

        fun addNestedResult(result: NestedMatchResult) {
            when (result) {
                NoMatch.NO_MATCH_RESULT -> {}
                AlwaysMatch.ALWAYS_MATCH_RESULT -> earliestAnalysisMatch = VersionedChanges.Companion.ALWAYS_MATCH
                -> updateAnalysisVersionIfEarlier(version)
                -> updateSourceVersionIfEarlier(version)
                -> {
                    updateAnalysisVersionIfEarlier(analysisVersion)
                    updateSourceVersionIfEarlier(sourceVersion)
                }
            }
        }

        fun addFutureNestedMatchResult(future: FutureNestedMatchResult) {
            com.google.common.util.concurrent.Futures.addCallback<NestedMatchResult?>(
                future,
                object : ResultCallback<NestedMatchResult?>() {
                    override fun processResult(result: NestedMatchResult) {
                        addNestedResult(result)
                    }
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        fun signalNestedTaskAdded() {
            increment()
        }

        fun signalNestedTaskComplete() {
            decrement()
        }

        fun notifyAllDependenciesAdded() {
            decrement()
        }

        protected val value: NestedMatchResult?
            get() = NestedMatchResultTypes.createNestedMatchResult(earliestAnalysisMatch, earliestSourceMatch)

        fun updateAnalysisVersionIfEarlier(version: Int) {
            var snapshot: Int
            do {
                snapshot = earliestAnalysisMatch
            } while (version < snapshot && !ANALYSIS_MATCH_HANDLE.compareAndSet(this, snapshot, version))
        }

        fun updateSourceVersionIfEarlier(version: Int) {
            var snapshot: Int
            do {
                snapshot = earliestSourceMatch
            } while (version < snapshot && !SOURCE_MATCH_HANDLE.compareAndSet(this, snapshot, version))
        }

        /** [FutureCallback] implementation that includes common future handling behavior.  */
        private abstract inner class ResultCallback<T> : com.google.common.util.concurrent.FutureCallback<T?> {
            abstract fun processResult(result: T?)

            override fun onSuccess(result: T?) {
                processResult(result)
                decrement()
            }

            override fun onFailure(t: Throwable) {
                notifyException(t)
            }
        }

        companion object {
            private val ANALYSIS_MATCH_HANDLE: java.lang.invoke.VarHandle
            private val SOURCE_MATCH_HANDLE: java.lang.invoke.VarHandle

            init {
                try {
                    ANALYSIS_MATCH_HANDLE =
                        java.lang.invoke.MethodHandles.lookup()
                            .findVarHandle(
                                NestedFutureResultAggregator::class.java,
                                "earliestAnalysisMatch",
                                Int::class.javaPrimitiveType
                            )
                    SOURCE_MATCH_HANDLE =
                        java.lang.invoke.MethodHandles.lookup()
                            .findVarHandle(
                                NestedFutureResultAggregator::class.java,
                                "earliestSourceMatch",
                                Int::class.javaPrimitiveType
                            )
                } catch (e: java.lang.ReflectiveOperationException) {
                    throw java.lang.ExceptionInInitializerError(e)
                }
            }
        }
    }
}
