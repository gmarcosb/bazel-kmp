// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.server.FailureDetails

/** Tests for [CrashFailureDetails].  */
@RunWith(TestParameterInjector::class)
class CrashFailureDetailsTest {
    @org.junit.After
    fun restoreDefaultOomDetector() {
        CrashFailureDetails.setOomDetector({ false })
    }

    @org.junit.Test
    fun nestedThrowables() {
        // This test confirms that throwables' details are recorded: their messages, types, stack
        // frames, and causes. The outermost throwable is recorded at index 0.

        val failureDetail: FailureDetail =
            CrashFailureDetails.forThrowable(
                functionForStackFrameTests_A(functionForStackFrameTests_B())
            )

        assertThat(failureDetail.getMessage())
            .isEqualTo(
                String.format(
                    "Crashed: (%s) myMessage_A, (%s) myMessage_B",
                    TEST_EXCEPTION_NAME, TEST_EXCEPTION_NAME
                )
            )
        assertThat(failureDetail.hasCrash()).isTrue()
        val crash: Crash = failureDetail.getCrash()
        assertThat(crash.getCode()).isEqualTo(Code.CRASH_UNKNOWN)
        assertThat(crash.getOomCauseCategory()).isEqualTo(OomCauseCategory.NONE)

        assertThat(crash.getCausesCount()).isEqualTo(2)

        val outerCause: Throwable = crash.getCauses(0)
        assertThat(outerCause.getMessage()).isEqualTo("myMessage_A")
        assertThat(outerCause.getThrowableClass()).isEqualTo(TEST_EXCEPTION_NAME)
        assertThat(outerCause.getStackTraceCount()).isAtLeast(2)
        com.google.common.truth.Subject.contains(
            "com.google.devtools.build.lib.util.CrashFailureDetailsTest."
                    + "functionForStackFrameTests_A"
        )
        com.google.common.truth.Subject.contains("com.google.devtools.build.lib.util.CrashFailureDetailsTest.nestedThrowables")

        val innerCause: Throwable = crash.getCauses(1)
        assertThat(innerCause.getMessage()).isEqualTo("myMessage_B")
        assertThat(innerCause.getThrowableClass()).isEqualTo(TEST_EXCEPTION_NAME)
        assertThat(innerCause.getStackTraceCount()).isAtLeast(2)
        com.google.common.truth.Subject.contains(
            "com.google.devtools.build.lib.util.CrashFailureDetailsTest."
                    + "functionForStackFrameTests_B"
        )
        com.google.common.truth.Subject.contains("com.google.devtools.build.lib.util.CrashFailureDetailsTest.nestedThrowables")
    }

    @org.junit.Test
    fun causeLimit() {
        // This test confirms that at most 5 throwables are recorded.
        val inner5: TestException = com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("inner5")
        val inner4: TestException =
            com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("inner4", inner5)
        val inner3: TestException =
            com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("inner3", inner4)
        val inner2: TestException =
            com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("inner2", inner3)
        val inner1: TestException =
            com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("inner1", inner2)
        val outer: TestException =
            com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("outer", inner1)

        assertThat(CrashFailureDetails.forThrowable(outer).getCrash().getCausesCount()).isEqualTo(5)
    }

    @org.junit.Test
    fun testMessageLimit() {
        val exception: TestException =
            com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("x".repeat(5000))

        val crashMessage: String? =
            CrashFailureDetails.forThrowable(exception).getCrash().getCauses(0).getMessage()

        Truth.assertThat(crashMessage).hasLength(2000)
        Truth.assertThat(crashMessage).endsWith("[truncated]")
    }

    @org.junit.Test
    fun causeCycle() {
        // This test confirms that throwables in a cause cycle are visited at most once.
        val inner2: TestException = com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("inner2")
        val inner1: TestException =
            com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("inner1", inner2)
        val outer: TestException =
            com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("outer", inner1)
        inner2.initCause(inner1)

        val causesList: MutableList<Throwable?> =
            CrashFailureDetails.forThrowable(outer).getCrash().getCausesList()
        Truth.assertThat(causesList.stream().map<Any?>(FailureDetails.Throwable::getMessage))
            .containsExactly("outer", "inner1", "inner2")
            .inOrder()
    }

    @org.junit.Test
    fun deepStack() {
        val stackTraceList: ProtocolStringList? =
            CrashFailureDetails.forThrowable(functionForDeepStackTrace(1001))
                .getCrash()
                .getCauses(0)
                .getStackTraceList()
        Truth.assertThat(stackTraceList).hasSize(1000)

        // Check that the deepest 1000 frames were recorded:
        for (stackFrame in stackTraceList) {
            Truth.assertThat(stackFrame).contains("CrashFailureDetailsTest.functionForDeepStackTrace")
        }
    }

    @org.junit.Test
    fun detailedExitConstruction_oom() {
        val detailedExitCode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CrashFailureDetails.detailedExitCodeForThrowable(java.lang.OutOfMemoryError())

        assertThat(detailedExitCode.getExitCode()).isEqualTo(ExitCode.OOM_ERROR)
        assertThat(detailedExitCode.getFailureDetail().getCrash().getOomCauseCategory())
            .isEqualTo(OomCauseCategory.ORGANIC)
    }

    @org.junit.Test
    fun detailedExitConstruction_wrappedOom() {
        val detailedExitCode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CrashFailureDetails.detailedExitCodeForThrowable(
                java.lang.IllegalStateException(java.lang.OutOfMemoryError())
            )

        assertThat(detailedExitCode.getExitCode()).isEqualTo(ExitCode.OOM_ERROR)
        assertThat(detailedExitCode.getFailureDetail().getCrash().getOomCauseCategory())
            .isEqualTo(OomCauseCategory.ORGANIC)
    }

    @org.junit.Test
    fun detailedExitConstruction_otherCrash() {
        val detailedExitCode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CrashFailureDetails.detailedExitCodeForThrowable(java.lang.IllegalStateException())

        assertThat(detailedExitCode.getExitCode()).isEqualTo(ExitCode.BLAZE_INTERNAL_ERROR)
        assertThat(detailedExitCode.getFailureDetail().getCrash().getOomCauseCategory())
            .isEqualTo(OomCauseCategory.NONE)
    }

    private enum class ThrowableType(throwable: Throwable) {
        OUT_OF_MEMORY_ERROR(java.lang.OutOfMemoryError()),
        ILLEGAL_STATE_EXCEPTION(java.lang.IllegalStateException());

        val throwable: Throwable?

        init {
            this.throwable = throwable
        }
    }

    @org.junit.Test
    fun detailExitConstruction_crashWithOomDetector_returnsOomCrash(
        @TestParameter throwableType: ThrowableType
    ) {
        CrashFailureDetails.setOomDetector({ true })
        val detailedExitCode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CrashFailureDetails.detailedExitCodeForThrowable(throwableType.throwable)

        assertThat(detailedExitCode.getExitCode()).isEqualTo(ExitCode.OOM_ERROR)
        val expectedOomCauseCategory: OomCauseCategory? =
            if (throwableType == ThrowableType.OUT_OF_MEMORY_ERROR)
                OomCauseCategory.ORGANIC
            else
                OomCauseCategory.OOM_DETECTOR_OVERRIDE
        assertThat(detailedExitCode.getFailureDetail().getCrash().getOomCauseCategory())
            .isEqualTo(expectedOomCauseCategory)
    }

    private class TestException : java.lang.Exception {
        private constructor(message: String?) : super(message)

        private constructor(message: String?, cause: Throwable?) : super(message, cause)
    }

    companion object {
        private const val TEST_EXCEPTION_NAME =
            "com.google.devtools.build.lib.util.CrashFailureDetailsTest\$TestException"

        private fun functionForStackFrameTests_A(cause: TestException?): TestException {
            return com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("myMessage_A", cause)
        }

        private fun functionForStackFrameTests_B(): TestException {
            return com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("myMessage_B")
        }

        private fun functionForDeepStackTrace(framesToBuild: Int): TestException {
            if (framesToBuild <= 1) {
                return com.google.devtools.build.lib.util.CrashFailureDetailsTest.TestException("myMessage_deep")
            } else {
                return functionForDeepStackTrace(framesToBuild - 1)
            }
        }
    }
}
