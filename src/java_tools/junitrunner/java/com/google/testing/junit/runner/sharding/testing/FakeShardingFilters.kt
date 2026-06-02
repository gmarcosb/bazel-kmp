// Copyright 2012 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.sharding.testing

import com.google.testing.junit.runner.sharding.ShardingFilters
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter
import java.util.Arrays
import kotlin.collections.MutableCollection
import kotlin.collections.MutableSet

/**
 * Filter factory that includes only descriptions in the set of descriptions
 * explicitly specified in the constructor.
 */
class FakeShardingFilters(vararg descriptionsToRun: Description?) : ShardingFilters(null, null) {
    private val descriptionsToRun: MutableSet<Description?>

    init {
        this.descriptionsToRun = copyOf<Description?>(*descriptionsToRun)
    }

    public override fun createShardingFilter(allDescriptions: MutableCollection<Description?>): Filter {
        return ExplicitDescriptionFilter(allDescriptions, descriptionsToRun)
    }


    private class ExplicitDescriptionFilter(
        allDescriptions: MutableCollection<Description?>,
        private val descriptionsToRun: MutableSet<Description?>
    ) : Filter() {
        private val allDescriptions: MutableSet<Description?>

        init {
            this.allDescriptions = copyOf<Description?>(allDescriptions)
        }

        override fun shouldRun(description: Description): Boolean {
            if (description.isSuite()) {
                return true
            }
            require(allDescriptions.contains(description)) { "Not in the suite: " + description }
            return descriptionsToRun.contains(description)
        }

        override fun describe(): String {
            return "explicit description filter"
        }
    }

    companion object {
        private fun <T> copyOf(vararg items: T?): MutableSet<T?> {
            return copyOf<T?>(Arrays.asList<T?>(*items))
        }

        private fun <T> copyOf(items: MutableCollection<T?>): MutableSet<T?> {
            return Collections.unmodifiableSet<T?>(HashSet<T?>(items))
        }
    }
}
