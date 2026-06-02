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

import com.google.devtools.build.lib.analysis.TopLevelArtifactContext

/**
 * The value of an AspectCompletion. Currently this just stores an Aspect.
 */
object AspectCompletionValue : SkyValue {
    @kotlin.jvm.JvmField
    @SerializationConstant
    val INSTANCE: AspectCompletionValue = AspectCompletionValue()

    fun keys(keys: MutableCollection<AspectKey?>, ctx: TopLevelArtifactContext?): Iterable<SkyKey?> {
        return com.google.common.collect.Iterables.transform<AspectKey?, SkyKey?>(
            keys,
            com.google.common.base.Function { k: AspectKey? -> AspectCompletionKey.Companion.create(k, ctx) })
    }

    /** The key of an AspectCompletionValue.  */
    @AutoValue
    abstract class AspectCompletionKey

        : TopLevelActionLookupKeyWrapper, StallableSkykey {
        abstract override fun actionLookupKey(): AspectKey?

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.ASPECT_COMPLETION
        }

        override fun valueIsShareable(): Boolean {
            return false
        }

        companion object {
            fun create(
                aspectKey: AspectKey?, topLevelArtifactContext: TopLevelArtifactContext?
            ): AspectCompletionKey {
                return AutoValue_AspectCompletionValue_AspectCompletionKey(
                    topLevelArtifactContext, aspectKey
                )
            }
        }
    }
}
