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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.SkyFunctionException.ReifiedSkyFunctionException

/** Tests for the non-trivial creation logic of [ErrorInfo].  */
@RunWith(JUnit4::class)
class ErrorInfoTest {
    /** Dummy SkyFunctionException implementation for the sake of testing.  */
    private class DummySkyFunctionException(
        cause: java.lang.Exception?,
        isTransient: Boolean,
        val isCatastrophic: Boolean
    ) : SkyFunctionException(cause, if (isTransient) Transience.TRANSIENT else Transience.PERSISTENT)

    @org.junit.Test
    fun testFromException_NonTransient() {
        runTestFromException( /* isDirectlyTransient= */false,  /* isTransitivelyTransient= */false)
    }

    @org.junit.Test
    fun testFromException_DirectlyTransient() {
        runTestFromException( /* isDirectlyTransient= */true,  /* isTransitivelyTransient= */false)
    }

    @org.junit.Test
    fun testFromException_TransitivelyTransient() {
        runTestFromException( /* isDirectlyTransient= */false,  /* isTransitivelyTransient= */true)
    }

    @org.junit.Test
    fun testFromException_DirectlyAndTransitivelyTransient() {
        runTestFromException( /* isDirectlyTransient= */true,  /* isTransitivelyTransient= */true)
    }

    @org.junit.Test
    fun testFromCycle() {
        val cycle: CycleInfo? =
            CycleInfo.createCycleInfo(
                com.google.common.collect.ImmutableList.of<E?>(GraphTester.Companion.skyKey("PATH, 1234")),
                com.google.common.collect.ImmutableList.of<E?>(GraphTester.Companion.skyKey("CYCLE, 4321"))
            )

        val errorInfo: ErrorInfo = ErrorInfo.fromCycle(cycle)

        assertThat(errorInfo.getException()).isNull()
        assertThat(errorInfo.isTransitivelyTransient).isFalse()
        assertThat(errorInfo.isCatastrophic).isFalse()
    }

    @org.junit.Test
    fun testFromChildErrors() {
        val cycle: CycleInfo =
            CycleInfo.createCycleInfo(
                com.google.common.collect.ImmutableList.of<E?>(GraphTester.Companion.skyKey("PATH, 1234")),
                com.google.common.collect.ImmutableList.of<E?>(GraphTester.Companion.skyKey("CYCLE, 4321"))
            )
        val cycleErrorInfo: ErrorInfo? = ErrorInfo.fromCycle(cycle)

        val exception1: java.lang.Exception = IOException("ehhhhh")
        val dummyException1 =
            DummySkyFunctionException(
                exception1,  /* isTransient= */true,  /* isCatastrophic= */false
            )
        val exceptionErrorInfo1: ErrorInfo? =
            ErrorInfo.fromException(
                ReifiedSkyFunctionException(dummyException1),  /* isTransitivelyTransient= */false
            )

        // N.B this ErrorInfo will be catastrophic.
        val exception2: java.lang.Exception = IOException("blahhhhh")
        val dummyException2 =
            DummySkyFunctionException(
                exception2,  /* isTransient= */false,  /* isCatastrophic= */true
            )
        val exceptionErrorInfo2: ErrorInfo? =
            ErrorInfo.fromException(
                ReifiedSkyFunctionException(dummyException2),  /* isTransitivelyTransient= */false
            )

        val currentKey: SkyKey? = GraphTester.Companion.skyKey("CURRENT, 9876")

        val errorInfo: ErrorInfo =
            ErrorInfo.fromChildErrors(
                currentKey,
                com.google.common.collect.ImmutableList.of<E?>(cycleErrorInfo, exceptionErrorInfo1, exceptionErrorInfo2)
            )

        // For simplicity we test the current implementation detail that we choose the first non-null
        // exception that we encounter. This isn't necessarily a requirement of the interface, but it
        // makes the test convenient and is a way to document the current behavior.
        assertThat(errorInfo.getException()).isSameInstanceAs(exception1)

        assertThat(errorInfo.getCycleInfo())
            .containsExactly(
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(
                        currentKey,
                        com.google.common.collect.Iterables.getOnlyElement<T?>(cycle.pathToCycle)
                    ),
                    cycle.cycle
                )
            )
        assertThat(errorInfo.isTransitivelyTransient).isTrue()
        assertThat(errorInfo.isCatastrophic).isTrue()
    }

    @org.junit.Test
    fun cannotCreateErrorInfoWithoutExceptionOrCycle() {
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
                java.lang.RuntimeException::class.java,
                org.junit.function.ThrowingRunnable {
                    ErrorInfo( /* exception= */
                        null,  /* cycles= */com.google.common.collect.ImmutableList.of<E?>(), false, false, false
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("At least one of exception and cycles must be present")
    }

    @org.junit.Test
    fun cannotCreateErrorInfoWithDirectTransienceButNotTransitiveTransience() {
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
                java.lang.RuntimeException::class.java,
                org.junit.function.ThrowingRunnable {
                    ErrorInfo(
                        java.lang.Exception(),  /* cycles= */
                        com.google.common.collect.ImmutableList.of<E?>(),  /* isDirectlyTransient= */
                        true,  /* isTransitivelyTransient= */
                        false,  /* isCatastrophic= */
                        false
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Cannot be directly transient but not transitively transient")
    }

    companion object {
        private fun runTestFromException(
            isDirectlyTransient: Boolean, isTransitivelyTransient: Boolean
        ) {
            val exception: java.lang.Exception = IOException("ehhhhh")
            val dummyException =
                DummySkyFunctionException(exception, isDirectlyTransient,  /* isCatastrophic= */false)

            val errorInfo: ErrorInfo =
                ErrorInfo.fromException(
                    ReifiedSkyFunctionException(dummyException), isTransitivelyTransient
                )

            assertThat(errorInfo.getException()).isSameInstanceAs(exception)
            assertThat(errorInfo.getCycleInfo()).isEmpty()
            assertThat(errorInfo.isDirectlyTransient).isEqualTo(isDirectlyTransient)
            assertThat(errorInfo.isTransitivelyTransient)
                .isEqualTo(isDirectlyTransient || isTransitivelyTransient)
            assertThat(errorInfo.isCatastrophic).isFalse()
        }
    }
}
