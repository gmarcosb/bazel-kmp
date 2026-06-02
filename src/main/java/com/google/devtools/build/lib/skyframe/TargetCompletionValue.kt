// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** The value of a TargetCompletion. Just a sentinel.  */
object TargetCompletionValue : SkyValue {
    @SerializationConstant
    val INSTANCE: TargetCompletionValue = TargetCompletionValue()

    fun key(
        configuredTargetKey: ConfiguredTargetKey?,
        topLevelArtifactContext: TopLevelArtifactContext?,
        willTest: Boolean
    ): TargetCompletionKey {
        return TargetCompletionKey.Companion.create(configuredTargetKey, topLevelArtifactContext, willTest)
    }

    fun keys(
        targets: MutableCollection<ConfiguredTarget?>,
        ctx: TopLevelArtifactContext?,
        targetsToTest: MutableSet<ConfiguredTarget?>
    ): Iterable<TargetCompletionKey?> {
        return com.google.common.collect.Iterables.transform<ConfiguredTarget?, TargetCompletionKey?>(
            targets,
            com.google.common.base.Function { ct: ConfiguredTarget? ->
                TargetCompletionKey.Companion.create(
                    ConfiguredTargetKey.fromConfiguredTarget(ct), ctx, targetsToTest.contains(ct)
                )
            })
    }

    /** [com.google.devtools.build.skyframe.SkyKey] for [TargetCompletionValue].  */
    @AutoValue
    abstract class TargetCompletionKey

        : TopLevelActionLookupKeyWrapper, StallableSkykey {
        abstract override fun actionLookupKey(): ConfiguredTargetKey?

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.TARGET_COMPLETION
        }

        override fun valueIsShareable(): Boolean {
            return false
        }

        abstract fun willTest(): Boolean

        companion object {
            fun create(
                actionLookupKey: ConfiguredTargetKey?,
                topLevelArtifactContext: TopLevelArtifactContext?,
                willTest: Boolean
            ): TargetCompletionKey {
                return AutoValue_TargetCompletionValue_TargetCompletionKey(
                    topLevelArtifactContext, actionLookupKey, willTest
                )
            }
        }
    }
}
