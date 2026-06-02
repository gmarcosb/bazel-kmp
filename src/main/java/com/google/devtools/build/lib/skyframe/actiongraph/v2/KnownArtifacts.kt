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

/** Cache for Artifacts in the action graph.  */
class KnownArtifacts internal constructor(aqueryOutputHandler: AqueryOutputHandler?) :
    BaseCache<Artifact?, AnalysisProtosV2.Artifact?>(aqueryOutputHandler) {
    private val knownPathFragments: KnownPathFragments

    init {
        knownPathFragments = KnownPathFragments(aqueryOutputHandler)
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun createProto(artifact: Artifact, id: Int): AnalysisProtosV2.Artifact {
        val artifactProtoBuilder: AnalysisProtosV2.Artifact.Builder =
            AnalysisProtosV2.Artifact.newBuilder()
                .setId(id)
                .setIsTreeArtifact(artifact.isTreeArtifact())

        val pathFragmentId = knownPathFragments.dataToIdAndStreamOutputProto(artifact.getExecPath())
        return artifactProtoBuilder.setPathFragmentId(pathFragmentId).build()
    }

    @Throws(IOException::class)
    override fun toOutput(artifactProto: AnalysisProtosV2.Artifact?) {
        aqueryOutputHandler.outputArtifact(artifactProto)
    }
}
