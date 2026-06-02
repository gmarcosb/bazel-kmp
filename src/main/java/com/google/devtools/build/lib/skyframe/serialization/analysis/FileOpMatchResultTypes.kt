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

import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency

/** Container for [DeltaDepotValidator.matches] result types.  */
internal class FileOpMatchResultTypes private constructor() {
    /** [DeltaDepotValidator.matches] result type.  */
    internal interface FileOpMatchResultOrFuture

    /** An immediate result.  */
    internal interface FileOpMatchResult : FileOpMatchResultOrFuture, MatchIndicator {
        fun version(): Int

        companion object {
            fun create(version: Int): FileOpMatchResult {
                return when (version) {
                    VersionedChanges.Companion.NO_MATCH -> NoMatch.NO_MATCH_RESULT
                    VersionedChanges.Companion.ALWAYS_MATCH -> AlwaysMatch.ALWAYS_MATCH_RESULT
                    else -> FileOpMatch(version)
                }
            }
        }
    }

    /** A result signaling a match.  */
    @kotlin.jvm.JvmRecord
    internal data class FileOpMatch(val version: Int) : FileOpMatchResult {
        val isMatch: Boolean
            get() = true
    }

    /** A future result.  */
    internal class FutureFileOpMatchResult
        (key: FileOpDependency?, consumer: java.util.function.BiConsumer<FileOpDependency?, FileOpMatchResult?>?) :
        SettableFutureKeyedValue<FutureFileOpMatchResult?, FileOpDependency?, FileOpMatchResult?>(key, consumer),
        FileOpMatchResultOrFuture
}
