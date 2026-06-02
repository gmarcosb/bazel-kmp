// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec

/** Components of the `--run_under` option.  */
interface RunUnder {
    /**
     * @return the whole value passed to --run_under option.
     */
    fun value(): String?

    /**
     * Returns everything except the first word (according to shell tokenization) passed to `--run_under`.
     */
    fun options(): com.google.common.collect.ImmutableList<String?>?

    /**
     * Represents a value of `--run_under` whose first word (according to shell tokenization)
     * starts with `"//"` or `"@"`. It is treated as a label referencing a target that
     * should be used as the `--run_under` executable.
     */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class LabelRunUnder(
        value: String?,
        options: com.google.common.collect.ImmutableList<String?>?,
        label: com.google.devtools.build.lib.cmdline.Label?
    ) : RunUnder {
        val value: String?
        val options: com.google.common.collect.ImmutableList<String?>?
        val label: com.google.devtools.build.lib.cmdline.Label?

        init {
            this.value = value
            this.options = options
            this.label = label
        }
    }

    /**
     * Represents a value of `--run_under` whose first word (according to shell tokenization)
     * does not start with `"//"` or `"@"`. It is treated as a shell command.
     */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class CommandRunUnder(
        value: String?,
        options: com.google.common.collect.ImmutableList<String?>?,
        command: String?
    ) : RunUnder {
        val value: String?
        val options: com.google.common.collect.ImmutableList<String?>?
        val command: String?

        init {
            this.value = value
            this.options = options
            this.command = command
        }
    }

    companion object {
        /**
         * Returns a new instance that only retains the information that is relevant for the analysis of
         * non-test targets.
         */
        @kotlin.jvm.JvmStatic
        fun trimForNonTestConfiguration(runUnder: RunUnder?): RunUnder? {
            return when (runUnder) {
                -> LabelRunUnder("", com.google.common.collect.ImmutableList.of<String?>(), labelRunUnder.label)
                null -> null
            }
        }
    }
}
