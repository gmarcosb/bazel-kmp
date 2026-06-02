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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.actions.Action

/**
 * A minimal local-only [OutputService].
 * 
 * 
 * This is used by default when no [com.google.devtools.build.lib.runtime.BlazeModule]
 * [provides][com.google.devtools.build.lib.runtime.BlazeModule.getOutputService] an
 * [OutputService].
 */
class LocalOutputService(directories: BlazeDirectories?) : OutputService {
    private val directories: BlazeDirectories

    init {
        this.directories = com.google.common.base.Preconditions.checkNotNull<BlazeDirectories>(directories)
    }

    override fun getFileSystemName(outputBaseFileSystemName: String?): String? {
        return outputBaseFileSystemName
    }

    val isLocalOnly: Boolean
        get() = true

    @Throws(AbruptExitException::class)
    override fun startBuild(
        buildId: UUID?, workspaceName: String?, eventHandler: EventHandler?, finalizeActions: Boolean
    ): ModifiedFileSet {
        val outputPath: com.google.devtools.build.lib.vfs.Path = directories.getOutputPath(workspaceName)
        val localOutputPath: com.google.devtools.build.lib.vfs.Path = directories.getLocalOutputPath()

        if (outputPath.isSymbolicLink()) {
            try {
                // Remove the existing symlink first.
                outputPath.delete()
                if (localOutputPath.exists()) {
                    // Pre-existing local output directory. Move to outputPath.
                    localOutputPath.renameTo(outputPath)
                }
            } catch (e: IOException) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(
                                "Couldn't handle local output directory symlinks: " + e.getMessage()
                            )
                            .setExecution(
                                Execution.newBuilder().setCode(Code.LOCAL_OUTPUT_DIRECTORY_SYMLINK_FAILURE)
                            )
                            .build()
                    ),
                    e
                )
            }
        }
        return ModifiedFileSet.Companion.EVERYTHING_MODIFIED
    }

    override fun finalizeBuild(buildSuccessful: Boolean) {}

    override fun finalizeAction(action: Action?, outputMetadataStore: OutputMetadataStore?) {}

    val batchStatter: BatchStat?
        get() = null

    override fun canCreateSymlinkTree(): Boolean {
        return false
    }

    override fun createSymlinkTree(
        symlinks: MutableMap<PathFragment?, PathFragment?>?, symlinkTreeRoot: PathFragment?
    ) {
        throw java.lang.UnsupportedOperationException()
    }

    override fun clean() {}

    val outputChecker: OutputChecker
        get() = OutputChecker.TRUST_LOCAL_ONLY
}
