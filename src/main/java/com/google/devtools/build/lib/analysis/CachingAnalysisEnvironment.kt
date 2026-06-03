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

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * The implementation of AnalysisEnvironment used for analysis. It tracks metadata for each
 * configured target, such as the errors and warnings emitted by that target. It is intended that a
 * separate instance is used for each configured target, so that these don't mix up.
 */
class CachingAnalysisEnvironment(
    artifactFactory: ArtifactFactory,
    actionKeyContext: ActionKeyContext?,
    owner: ActionLookupKey?,
    extendedSanityChecks: Boolean,
    allowAnalysisFailures: Boolean,
    errorEventListener: ExtendedEventHandler?,
    env: SkyFunction.Environment?,
    starlarkBuiltinsValue: StarlarkBuiltinsValue
) : AnalysisEnvironment {
    private val artifactFactory: ArtifactFactory
    private val owner: ActionLookupKey
    private val extendedSanityChecks: Boolean
    private val allowAnalysisFailures: Boolean
    private val actionKeyContext: ActionKeyContext?

    private var enabled = true
    private var errorEventListener: ExtendedEventHandler?
    private var skyframeEnv: SkyFunction.Environment?

    // TODO(bazel-team): Should this be nulled out by disable()? Alternatively, does disable() even
    // need to exist?
    private val starlarkBuiltinsValue: StarlarkBuiltinsValue

    /**
     * Map of artifacts to either themselves or to `Pair<Artifact, String>` if
     * --experimental_extended_sanity_checks is enabled. In the latter case, the string will contain
     * the stack trace of where the artifact was created. In the former case, we'll construct a
     * generic message in case of error.
     * 
     * 
     * The artifact is stored so that we can deduplicate artifacts created multiple times.
     */
    private var artifacts: MutableMap<Artifact, Any>? = HashMap<Artifact, Any>()

    /**
     * The list of actions registered by the configured target this analysis environment is
     * responsible for. May get cleared out at the end of the analysis of said target.
     */
    private val actions: MutableList<ActionAnalysisMetadata> = java.util.ArrayList<ActionAnalysisMetadata>()

    init {
        this.artifactFactory = artifactFactory
        this.actionKeyContext = actionKeyContext
        this.owner = com.google.common.base.Preconditions.checkNotNull<ActionLookupKey>(owner)
        this.extendedSanityChecks = extendedSanityChecks
        this.allowAnalysisFailures = allowAnalysisFailures
        this.errorEventListener = errorEventListener
        this.skyframeEnv = env
        this.starlarkBuiltinsValue = starlarkBuiltinsValue
    }

    fun disable(target: Target) {
        if (!hasErrors() && !allowAnalysisFailures) {
            verifyGeneratedArtifactHaveActions(target)
        }
        artifacts = null
        enabled = false
        errorEventListener = null
        skyframeEnv = null
    }

    /**
     * Sanity checks that all generated artifacts have a generating action.
     * @param target for error reporting
     */
    fun verifyGeneratedArtifactHaveActions(target: Target) {
        val orphanArtifacts: MutableCollection<String?> = getOrphanArtifactMap().values()
        val checkedActions: MutableList<String?>?
        if (!orphanArtifacts.isEmpty()) {
            checkedActions = com.google.common.collect.Lists.newArrayListWithCapacity<String?>(actions.size())
            for (action in actions) {
                val sb: java.lang.StringBuilder = shortDescription(action)
                for (o in action.getOutputs()) {
                    sb.append("\n    ")
                    sb.append(o.getExecPathString())
                }
                checkedActions!!.add(sb.toString())
            }
            throw java.lang.IllegalStateException(
                java.lang.String.format(
                    "%s %s : These artifacts do not have a generating action:\n%s\n"
                            + "These actions were checked:\n%s\n",
                    target.getTargetKind(),
                    target.getLabel(),
                    com.google.common.base.Joiner.on('\n').join(orphanArtifacts),
                    com.google.common.base.Joiner.on('\n').join(checkedActions)
                )
            )
        }
    }

    override fun getOrphanArtifacts(): com.google.common.collect.ImmutableSet<Artifact?> {
        return com.google.common.collect.ImmutableSet.copyOf<Artifact?>(getOrphanArtifactMap().keySet())
    }

    override fun getTreeArtifactsConflictingWithFiles(): com.google.common.collect.ImmutableSet<Artifact?> {
        var hasTreeArtifacts = false
        for (artifact in artifacts.keySet()) {
            if (artifact.isTreeArtifact()) {
                hasTreeArtifacts = true
                break
            }
        }
        if (!hasTreeArtifacts) {
            return com.google.common.collect.ImmutableSet.of<Artifact?>()
        }

        val collect: HashSet<PathFragment?> = HashSet<PathFragment?>()
        for (artifact in artifacts.keySet()) {
            if (!artifact.isSourceArtifact() && !artifact.isTreeArtifact()) {
                collect.add(artifact.getExecPath())
            }
        }

        val sameExecPathTreeArtifacts: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
            com.google.common.collect.ImmutableSet.builder<Artifact?>()
        for (artifact in artifacts.keySet()) {
            if (artifact.isTreeArtifact() && collect.contains(artifact.getExecPath())) {
                sameExecPathTreeArtifacts.add(artifact)
            }
        }

        return sameExecPathTreeArtifacts.build()
    }

    private fun getOrphanArtifactMap(): MutableMap<Artifact?, String?> {
        // Construct this set to avoid poor performance under large --runs_per_test.
        val artifactsWithActions: MutableSet<Artifact?> = HashSet<Artifact?>()
        for (action in actions) {
            // Don't bother checking that every Artifact only appears once; that test is performed
            // elsewhere (see #testNonUniqueOutputs in ActionListenerIntegrationTest).
            artifactsWithActions.addAll(action.getOutputs())
        }
        // The order of the artifacts.entrySet iteration is unspecified - we use a TreeMap here to
        // guarantee that the return value of this method is deterministic.
        val orphanArtifacts: MutableMap<Artifact?, String?> = TreeMap<Any?, Any?>(Artifact.EXEC_PATH_COMPARATOR)
        for (entry in artifacts.entrySet()) {
            val a: Artifact = entry.getKey()
            if (!a.isSourceArtifact() && !artifactsWithActions.contains(a)) {
                var value: Any = entry.getValue()
                if (value is Artifact) {
                    value = "No origin, run with --experimental_extended_sanity_checks"
                } else {
                    value = (value as Pair<*, *>).second
                }
                orphanArtifacts.put(
                    a,
                    java.lang.String.format(
                        "%s\n%s",
                        a.getExecPathString(),  // uncovered artifact
                        value
                    )
                ) // origin of creation
            }
        }
        return orphanArtifacts
    }

    override fun getEventHandler(): ExtendedEventHandler? {
        return errorEventListener
    }

    override fun getActionKeyContext(): ActionKeyContext? {
        return actionKeyContext
    }

    override fun hasErrors(): Boolean {
        com.google.common.base.Preconditions.checkState(enabled)
        return (errorEventListener as StoredEventHandler).hasErrors()
    }

    /**
     * Keeps track of artifacts. We check that all of them have an owner when the environment is
     * sealed (disable()). For performance reasons we only track the originating stacktrace when
     * running with --experimental_extended_sanity_checks.
     */
    // Cast of artifacts map's value to Pair.
    private fun dedupAndTrackArtifactAndOrigin(
        a: Artifact.DerivedArtifact?, e: Throwable?
    ): Artifact.DerivedArtifact? {
        if (artifacts!!.containsKey(a)) {
            val value: Any = artifacts!!.get(a)!!
            if (e == null) {
                return value as Artifact.DerivedArtifact?
            } else {
                return (value as Pair<Artifact.DerivedArtifact?, String?>).first
            }
        }
        if ((e != null)) {
            val sw: java.io.StringWriter = java.io.StringWriter()
            e.printStackTrace(PrintWriter(sw))
            artifacts!!.put(a, Pair.of(a, sw.toString()))
        } else {
            artifacts!!.put(a, a)
        }
        return a
    }

    override fun getDerivedArtifact(
        rootRelativePath: PathFragment?, root: ArtifactRoot?
    ): Artifact.DerivedArtifact? {
        com.google.common.base.Preconditions.checkState(enabled)
        return dedupAndTrackArtifactAndOrigin(
            artifactFactory.getDerivedArtifact(rootRelativePath, root, owner),
            if (extendedSanityChecks) Throwable() else null
        )
    }

    override fun getRunfilesArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): SpecialArtifact? {
        com.google.common.base.Preconditions.checkState(enabled)
        return dedupAndTrackArtifactAndOrigin(
            artifactFactory.getRunfilesArtifact(rootRelativePath, root, owner),
            if (extendedSanityChecks) Throwable() else null
        ) as SpecialArtifact?
    }

    override fun getTreeArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): SpecialArtifact? {
        com.google.common.base.Preconditions.checkState(enabled)
        return dedupAndTrackArtifactAndOrigin(
            artifactFactory.getTreeArtifact(rootRelativePath, root, owner),
            if (extendedSanityChecks) Throwable() else null
        ) as SpecialArtifact?
    }

    override fun getSymlinkArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): SpecialArtifact? {
        com.google.common.base.Preconditions.checkState(enabled)
        return dedupAndTrackArtifactAndOrigin(
            artifactFactory.getSymlinkArtifact(rootRelativePath, root, owner),
            if (extendedSanityChecks) Throwable() else null
        ) as SpecialArtifact?
    }

    override fun getFilesetArtifact(
        rootRelativePath: PathFragment?, root: ArtifactRoot?
    ): Artifact.DerivedArtifact? {
        com.google.common.base.Preconditions.checkState(enabled)
        return dedupAndTrackArtifactAndOrigin(
            artifactFactory.getFilesetArtifact(rootRelativePath, root, owner),
            if (extendedSanityChecks) Throwable() else null
        )
    }

    override fun getConstantMetadataArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): Artifact {
        return artifactFactory.getConstantMetadataArtifact(rootRelativePath, root, owner)
    }

    public override fun registerAction(action: ActionAnalysisMetadata?) {
        com.google.common.base.Preconditions.checkState(enabled)
        this.actions.add(com.google.common.base.Preconditions.checkNotNull<ActionAnalysisMetadata?>(action, owner))
    }

    override fun getLocalGeneratingAction(artifact: Artifact?): ActionAnalysisMetadata? {
        for (action in actions) {
            if (action.getOutputs().contains(artifact)) {
                return action
            }
        }
        return null
    }

    override fun getRegisteredActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> {
        return com.google.common.collect.ImmutableList.copyOf<ActionAnalysisMetadata?>(actions)
    }

    override fun getSkyframeEnv(): SkyFunction.Environment? {
        return skyframeEnv
    }

    override fun getStarlarkSemantics(): StarlarkSemantics {
        return starlarkBuiltinsValue.starlarkSemantics
    }

    override fun getStarlarkDefinedBuiltins(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return starlarkBuiltinsValue.exportedToJava
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getStableWorkspaceStatusArtifact(): Artifact {
        return getWorkspaceStatusValue().getStableArtifact()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getVolatileWorkspaceStatusArtifact(): Artifact {
        return getWorkspaceStatusValue().getVolatileArtifact()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun declareStampSettingDep() {
        checkNotNull(
            skyframeEnv.getValue(PrecomputedValue.STAMP_SETTING_MARKER.getKey()),
            "Precomputed value not done"
        )
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getWorkspaceStatusValue(): WorkspaceStatusValue {
        val workspaceStatusValue: WorkspaceStatusValue =
            (skyframeEnv.getValue(WorkspaceStatusValue.BUILD_INFO_KEY) as WorkspaceStatusValue)
        if (workspaceStatusValue == null) {
            throw MissingDepException("Restart due to missing build info")
        }
        return workspaceStatusValue
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getMainRepoMapping(): RepositoryMapping {
        val mainRepoMapping: RepositoryMappingValue =
            skyframeEnv.getValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue
        if (mainRepoMapping == null) {
            // This isn't expected to happen since the main repository mapping is computed before the
            // analysis phase.
            throw MissingDepException("Restart due to missing main repository mapping")
        }
        return mainRepoMapping.repositoryMapping()
    }

    public override fun getOwner(): ActionLookupKey {
        return owner
    }

    /** Thrown in case of a missing build info key.  */ // TODO(ulfjack): It would be better for this to be a checked exception, which requires updating
    // all callers to pass the exception through.
    class MissingDepException internal constructor(msg: String?) : java.lang.RuntimeException(msg)
    companion object {
        private fun shortDescription(action: ActionAnalysisMetadata?): java.lang.StringBuilder {
            if (action == null) {
                return java.lang.StringBuilder("null Action")
            }
            return java.lang.StringBuilder()
                .append(action.getClass().getName())
                .append(' ')
                .append(action.getMnemonic())
        }
    }
}
