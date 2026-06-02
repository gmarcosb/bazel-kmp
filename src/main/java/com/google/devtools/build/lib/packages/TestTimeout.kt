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
import java.util.Locale

/** Symbolic labels of test timeout. Borrows heavily from [TestSize].  */
enum class TestTimeout(timeout: Int) {
    // These symbolic labels are used in the build files.
    SHORT(60),
    MODERATE(300),
    LONG(900),
    ETERNAL(3600);

    @kotlin.jvm.JvmField
    private val timeout: Int

    init {
        this.timeout = timeout
    }

    override fun toString(): String {
        return super.toString().toLowerCase()
    }

    /** We print to upper case to make the test timeout warnings more readable.  */
    fun prettyPrint(): String? {
        return super.toString().toUpperCase()
    }

    @Deprecated("") // use getTimeout instead
    fun getTimeoutSeconds(): Int {
        return timeout
    }

    fun getTimeout(): java.time.Duration? {
        return java.time.Duration.ofSeconds(timeout.toLong())
    }

    /**
     * Returns true iff the given time is not close to the upper bound timeout and is so short that it
     * should be assigned a different timeout.
     * 
     * 
     * This is used to give suggestions to developers to update their timeouts. If this returns
     * true, a more reasonable timeout can be selected with [.getSuggestedTestTimeout]
     */
    fun isInRangeFuzzy(timeInSeconds: Int): Boolean {
        return TIMEOUT_FUZZY_RANGE.get(this).contains(timeInSeconds)
    }

    /** Converter for the --test_timeout option.  */
    class TestTimeoutConverter

        : com.google.devtools.common.options.Converter.Contextless<MutableMap<TestTimeout?, java.time.Duration?>?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): MutableMap<TestTimeout?, java.time.Duration?> {
            val values: MutableList<java.time.Duration?> = java.util.ArrayList<java.time.Duration?>()
            for (token in com.google.common.base.Splitter.on(',').limit(6).split(input)) {
                // Handle the case of "2," which is accepted as legal... Because Splitter.split is lazy,
                // there's no way of knowing if an empty string is a trailing or an intermediate one,
                // so we can't fully emulate String.split(String, 0).
                if (!token.isEmpty() || values.size() > 1) {
                    try {
                        values.add(java.time.Duration.ofSeconds(java.lang.Integer.parseInt(token).toLong()))
                    } catch (e: java.lang.NumberFormatException) {
                        throw com.google.devtools.common.options.OptionsParsingException(
                            "'" + input + "' is not an int",
                            e
                        )
                    }
                }
            }
            val timeouts: java.util.EnumMap<TestTimeout?, java.time.Duration?> =
                java.util.EnumMap<TestTimeout?, java.time.Duration?>(TestTimeout::class.java)
            if (values.size() == 1) {
                timeouts.put(TestTimeout.SHORT, values.get(0))
                timeouts.put(TestTimeout.MODERATE, values.get(0))
                timeouts.put(TestTimeout.LONG, values.get(0))
                timeouts.put(TestTimeout.ETERNAL, values.get(0))
            } else if (values.size() == 4) {
                timeouts.put(TestTimeout.SHORT, values.get(0))
                timeouts.put(TestTimeout.MODERATE, values.get(1))
                timeouts.put(TestTimeout.LONG, values.get(2))
                timeouts.put(TestTimeout.ETERNAL, values.get(3))
            } else {
                throw com.google.devtools.common.options.OptionsParsingException("Invalid number of comma-separated entries")
            }
            for (label in TestTimeout.entries) {
                if (!timeouts.containsKey(label) || timeouts.get(label).compareTo(java.time.Duration.ZERO) <= 0) {
                    timeouts.put(label, label.getTimeout())
                }
            }
            return timeouts
        }

        override fun getTypeDescription(): String {
            return "a single integer or comma-separated list of 4 integers"
        }
    }

    /** Converter for the --test_timeout_filters option.  */
    class TestTimeoutFilterConverter : EnumFilterConverter<TestTimeout?>(TestTimeout::class.java, "test timeout") {
        /**
         * {@inheritDoc}
         * 
         * 
         * This override is necessary to prevent OptionsData from throwing a "must be assignable from
         * the converter return type" exception. OptionsData doesn't recognize the generic type and
         * actual type are the same.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): MutableSet<TestTimeout?>? {
            return super.convert(input)
        }
    }

    companion object {
        /** Default --test_timeout flag, used when collecting code coverage.  */ // Do not increase these values without consulting b/459811767#comment3.
        const val COVERAGE_CMD_TIMEOUT: String = "--test_timeout=300,600,1200,3600"

        /** Map from test time to suggested TestTimeout.  */
        private val SUGGESTED_TIMEOUT: com.google.common.collect.RangeMap<Int?, TestTimeout?>

        /**
         * Map from TestTimeout to fuzzy range.
         * 
         * 
         * The fuzzy range is used to check whether the actual timeout is close to the upper bound of
         * the current timeout or much smaller than the next shorter timeout. This is used to give
         * suggestions to developers to update their timeouts.
         */
        private val TIMEOUT_FUZZY_RANGE: MutableMap<TestTimeout?, com.google.common.collect.Range<Int?>?>

        init {
            // For the largest timeout, cap suggested and fuzzy ranges at one year.
            val maxTimeout = 365 * 24 * 60 * 60 /* One year */

            val suggestedTimeoutBuilder: com.google.common.collect.ImmutableRangeMap.Builder<Int?, TestTimeout?> =
                com.google.common.collect.ImmutableRangeMap.builder<Int?, TestTimeout?>()
            val timeoutFuzzyRangeBuilder: com.google.common.collect.ImmutableMap.Builder<TestTimeout?, com.google.common.collect.Range<Int?>?> =
                com.google.common.collect.ImmutableMap.builder<TestTimeout?, com.google.common.collect.Range<Int?>?>()

            var previousMaxSuggested = 0
            var previousTimeout = 0

            val timeoutIterator: MutableIterator<TestTimeout> =
                java.util.Arrays.asList<TestTimeout?>(*TestTimeout.entries.toTypedArray()).iterator()
            while (timeoutIterator.hasNext()) {
                val timeout = timeoutIterator.next()

                // Set up time ranges for suggested timeouts and fuzzy timeouts. Fuzzy timeout ranges should
                // be looser than suggested timeout ranges in order to make sure that after a test size is
                // adjusted, it's difficult for normal time variance to push it outside the fuzzy timeout
                // range.

                // This should be exactly the previous max because there should be exactly one suggested
                // timeout for any given time.
                val minSuggested = previousMaxSuggested
                // Only suggest timeouts that are less than 75% of the actual timeout (unless there are no
                // higher timeouts). This should be low enough to prevent suggested times from causing test
                // timeout flakiness.
                val maxSuggested =
                    if (timeoutIterator.hasNext()) (timeout.timeout * 0.75).toInt() else maxTimeout

                // Set fuzzy minimum timeout to half the previous timeout. If the test is that fast, it should
                // be safe to use the shorter timeout.
                val minFuzzy = previousTimeout / 2
                // Set fuzzy maximum timeout to 90% of the timeout. A test this close to the limit can easily
                // become timeout flaky.
                val maxFuzzy = if (timeoutIterator.hasNext()) (timeout.timeout * 0.9).toInt() else maxTimeout

                timeoutFuzzyRangeBuilder.put(
                    timeout,
                    com.google.common.collect.Range.closedOpen<Int?>(minFuzzy, maxFuzzy)
                )

                suggestedTimeoutBuilder.put(
                    com.google.common.collect.Range.closedOpen<Int?>(
                        minSuggested,
                        maxSuggested
                    ), timeout
                )

                previousMaxSuggested = maxSuggested
                previousTimeout = timeout.timeout
            }
            SUGGESTED_TIMEOUT = suggestedTimeoutBuilder.build()
            TIMEOUT_FUZZY_RANGE = timeoutFuzzyRangeBuilder.buildOrThrow()
        }

        /**
         * Returns the enum associated with a test's timeout or null if the tag is not lower case or an
         * unknown size.
         */
        fun getTestTimeout(attr: String): TestTimeout? {
            if (attr != attr.toLowerCase()) {
                return null
            }
            try {
                return com.google.devtools.build.lib.packages.TestTimeout.valueOf(attr.toUpperCase(Locale.ENGLISH))
            } catch (e: java.lang.IllegalArgumentException) {
                return null
            }
        }

        /**
         * Returns test timeout of the given target using explicitly specified timeout or default through
         * the size label's associated default or null if the target is not a test.
         */
        fun getTestTimeout(target: com.google.devtools.build.lib.packages.Rule?): TestTimeout? {
            val attr: String? = NonconfigurableAttributeMapper.Companion.attributeOrNull<String?>(
                target,
                "timeout",
                com.google.devtools.build.lib.packages.Type.Companion.STRING
            )
            if (attr == null) {
                // The target is not a test. This is reached by serialization code as it tries to serialize
                // essential target fields. There's not enough context there to pre-determine whether a
                // target is a test or not, so it simply serializes any String timeout field.
                //
                // TODO(b/297857068): refactor ConfiguredTargetAndData and remove this branch.
                return null
            }
            if (attr != attr.toLowerCase()) {
                return null // attribute values must be lowercase
            }
            try {
                return com.google.devtools.build.lib.packages.TestTimeout.valueOf(attr.toUpperCase(Locale.ENGLISH))
            } catch (e: java.lang.IllegalArgumentException) {
                return null
            }
        }

        /**
         * Returns suggested test size for the given time in seconds.
         * 
         * 
         * Will suggest times that are unlikely to result in timeout flakiness even if the test has a
         * significant amount of time variance.
         */
        @kotlin.jvm.JvmStatic
        fun getSuggestedTestTimeout(timeInSeconds: Int): TestTimeout? {
            return SUGGESTED_TIMEOUT.get(timeInSeconds)
        }
    }
}
