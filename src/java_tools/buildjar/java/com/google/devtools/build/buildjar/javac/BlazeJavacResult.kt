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
// limitations under the License.
package com.google.devtools.build.buildjar.javac

import com.google.devtools.build.buildjar.javac.BlazeJavacResult
import com.google.devtools.build.buildjar.javac.FormattedDiagnostic
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build

/** The result of a single compilation performed by [BlazeJavacMain].  */
class BlazeJavacResult private constructor(
    private val status: Status?,
    diagnostics: com.google.common.collect.ImmutableList<FormattedDiagnostic?>?,
    output: String?,
    statistics: BlazeJavacStatistics?
) {
    /** The compilation result.  */
    enum class Status {
        OK,
        ERROR,
        CRASH,
        REQUIRES_FALLBACK,
        CANCELLED,
    }

    private val diagnostics: com.google.common.collect.ImmutableList<FormattedDiagnostic?>?
    private val output: String?
    private val statistics: BlazeJavacStatistics?

    fun withStatistics(statistics: BlazeJavacStatistics?): BlazeJavacResult {
        return BlazeJavacResult(status, diagnostics, output, statistics)
    }

    init {
        this.diagnostics = diagnostics
        this.output = output
        this.statistics = statistics
    }

    val isOk: Boolean
        get() = status == com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.OK

    fun status(): Status? {
        return status
    }

    fun diagnostics(): com.google.common.collect.ImmutableList<FormattedDiagnostic?>? {
        return diagnostics
    }

    fun output(): String? {
        return output
    }

    fun statistics(): BlazeJavacStatistics? {
        return statistics
    }

    companion object {
        fun ok(): BlazeJavacResult {
            return createFullResult(
                com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.OK,
                com.google.common.collect.ImmutableList.of<FormattedDiagnostic?>(),
                "",
                BlazeJavacStatistics.empty()
            )
        }

        fun error(message: String?): BlazeJavacResult {
            return createFullResult(
                com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.ERROR,
                com.google.common.collect.ImmutableList.of<FormattedDiagnostic?>(),
                message,
                BlazeJavacStatistics.empty()
            )
        }

        fun cancelled(message: String?): BlazeJavacResult {
            return createFullResult(
                com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.CANCELLED,
                com.google.common.collect.ImmutableList.of<FormattedDiagnostic?>(),
                message,
                BlazeJavacStatistics.empty()
            )
        }

        fun fallback(): BlazeJavacResult {
            return createFullResult(
                com.google.devtools.build.buildjar.javac.BlazeJavacResult.Status.REQUIRES_FALLBACK,
                com.google.common.collect.ImmutableList.of<FormattedDiagnostic?>(),
                "",
                BlazeJavacStatistics.empty()
            )
        }

        fun createFullResult(
            status: Status?,
            diagnostics: com.google.common.collect.ImmutableList<FormattedDiagnostic?>?,
            output: String?,
            statistics: BlazeJavacStatistics?
        ): BlazeJavacResult {
            return BlazeJavacResult(status, diagnostics, output, statistics)
        }
    }
}
