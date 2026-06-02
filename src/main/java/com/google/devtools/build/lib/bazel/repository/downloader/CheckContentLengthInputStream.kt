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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.devtools.build.lib.concurrent.ThreadSafety
import java.io.IOException
import java.io.InputStream

/**
 * Input stream that guarantees its contents match a size.
 * 
 * 
 * This class is not thread safe, but it is safe to message pass this object between threads.
 */
@ThreadSafety.ThreadCompatible
class CheckContentLengthInputStream(private val delegate: InputStream, private val expectedSize: Long) : InputStream() {
    private var actualSize: Long = 0

    @Throws(IOException::class)
    override fun read(): Int {
        val result = delegate.read()
        if (result == -1) {
            checkContentLength()
        } else {
            actualSize += 1
        }
        return result
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray?, offset: Int, length: Int): Int {
        val amount = delegate.read(buffer, offset, length)
        if (amount == -1) {
            checkContentLength()
        } else {
            actualSize += amount.toLong()
        }
        return amount
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return delegate.available()
    }

    @Throws(IOException::class)
    override fun close() {
        delegate.close()
        checkContentLength()
    }

    @Throws(IOException::class)
    private fun checkContentLength() {
        if (actualSize != expectedSize) {
            throw ContentLengthMismatchException(actualSize, expectedSize)
        }
    }
}
