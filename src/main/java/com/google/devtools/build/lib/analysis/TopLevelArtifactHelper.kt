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

import com.google.devtools.build.lib.actions.Artifact

/**
 * A small static class containing utility methods for handling the inclusion of extra top-level
 * artifacts into the build.
 */
object TopLevelArtifactHelper {
    private val MIN_LOGGING: java.time.Duration? = java.time.Duration.ofMillis(10)

    /**
     * Returns the set of all top-level output artifacts.
     * 
     * 
     * In contrast with [AnalysisResult.getArtifactsToBuild], which only returns artifacts to
     * request from the build tool, this method returns *all* artifacts produced by top-level
     * targets (including tests) and aspects.
     */
    fun findAllTopLevelArtifacts(analysisResult: com.google.devtools.build.lib.analysis.AnalysisResult): com.google.common.collect.ImmutableSet<Artifact?> {
        GoogleAutoProfilerUtils.logged("finding top level artifacts", MIN_LOGGING).use { ignored ->
            val artifacts: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.builder<Artifact?>()
            artifacts.addAll(analysisResult.getArtifactsToBuild())

            val ctx: TopLevelArtifactContext? = analysisResult.getTopLevelContext()
            val visited: MutableSet<NestedSet.Node?> = HashSet<NestedSet.Node?>()

            for (provider in com.google.common.collect.Iterables.concat<Any>(
                analysisResult.getTargetsToBuild(), analysisResult.getAspectsMap().values()
            )) {
                for (group in getAllArtifactsToBuild(provider, ctx).getAllArtifactsByOutputGroup().values()) {
                    memoizedAddAll(group.getArtifacts(), artifacts, visited)
                }
            }

            if (analysisResult.getTargetsToTest() != null) {
                for (testTarget in analysisResult.getTargetsToTest()) {
                    artifacts.addAll(TestProvider.Companion.getTestStatusArtifacts(testTarget))
                }
            }
            return artifacts.build()
        }
    }

    private fun memoizedAddAll(
        current: NestedSet<Artifact?>,
        artifacts: com.google.common.collect.ImmutableSet.Builder<Artifact?>,
        visited: MutableSet<NestedSet.Node?>
    ) {
        if (!visited.add(current.toNode())) {
            return
        }
        artifacts.addAll(current.getLeaves())
        for (child in current.getNonLeaves()) {
            memoizedAddAll(child, artifacts, visited)
        }
    }

    /**
     * Returns all artifacts to build if this target is requested as a top-level target. The resulting
     * set includes the temps and either the files to compile, if `context.compileOnly() == true`, or the files to run.
     * 
     * 
     * Calls to this method should generally return quickly; however, the runfiles computation can
     * be lazy, in which case it can be expensive on the first call. Subsequent calls may or may not
     * return the same `Iterable` instance.
     */
    fun getAllArtifactsToBuild(
        target: ProviderCollection, context: TopLevelArtifactContext
    ): ArtifactsToBuild {
        return getAllArtifactsToBuild(OutputGroupInfo.Companion.get(target), getFilesToBuild(target), context)
    }

    fun getAllArtifactsToBuild(
        outputGroupInfo: OutputGroupInfo?,
        filesToBuild: NestedSet<Artifact?>?,
        context: TopLevelArtifactContext
    ): ArtifactsToBuild {
        val allOutputGroups: com.google.common.collect.ImmutableMap.Builder<String?, ArtifactsInOutputGroup?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<K?, V?>(context.outputGroups().size())
        var allOutputGroupsImportant = true
        for (outputGroup in context.outputGroups()) {
            val results: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()

            if (outputGroup == OutputGroupInfo.Companion.DEFAULT && filesToBuild != null) {
                results.addTransitive(filesToBuild)
            }

            if (outputGroupInfo != null) {
                results.addTransitive(outputGroupInfo.getOutputGroup(outputGroup))
            }

            // Ignore output groups that have no artifacts.
            if (results.isEmpty()) {
                continue
            }

            val isImportantGroup: Boolean =
                !outputGroup.startsWith(OutputGroupInfo.Companion.HIDDEN_OUTPUT_GROUP_PREFIX)

            allOutputGroupsImportant = allOutputGroupsImportant and isImportantGroup

            val artifacts =
                ArtifactsInOutputGroup(isImportantGroup,  /*incomplete=*/false, results.build())

            allOutputGroups.put(outputGroup, artifacts)
        }

        return ArtifactsToBuild(
            allOutputGroups.buildOrThrow(),  /*allOutputGroupsImportant=*/allOutputGroupsImportant
        )
    }

    /**
     * Returns files to build directly from [FileProvider] or from `files` under [ ] provider.
     */
    private fun getFilesToBuild(target: ProviderCollection): NestedSet<Artifact?>? {
        if (target.getProvider(FileProvider::class.java) != null) {
            return target.getProvider(FileProvider::class.java).getFilesToBuild()
        } else if (target.get(DefaultInfo.Companion.PROVIDER.getKey()) != null) {
            val defaultInfo: DefaultInfo = target.get(DefaultInfo.Companion.PROVIDER.getKey()) as DefaultInfo
            if (defaultInfo.getFiles() != null) {
                try {
                    return defaultInfo.getFiles().getSet(Artifact::class.java)
                } catch (e: TypeException) {
                    throw java.lang.IllegalStateException("Error getting 'files' field of 'DefaultInfo'", e)
                }
            }
        }
        return null
    }

    /**
     * Returns false if the build outputs provided by the target should never be shown to users.
     * 
     * 
     * Always returns false for hidden rules and source file targets.
     */
    fun shouldConsiderForDisplay(configuredTarget: CqueryNode?): Boolean {
        // TODO(bazel-team): this is quite ugly. Add a marker provider for this check.
        if (configuredTarget is InputFileConfiguredTarget) {
            // Suppress display of source files (because we do no work to build them).
            return false
        }
        if (configuredTarget is RuleConfiguredTarget) {
            if (configuredTarget.getRuleClassString().contains("$")) {
                // Suppress display of hidden rules
                return false
            }
        }
        return true
    }

    /**
     * Returns true if the given artifact should be shown to users as a build output.
     * 
     * 
     * Always returns false for runfiles tree and source artifacts.
     */
    fun shouldDisplay(artifact: Artifact): Boolean {
        return !artifact.isSourceArtifact() && !artifact.isRunfilesTree()
    }

    /** Set of [Artifact]s in an output group.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    class ArtifactsInOutputGroup private constructor(
        private val important: Boolean,
        private val incomplete: Boolean,
        artifacts: NestedSet<Artifact?>?
    ) {
        private val artifacts: NestedSet<Artifact?>

        init {
            this.artifacts = com.google.common.base.Preconditions.checkNotNull<NestedSet<Artifact?>>(artifacts)
        }

        fun getArtifacts(): NestedSet<Artifact?> {
            return artifacts
        }

        /** Returns `true` if the user should know about this output group.  */
        fun areImportant(): Boolean {
            return important
        }

        fun isIncomplete(): Boolean {
            return incomplete
        }
    }

    /**
     * The set of artifacts to build.
     * 
     * 
     * There are two kinds: the ones that the user cares about (e.g. files to build) and the ones
     * they don't (e.g. baseline coverage artifacts). The latter type doesn't get reported on various
     * outputs, e.g. on the console output listing the output artifacts of targets on the command
     * line.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    class ArtifactsToBuild private constructor(
        artifacts: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>?,
        allOutputGroupsImportant: Boolean
    ) {
        private val artifacts: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>? = null
        private val allOutputGroupsImportant: Boolean

        init {
            TODO(
                """
                |Cannot convert element
                |With text:
                |this.artifacts = <ImmutableMap<String, ArtifactsInOutputGroup>>checkNotNull(artifacts);
                """.trimMargin()
            )
            this.allOutputGroupsImportant = allOutputGroupsImportant
        }

        /** Returns the artifacts that the user should know about.  */
        fun getImportantArtifacts(): NestedSet<Artifact?> {
            val builder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
            for (artifactsInOutputGroup in artifacts.values()) {
                if (artifactsInOutputGroup.areImportant()) {
                    builder.addTransitive(artifactsInOutputGroup.getArtifacts())
                }
            }
            return builder.build()
        }

        /** Returns the actual set of artifacts that need to be built.  */
        fun getAllArtifacts(): NestedSet<Artifact?> {
            val builder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
            for (artifactsInOutputGroup in artifacts.values()) {
                builder.addTransitive(artifactsInOutputGroup.getArtifacts())
            }
            return builder.build()
        }

        /**
         * Returns the set of all [Artifact]s grouped by their corresponding output group.
         * 
         * 
         * If an [Artifact] belongs to two or more output groups, it appears once in each
         * output group.
         */
        fun getAllArtifactsByOutputGroup(): com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?> {
            return artifacts
        }

        /**
         * Returns if all of the output groups returned by [.getAllArtifactsByOutputGroup] are
         * "important" - implying that all artifacts will be reported in BEP events.
         */
        fun areAllOutputGroupsImportant(): Boolean {
            return allOutputGroupsImportant
        }
    }

    /**
     * Recursive procedure filtering a target/aspect's declared `NestedSet<ArtifactsInOutputGroup>` and `NestedSet<Artifact>` to only include [ ] that were produced by successful actions.
     */
    class SuccessfulArtifactFilter(builtArtifacts: com.google.common.collect.ImmutableSet<Artifact?>) {
        private val artifactSetCanBeSkipped: MutableSet<Node?> = HashSet<Node?>()
        private val artifactSetToFilteredSet: HashMap<Node?, NestedSet<Artifact?>?> =
            HashMap<Node?, NestedSet<Artifact?>?>()

        private val builtArtifacts: com.google.common.collect.ImmutableSet<Artifact?>

        init {
            this.builtArtifacts = builtArtifacts
        }

        /**
         * Filters the declared output groups to only include artifacts that were actually built.
         * 
         * 
         * If no filtering is performed then the input NestedSet is returned directly.
         */
        fun filterArtifactsInOutputGroup(
            outputGroups: com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?>
        ): com.google.common.collect.ImmutableMap<String?, ArtifactsInOutputGroup?> {
            var leavesDirty = false
            val resultBuilder: com.google.common.collect.ImmutableMap.Builder<String?, ArtifactsInOutputGroup?> =
                com.google.common.collect.ImmutableMap.builder<String?, ArtifactsInOutputGroup?>()
            for (entry in outputGroups.entrySet()) {
                val artifactsInOutputGroup: ArtifactsInOutputGroup = entry.getValue()
                val filteredArtifactsInOutputGroup: ArtifactsInOutputGroup?
                val filteredArtifacts: NestedSet<Artifact?>? =
                    filterArtifactNestedSetToBuiltArtifacts(artifactsInOutputGroup.getArtifacts())
                if (filteredArtifacts == null) {
                    filteredArtifactsInOutputGroup = artifactsInOutputGroup
                } else {
                    filteredArtifactsInOutputGroup =
                        ArtifactsInOutputGroup(
                            artifactsInOutputGroup.areImportant(),  /*incomplete=*/true, filteredArtifacts
                        )
                    leavesDirty = true
                }
                if (!filteredArtifactsInOutputGroup.getArtifacts().isEmpty()) {
                    resultBuilder.put(entry.getKey(), filteredArtifactsInOutputGroup)
                }
            }
            if (!leavesDirty) {
                return outputGroups
            }
            return resultBuilder.buildOrThrow()
        }

        /**
         * Recursively filters the declared artifacts to only include artifacts that were actually
         * built.
         * 
         * 
         * Returns `null` if no artifacts are filtered out of the input.
         */
        private fun filterArtifactNestedSetToBuiltArtifacts(
            declaredArtifacts: NestedSet<Artifact?>
        ): NestedSet<Artifact?>? {
            val declaredArtifactsNode: Node? = declaredArtifacts.toNode()
            if (artifactSetCanBeSkipped.contains(declaredArtifactsNode)) {
                return null
            }
            val memoizedFilteredSet: NestedSet<Artifact?>? = artifactSetToFilteredSet.get(declaredArtifactsNode)
            if (memoizedFilteredSet != null) {
                return memoizedFilteredSet
            }

            // Scan the Artifact leaves for any artifact not present in builtArtifacts. If an un-built
            // artifact is found, exit the loop early, and construct the list of filteredArtifacts later.
            // This avoids unnecessary allocation in the case where all artifacts are built.
            var leavesDirty = false
            val leaves: com.google.common.collect.ImmutableList<Artifact?> = declaredArtifacts.getLeaves()
            for (a in leaves) {
                if (!builtArtifacts.contains(a)) {
                    leavesDirty = true
                    break
                }
            }
            // Unconditionally populate filteredNonLeaves by filtering each NestedSet<Artifact> non-leaf
            // successor, and set nonLeavesDirty if anything is filtered out. The filteredNonLeaves list
            // will only be used if leavesDirty is true or nonLeavesDirty is true.
            var nonLeavesDirty = false
            val nonLeaves: com.google.common.collect.ImmutableList<NestedSet<Artifact?>> =
                declaredArtifacts.getNonLeaves()
            val filteredNonLeaves: MutableList<NestedSet<Artifact?>?> =
                java.util.ArrayList<NestedSet<Artifact?>?>(nonLeaves.size())
            for (nonLeaf in nonLeaves) {
                var filteredNonLeaf: NestedSet<Artifact?>? = filterArtifactNestedSetToBuiltArtifacts(nonLeaf)
                // Null indicates no filtering happened and the input may be used as-is.
                if (filteredNonLeaf != null) {
                    nonLeavesDirty = true
                } else {
                    filteredNonLeaf = nonLeaf
                }
                if (!filteredNonLeaf.isEmpty()) {
                    filteredNonLeaves.add(filteredNonLeaf)
                }
            }
            if (!leavesDirty && !nonLeavesDirty) {
                artifactSetCanBeSkipped.add(declaredArtifactsNode)
                // Returning null indicates no filtering happened and the input may be used as-is.
                return null
            }
            val newSetBuilder: NestedSetBuilder<Artifact?> =
                NestedSetBuilder.newBuilder(declaredArtifacts.getOrder())
            for (a in leaves) {
                if (builtArtifacts.contains(a)) {
                    newSetBuilder.add(a)
                }
            }
            for (filteredNonLeaf in filteredNonLeaves) {
                newSetBuilder.addTransitive(filteredNonLeaf)
            }
            val result: NestedSet<Artifact?>? = newSetBuilder.build()
            artifactSetToFilteredSet.put(declaredArtifactsNode, result)
            return result
        }
    }
}
