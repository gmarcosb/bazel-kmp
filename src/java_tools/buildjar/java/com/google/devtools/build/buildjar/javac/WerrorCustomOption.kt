// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.buildjar.javac

import com.google.devtools.build.buildjar.javac.WerrorCustomOption
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import java.util.LinkedHashMap

/**
 * Logic for handling non-standard javac flag `-Werror:`, which allows failing the compilation
 * for individual xlint warnings.
 */
class WerrorCustomOption(werrors: com.google.common.collect.ImmutableMap<String?, Boolean?>) {
    private val werrors: com.google.common.collect.ImmutableMap<String?, Boolean?>

    init {
        this.werrors = werrors
    }

    /** Returns true if the given lint category should be promoted to an error.  */
    fun isEnabled(lintCategory: String?): Boolean {
        val all: Boolean = werrors.containsKey("all")
        return werrors.getOrDefault(lintCategory, all)
    }

    /** A builder for [WerrorCustomOption]s.  */
    internal class Builder(warningsAsErrorsDefault: com.google.common.collect.ImmutableList<String?>) {
        private val warningsAsErrorsDefault: com.google.common.collect.ImmutableList<String?>

        private val werrors: MutableMap<String?, Boolean?> = LinkedHashMap<String?, Boolean?>()

        init {
            this.warningsAsErrorsDefault = warningsAsErrorsDefault
            // initialize list of werrors with the ones we want on by default
            for (errorWarning in warningsAsErrorsDefault) {
                werrors.put(errorWarning, true)
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun all(): Builder {
            werrors.clear()
            werrors.put("all", true)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun process(flag: String): Builder {
            com.google.common.base.Preconditions.checkArgument(flag.startsWith(WERROR), flag)
            for (arg in com.google.common.base.Splitter.on(',').split(flag.substring(WERROR.length))) {
                // Warnings with a '+' or '-' have an implicit '+'.
                if (arg == "+all" || arg == "all") {
                    werrors.clear()
                    werrors.put("all", true)
                } else if (arg == "-all" || arg == "none") {
                    werrors.clear()
                    werrors.put("none", true)
                    for (errorWarning in warningsAsErrorsDefault) {
                        werrors.put(errorWarning, true)
                    }
                } else if (arg.startsWith("-")) {
                    val warning: String = arg.substring(1)
                    if (!warningsAsErrorsDefault.contains(warning)) {
                        werrors.put(warning, false)
                    }
                } else {
                    // '+' or raw warning category (implicit '+')
                    val warning: String = if (arg.startsWith("+")) arg.substring(1) else arg
                    werrors.put(warning, true)
                }
            }
            return this
        }

        fun build(): WerrorCustomOption {
            return WerrorCustomOption(com.google.common.collect.ImmutableMap.copyOf<String?, Boolean?>(werrors))
        }
    }

    /** Returns a normalized `-Werror:` flag.  */
    override fun toString(): String {
        if (this.werrors.isEmpty()) {
            return ""
        }
        val werrors: MutableMap<String?, Boolean?> = LinkedHashMap<String?, Boolean?>(this.werrors)
        val sb: java.lang.StringBuilder = java.lang.StringBuilder("-Werror:")
        if (werrors.containsKey("all")) {
            val b: Boolean = werrors.remove("all")!!
            sb.append(if (b) "" else "-").append("all,")
        }
        for (warning in werrors.keys) {
            val b: Boolean = werrors.get(warning)!!
            sb.append(if (b) "" else "-").append(warning).append(",")
        }
        // delete trailing ","
        sb.deleteCharAt(sb.length - 1)
        return sb.toString()
    }

    companion object {
        private const val WERROR = "-Werror:"

        fun create(arg: String): WerrorCustomOption {
            return com.google.devtools.build.buildjar.javac.WerrorCustomOption.Builder( /* warningsAsErrorsDefault= */
                com.google.common.collect.ImmutableList.of<String?>()
            )
                .process(arg)
                .build()
        }
    }
}
