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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.skyframe.TreeArtifactValue

/** A [InputMetadataProvider] implementation that consults two others in a given order.  */
class DelegatingPairInputMetadataProvider(primary: InputMetadataProvider, secondary: InputMetadataProvider) :
    InputMetadataProvider {
    private val primary: InputMetadataProvider
    private val secondary: InputMetadataProvider

    init {
        this.primary = primary
        this.secondary = secondary
    }

    @Throws(java.lang.InterruptedException::class, IOException::class, MissingDepExecException::class)
    override fun getInputMetadataChecked(input: ActionInput?): FileArtifactValue? {
        val metadata: FileArtifactValue? = primary.getInputMetadata(input)
        return if ((metadata != null) && (metadata !== FileArtifactValue.MISSING_FILE_MARKER))
            metadata
        else
            secondary.getInputMetadataChecked(input)
    }

    override fun getTreeMetadata(actionInput: ActionInput?): TreeArtifactValue? {
        val metadata: TreeArtifactValue? = primary.getTreeMetadata(actionInput)
        return if (metadata != null) metadata else secondary.getTreeMetadata(actionInput)
    }

    override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
        val metadata: TreeArtifactValue? = primary.getEnclosingTreeMetadata(execPath)
        return if (metadata != null) metadata else secondary.getEnclosingTreeMetadata(execPath)
    }

    override fun getFileset(input: ActionInput?): FilesetOutputTree? {
        val result: FilesetOutputTree? = primary.getFileset(input)
        return if (result != null) result else secondary.getFileset(input)
    }

    override fun getFilesets(): MutableMap<Artifact?, FilesetOutputTree?>? {
        val first: MutableMap<Artifact?, FilesetOutputTree?> = primary.getFilesets()
        val second: MutableMap<Artifact?, FilesetOutputTree?> = secondary.getFilesets()
        if (first.isEmpty()) {
            return second
        }
        if (second.isEmpty()) {
            return first
        }

        return com.google.common.collect.ImmutableMap.builderWithExpectedSize<Artifact?, FilesetOutputTree?>(
            first.size + second.size
        )
            .putAll(first)
            .putAll(second)
            .buildKeepingLast()
    }

    override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
        val result: RunfilesArtifactValue? = primary.getRunfilesMetadata(input)
        return if (result != null) result else secondary.getRunfilesMetadata(input)
    }

    override fun getRunfilesTrees(): com.google.common.collect.ImmutableList<RunfilesTree?> {
        val result: LinkedHashSet<RunfilesTree?> = LinkedHashSet<RunfilesTree?>()
        result.addAll(primary.getRunfilesTrees())
        result.addAll(secondary.getRunfilesTrees())
        return com.google.common.collect.ImmutableList.copyOf<RunfilesTree?>(result)
    }

    override fun getInput(execPath: PathFragment?): ActionInput? {
        val input: ActionInput? = primary.getInput(execPath)
        return if (input != null) input else secondary.getInput(execPath)
    }
}
