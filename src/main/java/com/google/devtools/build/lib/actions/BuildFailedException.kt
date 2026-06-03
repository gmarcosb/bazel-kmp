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

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * This exception gets thrown if there were errors during the execution phase of the build.
 * 
 * 
 * The argument to the constructor may be null if the thrower has already printed an error
 * message; in this case, no error message should be printed by the catcher. (Typically, this
 * happens when the builder is unsuccessful and `--keep_going` was specified. This error
 * corresponds to one or more actions failing, but since those actions' failures will be reported
 * separately, the exception carries no message and is just used for control flow.)
 * 
 * 
 * This exception typically leads to Bazel termination with exit code [ ][ExitCode.BUILD_FAILURE]. However, if a more specific exit code is appropriate, it can be
 * propagated by specifying the exit code to the constructor using a [DetailedExitCode].
 */
@ThreadSafe
open class BuildFailedException(
    message: String?,
    private val catastrophic: Boolean,
    private val errorAlreadyShown: Boolean,
    detailedExitCode: DetailedExitCode?
) : java.lang.Exception(message), DetailedException {
    private val detailedExitCode: DetailedExitCode

    constructor(message: String?, detailedExitCode: DetailedExitCode?) : this(
        message,  /*catastrophic=*/
        false,  /*errorAlreadyShown=*/
        false,
        detailedExitCode
    )

    init {
        this.detailedExitCode = com.google.common.base.Preconditions.checkNotNull<DetailedExitCode>(detailedExitCode)
    }

    fun isCatastrophic(): Boolean {
        return catastrophic
    }

    fun isErrorAlreadyShown(): Boolean {
        return errorAlreadyShown || getMessage() == null
    }

    public override fun getDetailedExitCode(): DetailedExitCode {
        return detailedExitCode
    }
}
