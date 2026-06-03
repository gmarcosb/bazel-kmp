// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.common

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [QueryTransitivePackagePreloader].  */
@RunWith(TestParameterInjector::class)
class QueryTransitivePackagePreloaderTest {
    @org.mockito.Mock
    var memoizingEvaluator: MemoizingEvaluator? = null

    @org.mockito.Mock
    var contextBuilder: EvaluationContext.Builder? = null

    @org.mockito.Mock
    var context: EvaluationContext? = null
    private val bugReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)

    private val underTest: QueryTransitivePackagePreloader = QueryTransitivePackagePreloader(
        { memoizingEvaluator }, { contextBuilder }, bugReporter
    )
    private var closeable: java.lang.AutoCloseable? = null

    @Before
    fun setUpMocks() {
        closeable = MockitoAnnotations.openMocks(this)
        Mockito.`when`<T?>(contextBuilder.setKeepGoing(ArgumentMatchers.anyBoolean())).thenReturn(contextBuilder)
        Mockito.`when`<T?>(contextBuilder.setParallelism(ArgumentMatchers.anyInt())).thenReturn(contextBuilder)
        Mockito.`when`<T?>(contextBuilder.setEventHandler(ArgumentMatchers.any<T?>())).thenReturn(contextBuilder)
        Mockito.`when`<T?>(contextBuilder.setDetectCycles(ArgumentMatchers.anyBoolean())).thenReturn(contextBuilder)
        Mockito.`when`<T?>(contextBuilder.build()).thenReturn(context)
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun releaseMocks() {
        Mockito.verifyNoMoreInteractions(memoizingEvaluator)
        Mockito.verifyNoMoreInteractions(bugReporter)
        closeable.close()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun preloadTransitiveTargets_noError() {
        val roots: MutableList<TransitiveTargetKey?> = com.google.common.collect.Lists.newArrayList(KEY)

        Mockito.`when`<T?>(memoizingEvaluator.evaluate(roots, context))
            .thenReturn(EvaluationResult.builder().build())

        underTest.preloadTransitiveTargets(
            < T > mock < T ? > (ExtendedEventHandler::class.java),
        com.google.common.collect.ImmutableList.of<E?>(LABEL),  /*keepGoing=*/
        true,
        1,  /*callerForError=*/
        null)

        Mockito.verify<Any?>(memoizingEvaluator).evaluate(roots, context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun preloadTransitiveTargets_errorWithNullCallerKeepGoing_doesntCleanGraph() {
        val roots: MutableList<TransitiveTargetKey?> = com.google.common.collect.Lists.newArrayList(KEY)

        Mockito.`when`<T?>(memoizingEvaluator.evaluate(roots, context))
            .thenReturn(EvaluationResult.builder().addError(KEY, UNDETAILED_ERROR).build())

        underTest.preloadTransitiveTargets(
            < T > mock < T ? > (ExtendedEventHandler::class.java),
        com.google.common.collect.ImmutableList.of<E?>(LABEL),  /*keepGoing=*/
        true,
        1,  /*callerForError=*/
        null)

        Mockito.verify<Any?>(memoizingEvaluator).evaluate(roots, context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun preloadTransitiveTargets_errorWithNullCallerKeepGoingCatastrophe_cleansGraph() {
        val roots: MutableList<TransitiveTargetKey?> = com.google.common.collect.Lists.newArrayList(KEY)

        Mockito.`when`<T?>(memoizingEvaluator.evaluate(roots, context))
            .thenReturn(
                EvaluationResult.builder()
                    .setCatastrophe(UndetailedException("catas"))
                    .addError(KEY, UNDETAILED_ERROR)
                    .build()
            )

        underTest.preloadTransitiveTargets(
            < T > mock < T ? > (ExtendedEventHandler::class.java),
        com.google.common.collect.ImmutableList.of<E?>(LABEL),  /*keepGoing=*/
        true,
        1,  /*callerForError=*/
        null)

        Mockito.verify<Any?>(memoizingEvaluator).evaluate(roots, context)
        Mockito.verify<Any?>(memoizingEvaluator).evaluate(com.google.common.collect.ImmutableList.of<E?>(), context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun preloadTransitiveTargets_errorWithNullCallerNoKeepGoing_cleansGraph() {
        val roots: MutableList<TransitiveTargetKey?> = com.google.common.collect.Lists.newArrayList(KEY)

        Mockito.`when`<T?>(memoizingEvaluator.evaluate(roots, context))
            .thenReturn(EvaluationResult.builder().addError(KEY, UNDETAILED_ERROR).build())

        underTest.preloadTransitiveTargets(
            < T > mock < T ? > (ExtendedEventHandler::class.java),
        com.google.common.collect.ImmutableList.of<E?>(LABEL),  /*keepGoing=*/
        false,
        1,  /*callerForError=*/
        null)

        Mockito.verify<Any?>(memoizingEvaluator).evaluate(roots, context)
        Mockito.verify<Any?>(memoizingEvaluator).evaluate(com.google.common.collect.ImmutableList.of<E?>(), context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun preloadTransitiveTargets_detailedErrorWithCaller_throwsError(
        @TestParameter keepGoing: Boolean
    ) {
        val roots: MutableList<TransitiveTargetKey?> = com.google.common.collect.Lists.newArrayList(KEY)

        Mockito.`when`<T?>(memoizingEvaluator.evaluate(roots, context))
            .thenReturn(EvaluationResult.builder().addError(KEY, DETAILED_ERROR).build())

        val e: com.google.devtools.build.lib.query2.engine.QueryException =
            org.junit.Assert.assertThrows<com.google.devtools.build.lib.query2.engine.QueryException>(
                com.google.devtools.build.lib.query2.engine.QueryException::class.java,
                org.junit.function.ThrowingRunnable {
                    underTest.preloadTransitiveTargets(
                        < T > mock < T ? > (ExtendedEventHandler::class.java),
                    com.google.common.collect.ImmutableList.of<E?>(LABEL),
                    keepGoing,
                    1,  /*callerForError=*/
                    <T > mock<T?>(QueryExpression::class.java))
                })
        Truth.assertThat(e).hasMessageThat().contains("failed: bork")
        assertThat(e.getFailureDetail())
            .isSameInstanceAs(MyDetailedException.Companion.DETAILED_EXIT_CODE.getFailureDetail())

        Mockito.verify<Any?>(memoizingEvaluator).evaluate(roots, context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun preloadTransitiveTargets_undetailedErrorWithCaller_throwsErrorAndFilesBugReport(
        @TestParameter keepGoing: Boolean
    ) {
        val roots: MutableList<TransitiveTargetKey?> = com.google.common.collect.Lists.newArrayList(KEY)

        Mockito.`when`<T?>(memoizingEvaluator.evaluate(roots, context))
            .thenReturn(EvaluationResult.builder().addError(KEY, UNDETAILED_ERROR).build())

        val e: com.google.devtools.build.lib.query2.engine.QueryException =
            org.junit.Assert.assertThrows<com.google.devtools.build.lib.query2.engine.QueryException>(
                com.google.devtools.build.lib.query2.engine.QueryException::class.java,
                org.junit.function.ThrowingRunnable {
                    underTest.preloadTransitiveTargets(
                        < T > mock < T ? > (ExtendedEventHandler::class.java),
                    com.google.common.collect.ImmutableList.of<E?>(LABEL),
                    keepGoing,
                    1,  /*callerForError=*/
                    <T > mock<T?>(QueryExpression::class.java))
                })
        Truth.assertThat(e).hasMessageThat().contains("failed: bork")
        assertThat(e.getFailureDetail())
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                FailureDetails.FailureDetail.newBuilder()
                    .setQuery(
                        FailureDetails.Query.newBuilder()
                            .setCode(FailureDetails.Query.Code.NON_DETAILED_ERROR)
                            .build()
                    )
                    .build()
            )

        Mockito.verify<Any?>(memoizingEvaluator).evaluate(roots, context)
        Mockito.verify<BugReporter?>(bugReporter).sendNonFatalBugReport(ArgumentMatchers.any<Throwable?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun preloadTransitiveTargets_undetailedCatastropheAndDetailedExceptionWithCaller_throwsErrorAndFilesBugReport(
        @TestParameter keepGoing: Boolean
    ) {
        val roots: MutableList<TransitiveTargetKey?> = com.google.common.collect.Lists.newArrayList(KEY)

        Mockito.`when`<T?>(memoizingEvaluator.evaluate(roots, context))
            .thenReturn(
                EvaluationResult.builder()
                    .addError(KEY, DETAILED_ERROR)
                    .setCatastrophe(UndetailedException("undetailed bok"))
                    .build()
            )

        val e: com.google.devtools.build.lib.query2.engine.QueryException =
            org.junit.Assert.assertThrows<com.google.devtools.build.lib.query2.engine.QueryException>(
                com.google.devtools.build.lib.query2.engine.QueryException::class.java,
                org.junit.function.ThrowingRunnable {
                    underTest.preloadTransitiveTargets(
                        < T > mock < T ? > (ExtendedEventHandler::class.java),
                    com.google.common.collect.ImmutableList.of<E?>(LABEL),
                    keepGoing,
                    1,  /*callerForError=*/
                    <T > mock<T?>(QueryExpression::class.java))
                })
        Truth.assertThat(e).hasMessageThat().contains("failed: undetailed bok")
        assertThat(e.getFailureDetail())
            .comparingExpectedFieldsOnly()
            .isEqualTo(
                FailureDetails.FailureDetail.newBuilder()
                    .setQuery(
                        FailureDetails.Query.newBuilder()
                            .setCode(FailureDetails.Query.Code.NON_DETAILED_ERROR)
                            .build()
                    )
                    .build()
            )

        Mockito.verify<Any?>(memoizingEvaluator).evaluate(roots, context)
        Mockito.verify<BugReporter?>(bugReporter).sendNonFatalBugReport(ArgumentMatchers.any<Throwable?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun preloadTransitiveTargets_undetailedAndDetailedExceptionsWithCaller_throwsError(
        @TestParameter keepGoing: Boolean, @TestParameter includeCycle: Boolean
    ) {
        val roots: MutableList<TransitiveTargetKey?> =
            com.google.common.collect.Lists.newArrayList<TransitiveTargetKey?>(
                KEY, KEY2, KEY3
            )

        val resultBuilder: EvaluationResult.Builder<SkyValue?> =
            EvaluationResult.builder().addError(KEY, UNDETAILED_ERROR).addError(KEY2, DETAILED_ERROR)
        if (includeCycle) {
            resultBuilder.addError(KEY3, CYCLE_ERROR)
        }
        Mockito.`when`<T?>(memoizingEvaluator.evaluate(roots, context)).thenReturn(resultBuilder.build())

        val e: com.google.devtools.build.lib.query2.engine.QueryException =
            org.junit.Assert.assertThrows<com.google.devtools.build.lib.query2.engine.QueryException>(
                com.google.devtools.build.lib.query2.engine.QueryException::class.java,
                org.junit.function.ThrowingRunnable {
                    underTest.preloadTransitiveTargets(
                        < T > mock < T ? > (ExtendedEventHandler::class.java),
                    com.google.common.collect.ImmutableList.of<E?>(LABEL, LABEL2, LABEL3),
                    keepGoing,
                    1,  /*callerForError=*/
                    <T > mock<T?>(QueryExpression::class.java))
                })
        Truth.assertThat(e).hasMessageThat().contains("failed: bork")
        assertThat(e.getFailureDetail())
            .isSameInstanceAs(MyDetailedException.Companion.DETAILED_EXIT_CODE.getFailureDetail())

        Mockito.verify<Any?>(memoizingEvaluator).evaluate(roots, context)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun preloadTransitiveTargets_cycleOnly_returns() {
        val roots: MutableList<TransitiveTargetKey?> = com.google.common.collect.Lists.newArrayList(KEY)

        Mockito.`when`<T?>(memoizingEvaluator.evaluate(roots, context))
            .thenReturn(EvaluationResult.builder().addError(KEY, CYCLE_ERROR).build())

        underTest.preloadTransitiveTargets(
            < T > mock < T ? > (ExtendedEventHandler::class.java),
        com.google.common.collect.ImmutableList.of<E?>(LABEL),  /*keepGoing=*/
        true,
        1,  /*callerForError=*/
        null)

        Mockito.verify<Any?>(memoizingEvaluator).evaluate(roots, context)
    }

    private class UndetailedException(message: String?) : java.lang.Exception(message)

    private class MyDetailedException(message: String?) : java.lang.Exception(message), DetailedException {
        val detailedExitCode: DetailedExitCode
            get() = DETAILED_EXIT_CODE

        companion object {
            private val DETAILED_EXIT_CODE: DetailedExitCode = DetailedExitCode.of(
                FailureDetails.FailureDetail.newBuilder()
                    .setQuery(
                        FailureDetails.Query.newBuilder()
                            .setCode(FailureDetails.Query.Code.BUILD_FILE_ERROR)
                    )
                    .build()
            )
        }
    }

    companion object {
        private val LABEL: Label = Label.parseCanonicalUnchecked("//my:label")
        private val LABEL2: Label? = Label.parseCanonicalUnchecked("//my:label2")
        private val LABEL3: Label? = Label.parseCanonicalUnchecked("//my:label3")
        private val KEY: TransitiveTargetKey = TransitiveTargetKey.of(LABEL)
        private val KEY2: TransitiveTargetKey? = TransitiveTargetKey.of(LABEL2)
        private val KEY3: TransitiveTargetKey? = TransitiveTargetKey.of(LABEL3)

        private val DETAILED_ERROR: ErrorInfo? = ErrorInfo.fromException(
            ReifiedSkyFunctionException(
                object : SkyFunctionException(
                    MyDetailedException("bork"), SkyFunctionException.Transience.PERSISTENT
                ) {}),  /*isTransitivelyTransient=*/
            false
        )
        private val UNDETAILED_ERROR: ErrorInfo? = ErrorInfo.fromException(
            ReifiedSkyFunctionException(
                object : SkyFunctionException(
                    UndetailedException("bork"), SkyFunctionException.Transience.PERSISTENT
                ) {}),  /*isTransitivelyTransient=*/
            false
        )
        private val CYCLE_ERROR: ErrorInfo? = ErrorInfo.fromCycle(
            CycleInfo.createCycleInfo(
                com.google.common.collect.ImmutableList.of<E?>(
                    KEY
                )
            )
        )
    }
}
