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

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/**
 * This exception gets thrown if [Action.execute] is unsuccessful.
 * Typically these are re-raised ExecException throwables.
 */
@ThreadSafe
open class ActionExecutionException : java.lang.Exception, DetailedException {
    private val action: ActionAnalysisMetadata
    private val rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?
    private val catastrophe: Boolean
    private val detailedExitCode: DetailedExitCode

    constructor(
        cause: Throwable,
        action: ActionAnalysisMetadata,
        catastrophe: Boolean,
        detailedExitCode: DetailedExitCode
    ) : super(cause.message, cause) {
        this.action = action
        this.detailedExitCode = detailedExitCode
        this.rootCauses = rootCausesFromAction(action, detailedExitCode)
        this.catastrophe = catastrophe
    }

    constructor(
        message: String?,
        cause: Throwable?,
        action: ActionAnalysisMetadata,
        catastrophe: Boolean,
        detailedExitCode: DetailedExitCode?
    ) : super(message, cause) {
        this.action = action
        this.catastrophe = catastrophe
        this.detailedExitCode = com.google.common.base.Preconditions.checkNotNull<DetailedExitCode>(detailedExitCode)
        this.rootCauses = rootCausesFromAction(action, detailedExitCode)
    }

    constructor(
        message: String?,
        action: ActionAnalysisMetadata,
        catastrophe: Boolean,
        detailedExitCode: DetailedExitCode
    ) : super(message) {
        this.action = action
        this.catastrophe = catastrophe
        this.detailedExitCode = detailedExitCode
        this.rootCauses = rootCausesFromAction(action, this.detailedExitCode)
    }

    constructor(
        message: String?,
        action: ActionAnalysisMetadata,
        rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
        catastrophe: Boolean,
        detailedExitCode: DetailedExitCode
    ) : super(message) {
        this.action = action
        this.rootCauses = rootCauses
        this.catastrophe = catastrophe
        this.detailedExitCode = detailedExitCode
    }

    constructor(
        message: String?,
        cause: Throwable?,
        action: ActionAnalysisMetadata,
        rootCauses: NestedSet<com.google.devtools.build.lib.causes.Cause?>?,
        catastrophe: Boolean,
        detailedExitCode: DetailedExitCode?
    ) : super(message, cause) {
        this.action = action
        this.rootCauses = rootCauses
        this.catastrophe = catastrophe
        this.detailedExitCode = com.google.common.base.Preconditions.checkNotNull<DetailedExitCode>(detailedExitCode)
    }

    /** Returns the action that failed.  */
    fun getAction(): ActionAnalysisMetadata {
        return action
    }

    /**
     * Return the root causes that should be reported. Usually the owner of the action, but it can be
     * the label of a missing artifact.
     */
    fun getRootCauses(): NestedSet<com.google.devtools.build.lib.causes.Cause?>? {
        return rootCauses
    }

    /**
     * Returns the location of the owner of this action.  May be null.
     */
    fun getLocation(): net.starlark.java.syntax.Location? {
        return action.getOwner().getLocation()
    }

    /**
     * Catastrophic exceptions should stop builds, even if --keep_going.
     */
    fun isCatastrophe(): Boolean {
        return catastrophe
    }

    /**
     * Returns the exit code to return from this Bazel invocation because of this action execution
     * failure.
     */
    fun getExitCode(): ExitCode {
        return detailedExitCode.getExitCode()
    }

    public override fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }

    /**
     * Returns true if the error should be shown.
     */
    open fun showError(): Boolean {
        return message != null
    }

    companion object {
        private fun rootCausesFromAction(
            action: ActionAnalysisMetadata?, detailedExitCode: DetailedExitCode
        ): NestedSet<com.google.devtools.build.lib.causes.Cause?> {
            return if (action == null || action.getOwner() == null || action.getOwner().getLabel() == null)
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            else
                NestedSetBuilder.create(
                    Order.STABLE_ORDER,
                    ActionFailed(
                        action.getPrimaryOutput().getExecPath(),
                        action.getOwner().getLabel(),
                        action.getOwner().getConfigurationChecksum(),
                        detailedExitCode
                    )
                )
        }

        fun fromExecException(
            exception: ExecException,
            action: com.google.devtools.build.lib.actions.Action
        ): ActionExecutionException {
            return fromExecException(exception, null, action)
        }

        /**
         * Returns a new ActionExecutionException given an optional action subtask describing which part
         * of the action failed (should be null for standard action failures). When appropriate (we use
         * some heuristics to decide), produces an abbreviated message incorporating just the termination
         * status if available.
         * 
         * @param exception initial ExecException
         * @param actionSubtask additional information about the action
         * @param action failed action
         * @return ActionExecutionException object describing the action failure
         */
        fun fromExecException(
            exception: ExecException, actionSubtask: String?, action: com.google.devtools.build.lib.actions.Action
        ): ActionExecutionException {
            // Message from ActionExecutionException will be prepended with action.describe() where
            // necessary: because not all ActionExecutionExceptions come from this codepath, it is safer
            // for consumers to manually prepend. We still put action.describe() in the failure detail
            // message argument.
            val message =
                ((if (actionSubtask == null) "" else actionSubtask + ": ")
                        + exception.getMessageForActionExecutionException())

            val code: DetailedExitCode? =
                DetailedExitCode.of(exception.getFailureDetail(action.describe() + " failed: " + message))
            if (exception is LostInputsExecException) {
                return exception.fromExecException(message, action, code)
            }

            return fromExecException(exception, message, action, code)
        }

        fun fromExecException(
            exception: ExecException,
            message: String?,
            action: com.google.devtools.build.lib.actions.Action,
            code: DetailedExitCode?
        ): ActionExecutionException {
            return ActionExecutionException(
                message, exception, action, exception.isCatastrophic(), code
            )
        }
    }
}
