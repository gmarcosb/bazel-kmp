// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.actiongraph.v2

import com.google.devtools.build.lib.actions.Artifact

/** Cache for NestedSets in the action graph.  */
class KnownNestedSets internal constructor(
    aqueryOutputHandler: AqueryOutputHandler?,
    private val knownArtifacts: KnownArtifacts
) : BaseCache<Any?, DepSetOfFiles?>(aqueryOutputHandler) {
    override fun transformToKey(nestedSet: Any): Any {
        return (nestedSet as NestedSet).toNode()
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun createProto(nestedSetObject: Any?, id: Int): DepSetOfFiles {
        val nestedSet: NestedSet<*> = nestedSetObject as NestedSet
        val depSetBuilder: DepSetOfFiles.Builder = DepSetOfFiles.newBuilder().setId(id)

        // Some malformed NestedSets have duplicate non-leaf child subsets. This does not add any
        // meaningful info and sometimes even corrupt the proto3 output. More context: b/186193294.
        val visited: MutableSet<Node?> = HashSet<Node?>()
        for (succ in nestedSet.getNonLeaves()) {
            if (visited.add(succ.toNode())) {
                depSetBuilder.addTransitiveDepSetIds(this.dataToIdAndStreamOutputProto(succ))
            }
        }
        for (elem in nestedSet.getLeaves()) {
            depSetBuilder.addDirectArtifactIds(
                knownArtifacts.dataToIdAndStreamOutputProto(elem as Artifact?)
            )
        }
        return depSetBuilder.build()
    }

    @Throws(IOException::class)
    override fun toOutput(depSetOfFilesProto: DepSetOfFiles?) {
        aqueryOutputHandler.outputDepSetOfFiles(depSetOfFilesProto)
    }
}
