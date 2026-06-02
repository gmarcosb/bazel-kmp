// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.analysis.test.TestShardingStrategyForced
import com.google.devtools.build.lib.analysis.test.TestShardingStrategyNotForced

/** A strategy for running the same tests in many processes.  */
internal interface TestShardingStrategy {
    fun getNumberOfShards(shardCountFromAttr: Int): Int

    /** Converts to [TestShardingStrategy].  */
    class ShardingStrategyConverter :
        com.google.devtools.common.options.Converter.Contextless<TestShardingStrategy?>() {
        val typeDescription: String
            get() = "explicit, disabled or forced=k where k is the number of shards to enforce"

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): TestShardingStrategy {
            for (value in TestShardingStrategyNotForced.entries) {
                if (com.google.common.base.Ascii.equalsIgnoreCase(value.toString(), input)) {
                    return value
                }
            }

            if (com.google.common.base.Ascii.toLowerCase(input).startsWith(FORCED_PREFIX)) {
                val forcedShardsCount: Int =
                    com.google.devtools.common.options.Converters.IntegerConverter()
                        .convert(input.substring(FORCED_PREFIX.length))
                if (forcedShardsCount < 0) {
                    throw com.google.devtools.common.options.OptionsParsingException("Forced shards count cannot be negative.")
                }

                return TestShardingStrategyForced(forcedShardsCount)
            }

            throw com.google.devtools.common.options.OptionsParsingException(
                ("Not a valid test sharding strategy: '"
                        + input
                        + "' (should be "
                        + this.typeDescription
                        + ")")
            )
        }

        companion object {
            private const val FORCED_PREFIX = "forced="
        }
    }
}
