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
package com.google.devtools.build.lib.starlarkbuildapi.test

import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.docgen.annot.StarlarkConstructor
import com.google.devtools.build.lib.starlarkbuildapi.core.ProviderApi
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.StarlarkValue

/**
 * Encapsulates information about an analysis-phase error which would have occurred during a build.
 */
@StarlarkBuiltin(
    name = "AnalysisTestResultInfo",
    category = DocCategory.PROVIDER,
    doc = ("Encapsulates the result of analyis-phase testing. Build targets which return an instance"
            + " of this provider signal to the build system that it should generate a 'stub' test"
            + " executable which generates the equivalent test result. Analysis test rules (rules"
            + " created with <code>analysis_test=True</code> <b>must</b> return an instance of"
            + " this provider, and non-analysis-phase test rules <b>cannot</b> return this "
            + "provider.")
)
interface AnalysisTestResultInfoApi : StarlarkValue {
    @get:StarlarkMethod(
        name = "success", doc = ("If true, then the analysis-phase test represented by this target passed. If "
                + "false, the test failed."), structField = true
    )
    val success: Boolean?

    @get:StarlarkMethod(
        name = "message",
        doc = "A descriptive message containing information about the test and its success/failure.",
        structField = true
    )
    val message: String?

    /** Provider class for [AnalysisTestResultInfoApi] objects.  */
    @StarlarkBuiltin(name = "Provider", documented = false, doc = "")
    interface AnalysisTestResultInfoProviderApi : ProviderApi {
        @StarlarkMethod(
            name = "AnalysisTestResultInfo",
            doc = "The <code>AnalysisTestResultInfo</code> constructor.",
            parameters = [Param(
                name = "success",
                named = true,
                doc = "If true, then the analysis-phase test represented by this target should "
                        + "pass. If false, the test should fail."
            ), Param(
                name = "message",
                named = true,
                doc = "A descriptive message containing information about the test and its "
                        + "success/failure."
            )],
            selfCall = true
        )
        @StarlarkConstructor
        fun testResultInfo(success: Boolean?, message: String?): AnalysisTestResultInfoApi?
    }
}
