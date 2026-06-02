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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.vfs.PathFragment
import java.io.IOException

/** Interface to support remote execution in repository_ctx.execute().  */
interface RepositoryRemoteExecutor {
    /** The result of a remotely executed command.  */
    class ExecutionResult(private val exitCode: Int, private val stdout: ByteArray?, private val stderr: ByteArray?) {
        fun exitCode(): Int {
            return exitCode
        }

        fun stdout(): ByteArray? {
            return stdout
        }

        fun stderr(): ByteArray? {
            return stderr
        }
    }

    /**
     * Execute a command remotely.
     * 
     * @param arguments the command arguments.
     * @param inputFiles the files to upload and stage for the command. The key describes where to
     * stage the file on the remote machine. The value is the path of the file on the host machine
     * (where Bazel is running).
     * @param executionProperties the remote platform the command should run on.
     * @param environment any environment variables that should be set in the command's environment.
     * @param workingDirectory the working directory to run the command under. `""` means that
     * the remote system should choose.
     * @param timeout execution timeout.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun execute(
        arguments: com.google.common.collect.ImmutableList<String?>?,
        inputFiles: com.google.common.collect.ImmutableSortedMap<PathFragment?, com.google.devtools.build.lib.vfs.Path?>?,
        executionProperties: com.google.common.collect.ImmutableMap<String?, String?>?,
        environment: com.google.common.collect.ImmutableMap<String?, String?>?,
        workingDirectory: String?,
        timeout: java.time.Duration?
    ): ExecutionResult?
}
