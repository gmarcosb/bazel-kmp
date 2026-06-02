// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** The coverage configuration fragment.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@RequiresOptions(options = [CoreOptions::class, CoverageOptions::class])
class CoverageConfiguration(buildOptions: BuildOptions) : Fragment(), CoverageConfigurationApi {
    /** Command-line options.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class CoverageOptions : FragmentOptions() {
        @get:com.google.devtools.common.options.Option(
            name = "coverage_output_generator",
            converter = LabelConverter::class,
            defaultValue = "@bazel_tools//tools/test:lcov_merger",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
            ],
            help = """
            Location of the binary that is used to postprocess raw coverage reports. This must
            be a binary target. Defaults to `@bazel_tools//tools/test:lcov_merger`.
            
            """.trimIndent()
        )
        abstract val coverageOutputGenerator: com.google.devtools.build.lib.cmdline.Label?

        @get:com.google.devtools.common.options.Option(
            name = "coverage_report_generator",
            converter = LabelConverter::class,
            defaultValue = "@bazel_tools//tools/test:coverage_report_generator",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
            ],
            help = """
            Location of the binary that is used to generate coverage reports. This must
            be a binary target. Defaults to `@bazel_tools//tools/test:coverage_report_generator`.
            
            """.trimIndent()
        )
        abstract val coverageReportGenerator: com.google.devtools.build.lib.cmdline.Label?
    }

    private val coverageOptions: CoverageOptions?

    init {
        if (!buildOptions.get(CoreOptions::class.java).getCollectCodeCoverage()) {
            this.coverageOptions = null
            return
        }
        this.coverageOptions = buildOptions.get(CoverageOptions::class.java)
    }

    @StarlarkConfigurationField(name = "output_generator", doc = "Label for the coverage output generator.")
    override fun outputGenerator(): com.google.devtools.build.lib.cmdline.Label? {
        if (coverageOptions == null) {
            return null
        }
        return coverageOptions.coverageOutputGenerator
    }

    fun reportGenerator(): com.google.devtools.build.lib.cmdline.Label? {
        if (coverageOptions == null) {
            return null
        }
        return coverageOptions.coverageReportGenerator
    }
}
