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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/** Utility class to validate results of executing Starlark rules and aspects.  */
object StarlarkProviderValidationUtil {
    @Throws(net.starlark.java.eval.EvalException::class)
    fun validateArtifacts(ruleContext: RuleContext) {
        val treeArtifactsConflictingWithFiles: com.google.common.collect.ImmutableSet<Artifact?> =
            ruleContext.getAnalysisEnvironment().getTreeArtifactsConflictingWithFiles()
        if (!treeArtifactsConflictingWithFiles.isEmpty()) {
            throw Starlark.errorf(
                "The following directories were also declared as files:\n%s",
                artifactsDescription(treeArtifactsConflictingWithFiles)
            )
        }

        val orphanArtifacts: com.google.common.collect.ImmutableSet<Artifact?> =
            ruleContext.getAnalysisEnvironment().getOrphanArtifacts()
        if (!orphanArtifacts.isEmpty()) {
            throw Starlark.errorf(
                "The following files have no generating action:\n%s",
                artifactsDescription(orphanArtifacts)
            )
        }
    }

    private fun artifactsDescription(artifacts: com.google.common.collect.ImmutableSet<Artifact?>): String {
        return com.google.common.base.Joiner.on("\n")
            .join(
                com.google.common.collect.Iterables.transform<Artifact?, Any?>(
                    artifacts,
                    Artifact::getRootRelativePathString
                )
            )
    }
}
