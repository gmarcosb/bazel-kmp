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
package com.google.devtools.build.lib.actions.cache

import com.google.devtools.build.lib.skyframe.TreeArtifactValue

/** Handles the metadata of the outputs of the action during its execution.  */
interface OutputMetadataStore {
    /**
     * Injects the metadata of a file.
     * 
     * 
     * This can be used to save filesystem operations when the metadata is already known.
     * 
     * 
     * [Tree artifacts][Artifact.isTreeArtifact] and their [ ][Artifact.isChildOfDeclaredDirectory] must not be passed here. Instead, they should be
     * passed to [.injectTree].
     * 
     * @param output a regular output file
     * @param metadata the file metadata
     */
    fun injectFile(output: Artifact?, metadata: FileArtifactValue?)

    /**
     * Injects the metadata of a tree artifact.
     * 
     * 
     * This can be used to save filesystem operations when the metadata is already known.
     * 
     * @param output an output directory [tree artifact][Artifact.isTreeArtifact]
     * @param tree a [TreeArtifactValue] with the metadata of the files stored in the directory
     */
    fun injectTree(output: SpecialArtifact?, tree: TreeArtifactValue?)

    /**
     * Returns a [FileArtifactValue] for the given [Artifact].
     * 
     * 
     * If the metadata of the given [Artifact] has not been injected via [.injectFile],
     * it will be computed from the filesystem. This may result in a significant amount of I/O. The
     * result will be cached for future calls to this method.
     * 
     * 
     * For artifacts of non-symlink type (i.e., [Artifact.isSymlink] returns false), the
     * returned [FileArtifactValue] corresponds to the final target of a symlink when one exists
     * in the filesystem, and therefore will not have a type of [FileStateType.SYMLINK].
     * 
     * 
     * If a stat is required to obtain the metadata, the output will first be set read-only and
     * executable by this call. This ensures that the returned metadata has an appropriate ctime,
     * which is affected by chmod. Note that this does not apply to outputs injected via [ ][.injectFile] since a stat is not required for them.
     * 
     * @param artifact the artifact to retrieve metadata for
     * @return the artifact metadata, or null the artifact is not a known output of the action
     * @throws IOException if the metadata cannot be obtained from the filesystem
     * @throws InterruptedException if the current thread is interrupted while computing the metadata
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun getOutputMetadata(artifact: Artifact?): FileArtifactValue?

    /**
     * Returns a [TreeArtifactValue] for the given [SpecialArtifact], which must be a tree
     * artifact (i.e., [SpecialArtifact.isTreeArtifact] must return true).
     * 
     * 
     * If the metadata of the given [SpecialArtifact] has not been injected via [ ][.injectTree], it will be computed from the filesystem. This may result in a significant amount
     * of I/O. The result will be cached for future calls to this method.
     * 
     * 
     * If a stat is required to obtain the metadata, the output will first be set read-only and
     * executable by this call. This ensures that the returned metadata has an appropriate ctime,
     * which is affected by chmod. Note that this does not apply to outputs injected via [ ][.injectFile] since a stat is not required for them.
     * 
     * @param treeArtifact the tree artifact to retrieve metadata for
     * @return the tree artifact metadata
     * @throws IOException if the metadata cannot be obtained from the filesystem
     * @throws InterruptedException if the current thread is interrupted while computing the metadata
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun getTreeArtifactValue(treeArtifact: SpecialArtifact?): TreeArtifactValue?

    /**
     * Marks an [Artifact] as intentionally omitted.
     * 
     * 
     * This is used as an optimization to not download *orphaned* artifacts (artifacts that
     * no action depends on) from a remote system.
     */
    fun markOmitted(output: Artifact?)

    /** Returns `true` if [.markOmitted] was called on the artifact.  */
    fun artifactOmitted(artifact: Artifact?): Boolean

    /**
     * Discards any cached metadata for the given outputs.
     * 
     * 
     * May be called if an action can make multiple attempts that are expected to create the same
     * set of output files.
     */
    fun resetOutputs(outputs: Iterable<out Artifact?>?)
}
