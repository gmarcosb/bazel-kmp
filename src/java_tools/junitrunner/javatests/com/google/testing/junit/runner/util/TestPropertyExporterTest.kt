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
package com.google.testing.junit.runner.util

import com.google.testing.junit.runner.util.TestPropertyExporter
import com.google.testing.junit.runner.util.TestPropertyRunnerIntegration
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner

/**
 * Tests for [TestPropertyExporter].
 */
@RunWith(MockitoJUnitRunner::class)
class TestPropertyExporterTest {
    @Mock
    private val mockCallback: com.google.testing.junit.runner.util.TestPropertyExporter.Callback? = null
    private var previousCallback: com.google.testing.junit.runner.util.TestPropertyExporter.Callback? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setThreadCallback() {
        previousCallback = TestPropertyRunnerIntegration.setTestCaseForThread(mockCallback)
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun restorePreviousThreadCallback() {
        TestPropertyRunnerIntegration.setTestCaseForThread(previousCallback)
    }

    @org.junit.Test
    fun testExportProperty() {
        TestPropertyExporter.Companion.INSTANCE.exportProperty("propertyName", "value")
        Mockito.verify<com.google.testing.junit.runner.util.TestPropertyExporter.Callback?>(mockCallback)
            .exportProperty("propertyName", "value")
    }

    @org.junit.Test
    fun testExportRepeatedProperty() {
        TestPropertyExporter.Companion.INSTANCE.exportRepeatedProperty("propertyName", "value")
        Mockito.verify<com.google.testing.junit.runner.util.TestPropertyExporter.Callback?>(mockCallback)
            .exportRepeatedProperty("propertyName", "value")
    }

    @org.junit.Test
    fun testExportProperty_emptyNameIsValid() {
        TestPropertyExporter.Companion.INSTANCE.exportProperty(" ", "value")
        Mockito.verify<com.google.testing.junit.runner.util.TestPropertyExporter.Callback?>(mockCallback)
            .exportProperty(" ", "value")
    }

    @org.junit.Test
    fun testExportRepeatedProperty_emptyNameIsValid() {
        TestPropertyExporter.Companion.INSTANCE.exportRepeatedProperty(" ", "value")
        Mockito.verify<com.google.testing.junit.runner.util.TestPropertyExporter.Callback?>(mockCallback)
            .exportRepeatedProperty(" ", "value")
    }
}
