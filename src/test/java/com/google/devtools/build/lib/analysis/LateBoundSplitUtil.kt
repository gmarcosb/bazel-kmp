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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/**
 * Rule and configuration class definitions for testing late-bound split attributes.
 */
object LateBoundSplitUtil {
    /**
     * A custom rule that requires [TestFragment].
     */
    val RULE_WITH_TEST_FRAGMENT: RuleDefinition = MockRule {
        MockRule.Companion.define(
            "rule_with_test_fragment",
            MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                builder.requiresConfigurationFragments(
                    TestFragment::class.java
                )
            })
    } as MockRule

    /** Returns the [TestOptions] from the given configuration.  */
    fun getOptions(config: BuildConfigurationValue): TestOptions {
        return config.getOptions().get(TestOptions::class.java)
    }

    /** A custom [FragmentOptions] with the option to be split.  */
    @OptionsClass
    abstract class TestOptions : FragmentOptions() {
        @get:com.google.devtools.common.options.Option(
            name = "foo",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = ""
        )
        abstract var fooFlag: String?
    }

    /** The [Fragment] that contains the options.  */
    @RequiresOptions(options = [TestOptions::class])
    class TestFragment(buildOptions: BuildOptions?) : Fragment() {
        private val buildOptions: BuildOptions?

        init {
            this.buildOptions = buildOptions
        }

        // Getter required to satisfy AutoCodec.
        fun getBuildOptions(): BuildOptions? {
            return buildOptions
        }
    }
}
