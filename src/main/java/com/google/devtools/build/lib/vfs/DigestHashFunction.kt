// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.vfs.DigestHashFunction.DigestLength.DigestLengthImpl
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HashMap
import java.util.stream.Collectors

/**
 * Type of hash function to use for digesting files.
 * 
 * 
 * This tracks parallel [java.security.MessageDigest] and [HashFunction] interfaces
 * for each provided hash, as Bazel uses both - MessageDigest where performance is critical and
 * HashFunctions where ease-of-use wins over.
 */
// The underlying HashFunctions are immutable and thread safe.
class DigestHashFunction private constructor(
    hashFunction: com.google.common.hash.HashFunction?,
    digestLength: DigestLength?,
    names: com.google.common.collect.ImmutableList<String>
) {
    /** Describes the length of a digest.  */
    interface DigestLength {
        /** Returns the length of a digest by inspecting its bytes. Used for variable-length digests.  */
        fun getDigestLength(bytes: ByteArray?, offset: Int): Int {
            return this.digestMaximumLength
        }

        /** Returns the maximum length a digest can turn into.  */
        val digestMaximumLength: Int

        /** Default implementation that simply returns a fixed length.  */
        class DigestLengthImpl internal constructor(hashFunction: com.google.common.hash.HashFunction) : DigestLength {
            private val length: Int

            init {
                this.length = hashFunction.bits() / 8
            }

            override fun getDigestMaximumLength(): Int {
                return length
            }
        }
    }

    private val hashFunction: com.google.common.hash.HashFunction?
    val digestLength: DigestLength?
    private val name: String
    private val messageDigestPrototype: MessageDigest
    private val messageDigestPrototypeSupportsClone: Boolean
    private val names: com.google.common.collect.ImmutableList<String>

    init {
        this.hashFunction = hashFunction
        this.digestLength = digestLength
        com.google.common.base.Preconditions.checkArgument(!names.isEmpty())
        this.name = names.get(0)
        this.names = names
        this.messageDigestPrototype = this.messageDigestInstance
        this.messageDigestPrototypeSupportsClone = supportsClone(messageDigestPrototype)
    }

    /** Converts a string to its registered [DigestHashFunction].  */
    class DigestFunctionConverter : com.google.devtools.common.options.Converter.Contextless<DigestHashFunction?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): DigestHashFunction? {
            for (possibleFunctions in hashFunctionRegistry.entrySet()) {
                if (possibleFunctions.getKey().equalsIgnoreCase(input)) {
                    return possibleFunctions.getValue()
                }
            }
            throw com.google.devtools.common.options.OptionsParsingException(
                java.lang.String.format(
                    "'%s' is not a valid hash function. Possible values are: %s",
                    input, possibleNames
                )
            )
        }

        val typeDescription: String
            get() = "hash function"
    }

    fun getHashFunction(): com.google.common.hash.HashFunction? {
        return hashFunction
    }

    /** Creates a new [MessageDigest] for this hash function.  */
    fun newMessageDigest(): MessageDigest? {
        if (messageDigestPrototypeSupportsClone) {
            try {
                return messageDigestPrototype.clone() as MessageDigest?
            } catch (e: java.lang.CloneNotSupportedException) {
                // We checked at initialization that this could be cloned, so this should never happen.
                throw java.lang.IllegalStateException("Could not clone message digest", e)
            }
        } else {
            return this.messageDigestInstance
        }
    }

    fun getNames(): com.google.common.collect.ImmutableList<String> {
        return names
    }

    override fun toString(): String {
        return name
    }

    private val messageDigestInstance: MessageDigest
        get() {
            try {
                return MessageDigest.getInstance(name)
            } catch (e: NoSuchAlgorithmException) {
                // We check when we register() this digest function that the message digest exists. This
                // should never happen.
                throw java.lang.IllegalStateException("message digest " + name + " not available", e)
            }
        }

    companion object {
        // This map must be declared first to make sure that calls to register() have it ready.
        private val hashFunctionRegistry: HashMap<String?, DigestHashFunction> = HashMap<String?, DigestHashFunction>()

        @kotlin.jvm.JvmField
        val SHA1: DigestHashFunction
        @kotlin.jvm.JvmField
        val SHA256: DigestHashFunction

        init {
            SHA1 = register(com.google.common.hash.Hashing.sha1(), "SHA-1", "SHA1", "sha1")
            SHA256 = register(com.google.common.hash.Hashing.sha256(), "SHA-256", "SHA256", "sha256")
            register(com.google.common.hash.Hashing.sha384(), "SHA-384", "SHA384", "sha384")
            register(com.google.common.hash.Hashing.sha512(), "SHA-512", "SHA512", "sha512")
        }

        fun register(
            hash: com.google.common.hash.HashFunction, hashName: String, vararg altNames: String?
        ): DigestHashFunction {
            return Companion.register(hash, DigestLengthImpl(hash), hashName, *altNames)
        }

        /**
         * Creates a new DigestHashFunction that is registered to be recognized by its name in [ ].
         * 
         * @param hashName the canonical name for this hash function - and the name that can be used to
         * uncover the MessageDigest.
         * @param altNames alternative names that will be mapped to this function by the converter but
         * will not serve as the canonical name for the DigestHashFunction.
         * @param hash The [HashFunction] to register.
         * @throws IllegalArgumentException if the name is already registered.
         */
        fun register(
            hash: com.google.common.hash.HashFunction?,
            digestLength: DigestLength?,
            hashName: String,
            vararg altNames: String?
        ): DigestHashFunction {
            try {
                MessageDigest.getInstance(hashName)
            } catch (e: NoSuchAlgorithmException) {
                throw java.lang.IllegalArgumentException(
                    "The hash function name provided does not correspond to a valid MessageDigest: "
                            + hashName,
                    e
                )
            }

            val names: com.google.common.collect.ImmutableList<String> =
                com.google.common.collect.ImmutableList.builder<String?>().add(hashName).add(*altNames).build()
            val hashFunction = DigestHashFunction(hash, digestLength, names)
            synchronized(hashFunctionRegistry) {
                for (name in names) {
                    require(!hashFunctionRegistry.containsKey(name)) { "Hash function " + name + " is already registered." }
                    hashFunctionRegistry.put(name, hashFunction)
                }
            }
            return hashFunction
        }

        private fun supportsClone(toCheck: MessageDigest): Boolean {
            try {
                val unused: Any? = toCheck.clone()
                return true
            } catch (e: java.lang.CloneNotSupportedException) {
                return false
            }
        }

        @kotlin.jvm.JvmStatic
        val possibleHashFunctions: com.google.common.collect.ImmutableSet<DigestHashFunction?>
            get() = com.google.common.collect.ImmutableSet.copyOf<DigestHashFunction?>(hashFunctionRegistry.values())

        private val possibleNames: String?
            get() = hashFunctionRegistry.values().stream()
                .map<String?>(java.util.function.Function { obj: DigestHashFunction? -> obj.toString() })
                .sorted()
                .distinct()
                .collect(Collectors.joining(", "))

        fun getHashFunctionFromName(hashName: String?): com.google.common.hash.HashFunction? {
            val digestHashFunction: DigestHashFunction = hashFunctionRegistry.get(hashName)
            requireNotNull(digestHashFunction) {
                java.lang.String.format(
                    "Hash function '%s' is not registered. Possible values are: %s",
                    hashName, possibleNames
                )
            }
            return digestHashFunction.getHashFunction()
        }
    }
}
