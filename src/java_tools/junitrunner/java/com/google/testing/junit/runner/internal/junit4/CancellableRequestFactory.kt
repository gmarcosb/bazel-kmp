// Copyright 2012 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.internal.junit4

import com.google.testing.junit.junit4.runner.RunNotifierWrapper
import org.junit.runner.Description
import org.junit.runner.Request
import org.junit.runner.Runner
import org.junit.runner.notification.RunNotifier
import org.junit.runner.notification.StoppedByUserException
import java.util.concurrent.atomic.AtomicReference

/**
 * Creates requests that can be cancelled.
 */
class CancellableRequestFactory {
    private var requestCreated = false

    @kotlin.concurrent.Volatile
    private var currentNotifier: ThreadSafeRunNotifier? = null
    private val cancellationRequest = AtomicReference<CancellationRequest?>(CancellationRequest.NOT_REQUESTED)

    /**
     * Creates a request that can be cancelled. Can only be called once.
     * 
     * @param delegate request to wrap
     */
    fun createRequest(delegate: Request?): Request {
        check(!requestCreated) { "a request was already created" }
        requestCreated = true

        return object : MemoizingRequest(delegate) {
            override fun createRunner(delegate: Request): Runner {
                return CancellableRunner(delegate.getRunner())
            }
        }
    }

    /**
     * Cancels the request created by this request factory.
     */
    fun cancelRun() {
        stop(true)
    }

    /** Cancels the request created by this request factory as orderly as possible.  */
    fun cancelRunOrderly() {
        stop(false)
    }

    private fun stop(hardStop: Boolean) {
        if (cancellationRequest.compareAndSet(
                CancellationRequest.NOT_REQUESTED,
                if (hardStop) CancellationRequest.HARD_STOP else CancellationRequest.ORDERLY_STOP
            )
        ) {
            val notifier: RunNotifier? = currentNotifier
            if (notifier != null) {
                notifier.pleaseStop()
            }
        }
    }


    private inner class CancellableRunner(private val delegate: Runner) : Runner() {
        override fun getDescription(): Description? {
            return delegate.getDescription()
        }

        override fun run(notifier: RunNotifier?) {
            currentNotifier = ThreadSafeRunNotifier(notifier)
            if (cancellationRequest.get() != CancellationRequest.NOT_REQUESTED) {
                currentNotifier!!.pleaseStop()
            }
            if (cancellationRequest.get() == CancellationRequest.ORDERLY_STOP) {
                return
            }

            try {
                delegate.run(currentNotifier)
            } catch (e: StoppedByUserException) {
                if (cancellationRequest.get() == CancellationRequest.HARD_STOP) {
                    throw RuntimeException("Test run interrupted", e)
                } else if (cancellationRequest.get() == CancellationRequest.ORDERLY_STOP) {
                    e.printStackTrace()
                    return
                }
                throw e
            }
        }
    }


    private class ThreadSafeRunNotifier(delegate: RunNotifier?) : RunNotifierWrapper(delegate) {
        @kotlin.concurrent.Volatile
        private var stopRequested = false

        /**
         * {@inheritDoc}
         *
         *
         * 
         * The implementation is almost an exact copy of the version in
         * `RunNotifier` but is thread-safe.
         */
        @Throws(StoppedByUserException::class)
        override fun fireTestStarted(description: Description?) {
            if (stopRequested) {
                throw StoppedByUserException()
            }
            getDelegate().fireTestStarted(description)
        }

        /**
         * {@inheritDoc}
         *
         *
         * 
         * This method is thread-safe.
         */
        override fun pleaseStop() {
            stopRequested = true
        }
    }

    /** Cancellation request types of a [CancellableRequestFactory].  */
    internal enum class CancellationRequest {
        NOT_REQUESTED,  // Initial state of CancellableRequestFactory
        HARD_STOP,  // Propagates StoppedByUserException
        ORDERLY_STOP // Catches StoppedByUserException and prevents further test runs
    }
}
