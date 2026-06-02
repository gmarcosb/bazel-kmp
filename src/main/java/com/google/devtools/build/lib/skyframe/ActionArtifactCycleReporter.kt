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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionLookupData

/**
 * Reports cycles between Actions and Artifacts. These indicates cycles within a rule.
 */
class ActionArtifactCycleReporter internal constructor(packageProvider: PackageProvider?) :
    AbstractLabelCycleReporter(packageProvider) {
    override fun prettyPrint(untypedKey: Any?): String {
        val key: SkyKey = untypedKey as SkyKey
        return prettyPrint(key.functionName(), key.argument())
    }

    override fun shouldSkipOnPathToCycle(key: SkyKey): Boolean {
        // BuildDriverKeys don't provide any relevant info for the end user.
        return SkyFunctions.BUILD_DRIVER == key.functionName() // ArtifactNestedSetKeys are just an implementation detail.
                || SkyFunctions.ARTIFACT_NESTED_SET == key.functionName()
    }

    override fun getLabel(key: SkyKey): Label? {
        val arg: Any? = key.argument()
        if (arg is Artifact) {
            return arg.getOwner()
        } else if (arg is ActionLookupData) {
            return arg.getLabel()
        } else if (arg is TopLevelActionLookupKeyWrapper) {
            return arg.actionLookupKey().getLabel()
        } else if (arg is TestCompletionKey
            && key.functionName() == SkyFunctions.TEST_COMPLETION
        ) {
            return (arg as TestCompletionKey).configuredTargetKey().getLabel()
        }
        throw java.lang.IllegalStateException(
            "Argument is not Action, TargetCompletion, AspectCompletion, or TestCompletion: " + arg
        )
    }

    override fun canReportCycle(topLevelKey: SkyKey?, cycleInfo: CycleInfo): Boolean {
        return ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP.test(topLevelKey)
                && cycleInfo.getCycle().stream().allMatch(ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP)
    }

    override fun shouldSkipIntermediateKeyOnCycle(key: SkyKey): Boolean {
        // ArtifactNestedSetKey isn't worth reporting to the user - it is just an optimization, and will
        // always be an intermediate member of a cycle. It may contain artifacts irrelevant to the
        // cycle, and may be nested several layers deep.
        return SkyFunctions.ARTIFACT_NESTED_SET == key.functionName()
    }

    companion object {
        @kotlin.jvm.JvmField
        val ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP: java.util.function.Predicate<SkyKey?> =
            com.google.common.base.Predicates.or<SkyKey?>(
                SkyFunctions.isSkyFunction(Artifact.ARTIFACT),
                SkyFunctions.isSkyFunction(SkyFunctions.ARTIFACT_NESTED_SET),
                SkyFunctions.isSkyFunction(SkyFunctions.ACTION_EXECUTION),
                SkyFunctions.isSkyFunction(SkyFunctions.TARGET_COMPLETION),
                SkyFunctions.isSkyFunction(SkyFunctions.ASPECT_COMPLETION),
                SkyFunctions.isSkyFunction(SkyFunctions.TEST_COMPLETION),
                SkyFunctions.isSkyFunction(SkyFunctions.BUILD_DRIVER)
            )

        /**
         * Should be kept consistent with [.ACTION_OR_ARTIFACT_OR_TRANSITIVE_RDEP] and [ ][.shouldSkipOnPathToCycle]
         */
        private fun prettyPrint(skyFunctionName: SkyFunctionName, arg: Any?): String {
            if (arg is Artifact) {
                return prettyPrintArtifact(arg)
            } else if (arg is ActionLookupData) {
                return "action from: " + arg
            } else if (arg is TopLevelActionLookupKeyWrapper) {
                if (skyFunctionName == SkyFunctions.TARGET_COMPLETION) {
                    return "configured target: " + arg.actionLookupKey().getLabel()
                }
                return ("top-level aspect: "
                        + (arg as AspectCompletionKey).actionLookupKey().prettyPrint())
            } else if (arg is TestCompletionKey
                && skyFunctionName == SkyFunctions.TEST_COMPLETION
            ) {
                return "test target: " + (arg as TestCompletionKey).configuredTargetKey().getLabel()
            }
            throw java.lang.IllegalStateException(
                "Argument is not Action, TargetCompletion, AspectCompletion, or TestCompletion: " + arg
            )
        }

        private fun prettyPrintArtifact(artifact: Artifact): String {
            return "file: " + artifact.getRootRelativePathString()
        }
    }
}
