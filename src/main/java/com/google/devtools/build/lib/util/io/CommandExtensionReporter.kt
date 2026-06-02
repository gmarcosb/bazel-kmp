// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.io

/**
 * Consumer of [Any] protos that sends messages to the build tool's gRPC client.
 * 
 * 
 * Instances of this interface must be *thread-safe*.
 */
fun interface CommandExtensionReporter {
    /**
     * Writes the command extension to the client in a gRPC RunResponse.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)
    fun report(commandExtension: Any?)

    companion object {
        /** Extension reporter that drops all extensions.  */
        @kotlin.jvm.JvmField
        val NO_OP_COMMAND_EXTENSION_REPORTER: CommandExtensionReporter = CommandExtensionReporter { any: Any? -> }
    }
}
