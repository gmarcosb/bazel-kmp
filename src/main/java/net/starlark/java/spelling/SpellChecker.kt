// Copyright 2016 The Bazel Authors. All rights reserved.
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
package net.starlark.java.spelling

/**
 * Class that provides functions to do spell checking, i.e. detect typos
 * and make suggestions.
 */
object SpellChecker {
    /**
     * Computes the edit distance between two strings. The edit distance is
     * the minimum number of insertions, deletions and replacements to
     * transform a string into the other string.
     * 
     * maxEditDistance is the maximum distance the function can return. If
     * it would be greater, the function returns -1. It is useful for
     * speeding up the computations.
     */
    @kotlin.jvm.JvmStatic
    fun editDistance(s1: String, s2: String, maxEditDistance: Int): Int {
        // This is the Levenshtein distance, as described here:
        // http://en.wikipedia.org/wiki/Levenshtein_distance
        //
        // We don't need to keep the full matrix. To update a cell, we only
        // need top-left, top, and left values. Using a single array is
        // sufficient. Top value is still in row[j] from the last iteration.
        // Top-left value is stored in 'previous'. Left value is row[j - 1].

        if (s1 == s2) {
            return 0
        }
        // Short-circuit based on string length.
        if (Math.abs(s1.length() - s2.length()) > maxEditDistance) {
            return -1
        }

        val row = IntArray(s2.length() + 1)
        for (i in 0..s2.length()) {
            row[i] = i
        }

        for (i in 1..s1.length()) {
            row[0] = i
            var bestInTheRow = row[0]
            var previous = i - 1

            for (j in 1..s2.length()) {
                val old = row[j]

                row[j] = Math.min(
                    previous + (if (s1.charAt(i - 1) == s2.charAt(j - 1)) 0 else 1),
                    1 + Math.min(row[j - 1], row[j])
                )
                previous = old
                bestInTheRow = Math.min(bestInTheRow, row[j])
            }
            if (bestInTheRow > maxEditDistance) {
                return -1
            }
        }
        val result = row[s2.length()]
        return if (result <= maxEditDistance) result else -1
    }

    /**
     * Find in words which string is the most similar to input (according to
     * the edit distance, ignoring case) - or null if no string is similar
     * enough. In case of equality, the first one in words wins.
     */
    fun suggest(input: String, words: Iterable<String>): String? {
        var input = input
        var best: String? = null
        // Heuristic: the expected number of typos depends on the length of the word.
        var bestDistance = Math.min(5, (input.length() + 1) / 2)
        input = input.toLowerCase()
        for (candidate in words) {
            val d = editDistance(input, candidate.toLowerCase(), bestDistance)
            if (d >= 0 && d < bestDistance) {
                bestDistance = d
                best = candidate
            }
        }
        return best
    }

    /**
     * Return a string to be used at the end of an error message. It is either an empty string, or a
     * spelling suggestion, e.g. " (did you mean 'x'?)".
     */
    fun didYouMean(input: String, words: Iterable<String>): String {
        val suggestion = suggest(input, words)
        if (suggestion == null) {
            return ""
        } else {
            return " (did you mean '" + suggestion + "'?)"
        }
    }
}
