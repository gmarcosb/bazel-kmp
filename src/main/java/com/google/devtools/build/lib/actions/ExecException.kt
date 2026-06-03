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

import com.google.devtools.build.lib.server.FailureDetails.FailureDetail

/**
 * An exception indication that the execution of an action has failed OR could not be attempted OR
 * could not be finished OR had something else wrong.
 * 
 * 
 * The six main kinds of failure are broadly defined as follows:
 * 
 * 
 * USER_INPUT which means it had something to do with what the user told us to do. This failure
 * should satisfy the invariant that it would happen identically again if all other things are
 * equal.
 * 
 * 
 * ENVIRONMENT which is loosely defined as anything which is generally out of scope for a blaze
 * evaluation. As a rule of thumb, these are any errors would not necessarily happen again given
 * constant input.
 * 
 * 
 * INTERRUPTION conditions arise from being unable to complete an evaluation for whatever reason.
 * 
 * 
 * INTERNAL_ERROR would happen because of anything which arises from within blaze itself but is
 * generally unexpected to ever occur for any user input.
 * 
 * 
 * LOST_INPUT which means the failure occurred because the action expected to consume some input
 * that went missing. Although this seems similar to ENVIRONMENT, Blaze may know how to fix this
 * problem.
 * 
 * 
 * MISSING_DEP which means that a skyframe restart is necessary because a dependency was missing.
 * 
 * 
 * The class is a catch-all for both failures of actions and failures to evaluate actions
 * properly.
 * 
 * 
 * Invariably, all low level ExecExceptions are caught by various specific ConfigurationAction
 * classes and re-raised as ActionExecutionExceptions.
 */
abstract class ExecException : java.lang.Exception {
    private val catastrophe: Boolean

    @kotlin.jvm.JvmOverloads
    constructor(message: String?, catastrophe: Boolean = false) : super(message) {
        this.catastrophe = catastrophe
    }

    constructor(cause: Throwable?) : super(cause) {
        this.catastrophe = false
    }

    constructor(
        message: String?,
        cause: Throwable?
    ) : super(if (cause == null) message else message + ": " + cause.getMessage(), cause) {
        this.catastrophe = false
    }

    /** Catastrophic exceptions should stop the build, even if --keep_going.  */
    fun isCatastrophic(): Boolean {
        return catastrophe
    }

    fun getMessageForActionExecutionException(): String? {
        return getMessage()
    }

    abstract fun getFailureDetail(message: String?): FailureDetail?
}
