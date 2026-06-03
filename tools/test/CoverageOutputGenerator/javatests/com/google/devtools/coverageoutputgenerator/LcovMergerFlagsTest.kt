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
package com.google.devtools.coverageoutputgenerator

import com.google.common.truth.Truth
import com.google.devtools.coverageoutputgenerator.LcovMergerFlags
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class LcovMergerFlagsTest {
    @org.junit.Test
    fun parseFlagsTestCoverageDirOutputFile() {
        val flags: LcovMergerFlags =
            LcovMergerFlags.Companion.parseFlags(
                arrayOf<String>(
                    "--coverage_dir=my_dir", "--output_file=my_file",
                )
            )
        Truth.assertThat(flags.coverageDir()).isEqualTo("my_dir")
        Truth.assertThat(flags.outputFile()).isEqualTo("my_file")
        Truth.assertThat(flags.reportsFile()).isNull()
        Truth.assertThat(flags.filterSources()).isEmpty()
    }

    @org.junit.Test
    fun parseFlagsTestReportsFileOutputFile() {
        val flags: LcovMergerFlags =
            LcovMergerFlags.Companion.parseFlags(
                arrayOf<String>(
                    "--reports_file=my_reports_file", "--output_file=my_file",
                )
            )
        Truth.assertThat(flags.reportsFile()).isEqualTo("my_reports_file")
        Truth.assertThat(flags.outputFile()).isEqualTo("my_file")
        Truth.assertThat(flags.coverageDir()).isNull()
        Truth.assertThat(flags.filterSources()).isEmpty()
    }

    @org.junit.Test
    fun parseFlagsTestReportsFileOutputFileFilterSources() {
        val flags: LcovMergerFlags =
            LcovMergerFlags.Companion.parseFlags(
                arrayOf<String>(
                    "--reports_file=my_reports_file",
                    "--output_file=my_file",
                    "--filter_sources=first_filter"
                )
            )
        Truth.assertThat(flags.reportsFile()).isEqualTo("my_reports_file")
        Truth.assertThat(flags.outputFile()).isEqualTo("my_file")
        Truth.assertThat(flags.coverageDir()).isNull()
        Truth.assertThat(flags.filterSources()).containsExactly("first_filter")
    }

    @org.junit.Test
    fun parseFlagsTestReportsFileOutputFileMultipleFilterSources() {
        val flags: LcovMergerFlags =
            LcovMergerFlags.Companion.parseFlags(
                arrayOf<String>(
                    "--reports_file=my_reports_file",
                    "--output_file=my_file",
                    "--filter_sources=first_filter",
                    "--filter_sources=second_filter"
                )
            )
        Truth.assertThat(flags.reportsFile()).isEqualTo("my_reports_file")
        Truth.assertThat(flags.outputFile()).isEqualTo("my_file")
        Truth.assertThat(flags.coverageDir()).isNull()
        Truth.assertThat(flags.filterSources()).containsExactly("first_filter", "second_filter")
    }

    @org.junit.Test
    fun parseFlagsTestCoverageDirAndReportsFile() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                LcovMergerFlags.Companion.parseFlags(
                    arrayOf<String>("--reports_file=my_reports_file", "--coverage_dir=my_coverage_dir")
                )
            })
    }

    @org.junit.Test
    fun parseFlagsTestEmptyFlags() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { LcovMergerFlags.Companion.parseFlags(arrayOf<String?>()) })
    }

    @org.junit.Test
    fun parseFlagsTestNoOutputFile() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                LcovMergerFlags.Companion.parseFlags(
                    arrayOf<String>(
                        "--reports_file=my_reports_file",
                    )
                )
            })
    }

    @org.junit.Test
    fun parseFlagsTestUnknownFlag() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                LcovMergerFlags.Companion.parseFlags(
                    arrayOf<String>(
                        "--fake_flag=my_reports_file",
                    )
                )
            })
    }

    @org.junit.Test
    fun parseFlagsTestInvalidFlagValue() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                LcovMergerFlags.Companion.parseFlags(
                    arrayOf<String>(
                        "--reports_file", "--output_file=my_file",
                    )
                )
            })
    }

    @org.junit.Test
    fun parseFlagsTestInvalidFlagValueWithoutDashes() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                LcovMergerFlags.Companion.parseFlags(
                    arrayOf<String>(
                        "reports_file", "--output_file=my_file",
                    )
                )
            })
    }
}
