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

import com.google.devtools.build.lib.analysis.ConfiguredTarget

@RunWith(JUnit4::class)
class TestSummaryTest {
    private var stubTarget: ConfiguredTarget? = null
    private var fs: FileSystem? = null
    private var basicBuilder: TestSummary.Builder? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createFileSystem() {
        fs = InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        stubTarget = stubTarget()
        basicBuilder = this.templateBuilder
    }

    private val templateBuilder: TestSummary.Builder
        get() {
            val configuration: BuildConfigurationValue =
                Mockito.mock<BuildConfigurationValue>(BuildConfigurationValue::class.java)
            Mockito.`when`<T?>(configuration.checksum()).thenReturn("abcdef")
            return TestSummary.newBuilder(stubTarget)
                .setConfiguration(configuration)
                .setStatus(BlazeTestStatus.PASSED)
                .setNumCached(NOT_CACHED)
                .setActionRan(true)
                .setRanRemotely(false)
        }

    private fun getPathList(vararg names: String?): MutableList<Path?> {
        val list: MutableList<Path?> = java.util.ArrayList<Path?>()
        for (name in names) {
            list.add(fs.getPath(name))
        }
        return list
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShouldProperlyTestLabels() {
        val target: ConfiguredTarget = target("somepath", "MyTarget")
        val expectedString = ANY_STRING + "//somepath:MyTarget" + ANY_STRING
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summaryStatus: TestSummary = createTestSummary(target, BlazeTestStatus.PASSED, CACHED)
        TestSummaryPrinter.print(summaryStatus, terminalPrinter, Path::getPathString, true, false)
        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShouldPrintPassedStatus() {
        val expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED + ANY_STRING
        val terminalPrinter: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, NOT_CACHED)
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)

        Mockito.verify<Any?>(terminalPrinter).print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShouldPrintFailedStatus() {
        val expectedString = ANY_STRING + "ERROR" + ANY_STRING + BlazeTestStatus.FAILED + ANY_STRING
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary = createTestSummary(stubTarget, BlazeTestStatus.FAILED, NOT_CACHED)

        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)

        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    private fun assertShouldNotPrint(status: BlazeTestStatus?, verboseSummary: Boolean) {
        val terminalPrinter: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(
            createTestSummary(stubTarget, status, NOT_CACHED),
            terminalPrinter,
            Path::getPathString,
            verboseSummary,
            false
        )
        Mockito.verify<Any?>(terminalPrinter, Mockito.never()).print(ArgumentMatchers.anyString())
    }

    @org.junit.Test
    fun testShouldPrintFailedToBuildStatus() {
        val expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.FAILED_TO_BUILD
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary = createTestSummary(BlazeTestStatus.FAILED_TO_BUILD, NOT_CACHED)

        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)

        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    fun testShouldNotPrintFailedToBuildStatus() {
        assertShouldNotPrint(BlazeTestStatus.FAILED_TO_BUILD, false)
    }

    @org.junit.Test
    fun testShouldNotPrintHaltedStatus() {
        assertShouldNotPrint(BlazeTestStatus.BLAZE_HALTED_BEFORE_TESTING, true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShouldPrintCachedStatus() {
        val expectedString = ANY_STRING + "\\(cached" + ANY_STRING
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, CACHED)

        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)

        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPartialCachedStatus() {
        val expectedString = ANY_STRING + "\\(3/4 cached" + ANY_STRING
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, CACHED - 1)
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)
        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncompleteCached() {
        val terminalPrinter: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        val summary: TestSummary = createTestSummary(stubTarget, BlazeTestStatus.INCOMPLETE, CACHED - 1)
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)
        Mockito.verify<Any?>(terminalPrinter).print(< T > not < T ? > (ArgumentMatchers.contains("cached")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShouldPrintUncachedStatus() {
        val terminalPrinter: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        val summary: TestSummary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, NOT_CACHED)
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)
        Mockito.verify<Any?>(terminalPrinter).print(< T > not < T ? > (ArgumentMatchers.contains("cached")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoTiming() {
        val expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary = createTestSummary(stubTarget, BlazeTestStatus.PASSED, NOT_CACHED)

        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)
        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilder() {
        // No need to copy if built twice in a row; no direct setters on the object.
        val summary: TestSummary = basicBuilder.build()
        val sameSummary: TestSummary? = basicBuilder.build()
        assertThat(sameSummary).isSameInstanceAs(summary)

        basicBuilder.addTestTimes(com.google.common.collect.ImmutableList.of<E?>(40L))

        val summaryCopy: TestSummary = basicBuilder.build()
        assertThat(summaryCopy.getTarget()).isEqualTo(summary.getTarget())
        assertThat(summaryCopy.getStatus()).isEqualTo(summary.getStatus())
        assertThat(summaryCopy.numCached()).isEqualTo(summary.numCached())
        assertThat(summaryCopy).isNotSameInstanceAs(summary)
        assertThat(summary.totalRuns()).isEqualTo(0)
        assertThat(summaryCopy.totalRuns()).isEqualTo(1)

        // Check that the builder can add a new warning to the copy,
        // despite the immutability of the original.
        basicBuilder.addTestTimes(com.google.common.collect.ImmutableList.of<E?>(60L))

        val fiftyCached: TestSummary = basicBuilder.setNumCached(50).build()
        assertThat(fiftyCached.getStatus()).isEqualTo(summary.getStatus())
        assertThat(fiftyCached.numCached()).isEqualTo(50)
        assertThat(fiftyCached.totalRuns()).isEqualTo(2)

        val sixtyCached: TestSummary = basicBuilder.setNumCached(60).build()
        assertThat(sixtyCached.numCached()).isEqualTo(60)
        assertThat(fiftyCached.numCached()).isEqualTo(50)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAsStreamProto() {
        val testParams: TestParams = Mockito.mock<TestParams>(TestParams::class.java)
        Mockito.`when`<T?>(testParams.getRuns()).thenReturn(2)
        Mockito.`when`<T?>(testParams.getShards()).thenReturn(3)

        val testProvider: TestProvider = TestProvider(testParams)
        Mockito.`when`<T?>(stubTarget.getProvider(< T > eq < T ? > (TestProvider::class.java))).thenReturn(testProvider)

        val pathConverter: PathConverter = Mockito.mock<PathConverter>(PathConverter::class.java)
        Mockito.`when`<T?>(pathConverter.apply(ArgumentMatchers.any<T?>(Path::class.java)))
            .thenAnswer(
                Answer { invocation: InvocationOnMock? -> "/path/to" + (invocation.getArguments()[0] as Path).getPathString() })

        val converters: BuildEventContext = Mockito.mock<BuildEventContext>(BuildEventContext::class.java)
        Mockito.`when`<T?>(converters.pathConverter()).thenReturn(pathConverter)

        val summary: TestSummary =
            basicBuilder
                .setStatus(BlazeTestStatus.FAILED)
                .addPassedLogs(getPathList("/apple"))
                .addFailedLogs(getPathList("/pear"))
                .mergeTiming(1000, 300)
                .build()

        assertThat(summary.asStreamProto(converters).getTestSummary())
            .isEqualTo(
                BuildEventStreamProtos.TestSummary.newBuilder()
                    .setOverallStatus(TestStatus.FAILED)
                    .setFirstStartTimeMillis(1000)
                    .setFirstStartTime(Timestamps.fromMillis(1000))
                    .setLastStopTimeMillis(1300)
                    .setLastStopTime(Timestamps.fromMillis(1300))
                    .setTotalRunDurationMillis(300)
                    .setTotalRunDuration(Durations.fromMillis(300))
                    .setRunCount(2)
                    .setShardCount(3)
                    .addPassed(BuildEventStreamProtos.File.newBuilder().setUri("/path/to/apple"))
                    .addFailed(BuildEventStreamProtos.File.newBuilder().setUri("/path/to/pear"))
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleTime() {
        val expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED + ANY_STRING +
                "in 3.4s"
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary? =
            basicBuilder.addTestTimes(com.google.common.collect.ImmutableList.of<E?>(3412L)).build()
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)
        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoTime() {
        // The last part matches anything not containing "in".
        val expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED + "(?!in)*"
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary? =
            basicBuilder.addTestTimes(com.google.common.collect.ImmutableList.of<E?>(3412L)).build()
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, false, false)
        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleTimes() {
        val expectedString = ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED + ANY_STRING +
                "\n  Stats over 3 runs: max = 3.0s, min = 1.0s, " +
                "avg = 2.0s, dev = 0.8s"
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)
        val summary: TestSummary? = basicBuilder
            .addTestTimes(com.google.common.collect.ImmutableList.of<E?>(1000L, 2000L, 3000L))
            .build()
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)
        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCoverageDataReferences() {
        val paths: MutableList<Path?> = getPathList("/cov1.dat", "/cov2.dat", "/cov3.dat", "/cov4.dat")
        FileSystemUtils.writeContentAsLatin1(paths.get(1), "something")
        FileSystemUtils.writeContentAsLatin1(paths.get(3), "")
        FileSystemUtils.writeContentAsLatin1(paths.get(3), "something else")
        val summary: TestSummary? = basicBuilder.addCoverageFiles(paths).build()

        val terminalPrinter: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)
        Mockito.verify<Any?>(terminalPrinter)
            .print(AdditionalMatchers.find(ANY_STRING + "INFO" + ANY_STRING + BlazeTestStatus.PASSED))
        Mockito.verify<Any?>(terminalPrinter).print(AdditionalMatchers.find("  /cov2.dat"))
        Mockito.verify<Any?>(terminalPrinter).print(AdditionalMatchers.find("  /cov4.dat"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlakyAttempts() {
        val expectedString = ANY_STRING + "WARNING" + ANY_STRING + BlazeTestStatus.FLAKY +
                ANY_STRING + ", failed in 2 out of 3"
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary? = basicBuilder
            .setStatus(BlazeTestStatus.FLAKY)
            .addPassedLogs(getPathList("/a"))
            .addFailedLogs(getPathList("/b", "/c"))
            .build()
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)
        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNumberOfFailedRuns() {
        val expectedString = ANY_STRING + "ERROR" + ANY_STRING + BlazeTestStatus.FAILED +
                ANY_STRING + "in 2 out of 3"
        val terminalPrinter: AnsiTerminalPrinter = Mockito.mock<AnsiTerminalPrinter>(AnsiTerminalPrinter::class.java)

        val summary: TestSummary? = basicBuilder
            .setStatus(BlazeTestStatus.FAILED)
            .addPassedLogs(getPathList("/a"))
            .addFailedLogs(getPathList("/b", "/c"))
            .build()
        TestSummaryPrinter.print(summary, terminalPrinter, Path::getPathString, true, false)
        terminalPrinter.print(AdditionalMatchers.find(expectedString))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileNamesNotShown() {
        val emptyDetails: MutableList<TestCase?> = com.google.common.collect.ImmutableList.of<TestCase?>()
        val summary: TestSummary? = basicBuilder
            .setStatus(BlazeTestStatus.FAILED)
            .addPassedLogs(getPathList("/apple"))
            .addFailedLogs(getPathList("/pear"))
            .addCoverageFiles(getPathList("/maracuja"))
            .addFailedTestCases(emptyDetails, FailedTestCasesStatus.FULL)
            .build()

        // Check that only //package:name is printed.
        val printer: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true)
        Mockito.verify<Any?>(printer).print(ArgumentMatchers.contains("//package:name"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMessageShownWhenTestCasesMissing() {
        val emptyList: com.google.common.collect.ImmutableList<TestCase?> =
            com.google.common.collect.ImmutableList.of<TestCase?>()
        val summary: TestSummary? = createTestSummaryWithDetails(
            BlazeTestStatus.FAILED, emptyList, FailedTestCasesStatus.NOT_AVAILABLE
        )

        val printer: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true)
        Mockito.verify<Any?>(printer).print(ArgumentMatchers.contains("//package:name"))
        Mockito.verify<Any?>(printer).print(ArgumentMatchers.contains("not available"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMessageShownForPartialResults() {
        val testCases: com.google.common.collect.ImmutableList<TestCase?> =
            com.google.common.collect.ImmutableList.of<TestCase?>(newDetail("orange", TestCase.Status.FAILED, 1500L))
        val summary: TestSummary? = createTestSummaryWithDetails(
            BlazeTestStatus.FAILED, testCases,
            FailedTestCasesStatus.PARTIAL
        )

        val printer: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true)
        Mockito.verify<Any?>(printer).print(ArgumentMatchers.contains("//package:name"))
        Mockito.verify<Any?>(printer).print(AdditionalMatchers.find("FAILED.*orange"))
        Mockito.verify<Any?>(printer).print(ArgumentMatchers.contains("incomplete"))
    }

    private fun newDetail(name: String?, status: TestCase.Status?, duration: Long): TestCase {
        return TestCase.newBuilder()
            .setName(name)
            .setStatus(status)
            .setRunDurationMillis(duration)
            .build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestCaseNamesShownWhenNeeded() {
        val detailPassed: TestCase =
            newDetail("strawberry", TestCase.Status.PASSED, 1000L)
        val detailFailed: TestCase =
            newDetail("orange", TestCase.Status.FAILED, 1500L)

        val summaryPassed: TestSummary = createTestSummaryWithDetails(
            BlazeTestStatus.PASSED, java.util.Arrays.asList<TestCase?>(detailPassed)
        )

        val summaryFailed: TestSummary = createTestSummaryWithDetails(
            BlazeTestStatus.FAILED, java.util.Arrays.asList<TestCase?>(detailPassed, detailFailed)
        )
        assertThat(summaryFailed.getStatus()).isEqualTo(BlazeTestStatus.FAILED)

        val printerPassed: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summaryPassed, printerPassed, Path::getPathString, true, true)
        Mockito.verify<Any?>(printerPassed).print(ArgumentMatchers.contains("//package:name"))

        val printerFailed: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summaryFailed, printerFailed, Path::getPathString, true, true)
        Mockito.verify<Any?>(printerFailed).print(ArgumentMatchers.contains("//package:name"))
        Mockito.verify<Any?>(printerFailed).print(AdditionalMatchers.find("FAILED.*orange *\\(1\\.5"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShowTestCaseNames() {
        val detailPassed: TestCase = newDetail("strawberry", TestCase.Status.PASSED, 1000L)
        val detailFailed: TestCase = newDetail("orange", TestCase.Status.FAILED, 1500L)

        val summaryPassed: TestSummary =
            createPassedTestSummary(BlazeTestStatus.PASSED, java.util.Arrays.asList<TestCase?>(detailPassed))

        val summaryFailed: TestSummary =
            createTestSummaryWithDetails(
                BlazeTestStatus.FAILED, java.util.Arrays.asList<TestCase?>(detailPassed, detailFailed)
            )
        assertThat(summaryFailed.getStatus()).isEqualTo(BlazeTestStatus.FAILED)

        val printerPassed: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summaryPassed, printerPassed, Path::getPathString, true, true)
        Mockito.verify<Any?>(printerPassed).print(ArgumentMatchers.contains("//package:name"))
        Mockito.verify<Any?>(printerPassed).print(AdditionalMatchers.find("PASSED.*strawberry *\\(1\\.0"))

        val printerFailed: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summaryFailed, printerFailed, Path::getPathString, true, true)
        Mockito.verify<Any?>(printerFailed).print(ArgumentMatchers.contains("//package:name"))
        Mockito.verify<Any?>(printerFailed).print(AdditionalMatchers.find("FAILED.*orange *\\(1\\.5"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestCaseNamesOrdered() {
        val details: Array<TestCase?> = arrayOf<TestCase?>(
            newDetail("apple", TestCase.Status.FAILED, 1000L),
            newDetail("banana", TestCase.Status.FAILED, 1000L),
            newDetail("cranberry", TestCase.Status.FAILED, 1000L)
        )

        // The exceedingly dumb approach: writing all the permutations down manually
        // is simply easier than any way of generating them.
        val permutations = arrayOf<IntArray>(
            intArrayOf(0, 1, 2),
            intArrayOf(0, 2, 1),
            intArrayOf(1, 0, 2),
            intArrayOf(1, 2, 0),
            intArrayOf(2, 0, 1),
            intArrayOf(2, 1, 0)
        )

        for (permutation in permutations) {
            val permutatedDetails: MutableList<TestCase?> = java.util.ArrayList<TestCase?>()

            for (element in permutation) {
                permutatedDetails.add(details[element])
            }

            val summary: TestSummary = createTestSummaryWithDetails(BlazeTestStatus.FAILED, permutatedDetails)

            // A mock that checks the ordering of method calls
            val printer: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
            TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true)
            val order: InOrder = Mockito.inOrder(printer)
            order.verify<Any?>(printer).print(ArgumentMatchers.contains("//package:name"))
            order.verify<Any?>(printer).print(AdditionalMatchers.find("FAILED.*apple"))
            order.verify<Any?>(printer).print(AdditionalMatchers.find("FAILED.*banana"))
            order.verify<Any?>(printer).print(AdditionalMatchers.find("FAILED.*cranberry"))
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCachedResultsFirstInSort() {
        val summaryFailedCached: TestSummary = createTestSummary(BlazeTestStatus.FAILED, CACHED)
        val summaryFailedNotCached: TestSummary = createTestSummary(BlazeTestStatus.FAILED, NOT_CACHED)
        val summaryPassedCached: TestSummary = createTestSummary(BlazeTestStatus.PASSED, CACHED)
        val summaryPassedNotCached: TestSummary = createTestSummary(BlazeTestStatus.PASSED, NOT_CACHED)

        // This way we can make the test independent from the sort order of FAILEd
        // and PASSED.
        assertThat(summaryFailedCached.compareTo(summaryPassedNotCached)).isLessThan(0)
        assertThat(summaryPassedCached.compareTo(summaryFailedNotCached)).isLessThan(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCollectingFailedDetails() {
        val rootCase: TestCase? =
            TestCase.newBuilder()
                .setName("tests")
                .setRunDurationMillis(5000L)
                .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
                .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
                .build()

        val summary: TestSummary? =
            this.templateBuilder.collectTestCases(rootCase).setStatus(BlazeTestStatus.FAILED).build()

        val printer: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true)
        Mockito.verify<Any?>(printer).print(ArgumentMatchers.contains("//package:name"))
        Mockito.verify<Any?>(printer).print(AdditionalMatchers.find("FAILED.*apple"))
        Mockito.verify<Any?>(printer).print(AdditionalMatchers.find("ERROR.*cherry"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCollectingAllDetails() {
        val rootCase: TestCase? =
            TestCase.newBuilder()
                .setName("tests")
                .setRunDurationMillis(5000L)
                .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
                .addChild(newDetail("banana", TestCase.Status.PASSED, 1000L))
                .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
                .addChild(newDetail("sugarcane", Status.SKIPPED, 0))
                .build()

        val summary: TestSummary? =
            this.templateBuilder.collectTestCases(rootCase).setStatus(BlazeTestStatus.FAILED).build()

        val printer: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true)
        Mockito.verify<Any?>(printer).print(ArgumentMatchers.contains("//package:name"))
        Mockito.verify<Any?>(printer).print(AdditionalMatchers.find("FAILED.*apple"))
        Mockito.verify<Any?>(printer).print(AdditionalMatchers.find("PASSED.*banana"))
        Mockito.verify<Any?>(printer).print(AdditionalMatchers.find("ERROR.*cherry"))
        Mockito.verify<Any?>(printer).print(AdditionalMatchers.find("SKIPPED.*sugarcane"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCollectingPassedDetails() {
        val rootCase: TestCase? =
            TestCase.newBuilder()
                .setName("tests")
                .setRunDurationMillis(5000L)
                .addChild(newDetail("apple", TestCase.Status.PASSED, 1000L))
                .addChild(newDetail("banana", Status.SKIPPED, 0))
                .build()

        val summary: TestSummary? =
            this.templateBuilder.collectTestCases(rootCase).setStatus(BlazeTestStatus.PASSED).build()

        val printer: AnsiTerminalPrinter? = Mockito.mock<AnsiTerminalPrinter?>(AnsiTerminalPrinter::class.java)
        TestSummaryPrinter.print(summary, printer, Path::getPathString, true, true)
        Mockito.verify<Any?>(printer).print(ArgumentMatchers.contains("//package:name"))
        Mockito.verify<Any?>(printer).print(AdditionalMatchers.find("PASSED.*apple"))
        Mockito.verify<Any?>(printer).print(AdditionalMatchers.find("SKIPPED.*banana"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun countTotalTestCases() {
        val rootCase: TestCase? =
            TestCase.newBuilder()
                .setName("tests")
                .setRunDurationMillis(5000L)
                .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
                .addChild(newDetail("banana", TestCase.Status.PASSED, 1000L))
                .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
                .addChild(newDetail("sugarcane", Status.SKIPPED, 0))
                .build()

        val summary: TestSummary =
            this.templateBuilder.collectTestCases(rootCase).setStatus(BlazeTestStatus.FAILED).build()

        assertThat(summary.getTotalTestCases()).isEqualTo(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun countUnknownTestCases() {
        val summary: TestSummary =
            this.templateBuilder.collectTestCases(null).setStatus(BlazeTestStatus.FAILED).build()

        assertThat(summary.getTotalTestCases()).isEqualTo(1)
        assertThat(summary.getUnknownTestCases()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun countNotRunTestCases() {
        val a: TestCase? =
            TestCase.newBuilder()
                .addChild(
                    TestCase.newBuilder().setName("A").setStatus(Status.PASSED).setRun(true).build()
                )
                .addChild(
                    TestCase.newBuilder().setName("B").setStatus(Status.PASSED).setRun(true).build()
                )
                .addChild(
                    TestCase.newBuilder().setName("C").setStatus(Status.PASSED).setRun(false).build()
                )
                .build()
        val summary: TestSummary =
            this.templateBuilder.collectTestCases(a).setStatus(BlazeTestStatus.FAILED).build()

        assertThat(summary.getTotalTestCases()).isEqualTo(2)
        assertThat(summary.getUnknownTestCases()).isEqualTo(0)
        assertThat(summary.getFailedTestCases()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun countTotalTestCasesInNestedTree() {
        val aCase: TestCase? =
            TestCase.newBuilder()
                .setName("tests-1")
                .setRunDurationMillis(5000L)
                .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
                .addChild(newDetail("banana", TestCase.Status.PASSED, 1000L))
                .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
                .build()
        val anotherCase: TestCase? =
            TestCase.newBuilder()
                .setName("tests-2")
                .setRunDurationMillis(5000L)
                .addChild(newDetail("apple", TestCase.Status.FAILED, 1000L))
                .addChild(newDetail("banana", TestCase.Status.PASSED, 1000L))
                .addChild(newDetail("cherry", TestCase.Status.ERROR, 1000L))
                .build()

        val rootCase: TestCase? =
            TestCase.newBuilder().setName("tests").addChild(aCase).addChild(anotherCase).build()

        val summary: TestSummary =
            this.templateBuilder.collectTestCases(rootCase).setStatus(BlazeTestStatus.FAILED).build()

        assertThat(summary.getTotalTestCases()).isEqualTo(6)
    }

    @Throws(java.lang.Exception::class)
    private fun target(path: String?, targetName: String?): ConfiguredTarget {
        val target: ConfiguredTarget = Mockito.mock<ConfiguredTarget>(ConfiguredTarget::class.java)
        Mockito.`when`<T?>(target.getLabel()).thenReturn(Label.create(path, targetName))
        Mockito.`when`<T?>(target.getConfigurationChecksum()).thenReturn("abcdef")
        val mockParams: TestParams = Mockito.mock<TestParams>(TestParams::class.java)
        Mockito.`when`<T?>(mockParams.getShards()).thenReturn(1)
        Mockito.`when`<T?>(target.getProvider(TestProvider::class.java)).thenReturn(TestProvider(mockParams))
        return target
    }

    @Throws(java.lang.Exception::class)
    private fun stubTarget(): ConfiguredTarget {
        return target(PATH, TARGET_NAME)
    }

    private fun createPassedTestSummary(status: BlazeTestStatus?, details: MutableList<TestCase?>?): TestSummary {
        return this.templateBuilder.setStatus(status).addPassedTestCases(details).build()
    }

    private fun createTestSummaryWithDetails(
        status: BlazeTestStatus?,
        details: MutableList<TestCase?>?
    ): TestSummary {
        val summary: TestSummary = this.templateBuilder
            .setStatus(status)
            .addFailedTestCases(details, FailedTestCasesStatus.FULL)
            .build()
        return summary
    }

    private fun createTestSummaryWithDetails(
        status: BlazeTestStatus?, testCaseList: MutableList<TestCase?>?,
        detailsStatus: FailedTestCasesStatus?
    ): TestSummary? {
        val summary: TestSummary? = this.templateBuilder
            .setStatus(status)
            .addFailedTestCases(testCaseList, detailsStatus)
            .build()
        return summary
    }

    private fun createTestSummary(status: BlazeTestStatus?, numCached: Int): TestSummary {
        val summary: TestSummary = this.templateBuilder
            .setStatus(status)
            .setNumCached(numCached)
            .addTestTimes(SMALL_TIMING)
            .build()
        return summary
    }

    companion object {
        private const val ANY_STRING = ".*?"
        private const val PATH = "package"
        private const val TARGET_NAME = "name"
        private val SMALL_TIMING: com.google.common.collect.ImmutableList<Long?> =
            com.google.common.collect.ImmutableList.of<Long?>(1L, 2L, 3L, 4L)

        private val CACHED: Int = SMALL_TIMING.size
        private const val NOT_CACHED = 0

        private fun createTestSummary(
            target: ConfiguredTarget?, status: BlazeTestStatus?,
            numCached: Int
        ): TestSummary {
            val emptyList: com.google.common.collect.ImmutableList<TestCase?> =
                com.google.common.collect.ImmutableList.of<TestCase?>()
            return TestSummary.newBuilder(target)
                .setStatus(status)
                .setNumCached(numCached)
                .setActionRan(true)
                .setRanRemotely(false)
                .addFailedTestCases(emptyList, FailedTestCasesStatus.FULL)
                .addTestTimes(SMALL_TIMING)
                .build()
        }
    }
}
