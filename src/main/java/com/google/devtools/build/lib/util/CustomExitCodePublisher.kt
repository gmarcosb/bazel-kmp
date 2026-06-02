// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.common.flogger.GoogleLogger
import java.io.IOException
import java.nio.file.Paths

/**
 * Provides an external way for the Bazel server to communicate its exit code to the client, when
 * the main gRPC channel is unavailable because the exit is too abrupt or originated in an async
 * thread.
 * 
 * 
 * Uses Java 8 [Path] objects rather than Bazel ones to avoid depending on the rest of
 * Bazel.
 */
// TODO(b/138456686): When the Bazel server is completely converted to use FailureDetail messages
//  for its failure modes, this publishing mechanism and the file it creates can probably be
//  deleted. We'll need to confirm that nothing other than the Bazel client consumes it.
object CustomExitCodePublisher {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    private const val EXIT_CODE_FILENAME = "exit_code_to_use_on_abrupt_exit"

    @kotlin.concurrent.Volatile
    private var abruptExitCodeFilePath: java.nio.file.Path? = null

    @kotlin.jvm.JvmStatic
    fun setAbruptExitStatusFileDir(path: String?) {
        abruptExitCodeFilePath = Paths.get(path).resolve(EXIT_CODE_FILENAME)
    }

    fun maybeDeleteAbruptExitStatusFile() {
        if (abruptExitCodeFilePath != null) {
            try {
                val deleted: Boolean = java.nio.file.Files.deleteIfExists(abruptExitCodeFilePath)
                if (deleted) {
                    logger.atInfo().log("Deleted old abrupt exit status file")
                }
            } catch (ioe: IOException) {
                logger.atWarning().withCause(ioe).log("Failed to delete old abrupt exit status file")
            }
        }
    }

    @kotlin.jvm.JvmStatic
    @com.google.common.annotations.VisibleForTesting
    fun resetAbruptExitStatusFile() {
        abruptExitCodeFilePath = null
    }

    fun maybeWriteExitStatusFile(exitCode: Int): Boolean {
        val path: java.nio.file.Path? = abruptExitCodeFilePath
        if (path != null) {
            try {
                java.nio.file.Files.write(
                    path,
                    java.lang.String.valueOf(exitCode).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
                return true
            } catch (ioe: IOException) {
                java.lang.System.err.printf(
                    "io error writing %d to abrupt exit status file %s: %s\n",
                    exitCode, path, ioe.getMessage()
                )
            }
        }
        return false
    }
}
