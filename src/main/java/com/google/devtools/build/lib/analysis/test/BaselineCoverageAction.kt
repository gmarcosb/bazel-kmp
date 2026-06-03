// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionExecutionContext

/** Generates baseline (empty) coverage for the given non-test target.  */
@com.google.common.annotations.VisibleForTesting
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class BaselineCoverageAction private constructor(
    owner: ActionOwner?,
    instrumentedFiles: NestedSet<Artifact?>,
    primaryOutput: Artifact?
) : AbstractFileWriteAction(owner, NestedSetBuilder.emptySet(Order.STABLE_ORDER), primaryOutput) {
    private val instrumentedFiles: NestedSet<Artifact?>

    init {
        this.instrumentedFiles = instrumentedFiles
    }

    @com.google.common.annotations.VisibleForTesting
    fun getInstrumentedFilesForTesting(): NestedSet<Artifact?> {
        return instrumentedFiles
    }

    override fun getMnemonic(): String {
        return "BaselineCoverage"
    }

    public override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint?
    ) {
        // TODO(b/150305897): No UUID?
        // TODO(b/150308417): Sort?
        Artifacts.addToFingerprint(fp, instrumentedFiles.toList())
    }

    override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter {
        return DeterministicWriter { out ->
            val writer: PrintWriter = PrintWriter(out)
            for (file in instrumentedFiles.toList()) {
                writer.write("SF:" + file.getExecPathString() + "\n")
                writer.write("end_of_record\n")
            }
            writer.flush()
        }
    }

    companion object {
        fun create(
            ruleContext: RuleContext, instrumentedFiles: NestedSet<Artifact?>
        ): BaselineCoverageAction {
            // Baseline coverage artifacts will still go into "testlogs" directory.
            val coverageData: Artifact? =
                ruleContext.getPackageRelativeArtifact(
                    PathFragment.create(ruleContext.getTarget().getName())
                        .getChild("baseline_coverage.dat"),
                    ruleContext.getTestLogsDirectory()
                )
            return BaselineCoverageAction(
                ruleContext.getActionOwner(), instrumentedFiles, coverageData
            )
        }
    }
}
