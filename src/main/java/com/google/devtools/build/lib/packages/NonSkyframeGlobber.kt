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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.GlobCache
import com.google.devtools.build.lib.packages.Globber
import com.google.devtools.build.lib.packages.Globber.BadGlobException
import com.google.devtools.build.lib.packages.NonSkyframeGlobber
import java.io.IOException

/** [Globber] that uses [GlobCache] instead of Skyframe.  */
class NonSkyframeGlobber internal constructor(globCache: GlobCache) : Globber {
    private val globCache: GlobCache

    init {
        this.globCache = globCache
    }

    /** The [Globber.Token] used by [NonSkyframeGlobber].  */
    class Token private constructor(
        includes: MutableList<String?>,
        excludes: MutableList<String?>?,
        globberOperation: com.google.devtools.build.lib.packages.Globber.Operation?,
        allowEmpty: Boolean
    ) : com.google.devtools.build.lib.packages.Globber.Token() {
        private val includes: MutableList<String?>
        private val excludes: MutableList<String?>?
        private val globberOperation: com.google.devtools.build.lib.packages.Globber.Operation?
        private val allowEmpty: Boolean

        init {
            this.includes = includes
            this.excludes = excludes
            this.globberOperation = globberOperation
            this.allowEmpty = allowEmpty
        }
    }

    @Throws(BadGlobException::class)
    override fun runAsync(
        includes: MutableList<String?>,
        excludes: MutableList<String?>?,
        globberOperation: com.google.devtools.build.lib.packages.Globber.Operation?,
        allowEmpty: Boolean
    ): Token {
        for (pattern in includes) {
            @Suppress("unused") val possiblyIgnoredError: java.util.concurrent.Future<*>? =
                globCache.getGlobUnsortedAsync(pattern, globberOperation)
        }
        return com.google.devtools.build.lib.packages.NonSkyframeGlobber.Token(
            includes,
            excludes,
            globberOperation,
            allowEmpty
        )
    }

    @Throws(BadGlobException::class, IOException::class, java.lang.InterruptedException::class)
    override fun fetchUnsorted(token: com.google.devtools.build.lib.packages.Globber.Token?): MutableList<String?>? {
        val ourToken = token as Token
        return globCache.globUnsorted(
            ourToken.includes, ourToken.excludes, ourToken.globberOperation, ourToken.allowEmpty
        )
    }

    override fun onInterrupt() {
        globCache.cancelBackgroundTasks()
    }

    override fun onCompletion() {
        globCache.finishBackgroundTasks()
    }

    fun getGlobFilesystemOperationCost(): Long {
        return globCache.getGlobFilesystemOperationCost()
    }
}
