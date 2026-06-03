// Copyright 2011 The Bazel Authors. All Rights Reserved.
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
import com.google.testing.junit.runner.internal.junit4.JUnit4TestNameListener
import com.google.testing.junit.runner.internal.junit4.JUnit4TestNameListener.testFinished
import com.google.testing.junit.runner.internal.junit4.JUnit4TestNameListener.testRunStarted
import com.google.testing.junit.runner.internal.junit4.JUnit4TestNameListener.testStarted
import com.google.testing.junit.runner.internal.junit4.JUnit4TestXmlListener.testRunStarted
import com.google.testing.junit.runner.internal.junit4.SettableCurrentRunningTest
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.google.testing.junit.runner.util.TestNameProvider
import org.junit.Before
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Tests for [JUnit4TestNameListener].
 */
@RunWith(JUnit4::class)
class JUnit4TestNameListenerTest {
    private var testNameListener: JUnit4TestNameListener? = null
    private var testNameProviderForTesting: TestNameProvider? = null

    @org.junit.Rule
    var name: TestName = TestName()

    @Before
    fun setCurrentRunningTest() {
        val currentRunningTest: SettableCurrentRunningTest = object : SettableCurrentRunningTest() {
            public override fun setGlobalTestNameProvider(provider: TestNameProvider) {
                testNameProviderForTesting = provider
            }
        }

        testNameListener = JUnit4TestNameListener(currentRunningTest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJUnit4Listener_normalUsage() {
        Truth.assertThat(testNameProviderForTesting).isNull()

        var description: org.junit.runner.Description =
            org.junit.runner.Description.createSuiteDescription(FakeTest::class.java)
        testNameListener.testRunStarted(description)
        Truth.assertThat(testNameProviderForTesting.get()).isNull()

        description = org.junit.runner.Description.createTestDescription(FakeTest::class.java, "methodName")
        testNameListener.testStarted(description)
        Truth.assertThat(testNameProviderForTesting.get()).isEqualTo(description)
        testNameListener.testFinished(description)
        Truth.assertThat(testNameProviderForTesting.get()).isNull()

        description = org.junit.runner.Description.createTestDescription(FakeTest::class.java, "anotherMethodName")
        testNameListener.testStarted(description)
        Truth.assertThat(testNameProviderForTesting.get()).isEqualTo(description)
        testNameListener.testFinished(description)
        Truth.assertThat(testNameProviderForTesting.get()).isNull()

        testNameListener.testRunFinished(null)
        Truth.assertThat(testNameProviderForTesting.get()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJUnit4Listener_hasExpectedDisplayName() {
        var description: org.junit.runner.Description =
            org.junit.runner.Description.createSuiteDescription(FakeTest::class.java)
        testNameListener.testRunStarted(description)

        description = org.junit.runner.Description.createTestDescription(this.javaClass, name.getMethodName())
        testNameListener.testStarted(description)
        Truth.assertThat(testNameProviderForTesting.get().getDisplayName())
            .isEqualTo(
                ("testJUnit4Listener_hasExpectedDisplayName("
                        + JUnit4TestNameListenerTest::class.java.getCanonicalName()
                        + ")")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJUnit4Listener_multipleThreads() {
        val executorService: ExecutorService = Executors.newSingleThreadExecutor()
        val description1: org.junit.runner.Description =
            org.junit.runner.Description.createTestDescription(FakeTest::class.java, "methodName")
        val description2: org.junit.runner.Description =
            org.junit.runner.Description.createTestDescription(FakeTest::class.java, "anotherMethodName")

        testNameListener.testRunStarted(org.junit.runner.Description.createSuiteDescription(FakeTest::class.java))
        Truth.assertThat(testNameProviderForTesting.get()).isNull()
        testNameListener.testStarted(description1)
        Truth.assertThat(testNameProviderForTesting.get()).isEqualTo(description1)

        val startSecondTestFuture: java.util.concurrent.Future<*> =
            executorService.submit(
                object : java.lang.Runnable {
                    override fun run() {
                        Truth.assertThat(testNameProviderForTesting.get()).isNull()
                        try {
                            testNameListener.testStarted(description2)
                        } catch (e: java.lang.Exception) {
                            com.google.common.base.Throwables.throwIfUnchecked(e)
                            throw java.lang.RuntimeException(e)
                        }
                        Truth.assertThat(testNameProviderForTesting.get()).isEqualTo(description2)
                    }
                })
        startSecondTestFuture.get()

        Truth.assertThat(testNameProviderForTesting.get()).isEqualTo(description1)
        testNameListener.testFinished(description1)
        Truth.assertThat(testNameProviderForTesting.get()).isNull()

        val endSecondTestFuture: java.util.concurrent.Future<*> =
            executorService.submit(
                object : java.lang.Runnable {
                    override fun run() {
                        Truth.assertThat(testNameProviderForTesting.get()).isEqualTo(description2)
                        try {
                            testNameListener.testFinished(description2)
                        } catch (e: java.lang.Exception) {
                            com.google.common.base.Throwables.throwIfUnchecked(e)
                            throw java.lang.RuntimeException(e)
                        }
                        Truth.assertThat(testNameProviderForTesting.get()).isNull()
                    }
                })
        endSecondTestFuture.get()

        Truth.assertThat(testNameProviderForTesting.get()).isNull()
    }

    /**
     * Typically, [junit.framework.TestListener.startTest]
     * and [junit.framework.TestListener.endTest]
     * should be called in pairs, but if they're not for some reason, the
     * listener will try to handle it as best as possible.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testJUnit4Listener_invalidStatesAreHandled() {
        testNameListener.testRunStarted(org.junit.runner.Description.createSuiteDescription(FakeTest::class.java))

        val description1: org.junit.runner.Description =
            org.junit.runner.Description.createTestDescription(FakeTest::class.java, "methodName")
        val description2: org.junit.runner.Description =
            org.junit.runner.Description.createTestDescription(FakeTest::class.java, "anotherMethodName")

        testNameListener.testStarted(description1)
        testNameListener.testStarted(description1)
        Truth.assertThat(testNameProviderForTesting.get()).isEqualTo(description1)

        testNameListener.testStarted(description2)
        Truth.assertThat(testNameProviderForTesting.get()).isEqualTo(description2)

        testNameListener.testFinished(description1)
        Truth.assertThat(testNameProviderForTesting.get()).isNull()

        testNameListener.testFinished(description2)
        Truth.assertThat(testNameProviderForTesting.get()).isNull()
    }


    private class FakeTest
}
