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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.devtools.build.lib.concurrent.ThreadSafety
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import javax.annotation.WillCloseWhenClosed

/**
 * Input stream that guarantees [InterruptedIOException].
 * 
 * 
 * This class exists to hedge against the possibility that the JVM might not implement this
 * functionality. See [bug 4385444](http://bugs.java.com/view_bug.do?bug_id=4385444).
 */
@ThreadSafety.ConditionallyThreadSafe
internal class InterruptibleInputStream(@param:WillCloseWhenClosed private val delegate: InputStream) : InputStream() {
    @Throws(IOException::class)
    override fun read(): Int {
        check()
        return delegate.read()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray?): Int {
        check()
        return delegate.read(buffer)
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray?, offset: Int, length: Int): Int {
        check()
        return delegate.read(buffer, offset, length)
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return delegate.available()
    }

    override fun markSupported(): Boolean {
        return delegate.markSupported()
    }

    override fun mark(readlimit: Int) {
        delegate.mark(readlimit)
    }

    @Throws(IOException::class)
    override fun reset() {
        delegate.reset()
    }

    @Throws(IOException::class)
    override fun close() {
        delegate.close()
    }

    companion object {
        @Throws(InterruptedIOException::class)
        private fun check() {
            if (Thread.interrupted()) {
                throw InterruptedIOException()
            }
        }
    }
}
