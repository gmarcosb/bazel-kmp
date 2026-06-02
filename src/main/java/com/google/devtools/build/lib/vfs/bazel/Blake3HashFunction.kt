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

import com.google.common.base.Preconditions
import com.google.common.hash.Funnel
import com.google.common.hash.HashCode
import com.google.common.hash.HashFunction
import com.google.common.hash.Hasher
import java.nio.ByteBuffer
import java.nio.charset.Charset

/** A [HashFunction] for BLAKE3.  */
class Blake3HashFunction : HashFunction {
    override fun bits(): Int {
        return 256
    }

    override fun newHasher(): Hasher {
        return Blake3Hasher(Blake3MessageDigest())
    }

    override fun newHasher(expectedInputSize: Int): Hasher {
        Preconditions.checkArgument(
            expectedInputSize >= 0, "expectedInputSize must be >= 0 but was %s", expectedInputSize
        )
        return newHasher()
    }

    /* The following methods implement the {HashFunction} interface. */
    override fun <T> hashObject(instance: T?, funnel: Funnel<in T?>): HashCode {
        return newHasher().putObject<T?>(instance, funnel).hash()
    }

    override fun hashUnencodedChars(input: CharSequence): HashCode {
        val len = input.length
        return newHasher(len * 2).putUnencodedChars(input).hash()
    }

    override fun hashString(input: CharSequence, charset: Charset): HashCode {
        return newHasher().putString(input, charset).hash()
    }

    override fun hashInt(input: Int): HashCode {
        return newHasher(4).putInt(input).hash()
    }

    override fun hashLong(input: Long): HashCode {
        return newHasher(8).putLong(input).hash()
    }

    override fun hashBytes(input: ByteArray): HashCode {
        return hashBytes(input, 0, input.size)
    }

    override fun hashBytes(input: ByteArray, off: Int, len: Int): HashCode {
        Preconditions.checkPositionIndexes(off, off + len, input.size)
        return newHasher(len).putBytes(input, off, len).hash()
    }

    override fun hashBytes(input: ByteBuffer): HashCode {
        return newHasher(input.remaining()).putBytes(input).hash()
    }

    companion object {
        val INSTANCE: Blake3HashFunction = Blake3HashFunction()
    }
}
