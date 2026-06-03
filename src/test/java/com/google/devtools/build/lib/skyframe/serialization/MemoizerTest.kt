// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec.stringCodec

/** Tests memo-based encoding and decoding, especially for cyclic data structures.  */
@RunWith(JUnit4::class)
class MemoizerTest {
    // These classes are used to model a potentially cyclic data structure with both mutable and
    // immutable components.
    private interface DummyLinkedList {
        val value: String?

        val next: DummyLinkedList?
    }

    private class MutableDummy(private val value: String?, private var next: DummyLinkedList?) : DummyLinkedList {
        override fun getValue(): String? {
            return value
        }

        override fun getNext(): DummyLinkedList? {
            return next
        }

        fun setNext(next: DummyLinkedList?) {
            this.next = next
        }
    }

    private class ImmutableDummy(private val value: String?, private val next: DummyLinkedList?) : DummyLinkedList {
        override fun getValue(): String? {
            return value
        }

        override fun getNext(): DummyLinkedList? {
            return next
        }
    }

    @org.junit.Test
    @Throws(IOException::class, SerializationException::class)
    fun chainOfMutables() {
        val c: DummyLinkedList = MutableDummy("C", null)
        val b: DummyLinkedList = MutableDummy("B", c)
        val a: DummyLinkedList = MutableDummy("A", b)
        assertABC(RoundTripping.roundTripMemoized(a))
    }

    @org.junit.Test
    @Throws(IOException::class, SerializationException::class)
    fun chainOfMixed() {
        val c: DummyLinkedList = MutableDummy("C", null)
        val b: DummyLinkedList = ImmutableDummy("B", c)
        val a: DummyLinkedList = MutableDummy("A", b)
        assertABC(RoundTripping.roundTripMemoized(a))
    }

    @org.junit.Test
    @Throws(IOException::class, SerializationException::class)
    fun cycleOfMutables() {
        val b = MutableDummy("B", null)
        val a: DummyLinkedList = MutableDummy("A", b)
        b.setNext(a)
        assertABcycle(RoundTripping.roundTripMemoized(a))
    }

    @org.junit.Test
    @Throws(IOException::class, SerializationException::class)
    fun cycleOfMixedWithMutableRoot() {
        val a = MutableDummy("A", null)
        val b: DummyLinkedList = ImmutableDummy("B", a)
        a.setNext(b)
        assertABcycle(RoundTripping.roundTripMemoized(a))
    }

    @org.junit.Test
    @Throws(IOException::class, SerializationException::class)
    fun cycleOfMixedWithImmutableRoot() {
        val b = MutableDummy("B", null)
        val a: DummyLinkedList = ImmutableDummy("A", b)
        b.setNext(a)
        assertABcycle(RoundTripping.roundTripMemoized(a))
    }

    // The following two tests verify that objects memoized using serialize can interoperate with
    // objects memoized using serializeLeaf, bidirectionally.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializedLeaf_canBeBackreferenced() {
        val first// deliberate to create different references
                = String("foo")
        val second// deliberate to create different references
                = String("foo")
        val subject: com.google.common.collect.ImmutableList<Any?> = com.google.common.collect.ImmutableList.of<Any?>(
            com.google.devtools.build.lib.skyframe.serialization.MemoizerTest.Wrapper(first), second
        )
        Truth.assertThat((subject.get(0) as Wrapper).value).isNotSameInstanceAs(subject.get(1))

        val deserialized: com.google.common.collect.ImmutableList<Any?> =
            RoundTripping.roundTripMemoized(subject, wrapperLeafCodec())
        Truth.assertThat(subject).isEqualTo(deserialized)
        // The "foo" instance memoized via serializeLeaf can be backreferenced by a codec that isn't
        // explicitly invoked via serializeLeaf.
        Truth.assertThat((deserialized.get(0) as Wrapper).value).isSameInstanceAs(deserialized.get(1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializeLeaf_canBackreferenceNonSerializeLeaf() {
        val first// deliberate to create different references
                = String("foo")
        val second// deliberate to create different references
                = String("foo")
        val subject: com.google.common.collect.ImmutableList<Any?> = com.google.common.collect.ImmutableList.of<Any?>(
            first,
            com.google.devtools.build.lib.skyframe.serialization.MemoizerTest.Wrapper(second)
        )
        Truth.assertThat(subject.get(0)).isNotSameInstanceAs((subject.get(1) as Wrapper).value)

        val deserialized: com.google.common.collect.ImmutableList<Any?> =
            RoundTripping.roundTripMemoized(subject, wrapperLeafCodec())
        Truth.assertThat(subject).isEqualTo(deserialized)
        // The "foo" instance memoized via serialize can be backreferenced by a codec that uses
        // serializeLeaf.
        Truth.assertThat(deserialized.get(0)).isSameInstanceAs((deserialized.get(1) as Wrapper).value)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializeAsBothLeafAndContainingSharedValue() {
        // Serializes the same Wrapper instance in two ways. Once using WrapperWithSharedStringCodec and
        // once using WrapperLeafCodec. This would cause them to use the same memoization which would
        // lead to an error without special handling.
        val wrappers = TwoWrappers()
        wrappers.one = com.google.devtools.build.lib.skyframe.serialization.MemoizerTest.Wrapper("value")
        wrappers.two = wrappers.one

        SerializationTester(wrappers)
            .makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
            .addCodec(WrapperWithSharedStringCodec())
            .runTests()
    }

    /** An example class that allows [LeafObjectCodec] to be exercised.  */
    private class Wrapper(private val value: String) {
        override fun equals(obj: Any?): Boolean {
            if (obj is Wrapper) {
                return value == obj.value
            }
            return false
        }

        override fun hashCode(): Int {
            return value.hashCode()
        }
    }

    private class WrapperLeafCodec : LeafObjectCodec<Wrapper?>() {
        val encodedClass: java.lang.Class<Wrapper?>
            get() = com.google.devtools.build.lib.skyframe.serialization.MemoizerTest.Wrapper::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(context: LeafSerializationContext, obj: Wrapper, codedOut: CodedOutputStream?) {
            context.serializeLeaf(obj.value, stringCodec(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream?): Wrapper {
            return com.google.devtools.build.lib.skyframe.serialization.MemoizerTest.Wrapper(
                context.deserializeLeaf(
                    codedIn,
                    stringCodec()
                )
            )
        }

        companion object {
            private val INSTANCE = WrapperLeafCodec()
        }
    }

    private class WrapperWithSharedStringCodec : DeferredObjectCodec<Wrapper?>() {
        val encodedClass: java.lang.Class<Wrapper?>
            get() = com.google.devtools.build.lib.skyframe.serialization.MemoizerTest.Wrapper::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(context: SerializationContext, obj: Wrapper, codedOut: CodedOutputStream?) {
            context.putSharedValue(
                obj.value,  /* distinguisher= */null, DeferredStringCodec.Companion.INSTANCE, codedOut
            )
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<Wrapper?> {
            val builder = WrapperBuilder()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                DeferredStringCodec.Companion.INSTANCE,
                builder,
                { builder: WrapperBuilder, value: Any? -> WrapperBuilder.Companion.setValue(builder, value) })
            return builder
        }

        private class WrapperBuilder : DeferredValue<Wrapper?> {
            private var value: String? = null

            public override fun call(): Wrapper {
                return com.google.devtools.build.lib.skyframe.serialization.MemoizerTest.Wrapper(value)
            }

            companion object {
                private fun setValue(builder: WrapperBuilder, value: Any?) {
                    builder.value = value as String
                }
            }
        }
    }

    private class DeferredStringCodec : DeferredObjectCodec<String?>() {
        public override fun autoRegister(): Boolean {
            return false
        }

        val encodedClass: java.lang.Class<String?>
            get() = String::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(context: SerializationContext?, obj: String?, codedOut: CodedOutputStream) {
            codedOut.writeStringNoTag(obj)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream
        ): DeferredValue<String?> {
            val value: String? = codedIn.readString()
            return DeferredValue { value }
        }

        companion object {
            private val INSTANCE = DeferredStringCodec()
        }
    }

    private class TwoWrappers {
        private var one: Wrapper? = null
        private var two: Wrapper? = null

        override fun equals(obj: Any?): Boolean {
            if (obj is TwoWrappers) {
                return one == obj.one && two == obj.two
            }
            return false
        }

        override fun hashCode(): Int {
            return hashObjects(one, two)
        }

        companion object {
            private fun setOne(parent: TwoWrappers, value: Any?) {
                parent.one = value as Wrapper
            }
        }
    }

    @com.google.errorprone.annotations.Keep
    private class TwoWrappersCodec : AsyncObjectCodec<TwoWrappers?>() {
        val encodedClass: java.lang.Class<TwoWrappers?>
            get() = TwoWrappers::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(context: SerializationContext, obj: TwoWrappers, codedOut: CodedOutputStream?) {
            context.serialize(obj.one, codedOut)
            context.serializeLeaf(obj.two, wrapperLeafCodec(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeAsync(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): TwoWrappers {
            val wrappers = TwoWrappers()
            context.registerInitialValue(wrappers)
            context.deserialize(
                codedIn,
                wrappers,
                { parent: TwoWrappers, value: Any? -> TwoWrappers.Companion.setOne(parent, value) })
            wrappers.two = context.deserializeLeaf(codedIn, wrapperLeafCodec())
            return wrappers
        }
    }

    companion object {
        private fun wrapperLeafCodec(): WrapperLeafCodec {
            return WrapperLeafCodec.Companion.INSTANCE
        }

        /** Asserts that `value` has the linked list structure `A -> B -> C`.  */
        private fun assertABC(value: DummyLinkedList) {
            Truth.assertThat(value.value).isEqualTo("A")
            Truth.assertThat(value.next).isNotNull()
            Truth.assertThat(value.next!!.value).isEqualTo("B")
            Truth.assertThat(value.next!!.next).isNotNull()
            Truth.assertThat(value.next!!.next!!.value).isEqualTo("C")
            Truth.assertThat(value.next!!.next!!.next).isNull()
        }

        /** Asserts that `value` has the cyclic linked list structure `A -> B -> A...`.  */
        private fun assertABcycle(value: DummyLinkedList) {
            Truth.assertThat(value.value).isEqualTo("A")
            Truth.assertThat(value.next).isNotNull()
            Truth.assertThat(value.next!!.value).isEqualTo("B")
            Truth.assertThat(value.next!!.next).isNotNull()
            // Check instance identity to ensure we reproduced the object graph without creating duplicates.
            Truth.assertThat(value.next!!.next).isSameInstanceAs(value)
        }
    }
}
