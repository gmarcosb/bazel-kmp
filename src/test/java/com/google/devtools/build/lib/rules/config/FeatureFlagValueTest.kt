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
// limitations under the License
package com.google.devtools.build.lib.rules.config

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.ImmutableSortedSet
import com.google.devtools.build.lib.analysis.config.BuildOptions
import org.junit.Test
import java.util.Map

/** Tests for feature flag options data.  */
@RunWith(JUnit4::class)
class FeatureFlagValueTest : BuildViewTestCase() {
    @Throws(Exception::class)
    private fun emptyBuildOptions(): BuildOptions {
        return BuildOptions.of(ImmutableList.of<E?>(ConfigFeatureFlagOptions::class.java))
    }

    private fun getKnownDefaultFlags(options: BuildOptions): MutableSet<Label?> {
        return options.getStarlarkOptions().entrySet().stream()
            .filter({ entry -> FeatureFlagValue.DefaultValue.INSTANCE.equals(entry.getValue()) })
            .map({ Map.Entry.getKey() })
            .collect(ImmutableSet.toImmutableSet<E?>())
    }

    @Test
    @Throws(Exception::class)
    fun replaceFlagValues_reflectedInGetFlagValues() {
        val options: BuildOptions =
            FeatureFlagValue.replaceFlagValues(
                emptyBuildOptions(),
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"), "value",
                    Label.parseCanonicalUnchecked("//label:b"), "otherValue"
                )
            )
        assertThat(options.getStarlarkOptions())
            .containsExactly(
                Label.parseCanonicalUnchecked("//label:a"),
                FeatureFlagValue.SetValue.of("value"),
                Label.parseCanonicalUnchecked("//label:b"),
                FeatureFlagValue.SetValue.of("otherValue")
            )
    }

    @Test
    @Throws(Exception::class)
    fun replaceFlagValues_totallyReplacesFlagValuesMap() {
        var options: BuildOptions = emptyBuildOptions()
        options =
            FeatureFlagValue.replaceFlagValues(
                options,
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"), "value",
                    Label.parseCanonicalUnchecked("//label:b"), "otherValue"
                )
            )
        options =
            FeatureFlagValue.replaceFlagValues(
                options,
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"), "differentValue",
                    Label.parseCanonicalUnchecked("//label:c"), "differentFlag"
                )
            )
        assertThat(options.getStarlarkOptions())
            .containsExactly(
                Label.parseCanonicalUnchecked("//label:a"),
                FeatureFlagValue.SetValue.of("differentValue"),
                Label.parseCanonicalUnchecked("//label:c"),
                FeatureFlagValue.SetValue.of("differentFlag")
            )
    }

    @Test
    @Throws(Exception::class)
    fun replaceFlagValues_emptiesKnownDefaultFlagsAndUnknownFlags() {
        val originalMap: MutableMap<Label?, String?> =
            ImmutableMap.of<K?, V?>(
                Label.parseCanonicalUnchecked("//label:a"), "value",
                Label.parseCanonicalUnchecked("//label:b"), "otherValue"
            )
        var options: BuildOptions = emptyBuildOptions()
        options = FeatureFlagValue.replaceFlagValues(options, originalMap)
        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSortedSet.of(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:c"),
                    Label.parseCanonicalUnchecked("//label:d")
                )
            )
        options = FeatureFlagValue.replaceFlagValues(options, originalMap)
        assertThat(options.get(ConfigFeatureFlagOptions::class.java).getAllFeatureFlagValuesArePresent())
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun replaceFlagValues_leavesNonFeatureFlagValuesAlone() {
        val originalMap: MutableMap<Label?, String?> =
            ImmutableMap.of<K?, V?>(
                Label.parseCanonicalUnchecked("//label:a"), "value",
                Label.parseCanonicalUnchecked("//label:b"), "otherValue"
            )
        val newMap: MutableMap<Label?, String?> =
            ImmutableMap.of<K?, V?>(
                Label.parseCanonicalUnchecked("//label:a"), "differentValue",
                Label.parseCanonicalUnchecked("//label:c"), "differentFlag"
            )
        var options: BuildOptions =
            emptyBuildOptions().toBuilder()
                .addStarlarkOption(Label.parseCanonicalUnchecked("//unrelated/starlark:option"), true)
                .build()
        options = FeatureFlagValue.replaceFlagValues(options, originalMap)
        options = FeatureFlagValue.replaceFlagValues(options, newMap)
        assertThat(options.getStarlarkOptions())
            .containsEntry(Label.parseCanonicalUnchecked("//unrelated/starlark:option"), true)
    }

    @Test
    @Throws(Exception::class)
    fun trimFlagValues_defaults_toEmptySetProducesEmptyOptions() {
        var options: BuildOptions = emptyBuildOptions()

        options = FeatureFlagValue.trimFlagValues(options, ImmutableSet.of<E?>())

        assertThat(options.getStarlarkOptions()).isEmpty()
        assertThat(options.get(ConfigFeatureFlagOptions::class.java).getAllFeatureFlagValuesArePresent())
            .isFalse()
        Truth.assertThat(getKnownDefaultFlags(options)).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun trimFlagValues_defaults_toPopulatedSetPopulatesKnownDefaultFlags() {
        var options: BuildOptions = emptyBuildOptions()

        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSet.of<E?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:b"),
                    Label.parseCanonicalUnchecked("//label:c")
                )
            )

        assertThat(options.getStarlarkOptions())
            .containsExactly(
                Label.parseCanonicalUnchecked("//label:a"), FeatureFlagValue.DefaultValue.INSTANCE,
                Label.parseCanonicalUnchecked("//label:b"), FeatureFlagValue.DefaultValue.INSTANCE,
                Label.parseCanonicalUnchecked("//label:c"), FeatureFlagValue.DefaultValue.INSTANCE
            )
        assertThat(options.get(ConfigFeatureFlagOptions::class.java).getAllFeatureFlagValuesArePresent())
            .isFalse()
        Truth.assertThat(getKnownDefaultFlags(options))
            .containsExactly(
                Label.parseCanonicalUnchecked("//label:a"),
                Label.parseCanonicalUnchecked("//label:b"),
                Label.parseCanonicalUnchecked("//label:c")
            )
    }

    @Test
    @Throws(Exception::class)
    fun trimFlagValues_withFlagsSet_toEmptySetProducesEmptyOptions() {
        var options: BuildOptions = emptyBuildOptions()
        options =
            FeatureFlagValue.replaceFlagValues(
                options,
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    "value",
                    Label.parseCanonicalUnchecked("//label:d"),
                    "otherValue"
                )
            )

        options = FeatureFlagValue.trimFlagValues(options, ImmutableSet.of<E?>())

        assertThat(options.getStarlarkOptions()).isEmpty()
        assertThat(options.get(ConfigFeatureFlagOptions::class.java).getAllFeatureFlagValuesArePresent())
            .isFalse()
        Truth.assertThat(getKnownDefaultFlags(options)).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun trimFlagValues_withFlagsSet_toPopulatedSetPopulatesFlagValuesAndKnownDefaultFlags() {
        var options: BuildOptions = emptyBuildOptions()
        options =
            FeatureFlagValue.replaceFlagValues(
                options,
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    "value",
                    Label.parseCanonicalUnchecked("//label:d"),
                    "otherValue"
                )
            )

        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSet.of<E?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:b"),
                    Label.parseCanonicalUnchecked("//label:c")
                )
            )

        assertThat(options.getStarlarkOptions())
            .containsExactly(
                Label.parseCanonicalUnchecked("//label:a"), FeatureFlagValue.SetValue.of("value"),
                Label.parseCanonicalUnchecked("//label:b"), FeatureFlagValue.DefaultValue.INSTANCE,
                Label.parseCanonicalUnchecked("//label:c"), FeatureFlagValue.DefaultValue.INSTANCE
            )
        assertThat(options.get(ConfigFeatureFlagOptions::class.java).getAllFeatureFlagValuesArePresent())
            .isFalse()
        Truth.assertThat(getKnownDefaultFlags(options))
            .containsExactly(
                Label.parseCanonicalUnchecked("//label:b"), Label.parseCanonicalUnchecked("//label:c")
            )
    }

    @Test
    @Throws(Exception::class)
    fun trimFlagValues_withTrimmedFlagsSet_toEmptySetProducesEmptyOptions() {
        var options: BuildOptions = emptyBuildOptions()
        options =
            FeatureFlagValue.replaceFlagValues(
                options,
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    "value",
                    Label.parseCanonicalUnchecked("//label:d"),
                    "otherValue"
                )
            )
        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSet.of<E?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:b")
                )
            )

        options = FeatureFlagValue.trimFlagValues(options, ImmutableSet.of<E?>())

        assertThat(options.getStarlarkOptions()).isEmpty()
        assertThat(options.get(ConfigFeatureFlagOptions::class.java).getAllFeatureFlagValuesArePresent())
            .isFalse()
        Truth.assertThat(getKnownDefaultFlags(options)).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun trimFlagValues_withTrimmedFlagsSet_toPopulatedSetPopulatesFlagState() {
        var options: BuildOptions = emptyBuildOptions()
        options =
            FeatureFlagValue.replaceFlagValues(
                options,
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    "value",
                    Label.parseCanonicalUnchecked("//label:d"),
                    "otherValue"
                )
            )
        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSet.of<E?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:b")
                )
            )

        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSet.of<E?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:b"),
                    Label.parseCanonicalUnchecked("//label:c")
                )
            )

        assertThat(options.getStarlarkOptions())
            .containsExactly(
                Label.parseCanonicalUnchecked("//label:a"), FeatureFlagValue.SetValue.of("value"),
                Label.parseCanonicalUnchecked("//label:b"), FeatureFlagValue.DefaultValue.INSTANCE,
                Label.parseCanonicalUnchecked("//label:c"), FeatureFlagValue.UnknownValue.INSTANCE
            )
    }

    @Test
    @Throws(Exception::class)
    fun trimFlagValues_leavesNonFeatureFlagValuesAlone() {
        var options: BuildOptions =
            emptyBuildOptions().toBuilder()
                .addStarlarkOption(Label.parseCanonicalUnchecked("//unrelated/starlark:option"), true)
                .build()
        options =
            FeatureFlagValue.replaceFlagValues(
                options,
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    "value",
                    Label.parseCanonicalUnchecked("//label:d"),
                    "otherValue"
                )
            )
        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSet.of<E?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:b")
                )
            )

        options = FeatureFlagValue.trimFlagValues(options, ImmutableSet.of<E?>())

        assertThat(options.getStarlarkOptions())
            .containsEntry(Label.parseCanonicalUnchecked("//unrelated/starlark:option"), true)
    }

    @Test
    @Throws(Exception::class)
    fun trimFlagValues_overwritesRequestedNonFeatureFlagValueWithDefaultIfUntrimmed() {
        var options: BuildOptions =
            emptyBuildOptions().toBuilder()
                .addStarlarkOption(Label.parseCanonicalUnchecked("//unrelated/starlark:option"), true)
                .build()
        options =
            FeatureFlagValue.replaceFlagValues(
                options,
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    "value",
                    Label.parseCanonicalUnchecked("//label:d"),
                    "otherValue"
                )
            )
        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSet.of<E?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:b"),
                    Label.parseCanonicalUnchecked("//unrelated/starlark:option")
                )
            )

        assertThat(options.getStarlarkOptions())
            .containsEntry(
                Label.parseCanonicalUnchecked("//unrelated/starlark:option"),
                FeatureFlagValue.DefaultValue.INSTANCE
            )
    }

    @Test
    @Throws(Exception::class)
    fun trimFlagValues_overwritesRequestedNonFeatureFlagValueWithUnknownIfTrimmed() {
        var options: BuildOptions =
            emptyBuildOptions().toBuilder()
                .addStarlarkOption(Label.parseCanonicalUnchecked("//unrelated/starlark:option"), true)
                .build()
        options =
            FeatureFlagValue.replaceFlagValues(
                options,
                ImmutableMap.of<K?, V?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    "value",
                    Label.parseCanonicalUnchecked("//label:d"),
                    "otherValue"
                )
            )
        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSet.of<E?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:b")
                )
            )
        options =
            FeatureFlagValue.trimFlagValues(
                options,
                ImmutableSet.of<E?>(
                    Label.parseCanonicalUnchecked("//label:a"),
                    Label.parseCanonicalUnchecked("//label:b"),
                    Label.parseCanonicalUnchecked("//unrelated/starlark:option")
                )
            )

        assertThat(options.getStarlarkOptions())
            .containsEntry(
                Label.parseCanonicalUnchecked("//unrelated/starlark:option"),
                FeatureFlagValue.UnknownValue.INSTANCE
            )
    }
}
