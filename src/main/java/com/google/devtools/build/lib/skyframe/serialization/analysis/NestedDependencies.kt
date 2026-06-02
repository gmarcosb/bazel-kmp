// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies.AvailableFileDependencies
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.NestedDependenciesOrFuture
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies

/**
 * A representation of a recursively composable set of [FileSystemDependencies].
 * 
 * 
 * This corresponds to a previously serialized [ ] instance, but this
 * implementation is mostly decoupled from Bazel code.
 */
internal abstract class NestedDependencies

    : FileSystemDependencies, NestedDependenciesOrFuture {
    internal class AvailableNestedDependencies private constructor(
        analysisDependencies: Array<FileSystemDependencies>,
        sources: Array<AvailableFileDependencies?>
    ) : NestedDependencies() {
        private val analysisDependencies: Array<FileSystemDependencies>
        private val sources: Array<AvailableFileDependencies?>

        init {
            com.google.common.base.Preconditions.checkArgument(
                analysisDependencies.size >= 1 || sources.size >= 1,
                "analysisDependencies and sources both empty"
            )
            this.analysisDependencies = analysisDependencies
            this.sources = sources
        }

        val isMissingData: Boolean
            get() = false

        fun analysisDependenciesCount(): Int {
            return analysisDependencies.size
        }

        fun getAnalysisDependency(index: Int): FileSystemDependencies? {
            return analysisDependencies[index]
        }

        fun sourcesCount(): Int {
            return sources.size
        }

        fun getSource(index: Int): AvailableFileDependencies? {
            return sources[index]
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("analysisDependencies", java.util.Arrays.asList<FileSystemDependencies?>(*analysisDependencies))
                .add("sources", java.util.Arrays.asList<AvailableFileDependencies?>(*sources))
                .toString()
        }
    }

    /**
     * Signals missing data in the nested set of dependencies.
     * 
     * 
     * This is deliberately not a singleton to avoid a memory leak in the weak-value caches in
     * [FileDependencyDeserializer].
     */
    internal class MissingNestedDependencies private constructor() : NestedDependencies() {
        val isMissingData: Boolean
            get() = true
    }

    companion object {
        // While formally possible, we don't anticipate analysisDependencies being empty often.
        // `sources` could be frequently empty.
        @kotlin.jvm.JvmField
        val EMPTY_SOURCES: Array<FileDependencies?> = arrayOfNulls<FileDependencies>(0)

        fun from(
            analysisDependencies: Array<FileSystemDependencies>, sources: Array<FileDependencies?>
        ): NestedDependencies {
            for (dep in analysisDependencies) {
                if (dep.isMissingData()) {
                    return MissingNestedDependencies()
                }
            }
            val size = sources.size
            val availableSources: Array<AvailableFileDependencies?> = arrayOfNulls<AvailableFileDependencies>(size)
            for (i in 0..<size) {
                when (sources[i]) {
                    -> availableSources[i] = available
                    -> return MissingNestedDependencies()
                }
            }
            return AvailableNestedDependencies(analysisDependencies, availableSources)
        }

        @com.google.common.annotations.VisibleForTesting
        fun from(
            analysisDependencies: MutableCollection<out FileSystemDependencies?>,
            sources: MutableCollection<FileDependencies?>
        ): NestedDependencies {
            return Companion.from(
                analysisDependencies.toArray<FileSystemDependencies?>(java.util.function.IntFunction { _Dummy_.__Array__() }),
                sources.toArray<FileDependencies?>(java.util.function.IntFunction { _Dummy_.__Array__() })
            )
        }

        @kotlin.jvm.JvmStatic
        fun newMissingInstance(): NestedDependencies {
            return MissingNestedDependencies()
        }
    }
}
