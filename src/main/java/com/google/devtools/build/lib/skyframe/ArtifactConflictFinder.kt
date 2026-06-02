// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/** An artifact conflict finder used in noskymeld mode.  */
internal object ArtifactConflictFinder {
    @kotlin.jvm.JvmField
    val ACTION_CONFLICTS: Precomputed<com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?> =
        Precomputed<com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?>("action_conflicts")

    // Action graph construction is CPU-bound.
    @kotlin.jvm.JvmField
    val NUM_JOBS: Int = java.lang.Runtime.getRuntime().availableProcessors()

    /**
     * Find conflicts between generated artifacts. There are two ways to have conflicts. First, if two
     * (unshareable) actions generate the same output artifact, this will result in an [ ]. Second, if one action generates an artifact whose path is a prefix of
     * another artifact's path, those two artifacts cannot exist simultaneously in the output tree.
     * This causes an [ActionConflictException].
     * 
     * 
     * This method must be called if a new action was added to the graph this build, so whenever a
     * new configured target was analyzed this build. It is somewhat expensive (~1s range for a medium
     * build as of 2014), so it should only be called when necessary.
     */
    @Throws(java.lang.InterruptedException::class)
    fun findAndStoreArtifactConflicts(
        actionLookupValues: Sharder<ActionLookupValue?>,
        actionCount: Int,
        actionKeyContext: ActionKeyContext?
    ): ActionConflictsAndStats {
        val temporaryBadActionMap: ConcurrentMap<ActionAnalysisMetadata?, ActionConflictException?> =
            ConcurrentHashMap<ActionAnalysisMetadata?, ActionConflictException?>()

        // Use the action count to presize - all actions have at least one output artifact.
        val actionGraph: MapBasedActionGraph = MapBasedActionGraph(actionKeyContext, actionCount)
        val artifacts: MutableList<Artifact?> = java.util.ArrayList<Artifact?>(actionCount)

        constructActionGraphAndArtifactList(
            actionGraph,
            Collections.synchronizedList<Artifact?>(artifacts),
            actionLookupValues,
            temporaryBadActionMap
        )

        val actionsWithArtifactPrefixConflict: MutableMap<ActionAnalysisMetadata?, ActionConflictException?> =
            Actions.findArtifactPrefixConflicts(actionGraph, artifacts)
        for (actionExceptionPair in actionsWithArtifactPrefixConflict.entries) {
            temporaryBadActionMap.put(actionExceptionPair.key, actionExceptionPair.value)
        }
        return ActionConflictsAndStats.Companion.create(
            com.google.common.collect.ImmutableMap.copyOf<ActionAnalysisMetadata?, ActionConflictException?>(
                temporaryBadActionMap
            ),
            actionGraph.getSize()
        )
    }

    /**
     * Simultaneously construct an action graph for all the actions in Skyframe and a map from [ ]s to their respective [Artifact]s. We do
     * this in a threadpool to save around 1.5 seconds on a mid-sized build versus a single-threaded
     * operation.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun constructActionGraphAndArtifactList(
        actionGraph: MutableActionGraph,
        artifacts: MutableList<Artifact?>,
        actionShards: Sharder<ActionLookupValue?>,
        badActionMap: ConcurrentMap<ActionAnalysisMetadata?, ActionConflictException?>
    ) {
        val executor: ExecutorService =
            Executors.newFixedThreadPool(
                NUM_JOBS,
                com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("ActionLookupValue Processor %d")
                    .build()
            )
        for (shard in actionShards) {
            executor.execute(java.lang.Runnable { actionRegistration(shard, actionGraph, artifacts, badActionMap) })
        }
        if (ExecutorUtil.interruptibleShutdown(executor)) {
            throw java.lang.InterruptedException()
        }
    }

    private fun actionRegistration(
        values: MutableList<ActionLookupValue>,
        actionGraph: MutableActionGraph,
        allArtifacts: MutableList<Artifact?>,
        badActionMap: ConcurrentMap<ActionAnalysisMetadata?, ActionConflictException?>
    ) {
        // Accumulated and added to the shared list at the end to reduce contention.
        val myArtifacts: MutableList<Artifact?> = java.util.ArrayList<Artifact?>(values.size)

        for (value in values) {
            for (action in value.getActions()) {
                try {
                    actionGraph.registerAction(action)
                } catch (e: ActionConflictException) {
                    // It may be possible that we detect a conflict for the same action more than once, if
                    // that action belongs to multiple aspect values. In this case we will harmlessly
                    // overwrite the badActionMap entry.
                    badActionMap.put(action, e)
                    // We skip the rest of the loop, and do not add the path->artifact mapping for this
                    // artifact below -- we don't need to check it since this action is already in
                    // error.
                    continue
                } catch (e: java.lang.InterruptedException) {
                    // Bail.
                    java.lang.Thread.currentThread().interrupt()
                    return
                }
                myArtifacts.addAll(action.getOutputs())
            }
        }

        allArtifacts.addAll(myArtifacts)
    }

    internal class ActionConflictsAndStats(
      conflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?,
      @kotlin.jvm.JvmField val outputArtifactCount: Int
    ) {
        val conflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?

        init {
            this.conflicts = conflicts
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?>(
                conflicts,
                "conflicts"
            )
        }

        companion object {
            fun create(
                conflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?,
                artifactCount: Int
            ): ActionConflictsAndStats {
                return ActionConflictsAndStats(conflicts, artifactCount)
            }
        }
    }
}
