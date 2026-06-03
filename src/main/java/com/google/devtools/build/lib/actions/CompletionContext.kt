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

/**
 * Container for the data one needs to resolve aggregate artifacts from events signaling the
 * completion of a target or an aspect (`TargetCompleteEvent` and `AspectCompleteEvent`).
 * 
 * 
 * This is needed because some artifacts (tree artifacts and Filesets) are in fact aggregations
 * of multiple files.
 */
class CompletionContext @com.google.common.annotations.VisibleForTesting constructor(
    pathResolver: ArtifactPathResolver?,
    importantInputMap: ActionInputMap,
    expandFilesets: Boolean
) {
    private val pathResolver: ArtifactPathResolver?

    // Only contains the metadata for 'important' artifacts of the Target/Aspect that completed. Any
    // 'unimportant' artifacts produced by internal output groups (most importantly, _validation) will
    // not be included to avoid retaining many GB on the heap. This ActionInputMap must only be
    // consulted with respect to known-important artifacts (e.g. artifacts referenced in BEP).
    private val importantInputMap: ActionInputMap
    private val expandFilesets: Boolean

    init {
        this.pathResolver = pathResolver
        this.importantInputMap = importantInputMap
        this.expandFilesets = expandFilesets
    }

    fun pathResolver(): ArtifactPathResolver? {
        return pathResolver
    }

    fun getImportantInputMap(): ActionInputMap {
        return importantInputMap
    }

    fun getFileArtifactValue(artifact: Artifact?): FileArtifactValue? {
        return importantInputMap.getInputMetadata(artifact)
    }

    /** Visits the expansion of the given artifacts.  */
    fun visitArtifacts(artifacts: Iterable<Artifact>, receiver: ArtifactReceiver) {
        for (artifact in artifacts) {
            if (artifact.isRunfilesTree()) {
                continue
            }
            if (artifact.isFileset()) {
                if (expandFilesets) {
                    val filesetOutput: FilesetOutputTree =
                        com.google.common.base.Preconditions.checkNotNull<FilesetOutputTree>(
                            importantInputMap.getFileset(artifact),
                            "missing metadata for fileset: %s",
                            artifact
                        )
                    for (link in filesetOutput.symlinks()) {
                        receiver.acceptFilesetMapping(artifact, link)
                    }
                }
            } else if (artifact.isTreeArtifact()) {
                val treeValue: TreeArtifactValue =
                    com.google.common.base.Preconditions.checkNotNull<TreeArtifactValue>(
                        importantInputMap.getTreeMetadata(artifact),
                        "missing metadata for tree artifact: %s",
                        artifact
                    )
                for (entry in treeValue.getChildValues().entrySet()) {
                    receiver.accept(entry.getKey(), entry.getValue())
                }
            } else {
                val metadata: FileArtifactValue =
                    com.google.common.base.Preconditions.checkNotNull<FileArtifactValue>(
                        importantInputMap.getInputMetadata(artifact),
                        "missing metadata for artifact: %s",
                        artifact
                    )
                receiver.accept(artifact, metadata)
            }
        }
    }

    /** A function that accepts an [Artifact].  */
    interface ArtifactReceiver {
        fun accept(artifact: Artifact?, metadata: FileArtifactValue?)

        fun acceptFilesetMapping(fileset: Artifact?, link: FilesetOutputSymlink?)
    }

    /** A factory for [ArtifactPathResolver].  */
    interface PathResolverFactory {
        fun createPathResolverForArtifactValues(actionInputMap: ActionInputMap?): ArtifactPathResolver?
    }

    companion object {
        val FAILED_COMPLETION_CTX: CompletionContext = CompletionContext(
            ArtifactPathResolver.Companion.IDENTITY, ActionInputMap(0),  /* expandFilesets= */false
        )

        fun create(
            expandFilesets: Boolean,
            importantInputMap: ActionInputMap,
            pathResolverFactory: PathResolverFactory
        ): CompletionContext {
            return CompletionContext(
                pathResolverFactory.createPathResolverForArtifactValues(importantInputMap),
                importantInputMap,
                expandFilesets
            )
        }
    }
}
