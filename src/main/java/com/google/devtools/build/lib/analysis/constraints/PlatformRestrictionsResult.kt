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
package com.google.devtools.build.lib.analysis.constraints

import com.google.auto.value.AutoBuilder
import com.google.devtools.build.lib.analysis.ConfiguredTarget

/**
 * Targets that have additional restrictions based on the current platform.
 * 
 * @param targetsToSkip Targets that need be skipped.
 * @param targetsWithErrors Targets that should be skipped, but were explicitly requested on the
 * command line.
 */
class PlatformRestrictionsResult(
    targetsToSkip: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
    targetsWithErrors: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?
) {
    /** [PlatformRestrictionsResult]Builder.  */
    @AutoBuilder
    interface Builder {
        fun targetsToSkip(targetsToSkip: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?): Builder?

        fun targetsWithErrors(targetsWithErrors: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?): Builder?

        fun build(): PlatformRestrictionsResult?
    }

    val targetsToSkip: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?
    val targetsWithErrors: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?

    init {
        this.targetsWithErrors = targetsWithErrors
        this.targetsToSkip = targetsToSkip
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<ConfiguredTarget?>?>(
            targetsToSkip,
            "targetsToSkip"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<ConfiguredTarget?>?>(
            targetsWithErrors,
            "targetsWithErrors"
        )
    }

    companion object {
        fun builder(): Builder {
            return AutoBuilder_PlatformRestrictionsResult_Builder()
                .targetsToSkip(com.google.common.collect.ImmutableSet.of<E?>())
                .targetsWithErrors(com.google.common.collect.ImmutableSet.of<E?>())
        }
    }
}
