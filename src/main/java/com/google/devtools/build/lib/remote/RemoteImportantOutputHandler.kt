// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionExecutionMetadata

/**
 * Implementation of [ImportantOutputHandler] for Build without the Bytes.
 * 
 * 
 * Any output that cannot be confirmed to still exist in remote cache results in rewinding.
 * 
 * 
 * The lifetime of an instance is a single build.
 */
class RemoteImportantOutputHandler(
    graph: WalkableGraph?,
    remoteOutputChecker: RemoteOutputChecker,
    actionInputPrefetcher: ActionInputPrefetcher,
    rewoundActionSynchronizer: RewoundActionSynchronizer?
) : ImportantOutputHandler {
    private val graph: WalkableGraph?
    private val remoteOutputChecker: RemoteOutputChecker
    private val actionInputPrefetcher: ActionInputPrefetcher
    private val rewoundActionSynchronizer: RewoundActionSynchronizer?

    init {
        this.graph = graph
        this.remoteOutputChecker = remoteOutputChecker
        this.actionInputPrefetcher = actionInputPrefetcher
        this.rewoundActionSynchronizer = rewoundActionSynchronizer
    }

    public override fun requiresHiddenOutputMetadata(): Boolean {
        // We want to process top-level runfiles in processOutputsAndGetLostArtifacts.
        return true
    }

    @Throws(ImportantOutputException::class, java.lang.InterruptedException::class)
    public override fun processOutputsAndGetLostArtifacts(
        importantOutputs: Iterable<Artifact>, metadataProvider: InputMetadataProvider
    ): LostArtifacts {
        try {
            maybeEnterProcessOutputsAndGetLostArtifacts(importantOutputs, metadataProvider).use { lock ->
                ensureToplevelArtifacts(importantOutputs, metadataProvider)
            }
        } catch (e: IOException) {
            if (e is BulkTransferException) {
                val lostArtifacts: LostArtifacts = e.getLostArtifacts(metadataProvider::getInput)
                if (!lostArtifacts.isEmpty()) {
                    return lostArtifacts
                }
            }
            throw ImportantOutputException(
                e,
                FailureDetail.newBuilder()
                    .setMessage(e.getMessage())
                    .setRemoteExecution(
                        RemoteExecution.newBuilder()
                            .setCode(RemoteExecution.Code.TOPLEVEL_OUTPUTS_DOWNLOAD_FAILURE)
                            .build()
                    )
                    .build()
            )
        }
        return LostArtifacts.EMPTY
    }

    public override fun processRunfilesAndGetLostArtifacts(
        runfilesDir: PathFragment?,
        runfiles: MutableMap<PathFragment?, Artifact?>?,
        metadataProvider: InputMetadataProvider?,
        inputManifestExtension: String?
    ): LostArtifacts? {
        throw java.lang.UnsupportedOperationException(
            "Unused in Bazel, runfiles are processed in processOutputsAndGetLostArtifacts"
        )
    }

    public override fun processTestOutputs(testOutputs: MutableCollection<com.google.devtools.build.lib.vfs.Path?>?) {
        // TODO: Either ensure that test outputs are never lost or implement a way to rewind them.
    }

    public override fun processWorkspaceStatusOutputs(
        stableOutput: com.google.devtools.build.lib.vfs.Path?,
        volatileOutput: com.google.devtools.build.lib.vfs.Path?
    ) {
    }

    public override fun processTooLargeStdoutErr(stdoutErr: com.google.devtools.build.lib.vfs.Path?) {}

    @Throws(java.lang.InterruptedException::class)
    private fun maybeEnterProcessOutputsAndGetLostArtifacts(
        importantOutputs: Iterable<Artifact>?, metadataProvider: InputMetadataProvider?
    ): SilentCloseable {
        if (rewoundActionSynchronizer
                    is RemoteRewoundActionSynchronizer
        ) {
            return rewoundActionSynchronizer.enterProcessOutputsAndGetLostArtifacts(
                importantOutputs, metadataProvider
            )
        }
        return SilentCloseable {}
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun ensureToplevelArtifacts(
        importantArtifacts: Iterable<Artifact>, metadataProvider: InputMetadataProvider
    ) {
        val futures: java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()

        for (artifact in importantArtifacts) {
            downloadArtifact(metadataProvider, artifact, futures)
        }

        for (runfileTree in metadataProvider.getRunfilesTrees()) {
            for (artifact in runfileTree.getArtifacts().toList()) {
                downloadArtifact(metadataProvider, artifact, futures)
            }
        }

        // TODO: Only wait for failed futures to complete as long as they can all be explained by
        // lost outputs.
        try {
            val unused: java.lang.Void? =
                com.google.devtools.build.lib.remote.util.Utils.mergeBulkTransfer(futures).get()
        } catch (e: ExecutionException) {
            com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(e.getCause(), IOException::class.java)
            com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                e.getCause(),
                java.lang.InterruptedException::class.java
            )
            com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            throw java.lang.IllegalStateException(e.getCause())
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun downloadArtifact(
        metadataProvider: InputMetadataProvider,
        artifact: Artifact,
        futures: MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>
    ) {
        if (!RemoteOutputChecker.Companion.mayBeRemote(artifact)) {
            return
        }

        // Metadata can be null during error bubbling, only download outputs that are already
        // generated. b/342188273
        if (artifact.isTreeArtifact()) {
            val treeArtifactValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                metadataProvider.getTreeMetadata(artifact)
            if (treeArtifactValue == null) {
                return
            }

            val filesToDownload: java.util.ArrayList<TreeFileArtifact?> =
                java.util.ArrayList<TreeFileArtifact?>(treeArtifactValue.getChildren().size())
            for (entry in treeArtifactValue.getChildValues().entrySet()) {
                if (remoteOutputChecker.shouldDownloadOutput(entry.getKey(), entry.getValue())) {
                    filesToDownload.add(entry.getKey())
                }
            }
            if (!filesToDownload.isEmpty()) {
                futures.add(
                    actionInputPrefetcher.prefetchFiles( // derivedArtifact's generating action may be an action template, which doesn't
                        // implement the required ActionExecutionMetadata.
                        getGeneratingAction(filesToDownload.getFirst()),  /* spawn= */
                        null,
                        { filesToDownload },
                        metadataProvider,
                        ActionInputPrefetcher.Priority.LOW,
                        ActionInputPrefetcher.Reason.OUTPUTS
                    )
                )
            }
        } else {
            val metadata: FileArtifactValue? = metadataProvider.getInputMetadata(artifact)
            if (metadata == null) {
                return
            }

            if (remoteOutputChecker.shouldDownloadOutput(artifact, metadata)) {
                futures.add(
                    actionInputPrefetcher.prefetchFiles(
                        if (artifact is DerivedArtifact)
                            getGeneratingAction(artifact)
                        else
                            null,  /* spawn= */
                        null,
                        { com.google.common.collect.ImmutableList.of<E?>(artifact) },
                        metadataProvider,
                        ActionInputPrefetcher.Priority.LOW,
                        ActionInputPrefetcher.Reason.OUTPUTS
                    )
                )
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getGeneratingAction(artifact: DerivedArtifact?): ActionExecutionMetadata? {
        val action: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Actions.getGeneratingAction(graph, artifact)
        com.google.common.base.Preconditions.checkState(
            action is ActionExecutionMetadata,
            "generating action for artifact %s is not an ActionExecutionMetadata, but %s",
            artifact,
            if (action != null) action.getClass() else null
        )
        return action as ActionExecutionMetadata?
    }
}
