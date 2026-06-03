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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Action

/**
 * A bipartite graph visitor which accumulates extra actions for a target.
 */
internal class ExtraActionsVisitor(
    ruleContext: RuleContext,
    mnemonicToExtraActionMap: com.google.common.collect.Multimap<String?, ExtraActionSpec?>
) : ActionGraphVisitor(getActionGraph(ruleContext)) {
    private val ruleContext: RuleContext
    private val mnemonicToExtraActionMap: com.google.common.collect.Multimap<String?, ExtraActionSpec?>
    private val extraArtifacts: MutableList<Artifact.DerivedArtifact?>

    /** Creates a new visitor for the extra actions associated with the given target.  */
    init {
        this.ruleContext = ruleContext
        this.mnemonicToExtraActionMap = mnemonicToExtraActionMap
        extraArtifacts = com.google.common.collect.Lists.newArrayList<Artifact.DerivedArtifact?>()
    }

    @Throws(java.lang.InterruptedException::class)
    fun maybeAddExtraAction(original: ActionAnalysisMetadata?) {
        if (original is Action) {
            val extraActions: MutableCollection<ExtraActionSpec> =
                mnemonicToExtraActionMap.get(original.getMnemonic())
            if (extraActions != null) {
                for (extraAction in extraActions) {
                    extraArtifacts.addAll(extraAction.addExtraAction(ruleContext, original))
                }
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    protected override fun visitAction(action: ActionAnalysisMetadata?) {
        maybeAddExtraAction(action)
    }

    /** Retrieves the collected artifacts since this method was last called and clears the list.  */
    fun getAndResetExtraArtifacts(): com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?> {
        val collected: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?> =
            com.google.common.collect.ImmutableList.copyOf<Artifact.DerivedArtifact?>(extraArtifacts)
        extraArtifacts.clear()
        return collected
    }

    companion object {
        /** Gets an action graph wrapper for the given target through its analysis environment.  */
        private fun getActionGraph(ruleContext: RuleContext): ActionGraph {
            return object : ActionGraph() {
                public override fun getGeneratingAction(artifact: Artifact?): ActionAnalysisMetadata? {
                    return ruleContext.getAnalysisEnvironment().getLocalGeneratingAction(artifact)
                }
            }
        }
    }
}
