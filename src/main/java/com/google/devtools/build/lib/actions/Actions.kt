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

import com.google.devtools.build.lib.vfs.PathFragment

/** Utility class for actions.  */
object Actions {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    private val PATH_ESCAPER: com.google.common.escape.Escaper = com.google.common.escape.Escapers.builder()
        .addEscape('_', "_U")
        .addEscape('/', "_S")
        .addEscape('\\', "_B")
        .addEscape(':', "_C")
        .addEscape('@', "_A")
        .build()

    /**
     * Determines whether the given action needs to depend on the build ID.
     * 
     * 
     * Such actions are not shareable across servers.
     */
    fun dependsOnBuildId(action: ActionAnalysisMetadata?): Boolean {
        // Volatile build actions may need to execute even if none of their known inputs have changed.
        // Depending on the build ID ensures that these actions have a chance to execute.
        // SkyframeAwareActions do not need to depend on the build ID because their volatility is due to
        // their dependence on Skyframe nodes that are not captured in the action cache. Any changes to
        // those nodes will cause this action to be rerun, so a build ID dependency is unnecessary.
        if (action !is com.google.devtools.build.lib.actions.Action) {
            return false
        }
        if (action is NotifyOnActionCacheHit) {
            return true
        }
        return (action as com.google.devtools.build.lib.actions.Action).isVolatile()
    }

    /**
     * Checks if the two actions are equivalent. This method exists to support sharing actions between
     * configured targets for cases where there is no canonical target that could own the action. In
     * the action graph construction this case shows up as two actions generating the same output
     * file.
     * 
     * 
     * This method implements an equivalence relationship across actions, based on the action
     * class, the key, and the list of inputs and outputs.
     */
    @Throws(java.lang.InterruptedException::class)
    fun canBeShared(
        actionKeyContext: ActionKeyContext?, a: ActionAnalysisMetadata, b: ActionAnalysisMetadata
    ): Boolean {
        if (!(a.getMnemonic() == b.getMnemonic() // Non-Actions cannot be shared.
                    && a is com.google.devtools.build.lib.actions.Action
                    && b is com.google.devtools.build.lib.actions.Action
                    && (a.getKey(actionKeyContext,  /* inputMetadataProvider= */null)
                    == b.getKey(actionKeyContext,  /* inputMetadataProvider= */null)))
        ) {
            return false
        }
        // Uses a standard comparison technique for shareable actions.
        if (a.isShareable() && b.isShareable()) {
            return com.google.devtools.build.lib.actions.Actions.artifactsEqualWithoutOwner(
                a.getMandatoryInputs().toList(), b.getMandatoryInputs().toList()
            )
                    && com.google.devtools.build.lib.actions.Actions.artifactsEqualWithoutOwner(
                a.getOutputs(),
                b.getOutputs()
            )
        }

        // If this is reached, at least one action is not shareable. If the actions differ on this, they
        // cannot be shared.
        if (a.isShareable() || b.isShareable()) {
            return false
        }

        // If the artifacts are in fact equal (with owners), these are aliases of the same action and
        // not in conflict with each other. This can occur under remote analysis. Without remote
        // analysis, this won't be reached because the actions would have reference equality. The
        // MapBasedActionGraph doesn't consider actions that are the same object instance for conflicts.
        return a.getMandatoryInputs().toList().equals(b.getMandatoryInputs().toList())
                && com.google.common.collect.Iterables.elementsEqual(a.getOutputs(), b.getOutputs())
    }

    /**
     * Checks whether provided actions are equivalent and adds a log line in case we may be overly
     * permissive in the result. Returned result is the same as for [ ][.canBeShared].
     * 
     * 
     * TODO(b/160181927): Remove the logging once we move shared actions detection to execution
     * phase.
     */
    @Throws(java.lang.InterruptedException::class)
    fun canBeSharedLogForPotentialFalsePositives(
        actionKeyContext: ActionKeyContext?,
        actionA: ActionAnalysisMetadata,
        actionB: ActionAnalysisMetadata
    ): Boolean {
        if (!com.google.devtools.build.lib.actions.Actions.canBeShared(actionKeyContext, actionA, actionB)) {
            return false
        }
        val treeArtifactInput: java.util.Optional<Artifact?> =
            actionA.getMandatoryInputs().toList().stream().filter({ obj: Artifact? -> obj.isTreeArtifact() })
                .findFirst()
        treeArtifactInput.ifPresent(
            java.util.function.Consumer { treeArtifact: Artifact? ->
                com.google.devtools.build.lib.actions.Actions.logger.atInfo().atMostEvery(5, TimeUnit.MINUTES).log(
                    ("Shared action: %s has a tree artifact input: %s -- shared actions"
                            + " detection is overly permissive in this case and may allow"
                            + " sharing of different actions"),
                    actionA, treeArtifact
                )
            })
        return true
    }

    private fun artifactsEqualWithoutOwner(
        collection1: MutableCollection<Artifact>, collection2: MutableCollection<Artifact>
    ): Boolean {
        if (collection1.size() != collection2.size()) {
            return false
        }
        val iterator1: MutableIterator<Artifact> = collection1.iterator()
        val iterator2: MutableIterator<Artifact> = collection2.iterator()
        while (iterator1.hasNext()) {
            val artifact1: Artifact = iterator1.next()
            val artifact2: Artifact = iterator2.next()
            if (!artifact1.equalsWithoutOwner(artifact2)) {
                return false
            }
        }
        return true
    }

    /**
     * Assigns generating action keys to artifacts, and finds action conflicts. An action conflict
     * happens if two actions generate the same output artifact. Shared actions are not allowed. See
     * [.canBeShared] for details.
     * 
     * @param actions a list of actions to check for action conflict.
     * @throws ActionConflictException iff there are two actions generate the same output
     */
    @Throws(
        ActionConflictException::class,
        java.lang.InterruptedException::class,
        ArtifactGeneratedByOtherRuleException::class
    )
    fun assignOwnersAndThrowIfConflict(
        actionKeyContext: ActionKeyContext?,
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata>,
        actionLookupKey: ActionLookupKey
    ) {
        com.google.devtools.build.lib.actions.Actions.assignOwnersAndThrowIfConflictMaybeToleratingSharedActions(
            actionKeyContext, actions, actionLookupKey,  /* allowSharedAction= */false
        )
    }

    /**
     * Assigns generating action keys to artifacts and finds action conflicts. An action conflict
     * happens if two actions generate the same output artifact. Shared actions are tolerated. See
     * [.canBeShared] for details. Should be called by a configured target/aspect on the actions
     * it owns. Should not be used for "global" checks of multiple configured targets: use [ ][.findArtifactPrefixConflicts] for that.
     * 
     * @param actions a list of actions to check for action conflicts, all generated by the same
     * configured target/aspect.
     * @throws ActionConflictException iff there are two unshareable actions generating the same
     * output
     */
    @Throws(
        ActionConflictException::class,
        java.lang.InterruptedException::class,
        ArtifactGeneratedByOtherRuleException::class
    )
    fun assignOwnersAndThrowIfConflictToleratingSharedActions(
        actionKeyContext: ActionKeyContext?,
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata>,
        actionLookupKey: ActionLookupKey
    ) {
        com.google.devtools.build.lib.actions.Actions.assignOwnersAndThrowIfConflictMaybeToleratingSharedActions(
            actionKeyContext, actions, actionLookupKey,  /* allowSharedAction= */true
        )
    }

    @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
    private fun verifyGeneratingActionKeys(
        output: DerivedArtifact,
        otherKey: ActionLookupData,
        allowSharedAction: Boolean,
        actionKeyContext: ActionKeyContext?,
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata>
    ) {
        val firstKey: ActionLookupData = output.getGeneratingActionKey()
        com.google.common.base.Preconditions.checkState(
            firstKey.getActionLookupKey() == otherKey.getActionLookupKey(),
            "Mismatched lookup keys? %s %s %s",
            output,
            firstKey,
            otherKey
        )
        val actionIndex: Int = firstKey.getActionIndex()
        val otherIndex: Int = otherKey.getActionIndex()
        if (actionIndex != otherIndex
            && (!allowSharedAction
                    || !com.google.devtools.build.lib.actions.Actions.canBeSharedLogForPotentialFalsePositives(
                actionKeyContext, actions.get(actionIndex), actions.get(otherIndex)
            ))
        ) {
            throw ActionConflictException.Companion.create(
                actionKeyContext, output, actions.get(actionIndex), actions.get(otherIndex)
            )
        }
    }

    /**
     * Checks `actions` for conflicts and sets each artifact's generating action key.
     * 
     * 
     * Conflicts can happen in one of two ways: the same artifact can be the output of multiple
     * unshareable actions (or shareable actions if `allowSharedAction` is false), or two
     * artifacts with the same execPath can be the outputs of different unshareable actions.
     */
    @Throws(
        ActionConflictException::class,
        java.lang.InterruptedException::class,
        ArtifactGeneratedByOtherRuleException::class
    )
    private fun assignOwnersAndThrowIfConflictMaybeToleratingSharedActions(
        actionKeyContext: ActionKeyContext?,
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata>,
        actionLookupKey: ActionLookupKey,
        allowSharedAction: Boolean
    ) {
        val seenArtifacts: MutableMap<PathFragment?, DerivedArtifact?> = HashMap<PathFragment?, DerivedArtifact?>()
        // Loop over the actions, looking at all outputs for conflicts.
        var actionIndex = 0
        for (action in actions) {
            val generatingActionKey: ActionLookupData =
            // Runfiles tree actions have the unfortunate property that their RichArtifactData
            // contains a NestedSet of Artifacts, which we currently deem to be not worth serializing.
                // TODO: b/401575099 - See if we can factor out the NestedSet and remove this exclusion.
                if (com.google.devtools.build.lib.actions.Actions.dependsOnBuildId(action) || action is RunfilesTreeAction)
                    ActionLookupData.Companion.createUnshareable(actionLookupKey, actionIndex)
                else
                    ActionLookupData.Companion.create(actionLookupKey, actionIndex)
            for (artifact in action.getOutputs()) {
                com.google.common.base.Preconditions.checkState(
                    !artifact.isSourceArtifact(),
                    "Source in outputs: %s %s %s",
                    artifact,
                    generatingActionKey,
                    action
                )
                val output: DerivedArtifact = artifact as DerivedArtifact
                // Has an artifact with this execPath been seen before?
                val equalOutput: DerivedArtifact? = seenArtifacts.putIfAbsent(output.getExecPath(), output)
                if (equalOutput != null) {
                    // Yes: assert that its generating action and this artifact's are compatible.
                    com.google.devtools.build.lib.actions.Actions.verifyGeneratingActionKeys(
                        equalOutput, generatingActionKey, allowSharedAction, actionKeyContext, actions
                    )
                }
                // Was this output already seen, so it has a generating action key set?
                if (!output.hasGeneratingActionKey()) {
                    // Common case: artifact hasn't been seen before.
                    output.setGeneratingActionKey(generatingActionKey)
                } else {
                    val oldKey: ActionLookupData = output.getGeneratingActionKey()
                    if (actionLookupKey != oldKey.getActionLookupKey()) {
                        // The rule is claiming to produce an output that one of its inputs produced. Silly!
                        throw ArtifactGeneratedByOtherRuleException(
                            java.lang.String.format(
                                "File '%s' is produced by %s but is already generated by rule %s",
                                output.prettyPrint(),
                                action.prettyPrint(),
                                oldKey.getActionLookupKey().getLabel()
                            )
                        )
                    }
                    // Key is already set: verify that the generating action and this action are compatible.
                    com.google.devtools.build.lib.actions.Actions.verifyGeneratingActionKeys(
                        output, generatingActionKey, allowSharedAction, actionKeyContext, actions
                    )
                }
            }
            actionIndex++
        }
    }

    private val EXEC_PATH_PREFIX_COMPARATOR: java.util.Comparator<Artifact?>? =
        java.util.Comparator.comparing<Artifact?, PathFragment?>(
            java.util.function.Function { obj: Artifact? -> obj.getExecPath() },
            PathFragment.HIERARCHICAL_COMPARATOR
        )

    /**
     * Check whether two artifacts are a runfiles tree - runfiles output manifest pair.
     * 
     * 
     * This is necessary because these are exempt from the "path of one artifact cannot be a prefix
     * of another" rule. This is like this for historical reasons.
     */
    fun isRunfilesArtifactPair(runfilesTree: Artifact, runfilesManifest: Artifact): Boolean {
        if (!runfilesTree.isRunfilesTree()) {
            // The outside artifact is not a runfiles tree. No go.
            return false
        }

        // Now check whether the path of the inner artifact matches the expected path of a runfiles
        // output manifest.
        return (runfilesManifest
            .getExecPathString()
                == runfilesTree.getExecPath().getRelative("MANIFEST").getPathString())
    }

    /**
     * Finds Artifact prefix conflicts between generated artifacts. An artifact prefix conflict
     * happens if one action generates an artifact whose path is a strict prefix of another artifact's
     * path. Those two artifacts cannot exist simultaneously in the output tree.
     * 
     * @param actionGraph the [ActionGraph] to query for artifact conflicts
     * @param artifacts all generated artifacts in the build
     * @return An immutable map between actions that generated the conflicting artifacts and their
     * associated [ActionConflictException]
     */
    fun findArtifactPrefixConflicts(
        actionGraph: ActionGraph,
        artifacts: MutableCollection<Artifact?>
    ): com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?> {
        // No actions in graph -- currently happens only in tests. Special-cased because .next() call
        // below is unconditional.
        if (artifacts.isEmpty()) {
            return com.google.common.collect.ImmutableMap.of<ActionAnalysisMetadata?, ActionConflictException?>()
        }

        val artifactArray: Array<Artifact?> = artifacts.toArray<Artifact?>(arrayOfNulls<Artifact>(0))
        java.util.Arrays.parallelSort<Artifact?>(
            artifactArray,
            com.google.devtools.build.lib.actions.Actions.EXEC_PATH_PREFIX_COMPARATOR
        )

        // Keep deterministic ordering of bad actions.
        val badActions: MutableMap<ActionAnalysisMetadata?, ActionConflictException?> =
            LinkedHashMap<ActionAnalysisMetadata?, ActionConflictException?>()
        val iter: MutableIterator<Artifact> = com.google.common.collect.Iterators.forArray<Artifact?>(*artifactArray)

        // Report an error for every derived artifact which is a strict prefix of another.
        // If x << y << z (where x << y means "y starts with x"), then we only report (x,y), (x,z), but
        // not (y,z).
        var artifactJ: Artifact = iter.next()
        while (iter.hasNext()) {
            // For each comparison, we have a prefix candidate (pathI) and a suffix candidate (pathJ).
            // At the beginning of the loop, we set pathI to the last suffix candidate, since it has not
            // yet been tested as a prefix candidate, and then set pathJ to the paths coming after pathI,
            // until we come to one that does not contain pathI as a prefix. pathI is then verified not to
            // be the prefix of any path, so we start the next run of the loop.
            val artifactI: Artifact = artifactJ
            val pathI: PathFragment = artifactI.getExecPath()
            // Compare pathI to the paths coming after it.
            while (iter.hasNext()) {
                artifactJ = iter.next()
                val pathJ: PathFragment = artifactJ.getExecPath()
                // Check length first so that we only detect strict prefix conflicts. Equal exec paths are
                // possible from shared actions.
                if (pathJ.getPathString().length() > pathI.getPathString().length() && pathJ.startsWith(pathI)
                    && !com.google.devtools.build.lib.actions.Actions.isRunfilesArtifactPair(artifactI, artifactJ)
                ) {
                    val actionI: ActionAnalysisMetadata =
                        com.google.common.base.Preconditions.checkNotNull<ActionAnalysisMetadata>(
                            actionGraph.getGeneratingAction(
                                artifactI
                            ), artifactI
                        )
                    val actionJ: ActionAnalysisMetadata =
                        com.google.common.base.Preconditions.checkNotNull<ActionAnalysisMetadata>(
                            actionGraph.getGeneratingAction(
                                artifactJ
                            ), artifactJ
                        )
                    val exception: ActionConflictException =
                        ActionConflictException.Companion.createPrefix(artifactI, artifactJ, actionI, actionJ)
                    badActions.put(actionI, exception)
                    badActions.put(actionJ, exception)
                } else { // pathJ didn't have prefix pathI, so no conflict possible for pathI.
                    break
                }
            }
        }
        return com.google.common.collect.ImmutableMap.copyOf<ActionAnalysisMetadata?, ActionConflictException?>(
            badActions
        )
    }

    /**
     * Returns the escaped name for a given relative path as a string. This takes a short relative
     * path and turns it into a string suitable for use as a filename. Invalid filename characters are
     * escaped with an '_' + a single character token.
     */
    fun escapedPath(path: String): String {
        return com.google.devtools.build.lib.actions.Actions.PATH_ESCAPER.escape(path)
    }

    @Throws(java.lang.InterruptedException::class)
    fun getGeneratingAction(graph: WalkableGraph, artifact: Artifact): ActionAnalysisMetadata? {
        if (artifact.isSourceArtifact()) {
            return null
        }

        return com.google.devtools.build.lib.actions.Actions.getGeneratingAction(graph, artifact as DerivedArtifact)
    }

    @Throws(java.lang.InterruptedException::class)
    fun getGeneratingAction(
        graph: WalkableGraph, artifact: DerivedArtifact
    ): ActionAnalysisMetadata? {
        return com.google.devtools.build.lib.actions.Actions.getAction(graph, artifact.getGeneratingActionKey())
    }

    @Throws(java.lang.InterruptedException::class)
    fun getAction(
        graph: WalkableGraph, actionLookupData: ActionLookupData
    ): ActionAnalysisMetadata {
        val actionLookupKey: ActionLookupKey? = actionLookupData.getActionLookupKey()
        val actionLookupValue: ActionLookupValue = graph.getValue(actionLookupKey) as ActionLookupValue
        return actionLookupValue.getActions().get(actionLookupData.getActionIndex())
    }

    /**
     * Signals the rare case of a rule that claims to generate a file that was actually provided to it
     * by one of its dependencies.
     */
    class ArtifactGeneratedByOtherRuleException private constructor(message: String?) : java.lang.Exception(message)
}
