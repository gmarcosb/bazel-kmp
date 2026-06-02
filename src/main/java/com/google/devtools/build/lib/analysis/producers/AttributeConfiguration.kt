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
package com.google.devtools.build.lib.analysis.producers

import com.google.auto.value.AutoOneOf
import com.google.devtools.build.lib.analysis.producers.AttributeConfiguration
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey

@AutoOneOf(com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind::class)
internal abstract class AttributeConfiguration {
    internal enum class Kind {
        /**
         * This is a visibility dependency.
         * 
         * 
         * Visibility dependencies have null configuration as they are not configurable. Once the
         * target is known, it should be verified to be a [PackageGroup].
         */
        VISIBILITY,

        /**
         * The configuration is null.
         * 
         * 
         * This is only applied when the dependency is in the same package as the parent and it is
         * not configurable. The value in this case stores any transition keys.
         */
        NULL_TRANSITION_KEYS,

        /**
         * There is a single configuration.
         * 
         * 
         * This can be the result of a patch transition or no transition at all.
         */
        UNARY,

        /**
         * There is a split transition.
         * 
         * 
         * It's possible for there to be only one entry.
         */
        SPLIT
    }

    abstract fun kind(): Kind?

    abstract fun visibility()

    abstract fun nullTransitionKeys(): com.google.common.collect.ImmutableList<String?>?

    abstract fun unary(): BuildConfigurationKey?

    abstract fun split(): com.google.common.collect.ImmutableMap<String?, BuildConfigurationKey?>?

    fun count(): Int {
        return when (kind()) {
            com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.VISIBILITY, com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.NULL_TRANSITION_KEYS, com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.UNARY -> 1
            com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.SPLIT -> split().size
        }
    }

    companion object {
        fun ofVisibility(): AttributeConfiguration {
            return AutoOneOf_AttributeConfiguration.visibility()
        }

        fun ofNullTransitionKeys(transitionKeys: com.google.common.collect.ImmutableList<String?>?): AttributeConfiguration {
            return AutoOneOf_AttributeConfiguration.nullTransitionKeys(transitionKeys)
        }

        fun ofUnary(key: BuildConfigurationKey?): AttributeConfiguration {
            return AutoOneOf_AttributeConfiguration.unary(key)
        }

        fun ofSplit(
            configurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationKey?>?
        ): AttributeConfiguration {
            return AutoOneOf_AttributeConfiguration.split(configurations)
        }
    }
}
