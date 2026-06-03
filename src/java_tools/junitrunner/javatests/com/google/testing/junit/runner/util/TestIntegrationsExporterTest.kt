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

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.util.TestIntegration
import com.google.testing.junit.runner.util.TestIntegrationsExporter
import com.google.testing.junit.runner.util.TestIntegrationsRunnerIntegration
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnitRunner

/** Tests for [TestIntegrationsExporter].  */
@RunWith(MockitoJUnitRunner::class)
class TestIntegrationsExporterTest {
    @Mock
    private val mockCallback: com.google.testing.junit.runner.util.TestIntegrationsExporter.Callback? = null
    private var previousCallback: com.google.testing.junit.runner.util.TestIntegrationsExporter.Callback? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setThreadCallback() {
        previousCallback = TestIntegrationsRunnerIntegration.setTestCaseForThread(mockCallback)
    }

    @org.junit.After
    fun restorePreviousThreadCallback() {
        TestIntegrationsRunnerIntegration.setTestCaseForThread(previousCallback)
    }

    @org.junit.Test
    fun testExportTestIntegration() {
        val testIntegration: TestIntegration? =
            TestIntegration.Companion.builder()
                .setContactEmail("test@testmail.com")
                .setComponentId("1234")
                .setName("Test")
                .setUrl("testurl")
                .setDescription("Test description.")
                .setForegroundColor("white")
                .setBackgroundColor("rgb(47, 122, 243)")
                .build()

        TestIntegrationsExporter.Companion.INSTANCE.newTestIntegration(testIntegration)
        Mockito.verify<com.google.testing.junit.runner.util.TestIntegrationsExporter.Callback?>(mockCallback)
            .exportTestIntegration(testIntegration)
    }
}
