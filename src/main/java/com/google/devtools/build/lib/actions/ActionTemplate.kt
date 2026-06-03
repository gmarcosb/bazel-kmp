// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.platform.PlatformInfo

/**
 * A placeholder action that, at execution time, expands into a list of [Action]s to be
 * executed.
 * 
 * 
 * ActionTemplate is for users who want to dynamically register Actions operating on individual
 * [TreeFileArtifact] inside input and output TreeArtifacts at execution time.
 * 
 * 
 * It takes in one or more input TreeArtifacts and generates one or more output TreeArtifacts.
 * The following happens at execution time for ActionTemplate:
 * 
 * 
 *  1. Input TreeArtifact(s) are resolved.
 *  1. Given the set of [TreeFileArtifact]s inside each input TreeArtifact, generate actions
 * with outputs inside output TreeArtifact(s).
 *  1. All expanded [Action]s are executed and their output [TreeFileArtifact]s
 * collected.
 *  1. Output TreeArtifact(s) are resolved.
 * 
 * 
 * 
 * Implementations of ActionTemplate must follow the contract of this interface and also make
 * sure:
 * 
 * 
 *  1. ActionTemplate instances should be immutable and side-effect free.
 *  1. ActionTemplate inputs and outputs are supersets of the inputs and outputs of expanded
 * actions, excluding inputs discovered at execution time. This ensures the ActionTemplate can
 * properly represent the expanded actions at analysis time, and the action graph at analysis
 * time is correct. This is important because the action graph is walked in a lot of places
 * for correctness checks and build analysis.
 *  1. The outputs of expanded actions must be under one of the output TreeArtifact(s) and must
 * not have artifact or artifact path prefix conflicts.
 * 
 */
interface ActionTemplate<T : com.google.devtools.build.lib.actions.Action?> : ActionAnalysisMetadata, StarlarkValue {
    /**
     * Given a list of input TreeFileArtifacts resolved at execution time, returns a list of expanded
     * actions to be executed.
     * 
     * 
     * Each of the expanded actions' outputs must be a [TreeFileArtifact] owned by `artifactOwner` with a parent in [.getOutputs]. This is generally satisfied by calling
     * [TreeFileArtifact.createTemplateExpansionOutput].
     * 
     * @param inputTreeFileArtifacts a list of [TreeFileArtifact]s from the input
     * TreeArtifact(s). Use [TreeFileArtifact.getParent] to identify which input [     ] the tree file artifact is from.
     * @param artifactOwner the [ArtifactOwner] of the generated output [     ]s
     * @param eventHandler the [EventHandler] to report events to.
     * @return a list of expanded [Action]s to execute
     */
    @Throws(ActionConflictException::class, ActionExecutionException::class, java.lang.InterruptedException::class)
    fun generateActionsForInputArtifacts(
        inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?>?,
        artifactOwner: ActionLookupKey?,
        eventHandler: EventHandler?
    ): com.google.common.collect.ImmutableList<T?>?

    /** Returns the input TreeArtifacts.  */
    fun getInputTreeArtifacts(): com.google.common.collect.ImmutableList<SpecialArtifact?>?

    override fun getPrimaryInput(): SpecialArtifact? {
        return getInputTreeArtifacts().get(0)
    }

    override fun getPrimaryOutput(): Artifact? {
        return getOutputs().iterator().next()
    }

    override fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return com.google.common.collect.ImmutableMap.of<String?, String?>()
    }

    override fun getExecutionPlatform(): PlatformInfo? {
        return null
    }

    public override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
        printer.append(prettyPrint())
    }

    public override fun isImmutable(): Boolean {
        return true
    }

    companion object {
        /**
         * Helper method to partition/denormalize the flattened list of input [TreeFileArtifact]s
         * into a list multimap of input [SpecialArtifact] -> children [TreeFileArtifact]s.
         */
        fun getInputTreeArtifactsToChildren(inputTreeArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?>): com.google.common.collect.ImmutableListMultimap<SpecialArtifact?, TreeFileArtifact?> {
            return inputTreeArtifacts.stream()
                .collect(
                    com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap<TreeFileArtifact?, SpecialArtifact?, TreeFileArtifact?>(
                        java.util.function.Function { obj: TreeFileArtifact? -> obj.getParent() },
                        java.util.function.Function { x: TreeFileArtifact? -> x })
                )
        }
    }
}
