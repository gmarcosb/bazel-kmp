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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.EnumFilterConverter
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper
import com.google.devtools.build.lib.packages.TestTimeout

/**
 * Possible test sizes.
 * 
 * Test size may affect the way how test is executed - e.g., it will determine
 * default timeout value and estimated local resource usage.
 */
enum class TestSize(defaultTimeout: TestTimeout, defaultShards: Int) {
    // Small tests use small amount of memory, but CPU intensive.
    SMALL(TestTimeout.SHORT, 2),

    // Medium tests tend to use larger amount of memory.
    MEDIUM(TestTimeout.MODERATE, 10),

    // All other tests estimated to use fairly large amount of memory.
    LARGE(TestTimeout.LONG, 20),
    ENORMOUS(TestTimeout.ETERNAL, 30);

    private val timeout: TestTimeout?
    @kotlin.jvm.JvmField
    private val defaultShards: Int

    init {
        this.timeout = defaultTimeout
        this.defaultShards = defaultShards
    }

    /**
     * Returns default timeout in seconds.
     */
    fun getDefaultTimeout(): TestTimeout? {
        return timeout
    }

    /**
     * Returns default number of shards.
     */
    fun getDefaultShards(): Int {
        return defaultShards
    }

    /** Normal practice is to always use size tags as lower case strings.  */
    override fun toString(): String {
        return super.toString().toLowerCase()
    }

    /**
     * Converter for the --test_size_filters option.
     */
    class TestSizeFilterConverter : EnumFilterConverter<TestSize?>(TestSize::class.java, "test size") {
        /**
         * {@inheritDoc}
         * 
         * 
         * This override is necessary to prevent OptionsData from throwing a "must be assignable from
         * the converter return type" exception. OptionsData doesn't recognize the generic type and
         * actual type are the same.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): MutableSet<TestSize?>? {
            return super.convert(input)
        }
    }

    companion object {
        // Memoize canonical lowercase name -> TestSize mappings to avoid extraneous toUpperCases for
        // valueOf.
        private val CANONICAL_LOWER_CASE_NAME_TABLE: com.google.common.collect.ImmutableMap<String?, TestSize?>

        init {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, TestSize?> =
                com.google.common.collect.ImmutableMap.builder<String?, TestSize?>()
            for (size in TestSize.entries) {
                builder.put(size.name().toLowerCase(), size)
            }
            CANONICAL_LOWER_CASE_NAME_TABLE = builder.buildOrThrow()
        }

        /** Returns test size of the given test target, or null if the size attribute is unrecognized.  */
        fun getTestSize(testTarget: com.google.devtools.build.lib.packages.Rule?): TestSize? {
            val attr: String? = NonconfigurableAttributeMapper.Companion.of(testTarget)
                .get<String?>("size", com.google.devtools.build.lib.packages.Type.Companion.STRING)
            return TestSize.Companion.getTestSize(attr)
        }

        /**
         * Returns [TestSize] matching the given timeout or null if the given timeout doesn't match
         * any [TestSize].
         * 
         * @param timeout The timeout associated with the desired TestSize.
         */
        fun getTestSize(timeout: TestTimeout?): TestSize? {
            for (size in TestSize.entries) {
                if (size.timeout == timeout) {
                    return size
                }
            }
            return null
        }

        /**
         * Returns the enum associated with a test's size or null if the tag is not lower case or an
         * unknown size.
         */
        @kotlin.jvm.JvmStatic
        fun getTestSize(attr: String): TestSize? {
            if (attr != attr.toLowerCase()) {
                return null
            }
            try {
                return CANONICAL_LOWER_CASE_NAME_TABLE.get(attr)
            } catch (e: java.lang.IllegalArgumentException) {
                return null
            }
        }
    }
}
