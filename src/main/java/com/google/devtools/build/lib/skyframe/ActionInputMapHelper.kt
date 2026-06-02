// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionInputMap

/** Static utilities for working with action inputs.  */
internal object ActionInputMapHelper {
    /** Adds a value obtained by an Artifact SkyValue lookup to the action input map.  */
    fun addToMap(
        inputMap: ActionInputMap,
        key: Artifact,
        value: SkyValue,
        consumer: MetadataConsumerForMetrics
    ) {
        when (value) {
            -> {
                expandTreeArtifactAndPopulateArtifactData(key, treeArtifactValue, inputMap)
                consumer.accumulate(treeArtifactValue)
            }

            -> {
                // Actions resulting from the expansion of an ActionTemplate consume only one of the files
                // in a tree artifact. However, the input prefetcher and the Linux sandbox require access to
                // the tree metadata to determine the prefetch location of a tree artifact materialized as a
                // symlink to another (cf. TreeArtifactValue#getResolvedPath()).
                if (key.isChildOfDeclaredDirectory()) {
                    val treeArtifact: SpecialArtifact? = key.getParent()
                    val treeArtifactValue: TreeArtifactValue? =
                        actionExecutionValue.getTreeArtifactValue(treeArtifact)
                    expandTreeArtifactAndPopulateArtifactData(treeArtifact, treeArtifactValue, inputMap)
                    consumer.accumulate(treeArtifactValue)
                }
                if (key.isFileset()) {
                    val metadata: FileArtifactValue? = actionExecutionValue.getExistingFileArtifactValue(key)
                    inputMap.put(key, metadata)
                    val filesetOutput: FilesetOutputTree? =
                        actionExecutionValue.getRichArtifactData() as FilesetOutputTree?
                    inputMap.putFileset(key, filesetOutput)
                    consumer.accumulate(filesetOutput)
                } else if (key.isRunfilesTree()) {
                    val runfilesArtifactValue: RunfilesArtifactValue =
                        actionExecutionValue.getRichArtifactData() as RunfilesArtifactValue
                    // Note: we don't expand the .runfiles/MANIFEST file into the inputs. The reason for that
                    // being that the MANIFEST file contains absolute paths that don't work with remote
                    // execution.
                    // Instead, the way the SpawnInputExpander expands runfiles is via the Runfiles class
                    // which contains all artifacts in the runfiles tree minus the MANIFEST file.
                    runfilesArtifactValue.forEachFile(
                        { artifact, metadata ->
                            inputMap.put(artifact, metadata)
                            consumer.accumulate(metadata)
                        })
                    runfilesArtifactValue.forEachTree(
                        { treeArtifact, metadata ->
                            expandTreeArtifactAndPopulateArtifactData(treeArtifact, metadata, inputMap)
                            consumer.accumulate(metadata)
                        })

                    runfilesArtifactValue.forEachFileset(
                        { fileset, filesetOutputTree ->
                            // NOTE: We don't call inputMap.put(fileset, <appropriate metadata> here.
                            // We should, but filesets inside runfiles don't have that computed Ideally, that
                            // single FileArtifactValue representing the full Fileset would be part of
                            // FilesetOutputTree. Alas, that's not the case today.
                            inputMap.putFileset(fileset, filesetOutputTree)
                            consumer.accumulate(filesetOutputTree)
                        })
                    // This is used for two purposes:
                    // - To collect the RunfilesTree objects which the execution strategies need to expand the
                    //   runfiles trees
                    // - The action cache checker may want the digest of the aggregated runfiles tree
                    inputMap.putRunfilesMetadata(key, runfilesArtifactValue)
                } else {
                    val metadata: FileArtifactValue? = actionExecutionValue.getExistingFileArtifactValue(key)
                    inputMap.put(key, metadata)
                    consumer.accumulate(metadata)
                }
            }

            -> {
                inputMap.put(key, fileArtifactValue)
                consumer.accumulate(fileArtifactValue)
            }

            null -> throw java.lang.IllegalStateException("Unexpected value " + value)
        }
    }

    private fun expandTreeArtifactAndPopulateArtifactData(
        treeArtifact: Artifact?,
        value: TreeArtifactValue,
        inputMap: ActionInputMap
    ) {
        inputMap.putTreeArtifact(treeArtifact, value)
        if (value.getArchivedRepresentation().isEmpty()) {
            return
        }

        val archivedRepresentation: ArchivedRepresentation = value.getArchivedRepresentation().get()
        inputMap.put(
            archivedRepresentation.archivedTreeFileArtifact,
            archivedRepresentation.archivedFileValue
        )
    }
}
