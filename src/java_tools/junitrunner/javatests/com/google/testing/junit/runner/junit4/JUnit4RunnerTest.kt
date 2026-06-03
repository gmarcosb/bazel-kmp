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
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment.getDescription
import com.google.testing.junit.runner.internal.junit4.CancellableRequestFactory
import com.google.testing.junit.runner.internal.junit4.CancellableRequestFactory.cancelRun
import com.google.testing.junit.runner.internal.junit4.JUnit4TestNameListener.testFinished
import com.google.testing.junit.runner.internal.junit4.JUnit4TestNameListener.testRunStarted
import com.google.testing.junit.runner.internal.junit4.JUnit4TestNameListener.testStarted
import com.google.testing.junit.runner.internal.junit4.JUnit4TestXmlListener.testRunStarted
import com.google.testing.junit.runner.junit4.JUnit4Bazel
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.suiteClass
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4BazelMock
import com.google.testing.junit.runner.junit4.JUnit4Config
import com.google.testing.junit.runner.junit4.JUnit4Runner
import com.google.testing.junit.runner.junit4.JUnit4Runner.model
import com.google.testing.junit.runner.junit4.JUnit4Runner.run
import com.google.testing.junit.runner.junit4.JUnit4RunnerBaseModule
import com.google.testing.junit.runner.junit4.JUnit4RunnerBaseModule.provideTextListener
import com.google.testing.junit.runner.junit4.JUnit4RunnerModule
import com.google.testing.junit.runner.junit4.JUnit4RunnerTest
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.google.testing.junit.runner.model.TestNode.testFailure
import com.google.testing.junit.runner.model.TestSuiteModel
import com.google.testing.junit.runner.model.TestSuiteModel.getNumTestCases
import com.google.testing.junit.runner.model.TestSuiteModel.testFailure
import com.google.testing.junit.runner.model.TestSuiteNode.testFailure
import com.google.testing.junit.runner.model.XmlWriter.close
import com.google.testing.junit.runner.sharding.ShardingEnvironment
import com.google.testing.junit.runner.sharding.ShardingFilters
import com.google.testing.junit.runner.sharding.testing.FakeShardingFilters
import com.google.testing.junit.runner.util.FakeTestClock
import com.google.testing.junit.runner.util.TestClock
import net.starlark.java.syntax.Identifier.getName
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.StoppedByUserException
import org.junit.runners.JUnit4
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses
import org.mockito.ArgumentMatchers
import org.mockito.InOrder
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.stubbing.Answer
import java.io.PrintStream
import java.util.Collections
import java.util.HashSet
import java.util.Properties

/**
 * Tests for [JUnit4Runner]
 */
@RunWith(MockitoJUnitRunner::class)
class JUnit4RunnerTest {
    private val stdoutByteStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
    private val stdoutPrintStream: PrintStream = PrintStream(stdoutByteStream, true)
    private var mockRunListener: RunListener? = null
    private var shardingEnvironment: ShardingEnvironment =
        com.google.testing.junit.runner.junit4.JUnit4RunnerTest.StubShardingEnvironment()
    private var shardingFilters: ShardingFilters? = null
    private var config: JUnit4Config? = null

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun closeStream() {
        stdoutPrintStream.close()
    }

    private fun createRunner(suiteClass: java.lang.Class<*>?): JUnit4Runner {
        return createComponent(suiteClass, CancellableRequestFactory()).runner()
    }

    private fun createComponent(
        suiteClass: java.lang.Class<*>?, cancellableRequestFactory: CancellableRequestFactory
    ): JUnit4Bazel {
        return JUnit4BazelMock.builder()
            .suiteClass(suiteClass)
            .testModule(
                TestModule(
                    cancellableRequestFactory
                )
            ) // instance method to support outer-class instance
            // variables.
            .build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPassingTest() {
        config = createConfig()
        mockRunListener = Mockito.mock<RunListener?>(RunListener::class.java)

        val runner: JUnit4Runner = createRunner(SamplePassingTest::class.java)

        val testDescription: org.junit.runner.Description =
            org.junit.runner.Description.createTestDescription(SamplePassingTest::class.java, "testThatAlwaysPasses")
        val suiteDescription: org.junit.runner.Description =
            org.junit.runner.Description.createSuiteDescription(SamplePassingTest::class.java)
        suiteDescription.addChild(testDescription)

        val result: org.junit.runner.Result? = runner.run()

        Truth.assertThat(result.getRunCount()).isEqualTo(1)
        Truth.assertThat(result.getFailureCount()).isEqualTo(0)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)

        assertPassingTestHasExpectedOutput(stdoutByteStream, SamplePassingTest::class.java)

        val inOrder: InOrder = Mockito.inOrder(mockRunListener)

        inOrder.verify<RunListener?>(mockRunListener).testRunStarted(suiteDescription)
        inOrder.verify<RunListener?>(mockRunListener).testStarted(testDescription)
        inOrder.verify<RunListener?>(mockRunListener).testFinished(testDescription)
        inOrder.verify<RunListener?>(mockRunListener)
            .testRunFinished(ArgumentMatchers.any<org.junit.runner.Result?>(org.junit.runner.Result::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailingTest() {
        config = createConfig()
        mockRunListener = Mockito.mock<RunListener?>(RunListener::class.java)

        val runner: JUnit4Runner = createRunner(SampleFailingTest::class.java)

        val testDescription: org.junit.runner.Description = org.junit.runner.Description.createTestDescription(
            SampleFailingTest::class.java,
            "testThatAlwaysFails"
        )
        val suiteDescription: org.junit.runner.Description =
            org.junit.runner.Description.createSuiteDescription(SampleFailingTest::class.java)
        suiteDescription.addChild(testDescription)

        val result: org.junit.runner.Result? = runner.run()

        Truth.assertThat(result.getRunCount()).isEqualTo(1)
        Truth.assertThat(result.getFailureCount()).isEqualTo(1)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)

        Truth.assertThat(extractOutput(stdoutByteStream))
            .contains(
                ("1) testThatAlwaysFails("
                        + SampleFailingTest::class.java.getName()
                        + ")\n"
                        + "java.lang.AssertionError: expected")
            )

        val inOrder: InOrder = Mockito.inOrder(mockRunListener)

        inOrder.verify<RunListener?>(mockRunListener)
            .testRunStarted(ArgumentMatchers.any<org.junit.runner.Description?>(org.junit.runner.Description::class.java))
        inOrder.verify<RunListener?>(mockRunListener)
            .testStarted(ArgumentMatchers.any<org.junit.runner.Description?>(org.junit.runner.Description::class.java))
        inOrder.verify<RunListener?>(mockRunListener)
            .testFailure(ArgumentMatchers.any<org.junit.runner.notification.Failure?>(org.junit.runner.notification.Failure::class.java))
        inOrder.verify<RunListener?>(mockRunListener)
            .testFinished(ArgumentMatchers.any<org.junit.runner.Description?>(org.junit.runner.Description::class.java))
        inOrder.verify<RunListener?>(mockRunListener)
            .testRunFinished(ArgumentMatchers.any<org.junit.runner.Result?>(org.junit.runner.Result::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailingInternationalCharsTest() {
        config = createConfig()
        mockRunListener = Mockito.mock<RunListener?>(RunListener::class.java)

        val runner: JUnit4Runner = createRunner(SampleInternationalFailingTest::class.java)

        val testDescription: org.junit.runner.Description = org.junit.runner.Description.createTestDescription(
            SampleInternationalFailingTest::class.java, "testFailingInternationalCharsTest"
        )
        val suiteDescription: org.junit.runner.Description = org.junit.runner.Description.createSuiteDescription(
            SampleInternationalFailingTest::class.java
        )
        suiteDescription.addChild(testDescription)

        val result: org.junit.runner.Result? = runner.run()

        Truth.assertThat(result.getRunCount()).isEqualTo(1)
        Truth.assertThat(result.getFailureCount()).isEqualTo(1)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)

        val output = String(stdoutByteStream.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
        // Intentionally swapped "Test 日\u672C." / "Test \u65E5本." to make sure that the "raw"
        // character does not get corrupted (would become ? in both cases and we would not notice).
        Truth.assertThat(output).contains("expected:<Test [Japan].> but was:<Test [日\u672C].>")

        val inOrder: InOrder = Mockito.inOrder(mockRunListener)

        inOrder.verify<RunListener?>(mockRunListener)
            .testRunStarted(ArgumentMatchers.any<org.junit.runner.Description?>(org.junit.runner.Description::class.java))
        inOrder.verify<RunListener?>(mockRunListener)
            .testStarted(ArgumentMatchers.any<org.junit.runner.Description?>(org.junit.runner.Description::class.java))
        inOrder.verify<RunListener?>(mockRunListener)
            .testFailure(ArgumentMatchers.any<org.junit.runner.notification.Failure?>(org.junit.runner.notification.Failure::class.java))
        inOrder.verify<RunListener?>(mockRunListener)
            .testFinished(ArgumentMatchers.any<org.junit.runner.Description?>(org.junit.runner.Description::class.java))
        inOrder.verify<RunListener?>(mockRunListener)
            .testRunFinished(ArgumentMatchers.any<org.junit.runner.Result?>(org.junit.runner.Result::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterruptedTest() {
        config = createConfig()
        mockRunListener = Mockito.mock<RunListener?>(RunListener::class.java)
        val requestFactory: CancellableRequestFactory = CancellableRequestFactory()
        val component: JUnit4Bazel = createComponent(
            com.google.testing.junit.runner.junit4.JUnit4RunnerTest.SampleSuite::class.java,
            requestFactory
        )
        val runner: JUnit4Runner = component.runner()

        val testDescription: org.junit.runner.Description = org.junit.runner.Description.createTestDescription(
            SamplePassingTest::class.java,
            "testThatAlwaysPasses"
        )

        Mockito.doAnswer(cancelTestRun(requestFactory))
            .`when`<RunListener?>(mockRunListener).testStarted(testDescription)

        val e: java.lang.RuntimeException = org.junit.Assert.assertThrows<java.lang.RuntimeException>(
            java.lang.RuntimeException::class.java,
            org.junit.function.ThrowingRunnable { runner.run() })
        Truth.assertThat(e).hasMessageThat().isEqualTo("Test run interrupted")
        Truth.assertWithMessage("Expected cause to be a StoppedByUserException")
            .that(e.cause is StoppedByUserException)
            .isTrue()

        val inOrder: InOrder = Mockito.inOrder(mockRunListener)
        inOrder.verify<RunListener?>(mockRunListener)
            .testRunStarted(ArgumentMatchers.any<org.junit.runner.Description?>(org.junit.runner.Description::class.java))
        inOrder.verify<RunListener?>(mockRunListener).testStarted(testDescription)
        inOrder.verify<RunListener?>(mockRunListener).testFinished(testDescription)
    }

    @org.junit.Test
    fun testShardingIsSupported() {
        config = createConfig()
        shardingEnvironment = Mockito.mock<ShardingEnvironment>(ShardingEnvironment::class.java)
        shardingFilters = FakeShardingFilters(
            org.junit.runner.Description.createTestDescription(SamplePassingTest::class.java, "testThatAlwaysPasses"),
            org.junit.runner.Description.createTestDescription(SampleFailingTest::class.java, "testThatAlwaysFails")
        )

        Mockito.`when`<Boolean?>(shardingEnvironment.isShardingEnabled()).thenReturn(true)

        val runner: JUnit4Runner =
            createRunner(com.google.testing.junit.runner.junit4.JUnit4RunnerTest.SampleSuite::class.java)
        val result: org.junit.runner.Result? = runner.run()

        Mockito.verify<ShardingEnvironment?>(shardingEnvironment).touchShardFile()

        Truth.assertThat(result.getRunCount()).isEqualTo(2)
        if (result.getFailureCount() > 1) {
            org.junit.Assert.fail("Too many failures: " + result.getFailures())
        }
        Truth.assertThat(result.getFailureCount()).isEqualTo(1)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)
        assertThat(runner.model.getNumTestCases()).isEqualTo(2)
    }

    @org.junit.Test
    fun testFilteringIsSupported() {
        config = createConfig("testThatAlwaysFails")
        val runner: JUnit4Runner =
            createRunner(com.google.testing.junit.runner.junit4.JUnit4RunnerTest.SampleSuite::class.java)
        val result: org.junit.runner.Result? = runner.run()

        Truth.assertThat(result.getRunCount()).isEqualTo(1)
        Truth.assertThat(result.getFailureCount()).isEqualTo(1)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)
        Truth.assertThat(result.getFailures().get(0).getDescription())
            .isEqualTo(
                org.junit.runner.Description.createTestDescription(SampleFailingTest::class.java, "testThatAlwaysFails")
            )
    }

    @org.junit.Test
    fun testRunFailsWithAllTestsFilteredOut() {
        config = createConfig("doesNotMatchAnything")
        val runner: JUnit4Runner =
            createRunner(com.google.testing.junit.runner.junit4.JUnit4RunnerTest.SampleSuite::class.java)
        val result: org.junit.runner.Result? = runner.run()

        Truth.assertThat(result.getRunCount()).isEqualTo(1)
        Truth.assertThat(result.getFailureCount()).isEqualTo(1)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)
        Truth.assertThat(result.getFailures().get(0).getMessage()).contains("No tests found")
    }

    @org.junit.Test
    fun testRunExcludeFilterAlwaysExits() {
        config = JUnit4Config("test", "CallsSystemExit", null, createProperties("1"))
        val runner: JUnit4Runner =
            createRunner(com.google.testing.junit.runner.junit4.JUnit4RunnerTest.SampleSuite::class.java)
        val result: org.junit.runner.Result? = runner.run()

        Truth.assertThat(result.getRunCount()).isEqualTo(2)
        Truth.assertThat(result.getFailureCount()).isEqualTo(1)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)
        Truth.assertThat(result.getFailures().get(0).getDescription())
            .isEqualTo(
                org.junit.runner.Description.createTestDescription(SampleFailingTest::class.java, "testThatAlwaysFails")
            )
    }

    @org.junit.Test
    fun testFilteringAndShardingTogetherIsSupported() {
        config = createConfig("testThatAlways(Passes|Fails)")
        shardingEnvironment = Mockito.mock<ShardingEnvironment>(ShardingEnvironment::class.java)
        shardingFilters = FakeShardingFilters(
            org.junit.runner.Description.createTestDescription(SamplePassingTest::class.java, "testThatAlwaysPasses"),
            org.junit.runner.Description.createTestDescription(SampleFailingTest::class.java, "testThatAlwaysFails")
        )

        Mockito.`when`<Boolean?>(shardingEnvironment.isShardingEnabled()).thenReturn(true)

        val runner: JUnit4Runner =
            createRunner(com.google.testing.junit.runner.junit4.JUnit4RunnerTest.SampleSuite::class.java)
        val result: org.junit.runner.Result? = runner.run()

        Mockito.verify<ShardingEnvironment?>(shardingEnvironment).touchShardFile()

        Truth.assertThat(result.getRunCount()).isEqualTo(2)
        Truth.assertThat(result.getFailureCount()).isEqualTo(1)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)
        Truth.assertThat(result.getFailures().get(0).getDescription())
            .isEqualTo(
                org.junit.runner.Description.createTestDescription(SampleFailingTest::class.java, "testThatAlwaysFails")
            )
    }

    @org.junit.Test
    fun testRunPassesWhenNoTestsOnCurrentShardWithFiltering() {
        config = createConfig("testThatAlwaysFails")
        shardingEnvironment = Mockito.mock<ShardingEnvironment>(ShardingEnvironment::class.java)
        shardingFilters = FakeShardingFilters(
            org.junit.runner.Description.createTestDescription(SamplePassingTest::class.java, "testThatAlwaysPasses")
        )

        Mockito.`when`<Boolean?>(shardingEnvironment.isShardingEnabled()).thenReturn(true)

        val runner: JUnit4Runner =
            createRunner(com.google.testing.junit.runner.junit4.JUnit4RunnerTest.SampleSuite::class.java)
        val result: org.junit.runner.Result? = runner.run()

        Mockito.verify<ShardingEnvironment?>(shardingEnvironment).touchShardFile()

        Truth.assertThat(result.getRunCount()).isEqualTo(0)
        Truth.assertThat(result.getFailureCount()).isEqualTo(0)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)
    }

    @org.junit.Test
    fun testRunFailsWhenNoTestsOnCurrentShardWithoutFiltering() {
        config = createConfig()
        shardingEnvironment = Mockito.mock<ShardingEnvironment>(ShardingEnvironment::class.java)
        shardingFilters = Mockito.mock<ShardingFilters?>(ShardingFilters::class.java)

        Mockito.`when`<Boolean?>(shardingEnvironment.isShardingEnabled()).thenReturn(true)
        Mockito.`when`<org.junit.runner.manipulation.Filter?>(shardingFilters.createShardingFilter(ArgumentMatchers.anyList<org.junit.runner.Description?>()))
            .thenReturn(NoneShallPassFilter())

        val runner: JUnit4Runner =
            createRunner(com.google.testing.junit.runner.junit4.JUnit4RunnerTest.SampleSuite::class.java)
        val result: org.junit.runner.Result? = runner.run()

        Truth.assertThat(result.getRunCount()).isEqualTo(1)
        Truth.assertThat(result.getFailureCount()).isEqualTo(1)
        Truth.assertThat(result.getIgnoreCount()).isEqualTo(0)
        Truth.assertThat(result.getFailures().get(0).getMessage()).contains("No tests found")

        Mockito.verify<ShardingEnvironment?>(shardingEnvironment).touchShardFile()
        Mockito.verify<ShardingFilters?>(shardingFilters)
            .createShardingFilter(ArgumentMatchers.anyList<org.junit.runner.Description?>())
    }

    @org.junit.Test
    fun testMustSpecifySupportedJUnitApiVersion() {
        config = JUnit4Config(null, null, null, createProperties("2"))
        val runner: JUnit4Runner = createRunner(SamplePassingTest::class.java)

        val e: java.lang.IllegalStateException? = org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable { runner.run() })
        Truth.assertThat(e).hasMessageThat().startsWith("Unsupported JUnit Runner API version")
    }

    private fun assertPassingTestHasExpectedOutput(
        outputStream: java.io.ByteArrayOutputStream,
        testClass: java.lang.Class<*>?
    ) {
        val expectedOutputStream: java.io.ByteArrayOutputStream = getExpectedOutput(testClass)

        Truth.assertThat(extractOutput(outputStream)).isEqualTo(extractOutput(expectedOutputStream))
    }

    private fun extractOutput(outputStream: java.io.ByteArrayOutputStream): String? {
        val output = String(outputStream.toByteArray(), java.nio.charset.Charset.defaultCharset())
        return output.replaceFirst("\nTime: .*\n".toRegex(), "\nTime: 0\n")
    }

    private fun getExpectedOutput(testClass: java.lang.Class<*>?): java.io.ByteArrayOutputStream {
        val core: JUnitCore = JUnitCore()

        val byteStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val printStream: PrintStream = PrintStream(byteStream)
        printStream.println("JUnit4 Test Runner")
        val listener: RunListener = org.junit.internal.TextListener(printStream)
        core.addListener(listener)

        val request: org.junit.runner.Request = org.junit.runner.Request.classWithoutSuiteMethod(testClass)

        core.run(request)
        printStream.close()

        return byteStream
    }

    /** Sample test that passes.  */
    @RunWith(JUnit4::class)
    class SamplePassingTest {
        @org.junit.Test
        fun testThatAlwaysPasses() {
        }
    }


    /** Sample test that fails.  */
    @RunWith(JUnit4::class)
    class SampleFailingTest {
        @org.junit.Test
        fun testThatAlwaysFails() {
            org.junit.Assert.fail("expected")
        }
    }


    /** Sample test that fails and shows international text without corrupting it.  */
    @RunWith(JUnit4::class)
    class SampleInternationalFailingTest {
        @org.junit.Test
        fun testThatAlwaysFails() {
            // Use JUnit asserts instead of Truth, since Truth's message format is subject to change.
            org.junit.Assert.assertEquals("Test Japan.", "Test \u65E5本.")
        }
    }


    /** Sample suite.  */
    @RunWith(Suite::class)
    @SuiteClasses(
        SamplePassingTest::class, SampleFailingTest::class
    )
    class SampleSuite


    private class StubShardingEnvironment : ShardingEnvironment() {
        val isShardingEnabled: Boolean
            get() = false

        val shardIndex: Int
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        val totalShards: Int
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        override fun touchShardFile() {
            throw java.lang.UnsupportedOperationException()
        }

        val testShardingStrategy: String?
            get() {
                throw java.lang.UnsupportedOperationException()
            }
    }


    /**
     * Filter that won't run any tests.
     */
    private class NoneShallPassFilter : org.junit.runner.manipulation.Filter() {
        override fun shouldRun(description: org.junit.runner.Description?): Boolean {
            return false
        }

        override fun describe(): String {
            return "none-shall-pass filter"
        }
    }

    internal inner class TestModule(cancellableRequestFactory: CancellableRequestFactory) : JUnit4RunnerModule(null) {
        private val stdout: PrintStream = PrintStream(stdoutByteStream)
        private val cancellableRequestFactory: CancellableRequestFactory

        init {
            this.cancellableRequestFactory = cancellableRequestFactory
        }

        override fun shardingEnvironment(): ShardingEnvironment {
            return shardingEnvironment
        }

        override fun clock(): TestClock {
            return FakeTestClock()
        }

        override fun config(): JUnit4Config {
            return config
        }

        public override fun shardingFilters(shardingEnvironment: ShardingEnvironment?): ShardingFilters {
            return if (shardingFilters == null)
                ShardingFilters(shardingEnvironment, ShardingFilters.Companion.DEFAULT_SHARDING_STRATEGY)
            else
                shardingFilters
        }

        override fun stdout(): PrintStream {
            return this.stdout
        }

        public override fun setOfRunListeners(
            config: JUnit4Config?,
            testSuiteModelSupplier: java.util.function.Supplier<TestSuiteModel?>?,
            cancellableRequestFactory: CancellableRequestFactory?
        ): MutableSet<RunListener?> {
            val set: MutableSet<RunListener?> = HashSet<RunListener?>()
            if (mockRunListener != null) {
                set.add(mockRunListener)
            }
            set.add(JUnit4RunnerBaseModule.provideTextListener(stdout()))
            return Collections.unmodifiableSet<RunListener?>(set)
        }

        public override fun cancellableRequestFactory(): CancellableRequestFactory {
            return cancellableRequestFactory
        }
    }

    companion object {
        private fun cancelTestRun(requestFactory: CancellableRequestFactory): Answer<java.lang.Void?> {
            return object : Answer<java.lang.Void?>() {
                override fun answer(invocation: InvocationOnMock?): java.lang.Void? {
                    requestFactory.cancelRun()
                    return null
                }
            }
        }

        private fun createConfig(includeFilter: String? = null): JUnit4Config {
            return JUnit4Config(includeFilter, null, null, createProperties("1"))
        }

        private fun createProperties(apiVersion: String?): Properties {
            val properties: Properties = Properties()
            properties.setProperty(JUnit4Config.JUNIT_API_VERSION_PROPERTY, apiVersion)
            return properties
        }
    }
}
