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
package com.google.devtools.build.lib.util

import java.io.IOException

/** Wrapper for [jdk.internal.vm.ThreadDumper].  */
object ThreadDumper {
    /**
     * Generate a thread dump in plain text format to the given output stream, UTF-8 encoded.
     * 
     * @throws IOException if an I/O error occurs
     */
    @Throws(IOException::class)  // intentional for compatibility with JDK 25
    fun dumpThreads(out: java.io.OutputStream?) {
        jdk.internal.vm.ThreadDumper.dumpThreads(out)
    }
}
