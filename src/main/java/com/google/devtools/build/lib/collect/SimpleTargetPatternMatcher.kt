// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect

import com.google.devtools.build.lib.cmdline.Label

/**
 * A simple matcher for checking whether a given label is part is a set of simple target patterns.
 * 
 * 
 * This does not implement full target patterns. Specifically, it handles:
 * 
 * 
 *  * Absolute labels, such as `//package:target` or `//package/subpackage`
 *  * Absolute package paths and all subpackages and targets, such as `//package/...`
 *  * Negative patterns of the above, such as `-//package:target`
 * 
 * 
 * It does not handle:
 * 
 * 
 *  * Relative labels (all labels with no repository are assumed to be in the main repository)
 *  * The `:all` or `:*` qualifiers
 * 
 * 
 * Patterns are processed in the order given, including negative patterns that override previous
 * patterns. This means that if the patterns are
 * 
 * 
 *  * `//package/...`
 *  * `-//package/subpackage/...`
 *  * `//package/subpackage/further/...`
 * 
 * 
 * then the labels `//package:something`, `//package/another` and `//package/subpackage/further:anything` all match, but the label `//package/subpackage:something` does not match.
 * 
 * 
 * Further note that this class does no loading of BUILD files and performs no verification that
 * targets actually exist: it simply matches abstract labels against patterns.
 */
class SimpleTargetPatternMatcher private constructor(singlePatternMatchers: com.google.common.collect.ImmutableList<SinglePatternMatcher>) {
    private val singlePatternMatchers: com.google.common.collect.ImmutableList<SinglePatternMatcher>

    init {
        this.singlePatternMatchers = singlePatternMatchers
    }

    val isEmpty: Boolean
        get() = this.singlePatternMatchers.isEmpty()

    /** Returns `true` if the label matches all patterns in this matcher.  */
    fun contains(label: Label?): Boolean {
        if (this.singlePatternMatchers.isEmpty()) {
            return false
        }

        // Check each sub-matcher.
        var result: MatchResult = com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.EXCLUDE
        for (matcher in this.singlePatternMatchers) {
            val matchResult: MatchResult = matcher.matches(label)
            if (matchResult == com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.INCLUDE || matchResult == com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.EXCLUDE) {
                result = matchResult
            }
        }
        return result == com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.INCLUDE
    }

    override fun toString(): String {
        val joined: String? =
            this.singlePatternMatchers.stream()
                .map<String?>(java.util.function.Function { obj: SinglePatternMatcher? -> obj.toString() })
                .collect(Collectors.joining(","))
        return java.lang.String.format("[%s]", joined)
    }

    override fun equals(other: Any?): Boolean {
        if (other is SimpleTargetPatternMatcher) {
            return this.singlePatternMatchers == other.singlePatternMatchers
        }
        return false
    }

    override fun hashCode(): Int {
        return this.singlePatternMatchers.hashCode()
    }

    private enum class MatchResult {
        INCLUDE,
        EXCLUDE,
        NOT_RELEVANT
    }

    private interface SinglePatternMatcher {
        fun matches(label: Label?): MatchResult
    }

    /** Checks if the given label exactly matches the pattern.  */
    private class ExactMatcher(private val rawPattern: String) : SinglePatternMatcher {
        private val label: Label

        init {
            this.label = Label.parseCanonical(rawPattern)
        }

        override fun matches(label: Label?): MatchResult {
            if (this.label.equals(label)) {
                return com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.INCLUDE
            }
            return com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.NOT_RELEVANT
        }

        override fun toString(): String {
            return this.rawPattern
        }

        override fun equals(other: Any?): Boolean {
            if (other is ExactMatcher) {
                return this.rawPattern == other.rawPattern
            }
            return false
        }

        override fun hashCode(): Int {
            return this.rawPattern.hashCode()
        }
    }

    /** Checks if the given label fails to match the pattern.  */
    private class NegativeMatcher(private val inner: SinglePatternMatcher) : SinglePatternMatcher {
        override fun matches(label: Label?): MatchResult {
            return when (this.inner.matches(label)) {
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.INCLUDE -> com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.EXCLUDE
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.EXCLUDE, com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.NOT_RELEVANT -> com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.NOT_RELEVANT
            }
        }

        override fun toString(): String {
            return java.lang.String.format("-%s", this.inner)
        }

        override fun equals(other: Any?): Boolean {
            if (other is NegativeMatcher) {
                return this.inner == other.inner
            }
            return false
        }

        override fun hashCode(): Int {
            return 0x37 xor this.inner.hashCode()
        }
    }

    private class WildcardMatcher(pattern: String) : SinglePatternMatcher {
        private val packagePath: PathFragment

        init {
            // Strip off the leading "//" and the trailing "/..." and create the wildcard matcher.
            this.packagePath = PathFragment.create(pattern.substring(2, pattern.lastIndexOf("...")))
        }

        override fun matches(label: Label): MatchResult {
            if (label.getPackageFragment().startsWith(this.packagePath)) {
                return com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.INCLUDE
            }
            return com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.MatchResult.NOT_RELEVANT
        }

        override fun toString(): String {
            return java.lang.String.format("//%s/...", this.packagePath)
        }

        override fun equals(other: Any?): Boolean {
            if (other is WildcardMatcher) {
                return this.packagePath == other.packagePath
            }
            return false
        }

        override fun hashCode(): Int {
            return this.packagePath.hashCode()
        }
    }

    companion object {
        @Throws(LabelSyntaxException::class)
        fun create(patterns: com.google.common.collect.ImmutableList<String>): SimpleTargetPatternMatcher {
            val singlePatternMatcherBuilder: com.google.common.collect.ImmutableList.Builder<SinglePatternMatcher?> =
                com.google.common.collect.ImmutableList.builder<SinglePatternMatcher?>()
            for (pattern in patterns) {
                val matcher = createSinglePatternMatcher(pattern)
                singlePatternMatcherBuilder.add(matcher)
            }
            return SimpleTargetPatternMatcher(singlePatternMatcherBuilder.build())
        }

        @Throws(LabelSyntaxException::class)
        private fun createSinglePatternMatcher(pattern: String): SinglePatternMatcher {
            var pattern = pattern
            if (pattern.startsWith("-")) {
                // Strip off the leading '-' and create a matcher for what remains. This will technically
                // handle a series of nested negative patterns (like `---//exact:target`), but isn't worth
                // detecting and throwing an error.
                pattern = pattern.substring(1)
                val inner = createSinglePatternMatcher(pattern)
                return NegativeMatcher(inner)
            } else if (pattern.endsWith("/...")) {
                return com.google.devtools.build.lib.collect.SimpleTargetPatternMatcher.WildcardMatcher(pattern)
            }

            // Just match the pattern as an exact label.
            return ExactMatcher(pattern)
        }
    }
}
