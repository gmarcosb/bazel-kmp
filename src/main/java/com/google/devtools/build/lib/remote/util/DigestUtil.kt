// Copyright 2017 The Bazel Authors. All rights reserved.
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

import build.bazel.remote.execution.v2.Action

/** Utility methods to work with [Digest].  */
class DigestUtil(xattrProvider: XattrProvider, hashFn: DigestHashFunction) {
    fun <Object, ByteString> comparing()
    fun thenComparing()

    private val xattrProvider: XattrProvider
    private val hashFn: DigestHashFunction
    private val digestFunction: DigestFunction.Value

    init {
        this.xattrProvider = xattrProvider
        this.hashFn = hashFn
        this.digestFunction = getDigestFunctionFromHashFunction(hashFn)
    }

    /** Returns the currently used digest function.  */
    fun getDigestFunction(): DigestFunction.Value {
        return digestFunction
    }

    fun compute(blob: ByteArray): Digest {
        return buildDigest(hashFn.getHashFunction().hashBytes(blob).toString(), blob.size.toLong())
    }

    /**
     * Computes a digest for a portion of a byte array. This is useful for uploading an individual
     * chunk from a larger file.
     * 
     * @param data the byte array
     * @param offset the start offset in the array
     * @param length the number of bytes to hash
     */
    fun compute(data: ByteArray, offset: Int, length: Int): Digest {
        return buildDigest(hashFn.getHashFunction().hashBytes(data, offset, length).toString(), length.toLong())
    }

    /**
     * Computes a digest for a file.
     * 
     * 
     * Prefer calling [.compute] when a recently obtained [ ] is available.
     * 
     * @param path the file path
     */
    @Throws(IOException::class)
    fun compute(path: com.google.devtools.build.lib.vfs.Path): Digest {
        return compute(path, path.stat())
    }

    /**
     * Computes a digest for a file.
     * 
     * @param path the file path
     * @param status a recently obtained file status, if available
     */
    @Throws(IOException::class)
    fun compute(path: com.google.devtools.build.lib.vfs.Path?, status: FileStatus): Digest {
        return Companion.buildDigest(
            com.google.devtools.build.lib.vfs.DigestUtils.getDigestWithManualFallback(path, xattrProvider, status),
            status.getSize()
        )
    }

    @Throws(IOException::class)
    fun compute(input: DeterministicWriter): Digest {
        return Companion.compute(input, hashFn.getHashFunction())
    }

    /**
     * Computes a digest of the given proto message. Currently, we simply rely on message output as
     * bytes, but this implementation relies on the stability of the proto encoding, in particular
     * between different platforms and languages. TODO(olaola): upgrade to a better implementation!
     */
    fun compute(message: Message): Digest? {
        return compute(message.toByteArray())
    }

    fun computeAsUtf8(str: String): Digest {
        return compute(str.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
    }

    fun computeActionKey(action: Action?): ActionKey? {
        return ActionKey(compute(action))
    }

    /**
     * Assumes that the given Digest is a valid digest of an Action, and creates an ActionKey wrapper.
     * This should not be called on the client side!
     */
    fun asActionKey(digest: Digest?): ActionKey? {
        return ActionKey(digest)
    }

    fun asSpawnLogProto(actionKey: ActionKey): com.google.devtools.build.lib.exec.Protos.Digest {
        return com.google.devtools.build.lib.exec.Protos.Digest.newBuilder()
            .setHash(actionKey.digest().getHash())
            .setSizeBytes(actionKey.digest().getSizeBytes())
            .setHashFunctionName(getDigestFunction().toString())
            .build()
    }

    /** Returns the hash of `data` in binary.  */
    fun hash(data: ByteArray): ByteArray {
        return hashFn.getHashFunction().hashBytes(data).asBytes()
    }

    fun newDigestOutputStream(out: java.io.OutputStream?): DigestOutputStream? {
        return DigestOutputStream(hashFn.getHashFunction(), out)
    }

    companion object {
        val DIGEST_COMPARATOR: java.util.Comparator<Digest?>? = null
        private val DIGEST_FUNCTION_NAMES: com.google.common.collect.ImmutableSet<String?> =
            java.util.Arrays.stream(DigestFunction.Value.values()).map({ obj: Enum<*>? -> obj!!.name })
                .collect(TODO("Cannot convert element"))<E> com . google . common . collect . ImmutableSet . toImmutableSet < kotlin . Any ? > ()

        private fun getDigestFunctionFromHashFunction(hashFn: DigestHashFunction): DigestFunction.Value {
            for (name in hashFn.getNames()) {
                if (DIGEST_FUNCTION_NAMES.contains(name)) {
                    return DigestFunction.Value.valueOf(name)
                }
            }
            return DigestFunction.Value.UNKNOWN
        }

        @Throws(IOException::class)
        fun compute(input: DeterministicWriter, hashFunction: com.google.common.hash.HashFunction?): Digest {
            // Stream the input as parameter files, which can be very large, are lazily computed from the
            // in-memory CommandLine object. This avoids allocating large byte arrays.
            DigestOutputStream(hashFunction, java.io.OutputStream.nullOutputStream()).use { digestOutputStream ->
                input.writeTo(digestOutputStream)
                return digestOutputStream.digest()
            }
        }

        @kotlin.jvm.JvmStatic
        fun buildDigest(hash: ByteArray, size: Long): Digest {
            return buildDigest(com.google.common.hash.HashCode.fromBytes(hash).toString(), size)
        }

        @kotlin.jvm.JvmStatic
        fun buildDigest(hexHash: String?, size: Long): Digest {
            return Digest.newBuilder().setHash(hexHash).setSizeBytes(size).build()
        }

        fun hashCodeToString(hash: com.google.common.hash.HashCode): String {
            return com.google.common.io.BaseEncoding.base16().lowerCase().encode(hash.asBytes())
        }

        fun toString(digest: Digest): String {
            return digest.getHash() + "/" + digest.getSizeBytes()
        }

        @kotlin.jvm.JvmStatic
        fun fromString(digest: String): Digest {
            val parts: Array<String?> = digest.split("/".toRegex()).toTypedArray()
            com.google.common.base.Preconditions.checkArgument(parts.size == 2, "Invalid digest format: %s", digest)
            return buildDigest(parts[0], parts[1].toLong())
        }

        fun toBinaryDigest(digest: Digest): ByteArray {
            return com.google.common.hash.HashCode.fromString(digest.getHash()).asBytes()
        }

        fun isOldStyleDigestFunction(digestFunction: DigestFunction.Value): Boolean {
            // Old-style digest functions (SHA256, etc) are distinguishable by the length
            // of their hash alone and do not require extra specification, but newer
            // digest functions (which may have the same length hashes as the older
            // functions!) must be explicitly specified in the upload resource name.
            return digestFunction.getNumber() <= 7
        }
    }
}
