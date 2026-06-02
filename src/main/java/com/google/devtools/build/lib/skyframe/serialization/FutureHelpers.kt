// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException
import java.io.IOException
import java.util.concurrent.ExecutionException

/** Helpers for serialization futures.  */
object FutureHelpers {
    /**
     * Waits for `future` and returns the result.
     * 
     * 
     * Handles exceptions by converting them into [SerializationException].
     */
    @com.google.common.annotations.VisibleForTesting // package-private
    @com.google.errorprone.annotations.CanIgnoreReturnValue // may be called for side effects
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> waitForSerializationFuture(future: com.google.common.util.concurrent.ListenableFuture<T?>): T? {
        try {
            // TODO: b/297857068 - revisit whether this should handle to interrupts. As of 02/09/24,
            // serialization doesn't handle interrupts so introducing them here could lead to unforseen
            // problems.
            return com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly<T?>(future)
        } catch (e: ExecutionException) {
            throw asSerializationException(e.getCause())
        }
    }

    /**
     * Gets the done value of `future`.
     * 
     * 
     * Handles exceptions by converting them into [SerializationException].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> getDoneSerializationFuture(future: com.google.common.util.concurrent.ListenableFuture<T?>): T? {
        try {
            return com.google.common.util.concurrent.Futures.getDone<T?>(future)
        } catch (e: ExecutionException) {
            throw asSerializationException(e.getCause())
        }
    }

    /**
     * Waits for `future` and returns the result.
     * 
     * 
     * Handles exceptions by converting them into [SerializationException]. The [ ] may have a [MissingFingerprintValueException] cause.
     * 
     * 
     * The [MissingFingerprintValueException] needs special case handling anywhere this
     * method is used. Outside of test code, this is only possible for via [ ][SharedValueDeserializationContext.deserializeWithSharedValues], which explicitly handles it.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue // may be called for side effects
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> waitForDeserializationFuture(future: com.google.common.util.concurrent.ListenableFuture<T?>): T? {
        try {
            // TODO: b/297857068 - revisit whether this should handle to interrupts. As of 02/09/24,
            // serialization doesn't handle interrupts so introducing them here could lead to unforseen
            // problems.
            return com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly<T?>(future)
        } catch (e: ExecutionException) {
            throw asDeserializationException(e.getCause())
        }
    }

    /**
     * Gets the done value of `future`.
     * 
     * 
     * May throw an [IllegalStateException] if the future is not done. See [ ][.waitForDeserializationFuture] for a description the [SerializationException].
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> getDoneDeserializationFuture(future: com.google.common.util.concurrent.ListenableFuture<T?>): T? {
        try {
            return com.google.common.util.concurrent.Futures.getDone<T?>(future)
        } catch (e: ExecutionException) {
            throw asDeserializationException(e.getCause())
        }
    }

    /**
     * Reports any errors that occur on `combiner`.
     * 
     * 
     * Used when a future value is going to be discarded, but it would be inappropriate to ignore
     * possible errors.
     */
    fun reportAnyFailures(combiner: com.google.common.util.concurrent.Futures.FutureCombiner<*>) {
        com.google.common.util.concurrent.Futures.addCallback<Any?>(
            combiner.call<Any?>(
                java.util.concurrent.Callable { null },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            ), FAILURE_REPORTING_CALLBACK, com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    val FAILURE_REPORTING_CALLBACK: FutureStatusCallback = object : FutureStatusCallback() {
        override fun onSuccess() {}

        override fun onFailure(t: Throwable) {
            BugReporter.defaultInstance().sendBugReport(t)
        }
    }

    private fun asDeserializationException(cause: Throwable?): com.google.devtools.build.lib.skyframe.serialization.SerializationException? {
        if (cause is MissingFingerprintValueException) {
            return com.google.devtools.build.lib.skyframe.serialization.SerializationException(cause)
        }
        return asSerializationException(cause)
    }

    private fun asSerializationException(cause: Throwable?): com.google.devtools.build.lib.skyframe.serialization.SerializationException {
        if (cause is com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
            return cause
        }
        if (cause is IOException) {
            return com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "serialization I/O error",
                cause
            )
        }
        return com.google.devtools.build.lib.skyframe.serialization.SerializationException(
            "unexpected serialization error",
            cause
        )
    }

    /**
     * A callback for [ListenableFuture] that only cares about the result status (success or
     * failure) and not the result value itself.
     */
    abstract class FutureStatusCallback : com.google.common.util.concurrent.FutureCallback<Any?> {
        override fun onSuccess(unused: Any?) {
            onSuccess()
        }

        /** Called when the future completes successfully.  */
        abstract fun onSuccess()
    }
}
