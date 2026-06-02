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

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.starlarkbuildapi.test.AnalysisFailureApi

/**
 * Encapsulates information about an analysis-phase error which would have occurred during a build.
 */
@AutoValue
abstract class AnalysisFailure internal constructor() // Should not be extended.
    : AnalysisFailureApi {
    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<AnalyisFailure object>")
    }

    companion object {
        fun create(label: com.google.devtools.build.lib.cmdline.Label?, message: String?): AnalysisFailure {
            return AutoValue_AnalysisFailure(label, message)
        }
    }
}
