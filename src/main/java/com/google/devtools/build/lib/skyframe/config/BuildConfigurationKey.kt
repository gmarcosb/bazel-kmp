// Copyright 2021 The Bazel Authors. All rights reserved.
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

/**
 * [SkyKey] for [com.google.devtools.build.lib.analysis.config.BuildConfigurationValue].
 */
@AutoCodec
class BuildConfigurationKey private constructor(options: BuildOptions?) : SkyKey {
    private val options: BuildOptions

    init {
        this.options = com.google.common.base.Preconditions.checkNotNull<BuildOptions>(options)
    }

    fun getOptions(): BuildOptions {
        return options
    }

    override fun functionName(): SkyFunctionName {
        return SkyFunctions.BUILD_CONFIGURATION
    }

    val optionsChecksum: String
        get() = options.checksum()

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is BuildConfigurationKey) {
            return false
        }
        return options.equals(o.options)
    }

    override fun hashCode(): Int {
        return options.hashCode()
    }

    override fun toString(): String {
        // This format is depended on by integration tests.
        return "BuildConfigurationKey[" + options.checksum() + "]"
    }

    val skyKeyInterner: SkyKeyInterner<BuildConfigurationKey?>
        get() = interner

    /** Enum pattern for avoiding cyclic class loading deadlocks.  */
    private enum class CodecHolder {
        INSTANCE;

        // it's immutable
        private val codec: DeferredObjectCodec<BuildConfigurationKey?>?

        init {
            val codecClass: java.lang.Class<*>
            try {
                codecClass =
                    java.lang.Class.forName(
                        "com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey_AutoCodec"
                    )
            } catch (e: java.lang.ClassNotFoundException) {
                // Okay, the codec class doesn't exist in the bootstrap jar file.
                this.codec = null
                return
            }
            try {
                val castCodec: DeferredObjectCodec<BuildConfigurationKey?>? =
                    codecClass.getDeclaredConstructor().newInstance() as DeferredObjectCodec<BuildConfigurationKey?>?
                this.codec = castCodec
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.IllegalStateException("couldn't instantiate BuildConfigurationKey_AutoCodec", e)
            }
        }
    }

    companion object {
        private val interner: SkyKeyInterner<BuildConfigurationKey?> = SkyKey.newInterner<BuildConfigurationKey?>()

        /**
         * Returns the key for a requested configuration.
         * 
         * @param options the [BuildOptions] object the [BuildOptions] should be rebuilt from
         */
        @AutoCodec.Instantiator
        fun create(options: BuildOptions?): BuildConfigurationKey {
            return interner.intern(BuildConfigurationKey(options))
        }

        fun codec(): DeferredObjectCodec<BuildConfigurationKey?>? {
            return CodecHolder.INSTANCE.codec
        }
    }
}
