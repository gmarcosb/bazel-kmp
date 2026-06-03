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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.util.Pair

/**
 * Safely await [CountDownLatch]es in tests, storing any exceptions that happen. Callers
 * should call [.assertNoErrors] at the end of each test method, either manually or using an
 * `@After` hook.
 */
class TrackingAwaiter private constructor() {
    private val exceptionsThrown: ConcurrentLinkedQueue<Pair<String?, Throwable?>?> =
        ConcurrentLinkedQueue<Pair<String?, Throwable?>?>()

    /** Threadpools can swallow exceptions. Make sure they don't get lost.  */
    fun awaitLatchAndTrackExceptions(latch: CountDownLatch, errorMessage: String?) {
        try {
            waitAndMaybeThrowInterrupt(latch, errorMessage)
        } catch (e: Throwable) {
            // We would expect e to be InterruptedException or AssertionError, but we leave it open so
            // that any throwable gets recorded.
            exceptionsThrown.add(Pair.of(errorMessage, e))
            // Caller will assert exceptionsThrown is empty at end of test and fail, even if this is
            // swallowed.
            com.google.common.base.Throwables.propagate(e)
        }
    }

    /** Allow arbitrary errors to be recorded here for later throwing.  */
    fun injectExceptionAndMessage(throwable: Throwable?, message: String?) {
        exceptionsThrown.add(Pair.of(message, throwable))
    }

    fun assertNoErrors() {
        val thisEvalExceptionsThrown: MutableList<Pair<String?, Throwable?>?> =
            com.google.common.collect.ImmutableList.copyOf<Pair<String?, Throwable?>?>(exceptionsThrown)
        exceptionsThrown.clear()
        Truth.assertThat(thisEvalExceptionsThrown).isEmpty()
    }

    companion object {
        val INSTANCE: TrackingAwaiter = TrackingAwaiter()

        /**
         * This method fixes a race condition with simply calling [CountDownLatch.await]. If this
         * thread is interrupted before `latch.await` is called, then `latch.await` will throw
         * an [InterruptedException] without checking the value of the latch at all. This leads to a
         * race condition in which this thread will throw an InterruptedException if it is slow calling
         * `latch.await`, but it will succeed normally otherwise.
         * 
         * 
         * To avoid this, we wait for the latch uninterruptibly. In the end, if the latch has in fact
         * been released, we do nothing, although the interrupted bit is set, so that the caller can
         * decide to throw an InterruptedException if it wants to. If the latch was not released, then
         * this was not a race condition, but an honest-to-goodness interrupt, and we propagate the
         * exception onward.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun waitAndMaybeThrowInterrupt(latch: CountDownLatch, errorMessage: String?) {
            if (com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly(
                    latch, com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
            ) {
                // Latch was released. We can ignore the interrupt state.
                return
            }
            if (java.lang.Thread.interrupted()) {
                // We were interrupted before the latch was released. Propagate this interruption.
                throw java.lang.InterruptedException()
            } else {
                // Nobody interrupted us, but latch wasn't released. Failure.
                throw java.lang.AssertionError(errorMessage)
            }
        }
    }
}
