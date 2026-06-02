// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs.bazel

import com.google.devtools.build.lib.jni.JniLoader.loadJni
import java.nio.ByteBuffer
import java.security.DigestException
import java.security.MessageDigest

/** A [MessageDigest] for BLAKE3.  */
class Blake3MessageDigest : MessageDigest("BLAKE3") {
    private val hasher = ByteArray(STATE_SIZE)
    private val oneByteArray = ByteArray(1)

    init {
        System.arraycopy(INITIAL_STATE, 0, hasher, 0, STATE_SIZE)
    }

    public override fun engineUpdate(data: ByteArray?, offset: Int, length: Int) {
        blake3_hasher_update(hasher, data, offset, length)
    }

    public override fun engineUpdate(b: Byte) {
        oneByteArray[0] = b
        engineUpdate(oneByteArray, 0, 1)
    }

    public override fun engineUpdate(input: ByteBuffer?) {
        super.engineUpdate(input)
    }

    private fun getOutput(outputLength: Int): ByteArray {
        val retByteArray = ByteArray(outputLength)
        blake3_hasher_finalize(hasher, retByteArray, outputLength)

        engineReset()
        return retByteArray
    }

    @Throws(CloneNotSupportedException::class)
    override fun clone(): Any {
        throw CloneNotSupportedException()
    }

    public override fun engineReset() {
        System.arraycopy(INITIAL_STATE, 0, hasher, 0, STATE_SIZE)
    }

    public override fun engineGetDigestLength(): Int {
        return OUT_LEN
    }

    public override fun engineDigest(): ByteArray {
        return getOutput(OUT_LEN)
    }

    @Throws(DigestException::class)
    public override fun engineDigest(buf: ByteArray, off: Int, len: Int): Int {
        if (len < OUT_LEN) {
            throw DigestException("partial digests not returned")
        }
        if (buf.size - off < OUT_LEN) {
            throw DigestException("insufficient space in the output buffer to store the digest")
        }

        val digestBytes = getOutput(OUT_LEN)
        System.arraycopy(digestBytes, 0, buf, off, digestBytes.size)
        return digestBytes.size
    }

    companion object {
        // These constants match the native definitions in:
        // https://github.com/BLAKE3-team/BLAKE3/blob/master/c/blake3.h
        const val KEY_LEN: Int = 32
        const val OUT_LEN: Int = 32

        init {
            loadJni()
        }

        private val STATE_SIZE: Int = hasher_size()
        private val INITIAL_STATE = ByteArray(STATE_SIZE)

        init {
            initialize_hasher(INITIAL_STATE)
        }

        external fun hasher_size(): Int

        external fun initialize_hasher(hasher: ByteArray?)

        external fun blake3_hasher_update(
            hasher: ByteArray?, input: ByteArray?, offset: Int, inputLen: Int
        )

        external fun blake3_hasher_finalize(hasher: ByteArray?, out: ByteArray?, outLen: Int)
    }
}
