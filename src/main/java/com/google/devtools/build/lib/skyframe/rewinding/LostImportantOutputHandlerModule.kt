// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.rewinding

import com.google.devtools.build.lib.actions.ActionInput

/**
 * Installs an [ImportantOutputHandler] that allows customizing lost outputs for rewinding
 * tests.
 */
class LostImportantOutputHandlerModule(digestFn: java.util.function.BiFunction<ByteArray?, Long?, String?>?) :
    BlazeModule() {
    // This is a multiset so an output can be marked lost more than once. This is necessary to test
    // scenarios where there might be a restart in rewinding.
    private val pathsToConsiderLost: com.google.common.collect.ConcurrentHashMultiset<String?> =
        com.google.common.collect.ConcurrentHashMultiset.create<String?>()
    private val digestFn: java.util.function.BiFunction<ByteArray?, Long?, String?>
    private var outputHandlerEnabled = true

    init {
        .also { this.digestFn = it } < BiFunction
        TODO(
            """
            |Cannot convert element
            |With text:
            |Long, String>>checkNotNull(digestFn);
            """.trimMargin()
        )
    }

    /** Controls whether an [ImportantOutputHandler] will be installed.  */
    fun setOutputHandlerEnabled(enabled: Boolean) {
        outputHandlerEnabled = enabled
    }

    fun addLostOutput(execPath: String) {
        pathsToConsiderLost.add(execPath, 1)
    }

    fun verifyAllLostOutputsConsumed() {
        Truth.assertThat(pathsToConsiderLost).isEmpty()
    }

    public override fun registerActionContexts(
        registryBuilder: ModuleActionContextRegistry.Builder,
        env: CommandEnvironment?,
        buildRequest: BuildRequest?
    ) {
        if (outputHandlerEnabled) {
            registryBuilder.register(ImportantOutputHandler::class.java, createOutputHandler(env))
        }
    }

    @com.google.errorprone.annotations.ForOverride
    protected fun createOutputHandler(env: CommandEnvironment?): ImportantOutputHandler? {
        return MockImportantOutputHandler()
    }

    /**
     * Returns whether the given output should be treated as lost.
     * 
     * 
     * If `true` is returned, the given output is removed from the set of lost outputs so
     * that a subsequent call to this method with the same output will return `false`.
     */
    protected fun outputIsLost(execPath: PathFragment): Boolean {
        return pathsToConsiderLost.removeExactly(execPath.getPathString(), 1)
    }

    private inner class MockImportantOutputHandler : ImportantOutputHandler {
        public override fun processOutputsAndGetLostArtifacts(
            importantOutputs: Iterable<Artifact?>, metadataProvider: InputMetadataProvider
        ): LostArtifacts {
            return getLostOutputs(importantOutputs, metadataProvider)
        }

        public override fun processRunfilesAndGetLostArtifacts(
            runfilesDir: PathFragment?,
            runfiles: MutableMap<PathFragment?, Artifact?>,
            metadataProvider: InputMetadataProvider,
            inputManifestExtension: String?
        ): LostArtifacts {
            return getLostOutputs(runfiles.values(), metadataProvider)
        }

        public override fun processTestOutputs(testOutputs: MutableCollection<com.google.devtools.build.lib.vfs.Path?>?) {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun processWorkspaceStatusOutputs(
            stableOutput: com.google.devtools.build.lib.vfs.Path?,
            volatileOutput: com.google.devtools.build.lib.vfs.Path?
        ) {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun processTooLargeStdoutErr(stdoutErr: com.google.devtools.build.lib.vfs.Path?) {
            throw java.lang.UnsupportedOperationException()
        }

        fun getLostOutputs(
            outputs: Iterable<Artifact?>, metadataProvider: InputMetadataProvider
        ): LostArtifacts {
            val lost: com.google.common.collect.ImmutableSetMultimap.Builder<String?, ActionInput?> =
                com.google.common.collect.ImmutableSetMultimap.builder<String?, ActionInput?>()
            for (output in Companion.expand(outputs, metadataProvider)) {
                if (!outputIsLost(output.getExecPath())) {
                    continue
                }
                val metadata: FileArtifactValue
                try {
                    metadata = metadataProvider.getInputMetadata(output)
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException(e)
                }
                lost.put(digestFn.apply(metadata.getDigest(), metadata.getSize()), output)
            }
            return LostArtifacts(lost.build())
        }

        companion object {
            private fun expand(
                outputs: Iterable<Artifact?>, inputMetadataProvider: InputMetadataProvider
            ): com.google.common.collect.ImmutableList<ActionInput> {
                return com.google.common.collect.Streams.stream<Artifact?>(outputs)
                    .flatMap(java.util.function.Function { artifact: Artifact? ->
                        Companion.expand(
                            artifact,
                            inputMetadataProvider
                        )
                    })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<ActionInput?>())
            }

            private fun expand(
                output: Artifact, inputMetadataProvider: InputMetadataProvider
            ): java.util.stream.Stream<out ActionInput?>? {
                if (output.isTreeArtifact()) {
                    val treeArtifactValue: TreeArtifactValue = inputMetadataProvider.getTreeMetadata(output)
                    val archivedTreeArtifact: ArchivedTreeArtifact? = treeArtifactValue.getArchivedArtifact()
                    val children: java.util.stream.Stream<TreeFileArtifact?> = treeArtifactValue.getChildren().stream()
                    return if (archivedTreeArtifact == null)
                        children
                    else
                        java.util.stream.Stream.concat<TreeFileArtifact?>(
                            children,
                            java.util.stream.Stream.of<Any?>(archivedTreeArtifact)
                        )
                }
                if (output.isFileset()) {
                    val links: com.google.common.collect.ImmutableList<FilesetOutputSymlink?> =
                        inputMetadataProvider.getFileset(output).symlinks()
                    return links.stream().map<ActionInput?>(FilesetOutputSymlink::target)
                }
                return java.util.stream.Stream.< ActionInput > of < ActionInput ? > (output)
            }
        }
    }
}
