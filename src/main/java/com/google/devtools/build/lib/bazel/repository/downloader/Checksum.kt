// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Ascii
import com.google.common.hash.HashCode
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache
import java.io.IOException
import java.util.*

/** The content checksum for an HTTP download, which knows its own type.  */
class Checksum private constructor(
    val keyType: DownloadCache.KeyType,
    val hashCode: HashCode,
    private val useSubresourceIntegrity: Boolean
) {
    /** Exception thrown to indicate that a string is not a valid checksum for that key type.  */
    class InvalidChecksumException : Exception {
        private constructor(
            keyType: DownloadCache.KeyType?,
            hash: String?
        ) : super("Invalid " + keyType + " checksum '" + hash + "'")

        private constructor(msg: String?) : super(msg)

        private constructor(msg: String?, cause: Throwable?) : super(msg, cause)
    }

    /** Exception thrown to indicate that a checksum is missing.  */
    class MissingChecksumException(message: String?) : IOException(message)

    fun toSubresourceIntegrity(): String {
        return toSubresourceIntegrity(keyType, hashCode)
    }

    override fun toString(): String {
        return hashCode.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other is Checksum) {
            return keyType == other.keyType && hashCode == other.hashCode
        }
        return false
    }

    override fun hashCode(): Int {
        return hashCode.hashCode() * 31 + keyType.hashCode()
    }

    fun emitOtherHashInSameFormat(otherHash: HashCode): String {
        if (useSubresourceIntegrity) {
            return toSubresourceIntegrity(keyType, otherHash)
        } else {
            return otherHash.toString()
        }
    }

    companion object {
        /** Constructs a new Checksum for a given key type and hash, in hex format.  */
        @Throws(InvalidChecksumException::class)
        fun fromString(keyType: DownloadCache.KeyType, hash: String): Checksum {
            return fromString(keyType, hash,  /* useSubresourceIntegrity= */false)
        }

        @Throws(InvalidChecksumException::class)
        private fun fromString(
            keyType: DownloadCache.KeyType,
            hash: String,
            useSubresourceIntegrity: Boolean
        ): Checksum {
            if (!keyType.isValid(hash)) {
                throw InvalidChecksumException(keyType, hash)
            }
            return Checksum(
                keyType, HashCode.fromString(Ascii.toLowerCase(hash)), useSubresourceIntegrity
            )
        }

        @Throws(InvalidChecksumException::class)
        private fun base64Decode(data: String?): ByteArray {
            try {
                return Base64.getDecoder().decode(data)
            } catch (e: IllegalArgumentException) {
                throw InvalidChecksumException("Invalid base64 '" + data + "'", e)
            }
        }

        /** Constructs a new Checksum from a hash in Subresource Integrity format.  */
        @kotlin.jvm.JvmStatic
        @Throws(InvalidChecksumException::class)
        fun fromSubresourceIntegrity(integrity: String): Checksum {
            val keyType: DownloadCache.KeyType?
            val hash: ByteArray
            val expectedLength: Int

            if (integrity.startsWith("sha1-")) {
                keyType = DownloadCache.KeyType.SHA1
                expectedLength = 20
                hash = base64Decode(integrity.substring(5))
            } else if (integrity.startsWith("sha256-")) {
                keyType = DownloadCache.KeyType.SHA256
                expectedLength = 32
                hash = base64Decode(integrity.substring(7))
            } else if (integrity.startsWith("sha384-")) {
                keyType = DownloadCache.KeyType.SHA384
                expectedLength = 48
                hash = base64Decode(integrity.substring(7))
            } else if (integrity.startsWith("sha512-")) {
                keyType = DownloadCache.KeyType.SHA512
                expectedLength = 64
                hash = base64Decode(integrity.substring(7))
            } else if (integrity.startsWith("blake3-")) {
                keyType = DownloadCache.KeyType.BLAKE3
                expectedLength = 32
                hash = base64Decode(integrity.substring(7))
            } else {
                throw InvalidChecksumException(
                    ("Unsupported checksum algorithm: '"
                            + integrity
                            + "' (expected SHA-1, SHA-256, SHA-384, or SHA-512)")
                )
            }

            if (hash.size != expectedLength) {
                throw InvalidChecksumException(
                    "Invalid " + keyType + " SRI checksum '" + integrity + "'"
                )
            }

            return fromString(
                keyType, HashCode.fromBytes(hash).toString(),  /* useSubresourceIntegrity= */true
            )
        }

        private fun toSubresourceIntegrity(keyType: DownloadCache.KeyType, hashCode: HashCode): String {
            val encoded = Base64.getEncoder().encodeToString(hashCode.asBytes())
            return keyType.getHashName() + "-" + encoded
        }
    }
}
