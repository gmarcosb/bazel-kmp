// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.sandbox

import com.google.devtools.build.lib.actions.ActionContext

// TODO(b/62588075): Use this class for the LocalSpawnRunnerTest as well.
/**
 * Utilities to help test SpawnRunners.
 * 
 * 
 * For example, to make embedded tools available for tests, or to use a rigged [ ] for testing purposes.
 */
object SpawnRunnerTestUtil {
    /**
     * Copies a file into a specific path.
     * 
     * @param sourceFile the file to copy
     * @param destinationDirectoryPath the directory to copy the sourceFile into
     */
    @Throws(IOException::class)
    fun copyFileToPath(sourceFile: java.io.File, destinationDirectoryPath: Path): Path {
        com.google.common.base.Preconditions.checkArgument(sourceFile.exists(), "source file to copy does not exist")
        com.google.common.base.Preconditions.checkArgument(
            destinationDirectoryPath.exists(), "destination directory to copy to does not exist"
        )

        val destinationFilePath: Path = destinationDirectoryPath.getRelative(sourceFile.getName())
        val destinationFile: java.io.File = destinationFilePath.getPathFile()

        com.google.common.base.Preconditions.checkState(
            !destinationFilePath.exists(),
            "destination file already exists"
        )
        com.google.common.io.Files.copy(sourceFile, destinationFile)

        return destinationFilePath
    }

    @Throws(IOException::class)
    private fun copyToolIntoPath(sourceToolRelativePath: String?, destinationDirectoryPath: Path): Path {
        val sourceToolFile: java.io.File =
            java.io.File(
                PathFragment.create(BlazeTestUtils.runfilesDir())
                    .getRelative(sourceToolRelativePath)
                    .getPathString()
            )
        com.google.common.base.Preconditions.checkState(sourceToolFile.exists(), "tool not found")

        val binDirectoryPath: Path = destinationDirectoryPath.getRelative("_bin")
        binDirectoryPath.createDirectory()

        val destinationToolPath: Path = copyFileToPath(sourceToolFile, binDirectoryPath)

        destinationToolPath.setExecutable(true)

        return destinationToolPath
    }

    /** Copies the `process-wrapper` tool a path where a runner expects to find it.  */
    @Throws(IOException::class)
    fun copyProcessWrapperIntoPath(destinationDirectoryPath: Path): Path {
        return copyToolIntoPath(TestConstants.PROCESS_WRAPPER_PATH, destinationDirectoryPath)
    }

    /** Copies the `linux-sandbox` tool into a path where a runner expects to find it.  */
    @Throws(IOException::class)
    fun copyLinuxSandboxIntoPath(destinationDirectoryPath: Path): Path {
        return copyToolIntoPath(TestConstants.LINUX_SANDBOX_PATH, destinationDirectoryPath)
    }

    /** Copies the `spend_cpu_time` test util into a path where a runner expects to find it.  */
    @Throws(IOException::class)
    fun copyCpuTimeSpenderIntoPath(destinationDirectoryPath: Path): Path {
        val realCpuTimeSpenderFile: java.io.File =
            java.io.File(
                PathFragment.create(BlazeTestUtils.runfilesDir())
                    .getRelative(TestConstants.CPU_TIME_SPENDER_PATH)
                    .getPathString()
            )
        com.google.common.base.Preconditions.checkState(realCpuTimeSpenderFile.exists(), "spend_cpu_time not found")

        val destinationCpuTimeSpenderPath: Path =
            copyFileToPath(realCpuTimeSpenderFile, destinationDirectoryPath)

        destinationCpuTimeSpenderPath.setExecutable(true)

        return destinationCpuTimeSpenderPath
    }

    /** A rigged spawn execution policy that can be used for testing purposes.  */
    class SpawnExecutionContextForTesting(spawn: Spawn, fileOutErr: FileOutErr?, timeout: java.time.Duration?) :
        SpawnExecutionContext {
        val reportedStatus: MutableList<ProgressStatus?> = java.util.ArrayList<ProgressStatus?>()
        var prefetchCalled: Boolean = false
        var lockOutputFilesCalled: Boolean = false

        private val spawn: Spawn
        private val timeout: java.time.Duration?
        private val fileOutErr: FileOutErr?

        /**
         * Creates a new spawn execution policy for testing purposes.
         * 
         * @param fileOutErr the [FileOutErr] object to use. After a [Spawn] is executed,
         * its stdout and stderr can be available here, if the spawn runner uses the fileOutErr
         * returned by [.getFileOutErr] on the spawn execution policy
         * @param timeout the timeout to use. Spawn runners may request this via [.getTimeout]
         */
        init {
            this.spawn = spawn
            this.fileOutErr = fileOutErr
            this.timeout = timeout
        }

        val id: Int
            get() = 0

        public override fun setDigest(digest: Digest?) {
            // Intentionally empty.
        }

        val digest: Digest?
            get() = null

        public override fun prefetchInputs(): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> {
            prefetchCalled = true
            return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun lockOutputFiles(exitCode: Int, errorMessage: String?, outErr: FileOutErr?) {
            lockOutputFilesCalled = true
        }

        public override fun speculating(): Boolean {
            return false
        }

        val inputMetadataProvider: InputMetadataProvider?
            get() = Mockito.mock<InputMetadataProvider?>(InputMetadataProvider::class.java)

        public override fun getTimeout(): java.time.Duration? {
            return timeout
        }

        public override fun getFileOutErr(): FileOutErr? {
            return fileOutErr
        }

        public override fun getInputMapping(
            baseDirectory: PathFragment, willAccessRepeatedly: Boolean
        ): SortedMap<PathFragment?, ActionInput?> {
            val inputMapping: TreeMap<PathFragment?, ActionInput?> = TreeMap<PathFragment?, ActionInput?>()
            for (actionInput in spawn.getInputFiles().toList()) {
                inputMapping.put(baseDirectory.getRelative(actionInput.getExecPath()), actionInput)
            }
            return inputMapping
        }

        public override fun report(progress: ProgressStatus?) {
            reportedStatus.add(progress)
        }

        public override fun <T : ActionContext?> getContext(identifyingType: java.lang.Class<T?>?): T? {
            throw java.lang.UnsupportedOperationException()
        }

        val isRewindingEnabled: Boolean
            get() = false

        public override fun checkForLostInputs() {}

        val actionFileSystem: FileSystem?
            get() = null

        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?>
            get() = com.google.common.collect.ImmutableMap.of<String?, String?>()
    }
}
