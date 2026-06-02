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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.SkyKey

/** Wrapper exception that [Runnable]s can throw.  */
internal class SchedulerException private constructor(
    cause: java.lang.Exception?,
    errorInfo: com.google.devtools.build.skyframe.ErrorInfo?,
    failedValue: SkyKey?,
    rdepsToBubbleUpTo: MutableSet<SkyKey?>?
) : java.lang.RuntimeException(if (errorInfo != null) errorInfo.getException() else cause) {
    private val failedValue: SkyKey
    private val errorInfo: com.google.devtools.build.skyframe.ErrorInfo?
    private val rdepsToBubbleUpTo: MutableSet<SkyKey?>?

    init {
        this.errorInfo = errorInfo
        this.rdepsToBubbleUpTo = rdepsToBubbleUpTo
        this.failedValue = com.google.common.base.Preconditions.checkNotNull<SkyKey>(failedValue, errorInfo)
    }

    fun getFailedValue(): SkyKey {
        return failedValue
    }

    fun getErrorInfo(): com.google.devtools.build.skyframe.ErrorInfo? {
        return errorInfo
    }

    fun getRdepsToBubbleUpTo(): MutableSet<SkyKey?>? {
        return rdepsToBubbleUpTo
    }

    companion object {
        /**
         * Returns a SchedulerException wrapping an expected error, e.g. an error describing an expected
         * build failure when trying to evaluate the given value, that should cause Skyframe to produce
         * useful error information to the user.
         */
        fun ofError(
            errorInfo: com.google.devtools.build.skyframe.ErrorInfo?,
            failedValue: SkyKey?,
            rdepsToBubbleUpTo: MutableSet<SkyKey?>?
        ): SchedulerException {
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.ErrorInfo?>(errorInfo)
            com.google.common.base.Preconditions.checkNotNull<MutableSet<SkyKey?>?>(
                rdepsToBubbleUpTo,
                "null rdeps: %s %s",
                errorInfo,
                failedValue
            )
            return SchedulerException(
                errorInfo.getException(), errorInfo, failedValue, rdepsToBubbleUpTo
            )
        }

        /**
         * Returns a SchedulerException wrapping an InterruptedException, e.g. if the user interrupts
         * the build, that should cause Skyframe to exit as soon as possible.
         */
        fun ofInterruption(cause: java.lang.InterruptedException?, failedValue: SkyKey?): SchedulerException {
            return SchedulerException(cause, null, failedValue, null)
        }
    }
}
