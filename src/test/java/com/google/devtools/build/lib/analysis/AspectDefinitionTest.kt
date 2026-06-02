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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Tests for aspect definitions.
 */
@RunWith(JUnit4::class)
class AspectDefinitionTest {
    private class P1 : TransitiveInfoProvider

    /**
     * A dummy aspect factory. Is there to demonstrate how to define aspects and so that we can test
     * `attributeAspect`.
     */
    class TestAspectClass : NativeAspectClass(), ConfiguredAspectFactory {
        private var definition: AspectDefinition? = null

        fun setAspectDefinition(definition: AspectDefinition?) {
            this.definition = definition
        }

        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            context: RuleContext?,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect? {
            throw java.lang.IllegalStateException()
        }

        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return definition
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectWithImplicitOrLateboundAttribute_addsToAttributeMap() {
        val implicit: Attribute? =
            attr("\$runtime", BuildType.LABEL)
                .value(Label.parseCanonicalUnchecked("//run:time"))
                .build()
        val latebound: LabelLateBoundDefault<java.lang.Void?>? =
            LateBoundDefault.fromConstantForTesting(Label.parseCanonicalUnchecked("//run:away"))
        val simple: AspectDefinition =
            Builder(TEST_ASPECT_CLASS)
                .add(implicit)
                .add(attr(":latebound", BuildType.LABEL).value(latebound))
                .build()
        assertThat(simple.getAttributes()).containsEntry("\$runtime", implicit)
        assertThat(simple.getAttributes()).containsKey(":latebound")
        assertThat(simple.getAttributes().get(":latebound").getLateBoundDefault())
            .isEqualTo(latebound)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectWithDuplicateAttribute_failsToAdd() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                Builder(TEST_ASPECT_CLASS)
                    .add(
                        attr("\$runtime", BuildType.LABEL)
                            .value(Label.parseCanonicalUnchecked("//run:time"))
                    )
                    .add(
                        attr("\$runtime", BuildType.LABEL)
                            .value(Label.parseCanonicalUnchecked("//oops"))
                    )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectWithUserVisibleAttribute_failsToAdd() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                Builder(TEST_ASPECT_CLASS)
                    .add(
                        attr("invalid", BuildType.LABEL)
                            .value(Label.parseCanonicalUnchecked("//run:time"))
                            .allowedFileTypes(FileTypeSet.NO_FILE)
                    )
                    .build()
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRequireStarlarkProviders_addsFlatSetOfRequiredProviders() {
        val requiresProviders: AspectDefinition =
            Builder(TEST_ASPECT_CLASS)
                .requireStarlarkProviders(STARLARK_P1, STARLARK_P2)
                .build()

        val expectedOkSet: AdvertisedProviderSet? =
            AdvertisedProviderSet.builder()
                .addStarlark(STARLARK_P1)
                .addStarlark(STARLARK_P2)
                .addStarlark(STARLARK_P3)
                .build()
        assertThat(requiresProviders.getRequiredProviders().isSatisfiedBy(expectedOkSet)).isTrue()

        val expectedFailSet: AdvertisedProviderSet? =
            AdvertisedProviderSet.builder().addStarlark(STARLARK_P1).build()
        assertThat(requiresProviders.getRequiredProviders().isSatisfiedBy(expectedFailSet)).isFalse()

        assertThat(requiresProviders.getRequiredProviders().isSatisfiedBy(AdvertisedProviderSet.ANY))
            .isTrue()
        assertThat(requiresProviders.getRequiredProviders().isSatisfiedBy(AdvertisedProviderSet.EMPTY))
            .isFalse()
    }

    @org.junit.Test
    fun testRequireStarlarkProviders_addsTwoSetsOfRequiredProviders() {
        val requiresProviders: AspectDefinition =
            Builder(TEST_ASPECT_CLASS)
                .requireStarlarkProviderSets(
                    com.google.common.collect.ImmutableList.of<E?>(
                        com.google.common.collect.ImmutableSet.of<E?>(STARLARK_P1, STARLARK_P2),
                        com.google.common.collect.ImmutableSet.of<E?>(
                            STARLARK_P3
                        )
                    )
                )
                .build()

        val expectedOkSet1: AdvertisedProviderSet? =
            AdvertisedProviderSet.builder().addStarlark(STARLARK_P1).addStarlark(STARLARK_P2).build()
        assertThat(requiresProviders.getRequiredProviders().isSatisfiedBy(expectedOkSet1)).isTrue()

        val expectedOkSet2: AdvertisedProviderSet? =
            AdvertisedProviderSet.builder().addStarlark(STARLARK_P3).build()
        assertThat(requiresProviders.getRequiredProviders().isSatisfiedBy(expectedOkSet2)).isTrue()

        val expectedFailSet: AdvertisedProviderSet? =
            AdvertisedProviderSet.builder().addStarlark(STARLARK_P4).build()
        assertThat(requiresProviders.getRequiredProviders().isSatisfiedBy(expectedFailSet)).isFalse()

        assertThat(requiresProviders.getRequiredProviders().isSatisfiedBy(AdvertisedProviderSet.ANY))
            .isTrue()
        assertThat(requiresProviders.getRequiredProviders().isSatisfiedBy(AdvertisedProviderSet.EMPTY))
            .isFalse()
    }

    @org.junit.Test
    fun testRequireProviders_defaultAcceptsEverything() {
        val noRequiredProviders: AspectDefinition = Builder(TEST_ASPECT_CLASS).build()

        val expectedOkSet: AdvertisedProviderSet? =
            AdvertisedProviderSet.builder()
                .addBuiltin(com.google.devtools.build.lib.analysis.AspectDefinitionTest.P1::class.java).addStarlark(
                    STARLARK_P4
                ).build()
        assertThat(noRequiredProviders.getRequiredProviders().isSatisfiedBy(expectedOkSet)).isTrue()

        assertThat(noRequiredProviders.getRequiredProviders().isSatisfiedBy(AdvertisedProviderSet.ANY))
            .isTrue()
        assertThat(
            noRequiredProviders.getRequiredProviders().isSatisfiedBy(AdvertisedProviderSet.EMPTY)
        )
            .isTrue()
    }

    @org.junit.Test
    fun testRequireAspectClass_defaultAcceptsNothing() {
        val noAspects: AspectDefinition = Builder(TEST_ASPECT_CLASS)
            .build()

        val expectedFailSet: AdvertisedProviderSet? =
            AdvertisedProviderSet.builder()
                .addBuiltin(com.google.devtools.build.lib.analysis.AspectDefinitionTest.P1::class.java).build()

        assertThat(noAspects.getRequiredProvidersForAspects().isSatisfiedBy(AdvertisedProviderSet.ANY))
            .isFalse()
        assertThat(
            noAspects.getRequiredProvidersForAspects()
                .isSatisfiedBy(AdvertisedProviderSet.EMPTY)
        )
            .isFalse()

        assertThat(noAspects.getRequiredProvidersForAspects().isSatisfiedBy(expectedFailSet))
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoConfigurationFragmentPolicySetup_hasNonNullPolicy() {
        val noPolicy: AspectDefinition = Builder(TEST_ASPECT_CLASS)
            .build()
        assertThat(noPolicy.getConfigurationFragmentPolicy()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRequiresConfigurationFragments_propagatedToConfigurationFragmentPolicy() {
        val requiresFragments: AspectDefinition =
            Builder(TEST_ASPECT_CLASS)
                .requiresConfigurationFragments(FooFragment::class.java, BarFragment::class.java)
                .build()
        assertThat(requiresFragments.getConfigurationFragmentPolicy()).isNotNull()
        assertThat(
            requiresFragments.getConfigurationFragmentPolicy().getRequiredConfigurationFragments()
        )
            .containsExactly(FooFragment::class.java, BarFragment::class.java)
    }

    private class FooFragment : Fragment()

    private class BarFragment : Fragment()

    @org.junit.Test
    fun testMissingFragmentPolicy_propagatedToConfigurationFragmentPolicy() {
        val missingFragments: AspectDefinition =
            Builder(TEST_ASPECT_CLASS)
                .setMissingFragmentPolicy(FooFragment::class.java, MissingFragmentPolicy.IGNORE)
                .build()
        assertThat(missingFragments.getConfigurationFragmentPolicy()).isNotNull()
        assertThat(
            missingFragments
                .getConfigurationFragmentPolicy()
                .getMissingFragmentPolicy(FooFragment::class.java)
        )
            .isEqualTo(MissingFragmentPolicy.IGNORE)
    }

    @org.junit.Test
    fun testRequiresConfigurationFragmentNames_propagatedToConfigurationFragmentPolicy() {
        val requiresFragments: AspectDefinition =
            Builder(TEST_ASPECT_CLASS)
                .requiresConfigurationFragmentsByStarlarkBuiltinName(com.google.common.collect.ImmutableList.of<E?>("test_fragment"))
                .build()
        assertThat(requiresFragments.getConfigurationFragmentPolicy()).isNotNull()
        assertThat(
            requiresFragments
                .getConfigurationFragmentPolicy()
                .isLegalConfigurationFragment(com.google.devtools.build.lib.analysis.AspectDefinitionTest.TestFragment::class.java)
        )
            .isTrue()
    }

    @org.junit.Test
    fun testAspectWithApplyToFiles_requiresProviders_fails() {
        val throwable: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    Builder(TEST_ASPECT_CLASS)
                        .applyToFiles(true)
                        .requireStarlarkProviders(STARLARK_P1, STARLARK_P2)
                        .build()
                })
        Truth.assertThat(throwable)
            .hasMessageThat()
            .contains("An aspect cannot simultaneously have required providers and apply to files.")
    }

    @org.junit.Test
    fun testAspectWithApplyToGeneratingRules_requiresProviders_fails() {
        val throwable: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    Builder(TEST_ASPECT_CLASS)
                        .applyToGeneratingRules(true)
                        .requireStarlarkProviders(STARLARK_P1, STARLARK_P2)
                        .build()
                })
        Truth.assertThat(throwable)
            .hasMessageThat()
            .contains(
                "An aspect cannot simultaneously have required providers and apply to generating"
                        + " rules."
            )
    }

    @org.junit.Test
    fun testAspectWithApplyToFiles_hasPropagationPredicate_fails() {
        val throwable: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    Builder(TEST_ASPECT_CLASS)
                        .applyToFiles(true)
                        .propagationPredicate(AspectPropagationPredicate(null, null))
                        .build()
                })
        Truth.assertThat(throwable)
            .hasMessageThat()
            .contains(
                "An aspect cannot simultaneously have a propagation predicate and apply to files."
            )
    }

    @org.junit.Test
    fun testAspectWithApplyToGeneratingRules_hasPropagationPredicate_fails() {
        val throwable: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable {
                    Builder(TEST_ASPECT_CLASS)
                        .applyToGeneratingRules(true)
                        .propagationPredicate(AspectPropagationPredicate(null, null))
                        .build()
                })
        Truth.assertThat(throwable)
            .hasMessageThat()
            .contains(
                "An aspect cannot simultaneously have a propagation predicate and apply to generating"
                        + " rules."
            )
    }

    @StarlarkBuiltin(name = "test_fragment", doc = "test fragment")
    private class TestFragment : StarlarkValue
    companion object {
        private val FAKE_LABEL: Label? = Label.parseCanonicalUnchecked("//fake/label.bzl")

        private val STARLARK_P1: StarlarkProviderIdentifier = StarlarkProviderIdentifier.forKey(
            Key(keyForBuild(FAKE_LABEL), "STARLARK_P1")
        )

        private val STARLARK_P2: StarlarkProviderIdentifier? = StarlarkProviderIdentifier.forKey(
            Key(keyForBuild(FAKE_LABEL), "STARLARK_P2")
        )

        private val STARLARK_P3: StarlarkProviderIdentifier = StarlarkProviderIdentifier.forKey(
            Key(keyForBuild(FAKE_LABEL), "STARLARK_P3")
        )

        private val STARLARK_P4: StarlarkProviderIdentifier? = StarlarkProviderIdentifier.forKey(
            Key(keyForBuild(FAKE_LABEL), "STARLARK_P4")
        )

        val TEST_ASPECT_CLASS: TestAspectClass = TestAspectClass()
    }
}
