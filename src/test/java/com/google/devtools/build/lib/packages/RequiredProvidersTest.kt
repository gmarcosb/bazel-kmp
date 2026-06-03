// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** Test for [com.google.devtools.build.lib.packages.RequiredProviders] class  */
@RunWith(JUnit4::class)
class RequiredProvidersTest {
    private class P1 : TransitiveInfoProvider

    private class P2 : TransitiveInfoProvider

    private class P3 : TransitiveInfoProvider

    @org.junit.Test
    fun any() {
        Truth.assertThat(
            satisfies(
                AdvertisedProviderSet.EMPTY,
                RequiredProviders.acceptAnyBuilder().build()
            )
        ).isTrue()
        Truth.assertThat(
            satisfies(
                AdvertisedProviderSet.ANY,
                RequiredProviders.acceptAnyBuilder().build()
            )
        ).isTrue()
        Truth.assertThat(
            satisfies(
                AdvertisedProviderSet.builder().addBuiltin(P1::class.java).build(),
                RequiredProviders.acceptAnyBuilder().build()
            )
        )
            .isTrue()
    }

    @org.junit.Test
    fun none() {
        Truth.assertThat(
            satisfies(
                AdvertisedProviderSet.EMPTY,
                RequiredProviders.acceptNoneBuilder().build()
            )
        ).isFalse()
        Truth.assertThat(
            satisfies(
                AdvertisedProviderSet.ANY,
                RequiredProviders.acceptNoneBuilder().build()
            )
        ).isFalse()
        Truth.assertThat(
            satisfies(
                AdvertisedProviderSet.builder().addBuiltin(P1::class.java).build(),
                RequiredProviders.acceptNoneBuilder().build()
            )
        )
            .isFalse()
        Truth.assertThat(
            satisfies(
                AdvertisedProviderSet.builder().build(),
                RequiredProviders.acceptNoneBuilder().build()
            )
        )
            .isFalse()
    }

    @org.junit.Test
    fun builtinProvidersAllMatch() {
        val providerSet: AdvertisedProviderSet =
            AdvertisedProviderSet.builder().addBuiltin(P1::class.java).addBuiltin(P2::class.java).build()
        Truth.assertThat(
            validateNative(
                providerSet,
                NO_PROVIDERS_REQUIRED,
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out TransitiveInfoProvider?>?>(
                    P1::class.java,
                    P2::class.java
                )
            )
        )
            .isTrue()
    }

    @org.junit.Test
    fun builtinProvidersBranchMatch() {
        Truth.assertThat(
            validateNative(
                AdvertisedProviderSet.builder().addBuiltin(P1::class.java).build(),
                NO_PROVIDERS_REQUIRED,
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out TransitiveInfoProvider?>?>(P1::class.java),
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out TransitiveInfoProvider?>?>(P2::class.java)
            )
        )
            .isTrue()
    }

    @org.junit.Test
    fun builtinsProvidersNoMatch() {
        Truth.assertThat(
            validateNative(
                AdvertisedProviderSet.builder().addBuiltin(P3::class.java).build(),
                "P1 or P2",
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out TransitiveInfoProvider?>?>(P1::class.java),
                com.google.common.collect.ImmutableSet.of<java.lang.Class<out TransitiveInfoProvider?>?>(P2::class.java)
            )
        )
            .isFalse()
    }

    @org.junit.Test
    fun starlarkProvidersAllMatch() {
        val providerSet: AdvertisedProviderSet =
            AdvertisedProviderSet.builder()
                .addStarlark(ID_NATIVE)
                .addStarlark(ID_STARLARK)
                .build()
        Truth.assertThat(
            validateStarlark(
                providerSet,
                NO_PROVIDERS_REQUIRED,
                com.google.common.collect.ImmutableSet.of<StarlarkProviderIdentifier?>(
                    ID_STARLARK, ID_NATIVE
                )
            )
        )
            .isTrue()
    }

    @org.junit.Test
    fun checkDescriptions() {
        assertThat(RequiredProviders.acceptAnyBuilder().build().getDescription())
            .isEqualTo("no providers required")
        assertThat(RequiredProviders.acceptNoneBuilder().build().getDescription())
            .isEqualTo("no providers accepted")
        assertThat(
            RequiredProviders.acceptAnyBuilder()
                .addStarlarkSet(com.google.common.collect.ImmutableSet.of<E?>(ID_STARLARK))
                .addBuiltinSet(com.google.common.collect.ImmutableSet.of<E?>(P1::class.java, P2::class.java))
                .build()
                .getDescription()
        )
            .isEqualTo("[P1, P2] or 'p_starlark'")
    }

    @get:org.junit.Test
    val starlarkProviders: Unit
        get() {
            assertThat(RequiredProviders.acceptAnyBuilder().build().getStarlarkProviders()).isEmpty()
            assertThat(RequiredProviders.acceptNoneBuilder().build().getStarlarkProviders()).isEmpty()
            assertThat(
                RequiredProviders.acceptAnyBuilder()
                    .addStarlarkSet(com.google.common.collect.ImmutableSet.of<E?>(ID_STARLARK))
                    .addBuiltinSet(com.google.common.collect.ImmutableSet.of<E?>(P1::class.java, P2::class.java))
                    .addBuiltinSet(com.google.common.collect.ImmutableSet.of<E?>(P3::class.java))
                    .build()
                    .getStarlarkProviders()
            )
                .containsExactly(com.google.common.collect.ImmutableSet.of<E?>(ID_STARLARK))
        }

    companion object {
        private const val NO_PROVIDERS_REQUIRED = "no providers required"

        private val P_NATIVE: Provider = object : BuiltinProvider<StructImpl?>("p_native", StructImpl::class.java) {}

        private val P_STARLARK: StarlarkProvider = StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
            .buildWithIdentityToken(SymbolGenerator.create<String?>("test").generate())

        init {
            try {
                P_STARLARK.export(
                    { ev -> },
                    Label.create("foo/bar", "x.bzl"),
                    "p_starlark",
                    net.starlark.java.syntax.Location.fromFile("/workspace/foo/bar/x.bzl")
                )
            } catch (e: LabelSyntaxException) {
                throw java.lang.AssertionError(e)
            }
        }

        private val ID_NATIVE: StarlarkProviderIdentifier? = StarlarkProviderIdentifier.forKey(P_NATIVE.getKey())
        private val ID_STARLARK: StarlarkProviderIdentifier = StarlarkProviderIdentifier.forKey(P_STARLARK.getKey())

        private fun satisfies(
            providers: AdvertisedProviderSet,
            requiredProviders: RequiredProviders
        ): Boolean {
            val result: Boolean = requiredProviders.isSatisfiedBy(providers)

            assertThat(
                requiredProviders.isSatisfiedBy(
                    providers.getBuiltinProviders()::contains,
                    providers.getStarlarkProviders()::contains
                )
            )
                .isEqualTo(result)
            return result
        }

        @java.lang.SafeVarargs
        private fun validateNative(
            providerSet: AdvertisedProviderSet,
            missing: String?,
            vararg sets: com.google.common.collect.ImmutableSet<java.lang.Class<out TransitiveInfoProvider?>?>?
        ): Boolean {
            val anyBuilder: RequiredProviders.Builder = RequiredProviders.acceptAnyBuilder()
            val noneBuilder: RequiredProviders.Builder = RequiredProviders.acceptNoneBuilder()
            for (set in sets) {
                anyBuilder.addBuiltinSet(set)
                noneBuilder.addBuiltinSet(set)
            }
            val rpStartingFromAny: RequiredProviders = anyBuilder.build()
            val result = satisfies(providerSet, rpStartingFromAny)
            assertThat(rpStartingFromAny.getMissing(providerSet).getDescription()).isEqualTo(missing)

            val rpStaringFromNone: RequiredProviders = noneBuilder.build()
            Truth.assertThat(satisfies(providerSet, rpStaringFromNone)).isEqualTo(result)
            assertThat(rpStaringFromNone.getMissing(providerSet).getDescription()).isEqualTo(missing)
            return result
        }

        @java.lang.SafeVarargs
        private fun validateStarlark(
            providerSet: AdvertisedProviderSet,
            missing: String?,
            vararg sets: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?
        ): Boolean {
            val anyBuilder: RequiredProviders.Builder = RequiredProviders.acceptAnyBuilder()
            val noneBuilder: RequiredProviders.Builder = RequiredProviders.acceptNoneBuilder()
            for (set in sets) {
                anyBuilder.addStarlarkSet(set)
                noneBuilder.addStarlarkSet(set)
            }

            val rpStartingFromAny: RequiredProviders = anyBuilder.build()
            val result = satisfies(providerSet, rpStartingFromAny)
            assertThat(rpStartingFromAny.getMissing(providerSet).getDescription()).isEqualTo(missing)

            val rpStaringFromNone: RequiredProviders = noneBuilder.build()
            Truth.assertThat(satisfies(providerSet, rpStaringFromNone)).isEqualTo(result)
            assertThat(rpStaringFromNone.getMissing(providerSet).getDescription()).isEqualTo(missing)
            return result
        }
    }
}
