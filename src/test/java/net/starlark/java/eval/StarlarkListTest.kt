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
package net.starlark.java.eval

import StarlarkList.SerializableListSupplier
import com.google.common.testing.GcFinalization
import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import net.starlark.java.eval.Mutability
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkInt
import net.starlark.java.eval.StarlarkSemantics
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.atomic.AtomicBoolean
import java.util.stream.IntStream

/** Tests of StarlarkList's Java API.  */ // TODO(adonovan): duplicate/share these tests for Tuple where applicable.
@RunWith(JUnit4::class)
class StarlarkListTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun listAfterRemoveHasExpectedEqualsAndHashCode() {
        val l: StarlarkList<String?> = StarlarkList.of(Mutability.create(), "1", "2", "3")
        l.removeElement("3")
        assertThat(l.hashCode()).isEqualTo(StarlarkList.immutableOf("1", "2").hashCode())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListAddWithIndex() {
        val mutability: Mutability? = Mutability.create("test")
        val list: StarlarkList<String?> = StarlarkList.newList(mutability)
        list.addElement("a")
        list.addElement("b")
        list.addElement("c")
        list.addElementAt(0, "d")
        assertThat(list.toString()).isEqualTo("[\"d\", \"a\", \"b\", \"c\"]")
        list.addElementAt(2, "e")
        assertThat(list.toString()).isEqualTo("[\"d\", \"a\", \"e\", \"b\", \"c\"]")
        list.addElementAt(4, "f")
        assertThat(list.toString()).isEqualTo("[\"d\", \"a\", \"e\", \"b\", \"f\", \"c\"]")
        list.addElementAt(6, "g")
        assertThat(list.toString()).isEqualTo("[\"d\", \"a\", \"e\", \"b\", \"f\", \"c\", \"g\"]")
        org.junit.Assert.assertThrows<java.lang.ArrayIndexOutOfBoundsException?>(
            java.lang.ArrayIndexOutOfBoundsException::class.java,
            org.junit.function.ThrowingRunnable { list.addElementAt(8, "h") })
    }

    @org.junit.Test
    fun testMutatorsCheckMutability() {
        val mutability: Mutability = Mutability.create("test")
        val list: StarlarkList<Any?> =
            StarlarkList.copyOf(
                mutability,
                com.google.common.collect.ImmutableList.of<E?>(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
            )
        mutability.freeze()
        checkImmutable(list)
    }

    @org.junit.Test
    fun testCannotMutateAfterShallowFreeze() {
        val mutability: Mutability? = Mutability.createAllowingShallowFreeze("test")
        val list: StarlarkList<Any?> =
            StarlarkList.copyOf(
                mutability,
                com.google.common.collect.ImmutableList.of<E?>(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
            )
        list.unsafeShallowFreeze()

        val e: EvalException? = org.junit.Assert.assertThrows<T?>(
            EvalException::class.java,
            org.junit.function.ThrowingRunnable { list.addElement(StarlarkInt.of(4)) })
        assertThat(e).hasMessageThat().isEqualTo("trying to mutate a frozen list value")
    }

    @org.junit.Test
    @Throws(EvalException::class)
    fun testCopyOfTakesCopy() {
        val copyFrom: java.util.ArrayList<String?> = com.google.common.collect.Lists.newArrayList<String?>("hi")
        val mutability: Mutability? = Mutability.create("test")
        val mutableList: StarlarkList<String?> = StarlarkList.copyOf(mutability, copyFrom)
        copyFrom.add("added1")
        mutableList.addElement("added2")

        Truth.assertThat(copyFrom).containsExactly("hi", "added1").inOrder()
        Truth.assertThat(mutableList as MutableList<String?>).containsExactly("hi", "added2").inOrder()
    }

    @org.junit.Test
    fun testWrapTakesOwnershipOfArray() {
        val wrapped = arrayOf<Any?>("hello")
        val mutability: Mutability? = Mutability.create("test")
        val mutableList: StarlarkList<Any?>? = StarlarkList.wrap(mutability, wrapped)

        // Big no-no, but we're proving a point.
        wrapped[0] = "goodbye"
        Truth.assertThat(mutableList as MutableList<*>?).containsExactly("goodbye")
    }

    @org.junit.Test
    @Throws(EvalException::class)
    fun testOfReturnsListWhoseArrayElementTypeIsObject() {
        val mu: Mutability? = Mutability.create("test")
        val list: StarlarkList<Any?> = StarlarkList.of(mu, "a", "b")
        list.addElement(StarlarkInt.of(1)) // no ArrayStoreException
        assertThat(list.toString()).isEqualTo("[\"a\", \"b\", 1]")
    }

    @org.junit.Test
    fun immutableSingleton() {
        val list: StarlarkList<Any?> = StarlarkList.immutableOf("a")
        checkImmutable(list)
        Truth.assertThat(list as MutableList<*>?).containsExactly("a")
    }

    @org.junit.Test
    fun immutableMultiElement() {
        val list: StarlarkList<Any?> = StarlarkList.immutableOf("a", "b", "c")
        checkImmutable(list)
        Truth.assertThat(list as MutableList<*>?).containsExactly("a", "b", "c").inOrder()
    }

    @org.junit.Test
    fun lazyImmutable() {
        val called: AtomicBoolean = AtomicBoolean(false)
        var supplier: SerializableListSupplier<Any?>? =
            SerializableListSupplier? {
            Truth.assertThat(called.getAndSet(true)).isFalse()
            com.google.common.collect.ImmutableList.of<String?>("a", "b", "c")
        }
        val list: StarlarkList<Any?> = StarlarkList.lazyImmutable(supplier)
        Truth.assertThat(called.get()).isFalse()
        Truth.assertThat(list as MutableList<*>?).containsExactly("a", "b", "c").inOrder()
        assertThat(list.get(0)).isEqualTo("a") // Supplier not called twice.

        // Supplier is discarded.
        val ref: java.lang.ref.WeakReference<Any?> = java.lang.ref.WeakReference<Any?>(supplier)
        supplier = null
        GcFinalization.awaitClear(ref)

        checkImmutable(list)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkListToArray() {
        val mu: Mutability? = Mutability.create("test")
        val list: StarlarkList<String?> = StarlarkList.newList(mu)

        for (i in 0..9) {
            for (len in intArrayOf(0, list.size() / 2, list.size(), list.size() * 2)) {
                for (elemType in arrayOf<java.lang.Class<*>>(Any::class.java, String::class.java)) {
                    val input = java.lang.reflect.Array.newInstance(elemType, len) as Array<Any?>
                    try {
                        checkToArray(input, list)
                    } catch (ex: java.lang.AssertionError) {
                        fail("list.toArray(new %s[%d]): %s", elemType.getSimpleName(), len, ex.message)
                    }
                }
            }
            // Note we add elements in loop instead of recreating a list
            // to also check that code works correctly when list capacity exceeds size.
            list.addElement(i.toString())
        }
    }

    @org.junit.Test
    fun testTupleToArray() {
        val tuple: Tuple =
            Tuple.of(
                IntStream.range(0, 10).mapToObj<String?>(java.util.function.IntFunction { i: Int -> i.toString() })
                    .toArray()
            )
        for (len in intArrayOf(0, tuple.size() / 2, tuple.size(), tuple.size() * 2)) {
            for (elemType in arrayOf<java.lang.Class<*>>(Any::class.java, String::class.java)) {
                val input = java.lang.reflect.Array.newInstance(elemType, len) as Array<Any?>
                try {
                    checkToArray(input, tuple)
                } catch (ex: java.lang.AssertionError) {
                    fail("tuple.toArray(new %s[%d]): %s", elemType.getSimpleName(), len, ex.message)
                }
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concat_failsCleanlyOnOverflow() {
        java.lang.System.gc()
        val mu: Mutability? = Mutability.create("test")
        // veryBigArray is large enough that veryBigArray.size * 2 > StarlarkList.MAX_ALLOC
        val veryBigArray = arrayOfNulls<Any>((1 shl 29) + 1) // This will OOM if jvm max heap is too low.
        val veryBigList: StarlarkList<Any?>? = StarlarkList.wrap(mu, veryBigArray)
        val e: EvalException? =
            org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { StarlarkList.concat(veryBigList, veryBigList, mu) })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("excessive capacity requested (536870913 + 536870913 elements)")
    }

    companion object {
        @com.google.errorprone.annotations.FormatMethod
        private fun fail(@com.google.errorprone.annotations.FormatString format: String, vararg args: Any?) {
            throw java.lang.AssertionError(String.format(format, *args))
        }

        private fun checkImmutable(list: StarlarkList<Any?>) {
            var e: EvalException? = org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { list.addElement(StarlarkInt.of(4)) })
            assertThat(e).hasMessageThat().isEqualTo("trying to mutate a frozen list value")
            e = org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { list.addElementAt(0, StarlarkInt.of(4)) })
            assertThat(e).hasMessageThat().isEqualTo("trying to mutate a frozen list value")
            e =
                org.junit.Assert.assertThrows<T?>(
                    EvalException::class.java,
                    org.junit.function.ThrowingRunnable {
                        list.addElements(
                            com.google.common.collect.ImmutableList.of<E?>(
                                StarlarkInt.of(4),
                                StarlarkInt.of(5),
                                StarlarkInt.of(6)
                            )
                        )
                    })
            assertThat(e).hasMessageThat().isEqualTo("trying to mutate a frozen list value")
            e = org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { list.removeElementAt(0) })
            assertThat(e).hasMessageThat().isEqualTo("trying to mutate a frozen list value")
            e = org.junit.Assert.assertThrows<T?>(
                EvalException::class.java,
                org.junit.function.ThrowingRunnable { list.setElementAt(0, StarlarkInt.of(10)) })
            assertThat(e).hasMessageThat().isEqualTo("trying to mutate a frozen list value")
        }

        // Asserts that seq.toArray(input) returns an array of class input.getClass(),
        // regardless of seq's element type, and contains the correct elements,
        // including trailing null padding if size < len.
        private fun checkToArray(input: Array<Any?>, seq: Sequence<*>) {
            java.util.Arrays.fill(input, "x")

            val output: Array<Any?> = seq.toArray(input)
            if (output.javaClass != input.javaClass) {
                fail("array class mismatch: input=%s, output=%s", input.javaClass, output.javaClass)
            }
            if (input.size < seq.size()) {
                // assert input is unchanged
                for (i in input.indices) {
                    if (input[i] != "x") {
                        fail("input[%d] = %s, want \"x\"", i, Starlark.repr(input[i], StarlarkSemantics.DEFAULT))
                    }
                }

                val expected: Array<Any?> = IntStream.range(0, seq.size())
                    .mapToObj<String?>(java.util.function.IntFunction { i: Int -> i.toString() }).toArray()
                if (!output.contentEquals(expected)) {
                    fail("output array = %s, want %s", output.contentToString(), expected.contentToString())
                }
            } else if (output != input) {
                for (j in output.indices) {
                    val want = if (j < seq.size()) j.toString() else null
                    if (output[j] != want) {
                        fail("output[%d] = %s, want %s", j, output[j], want)
                    }
                }
            }
        }
    }
}
