// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction.name

/** Flags specific to test summary reporting.  */
@com.google.devtools.common.options.OptionsClass
abstract class TestSummaryOptions : com.google.devtools.common.options.OptionsBase() {
    @get:com.google.devtools.common.options.Option(
        name = "verbose_test_summary",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("If true, print additional information (timing, number of failed runs, etc) in the"
                + " test summary.")
    )
    abstract var verboseSummary: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "test_verbose_timeout_warnings",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("If true, print additional warnings when the actual test execution time does not "
                + "match the timeout defined by the test (whether implied or explicit).")
    )
    abstract val testVerboseTimeoutWarnings: Boolean

    @get:com.google.devtools.common.options.Option(
        name = "print_relative_test_log_paths",
        defaultValue = "false",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
        help = ("If true, when printing the path to a test log, use relative path that makes use of "
                + "the 'testlogs' convenience symlink. N.B. - A subsequent 'build'/'test'/etc "
                + "invocation with a different configuration can cause the target of this symlink "
                + "to change, making the path printed previously no longer useful.")
    )
    abstract val printRelativeTestLogPaths: Boolean

    companion object {
        val DEFAULTS: TestSummaryOptions? =
            com.google.devtools.common.options.Options.getDefaults<TestSummaryOptions?>(TestSummaryOptions::class.java)
    }
}
