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
package com.google.devtools.build.lib.packages

import java.io.IOException

/** Interface for evaluating globs during package loading.  */
interface Globber {
    /** An opaque token for fetching the result of a glob computation.  */
    class Token

    /** Indicates the type of globbing operations we are doing.  */
    enum class Operation {
        // Return only files.
        FILES,

        // Return files and directories, but not sub-packages.
        FILES_AND_DIRS,

        // Return only sub-packages.
        SUBPACKAGES,
    }

    /** Used to indicate an invalid glob pattern.  */
    class BadGlobException(message: String?) : java.lang.Exception(message)

    /**
     * Asynchronously starts the given glob computation and returns a token for fetching the result.
     * 
     * @throws BadGlobException if any of the patterns in `includes` or `excludes` are
     * invalid.
     */
    @Throws(BadGlobException::class, java.lang.InterruptedException::class)
    fun runAsync(
        includes: MutableList<String?>?, excludes: MutableList<String?>?, operation: Operation?, allowEmpty: Boolean
    ): Token?

    /**
     * Fetches the result of a previously started glob computation. The returned list has an arbitrary
     * order.
     */
    @Throws(BadGlobException::class, IOException::class, java.lang.InterruptedException::class)
    fun fetchUnsorted(token: Token?): MutableList<String?>?

    /** Should be called when the globber is about to be discarded due to an interrupt.  */
    fun onInterrupt()

    /** Should be called when the globber is no longer needed.  */
    fun onCompletion()
}
