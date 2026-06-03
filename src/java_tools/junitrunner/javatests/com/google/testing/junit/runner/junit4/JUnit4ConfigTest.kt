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
package com.google.testing.junit.runner.junit4

import com.google.common.truth.Truth
import com.google.testing.junit.runner.junit4.JUnit4Config
import com.google.testing.junit.runner.junit4.JUnit4Config.jUnitRunnerApiVersion
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.Properties

/**
 * Tests for [JUnit4Config].
 */
@RunWith(JUnit4::class)
class JUnit4ConfigTest {
    private fun createConfigWithApiVersion(apiVersion: String?): JUnit4Config {
        val properties: Properties = Properties()
        properties.put(JUnit4Config.Companion.JUNIT_API_VERSION_PROPERTY, apiVersion)
        return createConfigWithProperties(properties)
    }

    private fun createConfigWithProperties(properties: Properties): JUnit4Config {
        return JUnit4Config("", null, null, properties)
    }

    @org.junit.Test
    fun testGetJUnitRunnerApiVersion_defaultValue() {
        val config: JUnit4Config = createConfigWithApiVersion("1")
        Truth.assertThat(config.jUnitRunnerApiVersion).isEqualTo(1)
    }

    @org.junit.Test
    fun testGetJUnitRunnerApiVersion_failsIfNotNumeric() {
        val config: JUnit4Config = createConfigWithApiVersion("I love pesto")

        val expected: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { config.jUnitRunnerApiVersion })
        Truth.assertThat(expected).hasMessageThat().contains("I love pesto")
    }

    @org.junit.Test
    fun testGetJUnitRunnerApiVersion_failsIfNotAnInteger() {
        val config: JUnit4Config = createConfigWithApiVersion("3.14")

        val expected: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { config.jUnitRunnerApiVersion })
        Truth.assertThat(expected).hasMessageThat().contains("3.14")
    }

    @org.junit.Test
    fun testGetJUnitRunnerApiVersion_failsIfNotOne() {
        val config: JUnit4Config = createConfigWithApiVersion("13")

        val expected: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { config.jUnitRunnerApiVersion })
        Truth.assertThat(expected).hasMessageThat().contains("13")
    }

    @org.junit.Test
    fun testGetJUnitRunnerApiVersion_oneIsValid() {
        val config: JUnit4Config = createConfigWithApiVersion("1")
        Truth.assertThat(config.jUnitRunnerApiVersion).isEqualTo(1)
    }
}
