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

import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec.MemoizationStrategy

/** Tests for [DeserializationContext].  */
@RunWith(TestParameterInjector::class)
class DeserializationContextTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nullDeserialize(@TestParameter useLeaf: Boolean) {
        val registry: ObjectCodecRegistry? = Mockito.mock<ObjectCodecRegistry?>(ObjectCodecRegistry::class.java)
        val codedInputStream: CodedInputStream = Mockito.mock<CodedInputStream>(CodedInputStream::class.java)
        Mockito.`when`<Int?>(codedInputStream.readSInt32()).thenReturn(0)
        val deserializationContext: DeserializationContext =
            ImmutableDeserializationContext(registry, com.google.common.collect.ImmutableClassToInstanceMap.of<B?>())
        if (useLeaf) {
            // Deserialization doesn't touch the codec if the value is null.
            assertThat(
                deserializationContext.< Object > deserializeLeaf < kotlin . Any ? > (codedInputStream,  /* codec= */
                null
            ))
            .isNull()
        } else {
            Truth.assertThat(deserializationContext.deserialize(codedInputStream) as Any?).isNull()
        }
        Mockito.verify<CodedInputStream?>(codedInputStream).readSInt32()
        Mockito.verifyNoInteractions(registry)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constantDeserialize(@TestParameter useLeaf: Boolean) {
        val registry: ObjectCodecRegistry = Mockito.mock<ObjectCodecRegistry>(ObjectCodecRegistry::class.java)
        val constant = "abcdef"
        Mockito.`when`<T?>(registry.maybeGetConstantByTag(1)).thenReturn(constant)
        val codedInputStream: CodedInputStream = Mockito.mock<CodedInputStream>(CodedInputStream::class.java)
        Mockito.`when`<Int?>(codedInputStream.readSInt32()).thenReturn(1)
        val deserializationContext: DeserializationContext =
            ImmutableDeserializationContext(registry, com.google.common.collect.ImmutableClassToInstanceMap.of<B?>())
        if (useLeaf) {
            assertThat(
                deserializationContext.deserializeLeaf(
                    codedInputStream, LeafCodecForCastingOnly.Companion.INSTANCE
                )
            )
                .isSameInstanceAs(constant)
        } else {
            Truth.assertThat(deserializationContext.deserialize(codedInputStream) as Any?)
                .isSameInstanceAs(constant)
        }
        Mockito.verify<CodedInputStream?>(codedInputStream).readSInt32()
        Mockito.verify<Any?>(registry).maybeGetConstantByTag(1)
    }

    private class LeafCodecForCastingOnly : LeafObjectCodec<String?>() {
        public override fun autoRegister(): Boolean {
            return false
        }

        val encodedClass: java.lang.Class<String?>
            get() = String::class.java

        public override fun serialize(
            context: LeafSerializationContext?, obj: String?, codedOut: CodedOutputStream?
        ) {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun deserialize(context: LeafDeserializationContext?, codedIn: CodedInputStream?): String? {
            throw java.lang.UnsupportedOperationException()
        }

        companion object {
            private val INSTANCE = LeafCodecForCastingOnly()
        }
    }

    @org.junit.Test
    @Throws(SerializationException::class, IOException::class)
    fun memoizingDeserialize_null() {
        val registry: ObjectCodecRegistry? = Mockito.mock<ObjectCodecRegistry?>(ObjectCodecRegistry::class.java)
        val codedInputStream: CodedInputStream = Mockito.mock<CodedInputStream>(CodedInputStream::class.java)
        val codecs: ObjectCodecs =
            ObjectCodecs(registry, com.google.common.collect.ImmutableClassToInstanceMap.of<B?>())
        Mockito.`when`<Int?>(codedInputStream.readSInt32()).thenReturn(0)
        Truth.assertThat(
            codecs.getMemoizingDeserializationContextForTesting().deserialize(codedInputStream) as Any?
        )
            .isEqualTo(null)
        Mockito.verify<CodedInputStream?>(codedInputStream).readSInt32()
        Mockito.verifyNoInteractions(registry)
    }

    @org.junit.Test
    @Throws(SerializationException::class, IOException::class)
    fun memoizingDeserialize_constant() {
        val constant = Any()
        val registry: ObjectCodecRegistry = Mockito.mock<ObjectCodecRegistry>(ObjectCodecRegistry::class.java)
        Mockito.`when`<T?>(registry.maybeGetConstantByTag(1)).thenReturn(constant)
        val codedInputStream: CodedInputStream = Mockito.mock<CodedInputStream>(CodedInputStream::class.java)
        val codecs: ObjectCodecs =
            ObjectCodecs(registry, com.google.common.collect.ImmutableClassToInstanceMap.of<B?>())
        Mockito.`when`<Int?>(codedInputStream.readSInt32()).thenReturn(1)
        Truth.assertThat(
            codecs.getMemoizingDeserializationContextForTesting().deserialize(codedInputStream) as Any?
        )
            .isEqualTo(constant)
        Mockito.verify<CodedInputStream?>(codedInputStream).readSInt32()
        Mockito.verify<Any?>(registry).maybeGetConstantByTag(1)
    }

    @org.junit.Test
    @Throws(SerializationException::class, IOException::class)
    fun memoizingDeserialize_codec() {
        val returned = Any()
        val codec: ObjectCodec<Any?> = Mockito.mock<ObjectCodec>(ObjectCodec::class.java)
        Mockito.`when`<T?>(codec.getStrategy()).thenReturn(MemoizationStrategy.MEMOIZE_AFTER)
        Mockito.`when`<T?>(codec.getEncodedClass()).thenAnswer(Answer { unused: InvocationOnMock? -> Any::class.java })
        Mockito.`when`<T?>(codec.additionalEncodedClasses()).thenReturn(com.google.common.collect.ImmutableSet.of<E?>())
        Mockito.`when`<T?>(codec.safeCast(ArgumentMatchers.any<T?>()))
            .thenAnswer(Answer { invocation: InvocationOnMock? -> invocation.getArgument<Any?>(0) })
        val codecDescriptor: ObjectCodecRegistry.CodecDescriptor =
            CodecDescriptor( /* tag= */1, codec)
        val registry: ObjectCodecRegistry = Mockito.mock<ObjectCodecRegistry>(ObjectCodecRegistry::class.java)
        Mockito.`when`<T?>(registry.getCodecDescriptorByTag(1)).thenReturn(codecDescriptor)
        val codedInputStream: CodedInputStream = Mockito.mock<CodedInputStream>(CodedInputStream::class.java)
        val deserializationContext: DeserializationContext =
            ObjectCodecs(registry).getMemoizingDeserializationContextForTesting()
        Mockito.`when`<T?>(codec.deserialize(deserializationContext, codedInputStream)).thenReturn(returned)
        Mockito.`when`<Int?>(codedInputStream.readSInt32()).thenReturn(1)
        Truth.assertThat(deserializationContext.deserialize(codedInputStream) as Any?).isEqualTo(returned)
        Mockito.verify<CodedInputStream?>(codedInputStream).readSInt32()
        Mockito.verify<Any?>(registry).maybeGetConstantByTag(1)
        Mockito.verify<Any?>(registry).getCodecDescriptorByTag(1)
        Mockito.verify<Any?>(codec).deserialize(deserializationContext, codedInputStream)
    }

    @get:org.junit.Test
    val dependency: Unit
        get() {
            val context: DeserializationContext =
                ImmutableDeserializationContext(
                    < T > mock < T ? > (ObjectCodecRegistry::class.java), com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(kotlin.String::class.java, "abc"))
            assertThat(context.getDependency(String::class.java)).isEqualTo("abc")
        }

    @get:org.junit.Test
    val dependency_notPresent: Unit
        get() {
            val context: DeserializationContext =
                ImmutableDeserializationContext(
                    < T > mock < T ? > (ObjectCodecRegistry::class.java), com.google.common.collect.ImmutableClassToInstanceMap.of<B?>())
            val e: java.lang.Exception? =
                org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
                    java.lang.NullPointerException::class.java,
                    org.junit.function.ThrowingRunnable { context.getDependency(String::class.java) })
            Truth.assertThat(e).hasMessageThat().contains("Missing dependency of type " + String::class.java)
        }

    @org.junit.Test
    fun dependencyOverrides_alreadyPresent() {
        val codecs: ObjectCodecs =
            ObjectCodecs(
                < T > mock < T ? > (ObjectCodecRegistry::class.java), com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(kotlin.String::class.java, "abc"))
        val overridden: DeserializationContext =
            codecs
                .withDependencyOverridesForTesting(
                    com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                        String::class.java,
                        "xyz"
                    )
                )
                .getDeserializationContextForTesting()
        assertThat(overridden.getDependency(String::class.java)).isEqualTo("xyz")
    }

    @org.junit.Test
    fun dependencyOverrides_new() {
        val codecs: ObjectCodecs =
            ObjectCodecs(
                < T > mock < T ? > (ObjectCodecRegistry::class.java), com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(kotlin.String::class.java, "abc"))
        val overridden: DeserializationContext =
            codecs
                .withDependencyOverridesForTesting(
                    com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                        Int::class.java,
                        1
                    )
                )
                .getDeserializationContextForTesting()
        assertThat(overridden.getDependency(Int::class.java)).isEqualTo(1)
    }

    @org.junit.Test
    fun dependencyOverrides_unchanged() {
        val codecs: ObjectCodecs =
            ObjectCodecs(
                < T > mock < T ? > (ObjectCodecRegistry::class.java), com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(kotlin.String::class.java, "abc"))
        val overridden: DeserializationContext =
            codecs
                .withDependencyOverridesForTesting(
                    com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                        Int::class.java,
                        1
                    )
                )
                .getDeserializationContextForTesting()
        assertThat(overridden.getDependency(String::class.java)).isEqualTo("abc")
    }
}
