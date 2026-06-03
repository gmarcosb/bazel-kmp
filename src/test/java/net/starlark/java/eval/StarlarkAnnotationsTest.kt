// Copyright 2016 The Bazel Authors. All Rights Reserved.
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

import net.starlark.java.annot.StarlarkAnnotations

/** Test Starlark annotations and utilities.  */
@RunWith(JUnit4::class)
class StarlarkAnnotationsTest {
    /** MockClassA  */
    @StarlarkBuiltin(name = "MockClassA", doc = "MockClassA")
    open class MockClassA : StarlarkValue {
        @StarlarkMethod(name = "foo", doc = "MockClassA#foo")
        open fun foo() {
        }

        @StarlarkMethod(name = "bar", doc = "MockClassA#bar")
        open fun bar() {
        }

        open fun baz() {}
    }

    /** MockInterfaceB1  */
    @StarlarkBuiltin(name = "MockInterfaceB1", doc = "MockInterfaceB1")
    interface MockInterfaceB1 : StarlarkValue {
        @StarlarkMethod(name = "foo", doc = "MockInterfaceB1#foo")
        fun foo()

        @StarlarkMethod(name = "bar", doc = "MockInterfaceB1#bar")
        fun bar()

        @StarlarkMethod(name = "baz", doc = "MockInterfaceB1#baz")
        fun baz()
    }

    /** MockInterfaceB2  */
    @StarlarkBuiltin(name = "MockInterfaceB2", doc = "MockInterfaceB2")
    interface MockInterfaceB2 : StarlarkValue {
        @StarlarkMethod(name = "baz", doc = "MockInterfaceB2#baz")
        fun baz()

        @StarlarkMethod(name = "qux", doc = "MockInterfaceB2#qux")
        fun qux()
    }

    /** MockClassC  */
    @StarlarkBuiltin(name = "MockClassC", doc = "MockClassC")
    open class MockClassC : MockClassA(), MockInterfaceB1, MockInterfaceB2 {
        @StarlarkMethod(name = "foo", doc = "MockClassC#foo")
        override fun foo() {
        }

        override fun bar() {}
        override fun baz() {}
        override fun qux() {}
    }

    /** MockClassD  */
    class MockClassD : MockClassC() {
        @StarlarkMethod(name = "foo", doc = "MockClassD#foo")
        override fun foo() {
        }
    }

    /**
     * A mock class that implements two unrelated module interfaces. This is invalid as the Starlark
     * type of such an object is ambiguous.
     */
    class ImplementsTwoUnrelatedInterfaceModules

        : MockInterfaceB1, MockInterfaceB2 {
        override fun foo() {}
        override fun bar() {}
        override fun baz() {}
        override fun qux() {}
    }

    /** ClassAModule test class  */
    @StarlarkBuiltin(name = "ClassAModule", doc = "ClassAModule")
    open class ClassAModule : StarlarkValue

    /** ExtendsClassA test class  */
    open class ExtendsClassA : ClassAModule()

    /** InterfaceBModule test interface  */
    @StarlarkBuiltin(name = "InterfaceBModule", doc = "InterfaceBModule")
    interface InterfaceBModule : StarlarkValue

    /** ExtendsInterfaceB test interface  */
    interface ExtendsInterfaceB : InterfaceBModule

    /**
     * A mock class which has two transitive superclasses ([ClassAModule] and [ ])) which are unrelated modules. This is invalid as the Starlark type of such
     * an object is ambiguous.
     * 
     * 
     * In other words: AmbiguousClass -> ClassAModule AmbiguousClass -> InterfaceBModule ... but
     * ClassAModule and InterfaceBModule have no relation.
     */
    class AmbiguousClass : ExtendsClassA(), ExtendsInterfaceB

    /** SubclassOfBoth test interface  */
    @StarlarkBuiltin(name = "SubclassOfBoth", doc = "SubclassOfBoth")
    open class SubclassOfBoth : ExtendsClassA(), ExtendsInterfaceB

    /**
     * A mock class similar to [AmbiugousClass] in that it has two separate superclass-paths to
     * Starlark modules, but is resolvable.
     * 
     * 
     * Concretely: UnambiguousClass -> SubclassOfBoth UnambiguousClass -> InterfaceBModule
     * SubclassOfBoth -> InterfaceBModule
     * 
     * 
     * ... so UnambiguousClass is of type SubclassOfBoth.
     */
    class UnambiguousClass : SubclassOfBoth(), ExtendsInterfaceB

    /** MockClassZ  */
    class MockClassZ

    // The tests for getStarlarkBuiltin() double as tests for getParentWithStarlarkBuiltin(),
    // since they share an implementation.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkBuiltinBasic() {
        // Normal case.
        val ann: StarlarkBuiltin =
            StarlarkAnnotations.getStarlarkBuiltin(net.starlark.java.eval.StarlarkAnnotationsTest.MockClassA::class.java)
        val cls: java.lang.Class<*>? =
            StarlarkAnnotations.getParentWithStarlarkBuiltin(net.starlark.java.eval.StarlarkAnnotationsTest.MockClassA::class.java)
        assertThat(ann).isNotNull()
        assertThat(ann.doc()).isEqualTo("MockClassA")
        Truth.assertThat(cls).isNotNull()
        Truth.assertThat(cls).isEqualTo(net.starlark.java.eval.StarlarkAnnotationsTest.MockClassA::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkBuiltinSubclass() {
        // Subclass's annotation is used.
        val ann: StarlarkBuiltin = StarlarkAnnotations.getStarlarkBuiltin(MockClassC::class.java)
        val cls: java.lang.Class<*>? = StarlarkAnnotations.getParentWithStarlarkBuiltin(MockClassC::class.java)
        assertThat(ann).isNotNull()
        assertThat(ann.doc()).isEqualTo("MockClassC")
        Truth.assertThat(cls).isNotNull()
        Truth.assertThat(cls).isEqualTo(MockClassC::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkBuiltinSubclassNoSubannotation() {
        // Falls back on superclass's annotation.
        val ann: StarlarkBuiltin = StarlarkAnnotations.getStarlarkBuiltin(MockClassD::class.java)
        val cls: java.lang.Class<*>? = StarlarkAnnotations.getParentWithStarlarkBuiltin(MockClassD::class.java)
        assertThat(ann).isNotNull()
        assertThat(ann.doc()).isEqualTo("MockClassC")
        Truth.assertThat(cls).isNotNull()
        Truth.assertThat(cls).isEqualTo(MockClassC::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkBuiltinNotFound() {
        // Doesn't exist.
        val ann: StarlarkBuiltin? = StarlarkAnnotations.getStarlarkBuiltin(MockClassZ::class.java)
        val cls: java.lang.Class<*>? = StarlarkAnnotations.getParentWithStarlarkBuiltin(MockClassZ::class.java)
        assertThat(ann).isNull()
        Truth.assertThat(cls).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkBuiltinAmbiguous() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                StarlarkAnnotations.getStarlarkBuiltin(
                    ImplementsTwoUnrelatedInterfaceModules::class.java
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkBuiltinTransitivelyAmbiguous() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { StarlarkAnnotations.getStarlarkBuiltin(AmbiguousClass::class.java) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkBuiltinUnambiguousComplex() {
        assertThat(StarlarkAnnotations.getStarlarkBuiltin(SubclassOfBoth::class.java))
            .isEqualTo(SubclassOfBoth::class.java.getAnnotation<A?>(StarlarkBuiltin::class.java))

        assertThat(StarlarkAnnotations.getStarlarkBuiltin(UnambiguousClass::class.java))
            .isEqualTo(SubclassOfBoth::class.java.getAnnotation<A?>(StarlarkBuiltin::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkCallableBasic() {
        // Normal case. Ensure two-arg form is consistent with one-arg form.
        val method: java.lang.reflect.Method? =
            net.starlark.java.eval.StarlarkAnnotationsTest.MockClassA::class.java.getMethod("foo")
        val ann: StarlarkMethod = StarlarkAnnotations.getStarlarkMethod(method)
        assertThat(ann).isNotNull()
        assertThat(ann.doc()).isEqualTo("MockClassA#foo")

        val ann2: StarlarkMethod? = StarlarkAnnotations.getStarlarkMethod(
            net.starlark.java.eval.StarlarkAnnotationsTest.MockClassA::class.java,
            method
        )
        assertThat(ann2).isEqualTo(ann)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkCallableSubclass() {
        // Subclass's annotation is used.
        val method: java.lang.reflect.Method? = MockClassC::class.java.getMethod("foo")
        val ann: StarlarkMethod = StarlarkAnnotations.getStarlarkMethod(method)
        assertThat(ann).isNotNull()
        assertThat(ann.doc()).isEqualTo("MockClassC#foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkCallableSubclassNoSubannotation() {
        // Falls back on superclass's annotation. Superclass takes precedence over interface.
        val method: java.lang.reflect.Method? = MockClassC::class.java.getMethod("bar")
        val ann: StarlarkMethod = StarlarkAnnotations.getStarlarkMethod(method)
        assertThat(ann).isNotNull()
        assertThat(ann.doc()).isEqualTo("MockClassA#bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkCallableTwoargForm() {
        // Ensure that when passing superclass in directly, we bypass subclass's annotation.
        val method: java.lang.reflect.Method? = MockClassC::class.java.getMethod("foo")
        val ann: StarlarkMethod = StarlarkAnnotations.getStarlarkMethod(
            net.starlark.java.eval.StarlarkAnnotationsTest.MockClassA::class.java,
            method
        )
        assertThat(ann).isNotNull()
        assertThat(ann.doc()).isEqualTo("MockClassA#foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkCallableNotFound() {
        // Null result when no annotation present...
        var method: java.lang.reflect.Method? =
            net.starlark.java.eval.StarlarkAnnotationsTest.MockClassA::class.java.getMethod("baz")
        var ann: StarlarkMethod? = StarlarkAnnotations.getStarlarkMethod(method)
        assertThat(ann).isNull()

        // ... including when it's only present in a subclass that was bypassed...
        method = MockClassC::class.java.getMethod("baz")
        ann = StarlarkAnnotations.getStarlarkMethod(
            net.starlark.java.eval.StarlarkAnnotationsTest.MockClassA::class.java,
            method
        )
        assertThat(ann).isNull()

        // ... or when the method itself is only in the subclass that was bypassed.
        method = MockClassC::class.java.getMethod("qux")
        ann = StarlarkAnnotations.getStarlarkMethod(
            net.starlark.java.eval.StarlarkAnnotationsTest.MockClassA::class.java,
            method
        )
        assertThat(ann).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetStarlarkCallableInterface() {
        // Search through parent interfaces. First interface takes priority.
        var method: java.lang.reflect.Method? = MockClassC::class.java.getMethod("baz")
        var ann: StarlarkMethod = StarlarkAnnotations.getStarlarkMethod(method)
        assertThat(ann).isNotNull()
        assertThat(ann.doc()).isEqualTo("MockInterfaceB1#baz")

        // Make sure both are still traversed.
        method = MockClassC::class.java.getMethod("qux")
        ann = StarlarkAnnotations.getStarlarkMethod(method)
        assertThat(ann).isNotNull()
        assertThat(ann.doc()).isEqualTo("MockInterfaceB2#qux")
    }
}
