// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/** Utility class to help with evaluating target patterns.  */
object TargetPatternUtil {
    /**
     * Expand the given `targetPatterns`, using the `filteringPolicy`. This handles the
     * needed underlying Skyframe calls (via `env`), and will return `null` to signal a
     * Skyframe restart.
     */
    @Throws(InvalidTargetPatternException::class, java.lang.InterruptedException::class)
    fun expandTargetPatterns(
        env: SkyFunction.Environment,
        targetPatterns: MutableList<SignedTargetPattern?>,
        filteringPolicy: FilteringPolicy?
    ): com.google.common.collect.ImmutableSet<Label?>? {
        if (targetPatterns.isEmpty()) {
            return com.google.common.collect.ImmutableSet.of<Label?>()
        }

        val targetPatternKeys: Iterable<TargetPatternKey> =
            TargetPatternValue.Companion.keys(targetPatterns, filteringPolicy)
        val resolvedPatterns: SkyframeLookupResult = env.getValuesAndExceptions(targetPatternKeys)
        val valuesMissing: Boolean = env.valuesMissing()
        // Use an ArrayList so that we can add and remove results based on negative patterns.
        val labels: MutableList<Label?>? = if (valuesMissing) null else java.util.ArrayList<Label?>()

        for (pattern in targetPatternKeys) {
            try {
                val value: TargetPatternValue? =
                    resolvedPatterns.getOrThrow<E?>(pattern, TargetParsingException::class.java) as TargetPatternValue?
                if (valuesMissing || value == null) {
                    continue
                }
                if (pattern.isNegative()) {
                    // Remove from the results.
                    labels!!.removeAll(value.getTargets().getTargets())
                } else {
                    // Add to results.
                    labels!!.addAll(value.getTargets().getTargets())
                }
            } catch (e: TargetParsingException) {
                throw InvalidTargetPatternException(pattern.getPattern(), e)
            }
        }

        if (env.valuesMissing()) {
            if (valuesMissing != env.valuesMissing()) {
                BugReport.logUnexpected(
                    "Some value from '%s' was missing, this should never happen", targetPatternKeys
                )
            }
            return null
        }

        return com.google.common.collect.ImmutableSet.copyOf<Label?>(labels)
    }

    // TODO(bazel-team): look into moving this into SignedTargetPattern itself.
    @Throws(InvalidTargetPatternException::class)
    fun parseAllSigned(
        patterns: MutableList<String?>, parser: TargetPattern.Parser?
    ): com.google.common.collect.ImmutableList<SignedTargetPattern?> {
        val parsedPatterns: com.google.common.collect.ImmutableList.Builder<SignedTargetPattern?> =
            com.google.common.collect.ImmutableList.builder<SignedTargetPattern?>()
        for (pattern in patterns) {
            try {
                parsedPatterns.add(SignedTargetPattern.parse(pattern, parser))
            } catch (e: TargetParsingException) {
                throw InvalidTargetPatternException(pattern, e)
            }
        }
        return parsedPatterns.build()
    }

    /** Converts patterns to signed patterns, considering all input patterns positive.  */
    fun toSigned(patterns: MutableList<TargetPattern?>): com.google.common.collect.ImmutableList<SignedTargetPattern?> {
        return patterns.stream()
            .map<Any?>(java.util.function.Function { pattern: TargetPattern? ->
                SignedTargetPattern.create(
                    pattern,
                    Sign.POSITIVE
                )
            })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    /** Exception used when an error occurs in [.expandTargetPatterns].  */ // TODO(bazel-team): Consolidate this and TargetParsingException. Just have the latter store the
    //   original unparsed pattern too.
    class InvalidTargetPatternException(val invalidPattern: String?, tpe: TargetParsingException?) :
        java.lang.Exception(tpe) {
        private val tpe: TargetParsingException?

        init {
            this.tpe = tpe
        }

        fun getTpe(): TargetParsingException? {
            return tpe
        }
    }
}
