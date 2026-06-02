// Copyright 2015 The Bazel Authors. All Rights Reserved.
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

/**
 * A filter that decorates another filter, filtering out any suites
 * that contain no tests.
 */
class SuiteTrimmingFilter(delegate: Filter) : Filter() {
    private val delegate: Filter

    init {
        if (delegate == null) {
            throw NullPointerException()
        }
        this.delegate = delegate
    }

    override fun describe(): String? {
        return delegate.describe()
    }

    override fun shouldRun(description: Description): Boolean {
        if (!delegate.shouldRun(description)) {
            return false
        }

        if (description.isTest()) {
            return true
        }

        // explicitly check if any children want to run
        for (each in description.getChildren()) {
            if (shouldRun(each)) {
                return true
            }
        }
        return false
    }
}
