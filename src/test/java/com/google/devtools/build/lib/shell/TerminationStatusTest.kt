// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.ActionExecutionContextBuilder.build
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.exec.util.TestExecutorBuilder.build
import com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [TerminationStatus].  */
@RunWith(JUnit4::class)
class TerminationStatusTest {
    @org.junit.Test
    fun testCrashed_exitCodesReturnFalse() {
        assertThat(TerminationStatus.crashed(0)).isFalse()
        assertThat(TerminationStatus.crashed(1)).isFalse()
        assertThat(TerminationStatus.crashed(127)).isFalse()
    }

    @org.junit.Test
    fun testCrashed_terminationSignalsReturnFalse() {
        assertThat(TerminationStatus.crashed(TerminationStatus.SIGNAL_1)).isFalse()
        assertThat(TerminationStatus.crashed(TerminationStatus.SIGNAL_63)).isFalse()
        assertThat(TerminationStatus.crashed(TerminationStatus.SIGNAL_SIGKILL)).isFalse()
        assertThat(TerminationStatus.crashed(TerminationStatus.SIGNAL_SIGTERM)).isFalse()
    }

    @org.junit.Test
    fun testCrashed_abruptSignalsReturnTrue() {
        assertThat(TerminationStatus.crashed(TerminationStatus.SIGNAL_SIGABRT)).isTrue()
        assertThat(TerminationStatus.crashed(TerminationStatus.SIGNAL_SIGBUS)).isTrue()
    }

    @org.junit.Test
    fun testBuilder_withNoWaitResponse() {
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { TerminationStatus.builder().setTimedOut(false).build() })
    }

    @org.junit.Test
    fun testBuilder_withNoTimedOut() {
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { TerminationStatus.builder().setWaitResponse(0).build() })
    }

    @org.junit.Test
    fun testBuilder_withNoExecutionTime() {
        val terminationStatus: TerminationStatus =
            TerminationStatus.builder().setWaitResponse(0).setTimedOut(false).build()
        assertThat(terminationStatus.getWallExecutionTime()).isEmpty()
        assertThat(terminationStatus.getUserExecutionTime()).isEmpty()
        assertThat(terminationStatus.getSystemExecutionTime()).isEmpty()
    }

    @org.junit.Test
    fun testBuilder_withExecutionTime() {
        val terminationStatus: TerminationStatus =
            TerminationStatus.builder()
                .setWaitResponse(0)
                .setTimedOut(false)
                .setWallExecutionTime(java.time.Duration.ofMillis(1929))
                .setUserExecutionTime(java.time.Duration.ofMillis(1492))
                .setSystemExecutionTime(java.time.Duration.ofMillis(1787))
                .build()
        assertThat(terminationStatus.getWallExecutionTime()).isPresent()
        assertThat(terminationStatus.getWallExecutionTime()).hasValue(java.time.Duration.ofMillis(1929))
        assertThat(terminationStatus.getUserExecutionTime()).isPresent()
        assertThat(terminationStatus.getUserExecutionTime()).hasValue(java.time.Duration.ofMillis(1492))
        assertThat(terminationStatus.getSystemExecutionTime()).isPresent()
        assertThat(terminationStatus.getSystemExecutionTime()).hasValue(java.time.Duration.ofMillis(1787))
    }
}
