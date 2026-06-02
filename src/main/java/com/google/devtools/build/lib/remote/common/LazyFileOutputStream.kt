// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.remote.common.MaybePathBacked
import java.io.FileOutputStream
import java.io.IOException

/**
 * Creates an [OutputStream] backed by a file that isn't actually opened until the first data
 * is written. This is useful to only have as many open file descriptors as necessary at a time to
 * avoid running into system limits.
 */
class LazyFileOutputStream(path: com.google.devtools.build.lib.vfs.Path) : java.io.OutputStream(), MaybePathBacked {
    private val path: com.google.devtools.build.lib.vfs.Path
    private var out: java.io.OutputStream? = null

    init {
        this.path = path
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray?) {
        ensureOpen()
        out.write(b)
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray?, off: Int, len: Int) {
        ensureOpen()
        out.write(b, off, len)
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        ensureOpen()
        out.write(b)
    }

    @Throws(IOException::class)
    override fun flush() {
        if (out != null) {
            out.flush()
        }
    }

    @Throws(IOException::class)
    override fun close() {
        if (out != null) {
            out.close()
        }
    }

    /**
     * If the output stream is a [FileOutputStream], call [FileDescriptor.sync] on it.
     * Otherwise, do nothing.
     */
    @Throws(IOException::class)
    fun syncIfPossible() {
        ensureOpen()
        if (out is FileOutputStream) {
            out.getFD().sync()
        }
    }

    @Throws(IOException::class)
    private fun ensureOpen() {
        if (out == null) {
            out = path.getOutputStream()
        }
    }

    override fun maybeGetPath(): com.google.devtools.build.lib.vfs.Path? {
        return path
    }
}
