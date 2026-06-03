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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * An action that is inserted into the build graph only to provide info
 * about rules to extra_actions.
 */
class PseudoAction<InfoType : MessageLite?>(
    uuid: UUID?,
    owner: ActionOwner?,
    inputs: NestedSet<Artifact?>?,
    outputs: MutableCollection<Artifact?>?,
    mnemonic: String?,
    infoExtension: Extension<ExtraActionInfo?, InfoType?>?,
    info: InfoType?
) : AbstractAction(owner, inputs, outputs) {
    @VisibleForSerialization
    protected val uuid: UUID?
    private val mnemonic: String?

    @VisibleForSerialization
    protected val infoExtension: Extension<ExtraActionInfo?, InfoType?>?

    private val info: InfoType?

    init {
        this.uuid = uuid
        this.mnemonic = mnemonic
        this.infoExtension = infoExtension
        this.info = info
    }

    @Throws(ActionExecutionException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult? {
        val message = mnemonic + "ExtraAction should not be executed."
        val detailedCode: DetailedExitCode? =
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setExecution(
                        Execution.newBuilder().setCode(Code.PSEUDO_ACTION_EXECUTION_PROHIBITED)
                    )
                    .build()
            )
        throw ActionExecutionException(message, this, false, detailedCode)
    }

    public override fun getMnemonic(): String? {
        return mnemonic
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addUUID(uuid)
        fp.addBytes(getInfo().toByteArray())
    }

    protected fun getInfo(): InfoType? {
        return this.info
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun getExtraActionInfo(actionKeyContext: ActionKeyContext?): ExtraActionInfo.Builder {
        return super.getExtraActionInfo(actionKeyContext).setExtension(infoExtension, getInfo())
    }

    companion object {
        fun getDummyOutput(ruleContext: RuleContext): Artifact? {
            return ruleContext.getPackageRelativeArtifact(
                ruleContext.getLabel().getName() + ".extra_action_dummy",
                ruleContext.getGenfilesDirectory()
            )
        }
    }
}
