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

import com.google.common.truth.Truth
import com.google.testing.junit.runner.internal.junit4.CancellableRequestFactory
import com.google.testing.junit.runner.internal.junit4.CancellableRequestFactory.cancelRun
import com.google.testing.junit.runner.internal.junit4.CancellableRequestFactory.createRequest
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4Runner.run
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runner.notification.RunNotifier
import org.junit.runner.notification.StoppedByUserException
import org.junit.runners.JUnit4
import org.junit.runners.Suite
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests for [CancellableRequestFactory].
 */
@RunWith(JUnit4::class)
class CancellableRequestFactoryTest {
    private val cancellableRequestFactory: CancellableRequestFactory = CancellableRequestFactory()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCancelRunAfterStarting() {
        val testStartLatch: CountDownLatch = CountDownLatch(1)
        val testContinueLatch: CountDownLatch = CountDownLatch(1)
        val secondTestRan: AtomicBoolean = AtomicBoolean(false)

        // Simulates a test that hangs
        val blockingRunner = FakeRunner("blocks", object : java.lang.Runnable {
            override fun run() {
                testStartLatch.countDown()
                try {
                    testContinueLatch.await(1, TimeUnit.SECONDS)
                } catch (e: java.lang.InterruptedException) {
                    java.lang.Thread.currentThread().interrupt()
                    throw java.lang.RuntimeException("Timed out waiting for signal to continue test", e)
                }
            }
        })

        // A runner that should never run its test
        val secondRunner = FakeRunner("shouldNotRun", object : java.lang.Runnable {
            override fun run() {
                secondTestRan.set(true)
            }
        })

        val fakeSuite = RunnerSuite(blockingRunner, secondRunner)
        val request: org.junit.runner.Request =
            cancellableRequestFactory.createRequest(org.junit.runner.Request.runner(fakeSuite))

        val executor: ExecutorService = Executors.newSingleThreadExecutor()
        val future: java.util.concurrent.Future<org.junit.runner.Result?> =
            executor.submit<org.junit.runner.Result?>(object : java.util.concurrent.Callable<org.junit.runner.Result?> {
                @Throws(java.lang.Exception::class)
                override fun call(): org.junit.runner.Result {
                    val core: JUnitCore = JUnitCore()
                    return core.run(request)
                }
            })

        // Simulate cancel being called in the middle of the test
        testStartLatch.await(1, TimeUnit.SECONDS)
        cancellableRequestFactory.cancelRun()
        testContinueLatch.countDown()

        val e: ExecutionException =
            org.junit.Assert.assertThrows<ExecutionException>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { future.get(10, TimeUnit.SECONDS) })
        val runnerException: Throwable? = e.cause
        Truth.assertThat(runnerException).isInstanceOf(java.lang.RuntimeException::class.java)
        Truth.assertThat(runnerException).hasMessageThat().isEqualTo("Test run interrupted")
        Truth.assertThat(runnerException).hasCauseThat().isInstanceOf(StoppedByUserException::class.java)

        executor.shutdownNow()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCancelRunBeforeStarting() {
        val testRan: AtomicBoolean = AtomicBoolean(false)

        // A runner that should never run its test
        val runner = FakeRunner("shouldNotRun", object : java.lang.Runnable {
            override fun run() {
                testRan.set(true)
            }
        })

        val request: org.junit.runner.Request =
            cancellableRequestFactory.createRequest(org.junit.runner.Request.runner(runner))
        cancellableRequestFactory.cancelRun()
        val core: JUnitCore = JUnitCore()

        val e: java.lang.RuntimeException? = org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable { core.run(request) })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Test run interrupted")
        Truth.assertThat(e).hasCauseThat().isInstanceOf(StoppedByUserException::class.java)

        Truth.assertThat(testRan.get()).isFalse()
    }

    @org.junit.Test
    fun testNormalRun() {
        val testRan: AtomicBoolean = AtomicBoolean(false)

        // A runner that should run its test
        val runner = FakeRunner("shouldRun", object : java.lang.Runnable {
            override fun run() {
                testRan.set(true)
            }
        })

        val request: org.junit.runner.Request =
            cancellableRequestFactory.createRequest(org.junit.runner.Request.runner(runner))
        val core: JUnitCore = JUnitCore()
        val result: org.junit.runner.Result = core.run(request)

        Truth.assertThat(testRan.get()).isTrue()
        Truth.assertThat(result.getRunCount()).isEqualTo(1)
        Truth.assertThat(result.getFailureCount()).isEqualTo(0)
    }

    @org.junit.Test
    fun testFailingRun() {
        val testRan: AtomicBoolean = AtomicBoolean(false)
        val expectedFailure: java.lang.RuntimeException = java.lang.RuntimeException()

        // A runner that should run its test
        val runner = FakeRunner("shouldRun", object : java.lang.Runnable {
            override fun run() {
                testRan.set(true)
                throw expectedFailure
            }
        })

        val request: org.junit.runner.Request =
            cancellableRequestFactory.createRequest(org.junit.runner.Request.runner(runner))
        val core: JUnitCore = JUnitCore()
        val result: org.junit.runner.Result = core.run(request)

        Truth.assertThat(testRan.get()).isTrue()
        Truth.assertThat(result.getRunCount()).isEqualTo(1)
        Truth.assertThat(result.getFailureCount()).isEqualTo(1)
        Truth.assertThat(result.getFailures().get(0).getException()).isSameInstanceAs(expectedFailure)
    }


    private class FakeRunner(testName: String?, test: java.lang.Runnable) : org.junit.runner.Runner() {
        private val testDescription: org.junit.runner.Description
        private val test: java.lang.Runnable

        init {
            this.test = test
            testDescription = org.junit.runner.Description.createTestDescription(FakeRunner::class.java, testName)
        }

        val description: org.junit.runner.Description
            get() = testDescription

        override fun run(notifier: RunNotifier) {
            notifier.fireTestStarted(testDescription)

            try {
                test.run()
            } catch (e: org.junit.internal.AssumptionViolatedException) {
                notifier.fireTestAssumptionFailed(org.junit.runner.notification.Failure(testDescription, e))
            } catch (e: Throwable) {
                notifier.fireTestFailure(org.junit.runner.notification.Failure(testDescription, e))
            } finally {
                notifier.fireTestFinished(testDescription)
            }
        }
    }

    class FakeSuite

    class RunnerSuite(vararg runners: org.junit.runner.Runner?) :
        Suite(FakeSuite::class.java, java.util.Arrays.asList<org.junit.runner.Runner?>(*runners))
}
