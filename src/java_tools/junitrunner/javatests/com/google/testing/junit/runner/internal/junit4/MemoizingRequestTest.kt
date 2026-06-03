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

import com.google.common.truth.Truth
import com.google.testing.junit.runner.internal.junit4.MemoizingRequest.getRunner
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import junit.framework.TestCase
import org.mockito.Mockito

/**
 * Tests for `MemoizingRequest`.
 */
class MemoizingRequestTest : TestCase() {
    private var mockRequestDelegate: org.junit.runner.Request? = null

    @Throws(java.lang.Exception::class)
    override fun setUp() {
        super.setUp()
        mockRequestDelegate =
            Mockito.mock<org.junit.runner.Request>(org.junit.runner.Request::class.java, Mockito.RETURNS_MOCKS)
    }

    fun testConstructorDoesNoWork() {
        com.google.testing.junit.runner.internal.junit4.MemoizingRequest(mockRequestDelegate)

        Mockito.verifyNoMoreInteractions(mockRequestDelegate)
    }

    fun testMemoizesRunner() {
        val memoizingRequest: com.google.testing.junit.runner.internal.junit4.MemoizingRequest =
            com.google.testing.junit.runner.internal.junit4.MemoizingRequest(mockRequestDelegate)

        val firstRunner: org.junit.runner.Runner? = memoizingRequest.getRunner()
        val secondRunner: org.junit.runner.Runner? = memoizingRequest.getRunner()

        Truth.assertThat(secondRunner).isSameInstanceAs(firstRunner)
        Mockito.verify<org.junit.runner.Request?>(mockRequestDelegate).getRunner()
        Mockito.verifyNoMoreInteractions(mockRequestDelegate)
    }

    fun testOverridingCreateRunner() {
        val stubRunner: org.junit.runner.Runner? =
            Mockito.mock<org.junit.runner.Runner?>(org.junit.runner.Runner::class.java)
        val memoizingRequest: com.google.testing.junit.runner.internal.junit4.MemoizingRequest =
            object : com.google.testing.junit.runner.internal.junit4.MemoizingRequest(mockRequestDelegate) {
                protected override fun createRunner(delegate: org.junit.runner.Request?): org.junit.runner.Runner? {
                    return stubRunner
                }
            }

        val firstRunner: org.junit.runner.Runner? = memoizingRequest.getRunner()
        val secondRunner: org.junit.runner.Runner? = memoizingRequest.getRunner()

        Truth.assertThat(firstRunner).isSameInstanceAs(stubRunner)
        Truth.assertThat(secondRunner).isSameInstanceAs(firstRunner)
        Mockito.verifyNoMoreInteractions(mockRequestDelegate)
    }
}
