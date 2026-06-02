// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.analysis.PlatformOptions

/** Tests for [TestTrimmingTransitionFactory.TestTrimmingTransition].  */
@RunWith(JUnit4::class)
class TestTrimmingTransitionTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(OptionsParsingException::class, java.lang.InterruptedException::class)
    fun removesTestOptionsWhenSet() {
        val options: BuildOptions? =
            BuildOptions.of(
                com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java, TestOptions::class.java),
                "--trim_test_configuration"
            )

        val result: BuildOptions =
            TRIM_TRANSITION.patch(
                BuildOptionsView(options, TRIM_TRANSITION.requiresOptionFragments()),
                StoredEventHandler()
            )

        // Verify the transitions actually applied.
        assertThat(result).isNotNull()
        assertThat(result).isNotEqualTo(options)
        assertThat(result.contains(TestOptions::class.java)).isFalse()
    }

    @get:Throws(OptionsParsingException::class, java.lang.InterruptedException::class)
    @get:org.junit.Test
    val isNOPWhenUnset: Unit
        get() {
            val options: BuildOptions? =
                BuildOptions.of(
                    com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java, TestOptions::class.java),
                    "--notrim_test_configuration"
                )

            val result: BuildOptions? =
                TRIM_TRANSITION.patch(
                    BuildOptionsView(
                        options,
                        TRIM_TRANSITION.requiresOptionFragments()
                    ),
                    StoredEventHandler()
                )

            // Verify the transitions actually applied.
            assertThat(result).isNotNull()
            assertThat(result).isEqualTo(options)
        }

    @org.junit.Test
    @Throws(OptionsParsingException::class, java.lang.InterruptedException::class)
    fun retainsStarlarkOptions() {
        val starlarkOptionKey: Label? = Label.parseCanonicalUnchecked("//options:foo")
        val starlarkOptionValue = "bar"

        val options: BuildOptions? =
            BuildOptions.of(
                com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java, TestOptions::class.java),
                "--trim_test_configuration"
            )
                .toBuilder()
                .addStarlarkOption(starlarkOptionKey, starlarkOptionValue)
                .build()

        val result: BuildOptions =
            TRIM_TRANSITION.patch(
                BuildOptionsView(options, TRIM_TRANSITION.requiresOptionFragments()),
                StoredEventHandler()
            )

        // Verify the transitions actually applied.
        assertThat(result).isNotNull()
        assertThat(result).isNotEqualTo(options)
        assertThat(result.getStarlarkOptions().get(starlarkOptionKey)).isEqualTo(starlarkOptionValue)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun composeCommutativelyWithExecutionTransition() {
        val executionPlatform: Label? = Label.parseCanonicalUnchecked("//platform:exec")

        val execTransition: PatchTransition =
            ExecutionTransitionFactory.createFactory()
                .create(
                    AttributeTransitionData.builder()
                        .attributes(FakeAttributeMapper.empty())
                        .analysisData(
                            getSkyframeExecutor()
                                .getStarlarkExecTransition(targetConfig.getOptions(), reporter)
                        )
                        .executionPlatform(executionPlatform)
                        .build()
                )
        assertThat(execTransition).isNotNull()

        // Apply the transition.
        val options: BuildOptions? =
            BuildOptions.of(
                targetConfig.getOptions().getFragmentClasses(),
                "--platforms=//platform:target",
                "--trim_test_configuration"
            )

        val handler: com.google.devtools.build.lib.events.EventHandler = StoredEventHandler()

        val execTransitionOptions: BuildOptions? =
            execTransition.patch(
                BuildOptionsView(options, execTransition.requiresOptionFragments()), handler
            )
        val execThenTrim: BuildOptions =
            TRIM_TRANSITION.patch(
                BuildOptionsView(execTransitionOptions, TRIM_TRANSITION.requiresOptionFragments()),
                handler
            )

        val trimTransitionOptions: BuildOptions? =
            TRIM_TRANSITION.patch(
                BuildOptionsView(options, TRIM_TRANSITION.requiresOptionFragments()), handler
            )
        val trimThenExec: BuildOptions? =
            execTransition.patch(
                BuildOptionsView(trimTransitionOptions, execTransition.requiresOptionFragments()),
                handler
            )

        assertThat(execThenTrim).isEqualTo(trimThenExec)

        // Verify the transitions actually applied.
        assertThat(execThenTrim).isNotNull()
        assertThat(execThenTrim).isNotEqualTo(options)

        assertThat(execThenTrim.get(PlatformOptions::class.java).getPlatforms())
            .containsExactly(executionPlatform)
        assertThat(execThenTrim.contains(TestOptions::class.java)).isFalse()
    }

    companion object {
        private val TRIM_TRANSITION: PatchTransition = TestTrimmingTransitionFactory.TestTrimmingTransition.INSTANCE
    }
}
