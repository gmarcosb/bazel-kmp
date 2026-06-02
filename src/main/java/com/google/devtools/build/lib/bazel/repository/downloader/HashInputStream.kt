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

import com.google.common.hash.HashCode
import com.google.common.hash.Hasher
import com.google.devtools.build.lib.concurrent.ThreadSafety
import java.io.IOException
import java.io.InputStream
import java.lang.String
import javax.annotation.WillCloseWhenClosed
import kotlin.ByteArray
import kotlin.Int

/**
 * Input stream that guarantees its contents matches a hash code.
 * 
 * 
 * The actual checksum is computed gradually as the input is read. If it doesn't match, then an
 * [IOException] will be thrown when [.close] is called, or when any read method is
 * called that detects the end of stream. This error will be thrown multiple times if these methods
 * are called again for some reason.
 * 
 * 
 * This class is not thread safe, but it is safe to message pass this object between threads.
 */
@ThreadSafety.ThreadCompatible
internal class HashInputStream(
    @param:WillCloseWhenClosed private val delegate: InputStream,
    private val checksum: Checksum
) : InputStream() {
    private val hasher: Hasher

    @kotlin.concurrent.Volatile
    private var actual: HashCode? = null

    init {
        this.hasher = checksum.getKeyType().newHasher()
    }

    @Throws(IOException::class)
    override fun read(): Int {
        val result = delegate.read()
        if (result == -1) {
            check()
        } else {
            hasher.putByte(result.toByte())
        }
        return result
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val amount = delegate.read(buffer, offset, length)
        if (amount == -1) {
            check()
        } else {
            hasher.putBytes(buffer, offset, amount)
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
        check()
    }

    @Throws(IOException::class)
    private fun check() {
        if (actual == null) {
            actual = hasher.hash()
        }
        if (checksum.getHashCode() != actual) {
            throw UnrecoverableHttpException(
                String.format(
                    "Checksum was %s but wanted %s",
                    checksum.emitOtherHashInSameFormat(actual),
                    checksum.emitOtherHashInSameFormat(checksum.getHashCode())
                )
            )
        }
    }
}
