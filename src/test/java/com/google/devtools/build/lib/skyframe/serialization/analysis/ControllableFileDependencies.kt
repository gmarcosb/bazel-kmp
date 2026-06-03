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

import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies.AvailableFileDependencies

/**
 * [FileDependencies] implementation that waits for [.enable] inside [ ].
 * 
 * 
 * This can be used to exercise certain concurrency conditions.
 */
internal class ControllableFileDependencies(
    resolvedPaths: com.google.common.collect.ImmutableList<String?>,
    dependencies: com.google.common.collect.ImmutableList<AvailableFileDependencies?>
) : AvailableFileDependencies() {
    private val resolvedPaths: com.google.common.collect.ImmutableList<String?>
    private val dependencies: com.google.common.collect.ImmutableList<AvailableFileDependencies?>

    private val findEarliestMatchEntered: CountDownLatch = CountDownLatch(1)
    private val countDown: CountDownLatch = CountDownLatch(1)

    init {
        this.resolvedPaths = resolvedPaths
        this.dependencies = dependencies
    }

    val isMissingData: Boolean
        get() = false

    @Throws(java.lang.InterruptedException::class)
    fun awaitEarliestMatchEntered() {
        findEarliestMatchEntered.await()
    }

    fun enable() {
        countDown.countDown()
    }

    public override fun findEarliestMatch(changes: VersionedChanges, validityHorizon: Int): Int {
        findEarliestMatchEntered.countDown()
        try {
            countDown.await()
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.AssertionError(e)
        }
        var minMatch: Int = VersionedChanges.NO_MATCH
        for (element in resolvedPaths) {
            val result: Int = changes.matchFileChange(element, validityHorizon)
            if (result < minMatch) {
                minMatch = result
            }
        }
        return minMatch
    }

    val dependencyCount: Int
        get() = dependencies.size

    public override fun getDependency(index: Int): AvailableFileDependencies? {
        return dependencies.get(index)
    }

    public override fun resolvedPath(): String? {
        return com.google.common.collect.Iterables.getLast<String?>(resolvedPaths)
    }

    val allResolvedPathsForTesting: com.google.common.collect.ImmutableList<String?>
        get() = resolvedPaths
}
