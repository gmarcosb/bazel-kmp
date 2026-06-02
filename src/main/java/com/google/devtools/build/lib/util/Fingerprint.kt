// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.BytesSink
import com.google.devtools.build.lib.vfs.DigestHashFunction
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.protobuf.ByteString
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.security.DigestException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Simplified wrapper for using [MessageDigest] to generate fingerprints.
 * 
 * 
 * A fingerprint is a cryptographic hash of a message that encodes the representation of an
 * object. Two objects of the same type have the same fingerprint if and only if they are equal.
 * This property allows fingerprints to be used as unique identifiers for objects of a particular
 * type, and for fingerprint equivalence to be used as a proxy for object equivalence, and these
 * properties hold even outside the process. Note that this is a stronger requirement than [ ][Object.hashCode], which allows unequal objects to share the same hash code.
 * 
 * 
 * Values are added to the fingerprint by converting them to bytes and digesting the bytes.
 * Therefore, there are two potential sources of bugs: 1) a proper hash collision where two distinct
 * streams of bytes produce the same digest, and 2) a programming oversight whereby two unequal
 * values produce the same bytes, or conversely, two equal values produce distinct bytes.
 * 
 * 
 * The case of a hash collision is statistically very unlikely, so we just need to ensure a
 * one-to-one relationship between equality classes of values and their byte representation. A good
 * way to do this is to literally serialize the values such that there is enough information to
 * unambiguously deserialize them. For example, when serializing a list of strings ([ ][.addStrings], it is enough to write each string's content along with its length, plus the overall
 * number of strings in the list. This ensures that no other list of strings can generate the same
 * bytes. Note that it is not necessary to avoid collisions between different fingerprinting methods
 * (e.g., between [.addStrings] and [.addString]) because the caller will only use one
 * or the other in a given context, or else the user is required to write a disambiguating tag if
 * both are possible.
 * 
 * @see java.security.MessageDigest
 */
class Fingerprint @kotlin.jvm.JvmOverloads constructor(digestFunction: DigestHashFunction = DigestHashFunction.Companion.SHA256) :
    BytesSink {
    // Make novel use of a CodedOutputStream, which is good at efficiently serializing data. By
    // flushing at the end of each digest we can continue to use the stream.
    private val codedOut: CodedOutputStream
    private val messageDigest: MessageDigest

    /** Creates and initializes a new instance.  */
    init {
        messageDigest = digestFunction.newMessageDigest()
        // This is a lot of indirection, but CodedOutputStream does a reasonable job of converting
        // strings to bytes without creating a whole bunch of garbage, which pays off.
        codedOut =
            CodedOutputStream.newInstance(
                DigestOutputStream(com.google.common.io.ByteStreams.nullOutputStream(), messageDigest),  /*bufferSize=*/
                1024
            )
    }

    /**
     * Completes the hash computation by doing final operations and resets the underlying state,
     * allowing this instance to be used again.
     * 
     * @return the digest as a 16-byte array
     * @see java.security.MessageDigest.digest
     */
    fun digestAndReset(): ByteArray? {
        try {
            codedOut.flush()
        } catch (e: IOException) {
            throw java.lang.IllegalStateException("failed to flush", e)
        }
        return messageDigest.digest()
    }

    /**
     * Completes the hash computation by doing final operations and resets the underlying state,
     * allowing this instance to be used again.
     * 
     * 
     * Instead of returning a digest, this method writes the digest straight into the supplied byte
     * array, at the given offset.
     * 
     * @see java.security.MessageDigest.digest
     */
    fun digestAndReset(buf: ByteArray?, offset: Int, len: Int) {
        try {
            codedOut.flush()
            messageDigest.digest(buf, offset, len)
        } catch (e: IOException) {
            throw java.lang.IllegalStateException("failed to flush", e)
        } catch (e: DigestException) {
            throw java.lang.IllegalStateException("failed to digest", e)
        }
    }

    /** Same as [.digestAndReset], except returns the digest in hex string form.  */
    fun hexDigestAndReset(): String {
        return Companion.hexDigest(digestAndReset()!!)
    }

    /**
     * Appends the specified bytes to the fingerprint message. Same as [.addBytes], but
     * faster when only a [ByteString] is available.
     * 
     * 
     * The fingerprint directly injects the bytes with no framing or tags added. Thus, not
     * guaranteed to be unambiguous; especially if input length is data-dependent.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addBytes(bytes: ByteString): Fingerprint {
        try {
            codedOut.writeRawBytes(bytes)
        } catch (e: IOException) {
            throw java.lang.IllegalStateException("failed to write bytes", e)
        }
        return this
    }

    /** Appends the specified bytes to the fingerprint message.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addBytes(input: ByteArray): Fingerprint {
        addBytes(input, 0, input.size)
        return this
    }

    /**
     * Appends the specified bytes to the fingerprint message, starting at offset.
     * 
     * 
     * The bytes are directly injected into the fingerprint with no framing or tags added. Thus,
     * not guaranteed to be unambiguous; especially if len is data-dependent.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addBytes(input: ByteArray?, offset: Int, len: Int): Fingerprint {
        try {
            codedOut.write(input, offset, len)
        } catch (e: IOException) {
            throw java.lang.IllegalStateException("failed to write bytes", e)
        }
        return this
    }

    // implementation of BytesSink
    override fun acceptBytes(buf: ByteArray?, offset: Int, len: Int) {
        addBytes(buf, offset, len)
    }

    /** Updates the digest with a boolean value.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addBoolean(input: Boolean): Fingerprint {
        try {
            codedOut.writeBoolNoTag(input)
        } catch (e: IOException) {
            throw java.lang.IllegalStateException("failed to write bool", e)
        }
        return this
    }

    /** Same as [.addBoolean], except considers nullability.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addNullableBoolean(input: Boolean?): Fingerprint {
        if (input == null) {
            addBoolean(false)
        } else {
            addBoolean(true)
            addBoolean(input)
        }
        return this
    }

    /** Appends an int to the fingerprint message.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addInt(x: Int): Fingerprint {
        try {
            codedOut.writeInt32NoTag(x)
        } catch (e: IOException) {
            throw java.lang.IllegalStateException("failed to write int", e)
        }
        return this
    }

    /** Appends a long to the fingerprint message.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addLong(x: Long): Fingerprint {
        try {
            codedOut.writeInt64NoTag(x)
        } catch (e: IOException) {
            throw java.lang.IllegalStateException("failed to write long", e)
        }
        return this
    }

    /** Same as [.addInt], except considers nullability.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addNullableInt(input: Int?): Fingerprint {
        if (input == null) {
            addBoolean(false)
        } else {
            addBoolean(true)
            addInt(input)
        }
        return this
    }

    /** Appends a [UUID] to the fingerprint message.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addUUID(uuid: UUID): Fingerprint {
        addLong(uuid.getLeastSignificantBits())
        addLong(uuid.getMostSignificantBits())
        return this
    }

    /** Appends a String to the fingerprint message.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addString(input: String?): Fingerprint {
        try {
            codedOut.writeStringNoTag(input)
        } catch (e: IOException) {
            throw java.lang.IllegalStateException("failed to write string", e)
        }
        return this
    }

    /** Same as [.addString], except considers nullability.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addNullableString(input: String?): Fingerprint {
        if (input == null) {
            addBoolean(false)
        } else {
            addBoolean(true)
            addString(input)
        }
        return this
    }

    /** Appends a [Path] to the fingerprint message.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addPath(input: com.google.devtools.build.lib.vfs.Path): Fingerprint {
        addString(input.getPathString())
        return this
    }

    /** Appends a [PathFragment] to the fingerprint message.  */
    fun addPath(input: PathFragment): Fingerprint {
        return addString(input.getPathString())
    }

    /**
     * Appends a collection of strings to the fingerprint message as a unit. The collection must have
     * a deterministic iteration order.
     * 
     * 
     * The fingerprint effectively records the sequence of calls, not just the elements. That is,
     * addStrings(x+y).addStrings(z) is different from addStrings(x).addStrings(y+z).
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addStrings(inputs: MutableCollection<String?>): Fingerprint {
        addInt(inputs.size)
        for (input in inputs) {
            addString(input)
        }

        return this
    }

    /**
     * Appends an arbitrary sequence of Strings as a unit.
     * 
     * 
     * This is slightly less efficient than [.addStrings].
     */
    // TODO(b/150312032): Deprecate this method.
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addIterableStrings(inputs: Iterable<String?>): Fingerprint {
        for (input in inputs) {
            addBoolean(true)
            addString(input)
        }
        addBoolean(false)

        return this
    }

    /** Updates the digest with the supplied map.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addStringMap(inputs: MutableMap<String?, String?>): Fingerprint {
        addInt(inputs.size)
        for (entry in inputs.entries) {
            addString(entry.key)
            addString(entry.value)
        }

        return this
    }

    /** Like [.addStrings] but for [PathFragment].  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addPaths(inputs: MutableCollection<PathFragment>): Fingerprint {
        addInt(inputs.size)
        for (input in inputs) {
            addPath(input)
        }
        return this
    }

    companion object {
        private fun hexDigest(digest: ByteArray): String {
            val b: java.lang.StringBuilder = java.lang.StringBuilder(32)
            for (i in digest.indices) {
                val n = digest[i].toInt()
                b.append("0123456789abcdef".get((n shr 4) and 0xF))
                b.append("0123456789abcdef".get(n and 0xF))
            }
            return b.toString()
        }

        // -------- Convenience methods ----------------------------
        /**
         * Computes the hex digest from a String using UTF8 encoding and returning the hexDigest().
         * 
         * @param input the String from which to compute the digest
         */
        @kotlin.jvm.JvmStatic
        fun getHexDigest(input: String): String {
            // TODO(b/112460990): This convenience method should
            // use the value from DigestHashFunction.getDefault(). However, this gets called during class
            // loading in a few places, before setDefault() has been called, so these call-sites should be
            // removed before this can be done safely.
            return hexDigest(
                DigestHashFunction.Companion.SHA256.newMessageDigest()
                    .digest(input.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            )
        }
    }
}
