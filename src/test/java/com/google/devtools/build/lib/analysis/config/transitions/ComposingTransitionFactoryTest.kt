// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config.transitions

import com.google.devtools.build.lib.analysis.RequiredConfigFragmentsProvider

/** Tests for [ComposingTransitionFactory].  */
@RunWith(JUnit4::class)
class ComposingTransitionFactoryTest {
    private var eventHandler: com.google.devtools.build.lib.events.EventHandler? = null

    @Before
    fun init() {
        eventHandler = StoredEventHandler()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compose_patch_patch() {
        // Same flag, will overwrite.
        val composed: TransitionFactory<StubData?> =
            ComposingTransitionFactory.of(
                StubPatchFactory(FLAG_1, "value1"), StubPatchFactory(FLAG_1, "value2")
            )

        assertThat(composed).isNotNull()
        assertThat(composed.isSplit()).isFalse()
        val transition: ConfigurationTransition = composed.create(StubData())
        val results: MutableCollection<BuildOptions?>? =
            transition
                .apply(
                    TransitionUtil.restrict(transition, BuildOptions.builder().build()), eventHandler
                )
                .values()
        Truth.assertThat(results).isNotNull()
        Truth.assertThat(results).hasSize(1)
        val result: BuildOptions? = com.google.common.collect.Iterables.getOnlyElement<BuildOptions?>(results)
        assertThat(result).isNotNull()
        assertThat(result.getStarlarkOptions()).containsEntry(FLAG_1, "value2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compose_patch_split() {
        // Different flags, will combine.
        val composed: TransitionFactory<StubData?> =
            ComposingTransitionFactory.of(
                StubPatchFactory(FLAG_1, "value1"),
                StubSplitFactory(FLAG_2, "value2a", "value2b")
            )

        assertThat(composed).isNotNull()
        assertThat(composed.isSplit()).isTrue()
        val transition: ConfigurationTransition = composed.create(StubData())
        val results: MutableMap<String?, BuildOptions>? =
            transition.apply(
                TransitionUtil.restrict(transition, BuildOptions.builder().build()), eventHandler
            )
        Truth.assertThat(results).isNotNull()
        Truth.assertThat(results).hasSize(2)

        val result0: BuildOptions = results!!.get("stub_split0")
        assertThat(result0).isNotNull()
        assertThat(result0.getStarlarkOptions()).containsEntry(FLAG_1, "value1")
        assertThat(result0.getStarlarkOptions()).containsEntry(FLAG_2, "value2a")

        val result1: BuildOptions = results.get("stub_split1")
        assertThat(result1).isNotNull()
        assertThat(result1.getStarlarkOptions()).containsEntry(FLAG_1, "value1")
        assertThat(result1.getStarlarkOptions()).containsEntry(FLAG_2, "value2b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun compose_split_patch() {
        // Different flags, will combine.
        val composed: TransitionFactory<StubData?> =
            ComposingTransitionFactory.of(
                StubSplitFactory(FLAG_1, "value1a", "value1b"),
                StubPatchFactory(FLAG_2, "value2")
            )

        assertThat(composed).isNotNull()
        assertThat(composed.isSplit()).isTrue()
        val transition: ConfigurationTransition = composed.create(StubData())
        val results: MutableMap<String?, BuildOptions>? =
            transition.apply(
                TransitionUtil.restrict(transition, BuildOptions.builder().build()), eventHandler
            )
        Truth.assertThat(results).isNotNull()
        Truth.assertThat(results).hasSize(2)

        val result0: BuildOptions = results!!.get("stub_split0")
        assertThat(result0).isNotNull()
        assertThat(result0.getStarlarkOptions()).containsEntry(FLAG_1, "value1a")
        assertThat(result0.getStarlarkOptions()).containsEntry(FLAG_2, "value2")

        val result1: BuildOptions = results.get("stub_split1")
        assertThat(result1).isNotNull()
        assertThat(result1.getStarlarkOptions()).containsEntry(FLAG_1, "value1b")
        assertThat(result1.getStarlarkOptions()).containsEntry(FLAG_2, "value2")
    }

    @org.junit.Test
    fun compose_split_split() {
        // Combining two split transition factories is not allowed.
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                ComposingTransitionFactory.of(
                    StubSplitFactory(FLAG_1, "value1a", "value1b"),
                    StubSplitFactory(FLAG_2, "value2a", "value2b")
                )
            })
    }

    @org.junit.Test
    fun compose_noTrans_first() {
        val patch: TransitionFactory<StubData?> = StubPatchFactory(FLAG_1, "value")
        val composed: TransitionFactory<StubData?>? =
            ComposingTransitionFactory.of(NoTransition.getFactory(), patch)

        assertThat(composed).isNotNull()
        assertThat(composed).isEqualTo(patch)
    }

    @org.junit.Test
    fun compose_noTrans_second() {
        val patch: TransitionFactory<StubData?> = StubPatchFactory(FLAG_1, "value")
        val composed: TransitionFactory<StubData?>? =
            ComposingTransitionFactory.of(patch, NoTransition.getFactory())

        assertThat(composed).isNotNull()
        assertThat(composed).isEqualTo(patch)
    }

    private class StubData : TransitionFactory.Data

    private class StubPatchFactory(flagLabel: Label?, flagValue: String?) : TransitionFactory<StubData?> {
        private val flagLabel: Label?
        private val flagValue: String?

        init {
            this.flagLabel = flagLabel
            this.flagValue = flagValue
        }

        public override fun create(data: StubData?): ConfigurationTransition {
            return PatchTransition { options, eventHandler ->
                updateOptions(
                    options.underlying(),
                    flagLabel,
                    flagValue
                )
            } as PatchTransition
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.ANY
        }
    }

    private class StubSplitFactory(flagLabel: Label?, vararg flagValues: String?) : TransitionFactory<StubData?> {
        private val flagLabel: Label?
        private val flagValues: com.google.common.collect.ImmutableList<String?>

        init {
            this.flagLabel = flagLabel
            this.flagValues = com.google.common.collect.ImmutableList.copyOf<String?>(flagValues)
        }

        public override fun create(data: StubData?): ConfigurationTransition {
            return SplitTransition { options, eventHandler ->
                IntStream.range(0, flagValues.size())
                    .boxed()
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<Int?, String?, Any?>(
                            java.util.function.Function { i: Int? -> "stub_split" + i },
                            java.util.function.Function { i: Int? ->
                                updateOptions(
                                    options.underlying(),
                                    flagLabel,
                                    flagValues.get(i)
                                )
                            })
                    )
            } as SplitTransition
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.ANY
        }

        public override fun isSplit(): Boolean {
            return true
        }
    }

    /** Custom fragment for use in tests.  */
    private class TransitionFactoryWithCustomFragments
        (fragments: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?) :
        TransitionFactory<StubData?> {
        private val fragments: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?

        init {
            this.fragments = fragments
        }

        public override fun create(data: StubData?): ConfigurationTransition {
            return object : PatchTransition() {
                public override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>? {
                    return fragments
                }

                public override fun patch(
                    options: BuildOptionsView,
                    eventHandler: com.google.devtools.build.lib.events.EventHandler?
                ): BuildOptions {
                    return options.underlying()
                }
            }
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.ANY
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun composed_required_fragments() {
        val composed: TransitionFactory<StubData?> =
            ComposingTransitionFactory.of(
                TransitionFactoryWithCustomFragments(com.google.common.collect.ImmutableSet.of<E?>(CppOptions::class.java)),
                TransitionFactoryWithCustomFragments(com.google.common.collect.ImmutableSet.of<E?>(JavaOptions::class.java))
            )
        val requiredFragments: RequiredConfigFragmentsProvider.Builder =
            RequiredConfigFragmentsProvider.builder()
        val transition: ConfigurationTransition = composed.create(StubData())
        transition.addRequiredFragments(requiredFragments, null)
        assertThat(requiredFragments.build().optionsClasses())
            .containsExactly(CppOptions::class.java, JavaOptions::class.java)
    }

    companion object {
        // Use starlark flags for the test since they are easy to set and check.
        private val FLAG_1: Label? = Label.parseCanonicalUnchecked("//flag1")
        private val FLAG_2: Label? = Label.parseCanonicalUnchecked("//flag2")

        // Helper methods and classes for the tests.
        private fun updateOptions(source: BuildOptions, flag: Label?, value: String?): BuildOptions {
            return source.clone().toBuilder().addStarlarkOption(flag, value).build()
        }
    }
}
