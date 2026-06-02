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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionInput

/**
 * A helper class for spawn strategies to turn runfiles suppliers into input mappings. This class
 * performs no I/O operations, but only rearranges the files according to how the runfiles should be
 * laid out.
 */
class SpawnInputExpander @kotlin.jvm.JvmOverloads constructor(private val expandArchivedTreeArtifacts: Boolean = true) {
    @com.google.common.annotations.VisibleForTesting
    fun addSingleRunfilesTreeToInputs(
        runfilesTree: RunfilesTree,
        inputMap: MutableMap<PathFragment?, ActionInput?>,
        inputMetadataProvider: InputMetadataProvider,
        pathMapper: PathMapper,
        baseDirectory: PathFragment
    ) {
        addSingleRunfilesTreeToInputs(
            inputMap,
            runfilesTree.getExecPath(),
            runfilesTree.getMapping(),
            inputMetadataProvider,
            pathMapper,
            baseDirectory
        )
    }

    /**
     * Gathers the mapping for a single runfiles tree into `inputMap`.
     * 
     * 
     * This should not be a public interface, it's only there to support legacy code until we
     * figure out how not to call this method (or else how to make this method more palatable)
     */
    fun addSingleRunfilesTreeToInputs(
        inputMap: MutableMap<PathFragment?, ActionInput?>,
        root: PathFragment,
        mappings: MutableMap<PathFragment?, Artifact?>,
        inputMetadataProvider: InputMetadataProvider,
        pathMapper: PathMapper,
        baseDirectory: PathFragment
    ) {
        com.google.common.base.Preconditions.checkArgument(!root.isAbsolute(), root)
        for (mapping in mappings.entrySet()) {
            val location: PathFragment = root.getRelative(mapping.getKey())
            val artifact: Artifact? = mapping.getValue()
            if (artifact == null) {
                addMapping(
                    inputMap,
                    mapForRunfiles(pathMapper, root, location),
                    VirtualActionInput.EMPTY_MARKER,
                    baseDirectory
                )
                continue
            }
            com.google.common.base.Preconditions.checkArgument(!artifact.isRunfilesTree(), artifact)
            if (artifact.isTreeArtifact()) {
                val treeArtifactValue: TreeArtifactValue = inputMetadataProvider.getTreeMetadata(artifact)
                val archivedTreeArtifact: ArchivedTreeArtifact? =
                    if (expandArchivedTreeArtifacts) null else treeArtifactValue.getArchivedArtifact()
                if (archivedTreeArtifact != null) {
                    addMapping(
                        inputMap,
                        mapForRunfiles(pathMapper, root, location),
                        archivedTreeArtifact,
                        baseDirectory
                    )
                } else if (treeArtifactValue.getChildren().isEmpty()) {
                    addMapping(inputMap, mapForRunfiles(pathMapper, root, location), artifact, baseDirectory)
                } else {
                    for (input in treeArtifactValue.getChildren()) {
                        addMapping(
                            inputMap,
                            mapForRunfiles(pathMapper, root, location)
                                .getRelative(input.getParentRelativePath()),
                            input,
                            baseDirectory
                        )
                    }
                }
            } else if (artifact.isFileset()) {
                // TODO(bazel-team): Add path mapping support for filesets.
                val filesetOutput: FilesetOutputTree = inputMetadataProvider.getFileset(artifact)
                addFilesetManifest(location, artifact, filesetOutput, inputMap, baseDirectory)
            } else {
                addMapping(inputMap, mapForRunfiles(pathMapper, root, location), artifact, baseDirectory)
            }
        }
    }

    private fun addInputs(
        inputMap: MutableMap<PathFragment?, ActionInput?>,
        inputFiles: NestedSet<out ActionInput?>?,
        inputMetadataProvider: InputMetadataProvider,
        pathMapper: PathMapper,
        baseDirectory: PathFragment
    ) {
        // Actions that accept TreeArtifacts as inputs generally expect the directory corresponding
        // to the artifact to be created, even if it is empty. We explicitly keep empty TreeArtifacts
        // here to signal consumers that they should create the directory.
        val inputs: MutableList<ActionInput> =
            InputMetadataProvider.expandArtifacts(
                inputMetadataProvider,
                inputFiles,  /* keepEmptyTreeArtifacts= */
                true,  /* keepRunfilesTrees= */
                true
            )
        for (input in inputs) {
            when (input) {
                -> {
                    val parent: Artifact = child.getParent()
                    val parentPath: PathFragment = pathMapper.map(parent.getExecPath())
                    addMapping(
                        inputMap,  // If the PathMapper was no-op for the parent, we can use the child's exec path and
                        // avoid path concatenation.
                        if (parentPath == parent.getExecPath())
                            child.getExecPath()
                        else
                            parentPath.getRelative(child.getParentRelativePath()),
                        input,
                        baseDirectory
                    )
                }

                -> addSingleRunfilesTreeToInputs(
                    inputMetadataProvider.getRunfilesMetadata(runfilesTreeArtifact).getRunfilesTree(),
                    inputMap,
                    inputMetadataProvider,
                    pathMapper,
                    baseDirectory
                )

                -> addFilesetManifest(
                    fileset.getExecPath(),
                    fileset,
                    inputMetadataProvider.getFileset(fileset),
                    inputMap,
                    baseDirectory
                )

                else -> addMapping(inputMap, pathMapper.map(input.getExecPath()), input, baseDirectory)
            }
        }
    }

    /**
     * Convert the inputs and runfiles of the given spawn to a map from exec-root relative paths to
     * [ActionInput]s. The returned map does not contain non-empty tree artifacts as they are
     * expanded to file artifacts. Tree artifacts that would expand to the empty set under the
     * provided [InputMetadataProvider] are left untouched so that their corresponding empty
     * directories can be created.
     * 
     * 
     * The returned map never contains `null` values.
     * 
     * 
     * The returned map contains all runfiles, but not the `MANIFEST`.
     */
    fun getInputMapping(
        spawn: Spawn, inputMetadataProvider: InputMetadataProvider, baseDirectory: PathFragment
    ): SortedMap<PathFragment?, ActionInput?> {
        val inputMap: TreeMap<PathFragment?, ActionInput?> = TreeMap<PathFragment?, ActionInput?>()
        addInputs(
            inputMap,
            spawn.getInputFiles(),
            inputMetadataProvider,
            spawn.getPathMapper(),
            baseDirectory
        )
        return inputMap
    }

    companion object {
        private fun addMapping(
            inputMap: MutableMap<PathFragment?, ActionInput?>,
            targetLocation: PathFragment,
            input: ActionInput?,
            baseDirectory: PathFragment
        ) {
            com.google.common.base.Preconditions.checkArgument(!targetLocation.isAbsolute(), targetLocation)
            inputMap.put(baseDirectory.getRelative(targetLocation), input)
        }

        @com.google.common.annotations.VisibleForTesting
        fun addFilesetManifests(
            filesetMappings: MutableMap<Artifact?, FilesetOutputTree?>,
            inputMap: MutableMap<PathFragment?, ActionInput?>,
            baseDirectory: PathFragment
        ) {
            for (entry in filesetMappings.entrySet()) {
                val fileset: Artifact = entry.getKey()
                addFilesetManifest(fileset.getExecPath(), fileset, entry.getValue(), inputMap, baseDirectory)
            }
        }

        private fun addFilesetManifest(
            location: PathFragment,
            filesetArtifact: Artifact,
            filesetOutput: FilesetOutputTree,
            inputMap: MutableMap<PathFragment?, ActionInput?>,
            baseDirectory: PathFragment
        ) {
            com.google.common.base.Preconditions.checkArgument(filesetArtifact.isFileset(), filesetArtifact)
            for (link in filesetOutput.symlinks()) {
                addMapping(inputMap, location.getRelative(link.name()), link.target(), baseDirectory)
            }
        }

        private fun mapForRunfiles(
            pathMapper: PathMapper, runfilesDir: PathFragment, execPath: PathFragment
        ): PathFragment {
            if (pathMapper.isNoop()) {
                return execPath
            }
            val runfilesDirName: String = runfilesDir.getBaseName()
            com.google.common.base.Preconditions.checkArgument(runfilesDirName.endsWith(".runfiles"))
            // Derive the path of the executable, apply the path mapping to it and then rederive the path
            // of the runfiles dir.
            val executable: PathFragment? =
                runfilesDir.replaceName(
                    runfilesDirName.substring(0, runfilesDirName.length() - ".runfiles".length())
                )
            return pathMapper
                .map(executable)
                .replaceName(runfilesDirName)
                .getRelative(execPath.relativeTo(runfilesDir))
        }
    }
}
