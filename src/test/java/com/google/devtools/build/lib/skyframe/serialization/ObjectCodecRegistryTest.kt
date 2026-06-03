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

import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.CodecDescriptor

/** Tests for [ObjectCodecRegistry].  */
@RunWith(JUnit4::class)
class ObjectCodecRegistryTest {
    @org.junit.Test
    @Throws(NoCodecException::class)
    fun testDescriptorLookups() {
        val codec1: SingletonCodec<String?>? = SingletonCodec.of("value1", "mnemonic1")
        val codec2: SingletonCodec<Int?>? = SingletonCodec.of(1, "mnemonic2")

        val underTest: ObjectCodecRegistry =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(false)
                .add(codec1)
                .add(codec2)
                .build()

        val fooDescriptor: CodecDescriptor = underTest.getCodecDescriptorForObject("hello")
        assertThat(fooDescriptor.codec()).isSameInstanceAs(codec1)
        assertThat(underTest.getCodecDescriptorByTag(fooDescriptor.tag()))
            .isSameInstanceAs(fooDescriptor)

        val barDescriptor: CodecDescriptor = underTest.getCodecDescriptorForObject(1)
        assertThat(barDescriptor.codec()).isSameInstanceAs(codec2)
        assertThat(underTest.getCodecDescriptorByTag(barDescriptor.tag()))
            .isSameInstanceAs(barDescriptor)

        assertThat(barDescriptor.tag()).isNotEqualTo(fooDescriptor.tag())

        org.junit.Assert.assertThrows<T?>(
            NoCodecException::class.java,
            org.junit.function.ThrowingRunnable { underTest.getCodecDescriptorForObject(1.toByte()) })
        org.junit.Assert.assertThrows<T?>(
            NoCodecException::class.java,
            org.junit.function.ThrowingRunnable { underTest.getCodecDescriptorByTag(42) })
    }

    @org.junit.Test
    @Throws(NoCodecException::class)
    fun testDefaultCodecFallback() {
        val codec: SingletonCodec<String?>? = SingletonCodec.of("value1", "mnemonic1")

        val underTest: ObjectCodecRegistry =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(codec)
                .addClassName(Byte::class.java.getName())
                .addClassName(Int::class.java.getName())
                .build()

        val fooDescriptor: CodecDescriptor = underTest.getCodecDescriptorForObject("value1")
        assertThat(fooDescriptor.codec()).isSameInstanceAs(codec)

        val barDefaultDescriptor: CodecDescriptor = underTest.getCodecDescriptorForObject(15)
        assertThat(barDefaultDescriptor.codec()).isNotSameInstanceAs(codec)
        assertThat(barDefaultDescriptor.tag()).isNotEqualTo(fooDescriptor.tag())
        assertThat(underTest.getCodecDescriptorByTag(barDefaultDescriptor.tag()))
            .isSameInstanceAs(barDefaultDescriptor)

        assertThat(underTest.getCodecDescriptorForObject(9.toByte()).codec().getClass())
            .isSameInstanceAs(barDefaultDescriptor.codec().getClass())

        // Bogus tags still throw.
        org.junit.Assert.assertThrows<T?>(
            NoCodecException::class.java,
            org.junit.function.ThrowingRunnable { underTest.getCodecDescriptorByTag(42) })
    }

    @org.junit.Test
    @Throws(NoCodecException::class)
    fun testStableTagOrdering() {
        val codec1: SingletonCodec<String?>? = SingletonCodec.of("value1", "mnemonic1")
        val codec2: SingletonCodec<Int?>? = SingletonCodec.of(1, "mnemonic2")

        val underTest1: ObjectCodecRegistry =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(codec1)
                .add(codec2)
                .addClassName(Byte::class.java.getName())
                .build()

        val underTest2: ObjectCodecRegistry =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(codec2)
                .add(codec1)
                .addClassName(Byte::class.java.getName())
                .build()

        assertThat(underTest1.getCodecDescriptorForObject("value1").tag())
            .isEqualTo(underTest2.getCodecDescriptorForObject("value1").tag())
        assertThat(underTest1.getCodecDescriptorForObject(5).tag())
            .isEqualTo(underTest2.getCodecDescriptorForObject(5).tag())
        // Default codec.
        assertThat(underTest1.getCodecDescriptorForObject(10.toByte()).tag())
            .isEqualTo(underTest2.getCodecDescriptorForObject(10.toByte()).tag())
    }

    @org.junit.Test
    fun constantsOrderedByLastOccurrenceInIteration() {
        val constant1 = Any()
        val constant2 = Any()
        val underTest1: ObjectCodecRegistry =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(false)
                .addReferenceConstant(constant1)
                .addReferenceConstant(constant2)
                .addReferenceConstant(constant1)
                .build()
        val underTest2: ObjectCodecRegistry =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(false)
                .addReferenceConstant(constant1)
                .addReferenceConstant(constant2)
                .build()
        assertThat(underTest1.maybeGetTagForConstant(constant1)).isEqualTo(3)
        assertThat(underTest1.maybeGetTagForConstant(constant2)).isEqualTo(2)
        assertThat(underTest2.maybeGetTagForConstant(constant1)).isEqualTo(1)
        assertThat(underTest2.maybeGetTagForConstant(constant2)).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(NoCodecException::class)
    fun excludingPrefix() {
        val underTest: ObjectCodecRegistry = builderWithThisClass().build()
        val descriptor: CodecDescriptor = underTest.getCodecDescriptorForObject(this)
        assertThat(descriptor).isNotNull()
        assertThat(descriptor.codec()).isInstanceOf(DynamicCodec::class.java)
        val underTestWithExcludeList: ObjectCodecRegistry =
            builderWithThisClass()
                .excludeClassNamePrefix(this.getClass().getPackage().getName())
                .build()
        org.junit.Assert.assertThrows<T?>(
            NoCodecException::class.java,
            org.junit.function.ThrowingRunnable { underTestWithExcludeList.getCodecDescriptorForObject(this) })
        val underTestWithWideExcludeList: ObjectCodecRegistry =
            builderWithThisClass().excludeClassNamePrefix("com").build()
        org.junit.Assert.assertThrows<T?>(
            NoCodecException::class.java,
            org.junit.function.ThrowingRunnable { underTestWithWideExcludeList.getCodecDescriptorForObject(this) })
    }

    @org.junit.Test
    @Throws(NoCodecException::class)
    fun testGetBuilder() {
        val codec1: SingletonCodec<String?>? = SingletonCodec.of("value1", "mnemonic1")
        val codec2: SingletonCodec<Int?>? = SingletonCodec.of(1, "mnemonic2")
        val constant = Any()

        val underTest: ObjectCodecRegistry =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(false)
                .add(codec1)
                .add(codec2)
                .addReferenceConstant(constant)
                .build()

        val copy: ObjectCodecRegistry = underTest.getBuilder().build()
        assertThat(copy.getCodecDescriptorForObject(12).tag()).isEqualTo(1)
        assertThat(copy.getCodecDescriptorForObject("value1").tag()).isEqualTo(2)
        assertThat(copy.maybeGetTagForConstant(constant)).isNotNull()
        org.junit.Assert.assertThrows<T?>(
            NoCodecException::class.java,
            org.junit.function.ThrowingRunnable { copy.getCodecDescriptorForObject(5.toByte()) })
    }

    private enum class TestEnum {
        ONE {
            override fun `val`(): Int {
                return 1
            }
        },
        TWO {
            override fun `val`(): Int {
                return 2
            }
        },
        THREE {
            override fun `val`(): Int {
                return 3
            }
        };

        @Suppress("unused")
        abstract fun `val`(): Int
    }

    @org.junit.Test
    @Throws(NoCodecException::class)
    fun testDefaultEnum() {
        Truth.assertThat(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum.ONE.getClass())
            .isNotEqualTo(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum::class.java)
        Truth.assertThat(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum.ONE.getDeclaringClass())
            .isEqualTo(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum::class.java)
        Truth.assertThat(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum.ONE.getClass())
            .isNotEqualTo(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum.TWO.getClass())

        val underTest: ObjectCodecRegistry =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .addClassName(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum::class.java.getName())
                .addClassName(
                    com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum.ONE.getClass()
                        .getName()
                )
                .addClassName(
                    com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum.TWO.getClass()
                        .getName()
                )
                .addClassName(
                    com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum.THREE.getClass()
                        .getName()
                )
                .build()

        val oneDescriptor: CodecDescriptor =
            underTest.getCodecDescriptorForObject(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum.ONE)
        val twoDescriptor: CodecDescriptor? =
            underTest.getCodecDescriptorForObject(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum.TWO)
        assertThat(oneDescriptor).isEqualTo(twoDescriptor)

        assertThat(
            oneDescriptor.codec().getEncodedClass()
        ).isEqualTo(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.TestEnum::class.java)
    }

    @org.junit.Test
    fun checksum_nullIfNotComputed() {
        val registry: ObjectCodecRegistry =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(SingletonCodec.of("value", "mnemonic"))
                .addClassName(Byte::class.java.getName())
                .addReferenceConstant(Any())
                .computeChecksum(false)
                .build()
        assertThat(registry.getChecksum()).isNull()
    }

    @org.junit.Test
    fun checksum_deterministic() {
        val codec: ObjectCodec<String?>? = SingletonCodec.of("value", "mnemonic")
        val constant = Any()
        val checksum1: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(codec)
                .addClassName(Byte::class.java.getName())
                .addReferenceConstant(constant)
                .computeChecksum(true)
                .build()
                .getChecksum()
        val checksum2: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(codec)
                .addClassName(Byte::class.java.getName())
                .addReferenceConstant(constant)
                .computeChecksum(true)
                .build()
                .getChecksum()
        Truth.assertThat(checksum1).isNotNull()
        Truth.assertThat(checksum1).isEqualTo(checksum2)
    }

    @org.junit.Test
    fun checksum_sensitiveToChangeInAllowDefaultCodec() {
        val codec: ObjectCodec<String?>? = SingletonCodec.of("value", "mnemonic")
        val constant = Any()
        val checksum1: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(codec)
                .addClassName(Byte::class.java.getName())
                .addReferenceConstant(constant)
                .computeChecksum(true)
                .build()
                .getChecksum()
        val checksum2: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(false)
                .add(codec)
                .addClassName(Byte::class.java.getName())
                .addReferenceConstant(constant)
                .computeChecksum(true)
                .build()
                .getChecksum()
        Truth.assertThat(checksum1).isNotNull()
        Truth.assertThat(checksum2).isNotNull()
        Truth.assertThat(checksum1).isNotEqualTo(checksum2)
    }

    @org.junit.Test
    fun checksum_sensitiveToChangeInCodecs() {
        val codec: ObjectCodec<String?>? = SingletonCodec.of("value", "mnemonic")
        val constant = Any()
        val checksum1: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(codec)
                .addClassName(Byte::class.java.getName())
                .addReferenceConstant(constant)
                .computeChecksum(true)
                .build()
                .getChecksum()
        val checksum2: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(false)
                .add(codec)
                .add(SingletonCodec.of("extra", "extra_mnemonic"))
                .addClassName(Byte::class.java.getName())
                .addReferenceConstant(constant)
                .computeChecksum(true)
                .build()
                .getChecksum()
        Truth.assertThat(checksum1).isNotNull()
        Truth.assertThat(checksum2).isNotNull()
        Truth.assertThat(checksum1).isNotEqualTo(checksum2)
    }

    @org.junit.Test
    fun checksum_sensitiveToChangeInClassNames() {
        val codec: ObjectCodec<String?>? = SingletonCodec.of("value", "mnemonic")
        val constant = Any()
        val checksum1: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(codec)
                .addClassName(Byte::class.java.getName())
                .addReferenceConstant(constant)
                .computeChecksum(true)
                .build()
                .getChecksum()
        val checksum2: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(false)
                .add(codec)
                .addClassName(Int::class.java.getName())
                .addReferenceConstant(constant)
                .computeChecksum(true)
                .build()
                .getChecksum()
        Truth.assertThat(checksum1).isNotNull()
        Truth.assertThat(checksum2).isNotNull()
        Truth.assertThat(checksum1).isNotEqualTo(checksum2)
    }

    @org.junit.Test
    fun checksum_sensitiveToChangeInReferenceConstants() {
        val codec: ObjectCodec<String?>? = SingletonCodec.of("value", "mnemonic")
        val constant = Any()
        val checksum1: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(true)
                .add(codec)
                .addClassName(Byte::class.java.getName())
                .addReferenceConstant(constant)
                .computeChecksum(true)
                .build()
                .getChecksum()
        val checksum2: ByteArray? =
            ObjectCodecRegistry.newBuilder()
                .setAllowDefaultCodec(false)
                .add(codec)
                .addClassName(Byte::class.java.getName())
                .addClassName(Int::class.java.getName())
                .addReferenceConstant(constant)
                .addReferenceConstant("another constant")
                .computeChecksum(true)
                .build()
                .getChecksum()
        Truth.assertThat(checksum1).isNotNull()
        Truth.assertThat(checksum2).isNotNull()
        Truth.assertThat(checksum1).isNotEqualTo(checksum2)
    }

    internal interface TestIntf

    @AutoCodec
    internal class ClassA : TestIntf

    internal class ClassB : TestIntf {
        internal class Codec : DeferredObjectCodec<TestIntf?>() {
            public override fun autoRegister(): Boolean {
                // Will be registered by the test explicitly.
                return false
            }

            val encodedClass: java.lang.Class<out TestIntf?>
                get() = ClassB::class.java

            public override fun additionalEncodedClasses(): com.google.common.collect.ImmutableSet<java.lang.Class<out TestIntf?>?> {
                return com.google.common.collect.ImmutableSet.of<java.lang.Class<out TestIntf?>?>(
                    ClassA::class.java,
                    ClassC::class.java
                )
            }

            @Throws(SerializationException::class, IOException::class)
            public override fun serialize(
                context: SerializationContext?,
                obj: TestIntf?,
                codedOut: CodedOutputStream?
            ) {
                // unused
            }

            @Throws(SerializationException::class, IOException::class)
            public override fun deserializeDeferred(
                context: AsyncDeserializationContext?, codedIn: CodedInputStream?
            ): DeferredValue<out TestIntf?>? {
                return DeferredValue { ClassB() }
            }
        }
    }

    @AutoCodec
    internal class ClassC : TestIntf

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDescriptorLookups_respectsInsertionOrder() {
        val a = ClassA()
        val b = ClassB()
        val c = ClassC()
        val tester: SerializationTester = SerializationTester(a, b, c)

        // The AutoCodecs for ClassA and ClassC will be added first.
        tester.addCodec(com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistryTest.ClassB.Codec())

        tester.setVerificationFunction(
            { `in`, out ->  // Expect that all three objects use ClassB.Codec() as it's registered as one of the
                // last.
                //
                // If there is implicit alphabetical ordering when processing codec descriptors,
                // then:
                // - a will be deserialized as an instance of A.class, or
                // - c will be deserialized as an instance of C.class.
                assertWithMessage("incorrect codec look up for %s", `in`)
                    .that(out)
                    .isInstanceOf(ClassB::class.java)
            })
        tester.runTests()
    }

    companion object {
        private fun builderWithThisClass(): ObjectCodecRegistry.Builder {
            return ObjectCodecRegistry.newBuilder().addClassName(ObjectCodecRegistryTest::class.java.getName())
        }
    }
}
