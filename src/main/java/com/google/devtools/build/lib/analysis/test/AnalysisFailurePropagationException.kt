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

import com.google.devtools.build.lib.server.FailureDetails.Analysis

/**
 * An exception thrown when `--allow_analysis_failures=true` but information about some
 * analysis-phase failure could not be tracked and propagated using the normal mechanism (i.e. could
 * not be encapsulated by an AnalysisFailureInfo provided by a dummy ConfiguredTarget).
 * 
 * 
 * For instance, we do not allow one analysis failure test to transitively depend upon another
 * (see cl/220144957). In that case, we cannot create a ConfiguredTarget to propagate the failure to
 * create the inner test. Nor would we want to propagate it: the failure is a limitation of the
 * analysis testing machinery making the outer test unusable, not a failure in the rule(s) which the
 * outer test is testing.
 */
class AnalysisFailurePropagationException(
    label: com.google.devtools.build.lib.cmdline.Label?,
    causes: Iterable<String?>
) : AbstractSaneAnalysisException(
    String.format(
        "Error while collecting analysis-phase failure information for '%s': %s",
        label, com.google.common.base.Joiner.on("; ").join(causes)
    )
) {
    val detailedExitCode: DetailedExitCode
        get() = DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setAnalysis(Analysis.newBuilder().setCode(Code.ANALYSIS_FAILURE_PROPAGATION_FAILED))
                .build()
        )
}
