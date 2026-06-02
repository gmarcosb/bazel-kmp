// Copyright 2019 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.vfs.inmemoryfs

import com.google.devtools.build.lib.clock.Clock
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
import java.io.OutputStream

/**
 * This interface represents a mutable file stored in an InMemoryFileSystem.
 */
@ThreadSafe
abstract class FileInfo protected constructor(clock: Clock?) : InMemoryContentInfo(clock) {
    override fun isDirectory(): Boolean {
        return false
    }

    override fun isSymbolicLink(): Boolean {
        return false
    }

    override fun isFile(): Boolean {
        return true
    }

    override fun isSpecialFile(): Boolean {
        return false
    }

    @Throws(IOException::class)
    abstract fun getOutputStream(append: Boolean): OutputStream?

    @get:Throws(IOException::class)
    abstract val inputStream: InputStream?

    @Throws(IOException::class)
    abstract fun createReadWriteByteChannel(): SeekableByteChannel?

    @Throws(IOException::class)
    abstract fun getxattr(name: String?): ByteArray?

    @get:Throws(IOException::class)
    abstract val fastDigest: ByteArray?
}
