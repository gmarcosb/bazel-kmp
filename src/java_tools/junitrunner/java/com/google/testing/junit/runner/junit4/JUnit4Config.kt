// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.junit4

import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.*

/**
 * Configuration for the JUnit4 test runner.
 */
internal class JUnit4Config private constructor(
    /**
     * Returns the value of the `test_runner_fail_fast` option, or `false`` if
     * it was not specified.
    ` */
    val testRunnerFailFast: Boolean,
    /**
     * Returns a regular expression representing an inclusive filter.
     * Only test descriptions that match this regular expression should be run.
     */
    val testIncludeFilterRegexp: String?,
    /**
     * Returns a regular expression representing an exclusive filter.
     * Test descriptions that match this regular expression should not be run.
     */
    val testExcludeFilterRegexp: String?,
    private val xmlOutputPath: Path?,
    systemProperties: Properties
) {
    private val junitApiVersion: String

    constructor(
        testIncludeFilterRegexp: String?,
        testExcludeFilterRegexp: String?,
        outputXmlFilePath: Path?
    ) : this(
        false,
        testIncludeFilterRegexp,
        testExcludeFilterRegexp,
        outputXmlFilePath,
        System.getProperties()
    )

    constructor(testRunnerFailFast: Boolean, testIncludeFilterRegexp: String?, testExcludeFilterRegexp: String?) : this(
        testRunnerFailFast,
        testIncludeFilterRegexp,
        testExcludeFilterRegexp,
        null,
        System.getProperties()
    )

    // VisibleForTesting
    constructor(
        testIncludeFilterRegexp: String?,
        testExcludeFilterRegexp: String?,
        xmlOutputPath: Path?,
        systemProperties: Properties
    ) : this(false, testIncludeFilterRegexp, testExcludeFilterRegexp, xmlOutputPath, systemProperties)

    init {
        junitApiVersion = systemProperties.getProperty(JUNIT_API_VERSION_PROPERTY, "1").trim { it <= ' ' }
    }

    /**
     * Returns the XML output path, or null if not specified.
     */
    fun getXmlOutputPath(): Path? {
        if (xmlOutputPath == null) {
            val envXmlOutputPath = System.getenv(XML_OUTPUT_FILE_ENV_VAR)
            return if (envXmlOutputPath == null) null else FileSystems.getDefault().getPath(envXmlOutputPath)
        }
        return xmlOutputPath
    }

    val jUnitRunnerApiVersion: Int
        /**
         * Gets the version of the JUnit Runner that the test is expecting.
         * Some features may be enabled or disabled based on this value.
         * 
         * @return api version
         * @throws IllegalStateException if the API version is unsupported.
         */
        get() {
            var apiVersion = 0
            try {
                apiVersion = junitApiVersion.toInt()
            } catch (e: NumberFormatException) {
                // ignore; handled below
            }

            check(apiVersion == 1) {
                ("Unsupported JUnit Runner API version " + JUNIT_API_VERSION_PROPERTY + "="
                        + junitApiVersion + " (must be \\\"1\\\")")
            }
            return apiVersion
        }

    companion object {
        // VisibleForTesting
        const val JUNIT_API_VERSION_PROPERTY: String = "com.google.testing.junit.runner.apiVersion"

        private const val XML_OUTPUT_FILE_ENV_VAR = "XML_OUTPUT_FILE"
    }
}
