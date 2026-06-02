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
package com.google.devtools.build.lib.remote.common

import java.io.IOException

/**
 * Creates an [InputStream] backed by a file that isn't actually opened until the first data
 * is read. This is useful to only have as many open file descriptors as necessary at a time to
 * avoid running into system limits.
 * 
 * 
 * The markSupported(), mark() and reset() methods need not be overridden, as they're unsupported
 * by the base implementation.
 */
class LazyFileInputStream(path: com.google.devtools.build.lib.vfs.Path) : java.io.InputStream() {
    private val path: com.google.devtools.build.lib.vfs.Path
    private var `in`: java.io.InputStream? = null

    init {
        this.path = path
    }

    @Throws(IOException::class)
    override fun available(): Int {
        ensureOpen()
        return `in`.available()
    }

    @Throws(IOException::class)
    override fun read(): Int {
        ensureOpen()
        return `in`.read()
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray?): Int {
        ensureOpen()
        return `in`.read(b)
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        ensureOpen()
        return `in`.read(b, off, len)
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        ensureOpen()
        return `in`.skip(n)
    }

    @Throws(IOException::class)
    override fun close() {
        if (`in` != null) {
            `in`.close()
        }
    }

    @Throws(IOException::class)
    private fun ensureOpen() {
        if (`in` == null) {
            `in` = path.getInputStream()
        }
    }
}
