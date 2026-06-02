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
package com.google.devtools.build.lib.analysis.extra

import com.google.devtools.build.lib.actions.Action

/**
 * Requests extra action info from shadowed action and writes it, in protocol buffer format, to an
 * .xa file for use by an extra action. This can only be done at execution time because actions may
 * store information only known at execution time into the protocol buffer.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable // if shadowedAction is immutable
class ExtraActionInfoFileWriteAction internal constructor(
    owner: ActionOwner?,
    primaryOutput: Artifact?,
    shadowedAction: Action
) : AbstractFileWriteAction(
    owner,
    if (shadowedAction.discoversInputs())
        NestedSetBuilder.stableOrder<Artifact?>().addAll(shadowedAction.getOutputs()).build()
    else
        NestedSetBuilder.emptySet<Artifact?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER),
    primaryOutput
) {
    private val shadowedAction: Action

    init {
        this.shadowedAction = com.google.common.base.Preconditions.checkNotNull<Action>(shadowedAction, primaryOutput)
    }

    @Throws(ExecException::class, java.lang.InterruptedException::class)
    public override fun newDeterministicWriter(ctx: ActionExecutionContext): DeterministicWriter {
        try {
            return ProtoDeterministicWriter(
                shadowedAction.getExtraActionInfo(ctx.getActionKeyContext()).build()
            )
        } catch (e: CommandLineExpansionException) {
            throw UserExecException(
                e,
                FailureDetail.newBuilder()
                    .setMessage(com.google.common.base.Strings.nullToEmpty(e.getMessage()))
                    .setSpawn(Spawn.newBuilder().setCode(Code.COMMAND_LINE_EXPANSION_FAILURE))
                    .build()
            )
        }
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(UUID)
        fp.addString(shadowedAction.getKey(actionKeyContext, inputMetadataProvider))
        fp.addBytes(shadowedAction.getExtraActionInfo(actionKeyContext).build().toByteArray())
    }

    companion object {
        private const val UUID = "1759f81d-e72e-477d-b182-c4532bdbaeeb"
    }
}
