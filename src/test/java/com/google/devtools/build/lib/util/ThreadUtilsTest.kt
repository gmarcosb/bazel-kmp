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
package com.google.devtools.build.lib.util

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.bugreport.Crash
import com.google.devtools.build.lib.bugreport.CrashContext
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference

/** Tests for [ThreadUtils].  */
@RunWith(JUnit4::class)
class ThreadUtilsTest {
    // TODO(b/150299871): inspecting the output of GoogleLogger or mocking it seems too hard for now.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        val future: com.google.common.util.concurrent.SettableFuture<Int?> =
            com.google.common.util.concurrent.SettableFuture.create<Int?>()
        val numParkThreads = 11
        val waitForThreads: CountDownLatch = CountDownLatch(numParkThreads + 2)
        val parkThreads: MutableList<java.lang.Thread> = java.util.ArrayList<java.lang.Thread>(numParkThreads)
        for (i in 0..<numParkThreads) {
            parkThreads.add(
                java.lang.Thread(
                    java.lang.Runnable { recursiveMethodPark(0, future, waitForThreads) },
                    "parkthread" + i
                )
            )
        }
        val noParkRunnable: java.lang.Runnable = java.lang.Runnable { recursiveMethodNoPark(0, waitForThreads) }
        val noParkThread: java.lang.Thread = java.lang.Thread(noParkRunnable, "noparkthread1")
        val noParkThread2: java.lang.Thread = java.lang.Thread(noParkRunnable, "noparkthread2")
        val reportedException: AtomicReference<Throwable?> = AtomicReference<Throwable?>()
        val bugReporter: BugReporter =
            object : BugReporter() {
                override fun sendBugReport(exception: Throwable?, args: MutableList<String?>?, vararg values: String?) {
                    Truth.assertThat(reportedException.get()).isNull()
                    reportedException.set(exception)
                }

                override fun sendNonFatalBugReport(exception: Throwable?) {
                    throw java.lang.UnsupportedOperationException()
                }

                override fun handleCrash(crash: Crash?, ctx: CrashContext?) {
                    BugReporter.defaultInstance().handleCrash(crash, ctx)
                }
            }
        parkThreads.forEach(java.util.function.Consumer { obj: java.lang.Thread? -> obj.start() })
        noParkThread.start()
        noParkThread2.start()
        waitForThreads.await()
        ThreadUtils.warnAboutSlowInterrupt("interrupt message", bugReporter)
        Truth.assertThat(reportedException.get())
            .hasCauseThat()
            .hasMessageThat()
            .isEqualTo("(Wrapper exception for longest stack trace) interrupt message")
        // The topmost method is either "sleep" or "sleep0" or "sleepNanos0". For example, in JDK 21,
        // "Thread.sleep" calls "sleepNanos" which then calls a "sleepNanos0" native method.
        val stackTrace: Array<java.lang.StackTraceElement?> = reportedException.get().cause.getStackTrace()
        if (stackTrace[0].getMethodName() == "sleepNanos0") {
            Truth.assertThat(stackTrace[1].getMethodName()).isEqualTo("sleepNanos")
            Truth.assertThat(stackTrace[2].getMethodName()).isEqualTo("sleep")
            Truth.assertThat(stackTrace[3].getMethodName()).isEqualTo("recursiveMethodNoPark")
        } else if (stackTrace[0].getMethodName() == "sleep0") {
            Truth.assertThat(stackTrace[1].getMethodName()).isEqualTo("sleep")
            Truth.assertThat(stackTrace[2].getMethodName()).isEqualTo("recursiveMethodNoPark")
        } else {
            Truth.assertThat(stackTrace[0].getMethodName()).isEqualTo("sleep")
            Truth.assertThat(stackTrace[1].getMethodName()).isEqualTo("recursiveMethodNoPark")
        }

        future.set(1)
        for (thread in parkThreads) {
            thread.join()
        }
        noParkThread.interrupt()
        noParkThread.join()
        noParkThread2.interrupt()
        noParkThread2.join()
    }

    companion object {
        private fun recursiveMethodPark(
            depth: Int, future: com.google.common.util.concurrent.SettableFuture<Int?>, waitForThreads: CountDownLatch
        ) {
            if (depth < 100) {
                recursiveMethodPark(depth + 1, future, waitForThreads)
                return
            }
            waitForThreads.countDown()
            try {
                future.get()
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: ExecutionException) {
                throw java.lang.IllegalStateException(e)
            }
        }

        private fun recursiveMethodNoPark(depth: Int, waitForThreads: CountDownLatch) {
            if (depth < 50) {
                recursiveMethodNoPark(depth + 1, waitForThreads)
                return
            }
            waitForThreads.countDown()
            try {
                java.lang.Thread.sleep(Long.Companion.MAX_VALUE)
            } catch (e: java.lang.InterruptedException) {
                // Ignored.
            }
        }
    }
}
