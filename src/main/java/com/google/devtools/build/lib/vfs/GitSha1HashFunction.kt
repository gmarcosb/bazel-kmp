// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

/** A [HashFunction] for GITSHA1.  */
class GitSha1HashFunction : com.google.common.hash.HashFunction {
    private fun newInitializedHasher(blobSize: Int): com.google.common.hash.Hasher {
        val hasher: com.google.common.hash.Hasher = SHA1.newHasher()
        hasher.putBytes(header)
        hasher.putString(java.lang.Integer.toString(blobSize), java.nio.charset.StandardCharsets.UTF_8)
        hasher.putByte(0.toByte())
        return hasher
    }

    override fun bits(): Int {
        return 160
    }

    override fun newHasher(): com.google.common.hash.Hasher {
        return DelayedGitSha1Hasher()
    }

    override fun newHasher(expectedInputSize: Int): com.google.common.hash.Hasher {
        com.google.common.base.Preconditions.checkArgument(
            expectedInputSize >= 0, "expectedInputSize must be >= 0 but was %s", expectedInputSize
        )
        return newInitializedHasher(expectedInputSize)
    }

    /* The following methods implement the {HashFunction} interface. */
    override fun <T> hashObject(
        instance: T?,
        funnel: com.google.common.hash.Funnel<in T?>
    ): com.google.common.hash.HashCode {
        return newHasher().putObject<T?>(instance, funnel).hash()
    }

    override fun hashUnencodedChars(input: CharSequence): com.google.common.hash.HashCode {
        val len: Int = input.length()
        return newHasher(len * 2).putUnencodedChars(input).hash()
    }

    override fun hashString(input: CharSequence, charset: java.nio.charset.Charset): com.google.common.hash.HashCode {
        return newHasher(input.length()).putString(input, charset).hash()
    }

    override fun hashInt(input: Int): com.google.common.hash.HashCode {
        return newHasher(4).putInt(input).hash()
    }

    override fun hashLong(input: Long): com.google.common.hash.HashCode {
        return newHasher(8).putLong(input).hash()
    }

    override fun hashBytes(input: ByteArray): com.google.common.hash.HashCode {
        return hashBytes(input, 0, input.size)
    }

    override fun hashBytes(input: ByteArray, off: Int, len: Int): com.google.common.hash.HashCode {
        com.google.common.base.Preconditions.checkPositionIndexes(off, off + len, input.size)
        return newHasher(len).putBytes(input, off, len).hash()
    }

    override fun hashBytes(input: java.nio.ByteBuffer): com.google.common.hash.HashCode {
        return newHasher(input.remaining()).putBytes(input).hash()
    }

    private inner class DelayedGitSha1Hasher : com.google.common.hash.Hasher {
        private val output: ByteArrayOutput

        init {
            output = ByteArrayOutput()
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putBoolean(b: Boolean): com.google.common.hash.Hasher {
            output.putByte(if (b) 1.toByte() else 0.toByte())
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putByte(b: Byte): com.google.common.hash.Hasher {
            output.putByte(b)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putBytes(bytes: ByteArray): com.google.common.hash.Hasher {
            output.putBytes(bytes)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putBytes(bytes: ByteArray, off: Int, len: Int): com.google.common.hash.Hasher {
            output.putBytes(bytes, off, len)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putBytes(b: java.nio.ByteBuffer): com.google.common.hash.Hasher {
            output.putBytes(b.array(), b.arrayOffset() + b.position(), b.remaining())
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putShort(s: Short): com.google.common.hash.Hasher {
            output.putShort(s)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putInt(i: Int): com.google.common.hash.Hasher {
            output.putInt(i)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putLong(l: Long): com.google.common.hash.Hasher {
            output.putLong(l)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun <T> putObject(
            instance: T?,
            funnel: com.google.common.hash.Funnel<in T?>
        ): com.google.common.hash.Hasher {
            funnel.funnel(instance, output)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putChar(c: Char): com.google.common.hash.Hasher {
            output.putChar(c)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putDouble(d: Double): com.google.common.hash.Hasher {
            output.putDouble(d)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putFloat(f: Float): com.google.common.hash.Hasher {
            output.putFloat(f)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putUnencodedChars(charSequence: CharSequence): com.google.common.hash.Hasher {
            for (i in 0..<charSequence.length()) {
                output.putChar(charSequence.charAt(i))
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putString(
            charSequence: CharSequence,
            charset: java.nio.charset.Charset
        ): com.google.common.hash.Hasher {
            output.putBytes(charSequence.toString().getBytes(charset))
            return this
        }

        override fun hash(): com.google.common.hash.HashCode {
            val body = output.toByteArray()
            return newHasher(body.size).putBytes(body).hash()
        }

        override fun hashCode(): Int {
            return hash().hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            if (obj is com.google.common.hash.Hasher) {
                return this.hash() == obj.hash()
            }
            return false
        }
    }

    private class ByteArrayOutput : com.google.common.hash.PrimitiveSink {
        private val buffer: com.google.common.io.ByteArrayDataOutput = com.google.common.io.ByteStreams.newDataOutput()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putBoolean(b: Boolean): ByteArrayOutput {
            buffer.writeBoolean(b)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putByte(b: Byte): ByteArrayOutput {
            buffer.write(b.toInt())
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putBytes(bytes: ByteArray): ByteArrayOutput {
            buffer.write(bytes)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putBytes(bytes: ByteArray, off: Int, len: Int): ByteArrayOutput {
            buffer.write(bytes, off, len)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putBytes(b: java.nio.ByteBuffer): ByteArrayOutput {
            buffer.write(b.array(), b.arrayOffset() + b.position(), b.remaining())
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putChar(c: Char): ByteArrayOutput {
            buffer.writeChar(c.code)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putDouble(d: Double): ByteArrayOutput {
            buffer.writeDouble(d)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putFloat(f: Float): ByteArrayOutput {
            buffer.writeFloat(f)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putInt(i: Int): ByteArrayOutput {
            buffer.writeInt(i)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putLong(l: Long): ByteArrayOutput {
            buffer.writeLong(l)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putShort(s: Short): ByteArrayOutput {
            buffer.writeShort(s.toInt())
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putString(charSequence: CharSequence, charset: java.nio.charset.Charset): ByteArrayOutput {
            buffer.write(charSequence.toString().getBytes(charset))
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun putUnencodedChars(charSequence: CharSequence): ByteArrayOutput {
            for (i in 0..<charSequence.length()) {
                buffer.writeChar(charSequence.charAt(i).code)
            }
            return this
        }

        fun toByteArray(): ByteArray {
            return buffer.toByteArray()
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: com.google.common.hash.HashFunction = GitSha1HashFunction()
        private val SHA1: com.google.common.hash.HashFunction = com.google.common.hash.Hashing.sha1()
        private val header =
            byteArrayOf('b'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte(), 'b'.code.toByte(), ' '.code.toByte())
    }
}
