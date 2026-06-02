// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact

/** Tracks whether an artifact was "consumed" by any action in the build.  */
class ConsumedArtifactsTracker : EphemeralCheckIfOutputConsumed {
    private val consumed: MutableSet<Artifact?> = ConcurrentHashMap.newKeySet<Artifact?>(524288)

    /**
     * This method guarantees the best estimate before the execution of the action that would generate
     * this artifact. The return value is undefined afterwards.
     */
    override fun test(artifact: Artifact?): Boolean {
        return consumed.contains(artifact)
    }

    fun unregisterOutputsAfterExecutionDone(outputs: MutableCollection<Artifact?>?) {
        consumed.removeAll(outputs)
    }

    /** Register the provided artifact as "consumed".  */
    fun registerConsumedArtifact(artifact: Artifact) {
        // We should only store the consumed status of artifacts that will later on be checked for
        // orphaned status directly. This is an optimization to keep the set smaller.
        if (!artifact.isSourceArtifact() // Source artifacts won't be orphaned.
            && !artifact.hasParent()
        ) { // Will be checked through the parent artifact.
            consumed.add(artifact)
        }
    }
}
