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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.packages.TestSize
import com.google.devtools.build.lib.packages.TestSize.TestSizeFilterConverter
import com.google.devtools.build.lib.packages.TestTimeout
import com.google.devtools.build.lib.packages.TestTimeout.TestTimeoutFilterConverter

/** Options that affect how command-line target patterns are resolved to individual targets.  */
@com.google.devtools.common.options.OptionsClass
abstract class LoadingOptions : com.google.devtools.common.options.OptionsBase() {
    @com.google.devtools.common.options.Option(
        name = "build_tests_only",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("If specified, only *_test and test_suite rules will be built and other targets specified"
                + " on the command line will be ignored. By default everything that was requested"
                + " will be built.")
    )
    abstract fun getBuildTestsOnly(): Boolean

    @com.google.devtools.common.options.Option(
        name = "compile_one_dependency",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Compile a single dependency of the argument files. This is useful for syntax checking"
                + " source files in IDEs, for example, by rebuilding a single target that depends on"
                + " the source file to detect errors as early as possible in the edit/build/test"
                + " cycle. This argument affects the way all non-flag arguments are interpreted;"
                + " instead of being targets to build they are source filenames.  For each source"
                + " filename an arbitrary target that depends on it will be built.")
    )
    abstract fun getCompileOneDependency(): Boolean

    @com.google.devtools.common.options.Option(
        name = "build_tag_filters",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Specifies a comma-separated list of tags. Each tag can be optionally preceded with '-'"
                + " to specify excluded tags. Only those targets will be built that contain at least"
                + " one included tag and do not contain any excluded tags. This option does not"
                + " affect the set of tests executed with the 'test' command; those are be governed"
                + " by the test filtering options, for example '--test_tag_filters'")
    )
    abstract fun getBuildTagFilterList(): MutableList<String?>?

    @com.google.devtools.common.options.Option(
        name = "test_tag_filters",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Specifies a comma-separated list of test tags. Each tag can be optionally "
                + "preceded with '-' to specify excluded tags. Only those test targets will be "
                + "found that contain at least one included tag and do not contain any excluded "
                + "tags. This option affects --build_tests_only behavior and the test command.")
    )
    abstract fun getTestTagFilterList(): MutableList<String?>?

    abstract fun setTestTagFilterList(value: MutableList<String?>?)

    @com.google.devtools.common.options.Option(
        name = "test_size_filters",
        converter = TestSizeFilterConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Specifies a comma-separated list of test sizes. Each size can be optionally "
                + "preceded with '-' to specify excluded sizes. Only those test targets will be "
                + "found that contain at least one included size and do not contain any excluded "
                + "sizes. This option affects --build_tests_only behavior and the test command.")
    )
    abstract fun getTestSizeFilterSet(): MutableSet<TestSize?>?

    abstract fun setTestSizeFilterSet(value: MutableSet<TestSize?>?)

    @com.google.devtools.common.options.Option(
        name = "test_timeout_filters",
        converter = TestTimeoutFilterConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Specifies a comma-separated list of test timeouts. Each timeout can be "
                + "optionally preceded with '-' to specify excluded timeouts. Only those test "
                + "targets will be found that contain at least one included timeout and do not "
                + "contain any excluded timeouts. This option affects --build_tests_only behavior "
                + "and the test command.")
    )
    abstract fun getTestTimeoutFilterSet(): MutableSet<TestTimeout?>?

    abstract fun setTestTimeoutFilterSet(value: MutableSet<TestTimeout?>?)

    @com.google.devtools.common.options.Option(
        name = "test_lang_filters",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Specifies a comma-separated list of test languages. Each language can be "
                + "optionally preceded with '-' to specify excluded languages. Only those "
                + "test targets will be found that are written in the specified languages. "
                + "The name used for each language should be the same as the language prefix in the "
                + "*_test rule, e.g. one of 'cc', 'java', 'py', etc. "
                + "This option affects --build_tests_only behavior and the test command.")
    )
    abstract fun getTestLangFilterList(): MutableList<String?>?

    abstract fun setTestLangFilterList(value: MutableList<String?>?)

    @com.google.devtools.common.options.Option(
        name = "build_manual_tests",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        help = ("Forces test targets tagged 'manual' to be built. 'manual' tests are excluded from "
                + "processing. This option forces them to be built (but not executed).")
    )
    abstract fun getBuildManualTests(): Boolean

    @Deprecated("")
    @com.google.devtools.common.options.Option(
        name = "experimental_skyframe_target_pattern_evaluator",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.DEPRECATED],
        help = ("Use the Skyframe-based target pattern evaluator; implies "
                + "--experimental_interleave_loading_and_analysis.")
    )
    abstract fun getUseSkyframeTargetPatternEvaluator(): Boolean

    @com.google.devtools.common.options.Option(
        name = "expand_test_suites",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("Expand test_suite targets into their constituent tests before analysis. When this flag"
                + " is turned on (the default), negative target patterns will apply to the tests"
                + " belonging to the test suite, otherwise they will not. Turning off this flag is"
                + " useful when top-level aspects are applied at command line: then they can analyze"
                + " test_suite targets.")
    )
    abstract fun getExpandTestSuites(): Boolean
}
