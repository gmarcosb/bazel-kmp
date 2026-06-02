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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Action

/**
 * The SkyFunction for [ActionTemplateExpansionValue].
 * 
 * 
 * Given an action template, this function resolves its input TreeArtifact, then expands the
 * action template into a list of actions using the expanded [TreeFileArtifact]s under the
 * input TreeArtifact.
 */
class ActionTemplateExpansionFunction @com.google.common.annotations.VisibleForTesting internal constructor(
    actionKeyContext: ActionKeyContext?
) : SkyFunction {
    private val actionKeyContext: ActionKeyContext?

    init {
        this.actionKeyContext = actionKeyContext
    }

    @Throws(ActionTemplateExpansionFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: ActionTemplateExpansionKey = skyKey.argument() as ActionTemplateExpansionKey
        val value: ActionLookupValue? = env.getValue(key.getActionLookupKey()) as ActionLookupValue?
        if (value == null) {
            // Because of the phase boundary separating analysis and execution, all needed
            // ActionLookupValues must have already been evaluated, so a missing ActionLookupValue is
            // unexpected. However, we tolerate this case.
            BugReport.sendBugReport(java.lang.IllegalStateException("Unexpected absent value for " + key))
            return null
        }
        val actionTemplate: ActionTemplate<*> = value.getActionTemplate(key.getActionIndex())

        val inputKeys: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
            com.google.common.collect.ImmutableList.builder<SkyKey?>().addAll(actionTemplate.getInputTreeArtifacts())

        // Following b/143205147, we unwrap the top layer of the NestedSet and evaluate the first layer
        // of the NestedSet as direct Artifact(s) and transitive NestedSet(s).
        if (!actionTemplate.getInputs().isEmpty()) {
            for (leaf in actionTemplate.getInputs().getLeaves()) {
                inputKeys.add(Artifact.key(leaf))
            }
            for (nonLeaf in actionTemplate.getInputs().getNonLeaves()) {
                inputKeys.add(ArtifactNestedSetKey.create(nonLeaf))
            }
        }

        val result: SkyframeLookupResult = env.getValuesAndExceptions(inputKeys.build())

        // Input TreeArtifact is not ready yet.
        if (env.valuesMissing()) {
            return null
        }
        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata>
        try {
            val inputTreeFileArtifacts: com.google.common.collect.ImmutableList.Builder<TreeFileArtifact?> =
                com.google.common.collect.ImmutableList.builder<TreeFileArtifact?>()
            for (inputTreeArtifact in actionTemplate.getInputTreeArtifacts()) {
                val treeArtifactValue: TreeArtifactValue? =
                    result.getOrThrow<E?>(inputTreeArtifact, ActionExecutionException::class.java) as TreeArtifactValue?
                // b/507424770#comment10: To handle the case of a wrongly bubbled up exception causing a
                // null value, we return null here so that we don't crash with an NPE.
                if (treeArtifactValue == null) {
                    return null
                }
                inputTreeFileArtifacts.addAll(treeArtifactValue.getChildren())
            }
            // Expand the action template using the list of expanded input TreeFileArtifacts.
            // TODO(rduan): Add a check to verify the inputs of expanded actions are subsets of inputs
            // of the ActionTemplate.
            actions =
                generateAndValidateActionsFromTemplate(
                    actionTemplate, inputTreeFileArtifacts.build(), key, env.getListener()
                )
        } catch (e: ActionExecutionException) {
            env.getListener()
                .handle(
                    Event.error(
                        actionTemplate.getOwner().getLocation(),
                        actionTemplate.describe() + " failed: " + e.getMessage()
                    )
                )
            throw ActionTemplateExpansionFunctionException(
                AlreadyReportedActionExecutionException(e)
            )
        } catch (e: ActionConflictException) {
            e.reportTo(env.getListener())
            throw ActionTemplateExpansionFunctionException(e)
        }
        try {
            checkActionAndArtifactConflicts(actions, key)
        } catch (e: ActionConflictException) {
            e.reportTo(env.getListener())
            throw ActionTemplateExpansionFunctionException(e)
        } catch (e: Actions.ArtifactGeneratedByOtherRuleException) {
            throw java.lang.IllegalStateException(
                ("Actions generated by template "
                        + actionTemplate.describe()
                        + " did not all output tree file artifacts belonging to the correct output tree"
                        + " artifact + ("
                        + skyKey
                        + ")"),
                e
            )
        }

        return ActionTemplateExpansionValue(actions)
    }

    /** Exception thrown by [ActionTemplateExpansionFunction].  */
    private class ActionTemplateExpansionFunctionException : SkyFunctionException {
        internal constructor(e: ActionConflictException?) : super(e, Transience.PERSISTENT)

        internal constructor(e: ActionExecutionException?) : super(e, Transience.PERSISTENT)
    }

    @Throws(
        ActionConflictException::class,
        java.lang.InterruptedException::class,
        Actions.ArtifactGeneratedByOtherRuleException::class
    )
    private fun checkActionAndArtifactConflicts(
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata>, key: ActionTemplateExpansionKey?
    ) {
        Actions.assignOwnersAndThrowIfConflict(actionKeyContext, actions, key)
        val artifactPrefixConflictMap: MutableMap<ActionAnalysisMetadata?, ActionConflictException?> =
            findArtifactPrefixConflicts(getMapForConsistencyCheck(actions))

        if (!artifactPrefixConflictMap.isEmpty()) {
            throw artifactPrefixConflictMap.values().iterator().next()
        }
    }

    private class MapBasedImmutableActionGraph(generatingActions: MutableMap<Artifact?, ActionAnalysisMetadata?>) :
        ActionGraph {
        private val generatingActions: MutableMap<Artifact?, ActionAnalysisMetadata?>

        init {
            this.generatingActions =
                com.google.common.collect.ImmutableMap.copyOf<Artifact?, ActionAnalysisMetadata?>(generatingActions)
        }

        public override fun getGeneratingAction(artifact: Artifact?): ActionAnalysisMetadata? {
            return generatingActions.get(artifact)
        }
    }

    companion object {
        @Throws(ActionConflictException::class, ActionExecutionException::class, java.lang.InterruptedException::class)
        private fun generateAndValidateActionsFromTemplate(
            actionTemplate: ActionTemplate<*>,
            inputTreeFileArtifacts: com.google.common.collect.ImmutableList<TreeFileArtifact?>?,
            key: ActionTemplateExpansionKey?,
            eventHandler: EventHandler?
        ): com.google.common.collect.ImmutableList<ActionAnalysisMetadata> {
            val outputs: MutableCollection<Artifact> = actionTemplate.getOutputs()
            for (output in outputs) {
                com.google.common.base.Preconditions.checkState(
                    output.isTreeArtifact(),
                    "%s declares an output which is not a tree artifact: %s",
                    actionTemplate,
                    output
                )
            }
            val actions: com.google.common.collect.ImmutableList<out Action> =
                actionTemplate.generateActionsForInputArtifacts(inputTreeFileArtifacts, key, eventHandler)
            for (action in actions) {
                for (output in action.getOutputs()) {
                    com.google.common.base.Preconditions.checkState(
                        output.getArtifactOwner().equals(key),
                        "%s generated an action with an output owned by the wrong owner %s not %s (%s)",
                        actionTemplate,
                        output.getArtifactOwner(),
                        key,
                        action
                    )
                    com.google.common.base.Preconditions.checkState(
                        output.hasParent(),
                        "%s generated an action which outputs a non-TreeFileArtifact %s (%s)",
                        actionTemplate,
                        output,
                        action
                    )
                    val outputTree: SpecialArtifact? =
                        if (output.getParent().isSubTreeArtifact())
                            output.getParent().getParent()
                        else
                            output.getParent()
                    com.google.common.base.Preconditions.checkState(
                        outputs.contains(outputTree),
                        "%s generated an action with an output %s under an undeclared tree not in %s (%s)",
                        actionTemplate,
                        output,
                        outputs,
                        action
                    )
                }
            }
            return com.google.common.collect.ImmutableList.< E > copyOf < E >(actions) // Just a cast, no copy performed.
        }

        private fun getMapForConsistencyCheck(
            actions: MutableList<out ActionAnalysisMetadata>
        ): com.google.common.collect.ImmutableMap<Artifact?, ActionAnalysisMetadata?> {
            if (actions.isEmpty()) {
                return com.google.common.collect.ImmutableMap.of<Artifact?, ActionAnalysisMetadata?>()
            }
            val result: HashMap<Artifact?, ActionAnalysisMetadata?> =
                com.google.common.collect.Maps.newHashMapWithExpectedSize<Artifact?, ActionAnalysisMetadata?>(
                    actions.size() * actions.get(
                        0
                    ).getOutputs().size()
                )
            for (action in actions) {
                for (output in action.getOutputs()) {
                    result.put(output, action)
                }
            }
            return com.google.common.collect.ImmutableMap.copyOf<Artifact?, ActionAnalysisMetadata?>(result)
        }

        /**
         * Finds Artifact prefix conflicts between generated artifacts. An artifact prefix conflict
         * happens if one action generates an artifact whose path is a prefix of another artifact's path.
         * Those two artifacts cannot exist simultaneously in the output tree.
         * 
         * @param generatingActions a map between generated artifacts and their associated generating
         * actions.
         * @return a map between actions that generated the conflicting artifacts and their associated
         * [ActionConflictException].
         */
        private fun findArtifactPrefixConflicts(
            generatingActions: MutableMap<Artifact?, ActionAnalysisMetadata?>
        ): MutableMap<ActionAnalysisMetadata?, ActionConflictException?> {
            return Actions.findArtifactPrefixConflicts(
                MapBasedImmutableActionGraph(generatingActions), generatingActions.keySet()
            )
        }
    }
}
