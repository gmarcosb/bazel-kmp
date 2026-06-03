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
package com.google.devtools.build.lib.shell

import com.google.devtools.build.lib.shell.Consumers.OutErrConsumers

/**
 * Tests that InterruptedExceptions can't derail FutureConsumption
 * instances; well, FutureConsumption is really an implementation detail,
 * but we want to exercise this code, so what ...
 */
@RunWith(JUnit4::class)
class FutureConsumptionTest {
    @Before
    @Throws(java.lang.Exception::class)
    fun configureLogger() {
        // enable all log statements to ensure there are no problems with
        // logging code
        java.util.logging.Logger.getLogger("com.google.devtools.build.lib.shell.Command")
            .setLevel(java.util.logging.Level.FINEST)
    }

    private val DEV_NULL: java.io.OutputStream = object : java.io.OutputStream() {
        override fun write(b: Int) {}
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFutureConsumptionIgnoresInterruptedExceptions() {
        // Set this up so that the consumer actually have to stream stuff into
        // DEV_NULL, which the discards everything.
        val outErr: OutErrConsumers = Consumers.createStreamingConsumers(
            DEV_NULL,
            DEV_NULL
        )

        val inputFinished: AtomicBoolean = AtomicBoolean(false)

        // We keep producing input until the other thread (the main test thread)
        // tells us to shut up ...
        val outInput: java.io.InputStream = object : java.io.InputStream() {
            override fun read(): Int {
                if (inputFinished.get()) {
                    return -1
                }
                return 0
            }
        }
        val errInput: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        outErr.registerInputs(outInput, errInput, false)
        // OK, this is the main test thread, which we need to interrupt *while*
        // it's waiting in outErr.waitForCompletion()
        val testThread: java.lang.Thread = java.lang.Thread.currentThread()

        // go into a different thread, wait a bit, interrupt the test thread,
        // wait a bit, and tell the input stream to finish.
        object : java.lang.Thread() {
            override fun run() {
                try {
                    java.lang.Thread.sleep(1000)
                } catch (e: java.lang.InterruptedException) {
                }
                testThread.interrupt() // this is what we're testing; basic
                try {
                    java.lang.Thread.sleep(1000)
                } catch (e: java.lang.InterruptedException) {
                }
                inputFinished.set(true)
            }
        }.start()

        outErr.waitForCompletion()
        // In addition to asserting that we were interrupted, this clears the interrupt bit of the
        // current thread, since Junit doesn't do it for us. This avoids the next test to run starting
        // in an interrupted state.
        Truth.assertThat(java.lang.Thread.interrupted()).isTrue()
    }
}
