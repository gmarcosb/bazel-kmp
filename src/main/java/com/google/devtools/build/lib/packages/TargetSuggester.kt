// Copyright 2023 The Bazel Authors. All rights reserved.
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

import java.util.Locale

/**
 * Some static utility functions for determining suggested targets when a user requests a
 * non-existent target.
 */
object TargetSuggester {
    private const val MAX_SUGGESTED_TARGETS_SIZE = 10

    private const val MAX_SUGGESTION_EDIT_DISTANCE = 5

    /**
     * Given a nonexistent target and the targets in its package, suggest what the user may have
     * intended based on lexicographic closeness to the possibilities.
     * 
     * 
     * This will be pretty printed in the following forms:
     * 
     * 
     * No suggested targets -> "".
     * 
     * 
     * Suggested target "a" -> "a".
     * 
     * 
     * Suggested targets "a", "b" -> "a, or b"
     * 
     * 
     * Suggested targets "a", "b", "c" -> "a, b, or c".
     */
    fun suggestTargets(input: String, words: MutableSet<String>): String {
        val suggestedTargets: com.google.common.collect.ImmutableList<String> = suggestedTargets(input, words)
        return prettyPrintTargets(suggestedTargets)
    }

    /**
     * Given a requested target and a Set of targets in the same package, return a list of the targets
     * closest to the requested target based on edit distance.
     * 
     * 
     * If any strings are identical minus capitalization changes, they will be returned. If any
     * other strings are exactly 1 character off, they will be returned. Otherwise, the 10 nearest
     * (within a small edit distance) will be returned.
     */
    @com.google.common.annotations.VisibleForTesting
    fun suggestedTargets(input: String, words: MutableSet<String>): com.google.common.collect.ImmutableList<String> {
        val lowerCaseInput: String = input.toLowerCase(Locale.US)

        // Add words based on edit distance
        val editDistancesBuilder: com.google.common.collect.ImmutableListMultimap.Builder<Int?, String?> =
            com.google.common.collect.ImmutableListMultimap.builder<Int?, String?>()

        val maxEditDistance: Int = java.lang.Math.min(MAX_SUGGESTION_EDIT_DISTANCE, (input.length() + 1) / 2)
        for (word in words) {
            val lowerCaseWord: String = word.toLowerCase(Locale.US)

            val editDistance: Int =
                net.starlark.java.spelling.SpellChecker.editDistance(lowerCaseInput, lowerCaseWord, maxEditDistance)

            if (editDistance >= 0) {
                editDistancesBuilder.put(editDistance, word)
            }
        }
        val editDistanceToWords: com.google.common.collect.ImmutableListMultimap<Int?, String?> =
            editDistancesBuilder.build()

        val zeroEditDistanceWords: com.google.common.collect.ImmutableList<String?> = editDistanceToWords.get(0)
        val oneEditDistanceWords: com.google.common.collect.ImmutableList<String?> = editDistanceToWords.get(1)

        if (editDistanceToWords.isEmpty()) {
            return com.google.common.collect.ImmutableList.of<String?>()
        } else if (!zeroEditDistanceWords.isEmpty()) {
            val sublistLength: Int = java.lang.Math.min(zeroEditDistanceWords.size(), MAX_SUGGESTED_TARGETS_SIZE)
            return com.google.common.collect.ImmutableList.copyOf<String?>(
                zeroEditDistanceWords.subList(
                    0,
                    sublistLength
                )
            )
        } else if (!oneEditDistanceWords.isEmpty()) {
            val sublistLength: Int = java.lang.Math.min(oneEditDistanceWords.size(), MAX_SUGGESTED_TARGETS_SIZE)
            return com.google.common.collect.ImmutableList.copyOf<String?>(
                oneEditDistanceWords.subList(
                    0,
                    sublistLength
                )
            )
        } else {
            return getSuggestedTargets(editDistanceToWords, maxEditDistance)
        }
    }

    /**
     * Given a map of edit distance values to words that are that distance from the requested target,
     * returns up to MAX_SUGGESTED_TARGETS_SIZE targets that are at least edit distance 2 but no more
     * than the given max away.
     */
    private fun getSuggestedTargets(
        editDistanceToWords: com.google.common.collect.ImmutableListMultimap<Int?, String?>, maxEditDistance: Int
    ): com.google.common.collect.ImmutableList<String> {
        // iterate through until MAX is achieved
        var total = 0
        val suggestedTargets: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        var editDistance = 2
        while (editDistance < maxEditDistance && total < MAX_SUGGESTED_TARGETS_SIZE
        ) {
            val values: com.google.common.collect.ImmutableList<String?> = editDistanceToWords.get(editDistance)
            val addAmount: Int = java.lang.Math.min(values.size(), MAX_SUGGESTED_TARGETS_SIZE - total)
            suggestedTargets.addAll(values.subList(0, addAmount))
            total += addAmount
            editDistance++
        }

        return suggestedTargets.build()
    }

    /**
     * Create a pretty-printable String for a list. Joiner doesn't currently support multiple
     * separators so this is a custom roll for now. Returns a comma-delimited list with ", or " before
     * the last element.
     */
    @com.google.common.annotations.VisibleForTesting
    fun prettyPrintTargets(targets: com.google.common.collect.ImmutableList<String>): String {
        val targetString: String
        if (targets.isEmpty()) {
            return ""
        } else if (targets.size() == 1) {
            targetString = targets.get(0)
        } else {
            val firstPart: String = com.google.common.base.Joiner.on(", ").join(targets.subList(0, targets.size() - 1))
            targetString = com.google.common.base.Joiner.on(", or ")
                .join(firstPart, com.google.common.collect.Iterables.getLast<String?>(targets))
        }
        return " (did you mean " + targetString + "?)"
    }
}
