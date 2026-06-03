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

import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.google.testing.junit.runner.util.TestPropertyExporter
import com.google.testing.junit.runner.util.TestPropertyRunnerIntegration

/**
 * JUnit runner integration code for test properties. Most code should not
 * use this, and should instead use [TestPropertyExporter].
 */
object TestPropertyRunnerIntegration {
    private val callbackForThread
            : java.lang.ThreadLocal<com.google.testing.junit.runner.util.TestPropertyExporter.Callback?> =
        object : java.lang.ThreadLocal<com.google.testing.junit.runner.util.TestPropertyExporter.Callback?>() {
            override fun initialValue(): com.google.testing.junit.runner.util.TestPropertyExporter.Callback {
                return com.google.testing.junit.runner.util.TestPropertyRunnerIntegration.NoOpCallback.Companion.INSTANCE
            }
        }

    /**
     * Sets the per-thread callback.
     * 
     * @param callback Callback
     */
    fun setTestCaseForThread(callback: com.google.testing.junit.runner.util.TestPropertyExporter.Callback?): com.google.testing.junit.runner.util.TestPropertyExporter.Callback? {
        val previousCallback: com.google.testing.junit.runner.util.TestPropertyExporter.Callback? =
            callbackForThread.get()
        if (callback == null) {
            callbackForThread.remove()
        } else {
            callbackForThread.set(callback)
        }
        return previousCallback
    }

    fun getCallbackForThread(): com.google.testing.junit.runner.util.TestPropertyExporter.Callback? {
        return callbackForThread.get()
    }

    private class NoOpCallback : com.google.testing.junit.runner.util.TestPropertyExporter.Callback {
        override fun exportProperty(name: String?, value: String?) {
        }

        override fun exportRepeatedProperty(name: String?, value: String?): String? {
            return name
        }

        companion object {
            private val INSTANCE: com.google.testing.junit.runner.util.TestPropertyExporter.Callback = NoOpCallback()
        }
    }
}
