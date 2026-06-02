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
 * A filter that returns `true` if both of its components return `true`.
 */
@Deprecated("")
internal class AndFilter(filter1: Filter, filter2: Filter) : Filter() {
    private val filter1: Filter
    private val filter2: Filter

    init {
        if (filter1 == null || filter2 == null) {
            throw NullPointerException()
        }
        this.filter1 = filter1
        this.filter2 = filter2
    }

    override fun shouldRun(description: Description?): Boolean {
        return filter1.shouldRun(description) && filter2.shouldRun(description)
    }

    override fun describe(): String? {
        return String.format("%s && %s", filter1.describe(), filter2.describe())
    }
}
