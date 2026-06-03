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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/** Provides [ActionInput] metadata.  */
@ThreadSafe
interface InputMetadataProvider {
    /**
     * Returns a [FileArtifactValue] for the given [ActionInput].
     * 
     * 
     * The returned [FileArtifactValue] instance corresponds to the final target of a symlink
     * and therefore must not have a type of [FileStateType.SYMLINK].
     * 
     * 
     * If [generating action][DerivedArtifact.getGeneratingActionKey] is not immediately
     * available, this method throws `MissingDepExecException` to signal that a Skyframe restart
     * is necessary to obtain the requested metadata.
     * 
     * @param input the input to retrieve the digest for
     * @return the artifact's digest or null if digest cannot be obtained (due to artifact
     * non-existence, lookup errors, or any other reason)
     * @throws InterruptedException if interrupted
     * @throws IOException if the action input cannot be digested
     * @throws MissingDepExecException if a Skyframe restart is required to provide the requested data
     */
    @Throws(java.lang.InterruptedException::class, IOException::class, MissingDepExecException::class)
    fun getInputMetadataChecked(input: ActionInput?): FileArtifactValue?

    /**
     * Returns the [TreeArtifactValue] for the given path, or `null` if no such tree
     * artifact exists.
     */
    fun getTreeMetadata(input: ActionInput?): TreeArtifactValue?

    /**
     * Returns the [TreeArtifactValue] for the tree artifact that contains the given path or
     * `null` if no such tree artifact exists.
     */
    fun getEnclosingTreeMetadata(execPath: PathFragment?): TreeArtifactValue?

    /**
     * Like [.getInputMetadata], but assumes that no Skyframe restart is needed.
     * 
     * 
     * If one is needed anyway, throws [IllegalStateException].
     */
    @Throws(IOException::class)
    fun getInputMetadata(input: ActionInput?): FileArtifactValue? {
        try {
            return getInputMetadataChecked(input)
        } catch (e: MissingDepExecException) {
            throw java.lang.IllegalStateException(e)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    /**
     * Returns the contents of a given Fileset on the inputs of the action.
     * 
     * 
     * Works both for Filesets that are directly on the inputs and those that are included in a
     * runfiles tree.
     */
    fun getFileset(input: ActionInput?): FilesetOutputTree?

    /**
     * Returns the Filesets on the inputs of the action.
     * 
     * 
     * Contains both Filesets that are directly on the inputs and those that are included in a
     * runfiles tree.
     */
    fun getFilesets(): MutableMap<Artifact?, FilesetOutputTree?>?

    /**
     * Returns the [RunfilesArtifactValue] for the given [ActionInput], which must be a
     * runfiles tree artifact.
     * 
     * @return the appropriate [RunfilesArtifactValue] or null if it's not found.
     */
    fun getRunfilesMetadata(input: ActionInput?): RunfilesArtifactValue?

    /** Returns the runfiles trees in this metadata provider.  */
    fun getRunfilesTrees(): com.google.common.collect.ImmutableList<RunfilesTree?>?

    /** Looks up an input from its exec path.  */
    fun getInput(execPath: PathFragment?): ActionInput?

    companion object {
        /**
         * Expands tree artifacts in a sequence of [ActionInput]s.
         * 
         * 
         * If `keepEmptyTreeArtifacts` is true, a tree artifact will be included in the
         * constructed list when it expands into zero file artifacts. Otherwise, only the file artifacts
         * the tree artifact expands into will be included.
         * 
         * 
         * Runfiles tree artifacts will be returned if `keepRunfilesTrees` is set, otherwise they
         * will be filtered out.
         * 
         * 
         * Non-runfiles, non-tree artifacts are returned untouched.
         */
        fun expandArtifacts(
            inputMetadataProvider: InputMetadataProvider,
            inputs: NestedSet<out ActionInput?>,
            keepEmptyTreeArtifacts: Boolean,
            keepRunfilesTrees: Boolean
        ): MutableList<ActionInput?> {
            val result: MutableList<ActionInput?> = java.util.ArrayList<ActionInput?>()
            val emptyTreeArtifacts: MutableSet<Artifact?> = TreeSet<Artifact?>()
            val treeFileArtifactParents: MutableSet<Artifact?> = HashSet<Artifact?>()
            for (input in inputs.toList()) {
                if (input !is Artifact) {
                    result.add(input)
                } else if (input.isRunfilesTree()) {
                    if (keepRunfilesTrees) {
                        result.add(input)
                    }
                } else if (input.isTreeArtifact()) {
                    val treeArtifactValue: TreeArtifactValue? = inputMetadataProvider.getTreeMetadata(input)
                    if (treeArtifactValue == null || treeArtifactValue.getChildren().isEmpty()) {
                        emptyTreeArtifacts.add(input)
                    } else {
                        result.addAll(treeArtifactValue.getChildren())
                    }
                } else {
                    result.add(input)
                    if (input.isChildOfDeclaredDirectory()) {
                        treeFileArtifactParents.add(input.getParent())
                    }
                }
            }

            if (keepEmptyTreeArtifacts) {
                emptyTreeArtifacts.removeAll(treeFileArtifactParents)
                result.addAll(emptyTreeArtifacts)
            }
            return result
        }
    }
}
