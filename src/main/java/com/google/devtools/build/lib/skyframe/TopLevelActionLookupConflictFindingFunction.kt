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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * Checks to see if any artifacts to be built by this [ActionLookupKey] transitively depend on
 * actions from an [ActionLookupValue] that has an action in conflict with another. If so,
 * none of this key's artifacts will be built.
 */
internal class TopLevelActionLookupConflictFindingFunction : SkyFunction {
    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val key = skyKey as Key
        val valueAndArtifactsToBuild: com.google.devtools.build.lib.util.Pair<ConfiguredObjectValue?, TopLevelArtifactHelper.ArtifactsToBuild?>? =
            CompletionFunction.Companion.getValueAndArtifactsToBuild<ConfiguredObjectValue?>(key, env)
        if (env.valuesMissing()) {
            return null
        }
        return if (GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissingMaybeWithExceptions(
                env,
                ActionLookupConflictFindingFunction.Companion.convertArtifacts(
                    valueAndArtifactsToBuild.second.getAllArtifacts()
                )
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
            )
        )
            null
        else
            ActionLookupConflictFindingValue.INSTANCE
    }

    override fun extractTag(skyKey: SkyKey): String? {
        return Label.print((skyKey as Key).actionLookupKey().getLabel())
    }

    @AutoValue
    internal abstract class Key : TopLevelActionLookupKeyWrapper {
        public override fun functionName(): SkyFunctionName {
            return SkyFunctions.TOP_LEVEL_ACTION_LOOKUP_CONFLICT_FINDING
        }

        companion object {
            fun create(
                actionLookupKey: ActionLookupKey?, topLevelArtifactContext: TopLevelArtifactContext?
            ): Key {
                return AutoValue_TopLevelActionLookupConflictFindingFunction_Key(
                    actionLookupKey, topLevelArtifactContext
                )
            }
        }
    }

    companion object {
        fun keys(
            keys: Iterable<ActionLookupKey?>, topLevelArtifactContext: TopLevelArtifactContext?
        ): Iterable<Key?> {
            return com.google.common.collect.Iterables.transform<ActionLookupKey?, Key?>(
                keys,
                com.google.common.base.Function { k: ActionLookupKey? ->
                    com.google.devtools.build.lib.skyframe.TopLevelActionLookupConflictFindingFunction.Key.Companion.create(
                        k,
                        topLevelArtifactContext
                    )
                })
        }
    }
}
