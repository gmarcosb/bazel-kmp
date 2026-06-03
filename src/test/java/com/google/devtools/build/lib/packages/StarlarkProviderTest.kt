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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER

/** Tests for [StarlarkProvider].  */
@RunWith(JUnit4::class)
class StarlarkProviderTest {
    private val generator: SymbolGenerator<*> = SymbolGenerator.create<String?>("test")

    @org.junit.Test
    fun unexportedProvider_accessors() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(generator.generate())
        assertThat(provider.isExported()).isFalse()
        assertThat(provider.getName()).isEqualTo("<no name>")
        assertThat(provider.getPrintableName()).isEqualTo("<no name>")
        assertThat(provider.createRawConstructor().getName()).isEqualTo("<raw constructor>")
        assertThat(provider.getErrorMessageForUnknownField("foo"))
            .isEqualTo("'struct' value has no field or method 'foo'")
        assertThat(provider.isImmutable()).isFalse()
        Truth.assertThat(Starlark.repr(provider, StarlarkSemantics.DEFAULT)).isEqualTo("<provider>")
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            provider::getKey
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exportedProvider_accessors() {
        val key: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:bar.bzl")), "prov")
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(key)
        assertThat(provider.isExported()).isTrue()
        assertThat(provider.getName()).isEqualTo("prov")
        assertThat(provider.getPrintableName()).isEqualTo("prov")
        assertThat(provider.createRawConstructor().getName()).isEqualTo("<raw constructor for prov>")
        assertThat(provider.getErrorMessageForUnknownField("foo"))
            .isEqualTo("'prov' value has no field or method 'foo'")
        assertThat(provider.isImmutable()).isTrue()
        Truth.assertThat(Starlark.repr(provider, StarlarkSemantics.DEFAULT)).isEqualTo("<provider>")
        assertThat(provider.getKey()).isEqualTo(key)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicInstantiation() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(generator.generate())
        val infoFromNormalConstructor: StarlarkInfo = instantiateWithA1B2C3(provider)
        assertHasExactlyValuesA1B2C3(infoFromNormalConstructor)
        assertThat(infoFromNormalConstructor.getProvider()).isEqualTo(provider)

        val infoFromRawConstructor: StarlarkInfo = instantiateWithA1B2C3(provider.createRawConstructor())
        assertHasExactlyValuesA1B2C3(infoFromRawConstructor)
        assertThat(infoFromRawConstructor.getProvider()).isEqualTo(provider)

        assertThat(infoFromNormalConstructor).isEqualTo(infoFromRawConstructor)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun instantiationWithInit() {
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setInit(initBC)
                .buildWithIdentityToken(generator.generate())
        val infoFromNormalConstructor: StarlarkInfo = instantiateWithA1(provider)
        assertHasExactlyValuesA1B2C3(infoFromNormalConstructor)
        assertThat(infoFromNormalConstructor.getProvider()).isEqualTo(provider)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun instantiationWithInitSignatureMismatch_fails() {
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setInit(initBC)
                .buildWithIdentityToken(generator.generate())
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { instantiateWithA1B2C3(provider) })
        Truth.assertThat(e).hasMessageThat().contains("expected a single `a` argument")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun instantiationWithInitReturnTypeMismatch_fails() {
        val initWithInvalidReturnType: StarlarkCallable =
            object : StarlarkCallable {
                override fun call(thread: StarlarkThread?, args: Tuple?, kwargs: Dict<String?, Any?>?): Any {
                    return "invalid"
                }

                val name: String
                    get() = "initWithInvalidReturnType"

                val location: net.starlark.java.syntax.Location
                    get() = net.starlark.java.syntax.Location.BUILTIN
            }

        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setInit(initWithInvalidReturnType)
                .buildWithIdentityToken(generator.generate())
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { instantiateWithA1B2C3(provider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("got string for 'return value of provider init()', want dict")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun instantiationWithFailingInit_fails() {
        val failingInit: StarlarkCallable =
            object : StarlarkCallable {
                @Throws(net.starlark.java.eval.EvalException::class)
                override fun call(thread: StarlarkThread?, args: Tuple?, kwargs: Dict<String?, Any?>?): Any? {
                    throw Starlark.errorf("failingInit fails")
                }

                val name: String
                    get() = "failingInit"

                val location: net.starlark.java.syntax.Location
                    get() = net.starlark.java.syntax.Location.BUILTIN
            }

        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setInit(failingInit)
                .buildWithIdentityToken(generator.generate())
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { instantiateWithA1B2C3(provider) })
        Truth.assertThat(e).hasMessageThat().contains("failingInit fails")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun rawConstructorBypassesInit() {
        val init: StarlarkCallable? = Mockito.mock<StarlarkCallable?>(StarlarkCallable::class.java, "init")
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setInit(init)
                .buildWithIdentityToken(generator.generate())
        val infoFromRawConstructor: StarlarkInfo = instantiateWithA1B2C3(provider.createRawConstructor())
        assertHasExactlyValuesA1B2C3(infoFromRawConstructor)
        assertThat(infoFromRawConstructor.getProvider()).isEqualTo(provider)
        Mockito.verifyNoInteractions(init)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicInstantiationWithSchemaWithSomeFieldsUnset() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a", "b", "c"))
                .buildWithIdentityToken(generator.generate())
        val infoFromNormalConstructor: StarlarkInfo = instantiateWithA1(provider)
        assertHasExactlyValuesA1(infoFromNormalConstructor)
        val infoFromRawConstructor: StarlarkInfo = instantiateWithA1(provider.createRawConstructor())
        assertHasExactlyValuesA1(infoFromRawConstructor)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicInstantiationWithSchemaWithAllFieldsSet() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a", "b", "c"))
                .buildWithIdentityToken(generator.generate())
        val infoFromNormalConstructor: StarlarkInfo = instantiateWithA1B2C3(provider)
        assertHasExactlyValuesA1B2C3(infoFromNormalConstructor)
        val infoFromRawConstructor: StarlarkInfo = instantiateWithA1B2C3(provider.createRawConstructor())
        assertHasExactlyValuesA1B2C3(infoFromRawConstructor)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicInstantiationWithDocumentedSchema() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "a",
                        "Parameter a",
                        "b",
                        "Parameter b",
                        "c",
                        "Parameter c"
                    )
                )
                .buildWithIdentityToken(generator.generate())
        val infoFromNormalConstructor: StarlarkInfo = instantiateWithA1(provider)
        assertHasExactlyValuesA1(infoFromNormalConstructor)
        val infoFromRawConstructor: StarlarkInfo = instantiateWithA1B2C3(provider.createRawConstructor())
        assertHasExactlyValuesA1B2C3(infoFromRawConstructor)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hasInstance() {
        val provider1: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(generator.generate())
        val info1: StarlarkInfo = instantiateWithA1B2C3(provider1)
        val provider2: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(generator.generate())
        val info2: StarlarkInfo = instantiateWithA1B2C3(provider2)
        assertThat(provider1.hasInstance(info1)).isTrue()
        assertThat(provider1.hasInstance(info2)).isFalse()
        assertThat(provider2.hasInstance(info1)).isFalse()
        assertThat(provider2.hasInstance(info2)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemaDisallowsUnexpectedFields() {
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a", "b"))
                .buildWithIdentityToken(generator.generate())
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { instantiateWithA1B2C3(provider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("got unexpected field 'c' in call to instantiate provider")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun documentedSchemaDisallowsUnexpectedFields() {
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableMap.of<K?, V?>("a", "Parameter a", "b", "Parameter b"))
                .buildWithIdentityToken(generator.generate())
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { instantiateWithA1B2C3(provider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("got unexpected field 'c' in call to instantiate provider")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemaEnforcedOnRawConstructor() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a", "b"))
                .buildWithIdentityToken(generator.generate())
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { instantiateWithA1B2C3(provider.createRawConstructor()) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("got unexpected field 'c' in call to instantiate provider")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemaEnforcedOnInit() {
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a", "b"))
                .setInit(initBC)
                .buildWithIdentityToken(generator.generate())
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { instantiateWithA1(provider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("got unexpected field 'c' in call to instantiate provider")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun documentedProvider_getDocumentation() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setDocumentation("My doc string")
                .buildWithIdentityToken(generator.generate())
        assertThat(provider.getDocumentation()).hasValue("My doc string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun undocumentedProvider_getDocumentation() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(generator.generate())
        assertThat(provider.getDocumentation()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemalessProvider_getFields() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(generator.generate())
        assertThat(provider.getFields()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemalessProvider_getSchema() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(generator.generate())
        assertThat(provider.getSchema()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithUndocumentedSchema_getFields() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN) // Note fields in setSchema() call below are not alphabetized to simulate
                // non-alphabetized field order in a provider declaration in Starlark code.
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("c", "a", "b"))
                .buildWithIdentityToken(generator.generate())
        assertThat(provider.getFields()).containsExactly("a", "b", "c").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithUndocumentedSchema_getSchema() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN) // Note fields in setSchema() call below are not alphabetized to simulate
                // non-alphabetized field order in a provider declaration in Starlark code.
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("c", "a", "b"))
                .buildWithIdentityToken(generator.generate())
        assertThat(provider.getSchema())
            .containsExactly(
                "c",
                java.util.Optional.empty<T?>(),
                "a",
                java.util.Optional.empty<T?>(),
                "b",
                java.util.Optional.empty<T?>()
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithDocumentedSchema_getFields() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN) // Note fields in setSchema() call below are not alphabetized to simulate
                // non-alphabetized field order in a provider declaration in Starlark code.
                .setSchema(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "c",
                        "Parameter c",
                        "a",
                        "Parameter a",
                        "b",
                        "Parameter b"
                    )
                )
                .buildWithIdentityToken(generator.generate())
        assertThat(provider.getFields()).containsExactly("a", "b", "c").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerWithDocumentedSchema_getSchema() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN) // Note fields in setSchema() call below are not alphabetized to simulate
                // non-alphabetized field order in a provider declaration in Starlark code.
                .setSchema(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "c",
                        "Parameter c",
                        "a",
                        "Parameter a",
                        "b",
                        "Parameter b"
                    )
                )
                .buildWithIdentityToken(generator.generate())
        assertThat(provider.getSchema())
            .containsExactly(
                "c",
                java.util.Optional.of<T?>("Parameter c"),
                "a",
                java.util.Optional.of<T?>("Parameter a"),
                "b",
                java.util.Optional.of<T?>("Parameter b")
            )
            .inOrder()
    }

    /**
     * Tests the safe storage and retrieval of depsets, which may be optimized to nested sets in the
     * internal representation.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemafulProvider_withDepset() {
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("field"))
                .buildWithIdentityToken(generator.generate())
        val instance1: StarlarkInfo
        val instance2: StarlarkInfo
        val instance3: StarlarkInfo
        val instance4: StarlarkInfo
        val instance5: StarlarkInfo
        val instance6: StarlarkInfo
        Mutability.create().use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            // Instantiates provider with values of different types all in the same field.
            // Instance with an empty depset of string
            instance1 =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "field", Depset.of(String::class.java, NestedSetBuilder.emptySet(STABLE_ORDER))
                    )
                ) as StarlarkInfo
            // Instance with a non-empty depset of string
            instance2 =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "field",
                        Depset.of(String::class.java, NestedSetBuilder.create(STABLE_ORDER, "foo"))
                    )
                ) as StarlarkInfo
            // Instance with a non-empty depset of int
            instance3 =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "field",
                        Depset.of(
                            StarlarkInt::class.java,
                            NestedSetBuilder.create(STABLE_ORDER, StarlarkInt.of(1))
                        )
                    )
                ) as StarlarkInfo
            // Instance with a string (not a depset)
            instance4 =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<String?, Any?>("field", "foo")
                ) as StarlarkInfo
            // Instance with a None
            instance5 =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<String?, Any?>("field", Starlark.NONE)
                ) as StarlarkInfo
            // Instance with the field not set
            instance6 =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<String?, Any?>()
                ) as StarlarkInfo
        }
        assertThat(instance1.getValue("field")).isInstanceOf(Depset::class.java)
        assertThat((instance1.getValue("field") as Depset).isEmpty()).isTrue()
        assertThat(instance2.getValue("field")).isInstanceOf(Depset::class.java)
        assertThat((instance2.getValue("field") as Depset).getElementClass()).isEqualTo(String::class.java)
        assertThat((instance2.getValue("field") as Depset).toList()).containsExactly("foo")
        assertThat(instance3.getValue("field")).isInstanceOf(Depset::class.java)
        assertThat((instance3.getValue("field") as Depset).getElementClass())
            .isEqualTo(StarlarkInt::class.java)
        assertThat((instance3.getValue("field") as Depset).toList()).containsExactly(StarlarkInt.of(1))
        assertThat(instance4.getValue("field")).isEqualTo("foo")
        assertThat(instance5.getValue("field")).isEqualTo(Starlark.NONE)
        assertThat(instance6.getValue("field")).isNull()
    }

    @org.junit.Test
    fun schemafulProvider_optimizeField() {
        val provider: StarlarkProvider =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("field"))
                .buildWithIdentityToken(generator.generate())

        // The first set is unwrapped and the type String is registered in the predictor.
        val depset1: Depset = Depset.of(String::class.java, NestedSetBuilder.create(STABLE_ORDER, "a", "b", "c"))
        assertThat(provider.optimizeField(0, depset1)).isSameInstanceAs(depset1.getSet())

        // A set with Integer type does not match and cannot be optimized.
        val depset2: Depset? =
            Depset.of(
                StarlarkInt::class.java,
                NestedSetBuilder.create(STABLE_ORDER, StarlarkInt.of(1), StarlarkInt.of(2))
            )
        assertThat(provider.optimizeField(0, depset2)).isSameInstanceAs(depset2)

        // A third, matching Depset is unwrapped.
        val depset3: Depset = Depset.of(String::class.java, NestedSetBuilder.create(STABLE_ORDER, "d", "e"))
        assertThat(provider.optimizeField(0, depset3)).isSameInstanceAs(depset3.getSet())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemafulProvider_mutable() {
        val key: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:bar.bzl")), "prov")
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a"))
                .buildExported(key)
        val instance: StarlarkInfo
        Mutability.create().use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            instance =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<String?, Any?>("a", StarlarkList.of<String?>(mu, "x"))
                ) as StarlarkInfo
            val list: StarlarkList<String?> = instance.getValue("a") as StarlarkList<String?>

            list.addElement("y") // verifies the fields of the provider instance are mutable
            assertThat(instance.isImmutable()).isFalse()
        }
        val list: StarlarkList<String?>? = instance.getValue("a") as StarlarkList<String?>?
        Truth.assertThat(list as Iterable<*>?).containsExactly("x", "y")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemafulProvider_immutable() {
        val key: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:bar.bzl")), "prov")
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a"))
                .buildExported(key)
        val instance: StarlarkInfo
        Mutability.create().use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            instance =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<String?, Any?>("a", StarlarkList.of<String?>(mu, "x"))
                ) as StarlarkInfo
        }
        assertThat(instance.isImmutable()).isTrue()
        val list: StarlarkList<String?> = instance.getValue("a") as StarlarkList<String?>
        Truth.assertThat(list as Iterable<*>?).containsExactly("x")
        // verifies the fields of the frozen provider instance are immutable
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { list.addElement("y") })
        Truth.assertThat(e).hasMessageThat().contains("trying to mutate a frozen list value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemafulProviderWithDepset_isImmutable() {
        val key: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:bar.bzl")), "prov")
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a"))
                .buildExported(key)
        val instance: StarlarkInfo
        Mutability.create().use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            instance =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "a", Depset.of(String::class.java, NestedSetBuilder.create(STABLE_ORDER, "foo"))
                    )
                ) as StarlarkInfo
            assertThat(instance.isImmutable()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemafulProviderWithDepset_becomesImmutable() {
        val key: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:bar.bzl")), "prov")
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a", "b"))
                .buildExported(key)
        val instance: StarlarkInfo
        Mutability.create().use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            instance =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "a",
                        Depset.of(String::class.java, NestedSetBuilder.create(STABLE_ORDER, "foo")),
                        "b",
                        StarlarkList.of<String?>(mu, "x")
                    )
                ) as StarlarkInfo
            assertThat(instance.isImmutable()).isFalse()
        }
        assertThat(instance.isImmutable()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun schemafulProvider_optimisedImmutable() {
        val key: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:bar.bzl")), "prov")
        val provider: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .setSchema(com.google.common.collect.ImmutableList.of<E?>("a"))
                .buildExported(key)
        var instance: StarlarkInfo
        Mutability.create().use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            instance =
                Starlark.call(
                    thread,
                    provider,  /* args= */
                    com.google.common.collect.ImmutableList.of<Any?>(),  /* kwargs= */
                    com.google.common.collect.ImmutableMap.of<String?, Any?>("a", StarlarkList.of<String?>(mu, "x"))
                ) as StarlarkInfo
        }
        instance = instance.unsafeOptimizeMemoryLayout()

        assertThat(instance.isImmutable()).isTrue()
        val list: StarlarkList<String?> = instance.getValue("a") as StarlarkList<String?>
        Truth.assertThat(list as Iterable<*>?).containsExactly("x")

        // verifies the fields of the frozen and optimised provider instance are immutable
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { list.addElement("y") })
        Truth.assertThat(e).hasMessageThat().contains("trying to mutate a frozen list value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerEquals() {
        // All permutations of differing label and differing name.
        val keyFooA: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo.bzl")), "provA")
        val keyFooB: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo.bzl")), "provB")
        val keyBarA: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//bar.bzl")), "provA")
        val keyBarB: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//bar.bzl")), "provB")

        // 1 for each key, plus a duplicate for one of the keys, plus 2 that have no key.
        val provFooA1: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(keyFooA)
        val provFooA2: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(keyFooA)
        val provFooB: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(keyFooB)
        val provBarA: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(keyBarA)
        val provBarB: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(keyBarB)
        val generator: SymbolGenerator<*> = SymbolGenerator.create<String?>("test")
        val provUnexported1: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(generator.generate())
        val provUnexported2: StarlarkProvider? =
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildWithIdentityToken(generator.generate())

        // For exported providers, different keys -> unequal, same key -> equal. For unexported
        // providers it comes down to object identity.
        EqualsTester()
            .addEqualityGroup(provFooA1, provFooA2)
            .addEqualityGroup(provFooB)
            .addEqualityGroup(provBarA, provBarA) // reflexive equality (exported)
            .addEqualityGroup(provBarB)
            .addEqualityGroup(provUnexported1, provUnexported1) // reflexive equality (unexported)
            .addEqualityGroup(provUnexported2)
            .testEquals()
    }

    companion object {
        /** Custom init equivalent to `def initBC(a): return {a:a, b:a*2, c:a*3}`  */
        private val initBC: StarlarkCallable = object : StarlarkCallable {
            @Throws(net.starlark.java.eval.EvalException::class)
            override fun call(thread: StarlarkThread?, args: Tuple, kwargs: Dict<String?, Any?>): Any? {
                if (!args.isEmpty()) {
                    throw Starlark.errorf("unexpected positional arguments")
                }
                if (kwargs.size != 1 || !kwargs.containsKey("a")) {
                    throw Starlark.errorf("expected a single `a` argument")
                }
                val a: StarlarkInt = kwargs.get("a") as StarlarkInt
                return Dict.builder<Any?, Any?>()
                    .put("a", a)
                    .put("b", StarlarkInt.of(a.toIntUnchecked() * 2))
                    .put("c", StarlarkInt.of(a.toIntUnchecked() * 3))
                    .build(Mutability.IMMUTABLE)
            }

            val name: String
                get() = "initBC"

            val location: net.starlark.java.syntax.Location
                get() = net.starlark.java.syntax.Location.BUILTIN
        }

        /** Instantiates a [StarlarkInfo] with fields a=1 (and nothing else).  */
        @Throws(java.lang.Exception::class)
        private fun instantiateWithA1(provider: StarlarkCallable?): StarlarkInfo {
            Mutability.create().use { mu ->
                val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
                val result: Any? =
                    Starlark.call(
                        thread,
                        provider,  /*args=*/
                        com.google.common.collect.ImmutableList.of<Any?>(),  /*kwargs=*/
                        com.google.common.collect.ImmutableMap.of<String?, Any?>("a", StarlarkInt.of(1))
                    )
                Truth.assertThat(result).isInstanceOf(StarlarkInfo::class.java)
                return result as StarlarkInfo
            }
        }

        /** Instantiates a [StarlarkInfo] with fields a=1, b=2, c=3 (and nothing else).  */
        @Throws(java.lang.Exception::class)
        private fun instantiateWithA1B2C3(provider: StarlarkCallable?): StarlarkInfo {
            Mutability.create().use { mu ->
                val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
                val result: Any? =
                    Starlark.call(
                        thread,
                        provider,  /*args=*/
                        com.google.common.collect.ImmutableList.of<Any?>(),  /*kwargs=*/
                        com.google.common.collect.ImmutableMap.of<String?, Any?>(
                            "a", StarlarkInt.of(1), "b", StarlarkInt.of(2), "c", StarlarkInt.of(3)
                        )
                    )
                Truth.assertThat(result).isInstanceOf(StarlarkInfo::class.java)
                return result as StarlarkInfo
            }
        }

        /** Asserts that a [StarlarkInfo] has field a=1 (and nothing else).  */
        @Throws(java.lang.Exception::class)
        private fun assertHasExactlyValuesA1(info: StarlarkInfo) {
            assertThat(info.getFieldNames()).containsExactly("a")
            assertThat(info.getValue("a")).isEqualTo(StarlarkInt.of(1))
        }

        /** Asserts that a [StarlarkInfo] has fields a=1, b=2, c=3 (and nothing else).  */
        @Throws(java.lang.Exception::class)
        private fun assertHasExactlyValuesA1B2C3(info: StarlarkInfo) {
            assertThat(info.getFieldNames()).containsExactly("a", "b", "c")
            assertThat(info.getValue("a")).isEqualTo(StarlarkInt.of(1))
            assertThat(info.getValue("b")).isEqualTo(StarlarkInt.of(2))
            assertThat(info.getValue("c")).isEqualTo(StarlarkInt.of(3))
        }
    }
}
