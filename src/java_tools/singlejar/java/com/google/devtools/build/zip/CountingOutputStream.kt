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

import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream

/** An OutputStream that counts the number of bytes written.  */
internal class CountingOutputStream
/**
 * Wraps another output stream, counting the number of bytes written.
 * 
 * @param out the output stream to be wrapped
 */
    (out: OutputStream?) : FilterOutputStream(out) {
    private var count: Long = 0

    /** Returns the number of bytes written.  */
    fun getCount(): Long {
        return count
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        out.write(b)
        count++
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray) {
        out.write(b)
        count += b.size.toLong()
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray?, off: Int, len: Int) {
        out.write(b, off, len)
        count += len.toLong()
    }
}