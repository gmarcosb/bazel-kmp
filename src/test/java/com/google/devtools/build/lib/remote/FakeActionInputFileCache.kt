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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Digest

/** A fake implementation of the [InputMetadataProvider] interface.  */
internal class FakeActionInputFileCache(execRoot: Path) : InputMetadataProvider {
    private val execRoot: Path
    private val cas: MutableMap<PathFragment?, FileArtifactValue?> = HashMap<PathFragment?, FileArtifactValue?>()
    private val runfilesMap: MutableMap<ActionInput?, RunfilesArtifactValue?> =
        HashMap<ActionInput?, RunfilesArtifactValue?>()
    private val trees: MutableMap<ActionInput?, TreeArtifactValue?> = HashMap<ActionInput?, TreeArtifactValue?>()
    private val runfilesTrees: MutableList<RunfilesTree?> = java.util.ArrayList<RunfilesTree?>()
    private val digestUtil: DigestUtil

    init {
        this.execRoot = execRoot
        this.digestUtil =
            DigestUtil(SyscallCache.NO_CACHE, execRoot.getFileSystem().getDigestFunction())
    }

    public override fun getInputMetadataChecked(input: ActionInput): FileArtifactValue? {
        return com.google.common.base.Preconditions.checkNotNull(
            cas.get(input.getExecPath()),
            "No metadata for input '%s' (exec path: '%s')",
            input,
            input.getExecPath()
        )
    }

    public override fun getTreeMetadata(actionInput: ActionInput?): TreeArtifactValue? {
        return trees.get(actionInput)
    }

    public override fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue? {
        throw java.lang.UnsupportedOperationException()
    }

    public override fun getFileset(input: ActionInput?): FilesetOutputTree? {
        throw java.lang.UnsupportedOperationException()
    }

    val filesets: MutableMap<Artifact, FilesetOutputTree>?
        get() {
            throw java.lang.UnsupportedOperationException()
        }

    public override fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue? {
        return runfilesMap.get(input)
    }

    public override fun getRunfilesTrees(): com.google.common.collect.ImmutableList<RunfilesTree?> {
        return com.google.common.collect.ImmutableList.copyOf<RunfilesTree?>(runfilesTrees)
    }

    public override fun getInput(execPath: PathFragment?): ActionInput? {
        throw java.lang.UnsupportedOperationException()
    }

    private fun setMetadata(input: ActionInput, metadata: FileArtifactValue?) {
        cas.put(input.getExecPath(), metadata)
    }

    fun addTreeArtifact(treeArtifact: ActionInput?, value: TreeArtifactValue?) {
        trees.put(treeArtifact, value)
    }

    fun addRunfilesTree(runfilesTreeArtifact: ActionInput?, runfilesTree: RunfilesTree?) {
        runfilesMap.put(
            runfilesTreeArtifact,
            RunfilesArtifactValue(
                runfilesTree,
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.common.collect.ImmutableList.of<E?>()
            )
        )
        runfilesTrees.add(runfilesTree)
    }

    @Throws(IOException::class)
    fun createScratchInput(input: ActionInput, content: String): Digest? {
        val inputFile: Path = execRoot.getRelative(input.getExecPath())
        inputFile.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContentAsLatin1(inputFile, content)
        val digest: Digest? = digestUtil.compute(inputFile)
        setMetadata(
            input,
            FileArtifactValue.createForNormalFile(
                DigestUtil.toBinaryDigest(digest),
                FileContentsProxy.create(inputFile.stat()),
                content.length
            )
        )
        return digest
    }

    @Throws(IOException::class)
    fun createScratchInputDirectory(input: ActionInput, content: Tree?): Digest? {
        val inputFile: Path = execRoot.getRelative(input.getExecPath())
        inputFile.createDirectoryAndParents()
        val digest: Digest? = digestUtil.compute(content)
        setMetadata(
            input, FileArtifactValue.createForDirectoryWithHash(DigestUtil.toBinaryDigest(digest))
        )
        return digest
    }

    @Throws(IOException::class)
    fun createScratchInputSymlink(input: Artifact, target: String?) {
        com.google.common.base.Preconditions.checkArgument(input.isSymlink())
        val inputFile: Path = input.getPath()
        inputFile.getParentDirectory().createDirectoryAndParents()
        inputFile.createSymbolicLink(PathFragment.create(target))
        setMetadata(input, FileArtifactValue.createForUnresolvedSymlink(input))
    }
}
