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
package com.google.testing.junit.junit4.runner

import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import java.util.regex.Pattern

/**
 * Filter that filters out test cases that either matches or does not match a specified regular
 * expression.
 */
class RegExTestCaseFilter private constructor(regularExpression: String?, private val isNegated: Boolean) : Filter() {
    private val pattern: Pattern

    init {
        this.pattern = Pattern.compile(regularExpression)
    }

    override fun shouldRun(description: Description): Boolean {
        if (description.isSuite()) {
            return true
        }

        val match = pattern.matcher(formatDescriptionName(description)).find()
        return if (isNegated) !match else match
    }

    override fun describe(): String? {
        return String.format("%sRegEx[%s]", if (isNegated) "NOT " else "", pattern.toString())
    }

    companion object {
        private const val TEST_NAME_FORMAT = "%s#%s"

        /**
         * Returns a filter that evaluates to `true` if the test case description matches
         * specified regular expression. Otherwise, returns `false`.
         */
        fun include(regularExpression: String?): RegExTestCaseFilter {
            return RegExTestCaseFilter(regularExpression, false)
        }

        /**
         * Returns a filter that evaluates to `false` if the test case description matches
         * specified regular expression. Otherwise, returns `true`.
         */
        fun exclude(regularExpression: String?): RegExTestCaseFilter {
            return RegExTestCaseFilter(regularExpression, true)
        }

        private fun formatDescriptionName(description: Description): String? {
            val methodName = if (description.getMethodName() == null) "" else description.getMethodName()

            val className = if (description.getClassName() == null) "" else description.getClassName()
            if (methodName.trim { it <= ' ' }.isEmpty() || className.trim { it <= ' ' }.isEmpty()) {
                return description.getDisplayName()
            }
            return String.format(TEST_NAME_FORMAT, className, methodName)
        }
    }
}
