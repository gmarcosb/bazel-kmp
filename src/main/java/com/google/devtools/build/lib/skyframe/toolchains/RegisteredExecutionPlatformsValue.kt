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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.cmdline.Label

/**
 * A value which represents every execution platform known to Bazel and available to run actions.
 * 
 * @param rejectedPlatforms Any execution platforms that were rejected, along with a reason. The
 * keys are the platform label, and the value is the rejection reason. Only non-null if [     ][RegisteredExecutionPlatformsValue.Key.debug] is `true`.
 */
@AutoCodec
class RegisteredExecutionPlatformsValue(
    registeredExecutionPlatformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?,
    rejectedPlatforms: com.google.common.collect.ImmutableMap<Label?, String?>?
) : SkyValue {
    /** [SkyKey] implementation used for [RegisteredExecutionPlatformsFunction].  */
    @AutoCodec
    @VisibleForSerialization
    class Key private constructor(configurationKey: BuildConfigurationKey?, debug: Boolean) : SkyKey {
        private val configurationKey: BuildConfigurationKey?
        private val debug: Boolean

        init {
            this.configurationKey = configurationKey
            this.debug = debug
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.REGISTERED_EXECUTION_PLATFORMS
        }

        fun configurationKey(): BuildConfigurationKey? {
            return configurationKey
        }

        fun debug(): Boolean {
            return debug
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is Key) {
                return false
            }
            return this.configurationKey == obj.configurationKey
                    && this.debug == obj.debug
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(configurationKey, debug)
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper("RegisteredExecutionPlatformsValue.Key")
                .add("configurationKey", configurationKey())
                .add("debug", debug())
                .toString()
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.toolchains.RegisteredExecutionPlatformsValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun of(configurationKey: BuildConfigurationKey?, debug: Boolean): Key {
                return com.google.devtools.build.lib.skyframe.toolchains.RegisteredExecutionPlatformsValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.toolchains.RegisteredExecutionPlatformsValue.Key(
                        configurationKey,
                        debug
                    )
                )
            }

            @VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.toolchains.RegisteredExecutionPlatformsValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    val registeredExecutionPlatformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?
    val rejectedPlatforms: com.google.common.collect.ImmutableMap<Label?, String?>?

    init {
        this.rejectedPlatforms = rejectedPlatforms
        this.registeredExecutionPlatformKeys = registeredExecutionPlatformKeys
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?>(
            registeredExecutionPlatformKeys,
            "registeredExecutionPlatformKeys"
        )
    }

    companion object {
        /** Returns the [SkyKey] for [RegisteredExecutionPlatformsValue]s.  */
        fun key(configurationKey: BuildConfigurationKey?, debug: Boolean): SkyKey {
            return com.google.devtools.build.lib.skyframe.toolchains.RegisteredExecutionPlatformsValue.Key.Companion.of(
                configurationKey,
                debug
            )
        }

        fun create(
            registeredExecutionPlatformKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>?,
            rejectedPlatforms: com.google.common.collect.ImmutableMap<Label?, String?>?
        ): RegisteredExecutionPlatformsValue {
            return RegisteredExecutionPlatformsValue(
                registeredExecutionPlatformKeys, rejectedPlatforms
            )
        }
    }
}
