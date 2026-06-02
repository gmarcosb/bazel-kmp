// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.testbed

import junit.framework.TestCase
import java.util.concurrent.TimeUnit

/**
 * This is a testbed for testing stack trace functionality. Failures in this test should not cause
 * continuous builds to go red.
 */
class StackTraceExercises : TestCase() {
    /** Succeeds fast but leaves behind a devious shutdown hook designed to wreak havoc.  */
    fun testSneakyShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread(Runnable { handleHook() }))
    }

    /** A test which invokes System.exit(0). Bad test!  */
    fun testNotSoFastBuddy() {
        println("Hey, not so fast there")
        System.exit(0)
    }

    companion object {
        private fun handleHook() {
            try {
                println("Entered shutdown hook")
                System.out.flush()
                Thread.ofVirtual()
                    .name("my-virtual-thread")
                    .start(
                        Runnable {
                            try {
                                TimeUnit.HOURS.sleep(1)
                            } catch (e: InterruptedException) {
                                println("Virtual thread interrupted")
                                System.out.flush()
                            }
                        })
                Fifo.waitUntilDataAvailable()
                Thread.sleep(15000)
            } catch (e: Exception) {
                throw Error(e)
            }
        }
    }
}
