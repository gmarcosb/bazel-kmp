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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder

/**
 * FailAction is an Action that always fails to execute. (Used as scaffolding for rules we haven't
 * yet implemented. Also useful for testing.)
 */
@Immutable
class FailAction(owner: ActionOwner?, outputs: Iterable<Artifact?>?, errorMessage: String?, failActionCode: Code?) :
    com.google.devtools.build.lib.actions.AbstractAction(
        owner,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        outputs
    ) {
    private val failureDetail: FailureDetail

    init {
        this.failureDetail =
            FailureDetail.newBuilder()
                .setMessage(errorMessage + " caused by " + getOwner().getLabel())
                .setFailAction(FailureDetails.FailAction.newBuilder().setCode(failActionCode).build())
                .build()
    }

    override fun getPrimaryInput(): Artifact? {
        return null
    }

    fun getErrorMessage(): String {
        return failureDetail.getMessage()
    }

    @Throws(ActionExecutionException::class)
    override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult? {
        throw ActionExecutionException(
            failureDetail.getMessage(), this, false, DetailedExitCode.of(failureDetail)
        )
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        // Should never be cached, but just be safe.
        fp.addString(getErrorMessage())
    }

    override fun getRawProgressMessage(): String {
        return ("Reporting failed target "
                + getOwner().getLabel()
                + " located at "
                + getOwner().getLocation())
    }

    override fun getMnemonic(): String {
        return "Fail"
    }

    companion object {
        private const val GUID = "626cb78a-810f-4af3-979c-ee194955f04c"
    }
}
