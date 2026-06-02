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
// limitations under the License
package com.google.devtools.build.lib.rules.config

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** Marker interface for detecting feature flags in the Starlark setting map.  */
interface FeatureFlagValue {
    /** A feature flag value for a flag known to be set to a particular value.  */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class SetValue(val value: String?) : FeatureFlagValue {
        override fun toString(): String {
            return String.format("FeatureFlagValue.SetValue{%s}", this.value)
        }

        init {
            String > java.util.Objects.requireNonNull<String?>(value, "value")
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun of(value: String?): SetValue {
                return SetValue(value)
            }
        }
    }

    /** A feature flag value for a flag known to be set to its default value.  */
    enum class DefaultValue : FeatureFlagValue {
        INSTANCE;

        override fun toString(): String {
            return "FeatureFlagValue.DefaultValue{}"
        }
    }

    /** A feature flag value for a flag which was requested but which value was already trimmed.  */
    enum class UnknownValue : FeatureFlagValue {
        INSTANCE;

        override fun toString(): String {
            return "FeatureFlagValue.UnknownValue{}"
        }
    }

    companion object {
        /** Returns a new BuildOptions with a new map of feature flag values.  */
        fun replaceFlagValues(original: BuildOptions, newValues: MutableMap<Label?, String?>): BuildOptions {
            val result: BuildOptions.Builder = original.toBuilder()
            for (entry in original.getStarlarkOptions().entrySet()) {
                if (entry.value is FeatureFlagValue) {
                    result.removeStarlarkOption(entry.key)
                }
            }
            val newValueObjects: com.google.common.collect.ImmutableMap.Builder<Label?, Any?> =
                com.google.common.collect.ImmutableMap.Builder<Label?, Any?>()
            for (entry in newValues.entries) {
                newValueObjects.put(entry.key, SetValue.Companion.of(entry.value))
            }
            result.addStarlarkOptions(newValueObjects.buildOrThrow())
            val builtResult: BuildOptions = result.build()
            val configFeatureFlagOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                builtResult.get(ConfigFeatureFlagOptions::class.java)
            if (configFeatureFlagOptions != null) {
                configFeatureFlagOptions.setAllFeatureFlagValuesArePresent(true)
            }
            return builtResult
        }

        /** Returns a new BuildOptions with the feature flag values trimmed down to the given flags.  */
        fun trimFlagValues(original: BuildOptions, availableFlags: MutableSet<Label?>): BuildOptions {
            // An important performance property of this method is that we don't create a new BuildOptions
            // instance unless we really need one. This particularly saves the expensive cost of
            // BuildOptions.hashCode(). Since this method is called unconditionally over every configured
            // target, this has real observable effect on build analysis time.
            val seenFlags: MutableSet<Label?> = LinkedHashSet<Label?>()
            val flagsToTrim: MutableSet<Label?> = LinkedHashSet<Label?>()
            val unknownFlagsToAdd: MutableMap<Label?, Any?> = LinkedHashMap<Label?, Any?>()
            val originalConfigFeatureFlagOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                original.get(ConfigFeatureFlagOptions::class.java)
            val changeAllValuesPresentOption =
                originalConfigFeatureFlagOptions != null
                        && originalConfigFeatureFlagOptions.getAllFeatureFlagValuesArePresent()

            // What do we need to change?
            original.getStarlarkOptions().entrySet().stream()
                .filter({ entry -> entry.getValue() is FeatureFlagValue })
                .forEach({ featureFlagEntry -> seenFlags.add(featureFlagEntry.getKey()) })
            flagsToTrim.addAll(com.google.common.collect.Sets.difference<Label?>(seenFlags, availableFlags))
            val unknownFlagValue: FeatureFlagValue =
                if (changeAllValuesPresentOption) com.google.devtools.build.lib.rules.config.FeatureFlagValue.DefaultValue.INSTANCE else UnknownValue.INSTANCE
            for (unknownFlag in com.google.common.collect.Sets.difference<Label?>(availableFlags, seenFlags)) {
                unknownFlagsToAdd.put(unknownFlag, unknownFlagValue)
            }

            // Nothing changed? Return the original BuildOptions.
            if (flagsToTrim.isEmpty() && unknownFlagsToAdd.isEmpty() && !changeAllValuesPresentOption) {
                return original
            }

            // Else construct a new one. This should not be the common case.
            val result: BuildOptions.Builder = original.toBuilder()
            for (trimmedFlag in flagsToTrim) {
                result.removeStarlarkOption(trimmedFlag)
            }
            unknownFlagsToAdd.forEach { (flag: Label?, value: Any?) -> result.addStarlarkOption(flag, value) }
            val builtResult: BuildOptions = result.build()
            val builtConfigFeatureFlagOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                builtResult.get(ConfigFeatureFlagOptions::class.java)
            if (builtConfigFeatureFlagOptions != null) {
                builtConfigFeatureFlagOptions.setAllFeatureFlagValuesArePresent(false)
            }
            return builtResult
        }
    }
}
