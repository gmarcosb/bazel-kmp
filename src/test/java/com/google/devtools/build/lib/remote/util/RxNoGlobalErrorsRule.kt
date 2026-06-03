// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.util

import com.google.devtools.build.lib.graph.Digraph.equals
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.functions
import com.google.devtools.build.lib.remote.grpc.TokenBucket.size
import io.reactivex.rxjava3.exceptions.CompositeException
import io.reactivex.rxjava3.plugins.RxJavaPlugins
import org.junit.rules.ExternalResource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A JUnit [org.junit.Rule] that captures uncaught errors from RxJava streams and rethrows
 * them post-test if left unaddressed.
 * 
 * 
 * This is to prevent false-positives caused by RxJava's default uncaught error handler, which
 * manually forwards the event to the current Thread's exception handler and bypasses JUnit's
 * failure reporting.
 * 
 * 
 * Can also be used to assert that no uncaught errors have yet been thrown mid-test. This is
 * useful to ensure tests are in a consistent state before continuing.
 */
class RxNoGlobalErrorsRule : ExternalResource() {
    private val errors: MutableList<Throwable?> = CopyOnWriteArrayList<Throwable?>()

    override fun before() {
        RxJavaPlugins.setErrorHandler(io.reactivex.rxjava3.functions.Consumer { e: Throwable? -> errors.add(e) })
    }

    override fun after() {
        assertNoErrors()
    }

    private class UncaughtRxErrors(cause: Throwable?) :
        java.lang.RuntimeException("There were uncaught Rx errors during test execution", cause)

    /**
     * Asserts that no uncaught errors have yet occurred.
     * 
     * 
     * If an Rx stream has thrown an uncaught error any time before this method is called, an
     * [UncaughtRxErrors] is thrown. This is useful for ensuring that tests are in a consistent
     * state before continuing.
     * 
     * 
     * You may need to advance any test schedulers so that any pending events are flushed.
     */
    private fun assertNoErrors() {
        if (errors.size() > 1) {
            val errorsArray: Array<Throwable?> = errors.toArray<Throwable?>(arrayOfNulls<Throwable>(0))
            throw UncaughtRxErrors(CompositeException(*errorsArray))
        } else if (errors.size() == 1) {
            throw UncaughtRxErrors(errors.get(0))
        }
    }
}
