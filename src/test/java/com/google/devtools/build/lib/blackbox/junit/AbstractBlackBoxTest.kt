// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.blackbox.junit

import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.blackbox.bazel.BlackBoxTestEnvironmentImpl
import com.google.devtools.build.lib.blackbox.bazel.CrossToolsSetup
import com.google.devtools.build.lib.blackbox.bazel.CxxToolsSetup
import com.google.devtools.build.lib.blackbox.bazel.DefaultToolsSetup
import com.google.devtools.build.lib.blackbox.bazel.JavaToolsSetup
import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestContext
import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestEnvironment
import com.google.devtools.build.lib.blackbox.framework.ToolsSetup
import com.google.devtools.build.lib.vfs.Path
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.rules.TestName
import java.nio.file.Path

/**
 * Abstract base class for all JUnit integration tests for Bazel and Blaze.
 * 
 * 
 * Reuses [BlackBoxTestEnvironment] for all the test methods in the class. Initializes the
 * test environment and creates the test context for thet concrete test methods. Alternatively,
 * [.setUp] method can be overridden in concrete tests, and [ ][.prepareEnvironment] method be called for each test method separately to let them
 * initialize the unique set of tools.
 * 
 * 
 * See [BlackBoxTestEnvironment], [BlackBoxTestContext]
 */
abstract class AbstractBlackBoxTest {
    @org.junit.Rule
    var testName: TestName = TestName()

    /** Test context, available to the concrete test methods through a getter [.context]  */
    private var context: BlackBoxTestContext? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        prepareEnvironment(this.additionalTools)
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun tearDown() {
        if (context != null) {
            try {
                context.bazel().shutdown()
            } finally {
                val workDir: Path? = context.getWorkDir()
                if (workDir != null) {
                    com.google.devtools.build.lib.blackbox.framework.PathUtils.deleteTreeWithRetry(workDir)
                }
            }
        }
    }

    /**
     * Prepares the test environment for the test method and set the test context.
     * 
     * @param tools all [ToolsSetup] to be called during environment initialization
     * @throws Exception if any [ToolsSetup] call fails
     */
    @Throws(java.lang.Exception::class)
    protected fun prepareEnvironment(tools: com.google.common.collect.ImmutableList<ToolsSetup?>?) {
        context = testEnvironment.prepareEnvironment(testName.getMethodName(), tools)
    }

    /**
     * Getter method for test context. Concrete test methods should only use the test context, but not
     * modify it.
     * 
     * @return test context
     */
    protected fun context(): BlackBoxTestContext? {
        return context
    }

    protected open val additionalTools: com.google.common.collect.ImmutableList<ToolsSetup?>?
        /**
         * Concrete test can either override this method to provide the list of additional tools besides
         * [.DEFAULT_TOOLS] to be initialized for all test methods, or call [ ][.prepareEnvironment] in each test method separately, passing the list of tools
         * 
         * @return the list of [ToolsSetup] to be called in environment initialization
         */
        get() = com.google.common.collect.ImmutableList.of<ToolsSetup?>()

    companion object {
        val DEFAULT_TOOLS: MutableList<ToolsSetup?> = com.google.common.collect.ImmutableList.of<ToolsSetup?>(
            DefaultToolsSetup(),
            JavaToolsSetup(),
            CxxToolsSetup(),
            CrossToolsSetup()
        )
        protected const val MODULE_DOT_BAZEL: String = "MODULE.bazel"

        /**
         * Shares the common infrastructure of a test group (execution service), serves as a test context
         * factory.
         */
        private var testEnvironment: BlackBoxTestEnvironment? = null

        @BeforeClass
        fun beforeClass() {
            testEnvironment = BlackBoxTestEnvironmentImpl()
        }

        @AfterClass
        fun afterClass() {
            testEnvironment.dispose()
        }

        protected val isWindows: Boolean
            /**
             * Check if we are running tests on Windows
             * 
             * @return True, if we are running tests on Windows; False, if we are running tests on other
             * platforms.
             */
            get() = com.google.devtools.build.lib.util.OS.WINDOWS == com.google.devtools.build.lib.util.OS.getCurrent()
    }
}
