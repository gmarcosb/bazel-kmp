// Copyright 2014 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.clock.Clock.currentTimeMillis
import com.google.devtools.build.lib.clock.Clock.nanoTime
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests for the Clock instance based on the Java System class.
 */
@RunWith(JUnit4::class)
class JavaClockTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaClockIsAdvancing() {
        val clock: com.google.devtools.build.lib.clock.Clock = com.google.devtools.build.lib.clock.JavaClock()
        val millis: Long = clock.currentTimeMillis()
        val nanos: Long = clock.nanoTime()

        java.lang.Thread.sleep(10)

        Truth.assertThat(clock.currentTimeMillis()).isNotEqualTo(millis)
        Truth.assertThat(clock.nanoTime()).isNotEqualTo(nanos)
    }
}
