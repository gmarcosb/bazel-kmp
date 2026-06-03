// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [TerminalTestResultNotifier].  */
@RunWith(JUnit4::class)
class TerminalTestResultNotifierTest {
    private val optionsParsingResult: OptionsParsingResult =
        Mockito.mock<OptionsParsingResult>(OptionsParsingResult::class.java)
    private val ansiTerminalPrinter: AnsiTerminalPrinter? =
        Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCaseOption_allPass() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.PASSED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.TESTCASE
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("10 passing"))
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCaseOption_allPassButTargetFails() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(10)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.TESTCASE
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains("0 passing")
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).contains(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCaseOption_someFail() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(2)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.TESTCASE
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("8 passing"))
        Truth.assertThat(printed).contains(error("2 failing"))
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shortOption_someFailToBuild() {
        val numFailedToBuildTestCases: Int = TerminalTestResultNotifier.NUM_FAILED_TO_BUILD + 1

        printFailedToBuildSummaries(ExecutionOptions.TestSummaryFormat.SHORT)

        val skippedMessage = this.printedMessage
        Truth.assertThat(skippedMessage).isEqualTo("(Skipping other failed to build tests)")

        val messageCaptor: ArgumentCaptor<String?> = ArgumentCaptor.forClass<String?, String?>(String::class.java)
        Mockito.verify<Any?>(ansiTerminalPrinter, Mockito.times(numFailedToBuildTestCases))
            .print(messageCaptor.capture())
        val values: MutableList<String?> = messageCaptor.getAllValues()

        for (i in 0..<numFailedToBuildTestCases - 1) {
            val message = values.get(i)
            Truth.assertThat(message).contains("//foo/bar:baz")
            Truth.assertThat(message).contains(BlazeTestStatus.FAILED_TO_BUILD.toString().replace('_', ' '))
        }

        val last = values.get(numFailedToBuildTestCases - 1)
        Truth.assertThat(last).contains("Executed 0 out of 6 tests")
        Truth.assertThat(last).contains(numFailedToBuildTestCases.toString() + " fail to build")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shortUncachedOption_someFailToBuild() {
        val numFailedToBuildTestCases: Int = TerminalTestResultNotifier.NUM_FAILED_TO_BUILD + 1

        printFailedToBuildSummaries(ExecutionOptions.TestSummaryFormat.SHORT_UNCACHED)

        val skippedMessage = this.printedMessage
        Truth.assertThat(skippedMessage).isEqualTo("(Skipping other failed to build tests)")

        val messageCaptor: ArgumentCaptor<String?> = ArgumentCaptor.forClass<String?, String?>(String::class.java)
        Mockito.verify<Any?>(ansiTerminalPrinter, Mockito.times(numFailedToBuildTestCases))
            .print(messageCaptor.capture()) // 1 but should all be printed
        val values: MutableList<String?> = messageCaptor.getAllValues()

        for (i in 0..<numFailedToBuildTestCases - 1) {
            val message = values.get(i)
            Truth.assertThat(message).contains("//foo/bar:baz")
            Truth.assertThat(message).contains(BlazeTestStatus.FAILED_TO_BUILD.toString().replace('_', ' '))
        }

        val last = values.get(numFailedToBuildTestCases - 1)
        Truth.assertThat(last).contains("Executed 0 out of 6 tests")
        Truth.assertThat(last).contains(numFailedToBuildTestCases.toString() + " fail to build")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCaseOption_allFail() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(10)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.TESTCASE
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains("0 passing")
        Truth.assertThat(printed).contains(error("10 failing"))
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.INFO.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedOption_allPass() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.PASSED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("10 passing"))
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains("0 skipped")
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedUncachedOption_allPassUncached() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.PASSED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED_UNCACHED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("10 passing"))
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains("0 skipped")
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedUncachedOption_allPassCached() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.PASSED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(false)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED_UNCACHED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("10 passing"))
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains("0 skipped")
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedOption_allPassButSomeSkipped() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.PASSED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(2)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("8 passing"))
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains(warn("2 skipped"))
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedUncachedOption_allPassUncachedButSomeSkipped() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.PASSED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(2)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED_UNCACHED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("8 passing"))
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains(warn("2 skipped"))
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedUncachedOption_allPassCachedButSomeSkipped() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.PASSED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(2)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(false)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED_UNCACHED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("8 passing"))
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains(warn("2 skipped"))
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedOption_allPassButTargetFails() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(10)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains("0 passing")
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).contains(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedUncachedOption_allPassButTargetFails() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(0)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(10)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED_UNCACHED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains("0 passing")
        Truth.assertThat(printed).contains("0 failing")
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).contains(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.ERROR.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedOption_someFail() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(2)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("8 passing"))
        Truth.assertThat(printed).contains(error("2 failing"))
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedUncachedOption_someFail() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(2)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED_UNCACHED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains(info("8 passing"))
        Truth.assertThat(printed).contains(error("2 failing"))
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedOption_allFail() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(10)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains("0 passing")
        Truth.assertThat(printed).contains(error("10 failing"))
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.INFO.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun detailedUncachedOption_allFail() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(10)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.DETAILED_UNCACHED
        )

        val printed = this.printedMessage
        Truth.assertThat(printed).contains("0 passing")
        Truth.assertThat(printed).contains(error("10 failing"))
        Truth.assertThat(printed).contains("out of 10 test cases")
        Truth.assertThat(printed).doesNotContain(SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER)
        Truth.assertThat(printed).doesNotContain(AnsiTerminalPrinter.Mode.INFO.toString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shortOption_noSummaryPrinted() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(2)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.SHORT
        )

        verifyNoSummaryPrinted()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shortUncachedOption_noSummaryPrinted() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(2)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.SHORT_UNCACHED
        )

        verifyNoSummaryPrinted()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun terseOption_noSummaryPrinted() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(2)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.TERSE
        )

        verifyNoSummaryPrinted()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noneOption_noSummaryPrinted() {
        printTestCaseSummary(
            TestSummarySpec.Companion.builder()
                .setStatus(BlazeTestStatus.FAILED)!!
                .setFailedTestCases(2)!!
                .setSkippedTestCases(0)!!
                .setUnknownTestCases(0)!!
                .setTotalTestCases(10)!!
                .setActionRan(true)!!
                .build()!!,
            ExecutionOptions.TestSummaryFormat.NONE
        )

        verifyNoSummaryPrinted()
    }

    // A helper that creates `TestSummary` mocks for testing.
    internal class TestSummarySpec(
        status: BlazeTestStatus?,
        failedTestCases: Int,
        skippedTestCases: Int,
        unknownTestCases: Int,
        totalTestCases: Int,
        actionRan: Boolean
    ) {
        private val status: BlazeTestStatus?
        private val failedTestCases: Int
        private val skippedTestCases: Int
        private val unknownTestCases: Int
        private val totalTestCases: Int
        private val actionRan: Boolean

        init {
            this.status = status
            this.failedTestCases = failedTestCases
            this.skippedTestCases = skippedTestCases
            this.unknownTestCases = unknownTestCases
            this.totalTestCases = totalTestCases
            this.actionRan = actionRan
        }

        @Throws(LabelSyntaxException::class)
        fun build(): TestSummary {
            val testSummary: TestSummary = Mockito.mock<TestSummary>(TestSummary::class.java)
            Mockito.`when`<T?>(testSummary.getTotalTestCases()).thenReturn(totalTestCases)
            Mockito.`when`<T?>(testSummary.getUnknownTestCases()).thenReturn(unknownTestCases)
            Mockito.`when`<T?>(testSummary.getStatus()).thenReturn(status)
            Mockito.`when`<T?>(testSummary.actionRan()).thenReturn(actionRan)

            val failedTestCase: TestCase? = TestCase.newBuilder().setStatus(Status.FAILED).build()
            val failedTestCasesList: MutableList<TestCase?> =
                Collections.nCopies<TestCase?>(failedTestCases, failedTestCase)
            Mockito.`when`<T?>(testSummary.getFailedTestCases()).thenReturn(failedTestCasesList)

            val skippedTestCase: TestCase? = TestCase.newBuilder().setStatus(Status.SKIPPED).build()
            val skippedTestCasesList: MutableList<TestCase?> =
                Collections.nCopies<TestCase?>(skippedTestCases, skippedTestCase)
            Mockito.`when`<T?>(testSummary.getSkippedTestCases()).thenReturn(skippedTestCasesList)

            val label: Label? = Label.parseCanonical("//foo:bar")
            Mockito.`when`<T?>(testSummary.getLabel()).thenReturn(label)

            return testSummary
        }

        @AutoBuilder(ofClass = TestSummarySpec::class)
        internal abstract class Builder {
            abstract fun setStatus(status: BlazeTestStatus?): Builder?

            abstract fun setFailedTestCases(failedTestCases: Int): Builder?

            abstract fun setSkippedTestCases(skippedTestCases: Int): Builder?

            abstract fun setUnknownTestCases(unknownTestCases: Int): Builder?

            abstract fun setTotalTestCases(totalTestCases: Int): Builder?

            abstract fun setActionRan(actionRan: Boolean): Builder?

            abstract fun build(): TestSummarySpec?
        }

        companion object {
            fun builder(): Builder {
                return AutoBuilder_TerminalTestResultNotifierTest_TestSummarySpec_Builder()
            }
        }
    }

    @Throws(LabelSyntaxException::class)
    private fun printFailedToBuildSummaries(testSummaryFormat: TestSummaryFormat?) {
        val executionOptions: ExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(ExecutionOptions::class.java)
        executionOptions.testSummary = testSummaryFormat
        Mockito.`when`<T?>(optionsParsingResult.getOptions<O?>(ExecutionOptions::class.java))
            .thenReturn(executionOptions)
        val testSummaryOptions: TestSummaryOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(TestSummaryOptions::class.java)
        testSummaryOptions.setVerboseSummary(true)
        Mockito.`when`<T?>(optionsParsingResult.getOptions<O?>(TestSummaryOptions::class.java))
            .thenReturn(testSummaryOptions)

        val builder: com.google.common.collect.ImmutableSortedSet.Builder<TestSummary?> =
            com.google.common.collect.ImmutableSortedSet.orderedBy<TestSummary?>(
                java.util.Comparator.comparing<TestSummary?, U?>(
                    java.util.function.Function { o: TestSummary? -> o.getLabel().name })
            )
        for (i in 0..<TerminalTestResultNotifier.NUM_FAILED_TO_BUILD + 1) {
            val testSummary: TestSummary = Mockito.mock<TestSummary>(TestSummary::class.java)
            Mockito.`when`<T?>(testSummary.getTotalTestCases()).thenReturn(0)

            val labelA: Label? = Label.parseCanonical("//foo/bar:baz" + i)
            Mockito.`when`<T?>(testSummary.getFailedTestCases())
                .thenReturn(com.google.common.collect.ImmutableList.of<E?>())
            Mockito.`when`<T?>(testSummary.getStatus()).thenReturn(BlazeTestStatus.FAILED_TO_BUILD)
            Mockito.`when`<T?>(testSummary.actionRan()).thenReturn(false)
            Mockito.`when`<T?>(testSummary.getLabel()).thenReturn(labelA)

            builder.add(testSummary)
        }

        val terminalTestResultNotifier: TerminalTestResultNotifier =
            TerminalTestResultNotifier(
                ansiTerminalPrinter,
                Path::getPathString,
                optionsParsingResult,
                RepositoryMapping.EMPTY
            )
        terminalTestResultNotifier.notify(builder.build(), 0)
    }

    @Throws(LabelSyntaxException::class)
    private fun printTestCaseSummary(
        testSummarySpec: TestSummarySpec, testSummaryFormat: TestSummaryFormat?
    ) {
        val executionOptions: ExecutionOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(ExecutionOptions::class.java)
        executionOptions.testSummary = testSummaryFormat
        Mockito.`when`<T?>(optionsParsingResult.getOptions<O?>(ExecutionOptions::class.java))
            .thenReturn(executionOptions)
        val testSummaryOptions: TestSummaryOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(TestSummaryOptions::class.java)
        testSummaryOptions.setVerboseSummary(true)
        Mockito.`when`<T?>(optionsParsingResult.getOptions<O?>(TestSummaryOptions::class.java))
            .thenReturn(testSummaryOptions)

        val terminalTestResultNotifier: TerminalTestResultNotifier =
            TerminalTestResultNotifier(
                ansiTerminalPrinter,
                Path::getPathString,
                optionsParsingResult,
                RepositoryMapping.EMPTY
            )
        terminalTestResultNotifier.notify(com.google.common.collect.ImmutableSet.of<E?>(testSummarySpec.build()), 1)
    }

    private val printedMessage: String?
        get() {
            val messageCaptor: ArgumentCaptor<String?> =
                ArgumentCaptor.forClass<String?, String?>(String::class.java)
            Mockito.verify<Any?>(ansiTerminalPrinter).printLn(messageCaptor.capture())
            return messageCaptor.getValue()
        }

    private fun verifyNoSummaryPrinted() {
        Mockito.verify<Any?>(ansiTerminalPrinter, Mockito.never()).printLn(ArgumentMatchers.any<T?>())
    }

    companion object {
        private const val SOME_TARGETS_ARE_MISSING_TEST_CASES_DISCLAIMER =
            "some targets did not have test case information"

        private fun info(message: String): String {
            return AnsiTerminalPrinter.Mode.INFO + message + AnsiTerminalPrinter.Mode.DEFAULT
        }

        private fun warn(message: String): String {
            return AnsiTerminalPrinter.Mode.WARNING + message + AnsiTerminalPrinter.Mode.DEFAULT
        }

        private fun error(message: String): String {
            return AnsiTerminalPrinter.Mode.ERROR + message + AnsiTerminalPrinter.Mode.DEFAULT
        }
    }
}
