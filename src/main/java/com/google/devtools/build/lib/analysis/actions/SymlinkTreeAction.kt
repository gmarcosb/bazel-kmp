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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * Action responsible for the symlink tree creation. Used to generate runfiles and fileset symlink
 * trees.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class SymlinkTreeAction @com.google.common.annotations.VisibleForTesting constructor(
    owner: ActionOwner?,
    inputManifest: Artifact,
    runfiles: com.google.devtools.build.lib.analysis.Runfiles?,
    outputManifest: Artifact,
    repoMappingManifest: Artifact?,
    env: ActionEnvironment,
    runfileSymlinksMode: RunfileSymlinksMode,
    workspaceName: String?
) : AbstractAction(
    owner,
    computeInputs(runfileSymlinksMode, runfiles, inputManifest, repoMappingManifest),
    com.google.common.collect.ImmutableSet.of<E?>(outputManifest)
), RichDataProducingAction {
    private val inputManifest: Artifact?
    private val outputManifest: Artifact
    private val env: ActionEnvironment
    private val runfileSymlinksMode: RunfileSymlinksMode
    private val repoMappingManifest: Artifact?

    // Exactly one of these two fields is non-null.
    private val runfiles: com.google.devtools.build.lib.analysis.Runfiles?
    private val workspaceNameForFileset: String?

    /**
     * Creates SymlinkTreeAction instance.
     * 
     * @param owner action owner
     * @param config the action owners build configuration
     * @param inputManifest the input runfiles manifest
     * @param runfiles the input runfiles
     * @param outputManifest the generated symlink tree manifest (must have "MANIFEST" base name).
     * Symlink tree root will be set to the artifact's parent directory.
     * @param repoMappingManifest the repository mapping manifest
     */
    constructor(
        owner: ActionOwner?,
        config: BuildConfigurationValue,
        inputManifest: Artifact,
        runfiles: com.google.devtools.build.lib.analysis.Runfiles?,
        outputManifest: Artifact,
        repoMappingManifest: Artifact?
    ) : this(
        owner,
        inputManifest,
        runfiles,
        outputManifest,
        repoMappingManifest,
        config.getActionEnvironment(),
        config.getRunfileSymlinksMode(),
        config.getWorkspaceName()
    )

    /**
     * Creates SymlinkTreeAction instance. Prefer the constructor that takes a [ ] instance; it is less likely to require changes in the future if we add
     * more command-line flags that affect this action.
     * 
     * @param owner action owner
     * @param inputManifest the input runfiles manifest
     * @param runfiles the input runfiles
     * @param outputManifest the generated symlink tree manifest (must have "MANIFEST" base name).
     * Symlink tree root will be set to the artifact's parent directory.
     * @param repoMappingManifest the repository mapping manifest
     * @param workspaceName name of the workspace
     */
    init {
        checkArgument(outputManifest.getExecPath().getBaseName().equals("MANIFEST"), outputManifest)
        this.outputManifest = outputManifest
        this.env = env
        this.runfileSymlinksMode = runfileSymlinksMode
        this.inputManifest = inputManifest
        this.repoMappingManifest = repoMappingManifest
        if (inputManifest.isFileset()) {
            com.google.common.base.Preconditions.checkArgument(
                runfiles == null,
                "Runfiles present for fileset %s",
                inputManifest
            )
            this.runfiles = null
            this.workspaceNameForFileset = com.google.common.base.Preconditions.checkNotNull<String?>(workspaceName)
        } else {
            this.runfiles =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.analysis.Runfiles?>(
                    runfiles
                )
            this.workspaceNameForFileset = null
        }
    }

    public override fun getEnvironment(): ActionEnvironment {
        return env
    }

    fun getInputManifest(): Artifact? {
        return inputManifest
    }

    fun getRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
        return runfiles
    }

    fun getOutputManifest(): Artifact {
        return outputManifest
    }

    fun getRepoMappingManifest(): Artifact? {
        return repoMappingManifest
    }

    fun isFilesetTree(): Boolean {
        return workspaceNameForFileset != null
    }

    fun getWorkspaceNameForFileset(): String {
        return com.google.common.base.Preconditions.checkNotNull<String>(
            workspaceNameForFileset,
            "Not a fileset tree: %s",
            outputManifest
        )
    }

    fun getRunfileSymlinksMode(): RunfileSymlinksMode {
        return runfileSymlinksMode
    }

    public override fun getMnemonic(): String {
        return "SymlinkTree"
    }

    protected override fun getRawProgressMessage(): String {
        return ((if (isFilesetTree()) "Creating Fileset tree " else "Creating runfiles tree ")
                + outputManifest.getExecPath().getParentDirectory().getPathString())
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        fp.addNullableString(workspaceNameForFileset)
        fp.addInt(runfileSymlinksMode.ordinal())
        env.addTo(fp)
        // We need to ensure that the fingerprints for two different instances of this action are
        // different. Consider the hypothetical scenario where we add a second runfiles object to this
        // class, which could also be null: the sequence
        //    if (r1 != null) r1.fingerprint(fp);
        //    if (r2 != null) r2.fingerprint(fp);
        // would *not* be safe; we'd get a collision between an action that has only r1 set, and another
        // that has only r2 set. Prefixing with a boolean indicating the presence of runfiles makes it
        // safe to add more fields in the future.
        fp.addBoolean(runfiles != null)
        if (runfiles != null) {
            runfiles.fingerprint(actionKeyContext, fp,  /* digestAbsolutePaths= */true)
        }
        fp.addBoolean(repoMappingManifest != null)
        if (repoMappingManifest != null) {
            fp.addPath(repoMappingManifest.getExecPath())
        }
    }

    public override fun reconstructRichDataOnActionCacheHit(
        inputMetadataProvider: InputMetadataProvider
    ): RichArtifactData? {
        return if (getPrimaryOutput().isFileset())
            FilesetOutputTree.Companion.forward(inputMetadataProvider.getFileset(getPrimaryInput()))
        else
            null
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        actionExecutionContext
            .getContext(SymlinkTreeActionContext::class.java)
            .createSymlinks(this, actionExecutionContext)
        if (getPrimaryOutput().isFileset()) {
            actionExecutionContext.setRichArtifactData(
                FilesetOutputTree.Companion.forward(
                    actionExecutionContext.getInputMetadataProvider().getFileset(getPrimaryInput())
                )
            )
        }
        return ActionResult.EMPTY
    }

    public override fun mayInsensitivelyPropagateInputs(): Boolean {
        return true
    }

    companion object {
        private const val GUID = "7a16371c-cd4a-494d-b622-963cd89f5212"

        private fun computeInputs(
            runfileSymlinksMode: RunfileSymlinksMode?,
            runfiles: com.google.devtools.build.lib.analysis.Runfiles?,
            inputManifest: Artifact?,
            repoMappingManifest: Artifact?
        ): NestedSet<Artifact?> {
            val inputs: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
            inputs.add(inputManifest)
            // On Windows, we need to know whether the target artifact is a file or a directory in order to
            // correctly create a symlink or junction to it.
            if (runfileSymlinksMode == RunfileSymlinksMode.CREATE && runfiles != null && OS.getCurrent() === OS.WINDOWS) {
                inputs.addTransitive(runfiles.getAllArtifacts())
                if (repoMappingManifest != null) {
                    inputs.add(repoMappingManifest)
                }
            }
            return inputs.build()
        }
    }
}
