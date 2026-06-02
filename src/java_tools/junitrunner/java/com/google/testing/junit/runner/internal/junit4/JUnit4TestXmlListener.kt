// Copyright 2012 The Bazel Authors. All Rights Reserved.
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

import com.google.testing.junit.runner.internal.SignalHandlers
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import sun.misc.Signal
import sun.misc.SignalHandler
import java.io.OutputStream
import java.util.function.Supplier

/** A listener that writes the test output as XML.  */
// no alternative for signal handling?
class JUnit4TestXmlListener(
    modelSupplier: Supplier<TestSuiteModel>,
    requestFactory: CancellableRequestFactory,
    signalHandlers: SignalHandlers,
    xmlStream: OutputStream?,
    errPrintStream: PrintStream
) : RunListener() {
    private val modelSupplier: Supplier<TestSuiteModel>
    private val requestFactory: CancellableRequestFactory
    private val signalHandlers: SignalHandlers
    private val xmlStream: OutputStream?
    private val errPrintStream: PrintStream

    @kotlin.concurrent.Volatile
    private var model: TestSuiteModel? = null

    init {
        this.modelSupplier = modelSupplier
        this.requestFactory = requestFactory
        this.signalHandlers = signalHandlers
        this.xmlStream = xmlStream
        this.errPrintStream = errPrintStream
    }

    @Throws(Exception::class)
    override fun testRunStarted(description: Description?) {
        model = modelSupplier.get()

        /*
     * At this point, command line filtering has been applied. Mark all remaining tests as
     * "pending"; any other tests will be considered "filtered".
     */
        model.testRunStarted(description)

        signalHandlers.installHandler(Signal("TERM"), WriteXmlSignalHandler())
    }

    @Throws(Exception::class)
    override fun testStarted(description: Description?) {
        model.testStarted(description)
    }

    override fun testAssumptionFailure(failure: Failure) {
        model.testSkipped(failure.getDescription())
    }

    @Throws(Exception::class)
    override fun testFailure(failure: Failure) {
        model.testFailure(failure.getDescription(), failure.getException())
    }

    @Throws(Exception::class)
    override fun testIgnored(description: Description) {
        // TODO(bazel-team) There's a known issue in the JUnit4 ParentRunner that
        // fires testIgnored on test suites that are being skipped due to an
        // assumption failure.
        if (isSuiteAssumptionFailure(description)) {
            model.testSkipped(description)
        } else {
            model.testSuppressed(description)
        }
    }

    private fun isSuiteAssumptionFailure(description: Description): Boolean {
        return description.isSuite() && description.getAnnotation<Ignore?>(Ignore::class.java) == null
    }

    @Throws(Exception::class)
    override fun testFinished(description: Description?) {
        model.testFinished(description)
    }

    @Throws(Exception::class)
    override fun testRunFinished(result: Result?) {
        model.writeAsXml(xmlStream)
    }

    private inner class WriteXmlSignalHandler : SignalHandler {
        override fun handle(signal: Signal) {
            try {
                errPrintStream.printf("%nReceived %s; writing test XML%n", signal.toString())
                requestFactory.cancelRun()
                model.testRunInterrupted()
                model.writeAsXml(xmlStream)
                errPrintStream.println("Done writing test XML")
            } catch (e: Exception) {
                errPrintStream.println("Could not write test XML")
                e.printStackTrace(errPrintStream)
            }
        }
    }
}
