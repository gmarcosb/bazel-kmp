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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.skyframe.serialization.SerializationContextTest
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.runner.RunWith
import java.io.IOException

/** Tests for [SerializationContext].  */
@RunWith(TestParameterInjector::class)
class SerializationContextTest {
    @kotlin.jvm.JvmRecord
    internal data class Example(val dataToSerialize: String?) {
        init {
            java.util.Objects.requireNonNull<String?>(dataToSerialize, "dataToSerialize")
        }

        companion object {
            fun withData(data: String?): Example {
                return com.google.devtools.build.lib.skyframe.serialization.SerializationContextTest.Example(data)
            }
        }
    }

    private inner class ExampleCodec : ObjectCodec<Example?> {
        val encodedClass: java.lang.Class<Example?>
            get() = com.google.devtools.build.lib.skyframe.serialization.SerializationContextTest.Example::class.java

        @Throws(IOException::class)
        public override fun serialize(context: SerializationContext?, obj: Example, codedOut: CodedOutputStream) {
            exampleCodecSerializeCalls++
            codedOut.writeStringNoTag(obj.dataToSerialize)
        }

        @Throws(IOException::class)
        public override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream): Example {
            exampleCodecDeserializeCalls++
            return com.google.devtools.build.lib.skyframe.serialization.SerializationContextTest.Example.Companion.withData(
                codedIn.readString()
            )
        }
    }

    private val registry: ObjectCodecRegistry = ObjectCodecRegistry.newBuilder()
        .addReferenceConstant(CONSTANT)
        .add(ExampleCodec())
        .build()

    private var exampleCodecSerializeCalls = 0

    private var exampleCodecDeserializeCalls = 0

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nullSerialize(@TestParameter memoize: Boolean) {
        val context: SerializationContext = getSerializationContext(memoize)
        val bytes: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytes)

        context.serialize(null, codedOut)
        codedOut.flush()

        val codedIn: CodedInputStream = CodedInputStream.newInstance(bytes.toByteArray())
        Truth.assertThat(codedIn.readSInt32()).isEqualTo(0)
        Truth.assertThat(codedIn.isAtEnd()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun constantSerialize(@TestParameter memoize: Boolean) {
        val context: SerializationContext = getSerializationContext(memoize)
        val bytes: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytes)

        context.serialize(CONSTANT, codedOut)
        codedOut.flush()

        val codedIn: CodedInputStream = CodedInputStream.newInstance(bytes.toByteArray())
        Truth.assertThat(codedIn.readSInt32()).isEqualTo(registry.maybeGetTagForConstant(CONSTANT))
        Truth.assertThat(codedIn.isAtEnd()).isTrue()
    }

    @org.junit.Test
    @Throws(SerializationException::class, IOException::class)
    fun descriptorSerialize() {
        val obj: Example =
            com.google.devtools.build.lib.skyframe.serialization.SerializationContextTest.Example.Companion.withData("data")
        val context: SerializationContext = getSerializationContext( /* memoizing= */false)
        val bytes: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytes)

        context.serialize(obj, codedOut)
        codedOut.flush()

        val codedIn: CodedInputStream = CodedInputStream.newInstance(bytes.toByteArray())
        Truth.assertThat(codedIn.readSInt32()).isEqualTo(registry.getCodecDescriptorForObject(obj).tag())
        Truth.assertThat(codedIn.readString()).isEqualTo(obj.dataToSerialize)
        Truth.assertThat(codedIn.isAtEnd()).isTrue()
    }

    @org.junit.Test
    @Throws(SerializationException::class, IOException::class)
    fun descriptorSerialize_memoizing() {
        val obj: Example =
            com.google.devtools.build.lib.skyframe.serialization.SerializationContextTest.Example.Companion.withData("data")
        val context: SerializationContext = getSerializationContext( /* memoizing= */true)
        val bytes: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytes)

        context.serialize(obj, codedOut)
        context.serialize(obj, codedOut)
        codedOut.flush()

        val codedIn: CodedInputStream = CodedInputStream.newInstance(bytes.toByteArray())
        Truth.assertThat(codedIn.readSInt32()).isEqualTo(registry.getCodecDescriptorForObject(obj).tag())
        Truth.assertThat(codedIn.readString()).isEqualTo(obj.dataToSerialize)
        Truth.assertThat(codedIn.isAtEnd()).isFalse()
        Truth.assertThat(exampleCodecSerializeCalls).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(SerializationException::class)
    fun explicitlyAllowedClassCheck() {
        val context: SerializationContext = getSerializationContext( /* memoizing= */true)
        context.addExplicitlyAllowedClass(String::class.java)
        context.checkClassExplicitlyAllowed(String::class.java, "str")
        org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java,
            org.junit.function.ThrowingRunnable { context.checkClassExplicitlyAllowed(Int::class.java, 0) })
        // Explicitly registered classes do not carry over to a new context.
        org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java,
            org.junit.function.ThrowingRunnable {
                context.getFreshContext().checkClassExplicitlyAllowed(String::class.java, "str")
            })
    }

    @org.junit.Test
    fun explicitlyAllowedClassCheckFailsIfNotMemoizing() {
        val context: SerializationContext = getSerializationContext( /* memoizing= */false)
        org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java, org.junit.function.ThrowingRunnable {
                context.addExplicitlyAllowedClass(
                    String::class.java
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mismatchMemoizingRoundtrip() {
        val registry: ObjectCodecRegistry? =
            ObjectCodecRegistry.newBuilder().add(ArrayListCodec()).build()
        val repeatedObject: java.util.ArrayList<Any?> = java.util.ArrayList<Any?>()
        repeatedObject.add(null)
        repeatedObject.add(null)
        val container: java.util.ArrayList<Any?> = java.util.ArrayList<Any?>()
        container.add(repeatedObject)
        val toSerialize: java.util.ArrayList<Any?> = java.util.ArrayList<Any?>()
        toSerialize.add(repeatedObject)
        toSerialize.add(container)

        val codecs: ObjectCodecs = ObjectCodecs(registry)
        val bytes: ByteString? = codecs.serialize(toSerialize)
        org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java,
            org.junit.function.ThrowingRunnable { codecs.deserializeMemoized(bytes) })
    }

    private class ArrayListCodec : ObjectCodec<java.util.ArrayList<*>?> {
        val encodedClass: java.lang.Class<java.util.ArrayList<*>?>
            get() = java.util.ArrayList::class.java as java.lang.Class<*> as java.lang.Class<java.util.ArrayList<*>?>

        public override fun autoRegister(): Boolean {
            return false
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: java.util.ArrayList<*>, codedOut: CodedOutputStream
        ) {
            codedOut.writeInt32NoTag(obj.size())
            for (item in obj) {
                context.serialize(item, codedOut)
            }
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserialize(
            context: DeserializationContext,
            codedIn: CodedInputStream
        ): java.util.ArrayList<*> {
            val size: Int = codedIn.readInt32()
            val result: java.util.ArrayList<*> = java.util.ArrayList<Any?>()
            for (i in 0..<size) {
                result.add(context.deserialize(codedIn))
            }
            return result
        }
    }

    @get:org.junit.Test
    val dependency: Unit
        get() {
            val context: SerializationContext =
                ObjectCodecs(
                    registry,
                    com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(String::class.java, "abc")
                )
                    .getSerializationContextForTesting()
            assertThat(context.getDependency(String::class.java)).isEqualTo("abc")
        }

    @get:org.junit.Test
    val dependency_notPresent: Unit
        get() {
            val context: SerializationContext = getSerializationContext( /* memoizing= */false)
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
                registry,
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(String::class.java, "abc")
            )
        val overridden: ObjectCodecs =
            codecs.withDependencyOverridesForTesting(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(String::class.java, "xyz")
            )
        assertThat(overridden.getSerializationContextForTesting().getDependency(String::class.java))
            .isEqualTo("xyz")
    }

    @org.junit.Test
    fun dependencyOverrides_new() {
        val codecs: ObjectCodecs =
            ObjectCodecs(
                registry,
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(String::class.java, "abc")
            )
        val overridden: ObjectCodecs =
            codecs.withDependencyOverridesForTesting(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                    Int::class.java,
                    1
                )
            )
        assertThat(overridden.getSerializationContextForTesting().getDependency(Int::class.java))
            .isEqualTo(1)
    }

    @org.junit.Test
    fun dependencyOverrides_unchanged() {
        val codecs: ObjectCodecs =
            ObjectCodecs(
                registry,
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(String::class.java, "abc")
            )
        val overridden: ObjectCodecs =
            codecs.withDependencyOverridesForTesting(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                    Int::class.java,
                    1
                )
            )
        assertThat(overridden.getSerializationContextForTesting().getDependency(String::class.java))
            .isEqualTo("abc")
    }

    private fun getSerializationContext(memoizing: Boolean): SerializationContext {
        val codecs: ObjectCodecs = ObjectCodecs(registry)
        return (if (memoizing)
            codecs.getMemoizingSerializationContextForTesting()
        else
            codecs.getSerializationContextForTesting())
    }

    companion object {
        private val CONSTANT = Any()
    }
}
