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

/**
 * Writes a manifest of instrumented source and metadata files.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
internal class InstrumentedFileManifestAction @com.google.common.annotations.VisibleForTesting constructor(
    owner: ActionOwner?,
    files: NestedSet<Artifact?>,
    output: Artifact?
) : AbstractFileWriteAction(owner,  /* inputs= */NestedSetBuilder.emptySet(Order.STABLE_ORDER), output) {
    private val files: NestedSet<Artifact?>

    init {
        this.files = files
    }

    override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter {
        return DeterministicWriter { out ->
            // Sort the exec paths before writing them out.
            val fileNames: Array<String?> =
                files.toList().stream().map(Artifact::getExecPathString).toArray({ _Dummy_.__Array__() })
            java.util.Arrays.sort(fileNames)
            OutputStreamWriter(out, java.nio.charset.StandardCharsets.ISO_8859_1).use { writer ->
                for (name in fileNames) {
                    writer.write(name)
                    writer.write('\n'.code)
                }
            }
        }
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        // TODO(b/150305897): use addUUID?
        fp.addString(GUID)
        // TODO(b/150308417): Not sorting is probably cheaper, might lead to unnecessary re-execution.
        Artifacts.addToFingerprint(fp, files.toList())
    }

    companion object {
        private const val GUID = "3833f0a3-7ea1-4d9f-b96f-66eff4c922b0"

        /**
         * Instantiates instrumented file manifest for the given target.
         * 
         * @param ruleContext context of the executable configured target
         * @param additionalSourceFiles additional instrumented source files, as
         * collected by the [InstrumentedFilesCollector]
         * @param metadataFiles *.gcno/ *.em files collected by the [InstrumentedFilesCollector]
         * @return instrumented file manifest artifact
         */
        fun getInstrumentedFileManifest(
            ruleContext: RuleContext,
            additionalSourceFiles: NestedSet<Artifact?>?, metadataFiles: NestedSet<Artifact?>?
        ): Artifact? {
            val instrumentedFileManifest: Artifact? = ruleContext.getBinArtifact(
                ruleContext.getTarget().getName() + ".instrumented_files"
            )

            val inputs: NestedSet<Artifact?> = NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(additionalSourceFiles)
                .addTransitive(metadataFiles)
                .build()
            ruleContext.registerAction(
                InstrumentedFileManifestAction(
                    ruleContext.getActionOwner(), inputs, instrumentedFileManifest
                )
            )

            return instrumentedFileManifest
        }
    }
}
