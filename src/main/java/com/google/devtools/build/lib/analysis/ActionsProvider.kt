// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * This provides a view over the actions that were created during the analysis of a rule
 * (not including actions for its transitive dependencies).
 */
object ActionsProvider : BuiltinProvider<StructImpl?>(), ActionsInfoProviderApi {
    /** The ActionsProvider singleton instance.  */
    val INSTANCE: ActionsProvider = ActionsProvider()

    /** Factory method for creating instances of the Actions provider.  */
    fun create(actions: Iterable<ActionAnalysisMetadata>): StructImpl {
        val map: MutableMap<Artifact?, ActionAnalysisMetadata?> = HashMap<Artifact?, ActionAnalysisMetadata?>()
        for (action in actions) {
            for (artifact in action.getOutputs()) {
                // In the case that two actions generated the same artifact, the first wins. They
                // ought to be equal anyway.
                map.putIfAbsent(artifact, action)
            }
        }
        val fields: com.google.common.collect.ImmutableMap<String?, Any?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>("by_file", Dict.immutableCopyOf(map))
        return StarlarkInfo.create(INSTANCE, fields)
    }
}
