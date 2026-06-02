// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.server.FailureDetails

/** Factory methods for producing [Crash]-type [FailureDetail] messages.  */
object CrashFailureDetails {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    /**
     * Max message length in [FailureDetails.Throwable] submessage, anything beyond this is
     * truncated.
     */
    private const val MAX_THROWABLE_MESSAGE_LENGTH = 2000

    /**
     * At most this many [FailureDetails.Throwable] messages will be specified by a [ ] submessage.
     */
    private const val MAX_CAUSE_CHAIN_SIZE = 5

    /**
     * At most this many stack trace element strings will be specified by a [ ] submessage.
     */
    private const val MAX_STACK_TRACE_SIZE = 1000

    private var oomDetector: java.util.function.BooleanSupplier = java.util.function.BooleanSupplier { false }

    /** Registers a predicate to use for more aggressive [OutOfMemoryError] detection.  */
    fun setOomDetector(oomDetector: java.util.function.BooleanSupplier) {
        CrashFailureDetails.oomDetector = oomDetector
    }

    /** Returns whether an [OutOfMemoryError] was detected.  */
    fun oomDetected(): Boolean {
        return oomDetector.getAsBoolean()
    }

    @kotlin.jvm.JvmStatic
    fun detailedExitCodeForThrowable(throwable: Throwable): DetailedExitCode {
        return DetailedExitCode.Companion.of(forThrowable(throwable))
    }

    /** Returns a [Crash]-type [FailureDetail] with its cause chain filled out.  */
    @kotlin.jvm.JvmStatic
    fun forThrowable(throwable: Throwable): FailureDetail {
        val crashBuilder: Crash.Builder = Crash.newBuilder()
        if (getRootCauseToleratingCycles(throwable) is java.lang.OutOfMemoryError) {
            crashBuilder.setCode(Crash.Code.CRASH_OOM).setOomCauseCategory(OomCauseCategory.ORGANIC)
        } else if (oomDetected()) {
            logger.atWarning().log("Classifying non-OOM crash as OOM")
            crashBuilder
                .setCode(Crash.Code.CRASH_OOM)
                .setOomCauseCategory(OomCauseCategory.OOM_DETECTOR_OVERRIDE)
        } else {
            crashBuilder.setCode(Crash.Code.CRASH_UNKNOWN)
        }
        addCause(crashBuilder, throwable, com.google.common.collect.Sets.newIdentityHashSet<Any?>())
        return FailureDetail.newBuilder()
            .setMessage("Crashed: " + joinSummarizedCauses(crashBuilder))
            .setCrash(crashBuilder)
            .build()
    }

    private fun joinSummarizedCauses(crashBuilder: Crash.Builder): String {
        return crashBuilder.getCausesOrBuilderList().stream()
            .map({ obj: CrashFailureDetails?, throwableOrBuilder: ThrowableOrBuilder ->
                summarizeCause(
                    throwableOrBuilder
                )
            })
            .collect(Collectors.joining(", "))
    }

    private fun summarizeCause(throwableOrBuilder: ThrowableOrBuilder): String? {
        return java.lang.String.format(
            "(%s) %s", throwableOrBuilder.getThrowableClass(), throwableOrBuilder.getMessage()
        )
    }

    private fun addCause(
        crashBuilder: Crash.Builder, throwable: Throwable, addedThrowables: MutableSet<Any?>
    ) {
        addedThrowables.add(throwable)

        crashBuilder.addCauses(getThrowable(throwable))

        val cause: Throwable? = throwable.getCause()
        if (cause == null || addedThrowables.contains(cause)
            || crashBuilder.getCausesOrBuilderList().size() >= MAX_CAUSE_CHAIN_SIZE
        ) {
            return
        }
        addCause(crashBuilder, cause, addedThrowables)
    }

    private fun getThrowable(throwable: Throwable): Throwable {
        val throwableMessage: String =
            com.google.common.base.Ascii.truncate(
                if (throwable.getMessage() != null) throwable.getMessage() else "",
                MAX_THROWABLE_MESSAGE_LENGTH,
                "[truncated]"
            )
        val throwableBuilder: FailureDetails.Throwable.Builder =
            FailureDetails.Throwable.newBuilder()
                .setMessage(throwableMessage)
                .setThrowableClass(throwable.getClass().getName())
        val stackTrace: Array<java.lang.StackTraceElement> = throwable.getStackTrace()
        for (stackTraceElement in stackTrace) {
            if (throwableBuilder.getStackTraceList().size() >= MAX_STACK_TRACE_SIZE) {
                break
            }
            throwableBuilder.addStackTrace(stackTraceElement.toString())
        }
        return throwableBuilder.build()
    }

    /**
     * Returns the innermost cause of `throwable`. The first throwable in a chain provides
     * context from when the error or exception was initially detected. Example usage:
     * 
     * <pre>
     * assertEquals("Unable to assign a customer id", Throwables.getRootCause(e).getMessage());
    </pre> * 
     * 
     * Cloned from [Throwables.getRootCause] with a modification to return an arbitrary element
     * of the cycle rather than throw if there is a causal cycle.
     */
    private fun getRootCauseToleratingCycles(throwable: Throwable): Throwable {
        // Keep a second pointer that slowly walks the causal chain. If the fast pointer ever catches
        // the slower pointer, then there's a loop.
        var throwable = throwable
        var slowPointer = throwable
        var advanceSlowPointer = false

        var cause: Throwable?
        while ((throwable.getCause().also { cause = it }) != null) {
            throwable = cause!!

            if (throwable === slowPointer) {
                // There's a cycle: choose an arbitrary element in that cycle.
                return throwable
            }
            if (advanceSlowPointer) {
                slowPointer = slowPointer.getCause()
            }
            advanceSlowPointer = !advanceSlowPointer // only advance every other iteration
        }
        return throwable
    }
}
