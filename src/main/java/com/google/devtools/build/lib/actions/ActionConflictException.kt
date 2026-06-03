// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.cmdline.Label

/**
 * This exception is thrown when a conflict between actions is detected. It contains information
 * about the artifact for which the conflict is found, and data about the two conflicting actions
 * and their owners. Non-final only for [WithAspectKeyInfo].
 */
open class ActionConflictException private constructor(
    artifact: Artifact?,
    attemptedAction: ActionAnalysisMetadata?,
    message: String?,
    isPrefixConflict: Boolean
) : AbstractSaneAnalysisException(message) {
    private val artifact: Artifact?
    private val attemptedAction: ActionAnalysisMetadata?
    private val isPrefixConflict: Boolean

    init {
        this.artifact = artifact
        this.attemptedAction = attemptedAction
        this.isPrefixConflict = isPrefixConflict
    }

    fun getArtifact(): Artifact? {
        return artifact
    }

    fun getAttemptedAction(): ActionAnalysisMetadata? {
        return attemptedAction
    }

    fun reportTo(eventListener: EventHandler) {
        eventListener.handle(Event.error(this.getMessage()))
    }

    public override fun getDetailedExitCode(): DetailedExitCode {
        return DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(getMessage())
                .setAnalysis(
                    Analysis.newBuilder()
                        .setCode(
                            if (isPrefixConflict) Code.ARTIFACT_PREFIX_CONFLICT else Code.ACTION_CONFLICT
                        )
                )
                .build()
        )
    }

    open fun getAspectKey(): ActionLookupKey? {
        return null
    }

    /**
     * For skymeld.
     * 
     * 
     * We need to forward the AspectKey along so that it's available for the final conflict report.
     */
    private class WithAspectKeyInfo(e: ActionConflictException, aspectKey: ActionLookupKey?) :
        ActionConflictException(e.artifact, e.attemptedAction, e.getMessage(), e.isPrefixConflict) {
        private val aspectKey: ActionLookupKey?

        init {
            this.aspectKey = aspectKey
        }

        override fun getAspectKey(): ActionLookupKey? {
            return aspectKey
        }
    }

    companion object {
        private const val MAX_DIFF_ARTIFACTS_TO_REPORT = 5

        fun create(
            actionKeyContext: ActionKeyContext?,
            artifact: Artifact,
            previousAction: ActionAnalysisMetadata,
            attemptedAction: ActionAnalysisMetadata
        ): ActionConflictException {
            return ActionConflictException(
                artifact,
                attemptedAction,
                createDetailedMessage(
                    artifact,
                    actionKeyContext,
                    attemptedAction,
                    previousAction
                ),  /* isPrefixConflict= */
                false
            )
        }

        fun create(
            artifact: Artifact?,
            attemptedAction: ActionAnalysisMetadata?,
            message: String?,
            isPrefixConflict: Boolean
        ): ActionConflictException {
            return ActionConflictException(artifact, attemptedAction, message, isPrefixConflict)
        }

        /**
         * Exception to indicate that one [Action] has an output artifact whose path is a prefix of
         * an output of another action. Since the first path cannot be both a directory and a file, this
         * would lead to an error if both actions were executed in the same build.
         */
        fun createPrefix(
            firstArtifact: Artifact,
            secondArtifact: Artifact,
            firstAction: ActionAnalysisMetadata,
            secondAction: ActionAnalysisMetadata
        ): ActionConflictException {
            return ActionConflictException(
                firstArtifact,
                firstAction,
                createPrefixDetailedMessage(
                    firstArtifact,
                    secondArtifact,
                    firstAction.getOwner().getLabel(),
                    secondAction.getOwner().getLabel()
                ),  /* isPrefixConflict= */
                true
            )
        }

        fun withAspectKeyInfo(
            e: ActionConflictException, aspectKey: ActionLookupKey?
        ): ActionConflictException {
            return WithAspectKeyInfo(e, aspectKey)
        }

        private fun createDetailedMessage(
            artifact: Artifact,
            actionKeyContext: ActionKeyContext?,
            a: ActionAnalysisMetadata,
            b: ActionAnalysisMetadata
        ): String {
            return ("file '"
                    + artifact.prettyPrint()
                    + "' is generated by these conflicting actions:\n"
                    + debugSuffix(actionKeyContext, a, b))
        }

        private fun createPrefixDetailedMessage(
            firstArtifact: Artifact, secondArtifact: Artifact, firstOwner: Label?, secondOwner: Label?
        ): String? {
            return String.format(
                ("One of the output paths '%s' (belonging to %s) and '%s' (belonging to %s) is a"
                        + " prefix of the other. These actions cannot be simultaneously present; please"
                        + " rename one of the output files or build just one of them"),
                firstArtifact.getExecPath(), firstOwner, secondArtifact.getExecPath(), secondOwner
            )
        }

        private fun addStringDetail(sb: java.lang.StringBuilder, key: String?, valueA: String?, valueB: String?) {
            var valueA = valueA
            var valueB = valueB
            valueA = if (valueA != null) valueA else "(null)"
            valueB = if (valueB != null) valueB else "(null)"

            sb.append(key).append(": ").append(valueA)
            if (valueA != valueB) {
                sb.append(", ").append(valueB)
            }
            sb.append("\n")
        }

        /** Appends a line diff for large string values e.g. describeKey which can be 50k chars long.  */
        private fun describeKeyDiff(
            sb: java.lang.StringBuilder, metadataA: ActionExecutionMetadata, metadataB: ActionExecutionMetadata
        ) {
            sb.append("Action describeKey: ")

            var valueA: String? = metadataA.describeKey()
            var valueB: String? = metadataB.describeKey()
            valueA = if (valueA != null) valueA else "(null)"
            valueB = if (valueB != null) valueB else "(null)"

            // Do not print the values when they are identical.
            if (valueA == valueB) {
                sb.append("are equal\n")
                return
            }

            sb.append("are different:\n")
            val linesA: MutableList<String> =
                com.google.common.base.Splitter.on('\n').splitToList(valueA.trim { it <= ' ' })
            val linesB: MutableList<String> =
                com.google.common.base.Splitter.on('\n').splitToList(valueB.trim { it <= ' ' })

            val maxLen: Int = max(linesA.size, linesB.size)

            for (i in 0..<maxLen) {
                val lineA = if (i < linesA.size) linesA.get(i) else "(null)"
                val lineB: String? = if (i < linesB.size) linesB.get(i) else "(null)"
                if (lineA != lineB) {
                    sb.append("  Action A: ").append(lineA).append("\n")
                    sb.append("  Action B: ").append(lineB).append("\n")
                }
            }
        }

        private fun addListDetail(
            sb: java.lang.StringBuilder, key: String?, valueA: Iterable<Artifact>, valueB: Iterable<Artifact>
        ) {
            val diffA: MutableSet<Artifact?> = differenceWithoutOwner(valueA, valueB)
            val diffB: MutableSet<Artifact?> = differenceWithoutOwner(valueB, valueA)

            sb.append(key).append(": ")
            if (diffA.isEmpty() && diffB.isEmpty()) {
                sb.append("are equal\n")
            } else {
                if (!diffA.isEmpty()) {
                    sb.append(
                        ("Attempted action contains artifacts not in previous action (first "
                                + MAX_DIFF_ARTIFACTS_TO_REPORT
                                + "): \n")
                    )
                    prettyPrintArtifactDiffs(sb, diffA)
                }

                if (!diffB.isEmpty()) {
                    sb.append(
                        ("Previous action contains artifacts not in attempted action (first "
                                + MAX_DIFF_ARTIFACTS_TO_REPORT
                                + "): \n")
                    )
                    prettyPrintArtifactDiffs(sb, diffB)
                }
            }
        }

        /** Returns items in `valueA` that are not in `valueB`, ignoring the owner.  */
        private fun differenceWithoutOwner(
            valueA: Iterable<Artifact>, valueB: Iterable<Artifact>
        ): MutableSet<Artifact?> {
            val diff: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.Builder<Artifact?>()

            // Group valueB by exec path for easier checks.
            val mapB: com.google.common.collect.ImmutableListMultimap<String?, Artifact?> =
                com.google.common.collect.Streams.stream<Artifact?>(valueB)
                    .collect(
                        com.google.common.collect.ImmutableListMultimap.toImmutableListMultimap<Artifact?, String?, Artifact?>(
                            java.util.function.Function { obj: Artifact? -> obj.getExecPathString() },
                            com.google.common.base.Functions.identity<Artifact?>()
                        )
                    )
            for (a in valueA) {
                var found = false
                for (b in mapB.get(a.getExecPathString())) {
                    if (a.equalsWithoutOwner(b)) {
                        found = true
                        break
                    }
                }
                if (!found) {
                    diff.add(a)
                }
            }

            return diff.build()
        }

        /** Pretty print action diffs (at most `MAX_DIFF_ARTIFACTS_TO_REPORT` lines).  */
        private fun prettyPrintArtifactDiffs(sb: java.lang.StringBuilder, diff: MutableSet<Artifact?>) {
            for (artifact in com.google.common.collect.Iterables.limit<Artifact>(diff, MAX_DIFF_ARTIFACTS_TO_REPORT)) {
                sb.append('\t').append(artifact.prettyPrint()).append('\n')
            }
        }

        // See also Actions.canBeShared()
        private fun debugSuffix(
            actionKeyContext: ActionKeyContext?, a: ActionAnalysisMetadata, b: ActionAnalysisMetadata
        ): String {
            // Note: the error message reveals to users the names of intermediate files that are not
            // documented in the BUILD language.  This error-reporting logic is rather elaborate but it
            // does help to diagnose some tricky situations.
            val sb: java.lang.StringBuilder = java.lang.StringBuilder()
            val aOwner: ActionOwner? = a.getOwner()
            val bOwner: ActionOwner? = b.getOwner()
            val aNull = aOwner == null
            val bNull = bOwner == null

            addStringDetail(
                sb,
                "Label",
                if (aNull) null else Label.print(aOwner.getLabel()),
                if (bNull) null else Label.print(bOwner.getLabel())
            )
            if ((!aNull && !aOwner.getAspectDescriptors().isEmpty())
                || (!bNull && !bOwner.getAspectDescriptors().isEmpty())
            ) {
                addStringDetail(sb, "Aspects", aspectDescriptor(aOwner), aspectDescriptor(bOwner))
            }
            addStringDetail(
                sb,
                "RuleClass",
                if (aNull) null else aOwner.getTargetKind(),
                if (bNull) null else bOwner.getTargetKind()
            )
            addStringDetail(
                sb,
                "JavaActionClass",
                if (aNull) null else a.javaClass.toString(),
                if (bNull) null else b.javaClass.toString()
            )
            addStringDetail(
                sb,
                "Configuration",
                if (aNull) null else aOwner.getConfigurationChecksum(),
                if (bNull) null else bOwner.getConfigurationChecksum()
            )
            addStringDetail(sb, "Mnemonic", a.getMnemonic(), b.getMnemonic())
            addStringDetail(
                sb, "IsShareable", a.isShareable().toString(), b.isShareable().toString()
            )
            try {
                addStringDetail(
                    sb,
                    "Action key",
                    a.getKey(actionKeyContext,  /* inputMetadataProvider= */null),
                    b.getKey(actionKeyContext,  /* inputMetadataProvider= */null)
                )
            } catch (e: java.lang.InterruptedException) {
                // Only for debugging - skip the key and carry on.
                addStringDetail(sb, "Action key", "<elided due to interrupt>", "<elided due to interrupt>")
                java.lang.Thread.currentThread().interrupt()
            }

            if ((a is ActionExecutionMetadata)
                && (b is ActionExecutionMetadata)
            ) {
                addStringDetail(
                    sb, "Progress message", a.getProgressMessage(), b.getProgressMessage()
                )
                describeKeyDiff(sb, a, b)
            }

            val aPrimaryInput: Artifact? = a.getPrimaryInput()
            val bPrimaryInput: Artifact? = b.getPrimaryInput()
            addStringDetail(
                sb,
                "PrimaryInput",
                if (aPrimaryInput == null) null else aPrimaryInput.toString(),
                if (bPrimaryInput == null) null else bPrimaryInput.toString()
            )
            addStringDetail(
                sb, "PrimaryOutput", a.getPrimaryOutput().toString(), b.getPrimaryOutput().toString()
            )

            // Only add list details if the primary input of A matches the input of B. Otherwise
            // the above information is enough and list diff detail is not needed.
            if ((aPrimaryInput == null && bPrimaryInput == null)
                || (aPrimaryInput != null && bPrimaryInput != null && aPrimaryInput.toString() == bPrimaryInput.toString())
            ) {
                val aPrimaryOutput: Artifact = a.getPrimaryOutput()
                val bPrimaryOutput: Artifact = b.getPrimaryOutput()
                if (!aPrimaryOutput.equalsWithoutOwner(bPrimaryOutput)) {
                    sb.append("Primary outputs are different: ")
                        .append(java.lang.System.identityHashCode(aPrimaryOutput))
                        .append(", ")
                        .append(java.lang.System.identityHashCode(bPrimaryOutput))
                        .append('\n')
                }
                val aArtifactOwner: ArtifactOwner = aPrimaryOutput.getArtifactOwner()
                val bArtifactOwner: ArtifactOwner = bPrimaryOutput.getArtifactOwner()
                addStringDetail(
                    sb, "Owner information", aArtifactOwner.toString(), bArtifactOwner.toString()
                )
                addListDetail(
                    sb, "MandatoryInputs", a.getMandatoryInputs().toList(), b.getMandatoryInputs().toList()
                )
                addListDetail(sb, "Outputs", a.getOutputs(), b.getOutputs())
            }

            return sb.toString()
        }

        private fun aspectDescriptor(owner: ActionOwner?): String? {
            return if (owner == null)
                null
            else
                owner.getAspectDescriptors().stream()
                    .map<Any?>(AspectDescriptor::getDescription)
                    .collect(Collectors.joining(",", "[", "]"))
        }
    }
}
