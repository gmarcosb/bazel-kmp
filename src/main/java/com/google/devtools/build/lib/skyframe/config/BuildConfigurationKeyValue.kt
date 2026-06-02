// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** Stores a [BuildConfigurationKey] with all platform mappings applied.  */
@AutoCodec
class BuildConfigurationKeyValue internal constructor(buildConfigurationKey: BuildConfigurationKey) : SkyValue {
    /** Key for [BuildConfigurationKeyValue] based on the build options.  */
    @ThreadSafety.Immutable
    @AutoCodec
    class Key private constructor(buildOptions: BuildOptions) : SkyKey {
        private val buildOptions: BuildOptions

        init {
            this.buildOptions = buildOptions
        }

        fun buildOptions(): BuildOptions {
            return buildOptions
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.BUILD_CONFIGURATION_KEY
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || getClass() != o.getClass()) {
                return false
            }
            val key = o as Key
            return buildOptions == key.buildOptions
        }

        override fun hashCode(): Int {
            return java.util.Objects.hashCode(buildOptions)
        }

        override fun toString(): String {
            return "BuildConfigurationKeyValue.Key{buildOptions=" + buildOptions.checksum() + "}"
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.config.BuildConfigurationKeyValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            fun create(buildOptions: BuildOptions): Key {
                return com.google.devtools.build.lib.skyframe.config.BuildConfigurationKeyValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.config.BuildConfigurationKeyValue.Key(buildOptions)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.config.BuildConfigurationKeyValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    private val buildConfigurationKey: BuildConfigurationKey

    init {
        this.buildConfigurationKey = buildConfigurationKey
    }

    fun buildConfigurationKey(): BuildConfigurationKey {
        return buildConfigurationKey
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is BuildConfigurationKeyValue) {
            return false
        }
        return this.buildConfigurationKey == obj.buildConfigurationKey
    }

    override fun hashCode(): Int {
        return java.util.Objects.hashCode(buildConfigurationKey)
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("buildConfigurationKey", buildConfigurationKey)
            .toString()
    }

    companion object {
        fun create(buildConfigurationKey: BuildConfigurationKey): BuildConfigurationKeyValue {
            return BuildConfigurationKeyValue(buildConfigurationKey)
        }
    }
}
