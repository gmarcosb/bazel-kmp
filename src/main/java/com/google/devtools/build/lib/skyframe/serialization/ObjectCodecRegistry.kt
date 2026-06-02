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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.DynamicCodec
import com.google.devtools.build.lib.skyframe.serialization.EnumCodec
import com.google.devtools.build.lib.skyframe.serialization.LambdaCodec
import com.google.devtools.build.lib.skyframe.serialization.MessageLiteCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.SerializationException.NoCodecException
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.MessageLite
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Registry class for handling [ObjectCodec] mappings. Codecs are indexed by [String]
 * classifiers and assigned deterministic numeric identifiers for more compact on-the-wire
 * representation if desired.
 */
class ObjectCodecRegistry private constructor(
    memoizingCodecs: com.google.common.collect.ImmutableSet<ObjectCodec<*>?>,
    referenceConstants: com.google.common.collect.ImmutableList<Any>,
    classNames: com.google.common.collect.ImmutableSortedSet<String?>,
    excludedClassNamePrefixes: com.google.common.collect.ImmutableList<String?>,
    allowDefaultCodec: Boolean,
    computeChecksum: Boolean
) {
    private val allowDefaultCodec: Boolean

    private val classMappedCodecs: ConcurrentMap<java.lang.Class<*>?, CodecDescriptor?>
    private val tagMappedCodecs: com.google.common.collect.ImmutableList<CodecDescriptor?>

    private val referenceConstantsStartTag: Int
    private val referenceConstantsMap: IdentityHashMap<Any?, Int?>
    private val referenceConstants: com.google.common.collect.ImmutableList<Any>

    /** This is sorted, but we need index-based access.  */
    private val classNames: com.google.common.collect.ImmutableList<String>

    private val dynamicCodecs: IdentityHashMap<String?, java.util.function.Supplier<CodecDescriptor>?>

    private val checksum: ByteArray?

    init {
        // Mimic what com.google.devtools.build.lib.util.Fingerprint does. Using it directly would
        // require untangling a circular dependency.
        var messageDigest: MessageDigest? = null
        var checksum: CodedOutputStream? = null
        if (computeChecksum) {
            messageDigest = MessageDigest.getInstance("SHA-256")
            checksum =
                CodedOutputStream.newInstance(
                    DigestOutputStream(
                        com.google.common.io.ByteStreams.nullOutputStream(),
                        messageDigest
                    ),  /*bufferSize=*/
                    1024
                )
            checksum.writeBoolNoTag(allowDefaultCodec)
        }
        this.allowDefaultCodec = allowDefaultCodec

        var nextTag = 1 // 0 is reserved for null.
        this.classMappedCodecs =
            ConcurrentHashMap<java.lang.Class<*>?, CodecDescriptor?>(
                memoizingCodecs.size(), 0.75f, java.lang.Runtime.getRuntime().availableProcessors()
            )
        val tagMappedMemoizingCodecsBuilder: com.google.common.collect.ImmutableList.Builder<CodecDescriptor?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<CodecDescriptor?>(memoizingCodecs.size())
        nextTag =
            processCodecs(
                memoizingCodecs, nextTag, tagMappedMemoizingCodecsBuilder, classMappedCodecs, checksum
            )
        this.tagMappedCodecs = tagMappedMemoizingCodecsBuilder.build()

        referenceConstantsStartTag = nextTag
        referenceConstantsMap = IdentityHashMap<Any?, Int?>()
        for (constant in referenceConstants) {
            referenceConstantsMap.put(constant, nextTag)
            addToChecksum(checksum, nextTag, constant.getClass().getName())
            nextTag++
        }
        this.referenceConstants = referenceConstants

        this.classNames =
            classNames.stream()
                .filter(java.util.function.Predicate { str: String? ->
                    Companion.isAllowed(
                        str!!,
                        excludedClassNamePrefixes
                    )
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
        this.dynamicCodecs = createDynamicCodecs(this.classNames, nextTag, checksum)
        if (computeChecksum) {
            checksum.flush()
            this.checksum = messageDigest.digest()
        } else {
            this.checksum = null
        }
    }

    @Throws(NoCodecException::class)
    fun getCodecDescriptorForObject(obj: Any): CodecDescriptor? {
        var type: java.lang.Class<*> = obj.getClass()
        val descriptor = getCodecDescriptor(type)
        if (descriptor != null) {
            return descriptor
        }
        if (!allowDefaultCodec) {
            throw NoCodecException(
                "No codec available for " + type + " and default fallback disabled"
            )
        }
        if (obj is Enum<*>) {
            // Enums must be serialized using declaring class.
            type = obj.getDeclaringClass()
        }
        return getDynamicCodecDescriptor(type.getName(), type)
    }

    /**
     * Returns a [CodecDescriptor] for the given type or null if none found.
     * 
     * 
     * Also checks if there are codecs for a superclass of the given type.
     */
    private fun getCodecDescriptor(type: java.lang.Class<*>?): CodecDescriptor? {
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return null
    }

    fun maybeGetConstantByTag(tag: Int): Any? {
        if (referenceConstantsStartTag <= tag
            && tag < referenceConstantsStartTag + referenceConstants.size()
        ) {
            return referenceConstants.get(tag - referenceConstantsStartTag)
        }
        return null
    }

    fun maybeGetTagForConstant(`object`: Any?): Int? {
        return referenceConstantsMap.get(`object`)
    }

    /** Returns the [CodecDescriptor] associated with the supplied tag.  */
    @Throws(NoCodecException::class)
    fun getCodecDescriptorByTag(tag: Int): CodecDescriptor? {
        var tagOffset = tag - 1 // 0 reserved for null
        if (tagOffset < 0) {
            throw NoCodecException("No codec available for tag " + tag)
        }
        if (tagOffset < tagMappedCodecs.size()) {
            return tagMappedCodecs.get(tagOffset)
        }

        tagOffset -= tagMappedCodecs.size()
        tagOffset -= referenceConstants.size()
        if (!allowDefaultCodec || tagOffset < 0 || tagOffset >= classNames.size()) {
            throw NoCodecException("No codec available for tag " + tag)
        }
        return getDynamicCodecDescriptor(classNames.get(tagOffset),  /*type=*/null)
    }

    /**
     * Returns a checksum computed from the tag mappings that make up this registry.
     * 
     * 
     * The checksum can be used to ensure consistent serialization semantics across servers.
     * 
     * 
     * Returns `null` if this instance was not configured to compute a checksum via [ ][Builder.computeChecksum].
     */
    fun getChecksum(): ByteArray? {
        return if (checksum == null) null else checksum.clone()
    }

    /**
     * Creates a builder using the current contents of this registry.
     * 
     * 
     * This is much more efficient than scanning multiple times.
     */
    fun getBuilder(): Builder {
        val builder = newBuilder()
        builder.setAllowDefaultCodec(allowDefaultCodec)
        for (entry in classMappedCodecs.entrySet()) {
            builder.add(entry.getValue().codec)
        }

        for (constant in referenceConstants) {
            builder.addReferenceConstant(constant)
        }

        for (className in classNames) {
            builder.addClassName(className)
        }
        return builder
    }

    fun classNames(): com.google.common.collect.ImmutableList<String> {
        return classNames
    }

    /**
     * Describes encoding logic.
     * 
     * @param tag Unique identifier for the associated codec. Intended to be used as a compact
     * on-the-wire representation of an encoded object's type. Returns a value ≥ 1. 0 is a special
     * tag representing null while negative numbers are reserved for backreferences.
     * @param codec The underlying codec.
     */
    internal class CodecDescriptor(tag: Int, codec: ObjectCodec<*>?) {
        val tag: Int
        val codec: ObjectCodec<*>?

        init {
            // Check that the tag is not a reserved value.
            this.codec = codec
            this.tag = tag
            com.google.common.base.Preconditions.checkArgument(tag >= 1)
        }
    }

    /** Builder for [ObjectCodecRegistry].  */
    class Builder {
        private val codecs: MutableMap<java.lang.Class<*>?, ObjectCodec<*>?> =
            HashMap<java.lang.Class<*>?, ObjectCodec<*>?>()
        private val referenceConstantsBuilder: com.google.common.collect.ImmutableList.Builder<Any?> =
            com.google.common.collect.ImmutableList.builder<Any?>()
        private val classNames: com.google.common.collect.ImmutableSortedSet.Builder<String?> =
            com.google.common.collect.ImmutableSortedSet.naturalOrder<String?>()
        private val excludedClassNamePrefixes: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        private var allowDefaultCodec = true
        private var computeChecksum = false

        /**
         * Adds the given codec. If a codec for this codec's encoded class already exists in the
         * registry, it is overwritten.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(codec: ObjectCodec<*>): Builder {
            codecs.put(codec.getEncodedClass(), codec)
            return this
        }

        /**
         * Set whether or not we allow fallback to java serialization when no matching codec is found.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setAllowDefaultCodec(allowDefaultCodec: Boolean): Builder {
            this.allowDefaultCodec = allowDefaultCodec
            return this
        }

        /**
         * Adds a constant value by reference. Any value encountered during serialization which `== object` will be replaced by `object` upon deserialization. Interned objects and
         * effective singletons are ideal for reference constants.
         * 
         * 
         * These constants should be interned or effectively interned: it should not be possible to
         * create objects that should be considered equal in which one has an element of this list and
         * the other does not, since that would break bit-for-bit equality of the objects' serialized
         * bytes when used in [com.google.devtools.build.skyframe.SkyKey]s.
         * 
         * 
         * Note that even [Boolean] does not satisfy this constraint, since `new Boolean(true)` is allowed, but upon deserialization, when a `boolean` is boxed to a
         * [Boolean], it will always be [Boolean.TRUE] or [Boolean.FALSE].
         * 
         * 
         * The same is not true for an empty [ImmutableList], since an empty non-[ ] will not serialize to an [ImmutableList], and so won't be deserialized
         * to an empty [ImmutableList]. If an object has a list field, and one codepath passes in
         * an empty [ArrayList] and another passes in an empty [ImmutableList], and two
         * objects constructed in this way can be considered equal, then those two objects already do
         * not serialize bit-for-bit identical disregarding this list of constants, since the list
         * object's codec will be different for the two objects.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addReferenceConstant(`object`: Any): Builder {
            referenceConstantsBuilder.add(`object`)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addReferenceConstants(referenceConstants: Iterable<*>): Builder {
            referenceConstantsBuilder.addAll(referenceConstants)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addClassName(className: String): Builder {
            classNames.add(className)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun excludeClassNamePrefix(classNamePrefix: String): Builder {
            excludedClassNamePrefixes.add(classNamePrefix)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun computeChecksum(computeChecksum: Boolean): Builder {
            this.computeChecksum = computeChecksum
            return this
        }

        fun build(): ObjectCodecRegistry {
            try {
                return ObjectCodecRegistry(
                    com.google.common.collect.ImmutableSet.copyOf<ObjectCodec<*>?>(codecs.values()),
                    referenceConstantsBuilder.build(),
                    classNames.build(),
                    excludedClassNamePrefixes.build(),
                    allowDefaultCodec,
                    computeChecksum
                )
            } catch (e: IOException) {
                throw java.lang.IllegalStateException("Unexpected exception while building codec registry", e)
            } catch (e: NoSuchAlgorithmException) {
                throw java.lang.IllegalStateException("Unexpected exception while building codec registry", e)
            }
        }
    }

    @Throws(NoCodecException::class)
    private fun getDynamicCodecDescriptor(className: String?, type: java.lang.Class<*>?): CodecDescriptor? {
        val supplier: java.util.function.Supplier<CodecDescriptor>? = dynamicCodecs.get(className)
        if (supplier != null) {
            val descriptor: CodecDescriptor = supplier.get()
            if (descriptor == null) {
                throw NoCodecException(
                    "There was a problem creating a codec for " + className + ". Check logs for details",
                    type
                )
            }
            return descriptor
        }
        if (type != null && LambdaCodec.Companion.isProbablyLambda(type)) {
            if (java.io.Serializable::class.java.isAssignableFrom(type)) {
                // LambdaCodec is hidden away as a codec for Serializable. This avoids special-casing it in
                // all places we look up a codec, and doesn't clash with anything else because Serializable
                // is an interface, not a class.
                return classMappedCodecs.get(java.io.Serializable::class.java)
            } else {
                throw NoCodecException(
                    ("No default codec available for "
                            + className
                            + ". If this is a lambda, try casting it to (type & Serializable), like "
                            + "(Supplier<String> & Serializable)"),
                    type
                )
            }
        }
        throw NoCodecException(
            "No default codec available for " + className, type
        )
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("checksum", checksum)
            .add("allowDefaultCodec", allowDefaultCodec)
            .add("classMappedCodecs.size", classMappedCodecs.size())
            .add("tagMappedCodecs.size", tagMappedCodecs.size())
            .add("referenceConstantsStartTag", referenceConstantsStartTag)
            .add("referenceConstants.size", referenceConstants.size())
            .add("classNames.size", classNames.size())
            .add("dynamicCodecs.size", dynamicCodecs.size())
            .toString()
    }

    companion object {
        /** Creates a new, empty builder.  */
        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder()
        }

        @Throws(IOException::class)
        private fun processCodecs(
            memoizingCodecs: Iterable<out ObjectCodec<*>?>,
            nextTag: Int,
            tagMappedCodecsBuilder: com.google.common.collect.ImmutableList.Builder<CodecDescriptor?>,
            codecsBuilder: ConcurrentMap<java.lang.Class<*>?, CodecDescriptor?>,
            checksum: CodedOutputStream?
        ): Int {
            // First, register all codecs and their monotonically increasing tag numbers in a stable
            // alphabetic sort order, using their primary encoded class as the key.
            val sortedCodecDescriptors: com.google.common.collect.ImmutableList<CodecDescriptor?> =
                com.google.common.collect.Streams.mapWithIndex( // Sort the codecs by their primary encoded class name.
                    com.google.common.collect.Streams.stream(memoizingCodecs)
                        .sorted(java.util.Comparator.comparing { o: ObjectCodec<*>? ->
                            o.getEncodedClass().getName()
                        }),  // Then create a codec descriptor for each codec.
                    { codec: ObjectCodec<*>?, idx: Long ->  // idx is small enough to be casted from long to int without loss of
                        // information.
                        CodecDescriptor(idx.toInt() + nextTag, codec)
                    })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<CodecDescriptor?>())

            // Then, perform checksumming and check that there's a unique codec descriptor for each encoded
            // class.
            for (codecDescriptor in sortedCodecDescriptors) {
                addToChecksum(checksum, codecDescriptor.tag, codecDescriptor.codec.getClass().getName())

                val previousCodecDescriptor: CodecDescriptor? =
                    codecsBuilder.put(codecDescriptor.codec.getEncodedClass(), codecDescriptor)
                com.google.common.base.Preconditions.checkState(
                    previousCodecDescriptor == null,
                    "found duplicate codec descriptor for %s, was: %s, new: %s",
                    codecDescriptor.codec.getEncodedClass(),
                    previousCodecDescriptor,
                    codecDescriptor
                )
            }

            // Finally, for all codec descriptors, map their additional encoded classes, and overwrite
            // any existing descriptor mappings.
            for (codecDescriptor in sortedCodecDescriptors) {
                for (otherClass in codecDescriptor.codec.additionalEncodedClasses()) {
                    codecsBuilder.put(otherClass, codecDescriptor)
                }
            }

            // Append all new descriptors into the builder.
            tagMappedCodecsBuilder.addAll(sortedCodecDescriptors)

            return nextTag + sortedCodecDescriptors.size()
        }

        @Throws(IOException::class)
        private fun createDynamicCodecs(
            classNames: com.google.common.collect.ImmutableList<String>, nextTag: Int, checksum: CodedOutputStream?
        ): IdentityHashMap<String?, java.util.function.Supplier<CodecDescriptor>?> {
            var nextTag = nextTag
            val dynamicCodecs: IdentityHashMap<String?, java.util.function.Supplier<CodecDescriptor>?> =
                IdentityHashMap<String?, java.util.function.Supplier<CodecDescriptor>?>(classNames.size())
            for (className in classNames) {
                val tag = nextTag++
                dynamicCodecs.put(
                    className,
                    com.google.common.base.Suppliers.memoize<CodecDescriptor?>(com.google.common.base.Supplier {
                        createDynamicCodecDescriptor(
                            tag,
                            className
                        )
                    })
                )
                addToChecksum(checksum, tag, className)
            }
            return dynamicCodecs
        }

        @Throws(IOException::class)
        private fun addToChecksum(checksum: CodedOutputStream?, tag: Int, className: String) {
            var className = className
            if (checksum != null) {
                checksum.writeInt32NoTag(tag)

                // Trim class names of lambdas to the enclosing class. The lambda class itself is named
                // nondeterministically.
                val lambdaIndex: Int = className.indexOf("$\$Lambda")
                if (lambdaIndex != -1) {
                    className = className.substring(0, lambdaIndex)
                }
                checksum.writeStringNoTag(className)
            }
        }

        private fun isAllowed(
            className: String, excludedClassNamePefixes: com.google.common.collect.ImmutableList<String?>
        ): Boolean {
            for (excludedClassNamePrefix in excludedClassNamePefixes) {
                if (className.startsWith(excludedClassNamePrefix)) {
                    return false
                }
            }
            return true
        }

        /** For enums, this method must only be called for the declaring class.  */
        private fun createDynamicCodecDescriptor(tag: Int, className: String?): CodecDescriptor? {
            try {
                val type: java.lang.Class<*> = java.lang.Class.forName(className)
                if (type.isEnum()) {
                    return createCodecDescriptorForEnum(tag, type)
                }
                if (MessageLite::class.java.isAssignableFrom(type)) {
                    return createCodecDescriptorForProto(tag, type)
                }
                return CodecDescriptor(tag, DynamicCodec(type))
            } catch (e: java.lang.ReflectiveOperationException) {
                com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    "Could not create codec for type: " + className,
                    e
                )
                    .printStackTrace()
                return null
            }
        }

        private fun createCodecDescriptorForEnum(tag: Int, enumType: java.lang.Class<*>): CodecDescriptor {
            return CodecDescriptor(tag, EnumCodec<Any?>(enumType))
        }

        private fun createCodecDescriptorForProto(tag: Int, protoType: java.lang.Class<*>?): CodecDescriptor {
            return CodecDescriptor(tag, MessageLiteCodec(protoType as java.lang.Class<out MessageLite?>?))
        }
    }
}
