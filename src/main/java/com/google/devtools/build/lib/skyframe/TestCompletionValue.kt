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

/**
 * A test completion value represents the completion of a test target. This includes the execution
 * of all test shards and repeated runs, if applicable.
 */
object TestCompletionValue : SkyValue {
    val TEST_COMPLETION_MARKER: TestCompletionValue = TestCompletionValue()

    fun key(
        lac: ConfiguredTargetKey?,
        topLevelArtifactContext: TopLevelArtifactContext?,
        exclusiveTesting: Boolean
    ): SkyKey {
        return TestCompletionKey.Companion.create(lac, topLevelArtifactContext, exclusiveTesting)
    }

    fun keys(
        targets: MutableCollection<ConfiguredTarget?>,
        topLevelArtifactContext: TopLevelArtifactContext?,
        exclusiveTesting: Boolean
    ): Iterable<SkyKey?> {
        return com.google.common.collect.Iterables.transform<ConfiguredTarget?, SkyKey?>(
            targets,
            com.google.common.base.Function { ct: ConfiguredTarget? ->
                TestCompletionKey.Companion.create(
                    ConfiguredTargetKey.fromConfiguredTarget(ct),
                    topLevelArtifactContext,
                    exclusiveTesting
                )
            })
    }

    /** Key for [TestCompletionValue] nodes.  */
    @AutoCodec
    @AutoValue
    abstract class TestCompletionKey : SkyKey {
        abstract fun configuredTargetKey(): ConfiguredTargetKey?

        abstract fun topLevelArtifactContext(): TopLevelArtifactContext?
        abstract fun exclusiveTesting(): Boolean

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.TEST_COMPLETION
        }

        override fun valueIsShareable(): Boolean {
            return false
        }

        val skyKeyInterner: SkyKeyInterner<TestCompletionKey?>
            get() = interner

        companion object {
            private val interner: SkyKeyInterner<TestCompletionKey?> = SkyKey.newInterner<TestCompletionKey?>()

            @VisibleForSerialization
            @AutoCodec.Instantiator
            fun create(
                configuredTargetKey: ConfiguredTargetKey?,
                topLevelArtifactContext: TopLevelArtifactContext?,
                exclusiveTesting: Boolean
            ): TestCompletionKey {
                return interner.intern(
                    AutoValue_TestCompletionValue_TestCompletionKey(
                        configuredTargetKey, topLevelArtifactContext, exclusiveTesting
                    )
                )
            }
        }
    }
}
