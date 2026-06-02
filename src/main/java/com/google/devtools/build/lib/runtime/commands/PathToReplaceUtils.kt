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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.CommandEnvironment

/** Helpers for constructing [ExecRequest]s.  */
object PathToReplaceUtils {
    /** Returns the common required [PathToReplace] list.  */
    fun getPathsToReplace(env: CommandEnvironment): com.google.common.collect.ImmutableList<PathToReplace?> {
        val pathsToReplace: com.google.common.collect.ImmutableList.Builder<PathToReplace?> =
            com.google.common.collect.ImmutableList.builder<PathToReplace?>()
        pathsToReplace
            .add(
                PathToReplace.newBuilder()
                    .setType(PathToReplace.Type.OUTPUT_BASE)
                    .setValue(bytes(env.getOutputBase().getPathString()))
                    .build()
            )
            .add(
                PathToReplace.newBuilder()
                    .setType(PathToReplace.Type.BUILD_WORKING_DIRECTORY)
                    .setValue(bytes(env.getWorkingDirectory().getPathString()))
                    .build()
            )
        val workspacePath: com.google.devtools.build.lib.vfs.Path? = env.getWorkspace()
        if (workspacePath != null) {
            pathsToReplace.add(
                PathToReplace.newBuilder()
                    .setType(PathToReplace.Type.BUILD_WORKSPACE_DIRECTORY)
                    .setValue(bytes(workspacePath.getPathString()))
                    .build()
            )
        }
        return pathsToReplace.build()
    }

    /** Converts a string to bytes for use in [ExecRequest] bytes fields.  */
    fun bytes(string: String): ByteString {
        return ByteString.copyFrom(string, java.nio.charset.StandardCharsets.ISO_8859_1)
    }
}
