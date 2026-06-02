// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.common.options.OptionsClass

/** Tests for [FragmentRegistry].  */
@RunWith(JUnit4::class)
class FragmentRegistryTest {
    @OptionsClass
    private abstract class OptionsA : FragmentOptions()

    @OptionsClass
    private abstract class OptionsB : FragmentOptions()

    @OptionsClass
    private abstract class MoreOptions : FragmentOptions()

    @OptionsClass
    private abstract class EvenMoreOptions : FragmentOptions()

    @RequiresOptions(options = OptionsA::class)
    private class FragmentA : Fragment()

    @RequiresOptions(options = OptionsB::class)
    private class FragmentB : Fragment()

    @org.junit.Test
    fun createsRegistry() {
        val registry: FragmentRegistry =
            FragmentRegistry.create( /*allFragments=*/
                com.google.common.collect.ImmutableList.of<E?>(
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentA::class.java,
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentB::class.java
                ),  /*universalFragments=*/
                com.google.common.collect.ImmutableList.of<E?>(com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentB::class.java),  /*additionalOptions=*/
                com.google.common.collect.ImmutableList.of<E?>(
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.MoreOptions::class.java,
                    EvenMoreOptions::class.java
                )
            )

        assertThat(registry.getAllFragments()).containsExactly(
            com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentA::class.java,
            com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentB::class.java
        )
        assertThat(registry.getUniversalFragments()).containsExactly(com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentB::class.java)
        assertThat(registry.getOptionsClasses())
            .containsExactly(
                OptionsA::class.java,
                OptionsB::class.java,
                com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.MoreOptions::class.java,
                EvenMoreOptions::class.java
            )
    }

    @org.junit.Test
    fun canonicalizesOrder() {
        val registry1: FragmentRegistry =
            FragmentRegistry.create( /*allFragments=*/
                com.google.common.collect.ImmutableList.of<E?>(
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentA::class.java,
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentB::class.java
                ),  /*universalFragments=*/
                com.google.common.collect.ImmutableList.of<E?>(
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentA::class.java,
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentB::class.java
                ),  /*additionalOptions=*/
                com.google.common.collect.ImmutableList.of<E?>(
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.MoreOptions::class.java,
                    EvenMoreOptions::class.java
                )
            )
        val registry2: FragmentRegistry =
            FragmentRegistry.create( /*allFragments=*/
                com.google.common.collect.ImmutableList.of<E?>(
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentB::class.java,
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentA::class.java
                ),  /*universalFragments=*/
                com.google.common.collect.ImmutableList.of<E?>(
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentB::class.java,
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentA::class.java
                ),  /*additionalOptions=*/
                com.google.common.collect.ImmutableList.of<E?>(
                    EvenMoreOptions::class.java,
                    com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.MoreOptions::class.java
                )
            )

        assertThat(registry1.getAllFragments())
            .containsAtLeastElementsIn(registry2.getAllFragments())
            .inOrder()
        assertThat(registry1.getUniversalFragments())
            .containsAtLeastElementsIn(registry2.getUniversalFragments())
            .inOrder()
        assertThat(registry1.getOptionsClasses())
            .containsAtLeastElementsIn(registry2.getOptionsClasses())
            .inOrder()
    }

    @org.junit.Test
    fun allFragmentsMustContainUniversalFragments() {
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
                java.lang.IllegalArgumentException::class.java,
                org.junit.function.ThrowingRunnable {
                    FragmentRegistry.create( /*allFragments=*/
                        com.google.common.collect.ImmutableList.of<E?>(com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentA::class.java),  /*universalFragments=*/
                        com.google.common.collect.ImmutableList.of<E?>(com.google.devtools.build.lib.analysis.config.FragmentRegistryTest.FragmentB::class.java),  /*additionalOptions=*/
                        com.google.common.collect.ImmutableList.of<E?>()
                    )
                })

        Truth.assertThat(e).hasMessageThat().contains("Missing universally required fragments")
        Truth.assertThat(e).hasMessageThat().contains("FragmentB")
    }
}
