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
import com.google.common.hash.Hasher
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.lang.Double
import java.lang.Float
import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlin.Boolean
import kotlin.Byte
import kotlin.ByteArray
import kotlin.Char
import kotlin.CharSequence
import kotlin.Int
import kotlin.Long
import kotlin.Short

/** A [Hasher] for BLAKE3.  */
class Blake3Hasher(private val messageDigest: Blake3MessageDigest) : Hasher {
    private var isDone = false

    /* The following methods implement the {Hasher} interface. */
    @CanIgnoreReturnValue
    override fun putBytes(b: ByteBuffer): Hasher {
        messageDigest.engineUpdate(b)
        return this
    }

    @CanIgnoreReturnValue
    override fun putBytes(bytes: ByteArray, off: Int, len: Int): Hasher {
        messageDigest.engineUpdate(bytes, off, len)
        return this
    }

    @CanIgnoreReturnValue
    override fun putBytes(bytes: ByteArray): Hasher {
        messageDigest.engineUpdate(bytes, 0, bytes.size)
        return this
    }

    @CanIgnoreReturnValue
    override fun putByte(b: Byte): Hasher {
        messageDigest.engineUpdate(b)
        return this
    }

    override fun hash(): HashCode {
        Preconditions.checkState(!isDone)
        isDone = true

        return HashCode.fromBytes(messageDigest.engineDigest())
    }

    @CanIgnoreReturnValue
    override fun putBoolean(b: Boolean): Hasher {
        return putByte(if (b) 1.toByte() else 0.toByte())
    }

    @CanIgnoreReturnValue
    override fun putDouble(d: Double): Hasher {
        return putLong(Double.doubleToRawLongBits(d))
    }

    @CanIgnoreReturnValue
    override fun putFloat(f: Float): Hasher {
        return putInt(Float.floatToRawIntBits(f))
    }

    @CanIgnoreReturnValue
    override fun putUnencodedChars(charSequence: CharSequence): Hasher {
        var i = 0
        val len = charSequence.length
        while (i < len) {
            putChar(charSequence.get(i))
            i++
        }
        return this
    }

    @CanIgnoreReturnValue
    override fun putString(charSequence: CharSequence, charset: Charset): Hasher {
        return putBytes(charSequence.toString().toByteArray(charset))
    }

    @CanIgnoreReturnValue
    override fun putShort(s: Short): Hasher {
        putByte(s.toByte())
        putByte((s.toInt() ushr 8).toByte())
        return this
    }

    @CanIgnoreReturnValue
    override fun putInt(i: Int): Hasher {
        putByte(i.toByte())
        putByte((i ushr 8).toByte())
        putByte((i ushr 16).toByte())
        putByte((i ushr 24).toByte())
        return this
    }

    @CanIgnoreReturnValue
    override fun putLong(l: Long): Hasher {
        var i = 0
        while (i < 64) {
            putByte((l ushr i).toByte())
            i += 8
        }
        return this
    }

    @CanIgnoreReturnValue
    override fun putChar(c: Char): Hasher {
        putByte(c.code.toByte())
        putByte((c.code ushr 8).toByte())
        return this
    }

    @CanIgnoreReturnValue
    override fun <T> putObject(instance: T?, funnel: Funnel<in T?>): Hasher {
        funnel.funnel(instance, this)
        return this
    }
}
