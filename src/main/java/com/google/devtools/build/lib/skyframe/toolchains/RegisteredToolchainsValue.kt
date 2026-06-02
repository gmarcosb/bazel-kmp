// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo

/**
 * A value which represents every toolchain known to Bazel and available for toolchain resolution.
 * 
 * @param rejectedToolchains Any toolchains that were rejected, along with a reason. The row keys
 * are the toolchain type labels, column keys are toolchain target (not implementation) labels,
 * and cells are the reason. Only non-null if [RegisteredToolchainsValue.Key.debug] is
 * `true`.
 */
@AutoCodec
class RegisteredToolchainsValue(
    registeredToolchains: com.google.common.collect.ImmutableList<DeclaredToolchainInfo?>?,
    rejectedToolchains: com.google.common.collect.ImmutableTable<Label?, Label?, String?>?
) : SkyValue {
    /** A [SkyKey] for `RegisteredToolchainsValue`.  */
    @AutoCodec
    class Key private constructor(configurationKey: BuildConfigurationKey?, debug: Boolean) : SkyKey {
        private val configurationKey: BuildConfigurationKey?
        private val debug: Boolean

        init {
            this.configurationKey = configurationKey
            this.debug = debug
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.REGISTERED_TOOLCHAINS
        }

        fun getConfigurationKey(): BuildConfigurationKey? {
            return configurationKey
        }

        fun debug(): Boolean {
            return debug
        }

        override fun toString(): String {
            return ("RegisteredToolchainsValue.Key{"
                    + "configurationKey: "
                    + configurationKey
                    + ", debug: "
                    + debug
                    + "}")
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

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.toolchains.RegisteredToolchainsValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun of(configurationKey: BuildConfigurationKey?, debug: Boolean): Key {
                return com.google.devtools.build.lib.skyframe.toolchains.RegisteredToolchainsValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.toolchains.RegisteredToolchainsValue.Key(
                        configurationKey,
                        debug
                    )
                )
            }

            @VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.toolchains.RegisteredToolchainsValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    val registeredToolchains: com.google.common.collect.ImmutableList<DeclaredToolchainInfo?>?
    val rejectedToolchains: com.google.common.collect.ImmutableTable<Label?, Label?, String?>?

    init {
        this.rejectedToolchains = rejectedToolchains
        this.registeredToolchains = registeredToolchains
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<DeclaredToolchainInfo?>?>(
            registeredToolchains,
            "registeredToolchains"
        )
    }

    companion object {
        /** Returns the [SkyKey] for [RegisteredToolchainsValue]s.  */
        fun key(configurationKey: BuildConfigurationKey?, debug: Boolean): Key {
            return com.google.devtools.build.lib.skyframe.toolchains.RegisteredToolchainsValue.Key.Companion.of(
                configurationKey,
                debug
            )
        }

        fun create(
            registeredToolchains: com.google.common.collect.ImmutableList<DeclaredToolchainInfo?>?,
            rejectedToolchains: com.google.common.collect.ImmutableTable<Label?, Label?, String?>?
        ): RegisteredToolchainsValue {
            return RegisteredToolchainsValue(registeredToolchains, rejectedToolchains)
        }
    }
}
