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
package com.google.devtools.build.lib.analysis.config.transitions

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * This contains the baseline options to compare against when constructing output paths.
 * 
 * 
 * When constructing the output mnemonic as part of making a [BuildConfigurationValue] and
 * the selected naming scheme is to diff against a baseline, this function returns the baseline to
 * use for that comparison. Differences in options between the given option and this baseline will
 * then be used to append a deconflicting ST-hash to the output mnemonic.
 * 
 * 
 * The afterExecTransition option in the key will apply the exec transition to the usual
 * baseline. It is expected that this is set whenever the given options have isExec set (and thus an
 * exec transition has already been applied to those options). The expectation here is that, as the
 * exec transition particularly sets many options, comparing against a post-exec baseline will yield
 * fewer differences. Note that some indicator must be added to the mnemonic (e.g. -exec-) in order
 * to deconflict for similar options where isExec is not set.
 * 
 * 
 * Similarly, the trimTestOptions option in the key will apply test trimming to the usual
 * baseline to reduce the number of differences for non-test targets.
 */
@com.google.errorprone.annotations.CheckReturnValue
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
@AutoCodec
class BaselineOptionsValue(toOptions: BuildOptions?) : SkyValue {
    /** [SkyKey] implementation used for [BaselineOptionsValue].  */
    @com.google.errorprone.annotations.CheckReturnValue
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @AutoCodec
    class Key(
      @kotlin.jvm.JvmField val afterExecTransition: Boolean,
      @kotlin.jvm.JvmField val trimTestOptions: Boolean,
      newPlatform: com.google.devtools.build.lib.cmdline.Label?
    ) : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.BASELINE_OPTIONS
        }

        override fun toString(): String {
            return "BaselineOptionsValue.Key{afterExecTransition=%s, trimTestOptions=%s, newPlatform=%s}"
                .formatted(this.afterExecTransition, this.trimTestOptions, this.newPlatform)
        }

        val newPlatform: com.google.devtools.build.lib.cmdline.Label?

        init {
            this.newPlatform = newPlatform
        }

        companion object {
            fun create(
                afterExecTransition: Boolean,
                trimTestOptions: Boolean,
                newPlatform: com.google.devtools.build.lib.cmdline.Label?
            ): Key {
                return com.google.devtools.build.lib.analysis.config.transitions.BaselineOptionsValue.Key(
                    afterExecTransition,
                    trimTestOptions,
                    newPlatform
                )
            }
        }
    }

    val toOptions: BuildOptions?

    init {
        this.toOptions = toOptions
        java.util.Objects.requireNonNull<Any?>(toOptions, "toOptions")
    }

    companion object {
        fun create(toOptions: BuildOptions?): BaselineOptionsValue {
            return BaselineOptionsValue(toOptions)
        }

        fun key(
            afterExecTransition: Boolean,
            trimTestOptions: Boolean,
            newPlatform: com.google.devtools.build.lib.cmdline.Label?
        ): Key {
            return com.google.devtools.build.lib.analysis.config.transitions.BaselineOptionsValue.Key.Companion.create(
                afterExecTransition,
                trimTestOptions,
                newPlatform
            )
        }
    }
}
