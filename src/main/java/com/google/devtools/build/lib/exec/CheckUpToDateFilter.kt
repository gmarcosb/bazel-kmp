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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.Action

/**
 * Class implements --check_???_up_to_date execution filter predicate
 * that prevents certain actions from being executed (thus aborting
 * the build if action is not up-to-date).
 */
class CheckUpToDateFilter private constructor(options: ExecutionOptions) : com.google.common.base.Predicate<Action?> {
    private val allowBuildActionExecution: Boolean
    private val allowTestActionExecution: Boolean

    /**
     * Creates new execution filter based on --check_up_to_date and
     * --check_tests_up_to_date options.
     */
    init {
        // If we want to check whether test is up-to-date, we should disallow
        // test execution.
        this.allowTestActionExecution = !options.getTestCheckUpToDate()

        // Build action execution should be prohibited in two cases - if we are
        // checking whether build is up-to-date or if we are checking that tests
        // are up-to-date (and test execution is not allowed).
        this.allowBuildActionExecution = allowTestActionExecution && !options.getCheckUpToDate()
    }

    /**
     * @return true if actions' execution is allowed, false - otherwise
     */
    override fun apply(action: Action?): Boolean {
        if (action is TestRunnerAction) {
            return allowTestActionExecution
        } else {
            return allowBuildActionExecution
        }
    }

    companion object {
        /**
         * Determines an execution filter based on the --check_up_to_date and
         * --check_tests_up_to_date options. Returns a singleton if possible.
         */
        fun fromOptions(options: ExecutionOptions): com.google.common.base.Predicate<Action?> {
            if (!options.getTestCheckUpToDate() && !options.getCheckUpToDate()) {
                return com.google.common.base.Predicates.alwaysTrue<Action?>()
            }
            return CheckUpToDateFilter(options)
        }
    }
}
