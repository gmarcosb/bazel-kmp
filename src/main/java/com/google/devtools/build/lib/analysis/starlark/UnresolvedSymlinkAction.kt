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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * Action to create a possibly unresolved symbolic link to a raw path.
 * 
 * 
 * To create a symlink to a known-to-exist target with alias semantics similar to a true copy of
 * the input, use [SymlinkAction] instead.
 */
class UnresolvedSymlinkAction private constructor(
    owner: ActionOwner?,
    primaryOutput: Artifact,
    private val target: String?,
    targetType: SymlinkTargetType,
    progressMessage: String?
) : AbstractAction(
    owner,
    NestedSetBuilder.emptySet(Order.STABLE_ORDER),
    com.google.common.collect.ImmutableSet.of<E?>(primaryOutput)
) {
    private val targetType: SymlinkTargetType
    private val progressMessage: String?

    init {
        this.targetType = targetType
        this.progressMessage = progressMessage
    }

    @Throws(ActionExecutionException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        val outputPath: Path = actionExecutionContext.getInputPath(getPrimaryOutput())
        try {
            outputPath.createSymbolicLink(getTargetPathFragment(), targetType)
        } catch (e: IOException) {
            val message: String? =
                java.lang.String.format(
                    "failed to create symbolic link '%s' with target '%s' due to I/O error: %s",
                    getPrimaryOutput().getExecPathString(), target, e.getMessage()
                )
            val code: DetailedExitCode = createDetailedExitCode(message, Code.LINK_CREATION_IO_EXCEPTION)
            throw ActionExecutionException(message, e, this, false, code)
        }

        return ActionResult.EMPTY
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        fp.addString(target)
        fp.addString(targetType.name())
    }

    public override fun describeKey(): String? {
        return java.lang.String.format("GUID: %s\ntarget: %s\ntype: %s\n", GUID, target, targetType.name())
    }

    public override fun getMnemonic(): String {
        return "UnresolvedSymlink"
    }

    protected override fun getRawProgressMessage(): String? {
        return progressMessage
    }

    fun getTarget(): String {
        return getTargetPathFragment().getPathString()
    }

    private fun getTargetPathFragment(): PathFragment {
        // TODO: PathFragment#create normalizes the symlink target, which may change how it resolves
        //  when combined with directory symlinks. Ideally, Bazel's file system abstraction would
        //  offer a way to create symlinks without any preprocessing of the target.
        return PathFragment.create(target)
    }

    companion object {
        private const val GUID = "0f302651-602c-404b-881c-58913193cfe7"

        fun create(
            owner: ActionOwner?,
            primaryOutput: Artifact,
            target: String?,
            targetType: SymlinkTargetType,
            progressMessage: String?
        ): UnresolvedSymlinkAction {
            com.google.common.base.Preconditions.checkArgument(primaryOutput.isSymlink())
            return UnresolvedSymlinkAction(owner, primaryOutput, target, targetType, progressMessage)
        }

        private fun createDetailedExitCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setSymlinkAction(FailureDetails.SymlinkAction.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
