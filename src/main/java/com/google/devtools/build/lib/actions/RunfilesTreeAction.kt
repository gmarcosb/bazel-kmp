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

import com.google.devtools.build.lib.analysis.platform.PlatformInfo

/**
 * An action that depends on a set of inputs and creates a single output file whenever it runs. This
 * is useful for bundling up a bunch of dependencies that are shared between individual targets in
 * the action graph; for example generated header files.
 */
@Immutable
class RunfilesTreeAction(
    owner: ActionOwner?,
    runfilesTree: RunfilesTree?,
    inputs: NestedSet<Artifact?>?,
    outputs: com.google.common.collect.ImmutableSet<Artifact?>
) : com.google.devtools.build.lib.actions.AbstractAction(owner, inputs, outputs), RichDataProducingAction {
    /** The runfiles tree created by this action.  */
    private val runfilesTree: RunfilesTree?

    init {
        this.runfilesTree = runfilesTree
        com.google.common.base.Preconditions.checkArgument(
            com.google.common.collect.Iterables.getOnlyElement<Artifact?>(
                outputs
            ).isRunfilesTree(), outputs
        )
    }

    fun getRunfilesTree(): RunfilesTree? {
        return runfilesTree
    }

    @Throws(IOException::class)
    private fun createRunfilesArtifactValue(
        inputMetadataProvider: InputMetadataProvider
    ): RunfilesArtifactValue {
        val inputs: com.google.common.collect.ImmutableList<Artifact?> = getInputs().toList()
        val files: com.google.common.collect.ImmutableList.Builder<Artifact?> =
            com.google.common.collect.ImmutableList.builder<Artifact?>()
        val fileValues: com.google.common.collect.ImmutableList.Builder<FileArtifactValue?> =
            com.google.common.collect.ImmutableList.builder<FileArtifactValue?>()
        val trees: com.google.common.collect.ImmutableList.Builder<Artifact?> =
            com.google.common.collect.ImmutableList.builder<Artifact?>()
        val treeValues: com.google.common.collect.ImmutableList.Builder<TreeArtifactValue?> =
            com.google.common.collect.ImmutableList.builder<TreeArtifactValue?>()
        val filesets: com.google.common.collect.ImmutableList.Builder<Artifact?> =
            com.google.common.collect.ImmutableList.builder<Artifact?>()
        val filesetValues: com.google.common.collect.ImmutableList.Builder<FilesetOutputTree?> =
            com.google.common.collect.ImmutableList.builder<FilesetOutputTree?>()

        // Sort for better equality in RunfilesArtifactValue.
        val sortedInputs: com.google.common.collect.ImmutableList<Artifact> =
            com.google.common.collect.ImmutableList.sortedCopyOf<Artifact?>(
                Artifact.Companion.EXEC_PATH_COMPARATOR,
                inputs
            )
        for (input in sortedInputs) {
            if (input.isFileset()) {
                filesets.add(input)
                filesetValues.add(inputMetadataProvider.getFileset(input))
            } else if (input.isTreeArtifact()) {
                trees.add(input)
                treeValues.add(inputMetadataProvider.getTreeMetadata(input))
            } else {
                files.add(input)
                fileValues.add(inputMetadataProvider.getInputMetadata(input))
            }
        }

        return RunfilesArtifactValue(
            runfilesTree,
            files.build(),
            fileValues.build(),
            trees.build(),
            treeValues.build(),
            filesets.build(),
            filesetValues.build()
        )
    }

    override fun reconstructRichDataOnActionCacheHit(
        inputMetadataProvider: InputMetadataProvider
    ): RichArtifactData {
        try {
            return createRunfilesArtifactValue(inputMetadataProvider)
        } catch (e: IOException) {
            // On action cache hits, all input metadata should already be in RAM
            throw java.lang.IllegalStateException(e)
        }
    }

    override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult? {
        try {
            val runfilesArtifactValue: RunfilesArtifactValue =
                createRunfilesArtifactValue(
                    actionExecutionContext.getInputMetadataProvider()
                )
            actionExecutionContext.setRichArtifactData(runfilesArtifactValue)
        } catch (e: IOException) {
            throw java.lang.IllegalStateException(e)
        }

        return ActionResult.Companion.EMPTY
    }

    override fun prepare(
        execRoot: Path?,
        pathResolver: ArtifactPathResolver?,
        bulkDeleter: BulkDeleter?,
        cleanupArchivedArtifacts: Boolean
    ) {
        // Runfiles trees are created as a side effect of building the output manifest, not the runfiles
        // tree artifact. This method is overridden so that depending on the runfiles tree does not
        // delete the runfiles tree that's on the file system that someone decided it must be there.
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint?
    ) {
        // Only the set of inputs matters, and the dependency checker is
        // responsible for considering those.
    }

    override fun getRawProgressMessage(): String? {
        return null // this action doesn't actually do anything so let's not report it
    }

    override fun prettyPrint(): String {
        return "runfiles for " + Label.print(getOwner().getLabel())
    }

    override fun getMnemonic(): String {
        return MNEMONIC
    }

    override fun mayInsensitivelyPropagateInputs(): Boolean {
        return true
    }

    override fun getExecutionPlatform(): PlatformInfo {
        return PlatformInfo.EMPTY_PLATFORM_INFO
    }

    override fun getExecProperties(): com.google.common.collect.ImmutableMap<String?, String?> {
        // Runfiles tree actions do not execute actual actions, and therefore have no execution
        // platform.
        return com.google.common.collect.ImmutableMap.of<String?, String?>()
    }

    companion object {
        const val MNEMONIC: String = "RunfilesTree"
    }
}
