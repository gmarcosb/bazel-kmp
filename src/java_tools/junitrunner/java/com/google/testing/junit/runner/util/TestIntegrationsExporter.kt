// Copyright 2016 The Bazel Authors. All Rights Reserved.
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

import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.util.TestIntegration
import com.google.testing.junit.runner.util.TestIntegrationsExporter
import com.google.testing.junit.runner.util.TestIntegrationsRunnerIntegration

/** Exports test TestIntegrations to the test XML.  */
class TestIntegrationsExporter protected constructor(private val callback: Callback) {
    fun newTestIntegration(testIntegration: TestIntegration?) {
        callback.exportTestIntegration(testIntegration)
    }

    /** Callback that is used to store TestIntegration in the model.  */
    interface Callback {
        /** Export the TestIntegration.  */
        fun exportTestIntegration(testIntegration: TestIntegration?)
    }

    /**
     * Default callback implementation. Calls the test runner model to write the external integrations
     * to the XML.
     */
    private class DefaultCallback : Callback {
        override fun exportTestIntegration(testIntegration: TestIntegration?) {
            TestIntegrationsRunnerIntegration.getCallbackForThread().exportTestIntegration(testIntegration)
        }
    }

    companion object {
        /**
         * The global `TestIntegrationsExporter`, which writes the properties into the test XML if
         * the test is running from the command line.
         * 
         * 
         * If you have test infrastructure that needs to export properties, consider injecting an
         * instance of `TestIntegrationsExporter`. Your tests can use one of the static methods in
         * this class to create a fake instance.
         */
        val INSTANCE: TestIntegrationsExporter =
            TestIntegrationsExporter(com.google.testing.junit.runner.util.TestIntegrationsExporter.DefaultCallback())
    }
}
