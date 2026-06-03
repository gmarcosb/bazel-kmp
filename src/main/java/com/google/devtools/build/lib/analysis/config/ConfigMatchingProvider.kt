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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.TransitiveInfoProvider

/**
 * A "configuration target" that asserts whether or not it matches the configuration it's bound to.
 * 
 * 
 * This can be used, e.g., to declare a BUILD target that defines the conditions which trigger a
 * configurable attribute branch. In general, this can be used to trigger for any user-configurable
 * build behavior.
 */
@Immutable
@AutoValue
abstract class ConfigMatchingProvider : TransitiveInfoProvider {
    /**
     * Potential values for result field.
     * 
     * 
     * Note that while it is possible to be more aggressive in interpreting and merging
     * MatchResult, currently taking a more cautious approach and focusing on propagating errors.
     * 
     * 
     * e.g. If merging where one is InError and the other is No, then currently will propagate the
     * errors, versus a more aggressive future approach could just propagate No.)
     */
    interface MatchResult {
        /**
         * The configuration matches.
         * 
         * 
         * Preferably use the shared [MatchResult.MATCH] instance of this class.
         */
        @AutoCodec
        class Match : MatchResult

        /**
         * The configuration does not match.
         * 
         * @param diffs an optional list of diffs that describe the differences between the expected and
         * actual configuration
         */
        @AutoCodec
        class NoMatch @AutoCodec.Instantiator constructor(diffs: com.google.common.collect.ImmutableList<Diff?>?) :
            MatchResult {
            constructor(diff: Diff) : this(com.google.common.collect.ImmutableList.of<Diff?>(diff))

            /**
             * A human-readable description of the difference between the expected and actual
             * configuration.
             * 
             * @param what the label of the constraint or setting that failed to match
             * @param got the actual value of the setting
             * @param want the expected value of the setting
             */
            @AutoCodec
            class Diff(what: Label?, got: String?, want: String?) {
                /** A builder for [Diff].  */
                @AutoBuilder
                abstract class Builder {
                    abstract fun what(what: Label?): Builder?

                    abstract fun got(got: String?): Builder?

                    abstract fun want(want: String?): Builder?

                    abstract fun build(): Diff?
                }

                val what: Label?
                val got: String?
                val want: String?

                init {
                    this.what = what
                    this.got = got
                    this.want = want
                }

                companion object {
                    fun what(what: Label?): Builder {
                        return AutoBuilder_ConfigMatchingProvider_MatchResult_NoMatch_Diff_Builder()
                            .what(what)
                    }
                }
            }

            val diffs: com.google.common.collect.ImmutableList<Diff?>?

            init {
                this.diffs = diffs
            }
        }

        /** Errors make the match question irresolvable.  */
        @AutoCodec
        class InError(errors: com.google.common.collect.ImmutableList<String?>?) : MatchResult {
            val errors: com.google.common.collect.ImmutableList<String?>?

            init {
                this.errors = errors
            }
        }

        companion object {
            fun combine(previous: MatchResult, current: MatchResult?): MatchResult? {
                return when (previous) {
                    -> when (current) {
                        -> InError(
                            com.google.common.collect.ImmutableList.builder<String?>()
                                .addAll(previousErrors)
                                .addAll(currentErrors)
                                .build()
                        )

                        else -> previous
                    }

                    -> when (current) {
                        -> current
                        -> NoMatch(
                            com.google.common.collect.ImmutableList.builder<NoMatch.Diff?>()
                                .addAll(previousDiffs)
                                .addAll(currentDiffs)
                                .build()
                        )

                        -> previous
                    }

                    -> current
                }
            }

            val MATCH: MatchResult =
                com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider.MatchResult.Match()

            /**
             * A result for the case in which an analysis error occurred that prevents the match from being
             * evaluated.
             */
            val ALREADY_REPORTED_NO_MATCH: MatchResult =
                NoMatch(com.google.common.collect.ImmutableList.of<NoMatch.Diff?>())
        }
    }

    /** Result of accumulating match results: contains any errors or non-matching labels.  */
    class AccumulateResults(
        nonMatching: com.google.common.collect.ImmutableList<Label?>?,
        errors: com.google.common.collect.ImmutableMultimap<Label?, String?>?
    ) {
        fun success(): Boolean {
            return nonMatching.isEmpty() && errors.isEmpty()
        }

        val nonMatching: com.google.common.collect.ImmutableList<Label?>?
        val errors: com.google.common.collect.ImmutableMultimap<Label?, String?>?

        init {
            this.nonMatching = nonMatching
            this.errors = errors
        }
    }

    /** The target's label.  */
    abstract fun label(): Label?

    abstract fun settingsMap(): com.google.common.collect.ImmutableMultimap<String?, String?>?

    abstract fun flagSettingsMap(): com.google.common.collect.ImmutableMap<Label?, String?>?

    abstract fun constraintValuesSetting(): com.google.common.collect.ImmutableSet<Label?>

    /**
     * Whether or not the configuration criteria defined by this target match its actual
     * configuration.
     */
    abstract fun result(): MatchResult?

    /**
     * Returns true if this matcher's conditions are a proper superset of another matcher's
     * conditions, i.e. if this matcher is a specialization of the other one.
     */
    fun refines(other: ConfigMatchingProvider): Boolean {
        val settings: com.google.common.collect.ImmutableSet<MutableMap.MutableEntry<String?, String?>?> =
            com.google.common.collect.ImmutableSet.copyOf<MutableMap.MutableEntry<String?, String?>?>(settingsMap().entries())
        val otherSettings: com.google.common.collect.ImmutableSet<MutableMap.MutableEntry<String?, String?>?> =
            com.google.common.collect.ImmutableSet.copyOf<MutableMap.MutableEntry<String?, String?>?>(
                other.settingsMap().entries()
            )
        val flagSettings: com.google.common.collect.ImmutableSet<MutableMap.MutableEntry<Label?, String?>?> =
            flagSettingsMap().entrySet()
        val otherFlagSettings: com.google.common.collect.ImmutableSet<MutableMap.MutableEntry<Label?, String?>?> =
            other.flagSettingsMap().entrySet()

        val constraintValueSettings: com.google.common.collect.ImmutableSet<Label?> = constraintValuesSetting()
        val otherConstraintValueSettings: com.google.common.collect.ImmutableSet<Label?> =
            other.constraintValuesSetting()

        if (!settings.containsAll(otherSettings) || !flagSettings.containsAll(otherFlagSettings) || !constraintValueSettings.containsAll(
                otherConstraintValueSettings
            )
        ) {
            return false // Not a superset.
        }

        return settings.size() > otherSettings.size() || flagSettings.size() > otherFlagSettings.size() || constraintValueSettings.size() > otherConstraintValueSettings.size()
    }

    /** Format this provider as its label.  */
    override fun toString(): String {
        return label().toString()
    }

    companion object {
        /**
         * Combine the results from the given [ConfigMatchingProvider] instances, returning any
         * errors and non-matching providers.
         */
        fun accumulateMatchResults(providers: MutableList<ConfigMatchingProvider>): AccumulateResults {
            val nonMatching: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builder<Label?>()
            val errors: com.google.common.collect.ImmutableMultimap.Builder<Label?, String?> =
                com.google.common.collect.ImmutableMultimap.builder<Label?, String?>()
            for (configProvider in providers) {
                val matchResult = configProvider.result()
                if (matchResult is) {
                    errors.putAll(configProvider.label(), messages)
                } else if (matchResult is NoMatch) {
                    nonMatching.add(configProvider.label())
                }
            }

            return AccumulateResults(nonMatching.build(), errors.build())
        }

        /**
         * Create a ConfigMatchingProvider.
         * 
         * @param label the build label corresponding to this matcher
         * @param settingsMap the condition settings that trigger this matcher
         * @param flagSettingsMap the label-keyed settings that trigger this matcher
         * @param result whether the current associated configuration matches, doesn't match, or is
         * irresolvable due to specified issue
         */
        fun create(
            label: Label?,
            settingsMap: com.google.common.collect.ImmutableMultimap<String?, String?>?,
            flagSettingsMap: com.google.common.collect.ImmutableMap<Label?, String?>?,
            constraintValueSettings: com.google.common.collect.ImmutableSet<Label?>?,
            result: MatchResult?
        ): ConfigMatchingProvider {
            return AutoValue_ConfigMatchingProvider(
                label, settingsMap, flagSettingsMap, constraintValueSettings, result
            )
        }
    }
}
