// Copyright 2019 The Bazel Authors. All rights reserved.
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

/** A [InputMetadataProvider] backed by static data  */
class StaticInputMetadataProvider(inputToMetadata: MutableMap<out ActionInput?, FileArtifactValue?>) :
    InputMetadataProvider {
    private val inputToMetadata: com.google.common.collect.ImmutableMap<ActionInput?, FileArtifactValue?>
    private val execPathToInput: com.google.common.collect.ImmutableMap<PathFragment?, ActionInput?>

    init {
        this.inputToMetadata =
            com.google.common.collect.ImmutableMap.copyOf<ActionInput?, FileArtifactValue?>(inputToMetadata)
        this.execPathToInput = constructExecPathToInputMap(inputToMetadata.keys)
    }

    override fun getInputMetadataChecked(input: ActionInput?): FileArtifactValue? {
        return inputToMetadata.get(input)
    }

    override fun getTreeMetadata(actionInput: ActionInput?): TreeArtifactValue? {
        return null
    }

    override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
        return null
    }

    override fun getFileset(input: ActionInput?): FilesetOutputTree? {
        return null
    }

    override fun getFilesets(): com.google.common.collect.ImmutableMap<Artifact?, FilesetOutputTree?> {
        return com.google.common.collect.ImmutableMap.of<Artifact?, FilesetOutputTree?>()
    }

    override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
        return null
    }

    override fun getRunfilesTrees(): com.google.common.collect.ImmutableList<RunfilesTree?> {
        return com.google.common.collect.ImmutableList.of<RunfilesTree?>()
    }

    override fun getInput(execPath: PathFragment?): ActionInput? {
        return execPathToInput.get(execPath)
    }

    companion object {
        private val EMPTY =
            StaticInputMetadataProvider(com.google.common.collect.ImmutableMap.of<ActionInput?, FileArtifactValue?>())

        fun empty(): StaticInputMetadataProvider {
            return EMPTY
        }

        private fun constructExecPathToInputMap(
            inputs: MutableCollection<out ActionInput>
        ): com.google.common.collect.ImmutableMap<PathFragment?, ActionInput?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<PathFragment?, ActionInput?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<PathFragment?, ActionInput?>(inputs.size)
            for (input in inputs) {
                builder.put(input.getExecPath(), input)
            }
            return builder.buildOrThrow()
        }
    }
}
