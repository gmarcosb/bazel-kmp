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
package com.google.devtools.build.lib.remote.util

import build.bazel.remote.execution.v2.Digest

/**
 * An [OutputStream] that maintains a [Digest] of the data written to it.
 * 
 * 
 * Similar to Guava's [com.google.common.hash.HashingOutputStream], but for computing the
 * [Digest] of the written data as specified by the remote execution protocol.
 */
class DigestOutputStream(
    hashFunction: com.google.common.hash.HashFunction,
    @javax.annotation.WillCloseWhenClosed out: java.io.OutputStream?
) : FilterOutputStream(com.google.common.base.Preconditions.checkNotNull<java.io.OutputStream?>(out)) {
    private val hasher: com.google.common.hash.Hasher
    private var size: Long = 0

    /**
     * Creates an output stream that creates an [Digest] using the given [HashFunction],
     * and forwards all data written to it to the underlying [OutputStream].
     * 
     * 
     * The [OutputStream] should not be written to before or after the hand-off.
     */
    init {
        this.hasher =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.hash.Hasher>(hashFunction.newHasher())
    }

    @Throws(IOException::class)
    override fun write(b: Int) {
        size++
        hasher.putByte(b.toByte())
        out.write(b)
    }

    @Throws(IOException::class)
    override fun write(bytes: ByteArray, off: Int, len: Int) {
        size += len.toLong()
        hasher.putBytes(bytes, off, len)
        out.write(bytes, off, len)
    }

    /**
     * Returns the [Digest] of the data written to this stream. The result is unspecified if
     * this method is called more than once on the same instance.
     */
    fun digest(): Digest {
        return Digest.newBuilder().setHash(hasher.hash().toString()).setSizeBytes(size).build()
    }

    @Throws(IOException::class)
    override fun close() {
        out.close()
    }
}
