// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.collect.nestedset.Depset.ElementType

/** Tests for Depset.  */
@RunWith(JUnit4::class)
class DepsetTest {
    private val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstructor() {
        ev.exec("s = depset(order='default')")
        Truth.assertThat(ev.lookup("s")).isInstanceOf(Depset::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTuples() {
        ev.exec(
            "s_one = depset([('1', '2'), ('3', '4')])",
            "s_two = depset(direct = [('1', '2'), ('3', '4'), ('5', '6')])",
            "s_three = depset(transitive = [s_one, s_two])",
            "s_four = depset(direct = [('1', '3')], transitive = [s_one, s_two])",
            "s_five = depset(direct = [('1', '3', '5')], transitive = [s_one, s_two])",
            "s_six = depset(transitive = [s_one, s_five])",
            "s_seven = depset(direct = [('1', '3')], transitive = [s_one, s_five])",
            "s_eight = depset(direct = [(1, 3)], transitive = [s_one, s_two])"
        ) // note, tuple of int
        assertThat(get("s_one").getElementType()).isEqualTo(TUPLE)
        assertThat(get("s_two").getElementType()).isEqualTo(TUPLE)
        assertThat(get("s_three").getElementType()).isEqualTo(TUPLE)
        assertThat(get("s_eight").getElementType()).isEqualTo(TUPLE)

        assertThat(get("s_four").getSet(Tuple::class.java).toList())
            .containsExactly(
                Tuple.of("1", "3"), Tuple.of("1", "2"), Tuple.of("3", "4"), Tuple.of("5", "6")
            )
        assertThat(get("s_five").getSet(Tuple::class.java).toList())
            .containsExactly(
                Tuple.of("1", "3", "5"), Tuple.of("1", "2"), Tuple.of("3", "4"), Tuple.of("5", "6")
            )
        assertThat(get("s_eight").getSet(Tuple::class.java).toList())
            .containsExactly(
                Tuple.of(StarlarkInt.of(1), StarlarkInt.of(3)),
                Tuple.of("1", "2"),
                Tuple.of("3", "4"),
                Tuple.of("5", "6")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetSet() {
        ev.exec("s = depset(['a', 'b'])")
        assertThat(get("s").getSet(String::class.java).toList()).containsExactly("a", "b").inOrder()
        assertThat(get("s").getSet(Any::class.java).toList()).containsExactly("a", "b").inOrder()
        org.junit.Assert.assertThrows<T?>(
            Depset.TypeException::class.java,
            org.junit.function.ThrowingRunnable { get("s").getSet(StarlarkInt::class.java) })

        // getSet argument must be a legal Starlark value class, or Object,
        // but not some superclass that doesn't implement StarlarkValue.
        val ints: Depset =
            Depset.of(
                StarlarkInt::class.java,
                NestedSetBuilder.create(
                    Order.STABLE_ORDER, StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3)
                )
            )
        assertThat(ints.getSet(StarlarkInt::class.java).toString()).isEqualTo("[1, 2, 3]")
        val ex: java.lang.IllegalArgumentException =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    ints.getSet(
                        Number::class.java
                    )
                })
        Truth.assertThat(ex.message).contains("Number is not a subclass of StarlarkValue")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetSetDirect() {
        ev.exec("s = depset(direct = ['a', 'b'])")
        assertThat(get("s").getSet(String::class.java).toList()).containsExactly("a", "b").inOrder()
        assertThat(get("s").getSet(Any::class.java).toList()).containsExactly("a", "b").inOrder()
        org.junit.Assert.assertThrows<T?>(
            Depset.TypeException::class.java,
            org.junit.function.ThrowingRunnable { get("s").getSet(StarlarkInt::class.java) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToList() {
        ev.exec("s = depset(['a', 'b'])")
        assertThat(get("s").toList(String::class.java)).containsExactly("a", "b").inOrder()
        assertThat(get("s").toList(Any::class.java)).containsExactly("a", "b").inOrder()
        assertThat(get("s").toList()).containsExactly("a", "b").inOrder()
        org.junit.Assert.assertThrows<T?>(
            Depset.TypeException::class.java,
            org.junit.function.ThrowingRunnable { get("s").toList(StarlarkInt::class.java) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToListDirect() {
        ev.exec("s = depset(direct = ['a', 'b'])")
        assertThat(get("s").toList(String::class.java)).containsExactly("a", "b").inOrder()
        assertThat(get("s").toList(Any::class.java)).containsExactly("a", "b").inOrder()
        assertThat(get("s").toList()).containsExactly("a", "b").inOrder()
        org.junit.Assert.assertThrows<T?>(
            Depset.TypeException::class.java,
            org.junit.function.ThrowingRunnable { get("s").toList(StarlarkInt::class.java) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOrder() {
        ev.exec("s = depset(['a', 'b'], order='postorder')")
        assertThat(get("s").getSet(String::class.java).getOrder()).isEqualTo(Order.COMPILE_ORDER)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOrderDirect() {
        ev.exec("s = depset(direct = ['a', 'b'], order='postorder')")
        assertThat(get("s").getSet(String::class.java).getOrder()).isEqualTo(Order.COMPILE_ORDER)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadOrder() {
        ev.Scenario()
            .testIfExactError("Invalid order: non_existing", "depset(['a'], order='non_existing')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadOrderDirect() {
        ev.Scenario()
            .testIfExactError(
                "Invalid order: non_existing", "depset(direct = ['a'], order='non_existing')"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyGenericType() {
        ev.exec("s = depset()")
        assertThat(get("s").getElementType()).isEqualTo(ElementType.EMPTY)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHomogeneousGenericType() {
        ev.exec("s = depset(direct = ['a', 'b', 'c'])")
        assertThat(get("s").getElementType()).isEqualTo(ElementType.STRING)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHomogeneousGenericTypeDirect() {
        ev.exec("s = depset(direct = ['a', 'b', 'c'], transitive = [])")
        assertThat(get("s").getElementType()).isEqualTo(ElementType.STRING)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHomogeneousGenericTypeTransitive() {
        ev.exec("s = depset(direct = ['a', 'b', 'c'], transitive = [depset(['x'])])")
        assertThat(get("s").getElementType()).isEqualTo(ElementType.STRING)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveIncompatibleOrder() {
        ev.checkEvalError(
            "Order 'postorder' is incompatible with order 'topological'",
            "depset(direct = ['a', 'b'], order='postorder',",
            "       transitive = [depset(['c', 'd'], order='topological')])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadGenericType() {
        ev.Scenario()
            .testIfExactError(
                "cannot add an item of type 'int' to a depset of 'string'", "depset(['a', 5])"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadGenericTypeDirect() {
        ev.Scenario()
            .testIfExactError(
                "cannot add an item of type 'int' to a depset of 'string'",
                "depset(direct = ['a', 5])"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadGenericTypeTransitive() {
        ev.Scenario()
            .testIfExactError(
                "cannot add an item of type 'int' to a depset of 'string'",
                "depset(['a', 'b'], transitive=[depset([1])])"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTooManyPositionals() {
        ev.Scenario()
            .testIfErrorContains(
                "depset() accepts no more than 2 positional arguments but got 3",
                "depset([], 'default', [])"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveOrderDirect() {
        assertContainsInOrder("depset(direct=[], transitive=[depset(['a', 'b', 'c'])])", "a", "b", "c")
        assertContainsInOrder("depset(direct=['a'], transitive = [depset(['b', 'c'])])", "b", "c", "a")
        assertContainsInOrder("depset(direct=['a', 'b'], transitive = [depset(['c'])])", "c", "a", "b")
        assertContainsInOrder(
            "depset(direct=['a', 'b', 'c'], transitive = [depset([])])",
            "a", "b", "c"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncompatibleUnion() {
        ev.Scenario()
            .testIfErrorContains("unsupported binary operation: depset + list", "depset([]) + ['a']")

        ev.Scenario()
            .testIfErrorContains("unsupported binary operation: depset | list", "depset([]) | ['a']")
    }

    @Throws(java.lang.Exception::class)
    private fun assertContainsInOrder(statement: String?, vararg expectedElements: Any?) {
        assertThat((ev.eval(statement) as Depset).toList()).containsExactly(expectedElements).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToString() {
        ev.exec("s = depset([3, 4, 5], transitive = [depset([2, 4, 6])])", "x = str(s)")
        Truth.assertThat(ev.lookup("x")).isEqualTo("depset([2, 4, 6, 3, 5])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToStringWithOrder() {
        ev.exec(
            "s = depset([3, 4, 5], transitive = [depset([2, 4, 6])], ",
            "           order = 'topological')",
            "x = str(s)"
        )
        Truth.assertThat(ev.lookup("x")).isEqualTo("depset([3, 5, 6, 4, 2], order = \"topological\")")
    }

    @Throws(java.lang.Exception::class)
    private fun get(varname: String?): Depset? {
        return ev.lookup(varname) as Depset?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToListForStarlark() {
        ev.exec(
            "s = depset([3, 4, 5], transitive = [depset([2, 4, 6])])",
            "x = s.to_list()",
            "y = [2, 4, 6, 3, 5]"
        )
        Truth.assertThat(ev.lookup("x")).isEqualTo(ev.lookup("y"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepsetIsNotIterable() {
        ev.Scenario()
            .testIfErrorContains("want 'iterable'", "list(depset(['a', 'b']))")
            .testIfErrorContains("not iterable", "max(depset([1, 2, 3]))")
            .testIfErrorContains(
                "unsupported binary operation: int in depset", "1 in depset([1, 2, 3])"
            )
            .testIfErrorContains("want 'iterable'", "sorted(depset(['a', 'b']))")
            .testIfErrorContains("want 'iterable'", "tuple(depset(['a', 'b']))")
            .testIfErrorContains("not iterable", "[x for x in depset()]")
            .testIfErrorContains("want 'iterable or string'", "len(depset(['a']))")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOrderCompatibility() {
        // Two sets are compatible if
        //  (a) both have the same order or
        //  (b) at least one order is "default"

        for (first in Order.values()) {
            val s1: Depset? = Depset.of(String::class.java, NestedSetBuilder.create(first, "1", "11"))

            for (second in Order.values()) {
                val s2: Depset? = Depset.of(String::class.java, NestedSetBuilder.create(second, "2", "22"))

                var compatible = true

                try {
                    // merge
                    Depset.fromDirectAndTransitive(
                        first,  /*direct=*/
                        com.google.common.collect.ImmutableList.of<E?>(),  /*transitive=*/
                        com.google.common.collect.ImmutableList.of<E?>(s1, s2),  /*strict=*/
                        true
                    )
                } catch (ex: java.lang.Exception) {
                    compatible = false
                }

                Truth.assertThat(compatible).isEqualTo(areOrdersCompatible(first, second))
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMutableDepsetElementsDesiredBehavior() {
        // See b/144992997 and github.com/bazelbuild/bazel/issues/10313.
        ev.setSemantics("--incompatible_always_check_depset_elements=true")

        // Test legacy depset(...) and new depset(direct=...) constructors.

        // mutable list should be an error
        ev.checkEvalError("depset elements must not be mutable values", "depset([[1,2,3]])")
        ev.checkEvalError("depset elements must not be mutable values", "depset(direct=[[1,2,3]])")

        // struct containing mutable list should be an error
        ev.checkEvalError("depset elements must not be mutable values", "depset([struct(a=[])])")
        ev.checkEvalError(
            "depset elements must not be mutable values", "depset(direct=[struct(a=[])])"
        )

        // tuple of frozen list currently gives no error (this may change)
        ev.update("x", StarlarkList.empty<Any?>())
        ev.eval("depset([(x,)])")
        ev.eval("depset(direct=[(x,)])")

        // any list (even frozen) is an error, even with legacy constructor
        ev.checkEvalError("depsets cannot contain items of type 'list'", "depset([x,])")
        ev.checkEvalError("depsets cannot contain items of type 'list'", "depset(direct=[x,])")

        // toplevel dict is an error, even with legacy constructor
        ev.checkEvalError("depset elements must not be mutable values", "depset([{}])")
        ev.checkEvalError("depset elements must not be mutable values", "depset(direct=[{}])")

        // struct containing dict should be an error
        ev.checkEvalError("depset elements must not be mutable values", "depset([struct(a={})])")
        ev.checkEvalError(
            "depset elements must not be mutable values", "depset(direct=[struct(a={})])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConstructorDepthLimit() {
        ev.Scenario()
            .setUp(
                "def create_depset(depth):",
                "  x = depset([0])",
                "  for i in range(1, depth):",
                "    x = depset([i], transitive = [x])"
            )
            .testEval("create_depset(3000)", "None") // succeeds
            .testIfErrorContains("depset depth 3501 exceeds limit (3500)", "create_depset(4000)")

        ev.Scenario("--nested_set_depth_limit=100")
            .setUp(
                "def create_depset(depth):",
                "  x = depset([0])",
                "  for i in range(1, depth):",
                "    x = depset([i], transitive = [x])"
            )
            .testEval("create_depset(99)", "None") // succeeds
            .testIfErrorContains("depset depth 101 exceeds limit (100)", "create_depset(1000)")
    }

    @org.junit.Test
    fun testElementTypeOf() {
        // legal values
        assertThat(ElementType.of(String::class.java).toString()).isEqualTo("string")
        assertThat(ElementType.of(StarlarkInt::class.java).toString()).isEqualTo("int")
        assertThat(ElementType.of(Boolean::class.java).toString()).isEqualTo("bool")

        // concrete non-values
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ElementType.of(
                    Float::class.java
                )
            })

        // concrete classes that implement StarlarkValue
        assertThat(ElementType.of(StarlarkList::class.java).toString()).isEqualTo("list")
        assertThat(ElementType.of(Tuple::class.java).toString()).isEqualTo("tuple")
        assertThat(ElementType.of(Dict::class.java).toString()).isEqualTo("dict")
        class V : StarlarkValue // no StarlarkModule annotation

        assertThat(ElementType.of(V::class.java).toString()).isEqualTo("V")

        // abstract classes that implement StarlarkValue
        assertThat(ElementType.of(net.starlark.java.eval.Sequence::class.java).toString()).isEqualTo("sequence")
        assertThat(ElementType.of(StarlarkCallable::class.java).toString()).isEqualTo("callable")
        assertThat(ElementType.of(StarlarkIterable::class.java).toString()).isEqualTo("iterable")

        // superclasses of legal values that aren't values themselves
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ElementType.of(
                    Number::class.java
                )
            })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { ElementType.of(CharSequence::class.java) })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ElementType.of(
                    Any::class.java
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetComparison() {
        ev.Scenario()
            .testIfExactError(
                "unsupported comparison: depset <=> depset", "depset([1, 2]) < depset([3, 4])"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepsetDirectInvalidType() {
        ev.Scenario()
            .testIfErrorContains(
                "in call to depset(), parameter 'direct' got value of type 'string', want 'sequence or"
                        + " NoneType'",
                "depset(direct='hello')"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyDepsetInternedPerOrder() {
        ev.exec(
            "stable1 = depset()",
            "stable2 = depset()",
            "preorder1 = depset(order = 'preorder')",
            "preorder2 = depset(order = 'preorder')"
        )
        Truth.assertThat(ev.lookup("stable1")).isSameInstanceAs(ev.lookup("stable2"))
        Truth.assertThat(ev.lookup("preorder1")).isSameInstanceAs(ev.lookup("preorder2"))
        Truth.assertThat(ev.lookup("stable1")).isNotSameInstanceAs(ev.lookup("preorder1"))
        Truth.assertThat(ev.lookup("stable2")).isNotSameInstanceAs(ev.lookup("preorder2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleNonEmptyTransitiveAndNoDirectsUnwrapped() {
        ev.exec(
            "inner = depset([1, 2, 3])", "outer = depset(transitive = [depset(), inner, depset()])"
        )
        Truth.assertThat(ev.lookup("outer")).isSameInstanceAs(ev.lookup("inner"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleNonEmptyTransitiveAndMatchingDirectUnwrapped() {
        ev.exec("inner = depset([1])", "outer = depset([1], transitive = [depset(), inner, depset()])")
        Truth.assertThat(ev.lookup("outer")).isSameInstanceAs(ev.lookup("inner"))
    }

    companion object {
        private val TUPLE: ElementType? = ElementType.of(Tuple::class.java)

        private fun areOrdersCompatible(first: Order?, second: Order?): Boolean {
            return first === Order.STABLE_ORDER || second === Order.STABLE_ORDER || first === second
        }
    }
}
