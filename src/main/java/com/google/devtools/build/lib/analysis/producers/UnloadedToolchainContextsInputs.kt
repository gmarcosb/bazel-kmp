// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.ExecGroupCollection

/** Collates inputs for the [UnloadedToolchainContextsProducer].  */
@AutoValue
abstract class UnloadedToolchainContextsInputs : ExecGroupCollection.Builder() {
    // Null if no toolchain resolution is required.
    abstract fun targetToolchainContextKey(): ToolchainContextKey?

    companion object {
        fun create(
            processedExecGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>?,
            targetToolchainContextKey: ToolchainContextKey?
        ): UnloadedToolchainContextsInputs {
            return AutoValue_UnloadedToolchainContextsInputs(
                processedExecGroups, targetToolchainContextKey
            )
        }

        @kotlin.jvm.JvmStatic
        fun empty(): UnloadedToolchainContextsInputs {
            return AutoValue_UnloadedToolchainContextsInputs(
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* targetToolchainContextKey= */null
            )
        }
    }
}
