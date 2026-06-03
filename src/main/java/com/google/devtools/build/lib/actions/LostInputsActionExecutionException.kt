// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.util.DetailedExitCode

/**
 * An [ActionExecutionException] thrown when an action fails to execute because one or more of
 * its inputs was lost. In some cases, Bazel may know how to fix this on its own.
 */
class LostInputsActionExecutionException(
    message: String?,
    lostInputs: com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?>,
    action: com.google.devtools.build.lib.actions.Action?,
    cause: java.lang.Exception?,
    detailedExitCode: DetailedExitCode?
) : ActionExecutionException(message, cause, action,  /* catastrophe= */false, detailedExitCode) {
    /** Maps lost input digests to their [ActionInput]s.  */
    private val lostInputs: com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?>

    /**
     * The [ActionLookupData] for the action whose evaluation failed. Used to distinguish
     * whether an action handling this exception was primary in its set of shared actions. Event
     * emission and action execution state invalidation should only happen for the primary action.
     */
    private var primaryAction: ActionLookupData? = null

    /**
     * If an ActionStartedEvent was emitted and this action is primary (amongst its set of shared
     * actions), then:
     * 
     * 
     *  * if rewinding is attempted, then an ActionRewindEvent should be emitted.
     *  * if rewinding fails, then an ActionCompletionEvent should be emitted.
     * 
     */
    private var actionStartedEventAlreadyEmitted = false

    /** Used to report the action execution failure if rewinding also fails.  */
    private var primaryOutputPath: Path? = null

    /**
     * Used to report the action execution failure if rewinding also fails. Note that this will be
     * closed, so it may only be used for reporting.
     */
    private var fileOutErr: FileOutErr? = null

    /** Used to inform rewinding that lost inputs were found during input discovery.  */
    private var fromInputDiscovery = false

    init {
        this.lostInputs = lostInputs
    }

    fun getLostInputs(): com.google.common.collect.ImmutableSetMultimap<String?, ActionInput?> {
        return lostInputs
    }

    fun getPrimaryOutputPath(): Path? {
        return primaryOutputPath
    }

    fun setPrimaryOutputPath(primaryOutputPath: Path?) {
        this.primaryOutputPath = primaryOutputPath
    }

    fun getFileOutErr(): FileOutErr? {
        return fileOutErr
    }

    fun setFileOutErr(fileOutErr: FileOutErr?) {
        this.fileOutErr = fileOutErr
    }

    fun setPrimaryAction(primaryAction: ActionLookupData?) {
        this.primaryAction = primaryAction
    }

    /**
     * Whether `actionLookupData` is equal to the previously set primary action. May only be
     * called after the primary action is set.
     */
    fun isPrimaryAction(actionLookupData: ActionLookupData): Boolean {
        com.google.common.base.Preconditions.checkNotNull<ActionLookupData?>(
            primaryAction,
            "expected primary action to have been set"
        )
        return actionLookupData == primaryAction
    }

    fun isActionStartedEventAlreadyEmitted(): Boolean {
        return actionStartedEventAlreadyEmitted
    }

    fun setActionStartedEventAlreadyEmitted() {
        this.actionStartedEventAlreadyEmitted = true
    }

    fun isFromInputDiscovery(): Boolean {
        return fromInputDiscovery
    }

    fun setFromInputDiscovery() {
        this.fromInputDiscovery = true
    }

    /**
     * Converts to the "lost inputs" subtype of the other exception type ([ExecException]) used
     * during action execution.
     * 
     * 
     * May not be used if this exception has been decorated with additional information from its
     * context (e.g. from [.setPrimaryOutputPath] or other setters) because that information
     * would be lost if so.
     */
    fun toExecException(): LostInputsExecException {
        com.google.common.base.Preconditions.checkState(primaryAction == null)
        com.google.common.base.Preconditions.checkState(!actionStartedEventAlreadyEmitted)
        com.google.common.base.Preconditions.checkState(primaryOutputPath == null)
        com.google.common.base.Preconditions.checkState(fileOutErr == null)
        com.google.common.base.Preconditions.checkState(!fromInputDiscovery)
        return LostInputsExecException(lostInputs, this)
    }
}
