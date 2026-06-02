// Copyright 2015 The Bazel Authors. All Rights Reserved.
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

import junit.framework.Test
import java.util.concurrent.TimeUnit

/**
 * This is a testbed for testing stack trace functionality when the test runner is interrupted with
 * a TERM signal during the test suite creation phase.
 * 
 * 
 * Failures in this test should not cause continuous builds to go red.
 */
object SuiteMethodTakesForever {
    /**
     * Simulates a test suite that takes a really long time to build, giving enough time to the test
     * to send the TERM signal and verify the output.
     */
    @Throws(Exception::class)
    fun suite(): Test? {
        println("Entered suite creation")
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
        TimeUnit.HOURS.sleep(1)
        throw IllegalStateException(
            "Expected to be interrupted before finishing the suite creation"
        )
    }
}
