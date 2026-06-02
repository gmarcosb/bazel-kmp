// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionInput

/**
 * This class stores the metadata for the inputs of an action.
 * 
 * 
 * It is constructed during the preparation for the execution of the action and garbage collected
 * once the action finishes executing.
 */
class ActionInputMetadataProvider(inputArtifactData: ActionInputMap) : InputMetadataProvider {
    private val inputArtifactData: ActionInputMap

    /**
     * Supports looking up a [FilesetOutputSymlink] by the target's exec path.
     * 
     * 
     * Initialized lazily because it can consume significant memory and may never be needed, for
     * example if there is an action cache hit.
     */
    private val filesetMapping: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, FilesetOutputSymlink?>?>

    init {
        this.inputArtifactData = inputArtifactData
        this.filesetMapping =
            com.google.common.base.Suppliers.memoize<com.google.common.collect.ImmutableMap<String?, FilesetOutputSymlink?>?>(
                com.google.common.base.Supplier { createFilesetMapping(inputArtifactData.getFilesets()) })
    }

    @Throws(IOException::class)
    public override fun getInputMetadataChecked(actionInput: ActionInput?): FileArtifactValue? {
        if (actionInput !is Artifact) {
            return null
        }
        val value: FileArtifactValue? = inputArtifactData.getInputMetadataChecked(actionInput)
        if (value != null) {
            return checkExists(value, actionInput)
        }
        val filesetLink: FilesetOutputSymlink? = filesetMapping.get().get(actionInput.getExecPathString())
        if (filesetLink != null) {
            return filesetLink.metadata()
        }
        return null
    }

    public override fun getTreeMetadata(actionInput: ActionInput?): TreeArtifactValue? {
        return inputArtifactData.getTreeMetadata(actionInput)
    }

    public override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
        return inputArtifactData.getEnclosingTreeMetadata(execPath)
    }

    public override fun getFileset(input: ActionInput?): FilesetOutputTree? {
        return inputArtifactData.getFileset(input)
    }

    val filesets: MutableMap<Artifact, FilesetOutputTree>
        get() = inputArtifactData.getFilesets()

    public override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
        return inputArtifactData.getRunfilesMetadata(input)
    }

    val runfilesTrees: com.google.common.collect.ImmutableList<RunfilesTree?>
        get() = inputArtifactData.getRunfilesTrees()

    public override fun getInput(execPath: PathFragment): ActionInput? {
        val input: ActionInput? = inputArtifactData.getInput(execPath)
        if (input != null) {
            return input
        }
        val filesetLink: FilesetOutputSymlink? = filesetMapping.get().get(execPath.getPathString())
        if (filesetLink != null) {
            return filesetLink.target()
        }
        return null
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("inputArtifactDataSize", inputArtifactData.sizeForDebugging())
            .toString()
    }

    companion object {
        private fun createFilesetMapping(
            filesets: MutableMap<Artifact?, FilesetOutputTree>
        ): com.google.common.collect.ImmutableMap<String?, FilesetOutputSymlink?> {
            val filesetMap: MutableMap<String?, FilesetOutputSymlink?> = HashMap<String?, FilesetOutputSymlink?>()
            for (filesetOutput in filesets.values()) {
                for (link in filesetOutput.symlinks()) {
                    filesetMap.put(link.target().getExecPathString(), link)
                }
            }
            return com.google.common.collect.ImmutableMap.copyOf<String?, FilesetOutputSymlink?>(filesetMap)
        }

        /**
         * If `value` represents an existing file, returns it as is, otherwise throws [ ].
         */
        @Throws(FileNotFoundException::class)
        private fun checkExists(value: FileArtifactValue?, artifact: Artifact?): FileArtifactValue? {
            if (FileArtifactValue.MISSING_FILE_MARKER.equals(value)) {
                throw FileNotFoundException(artifact.toString() + " does not exist")
            }
            return com.google.common.base.Preconditions.checkNotNull<FileArtifactValue?>(value, artifact)
        }
    }
}
