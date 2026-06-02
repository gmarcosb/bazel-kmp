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
 * Value that stores the workspace status artifacts and their generating action. There should be
 * only one of these values in the graph at any time.
 */
// TODO(bazel-team): This seems to be superfluous now, but it cannot be removed without making
// PrecomputedValue public instead of package-private
class WorkspaceStatusValue internal constructor(workspaceStatusAction: WorkspaceStatusAction) :
    BasicActionLookupValue(com.google.common.collect.ImmutableList.of<E?>(workspaceStatusAction)) {
    private val stableArtifact: Artifact?
    private val volatileArtifact: Artifact?

    init {
        this.stableArtifact = workspaceStatusAction.stableStatus
        this.volatileArtifact = workspaceStatusAction.volatileStatus
    }

    fun getStableArtifact(): Artifact? {
        return stableArtifact
    }

    fun getVolatileArtifact(): Artifact? {
        return volatileArtifact
    }

    /** [com.google.devtools.build.skyframe.SkyKey] for [WorkspaceStatusValue].  */
    class BuildInfoKey private constructor() : ActionLookupKey {
        public override fun functionName(): SkyFunctionName {
            return SkyFunctions.BUILD_INFO
        }

        val label: Label?
            get() = null

        val configurationKey: BuildConfigurationKey?
            get() = null
    }

    companion object {
        // There should only ever be one BuildInfo value in the graph.
        @kotlin.jvm.JvmField
        @SerializationConstant
        val BUILD_INFO_KEY: BuildInfoKey = BuildInfoKey()
    }
}
