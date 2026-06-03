// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.bugreport.BugReporter.sendBugReport
import com.google.devtools.build.lib.bugreport.Crash
import com.google.devtools.build.lib.bugreport.CrashContext
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.build.lib.testutil.TestInterruptingBugReporter
import org.hamcrest.CoreMatchers
import org.junit.internal.matchers.ThrowableMessageMatcher
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Tests for [TestInterruptingBugReporter].  */
@RunWith(JUnit4::class)
// Need to use an outer rule to test our inner rule.
class TestInterruptingBugReporterTest {
    @org.junit.Rule(order = 0)
    @Suppress("deprecation") // See above.
    val thrown: org.junit.rules.ExpectedException = org.junit.rules.ExpectedException.none()

    @org.junit.Rule(order = 1)
    val bugReporter: TestInterruptingBugReporter = TestInterruptingBugReporter()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @org.junit.After
    fun shutdownExecutor() {
        executor.shutdownNow()
    }

    @org.junit.Test
    fun passing() {
    }

    @org.junit.Test
    fun failing() {
        thrown.expect(java.lang.IllegalStateException::class.java)
        thrown.expectMessage("Intentional")
        throw java.lang.IllegalStateException("Intentional")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interrupted() {
        thrown.expect(java.lang.InterruptedException::class.java)
        thrown.expectMessage("Manual interrupt")
        throw java.lang.InterruptedException("Manual interrupt")
    }

    @org.junit.Test
    fun mainThread_sendBugReport() {
        thrown.expect(java.lang.IllegalStateException::class.java)
        thrown.expectCause(CoreMatchers.instanceOf<Any?>(IOException::class.java))
        thrown.expectCause(ThrowableMessageMatcher.hasMessage<Throwable?>(CoreMatchers.equalTo<String?>("IO error")))
        throw org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { bugReporter.sendBugReport(IOException("IO error")) })
    }

    @org.junit.Test
    fun mainThread_handleCrash() {
        thrown.expect(java.lang.IllegalStateException::class.java)
        thrown.expectCause(CoreMatchers.instanceOf<Any?>(IOException::class.java))
        thrown.expectCause(ThrowableMessageMatcher.hasMessage<Throwable?>(CoreMatchers.equalTo<String?>("IO error")))
        throw org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                bugReporter.handleCrash(
                    Crash.from(IOException("IO error")),
                    CrashContext.halt()
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asyncThread_noException() {
        val future: java.util.concurrent.Future<java.lang.Void?> = doSomethingAsync(java.lang.Runnable {})
        future.get()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asyncThread_uncaughtException() {
        thrown.expect(java.lang.IllegalStateException::class.java)
        thrown.expectMessage("Intentional")
        val future: java.util.concurrent.Future<java.lang.Void?> =
            doSomethingAsync(
                java.lang.Runnable {
                    throw java.lang.IllegalStateException("Intentional")
                })
        throw org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { future.get() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asyncThread_sendBugReport() {
        thrown.expect(java.lang.IllegalStateException::class.java)
        thrown.expectMessage("Intentional")
        val future: java.util.concurrent.Future<java.lang.Void?> =
            doSomethingAsync(java.lang.Runnable { bugReporter.sendBugReport(java.lang.IllegalStateException("Intentional")) })
        throw org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { future.get() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun asyncThread_handleCrash() {
        thrown.expect(java.lang.IllegalStateException::class.java)
        thrown.expectMessage("Intentional")
        val future: java.util.concurrent.Future<java.lang.Void?> =
            doSomethingAsync(
                java.lang.Runnable {
                    bugReporter.handleCrash(
                        Crash.from(java.lang.IllegalStateException("Intentional")), CrashContext.halt()
                    )
                })
        throw org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { future.get() })
    }

    private fun doSomethingAsync(something: java.lang.Runnable): java.util.concurrent.Future<java.lang.Void?> {
        val future: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        executor.execute(
            java.lang.Runnable {
                something.run()
                future.set(null)
            })
        return future
    }
}
