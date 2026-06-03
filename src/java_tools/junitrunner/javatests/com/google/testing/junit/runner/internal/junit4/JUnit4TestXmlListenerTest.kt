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
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment.getDescription
import com.google.testing.junit.runner.internal.SignalHandlers
import com.google.testing.junit.runner.internal.junit4.CancellableRequestFactory
import com.google.testing.junit.runner.internal.junit4.CancellableRequestFactory.cancelRun
import com.google.testing.junit.runner.internal.junit4.JUnit4TestNameListener.testRunStarted
import com.google.testing.junit.runner.internal.junit4.JUnit4TestXmlListener
import com.google.testing.junit.runner.internal.junit4.JUnit4TestXmlListener.testRunStarted
import com.google.testing.junit.runner.internal.junit4.MemoizingRequest.getRunner
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4Runner.run
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.google.testing.junit.runner.model.TestNode.testFailure
import com.google.testing.junit.runner.model.TestNode.testSkipped
import com.google.testing.junit.runner.model.TestNode.testSuppressed
import com.google.testing.junit.runner.model.TestSuiteModel
import com.google.testing.junit.runner.model.TestSuiteModel.testFailure
import com.google.testing.junit.runner.model.TestSuiteModel.testRunInterrupted
import com.google.testing.junit.runner.model.TestSuiteModel.testSkipped
import com.google.testing.junit.runner.model.TestSuiteModel.testSuppressed
import com.google.testing.junit.runner.model.TestSuiteModel.writeAsXml
import com.google.testing.junit.runner.model.TestSuiteNode.getChildren
import com.google.testing.junit.runner.model.TestSuiteNode.testFailure
import com.google.testing.junit.runner.model.TestSuiteNode.testSkipped
import com.google.testing.junit.runner.model.TestSuiteNode.testSuppressed
import net.starlark.java.syntax.Identifier.getName
import org.junit.Assume
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.runner.JUnitCore
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.InOrder
import org.mockito.Mockito
import java.io.PrintStream

/**
 * Tests for [JUnit4TestXmlListener]
 */
@RunWith(JUnit4::class)
class JUnit4TestXmlListenerTest {
    private val fakeSignalHandlers = FakeSignalHandlers()
    private val errStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
    private var errPrintStream: PrintStream? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createErrPrintStream() {
        errPrintStream = PrintStream(errStream, true, CHARSET)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun signalHandlerWritesXml() {
        val mockModelSupplier: TestSuiteModelSupplier =
            Mockito.mock<TestSuiteModelSupplier>(TestSuiteModelSupplier::class.java)
        val mockModel: TestSuiteModel? = Mockito.mock<TestSuiteModel?>(TestSuiteModel::class.java)
        val mockRequestFactory: CancellableRequestFactory =
            Mockito.mock<CancellableRequestFactory>(CancellableRequestFactory::class.java)
        val mockXmlStream: java.io.OutputStream? = Mockito.mock<java.io.OutputStream?>(java.io.OutputStream::class.java)
        val listener: JUnit4TestXmlListener = JUnit4TestXmlListener(
            mockModelSupplier, mockRequestFactory, fakeSignalHandlers, mockXmlStream, errPrintStream
        )

        val request: org.junit.runner.Request =
            org.junit.runner.Request.classWithoutSuiteMethod(PassingTest::class.java)
        val suiteDescription: org.junit.runner.Description = request.getRunner().getDescription()

        Mockito.`when`<TestSuiteModel?>(mockModelSupplier.get()).thenReturn(mockModel)

        listener.testRunStarted(suiteDescription)
        Truth.assertThat(fakeSignalHandlers.handlers).hasSize(1)

        fakeSignalHandlers.handlers.get(0).handle(sun.misc.Signal("TERM"))

        val errOutput = errStream.toString(CHARSET)
        Truth.assertWithMessage("expected signal name in stderr")
            .that(errOutput.contains("SIGTERM"))
            .isTrue()
        Truth.assertWithMessage("expected message in stderr")
            .that(errOutput.contains("Done writing test XML"))
            .isTrue()

        val inOrder: InOrder = Mockito.inOrder(mockRequestFactory, mockModel)
        inOrder.verify<CancellableRequestFactory?>(mockRequestFactory).cancelRun()
        inOrder.verify<TestSuiteModel?>(mockModel).testRunInterrupted()
        inOrder.verify<TestSuiteModel?>(mockModel).writeAsXml(mockXmlStream)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun writesXmlAtTestEnd() {
        val mockModelSupplier: TestSuiteModelSupplier =
            Mockito.mock<TestSuiteModelSupplier>(TestSuiteModelSupplier::class.java)
        val mockModel: TestSuiteModel? = Mockito.mock<TestSuiteModel?>(TestSuiteModel::class.java)
        val mockRequestFactory: CancellableRequestFactory =
            Mockito.mock<CancellableRequestFactory>(CancellableRequestFactory::class.java)
        val mockXmlStream: java.io.OutputStream? = Mockito.mock<java.io.OutputStream?>(java.io.OutputStream::class.java)
        val listener: JUnit4TestXmlListener = JUnit4TestXmlListener(
            mockModelSupplier, mockRequestFactory, fakeSignalHandlers, mockXmlStream, errPrintStream
        )

        Mockito.`when`<TestSuiteModel?>(mockModelSupplier.get()).thenReturn(mockModel)

        val core: JUnitCore = JUnitCore()
        core.addListener(listener)
        core.run(org.junit.runner.Request.classWithoutSuiteMethod(PassingTest::class.java))

        Truth.assertWithMessage("no output to stderr expected").that(errStream.size()).isEqualTo(0)
        Mockito.verify<TestSuiteModel?>(mockModel).writeAsXml(mockXmlStream)
        Mockito.verify<CancellableRequestFactory?>(mockRequestFactory, Mockito.never()).cancelRun()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assumptionViolationsAreReportedAsSkippedTests() {
        val mockModelSupplier: TestSuiteModelSupplier =
            Mockito.mock<TestSuiteModelSupplier>(TestSuiteModelSupplier::class.java)
        val mockModel: TestSuiteModel? = Mockito.mock<TestSuiteModel?>(TestSuiteModel::class.java)
        val mockRequestFactory: CancellableRequestFactory =
            Mockito.mock<CancellableRequestFactory>(CancellableRequestFactory::class.java)
        val mockXmlStream: java.io.OutputStream? = Mockito.mock<java.io.OutputStream?>(java.io.OutputStream::class.java)
        val listener: JUnit4TestXmlListener = JUnit4TestXmlListener(
            mockModelSupplier, mockRequestFactory, fakeSignalHandlers, mockXmlStream, errPrintStream
        )

        val request: org.junit.runner.Request =
            org.junit.runner.Request.classWithoutSuiteMethod(TestWithAssumptionViolation::class.java)
        val suiteDescription: org.junit.runner.Description = request.getRunner().getDescription()
        val testDescription: org.junit.runner.Description? = suiteDescription.getChildren().get(0)

        Mockito.`when`<TestSuiteModel?>(mockModelSupplier.get()).thenReturn(mockModel)

        val core: JUnitCore = JUnitCore()
        core.addListener(listener)
        core.run(request)

        Truth.assertWithMessage("no output to stderr expected").that(errStream.size()).isEqualTo(0)
        val inOrder: InOrder = Mockito.inOrder(mockModel)
        inOrder.verify<TestSuiteModel?>(mockModel).testSkipped(testDescription)
        inOrder.verify<TestSuiteModel?>(mockModel).writeAsXml(mockXmlStream)
        Mockito.verify<CancellableRequestFactory?>(mockRequestFactory, Mockito.never()).cancelRun()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun assumptionViolationsAtSuiteLevelAreReportedAsSkippedSuite() {
        val mockModelSupplier: TestSuiteModelSupplier =
            Mockito.mock<TestSuiteModelSupplier>(TestSuiteModelSupplier::class.java)
        val mockModel: TestSuiteModel? = Mockito.mock<TestSuiteModel?>(TestSuiteModel::class.java)
        val mockRequestFactory: CancellableRequestFactory =
            Mockito.mock<CancellableRequestFactory>(CancellableRequestFactory::class.java)
        val mockXmlStream: java.io.OutputStream? = Mockito.mock<java.io.OutputStream?>(java.io.OutputStream::class.java)
        val listener: JUnit4TestXmlListener = JUnit4TestXmlListener(
            mockModelSupplier, mockRequestFactory, fakeSignalHandlers, mockXmlStream, errPrintStream
        )

        val request: org.junit.runner.Request = org.junit.runner.Request.classWithoutSuiteMethod(
            TestWithAssumptionViolationOnTheSuiteLevel::class.java
        )
        val suiteDescription: org.junit.runner.Description = request.getRunner().getDescription()

        Mockito.`when`<TestSuiteModel?>(mockModelSupplier.get()).thenReturn(mockModel)

        val core: JUnitCore = JUnitCore()
        core.addListener(listener)
        core.run(request)

        Truth.assertWithMessage("no output to stderr expected").that(errStream.size()).isEqualTo(0)
        val inOrder: InOrder = Mockito.inOrder(mockModel)
        inOrder.verify<TestSuiteModel?>(mockModel).testSkipped(suiteDescription)
        inOrder.verify<TestSuiteModel?>(mockModel).writeAsXml(mockXmlStream)
        Mockito.verify<CancellableRequestFactory?>(mockRequestFactory, Mockito.never()).cancelRun()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun failuresAreReported() {
        val mockModelSupplier: TestSuiteModelSupplier =
            Mockito.mock<TestSuiteModelSupplier>(TestSuiteModelSupplier::class.java)
        val mockModel: TestSuiteModel? = Mockito.mock<TestSuiteModel?>(TestSuiteModel::class.java)
        val mockRequestFactory: CancellableRequestFactory =
            Mockito.mock<CancellableRequestFactory>(CancellableRequestFactory::class.java)
        val mockXmlStream: java.io.OutputStream? = Mockito.mock<java.io.OutputStream?>(java.io.OutputStream::class.java)
        val listener: JUnit4TestXmlListener = JUnit4TestXmlListener(
            mockModelSupplier, mockRequestFactory, fakeSignalHandlers, mockXmlStream, errPrintStream
        )

        val request: org.junit.runner.Request =
            org.junit.runner.Request.classWithoutSuiteMethod(FailingTest::class.java)
        val suiteDescription: org.junit.runner.Description = request.getRunner().getDescription()
        val testDescription: org.junit.runner.Description? = suiteDescription.getChildren().get(0)

        Mockito.`when`<TestSuiteModel?>(mockModelSupplier.get()).thenReturn(mockModel)

        val core: JUnitCore = JUnitCore()
        core.addListener(listener)
        core.run(request)

        Truth.assertWithMessage("no output to stderr expected").that(errStream.size()).isEqualTo(0)
        val inOrder: InOrder = Mockito.inOrder(mockModel)
        inOrder.verify<TestSuiteModel?>(mockModel).testFailure(
            ArgumentMatchers.eq<org.junit.runner.Description?>(testDescription),
            ArgumentMatchers.any<Throwable?>(Throwable::class.java)
        )
        inOrder.verify<TestSuiteModel?>(mockModel).writeAsXml(mockXmlStream)
        Mockito.verify<CancellableRequestFactory?>(mockRequestFactory, Mockito.never()).cancelRun()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ignoredTestAreReportedAsSuppressedTests() {
        val mockModelSupplier: TestSuiteModelSupplier =
            Mockito.mock<TestSuiteModelSupplier>(TestSuiteModelSupplier::class.java)
        val mockModel: TestSuiteModel? = Mockito.mock<TestSuiteModel?>(TestSuiteModel::class.java)
        val mockRequestFactory: CancellableRequestFactory =
            Mockito.mock<CancellableRequestFactory>(CancellableRequestFactory::class.java)
        val mockXmlStream: java.io.OutputStream? = Mockito.mock<java.io.OutputStream?>(java.io.OutputStream::class.java)
        val listener: JUnit4TestXmlListener = JUnit4TestXmlListener(
            mockModelSupplier, mockRequestFactory, fakeSignalHandlers, mockXmlStream, errPrintStream
        )

        val request: org.junit.runner.Request =
            org.junit.runner.Request.classWithoutSuiteMethod(TestWithIgnoredTestCase::class.java)
        val suiteDescription: org.junit.runner.Description = request.getRunner().getDescription()
        val testDescription: org.junit.runner.Description? = suiteDescription.getChildren().get(0)

        Mockito.`when`<TestSuiteModel?>(mockModelSupplier.get()).thenReturn(mockModel)

        val core: JUnitCore = JUnitCore()
        core.addListener(listener)
        core.run(request)

        Truth.assertWithMessage("no output to stderr expected").that(errStream.size()).isEqualTo(0)
        val inOrder: InOrder = Mockito.inOrder(mockModel)
        inOrder.verify<TestSuiteModel?>(mockModel).testSuppressed(testDescription)
        inOrder.verify<TestSuiteModel?>(mockModel).writeAsXml(mockXmlStream)
        Mockito.verify<CancellableRequestFactory?>(mockRequestFactory, Mockito.never()).cancelRun()
    }

    /**
     * Test with a method that always passes.
     */
    class PassingTest {
        @org.junit.Test
        fun alwaysPasses() {
        }
    }


    /**
     * Test with a method that always fails.
     */
    class FailingTest {
        @org.junit.Test
        fun alwaysFails() {
            org.junit.Assert.fail()
        }
    }


    /**
     * Test with a method that is always skipped.
     */
    class TestWithAssumptionViolation {
        @org.junit.Test
        fun alwaysSkipped() {
            Assume.assumeTrue(false)
        }
    }

    /**
     * Test with a method that is always skipped.
     */
    class TestWithAssumptionViolationOnTheSuiteLevel {
        @org.junit.Test
        fun fakeTest() {
        }

        companion object {
            @BeforeClass
            fun failedSuiteLevelAssumption() {
                Assume.assumeTrue(false)
            }
        }
    }

    class TestWithIgnoredTestCase {
        @org.junit.Test
        @Ignore
        fun alwaysIgnored() {
        }
    }

    private class FakeSignalHandlers : SignalHandlers(null) {
        var handlers: MutableList<sun.misc.SignalHandler?> = java.util.ArrayList<sun.misc.SignalHandler?>()

        override fun installHandler(signal: sun.misc.Signal, signalHandler: sun.misc.SignalHandler?) {
            if (signal.getName() == "TERM") {
                handlers.add(signalHandler)
            }
        }
    }


    private interface TestSuiteModelSupplier : java.util.function.Supplier<TestSuiteModel?>
    companion object {
        private const val CHARSET = "UTF-8"
    }
}