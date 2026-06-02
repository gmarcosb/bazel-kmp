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
package com.google.devtools.build.lib.bugreport

import com.google.common.collect.ImmutableList
import com.google.errorprone.annotations.FormatMethod
import com.google.errorprone.annotations.FormatString
import java.lang.String
import kotlin.Any
import kotlin.Exception
import kotlin.IllegalStateException
import kotlin.Throwable

/**
 * Logs bug reports.
 * 
 * 
 * This interface is generally fulfilled by [.defaultInstance]. It exists to facilitate
 * alternate implementations in tests that intentionally send bug reports, since the default
 * instance throws if invoked during a test case.
 */
interface BugReporter {
    /** Reports an unexpected state, see [BugReport.logUnexpected].  */
    @FormatMethod
    fun logUnexpected(@FormatString message: String?, vararg args: Any?) {
        BugReport.logUnexpected(message, *args)
    }

    /** See [BugReport.logUnexpected].  */
    @FormatMethod
    fun logUnexpected(e: Exception?, @FormatString message: String?, vararg args: Any?) {
        BugReport.logUnexpected(e, message, *args)
    }

    /** Reports an exception, see [BugReport.sendBugReport].  */
    @FormatMethod
    fun sendBugReport(@FormatString message: String, vararg args: Any?) {
        sendBugReport(IllegalStateException(String.format(message, *args)))
    }

    /** Reports an exception, see [BugReport.sendBugReport].  */
    fun sendBugReport(exception: Throwable?) {
        sendBugReport(exception,  /*args=*/ImmutableList.of<kotlin.String?>())
    }

    /** Reports an exception, see [BugReport.sendBugReport].  */
    fun sendBugReport(exception: Throwable?, args: MutableList<kotlin.String?>?, vararg values: kotlin.String?)

    /** Reports a non-fatal exception, see [BugReport.sendNonFatalBugReport].  */
    fun sendNonFatalBugReport(exception: Throwable?)

    /** See [BugReport.handleCrash].  */
    fun handleCrash(crash: Crash?, ctx: CrashContext?)

    companion object {
        @kotlin.jvm.JvmStatic
        fun defaultInstance(): BugReporter {
            return BugReport.REPORTER_INSTANCE
        }
    }
}
