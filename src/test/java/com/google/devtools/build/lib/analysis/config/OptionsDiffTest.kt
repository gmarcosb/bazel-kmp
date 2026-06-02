// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** Tests of @link OptionsDiff}.  */
@RunWith(JUnit4::class)
class OptionsDiffTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diff() {
        val one: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8")
        val two: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=dbg", "cpu=k8")
        val three: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=dbg", "cpu=k8")

        val diffOneTwo: OptionsDiff = OptionsDiff.diff(one, two)
        val diffTwoThree: OptionsDiff = OptionsDiff.diff(two, three)

        assertThat(diffOneTwo.areSame()).isFalse()
        assertThat(diffOneTwo.getFirst().keySet()).isEqualTo(diffOneTwo.getSecond().keySet())
        com.google.common.truth.Subject.contains("opt")
        com.google.common.truth.Subject.contains("dbg")

        assertThat(diffTwoThree.areSame()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diff_differentFragments() {
        val one: BuildOptions? = BuildOptions.of(com.google.common.collect.ImmutableList.of<E?>(CppOptions::class.java))
        val two: BuildOptions? = BuildOptions.of(BUILD_CONFIG_OPTIONS)

        val diff: OptionsDiff = OptionsDiff.diff(one, two)

        assertThat(diff.areSame()).isFalse()
        assertThat(diff.getExtraFirstFragmentClassesForTesting()).containsExactly(CppOptions::class.java)
        assertThat(
            diff.getExtraSecondFragmentsForTesting().stream().map(FragmentOptions::getOptionsClass)
        )
            .containsExactlyElementsIn(BUILD_CONFIG_OPTIONS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun biff_nullOptionsThrow() {
        val options: BuildOptions? =
            BuildOptions.of(BUILD_CONFIG_OPTIONS, "--compilation_mode=opt", "cpu=k8")
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { OptionsDiff.diff(options, null) })
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { OptionsDiff.diff(null, options) })
    }

    @org.junit.Test
    fun diff_sameStarlarkOptions() {
        val flagName: Label = Label.parseCanonicalUnchecked("//foo/flag")
        val flagValue = "value"
        val one: BuildOptions? = BuildOptions.of(com.google.common.collect.ImmutableMap.of<K?, V?>(flagName, flagValue))
        val two: BuildOptions? = BuildOptions.of(com.google.common.collect.ImmutableMap.of<K?, V?>(flagName, flagValue))

        assertThat(OptionsDiff.diff(one, two).areSame()).isTrue()
    }

    @org.junit.Test
    fun diff_differentStarlarkOptions() {
        val flagName: Label = Label.parseCanonicalUnchecked("//bar/flag")
        val flagValueOne = "valueOne"
        val flagValueTwo = "valueTwo"
        val one: BuildOptions? =
            BuildOptions.of(com.google.common.collect.ImmutableMap.of<K?, V?>(flagName, flagValueOne))
        val two: BuildOptions? =
            BuildOptions.of(com.google.common.collect.ImmutableMap.of<K?, V?>(flagName, flagValueTwo))

        val diff: OptionsDiff = OptionsDiff.diff(one, two)

        assertThat(diff.areSame()).isFalse()
        assertThat(diff.getStarlarkFirstForTesting().keySet())
            .isEqualTo(diff.getStarlarkSecondForTesting().keySet())
        assertThat(diff.getStarlarkFirstForTesting().keySet()).containsExactly(flagName)
        assertThat(diff.getStarlarkFirstForTesting().values()).containsExactly(flagValueOne)
        assertThat(diff.getStarlarkSecondForTesting().values()).containsExactly(flagValueTwo)
    }

    @org.junit.Test
    fun diff_extraStarlarkOptions() {
        val flagNameOne: Label = Label.parseCanonicalUnchecked("//extra/flag/one")
        val flagNameTwo: Label = Label.parseCanonicalUnchecked("//extra/flag/two")
        val flagValue = "foo"
        val one: BuildOptions? =
            BuildOptions.of(com.google.common.collect.ImmutableMap.of<K?, V?>(flagNameOne, flagValue))
        val two: BuildOptions? =
            BuildOptions.of(com.google.common.collect.ImmutableMap.of<K?, V?>(flagNameTwo, flagValue))

        val diff: OptionsDiff = OptionsDiff.diff(one, two)

        assertThat(diff.areSame()).isFalse()
        assertThat(diff.getExtraStarlarkOptionsFirstForTesting()).containsExactly(flagNameOne)
        assertThat(diff.getExtraStarlarkOptionsSecondForTesting().entrySet())
            .containsExactly(com.google.common.collect.Maps.immutableEntry<K?, V?>(flagNameTwo, flagValue))
    }

    companion object {
        private val BUILD_CONFIG_OPTIONS: com.google.common.collect.ImmutableList<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java)
    }
}
