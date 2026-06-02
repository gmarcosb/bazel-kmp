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

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * A marker for an [com.google.devtools.build.lib.actions.ActionLookupValue] which is known to
 * be transitively error-free from action conflict issues.
 */
object ActionLookupConflictFindingValue : SkyValue {
    @kotlin.jvm.JvmField
    @SerializationConstant
    val INSTANCE: ActionLookupConflictFindingValue = ActionLookupConflictFindingValue()

    fun key(lookupKey: ActionLookupKey?): Key {
        return com.google.devtools.build.lib.skyframe.ActionLookupConflictFindingValue.Key.Companion.create(lookupKey)
    }

    fun key(artifact: Artifact): Key? {
        com.google.common.base.Preconditions.checkArgument(artifact is Artifact.DerivedArtifact, artifact)
        return key(
            (artifact as Artifact.DerivedArtifact).getGeneratingActionKey().getActionLookupKey()
        )
    }

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(arg: ActionLookupKey?) : AbstractSkyKey<ActionLookupKey?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.ACTION_LOOKUP_CONFLICT_FINDING
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.ActionLookupConflictFindingValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: ActionLookupKey?): Key {
                return com.google.devtools.build.lib.skyframe.ActionLookupConflictFindingValue.Key.Companion.interner.intern(
                    Key(arg)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.ActionLookupConflictFindingValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }
}
