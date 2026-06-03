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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/** Context to be informed of top-level outputs and their runfiles.  */
interface ImportantOutputHandler : ActionContext {
    /**
     * Whether this handler requires metadata of top-level [ ][com.google.devtools.build.lib.analysis.OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX] in [.processOutputsAndGetLostArtifacts]. Notably, this includes top-level
     * runfiles trees.
     * 
     * 
     * If `false`, top-level runfiles may be handled in [ ][.processRunfilesAndGetLostArtifacts].
     */
    fun requiresHiddenOutputMetadata(): Boolean {
        return false
    }

    /**
     * Informs this handler that top-level outputs have been built.
     * 
     * 
     * The handler may verify that remotely stored outputs are still available. Returns a map from
     * digest to output for any artifacts that need to be regenerated via action rewinding.
     * 
     * @param importantOutputs top-level outputs, excluding [     ][com.google.devtools.build.lib.analysis.OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX]
     * @param metadataProvider provides metadata for artifacts in `importantOutputs` and their
     * expansions; if [.requiresHiddenOutputMetadata], additionally provides metadata for
     * artifacts in [     ][com.google.devtools.build.lib.analysis.OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX] and their expansions
     * @return any artifacts that need to be regenerated via action rewinding
     * @throws ImportantOutputException for an issue processing the outputs, not including lost
     * outputs which are reported in the returned [LostArtifacts]
     */
    @Throws(ImportantOutputException::class, java.lang.InterruptedException::class)
    fun processOutputsAndGetLostArtifacts(
        importantOutputs: Iterable<Artifact?>?, metadataProvider: InputMetadataProvider?
    ): LostArtifacts?

    /**
     * Informs this handler that the runfiles of a top-level target have been built.
     * 
     * 
     * The handler may verify that remotely stored outputs are still available. Returns a map from
     * digest to output for any artifacts that need to be regenerated via action rewinding.
     * 
     * @param runfilesDir exec path of the runfiles directory
     * @param runfiles mapping from `runfilesDir`-relative path to target artifact; values may
     * be `null` to represent an empty file (can happen with `__init__.py` files, see
     * [com.google.devtools.build.lib.rules.python.PythonUtils.GetInitPyFiles])
     * @param metadataProvider provides metadata for artifacts in `runfiles` and their
     * expansions
     * @param inputManifestExtension the file extension of the input manifest
     * @return any artifacts that need to be regenerated via action rewinding
     * @throws ImportantOutputException for an issue processing the runfiles, not including lost
     * outputs which are reported in the returned [LostArtifacts]
     */
    @Throws(ImportantOutputException::class, java.lang.InterruptedException::class)
    fun processRunfilesAndGetLostArtifacts(
        runfilesDir: PathFragment?,
        runfiles: MutableMap<PathFragment?, Artifact?>?,
        metadataProvider: InputMetadataProvider?,
        inputManifestExtension: String?
    ): LostArtifacts?

    /**
     * Informs this handler of outputs from a completed test attempt.
     * 
     * 
     * The given paths are under the exec root and are backed by an [ ][com.google.devtools.build.lib.vfs.OutputService.createActionFileSystem] if
     * applicable.
     * 
     * 
     * Test outputs should never be lost. Test actions are not shareable across servers (see [ ][Actions.dependsOnBuildId]), so outputs passed to this method come from a just-executed test
     * action.
     */
    @Throws(ImportantOutputException::class, java.lang.InterruptedException::class)
    fun processTestOutputs(testOutputs: MutableCollection<Path?>?)

    /**
     * Informs this handler of outputs from [ ].
     * 
     * 
     * The given paths are under the exec root and are backed by an [ ][com.google.devtools.build.lib.vfs.OutputService.createActionFileSystem] if
     * applicable.
     * 
     * 
     * Workspace status outputs should never be lost. [ ] is not shareable across servers
     * (see [Actions.dependsOnBuildId]), so outputs passed to this method come from a
     * just-executed action.
     */
    @Throws(ImportantOutputException::class, java.lang.InterruptedException::class)
    fun processWorkspaceStatusOutputs(stableOutput: Path?, volatileOutput: Path?)

    /**
     * Informs this handler of a stdout or stderr file that is too large to display to the console and
     * should instead be made available in file form.
     * 
     * 
     * The given paths is under the exec root and is backed by an [ ][com.google.devtools.build.lib.vfs.OutputService.createActionFileSystem] if
     * applicable.
     * 
     * 
     * Stdout and stderr files should never be lost because they are only accessed after a
     * just-executed action.
     */
    @Throws(ImportantOutputException::class, java.lang.InterruptedException::class)
    fun processTooLargeStdoutErr(stdoutErr: Path?)

    /** Represents artifacts that need to be regenerated via action rewinding.  */
    class LostArtifacts(byDigest: com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?>?) {
        fun isEmpty(): Boolean {
            return byDigest.isEmpty()
        }

        /** Throws [LostInputsExecException] if this instance is not empty.  */
        @Throws(LostInputsExecException::class)
        fun throwIfNotEmpty() {
            if (!isEmpty()) {
                throw LostInputsExecException(byDigest)
            }
        }

        val byDigest: com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?>?

        init {
            this.byDigest = byDigest
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?>?>(
                byDigest
            )
        }

        companion object {
            /** An empty instance of [LostArtifacts].  */
            val EMPTY: LostArtifacts =
                LostArtifacts(com.google.common.collect.ImmutableSetMultimap.of<String?, ActionInput?>())
        }
    }

    /** Represents an exception encountered during processing of important outputs.  */
    class ImportantOutputException(cause: Throwable?, failureDetail: FailureDetail) :
        java.lang.Exception(failureDetail.getMessage(), cause), DetailedException {
        private val failureDetail: FailureDetail

        init {
            this.failureDetail = failureDetail
        }

        fun getFailureDetail(): FailureDetail {
            return failureDetail
        }

        public override fun getDetailedExitCode(): DetailedExitCode {
            return DetailedExitCode.of(failureDetail)
        }
    }

    companion object {
        /**
         * A threshold to pass to [com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils]
         * for profiling [ImportantOutputHandler] operations.
         */
        val LOG_THRESHOLD: java.time.Duration? = java.time.Duration.ofMillis(100)
    }
}
