// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.model

import com.google.common.truth.Truth
import com.google.testing.junit.runner.model.TestInstantUtil
import com.google.testing.junit.runner.model.TestInterval
import com.google.testing.junit.runner.model.TestInterval.endMillis
import com.google.testing.junit.runner.model.TestInterval.startInstantToString
import com.google.testing.junit.runner.model.TestInterval.startMillis
import com.google.testing.junit.runner.model.TestInterval.toDurationMillis
import com.google.testing.junit.runner.util.TestClock.TestInstant
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.time.Instant

@RunWith(JUnit4::class)
class TestIntervalTest {
    @org.junit.Rule
    var thrown: org.junit.rules.ExpectedException = org.junit.rules.ExpectedException.none()

    @org.junit.Test
    fun testCreation() {
        val start: Instant = Instant.ofEpochMilli(123456)
        val end: Instant = Instant.ofEpochMilli(234567)
        var interval: TestInterval = TestInterval(TestInstantUtil.testInstant(start), TestInstantUtil.testInstant(end))
        Truth.assertThat(interval.startMillis).isEqualTo(123456)
        Truth.assertThat(interval.endMillis).isEqualTo(234567)

        interval = TestInterval(TestInstantUtil.testInstant(start), TestInstantUtil.testInstant(start))
        Truth.assertThat(interval.startMillis).isEqualTo(123456)
        Truth.assertThat(interval.endMillis).isEqualTo(123456)
    }

    @org.junit.Test
    fun testCreationFailure() {
        thrown.expect(java.lang.IllegalArgumentException::class.java)
        thrown.expectMessage("Start must be before end")
        TestInterval(
            TestInstantUtil.testInstant(Instant.ofEpochMilli(35)),
            TestInstantUtil.testInstant(Instant.ofEpochMilli(23))
        )
    }

    @org.junit.Test
    fun testToDuration() {
        Truth.assertThat(
            TestInterval(
                TestInstantUtil.testInstant(Instant.ofEpochMilli(50)),
                TestInstantUtil.testInstant(Instant.ofEpochMilli(150))
            )
                .toDurationMillis()
        )
            .isEqualTo(100)
        Truth.assertThat(
            TestInterval(
                TestInstantUtil.testInstant(Instant.ofEpochMilli(100)),
                TestInstantUtil.testInstant(Instant.ofEpochMilli(100))
            )
                .toDurationMillis()
        )
            .isEqualTo(0)
    }

    @org.junit.Test
    fun testToDurationOnNonMonotonicWallTime() {
        val start: Instant = Instant.ofEpochMilli(123456)
        val end: Instant = Instant.ofEpochMilli(123456)
        val monotonicStart: java.time.Duration? = java.time.Duration.ofMillis(50)
        val monotonicEnd: java.time.Duration? = java.time.Duration.ofMillis(150)
        val interval: TestInterval =
            TestInterval(
                TestInstant(start, monotonicStart), TestInstant(end, monotonicEnd)
            )
        Truth.assertThat(interval.startMillis).isEqualTo(123456)
        Truth.assertThat(interval.endMillis).isEqualTo(123456)
        Truth.assertThat(interval.toDurationMillis()).isEqualTo(100)
    }

    @org.junit.Test
    fun testDateFormat() {
        val start: Instant = Instant.ofEpochMilli(1471709734000L)
        val end: Instant? = start.plusMillis(100)
        val interval: TestInterval = TestInterval(TestInstantUtil.testInstant(start), TestInstantUtil.testInstant(end))
        Truth.assertThat(interval.startInstantToString()).isEqualTo("2016-08-20T16:15:34.000Z")
    }
}
