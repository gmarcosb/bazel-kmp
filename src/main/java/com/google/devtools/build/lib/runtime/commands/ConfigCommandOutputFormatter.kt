// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.analysis.config.output.ConfigurationForOutput

/**
 * Formats output for [ConfigCommand].
 * 
 * 
 * The basic contract is @link ConfigCommand} makes all important structural decisions: what data
 * gets reported, how different pieces of data relate to each other, and how data is ordered. A
 * [ConfigCommandOutputFormatter] then outputs this in a format-appropriate way.
 */
internal abstract class ConfigCommandOutputFormatter(writer: PrintWriter) {
    protected val writer: PrintWriter

    /** Constructs a formatter that writes output to the given [PrintWriter].  */
    init {
        this.writer = writer
    }

    /** Outputs a list of configuration hash IDs.  */
    abstract fun writeConfigurationIDs(configurations: Iterable<ConfigurationForOutput?>?)

    /** Outputs a single configuration.  */
    abstract fun writeConfiguration(configuration: ConfigurationForOutput?)

    /** Outputs a series of configurations.  */
    abstract fun writeConfigurations(configurations: Iterable<ConfigurationForOutput?>?)

    /** Outputs the diff between two configurations  */
    abstract fun writeConfigurationDiff(diff: ConfigurationDiffForOutput?)

    /** A [ConfigCommandOutputFormatter] that outputs plan user-readable text.  */
    internal class TextOutputFormatter(writer: PrintWriter) : ConfigCommandOutputFormatter(writer) {
        override fun writeConfigurationIDs(configurations: Iterable<ConfigurationForOutput?>) {
            writer.println("Available configurations:")
            configurations.forEach(
                java.util.function.Consumer { config: ConfigurationForOutput? ->
                    writer.printf(
                        "%s %s%s%n",
                        config.configHash,
                        config.mnemonic,
                        com.google.devtools.build.lib.runtime.commands.ConfigCommandOutputFormatter.TextOutputFormatter.Companion.getSuffix(
                            config
                        )
                    )
                })
        }

        override fun writeConfiguration(configuration: ConfigurationForOutput) {
            writer.println("BuildConfigurationValue " + configuration.configHash + ":")
            writer.println("Skyframe Key: " + configuration.skyKey)

            val fragments: java.lang.StringBuilder = java.lang.StringBuilder()
            for (fragment in configuration.getFragments()) {
                fragments
                    .append(fragment.name)
                    .append(": [")
                    .append(java.lang.String.join(",", fragment.fragmentOptions))
                    .append("], ")
            }

            writer.println("Fragments: " + fragments)
            for (fragment in configuration.getFragmentOptions()) {
                writer.println("FragmentOptions " + fragment.name + " {")
                for (optionSetting in fragment.getOptions().entrySet()) {
                    writer.printf("  %s: %s\n", optionSetting.key, optionSetting.value)
                }
                writer.println("}")
            }
        }

        override fun writeConfigurations(configurations: Iterable<ConfigurationForOutput>) {
            for (config in configurations) {
                writeConfiguration(config)
            }
        }

        override fun writeConfigurationDiff(diff: ConfigurationDiffForOutput) {
            writer.printf(
                "Displaying diff between configs %s and %s\n", diff.configHash1, diff.configHash2
            )
            for (fragmentDiff in diff.fragmentsDiff) {
                writer.println("FragmentOptions " + fragmentDiff.name + " {")
                for (optionDiff in fragmentDiff.optionsDiff.entries) {
                    writer.printf(
                        "  %s: %s, %s\n",
                        optionDiff.key, optionDiff.value.first, optionDiff.value.second
                    )
                }
                writer.println("}")
            }
        }

        companion object {
            private fun getSuffix(config: ConfigurationForOutput): String {
                if (config.isExec) {
                    return " (exec)"
                } else if (!config.hasTestConfig()) {
                    return " (test-trimmed)"
                }
                return ""
            }
        }
    }

    /** A [ConfigCommandOutputFormatter] that outputs structured JSON.  */
    internal class JsonOutputFormatter(writer: PrintWriter) : ConfigCommandOutputFormatter(writer) {
        private val gson: Gson

        init {
            this.gson = Gson()
        }

        override fun writeConfigurationIDs(configurations: Iterable<ConfigurationForOutput?>) {
            val configurationIDs: Iterable<String?> =
                com.google.common.collect.Streams.stream<ConfigurationForOutput?>(configurations)
                    .map<Any?> { config: ConfigurationForOutput? -> config.configHash }
                    .collect(Collectors.toList())
            writer.println(
                gson.toJson(
                    com.google.common.collect.ImmutableMap.of<String?, Iterable<String?>?>(
                        "configuration-IDs",
                        configurationIDs
                    )
                )
            )
        }

        override fun writeConfiguration(configuration: ConfigurationForOutput?) {
            writer.println(gson.toJson(configuration))
        }

        override fun writeConfigurations(configurations: Iterable<ConfigurationForOutput?>?) {
            writer.println(gson.toJson(configurations))
        }

        override fun writeConfigurationDiff(diff: ConfigurationDiffForOutput?) {
            writer.println(gson.toJson(diff))
        }
    }
}
