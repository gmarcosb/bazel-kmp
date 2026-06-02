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
package com.google.devtools.build.lib.rules.python


import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.Action
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization

/** A helper class for analyzing a Python configured target.  */
object PyCommon {
    /** Name of the version attribute.  */
    const val PYTHON_VERSION_ATTRIBUTE: String = "python_version"

    // Public so that Starlark bindings can access it. Should only be called by PyStarlarkBuiltins.
    // TODO(b/253059598): Remove support for this; https://github.com/bazelbuild/bazel/issues/16455
    fun registerPyExtraActionPseudoAction(
        ruleContext: RuleContext, dependencyTransitivePythonSources: NestedSet<Artifact?>
    ) {
        ruleContext.registerAction(
            makePyExtraActionPseudoAction(
                ruleContext.getActionOwner(),  // Has to be unfiltered sources as filtered will give an error for
                // unsupported file types where as certain tests only expect a warning.
                ruleContext.getPrerequisiteArtifacts("srcs")
                    .list(),  // We must not add the files declared in the srcs of this rule.;
                dependencyTransitivePythonSources,
                PseudoAction.getDummyOutput(ruleContext)
            )
        )
    }

    /**
     * Creates a [PseudoAction] that is only used for providing information to the blaze
     * extra_action feature.
     */
    private fun makePyExtraActionPseudoAction(
        owner: ActionOwner?,
        sources: MutableList<Artifact?>?,
        dependencies: NestedSet<Artifact?>,
        output: Artifact
    ): Action {
        val info: PythonInfo? =
            PythonInfo.newBuilder()
                .addAllSourceFile(Artifact.toExecPaths(sources))
                .addAllDepFile(Artifact.toExecPaths(dependencies.toList()))
                .build()

        return PyPseudoAction(
            owner,
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addAll(sources)
                .addTransitive(dependencies)
                .build(),
            ImmutableList.of<Artifact?>(output),
            "Python",
            PYTHON_INFO,
            info
        )
    }

    @SerializationConstant
    @VisibleForSerialization
    val PYTHON_INFO: GeneratedExtension<ExtraActionInfo?, PythonInfo?>? = PythonInfo.pythonInfo

    // Used purely to set the legacy ActionType of the ExtraActionInfo.
    @ThreadSafety.Immutable
    private class PyPseudoAction(
        owner: ActionOwner?,
        inputs: NestedSet<Artifact?>?,
        outputs: MutableCollection<Artifact?>?,
        mnemonic: String?,
        infoExtension: GeneratedExtension<ExtraActionInfo?, PythonInfo?>?,
        info: PythonInfo?
    ) : PseudoAction<PythonInfo?>(ACTION_UUID, owner, inputs, outputs, mnemonic, infoExtension, info) {
        companion object {
            private val ACTION_UUID: UUID = UUID.fromString("8d720129-bc1a-481f-8c4c-dbe11dcef319")
        }
    }
}
