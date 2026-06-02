// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.zip

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * An [InputStream] that counts the number of bytes read.
 */
class CountingInputStream
/**
 * Wraps another input stream, counting the number of bytes read.
 * 
 * @param in the input stream to be wrapped
 */
    (`in`: InputStream?) : FilterInputStream(checkNotNull<InputStream?>(`in`)) {
    private var count: Long = 0
    private var mark: Long = -1

    /** Returns the number of bytes read.  */
    fun getCount(): Long {
        return count
    }

    @Throws(IOException::class)
    override fun read(): Int {
        val result = `in`.read()
        count += (if (result == -1) 0 else 1).toLong()
        return result
    }

    @Throws(IOException::class)
    override fun read(b: ByteArray?, off: Int, len: Int): Int {
        val result = `in`.read(b, off, len)
        count += (if (result == -1) 0 else result).toLong()
        return result
    }

    @Throws(IOException::class)
    override fun skip(n: Long): Long {
        val result = `in`.skip(n)
        count += result
        return result
    }

    @kotlin.jvm.Synchronized
    override fun mark(readlimit: Int) {
        `in`.mark(readlimit)
        mark = count
        // it's okay to mark even if mark isn't supported, as reset won't work
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    override fun reset() {
        if (!`in`.markSupported()) {
            throw IOException("Mark not supported")
        }
        if (mark == -1L) {
            throw IOException("Mark not set")
        }

        `in`.reset()
        count = mark
    }

    companion object {
        private fun <T> checkNotNull(reference: T?): T? {
            if (reference == null) {
                throw NullPointerException()
            }
            return reference
        }
    }
}