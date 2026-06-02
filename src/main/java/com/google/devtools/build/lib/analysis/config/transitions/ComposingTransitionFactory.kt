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
package com.google.devtools.build.lib.analysis.config.transitions

import com.google.devtools.build.lib.analysis.config.BuildOptionDetails

/**
 * A transition factory that composes two other transition factories in an ordered sequence.
 * 
 * 
 * Example:
 * 
 * <pre>
 * transitionFactory1: { someSetting = $oldVal + " foo" }
 * transitionFactory2: { someSetting = $oldVal + " bar" }
 * ComposingTransitionFactory(transitionFactory1, transitionFactory2):
 * { someSetting = $oldVal + " foo bar" }
</pre> * 
 */
@AutoValue
abstract class ComposingTransitionFactory<T : com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?>
    : TransitionFactory<T?> {
    override fun create(data: T?): ConfigurationTransition {
        val transition1: ConfigurationTransition = transitionFactory1().create(data)
        val transition2: ConfigurationTransition = transitionFactory2().create(data)
        return ComposingTransition(transition1, transition2)
    }

    override fun transitionType(): TransitionType? {
        // Both types must match so this is correct.
        return transitionFactory1().transitionType()
    }

    abstract fun transitionFactory1(): TransitionFactory<T?>?

    abstract fun transitionFactory2(): TransitionFactory<T?>?

    val isTool: Boolean
        get() = transitionFactory1().isTool() || transitionFactory2().isTool()

    val isSplit: Boolean
        get() = transitionFactory1().isSplit() || transitionFactory2().isSplit()

    override fun visit(visitor: com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Visitor<T?>) {
        this.transitionFactory1().visit(visitor)
        this.transitionFactory2().visit(visitor)
    }

    /** A configuration transition that composes two other transitions in an ordered sequence.  */
    private class ComposingTransition(transition1: ConfigurationTransition, transition2: ConfigurationTransition) :
        ConfigurationTransition {
        private val transition1: ConfigurationTransition
        private val transition2: ConfigurationTransition

        /**
         * Creates a [ComposingTransition] that applies the sequence: `fromOptions -> transition1 -> transition2 -> toOptions `.
         */
        init {
            this.transition1 = transition1
            this.transition2 = transition2
        }

        override fun addRequiredFragments(
            requiredFragments: RequiredConfigFragmentsProvider.Builder,
            optionDetails: BuildOptionDetails?
        ) {
            // At first glance this code looks wrong. A composing transition applies transition2 over
            // transition1's outputs, not the original options. We don't have to worry about that here
            // because the reason we pass the options is so Starlark transitions can map individual flags
            // like "//command_line_option:copts" to the fragments that own them. This doesn't depend on
            // the
            // flags' values. This is fortunate, because it producers simpler, faster code and cleaner
            // interfaces.
            transition1.addRequiredFragments(requiredFragments, optionDetails)
            transition2.addRequiredFragments(requiredFragments, optionDetails)
        }

        @Throws(java.lang.InterruptedException::class)
        override fun apply(
            buildOptions: BuildOptionsView, eventHandler: com.google.devtools.build.lib.events.EventHandler?
        ): com.google.common.collect.ImmutableMap<String?, BuildOptions?> {
            val toOptions: com.google.common.collect.ImmutableMap.Builder<String?, BuildOptions?> =
                com.google.common.collect.ImmutableMap.builder<String?, BuildOptions?>()
            val transition1Output: MutableMap<String?, BuildOptions?> =
                transition1.apply(
                    TransitionUtil.restrict(transition1, buildOptions.underlying()), eventHandler
                )
            for (entry1 in transition1Output.entries) {
                val transition2Output: MutableMap<String?, BuildOptions?> =
                    transition2.apply(
                        TransitionUtil.restrict(transition2, entry1.value), eventHandler
                    )
                for (entry2 in transition2Output.entries) {
                    toOptions.put(composeKeys(entry1.key!!, entry2.key!!), entry2.value)
                }
            }
            return toOptions.buildOrThrow()
        }

        override fun reasonForOverride(): String {
            return "Basic abstraction for combining other transitions"
        }

        val name: String
            get() = "(" + transition1.getName() + " + " + transition2.getName() + ")"

        // Override to allow recursive visiting.
        @Throws(E::class)
        override fun <E : java.lang.Exception?> visit(visitor: com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition.Visitor<E?>) {
            this.transition1.visit<E?>(visitor)
            this.transition2.visit<E?>(visitor)
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(transition1, transition2)
        }

        override fun equals(other: Any?): Boolean {
            return other is ComposingTransition
                    && other.transition1 == this.transition1
                    && other.transition2 == this.transition2
        }

        /**
         * Composes a new key out of two given keys. Composing two split transitions is not allowed at
         * the moment, so what this essentially does are (1) make sure not both transitions are split
         * and (2) choose one from a split transition, if there's any, or return `PATCH_TRANSITION_KEY`, if there isn't.
         */
        fun composeKeys(key1: String, key2: String): String {
            if (key1 != ConfigurationTransition.Companion.PATCH_TRANSITION_KEY) {
                check(key2 == ConfigurationTransition.Companion.PATCH_TRANSITION_KEY) {
                    String.format(
                        "can't compose two split transitions %s and %s",
                        transition1.getName(), transition2.getName()
                    )
                }
                return key1
            }
            return key2
        }
    }

    companion object {
        /**
         * Creates a [ComposingTransitionFactory] that applies the given factories in sequence:
         * `fromOptions -> transition1 -> transition2 -> toOptions `.
         * 
         * 
         * Note that this method checks for transition factories that cannot be composed, such as if
         * one of the transitions is [NoTransition], and returns an efficiently composed transition.
         */
        fun <T : com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> of(
            transitionFactory1: TransitionFactory<T?>, transitionFactory2: TransitionFactory<T?>
        ): TransitionFactory<T?> {
            com.google.common.base.Preconditions.checkNotNull<TransitionFactory<T?>?>(transitionFactory1)
            com.google.common.base.Preconditions.checkNotNull<TransitionFactory<T?>?>(transitionFactory2)
            com.google.common.base.Preconditions.checkArgument(
                transitionFactory1.transitionType().isCompatibleWith(transitionFactory2.transitionType()),
                "transition factory types must be compatible"
            )
            com.google.common.base.Preconditions.checkArgument(
                !transitionFactory1.isSplit() || !transitionFactory2.isSplit(),
                "can't compose two split transition factories"
            )

            if (NoTransition.Companion.isInstance<T?>(transitionFactory1)) {
                // Since transitionFactory1 causes no changes, use transitionFactory2 directly.
                return transitionFactory2
            } else if (NoTransition.Companion.isInstance<T?>(transitionFactory2)) {
                // Since transitionFactory2 causes no changes, use transitionFactory1 directly.
                return transitionFactory1
            }

            return Companion.create<T?>(transitionFactory1, transitionFactory2)
        }

        private fun <T : com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> create(
            transitionFactory1: TransitionFactory<T?>?, transitionFactory2: TransitionFactory<T?>?
        ): TransitionFactory<T?> {
            return AutoValue_ComposingTransitionFactory<T?>(transitionFactory1, transitionFactory2)
        }
    }
}
