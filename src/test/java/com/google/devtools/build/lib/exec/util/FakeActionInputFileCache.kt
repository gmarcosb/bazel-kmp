// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec.util


import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.actions.ActionInput
import java.lang.String
import kotlin.UnsupportedOperationException
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** A fake implementation of the [InputMetadataProvider] interface.  */
class FakeActionInputFileCache : InputMetadataProvider {
    private val inputs: MutableMap<ActionInput, FileArtifactValue?> = HashMap<ActionInput, FileArtifactValue?>()
    private val treeArtifacts: MutableMap<ActionInput?, TreeArtifactValue?> =
        HashMap<ActionInput?, TreeArtifactValue?>()
    private val runfilesInputs: MutableMap<ActionInput?, RunfilesArtifactValue?> =
        HashMap<ActionInput?, RunfilesArtifactValue?>()
    private val filesets: MutableMap<Artifact?, FilesetOutputTree?> = HashMap<Artifact?, FilesetOutputTree?>()
    private val runfilesTrees: MutableList<RunfilesTree?> = ArrayList<RunfilesTree?>()

    fun put(artifact: ActionInput?, metadata: FileArtifactValue?) {
        inputs.put(artifact, metadata)
    }

    fun putTreeArtifact(actionInput: ActionInput?, treeArtifactValue: TreeArtifactValue?) {
        treeArtifacts.put(actionInput, treeArtifactValue)
    }

    fun putRunfilesTree(runfilesTreeArtifact: ActionInput?, runfilesTree: RunfilesTree?) {
        val runfilesArtifactValue: RunfilesArtifactValue =
            RunfilesArtifactValue(
                runfilesTree,
                ImmutableList.of<E?>(),
                ImmutableList.of<E?>(),
                ImmutableList.of<E?>(),
                ImmutableList.of<E?>(),
                ImmutableList.of<E?>(),
                ImmutableList.of<E?>()
            )
        runfilesInputs.put(runfilesTreeArtifact, runfilesArtifactValue)
        runfilesTrees.add(runfilesTree)
    }

    fun putFileset(fileset: Artifact?, filesetOutputTree: FilesetOutputTree?) {
        filesets.put(fileset, filesetOutputTree)
    }

    @Throws(IOException::class)
    public override fun getInputMetadataChecked(input: ActionInput): FileArtifactValue? {
        var result: FileArtifactValue? = null
        if (input is TreeFileArtifact) {
            for (entry in treeArtifacts.entries) {
                if (input.getExecPath().startsWith(entry.key.getExecPath())) {
                    result = entry.value.getChildValues().get(input)
                    break
                }
            }
        } else {
            result = inputs.get(input)
        }

        if (result === FileArtifactValue.MISSING_FILE_MARKER) {
            throw FileNotFoundException(
                String.format("File '%s' does not exist", input.getExecPathString())
            )
        }

        return result
    }

    public override fun getTreeMetadata(actionInput: ActionInput?): TreeArtifactValue? {
        return treeArtifacts.get(actionInput)
    }

    public override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
        throw UnsupportedOperationException()
    }

    public override fun getFileset(input: ActionInput?): FilesetOutputTree? {
        return filesets.get(input)
    }

    public override fun getFilesets(): ImmutableMap<Artifact?, FilesetOutputTree?> {
        return ImmutableMap.copyOf<Artifact?, FilesetOutputTree?>(filesets)
    }

    public override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
        return runfilesInputs.get(input)
    }

    public override fun getRunfilesTrees(): ImmutableList<RunfilesTree?> {
        return ImmutableList.copyOf<RunfilesTree?>(runfilesTrees)
    }

    public override fun getInput(execPath: PathFragment): ActionInput? {
        for (i in inputs.keys) {
            if (i.getExecPath().equals(execPath)) {
                return i
            }
        }

        for (e in treeArtifacts.entries) {
            if (!execPath.startsWith(e.key.getExecPath())) {
                continue
            }

            for (c in e.value.getChildValues().keySet()) {
                if (c.getExecPath().equals(execPath)) {
                    return c
                }
            }
        }

        return null
    }

    val allTreeArtifacts: ImmutableMap<ActionInput, TreeArtifactValue>
        get() = ImmutableMap.copyOf<ActionInput?, TreeArtifactValue?>(treeArtifacts)
}
