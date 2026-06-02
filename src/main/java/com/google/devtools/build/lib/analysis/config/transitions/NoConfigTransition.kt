// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * Transitions to a stable, empty configuration for rules that don't rely on configuration.
 * 
 * 
 * This prevents unnecessary configured target forking, which prevents unnecessary build graph
 * bloat. That in turn reduces build time and memory use.
 * 
 * 
 * For example, imagine `cc_library //:foo` in config A depends on config-independent
 * target `//:noconfig` and `cc_library //:bar` in config B also depends on `//:noconfig`. Without transitions, `//:noconfig` will be configured and analyzed twice: for
 * configs A and B. This is completely wasteful if `//:noconfig` does the same thing
 * regardless of configuration. Instead, apply this transition to `//:noconfig`.
 * 
 * 
 * The empty configuration produced by this transition has no native fragments other than [ ], and even this has only the default values for its options. This can have surprising
 * effects; for instance, `--check_visibility` gets reset to `true`, making it
 * impossible to disable visibility checking within a `constraint_value`'s `constraint_setting` attribute.
 * 
 * 
 * This is safest for rules that don't produce actions and don't have dependencies. Remember that
 * even if a rule doesn't read configuration, if any of its transitive dependencies read
 * configuration or if the rule has a `select()`, its output may still be
 * configuration-dependent. So use with careful discretion.
 */
class NoConfigTransition private constructor() : PatchTransition {
    override fun requiresOptionFragments(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
        return com.google.common.collect.ImmutableSet.of<E?>(CoreOptions::class.java)
    }

    override fun patch(
        options: BuildOptionsView?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): BuildOptions {
        return CommonOptions.EMPTY_OPTIONS
    }

    /** A [TransitionFactory] implementation that generates the transition.  */
    internal class Factory<T : com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> :
        TransitionFactory<T?>, ConfigurationTransitionApi {
        override fun create(unused: T?): PatchTransition {
            return INSTANCE
        }

        override fun transitionType(): TransitionType {
            return TransitionType.ANY
        }

        val isTool: Boolean
            get() = true
    }

    companion object {
        @SerializationConstant
        val INSTANCE: NoConfigTransition = NoConfigTransition()
        private val FACTORY_INSTANCE: TransitionFactory<out com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> =
            com.google.devtools.build.lib.analysis.config.transitions.NoConfigTransition.Factory<com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?>()

        /**
         * Returns `true` if the given [TransitionFactory] is an instance of the no
         * transition.
         */
        fun <T : com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> isInstance(
            instance: TransitionFactory<T?>?
        ): Boolean {
            return instance is Factory<*>
        }

        /** Returns a [TransitionFactory] instance that generates the transition.  */
        @kotlin.jvm.JvmStatic
        fun <T : com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> getFactory(): TransitionFactory<T?>? {
            val castFactory: TransitionFactory<T?>? = FACTORY_INSTANCE as TransitionFactory<T?>?
            return castFactory
        }
    }
}
