// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.aquery

import com.google.devtools.build.lib.query2.aquery.AqueryActionFilter

/** Encapsulate the action filters parsed from aquery command.  */
class AqueryActionFilter private constructor(builder: Builder) {
    // TODO(leba): Use Enum for list of filters.
    private val filterMap: com.google.common.collect.ImmutableMultimap<String?, java.util.regex.Pattern?>

    init {
        filterMap =
            com.google.common.collect.ImmutableMultimap.copyOf<String?, java.util.regex.Pattern?>(builder.filterMap)
    }

    fun hasFilterForFunction(function: String?): Boolean {
        return filterMap.containsKey(function)
    }

    /**
     * Returns whether the input string matches ALL the filter patterns of a specific type parsed from
     * aquery command.
     * 
     * @param function the name of the aquery function (inputs, outputs, mnemonic)
     * @param input the string to be matched against
     */
    fun matchesAllPatternsForFunction(function: String?, input: String?): Boolean {
        if (!hasFilterForFunction(function)) {
            return false
        }

        return filterMap.get(function).stream()
            .allMatch(java.util.function.Predicate { pattern: java.util.regex.Pattern? ->
                pattern.matcher(input).matches()
            })
    }

    /** Builder class for `AqueryActionFilter`  */
    class Builder {
        private val filterMap: com.google.common.collect.Multimap<String?, java.util.regex.Pattern?>

        init {
            filterMap = com.google.common.collect.HashMultimap.create<String?, java.util.regex.Pattern?>()
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun put(key: String?, value: java.util.regex.Pattern?): Builder {
            filterMap.put(key, value)
            return this
        }

        fun build(): AqueryActionFilter {
            return AqueryActionFilter(this)
        }
    }

    companion object {
        fun emptyInstance(): AqueryActionFilter {
            return builder().build()
        }

        fun builder(): Builder {
            return com.google.devtools.build.lib.query2.aquery.AqueryActionFilter.Builder()
        }
    }
}
