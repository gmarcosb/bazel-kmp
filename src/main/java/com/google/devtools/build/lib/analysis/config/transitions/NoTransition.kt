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
package com.google.devtools.build.lib.analysis.config.transitions

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** No-op configuration transition.  */
class NoTransition private constructor() : PatchTransition {
    override fun patch(
        options: BuildOptionsView,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): BuildOptions {
        return options.underlying()
    }

    /** A [TransitionFactory] implementation that generates the no transition.  */
    internal class Factory<T : com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> :
        TransitionFactory<T?>, ConfigurationTransitionApi {
        override fun create(unused: T?): ConfigurationTransition {
            return INSTANCE
        }

        override fun transitionType(): TransitionType {
            return TransitionType.ANY
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val INSTANCE: NoTransition = NoTransition()
        private val FACTORY_INSTANCE: TransitionFactory<out com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> =
            com.google.devtools.build.lib.analysis.config.transitions.NoTransition.Factory<com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?>()

        /** Returns a [TransitionFactory] instance that generates the no transition.  */
        @kotlin.jvm.JvmStatic
        fun <T : com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> getFactory(): TransitionFactory<T?>? {
            val castFactory: TransitionFactory<T?>? = FACTORY_INSTANCE as TransitionFactory<T?>?
            return castFactory
        }

        /**
         * Returns `true` if the given [TransitionFactory] is an instance of the no
         * transition.
         */
        fun <T : com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory.Data?> isInstance(
            instance: TransitionFactory<T?>?
        ): Boolean {
            return instance is Factory<*>
        }

        /**
         * Returns `true` if the given [ConfigurationTransition] is an instance of the no
         * transition.
         */
        fun isInstance(transition: ConfigurationTransition?): Boolean {
            return transition is NoTransition
        }
    }
}
