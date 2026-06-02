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
package com.google.devtools.build.lib.unix

import com.google.devtools.build.lib.runtime.BlazeService
import java.util.concurrent.atomic.AtomicReference

/** Service for various UNIX process utilities.  */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface ProcessUtilsService : BlazeService {
    /**
     * Returns the real group ID of the current process.
     * 
     * @throws UnsatisfiedLinkError when JNI is not available.
     * @throws UnsupportedOperationException on operating systems where this call is not implemented.
     */
    fun getgid(): Int

    /**
     * Returns the real user ID of the current process.
     * 
     * @throws UnsatisfiedLinkError when JNI is not available.
     * @throws UnsupportedOperationException on operating systems where this call is not implemented.
     */
    fun getuid(): Int

    companion object {
        val service: AtomicReference<ProcessUtilsService?> = AtomicReference<ProcessUtilsService?>()

        fun registerJniService(service: ProcessUtilsService?) {
            Companion.service.set(service)
        }

        fun getService(): ProcessUtilsService? {
            return service.get()
        }
    }
}
