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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.ActionExecutionInactivityWatchdog.InactivityMonitor

/** Tests for ActionExecutionInactivityWatchdog.  */
@RunWith(JUnit4::class)
class ActionExecutionInactivityWatchdogTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInactivityWatchdogReportsWhenItShould() {
        assertInactivityWatchdogReports(true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInactivityWatchdogDoesNotReportWhenItShouldNot() {
        assertInactivityWatchdogReports(false)
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun assertInactivityWatchdogReports(shouldReport: Boolean) {
            // The monitor implementation below is a state machine. This variable indicates which state
            // it is in.
            val monitorState = intArrayOf(0)

            // Object that the test thread will wait on.
            val monitorFinishedIndicator = Any()

            // Reported number of action completions in each call to waitForNextCompletion.
            val actionCompletions = intArrayOf(1, 0, 3, 0, 0, 0, 0, 2)

            // Simulated delay of action completions in each call to waitForNextCompletion.
            val waits = intArrayOf(5, 10, 3, 10, 30, 60, 60, 1)

            val clock: com.google.devtools.build.lib.testutil.ManualClock =
                com.google.devtools.build.lib.testutil.ManualClock()
            val start: Instant? = clock.now()

            // Log of all Sleep.sleep and InactivityMonitor.waitForNextCompletion calls.
            val sleepsAndWaits: MutableList<String?> = java.util.ArrayList<String?>()
            val inactivityReports: MutableList<String?> = java.util.ArrayList<String?>()

            // Mock monitor for this test.
            val monitor: InactivityMonitor =
                object : InactivityMonitor() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun waitForNextCompletion(timeoutSeconds: Int): Int {
                        // Simulate the following sequence of events (see actionCompletions):
                        // 1. return in 5s (within timeout), 1 action completed; caller will sleep
                        // 2. return in 10s (after timeout), 0 action completed; caller will wait
                        // 3. return in 3s (within timeout), 3 actions completed (this is possible, since the
                        //    waiting (thread doesn't necessarily wake up immediately); caller will sleep
                        // 4. return in 10s (after timeout), 0 action completed; caller will wait 30s
                        // 5. return in 30s (after timeout), 0 action completed still; caller will wait 60s
                        // 6. return in 60s (after timeout), 0 action completed still; caller will wait 60s
                        // 7. return in 60s (after timeout), 0 action completed still; caller will wait 60s
                        // 8. return in 1s (within timeout), 2 actions completed; caller will sleep, but we
                        //    won't record that, because monitorState reached its maximum
                        synchronized(monitorFinishedIndicator) {
                            if (monitorState[0] >= actionCompletions.size) {
                                // Notify the test thread that the test is over.
                                (monitorFinishedIndicator as java.lang.Object).notify()
                                return 1
                            } else {
                                val index = monitorState[0]
                                val wait = waits[index]
                                clock.advance(java.time.Duration.ofSeconds(wait.toLong()))
                                sleepsAndWaits.add("wait:" + wait)
                                ++monitorState[0]
                                return actionCompletions[index]
                            }
                        }
                    }

                    public override fun hasStarted(): Boolean {
                        return true
                    }

                    val pending: Int
                        get() {
                            var index = monitorState[0]
                            if (index >= actionCompletions.size) {
                                return 0
                            }
                            var result = actionCompletions[index]
                            while (result == 0) {
                                ++index
                                result = actionCompletions[index]
                            }
                            return result
                        }
                }

            val didReportInactivity = booleanArrayOf(false)
            val reporter: InactivityReporter =
                object : InactivityReporter() {
                    public override fun maybeReportInactivity(lastActionCompletedAt: Instant?) {
                        val sinceStart: java.time.Duration = java.time.Duration.between(start, lastActionCompletedAt)
                        inactivityReports.add("inactivity:" + sinceStart.toSeconds())
                        if (shouldReport) {
                            didReportInactivity[0] = true
                        }
                    }
                }

            // Mock sleep object; just logs how much the caller's thread would've slept.
            val sleep: ActionExecutionInactivityWatchdog.Sleep =
                object : Sleep() {
                    @Throws(java.lang.InterruptedException::class)
                    public override fun sleep(durationMilliseconds: Int) {
                        if (monitorState[0] < actionCompletions.size) {
                            sleepsAndWaits.add("sleep:" + durationMilliseconds)
                            clock.advance(java.time.Duration.ofMillis(durationMilliseconds.toLong()))
                        }
                    }
                }

            val watchdog: ActionExecutionInactivityWatchdog =
                ActionExecutionInactivityWatchdog(monitor, reporter, 0, sleep, clock)
            try {
                synchronized(monitorFinishedIndicator) {
                    watchdog.start()
                    val startTime: Long = java.lang.System.currentTimeMillis()
                    var done = false
                    while (!done) {
                        try {
                            (monitorFinishedIndicator as java.lang.Object).wait(5000)
                            done = true
                            Truth.assertWithMessage("test didn't finish under 5 seconds")
                                .that(java.lang.System.currentTimeMillis() - startTime)
                                .isLessThan(5000L)
                        } catch (ie: java.lang.InterruptedException) {
                            // so-called Spurious Wakeup; ignore
                        }
                    }
                }
            } finally {
                watchdog.stop()
            }

            Truth.assertThat(didReportInactivity[0]).isEqualTo(shouldReport)
            Truth.assertThat(sleepsAndWaits)
                .containsExactly(
                    "wait:5",
                    "sleep:1000",
                    "wait:10",
                    "wait:3",
                    "sleep:1000",
                    "wait:10",
                    "wait:30",
                    "wait:60",
                    "wait:60",
                    "wait:1"
                )
                .inOrder()
            Truth.assertThat(inactivityReports)
                .containsExactly(
                    "inactivity:5", "inactivity:19", "inactivity:19", "inactivity:19", "inactivity:19"
                )
                .inOrder()
        }
    }
}
