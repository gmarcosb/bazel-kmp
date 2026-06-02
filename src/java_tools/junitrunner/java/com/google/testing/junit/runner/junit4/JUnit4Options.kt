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
package com.google.testing.junit.runner.junit4

import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.MutableIterator
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/**
 * Simple options parser for JUnit 4.
 * 
 * 
 * 
 * For the options "test_filter" and "test_exclude_filter", this class properly handles arguments in
 * either the form "--test_filter=foo" or "--test_filter foo".
 */
internal class JUnit4Options // VisibleForTesting
    (
    /**
     * Returns the value of the `test_runner_fail_fast` option, or `false`` if
     * it was not specified.
    ` */
    val testRunnerFailFast: Boolean,
    /**
     * Returns the value of the test_filter option, or `null` if
     * it was not specified.
     */
    val testIncludeFilter: String?,
    /**
     * Returns the value of the test_exclude_filter option, or `null` if
     * it was not specified.
     */
    val testExcludeFilter: String?,
    /**
     * Returns an array of the arguments that did not match any known option.
     */
    val unparsedArgs: Array<String?>?
) {
    companion object {
        const val TEST_INCLUDE_FILTER_OPTION: String = "--test_filter"
        const val TEST_EXCLUDE_FILTER_OPTION: String = "--test_exclude_filter"

        // This gets passed in by the build system.
        private const val TESTBRIDGE_TEST_ONLY = "TESTBRIDGE_TEST_ONLY"

        // This gets passed in by the build system.
        private const val TESTBRIDGE_TEST_RUNNER_FAIL_FAST = "TESTBRIDGE_TEST_RUNNER_FAIL_FAST"

        /**
         * Parses the given array of arguments and returns a JUnit4Options
         * object representing the parsed arguments.
         */
        fun parse(envVars: MutableMap<String?, String?>, args: MutableList<String?>): JUnit4Options {
            val unparsedArgs: MutableList<String?> = ArrayList<String?>()
            val optionsMap: MutableMap<String?, String?> = HashMap<String?, String?>()

            optionsMap.put(TEST_INCLUDE_FILTER_OPTION, null)
            optionsMap.put(TEST_EXCLUDE_FILTER_OPTION, null)

            val it: MutableIterator<String> = args.iterator()
            while (it.hasNext()) {
                val arg = it.next()
                val indexOfEquals: Int = arg.indexOf("=")

                if (indexOfEquals > 0) {
                    val optionName: String = arg.substring(0, indexOfEquals)
                    if (optionsMap.containsKey(optionName)) {
                        optionsMap.put(optionName, arg.substring(indexOfEquals + 1))
                        continue
                    }
                } else if (optionsMap.containsKey(arg)) {
                    // next argument is the regexp
                    if (!it.hasNext()) {
                        throw RuntimeException("No filter expression specified after " + arg)
                    }
                    optionsMap.put(arg, it.next())
                    continue
                }
                unparsedArgs.add(arg)
            }
            // If TESTBRIDGE_TEST_ONLY is set in the environment, forward it to the
            // --test_filter flag.
            val testFilter = envVars.get(TESTBRIDGE_TEST_ONLY)
            if (testFilter != null && optionsMap.get(TEST_INCLUDE_FILTER_OPTION) == null) {
                optionsMap.put(TEST_INCLUDE_FILTER_OPTION, testFilter)
            }
            val testRunnerFailFast = "1" == envVars.get(TESTBRIDGE_TEST_RUNNER_FAIL_FAST)
            return JUnit4Options(
                testRunnerFailFast,
                optionsMap.get(TEST_INCLUDE_FILTER_OPTION),
                optionsMap.get(TEST_EXCLUDE_FILTER_OPTION),
                unparsedArgs.toTypedArray<String?>()
            )
        }
    }
}
