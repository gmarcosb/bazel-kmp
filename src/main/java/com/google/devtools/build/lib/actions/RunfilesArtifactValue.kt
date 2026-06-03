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

import com.google.devtools.build.lib.skyframe.TreeArtifactValue

/**
 * The artifacts behind a runfiles tree.
 * 
 * 
 * NB: since this class contains a nested set (through [RunfilesTree]), [ ] needs to be special-cased in `Actions.assignOwnersAndThrowIfConflictMaybeToleratingSharedActions`. The comment in that method
 * explains why.
 */
@AutoCodec
class RunfilesArtifactValue(
    runfilesTree: RunfilesTree?,
    files: com.google.common.collect.ImmutableList<Artifact?>?,
    fileValues: com.google.common.collect.ImmutableList<FileArtifactValue?>?,
    trees: com.google.common.collect.ImmutableList<Artifact?>?,
    treeValues: com.google.common.collect.ImmutableList<TreeArtifactValue?>?,
    filesets: com.google.common.collect.ImmutableList<Artifact?>?,
    filesetValues: com.google.common.collect.ImmutableList<FilesetOutputTree>?
) : RichArtifactData {
    /** A callback for consuming artifacts in a runfiles tree.  */
    fun interface RunfilesConsumer<T> {
        fun accept(artifact: Artifact?, metadata: T?)
    }

    private val metadata: FileArtifactValue
    private val runfilesTree: RunfilesTree

    // Parallel lists.
    private val files: com.google.common.collect.ImmutableList<Artifact?>
    private val fileValues: com.google.common.collect.ImmutableList<FileArtifactValue?>

    // Parallel lists.
    private val trees: com.google.common.collect.ImmutableList<Artifact?>
    private val treeValues: com.google.common.collect.ImmutableList<TreeArtifactValue?>

    // Parallel lists
    private val filesets: com.google.common.collect.ImmutableList<Artifact?>
    private val filesetValues: com.google.common.collect.ImmutableList<FilesetOutputTree>

    init {
        this.runfilesTree = com.google.common.base.Preconditions.checkNotNull<RunfilesTree>(runfilesTree)
        this.files =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<Artifact?>>(files)
        this.fileValues =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<FileArtifactValue?>>(
                fileValues
            )
        this.trees =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<Artifact?>>(trees)
        this.treeValues =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<TreeArtifactValue?>>(
                treeValues
            )
        this.filesets =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<Artifact?>>(
                filesets
            )
        this.filesetValues =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<FilesetOutputTree>>(
                filesetValues
            )
        com.google.common.base.Preconditions.checkArgument(
            files.size == fileValues.size && trees.size == treeValues.size && filesets.size == filesetValues.size,
            "Size mismatch: %s",
            this
        )

        // Compute the digest of this runfiles tree by combining its layout and the digests of every
        // artifact it references.
        this.metadata = FileArtifactValue.Companion.createRunfilesProxy(computeDigest())
    }

    private fun computeDigest(): ByteArray {
        val result: Fingerprint = Fingerprint()

        result.addInt(runfilesTree.getMapping().size)
        for (entry in runfilesTree.getMapping().entries) {
            result.addPath(entry.key)
            result.addBoolean(entry.value != null)
            if (entry.value != null) {
                result.addPath(entry.value.getExecPath())
            }
        }

        result.addInt(files.size)
        for (i in files.indices) {
            val value: FileArtifactValue =
                if (files.get(i).isConstantMetadata()) ConstantMetadataValue.Companion.INSTANCE else fileValues.get(i)
            value.addTo(result)
        }

        result.addInt(trees.size)
        for (i in trees.indices) {
            result.addBytes(treeValues.get(i).getDigest())
        }

        for (i in filesets.indices) {
            val fileset: FilesetOutputTree = filesetValues.get(i)
            fileset.addTo(result)
        }

        return result.digestAndReset()
    }

    fun withOverriddenRunfilesTree(overrideTree: RunfilesTree?): RunfilesArtifactValue {
        return RunfilesArtifactValue(
            overrideTree, files, fileValues, trees, treeValues, filesets, filesetValues
        )
    }

    /** Returns the data of the artifact for this value, as computed by the action cache checker.  */
    fun getMetadata(): FileArtifactValue {
        return metadata
    }

    /** Returns the runfiles tree this value represents.  */
    fun getRunfilesTree(): RunfilesTree {
        return runfilesTree
    }

    /**
     * Returns all artifacts in the runfiles tree this value represents. Tree artifacts and filesets
     * are included, but are not expanded.
     * 
     * 
     * This is similar to calling [RunfilesTree.getArtifacts] on the result of [ ][.getRunfilesTree], except this method additionally includes manifest files.
     */
    fun getAllArtifacts(): Iterable<Artifact?> {
        return com.google.common.collect.Iterables.concat<Artifact?>(files, trees, filesets)
    }

    /** Visits the file artifacts that this runfiles artifact expands to, together with their data.  */
    fun forEachFile(consumer: RunfilesConsumer<FileArtifactValue?>) {
        for (i in files.indices) {
            consumer.accept(files.get(i), fileValues.get(i))
        }
    }

    /** Visits the tree artifacts that this runfiles artifact expands to, together with their data.  */
    fun forEachTree(consumer: RunfilesConsumer<TreeArtifactValue?>) {
        for (i in trees.indices) {
            consumer.accept(trees.get(i), treeValues.get(i))
        }
    }

    /**
     * Visits the fileset artifacts that this runfiles artifact expands to, together with their data.
     */
    fun forEachFileset(consumer: RunfilesConsumer<FilesetOutputTree?>) {
        for (i in filesets.indices) {
            consumer.accept(filesets.get(i), filesetValues.get(i))
        }
    }

    override fun equals(o: Any?): Boolean {
        // This method, seemingly erroneously, does not check whether the runfilesTree of the two
        // objects are equivalent. This is because it's unnecessary because the layout of the runfiles
        // tree is already factored into the equality decision in two ways:
        // - Through "metadata", which takes the layout into account (see computeDigest())
        // - Through the runfiles input manifest file, which is part of the runfiles tree, which
        //   contains the exact mapping and whose digest is in "fileValues"
        if (this === o) {
            return true
        }
        if (o !is RunfilesArtifactValue) {
            return false
        }
        return metadata == o.metadata
                && files == o.files
                && fileValues == o.fileValues
                && trees == o.trees
                && treeValues == o.treeValues
                && filesets == o.filesets
                && filesetValues == o.filesetValues
    }

    override fun hashCode(): Int {
        return HashCodes.hashObjects(
            metadata, files, fileValues, trees, treeValues, filesets, filesetValues
        )
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("metadata", metadata)
            .add("files", files)
            .add("fileValues", fileValues)
            .add("trees", trees)
            .add("treeValues", treeValues)
            .add("filesets", filesets)
            .add("filesetValues", fileValues)
            .toString()
    }
}
