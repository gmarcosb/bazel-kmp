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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/**
 * A value referring to a computed set of resolved targets. This is used for the results of target
 * pattern parsing.
 */
@Immutable
@ThreadSafe
class TargetPatternValue internal constructor(targets: ResolvedTargets<Label?>?) : SkyValue {
    private val targets: ResolvedTargets<Label?>

    init {
        this.targets = com.google.common.base.Preconditions.checkNotNull<ResolvedTargets<Label?>>(targets)
    }

    private class TargetPatternKeyWithExclusionsResult(
        targetPatternKeyMaybe: java.util.Optional<TargetPatternKey?>,
        indicesOfNegativePatternsThatNeedToBeIncluded: com.google.common.collect.ImmutableList<Int?>?
    ) {
        private val targetPatternKeyMaybe: java.util.Optional<TargetPatternKey?>
        private val indicesOfNegativePatternsThatNeedToBeIncluded: com.google.common.collect.ImmutableList<Int?>?

        init {
            this.targetPatternKeyMaybe = targetPatternKeyMaybe
            this.indicesOfNegativePatternsThatNeedToBeIncluded =
                indicesOfNegativePatternsThatNeedToBeIncluded
        }
    }

    fun getTargets(): ResolvedTargets<Label?> {
        return targets
    }

    /**
     * A TargetPatternKey is a tuple of pattern (eg, "foo/..."), filtering policy, a relative pattern
     * offset, whether it is a positive or negative match, and a set of excluded subdirectories.
     */
    @ThreadSafe
    class TargetPatternKey private constructor(
        signedParsedPattern: SignedTargetPattern?,
        policy: FilteringPolicy?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?
    ) : SkyKey {
        private val signedParsedPattern: SignedTargetPattern
        private val policy: FilteringPolicy

        /**
         * Must be "compatible" with [.signedParsedPattern]: if [.signedParsedPattern] is a
         * [TargetsBelowDirectory] object, then [TargetsBelowDirectory.containedIn] is false
         * for every element of `excludedSubdirectories`.
         */
        private val excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>

        constructor(signedParsedPattern: SignedTargetPattern?, policy: FilteringPolicy?) : this(
            signedParsedPattern,
            policy,
            com.google.common.collect.ImmutableSet.of<PathFragment?>()
        )

        init {
            this.signedParsedPattern =
                com.google.common.base.Preconditions.checkNotNull<SignedTargetPattern>(signedParsedPattern)
            this.policy = com.google.common.base.Preconditions.checkNotNull<FilteringPolicy>(policy)
            this.excludedSubdirectories =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<PathFragment?>>(
                    excludedSubdirectories
                )
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.TARGET_PATTERN
        }

        val pattern: String
            get() = signedParsedPattern.pattern().originalPattern

        val parsedPattern: TargetPattern
            get() = signedParsedPattern.pattern()

        private fun getSignedParsedPattern(): SignedTargetPattern {
            return signedParsedPattern
        }

        val isNegative: Boolean
            get() = signedParsedPattern.sign() === Sign.NEGATIVE

        fun getPolicy(): FilteringPolicy {
            return policy
        }

        fun getExcludedSubdirectories(): com.google.common.collect.ImmutableSet<PathFragment?> {
            return excludedSubdirectories
        }

        override fun toString(): String {
            return java.lang.String.format(
                "%s, excludedSubdirs=%s, filteringPolicy=%s",
                (if (this.isNegative) "-" else "") + signedParsedPattern.pattern().originalPattern,
                excludedSubdirectories,
                policy
            )
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(signedParsedPattern, policy, excludedSubdirectories)
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is TargetPatternKey) {
                return false
            }

            return obj.signedParsedPattern.equals(this.signedParsedPattern)
                    && obj.policy.equals(this.policy)
                    && obj.excludedSubdirectories == this.excludedSubdirectories
        }
    }

    companion object {
        /**
         * Create a target pattern [SkyKey].
         * 
         * @param pattern The pattern, eg "-foo/biz...".
         * @param policy The filtering policy, eg "only return test targets"
         */
        @ThreadSafe
        fun key(pattern: SignedTargetPattern, policy: FilteringPolicy?): TargetPatternKey {
            return TargetPatternKey(
                pattern, if (pattern.sign() === Sign.POSITIVE) policy else FilteringPolicies.NO_FILTER
            )
        }

        /**
         * Returns an iterable of [TargetPatternKey]s, in the same order as the list of patterns
         * provided as input.
         * 
         * @param patterns The list of patterns, eg "-foo/biz...".
         * @param policy The filtering policy, eg "only return test targets"
         */
        @ThreadSafe
        fun keys(
            patterns: MutableList<SignedTargetPattern?>, policy: FilteringPolicy?
        ): Iterable<TargetPatternKey?> {
            return patterns.stream()
                .map<TargetPatternKey?>(java.util.function.Function { pattern: SignedTargetPattern? ->
                    key(
                        pattern,
                        policy
                    )
                }).collect(com.google.common.collect.ImmutableList.toImmutableList<TargetPatternKey?>())
        }

        @ThreadSafe
        fun combineTargetsBelowDirectoryWithNegativePatterns(
            keys: MutableList<TargetPatternKey>, excludeSingleTargets: Boolean
        ): com.google.common.collect.ImmutableList<TargetPatternKey?> {
            val builder: com.google.common.collect.ImmutableList.Builder<TargetPatternKey?> =
                com.google.common.collect.ImmutableList.builder<TargetPatternKey?>()
            // We use indicesOfNegativePatternsThatNeedToBeIncluded to avoid adding negative TBD or single
            // target patterns that have already been combined with previous patterns as an excluded
            // directory or excluded single target.
            val indicesOfNegativePatternsThatNeedToBeIncluded: HashSet<Int?> = HashSet<Int?>()
            var positivePatternSeen = false
            for (i in keys.indices) {
                val targetPatternKey = keys.get(i)
                if (targetPatternKey.isNegative) {
                    if (indicesOfNegativePatternsThatNeedToBeIncluded.contains(i) || !positivePatternSeen) {
                        builder.add(targetPatternKey)
                    }
                } else {
                    positivePatternSeen = true
                    val result =
                        computeTargetPatternKeyWithExclusions(targetPatternKey, i, keys, excludeSingleTargets)
                    result.targetPatternKeyMaybe.ifPresent(java.util.function.Consumer { element: TargetPatternKey? ->
                        builder.add(
                            element
                        )
                    })
                    indicesOfNegativePatternsThatNeedToBeIncluded.addAll(
                        result.indicesOfNegativePatternsThatNeedToBeIncluded
                    )
                }
            }
            return builder.build()
        }

        private fun setExcludedDirectoriesAndTargets(
            original: TargetPatternKey,
            excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
            excludedSingleTargets: com.google.common.collect.ImmutableSet<Label?>
        ): TargetPatternKey {
            var policy: FilteringPolicy? = original.getPolicy()
            if (!excludedSingleTargets.isEmpty()) {
                policy =
                    FilteringPolicies.and(policy, TargetExcludingFilteringPolicy(excludedSingleTargets))
            }
            return TargetPatternKey(original.getSignedParsedPattern(), policy, excludedSubdirectories)
        }

        private fun computeTargetPatternKeyWithExclusions(
            targetPatternKey: TargetPatternKey,
            position: Int,
            keys: MutableList<TargetPatternKey>,
            excludeSingleTargets: Boolean
        ): TargetPatternKeyWithExclusionsResult {
            val targetPattern: TargetPattern = targetPatternKey.parsedPattern
            val excludedDirectoriesBuilder: com.google.common.collect.ImmutableSet.Builder<PathFragment?> =
                com.google.common.collect.ImmutableSet.builder<PathFragment?>()
            val excludedSingleTargetsBuilder: com.google.common.collect.ImmutableSet.Builder<Label?> =
                com.google.common.collect.ImmutableSet.builder<Label?>()
            val indicesOfNegativePatternsThatNeedToBeIncludedBuilder: com.google.common.collect.ImmutableList.Builder<Int?> =
                com.google.common.collect.ImmutableList.builder<Int?>()
            for (j in position + 1..<keys.size()) {
                val laterTargetPatternKey = keys.get(j)
                val laterParsedPattern: TargetPattern = laterTargetPatternKey.parsedPattern
                if (!laterTargetPatternKey.isNegative) {
                    continue
                }
                if (laterParsedPattern.type === Type.TARGETS_BELOW_DIRECTORY) {
                    val laterParsedTargetsBelowDirectory: TargetsBelowDirectory =
                        laterParsedPattern as TargetsBelowDirectory
                    if (targetPattern.type === Type.TARGETS_BELOW_DIRECTORY) {
                        val targetsBelowDirectory: TargetsBelowDirectory = targetPattern as TargetsBelowDirectory
                        if (laterParsedTargetsBelowDirectory.contains(targetsBelowDirectory)
                            === ContainsResult.DIRECTORY_EXCLUSION_WOULD_BE_EXACT
                        ) {
                            return TargetPatternKeyWithExclusionsResult(
                                java.util.Optional.empty<TargetPatternKey?>(),
                                com.google.common.collect.ImmutableList.of<Int?>()
                            )
                        } else {
                            when (targetsBelowDirectory.contains(laterParsedTargetsBelowDirectory)) {
                                DIRECTORY_EXCLUSION_WOULD_BE_EXACT -> excludedDirectoriesBuilder.add(
                                    laterParsedTargetsBelowDirectory.getDirectory().getPackageFragment()
                                )

                                DIRECTORY_EXCLUSION_WOULD_BE_TOO_BROAD -> indicesOfNegativePatternsThatNeedToBeIncludedBuilder.add(
                                    j
                                )

                                NOT_CONTAINED -> {}
                            }
                        }
                    }
                } else if (excludeSingleTargets && laterParsedPattern.type === Type.SINGLE_TARGET) {
                    excludedSingleTargetsBuilder.add(laterParsedPattern.getSingleTargetLabel())
                } else {
                    indicesOfNegativePatternsThatNeedToBeIncludedBuilder.add(j)
                }
            }
            return TargetPatternKeyWithExclusionsResult(
                java.util.Optional.of<TargetPatternKey?>(
                    setExcludedDirectoriesAndTargets(
                        targetPatternKey,
                        excludedDirectoriesBuilder.build(),
                        excludedSingleTargetsBuilder.build()
                    )
                ),
                indicesOfNegativePatternsThatNeedToBeIncludedBuilder.build()
            )
        }
    }
}
