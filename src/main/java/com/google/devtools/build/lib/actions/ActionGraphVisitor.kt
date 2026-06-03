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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata
import com.google.devtools.build.lib.actions.ActionGraph
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.lib.actions.BipartiteVisitor

/**
 * An abstract visitor for the action graph.  Specializes [BipartiteVisitor] for artifacts and
 * actions, and takes care of visiting the complete transitive closure.
 */
abstract class ActionGraphVisitor(actionGraph: ActionGraph) : BipartiteVisitor<ActionAnalysisMetadata?, Artifact?>() {
    private val actionGraph: ActionGraph

    init {
        this.actionGraph = actionGraph
    }

    /**
     * Called for all artifacts in the visitation.  Hook for subclasses.
     * 
     * @param artifact
     */
    protected fun visitArtifact(artifact: Artifact?) {}

    /**
     * Called for all actions in the visitation. Hook for subclasses.
     * 
     * @param action
     */
    @Throws(java.lang.InterruptedException::class)
    protected open fun visitAction(action: ActionAnalysisMetadata?) {
    }

    /**
     * Whether the given action should be visited. If this returns false, the visitation stops here,
     * so the dependencies of this action are also not visited.
     * 
     * @param action
     */
    protected fun shouldVisit(action: ActionAnalysisMetadata?): Boolean {
        return true
    }

    /**
     * Whether the given artifact should be visited. If this returns false, the visitation stops here,
     * so dependencies of this artifact (if it is a generated one) are also not visited.
     * 
     * @param artifact
     */
    protected fun shouldVisit(artifact: Artifact?): Boolean {
        return true
    }

    @Suppress("unused")
    protected fun visitArtifacts(artifacts: Iterable<Artifact?>) {
        for (artifact in artifacts) {
            visitArtifact(artifact)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    override fun white(artifact: Artifact?) {
        val action: ActionAnalysisMetadata? = actionGraph.getGeneratingAction(artifact)
        visitArtifact(artifact)
        if (action != null && shouldVisit(action)) {
            visitBlackNode(action)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    override fun black(action: ActionAnalysisMetadata) {
        visitAction(action)
        for (input in action.getInputs().toList()) {
            if (shouldVisit(input)) {
                visitWhiteNode(input)
            }
        }
    }
}
