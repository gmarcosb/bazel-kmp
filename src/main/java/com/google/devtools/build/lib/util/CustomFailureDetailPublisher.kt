// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/**
 * Provides an external way for the Bazel server to communicate a failure_detail protobuf to its
 * user, when the main gRPC channel is unavailable because the server's exit is too abrupt, or the
 * failure occurred outside of a command.
 * 
 * 
 * Uses Java 8 [Path] objects rather than Bazel ones to avoid depending on the rest of
 * Bazel.
 */
object CustomFailureDetailPublisher {
    @kotlin.concurrent.Volatile
    private var failureDetailFilePath: java.nio.file.Path? = null

    @kotlin.jvm.JvmStatic
    fun setFailureDetailFilePath(path: String?) {
        failureDetailFilePath = Paths.get(path)
    }

    @kotlin.jvm.JvmStatic
    @com.google.common.annotations.VisibleForTesting
    fun resetFailureDetailFilePath() {
        failureDetailFilePath = null
    }

    fun maybeWriteFailureDetailFile(failureDetail: FailureDetail): Boolean {
        val path: java.nio.file.Path? = failureDetailFilePath
        if (path != null) {
            try {
                java.nio.file.Files.write(path, failureDetail.toByteArray())
                return true
            } catch (ioe: IOException) {
                java.lang.System.err.printf(
                    "io error writing failure detail to file %s.\nfailure_detail: %s\nIOException: %s",
                    path, failureDetail, ioe.getMessage()
                )
            }
        }
        return false
    }
}
