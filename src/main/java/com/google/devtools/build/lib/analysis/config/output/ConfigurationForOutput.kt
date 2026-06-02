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
package com.google.devtools.build.lib.analysis.config.output

import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue

/**
 * Data structure defining a [BuildConfigurationValue] for the purpose of returning user
 * output about the configuration.
 * 
 * 
 * Includes all data representing a "configuration" and defines their relative structure and list
 * order.
 * 
 * 
 * A [com.google.devtools.build.lib.runtime.commands.ConfigCommandOutputFormatter] uses
 * this to lightly format output from a logically consistent core structure.
 */
class ConfigurationForOutput(
  @kotlin.jvm.JvmField private val skyKey: String,
  @kotlin.jvm.JvmField private val configHash: String,
  @kotlin.jvm.JvmField private val mnemonic: String?,
  @kotlin.jvm.JvmField private val isExec: Boolean,
  fragments: MutableList<FragmentForOutput?>,
  fragmentOptions: MutableList<FragmentOptionsForOutput?>
) {
    private val fragments: MutableList<FragmentForOutput?>
    private val fragmentOptions: MutableList<FragmentOptionsForOutput?>

    init {
        this.fragments = fragments
        this.fragmentOptions = fragmentOptions
    }

    fun getSkyKey(): String {
        return skyKey
    }

    fun getConfigHash(): String {
        return configHash
    }

    fun getMnemonic(): String? {
        return mnemonic
    }

    fun isExec(): Boolean {
        return isExec
    }

    fun hasTestConfig(): Boolean {
        return fragmentOptions.stream()
            .map<String?> { obj: FragmentOptionsForOutput? -> obj.getName() }
            .anyMatch { name: String? -> name.contains("TestConfiguration") }
    }

    fun getFragments(): MutableList<FragmentForOutput?> {
        return fragments
    }

    /**
     * The union of [FragmentOptionsForOutput] used by the Fragments associated with this
     * configuration, sorted by FragmentOptionsForOutput name.
     */
    fun getFragmentOptions(): MutableList<FragmentOptionsForOutput?> {
        return fragmentOptions
    }

    fun fragment(fragmentName: String?): FragmentOptionsForOutput? {
        return this.fragmentOptions.stream()
            .filter { fo: FragmentOptionsForOutput? -> fo.getName() == fragmentName }
            .findFirst()
            .orElse(null)
    }

    fun fragmentOptionNames(): SortedSet<String?> {
        return this.fragmentOptions.stream()
            .map<String?> { obj: FragmentOptionsForOutput? -> obj.getName() }
            .collect(com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet<String?>(com.google.common.collect.Ordering.natural<String?>()))
    }

    override fun equals(o: Any?): Boolean {
        if (o is ConfigurationForOutput) {
            return o.skyKey == skyKey
                    && o.configHash == configHash
                    && o.fragments == fragments
                    && o.fragmentOptions == fragmentOptions
        }
        return false
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(skyKey, configHash, fragments, fragmentOptions)
    }

    /**
     * Starlark options don't have configuration fragments. This is just to keep their output
     * consistent with native options, i.e. to include "user-defined" section in the output list.
     */
    @com.google.devtools.common.options.OptionsClass
    internal object UserDefinedFragment : FragmentOptions() {
        const val DESCRIPTIVE_NAME: String =
            "user-defined" // Intentionally empty: we read the actual options directly from BuildOptions.
    }

    companion object {
        /** Constructs a [ConfigurationForOutput] from the given [BuildConfigurationValue].  */
        fun getConfigurationForOutput(
            buildConfigurationValue: BuildConfigurationValue
        ): ConfigurationForOutput {
            val fragmentDefs: com.google.common.collect.ImmutableSortedMap<java.lang.Class<out Fragment?>?, com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>?> =
                buildConfigurationValue.getFragments().keySet().stream()
                    .collect(
                        com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap<T?, K?, V?>(
                            FragmentClassSet.Companion.LEXICAL_FRAGMENT_SORTER,
                            java.util.function.Function { fragment: T? -> fragment },
                            java.util.function.Function { fragment: T? ->
                                com.google.common.collect.ImmutableSortedSet.copyOf(
                                    java.util.Comparator.comparing<T?, U?>(java.util.function.Function { obj: T? -> obj.getName() }),
                                    Fragment.requiredOptions(fragment)
                                )
                            })
                    )

            return getConfigurationForOutput(
                buildConfigurationValue.getKey(),
                buildConfigurationValue.checksum(),
                buildConfigurationValue,
                fragmentDefs
            )
        }

        /** Constructs a [ConfigurationForOutput] from the given input data.  */
        fun getConfigurationForOutput(
            skyKey: BuildConfigurationKey,
            configHash: String,
            config: BuildConfigurationValue,
            fragmentDefs: com.google.common.collect.ImmutableSortedMap<java.lang.Class<out Fragment?>?, com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>?>
        ): ConfigurationForOutput {
            val fragments: com.google.common.collect.ImmutableSortedSet.Builder<FragmentForOutput?> =
                com.google.common.collect.ImmutableSortedSet.orderedBy<FragmentForOutput?>(
                    java.util.Comparator.comparing<FragmentForOutput?, String?>(
                        java.util.function.Function { e: FragmentForOutput? -> e.getName() })
                )
            for (entry in fragmentDefs.entries) {
                fragments.add(
                    FragmentForOutput(
                        entry.key.getName(),
                        entry.value.stream()
                            .map<String?> { obj: java.lang.Class<out FragmentOptions?>? -> obj.getName() }
                            .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())))
            }
            fragmentDefs.entries.stream()
                .filter { entry: MutableMap.MutableEntry<java.lang.Class<out Fragment?>?, com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>?>? ->
                    config.hasFragment(
                        entry!!.key
                    )
                }
                .forEach { entry: MutableMap.MutableEntry<java.lang.Class<out Fragment?>?, com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>?>? ->
                    fragments.add(
                        FragmentForOutput(
                            entry!!.key.getName(),
                            entry.value.stream()
                                .map<String?> { obj: java.lang.Class<out FragmentOptions?>? -> obj.getName() }
                                .collect(Collectors.toList())))
                }

            val fragmentOptions: com.google.common.collect.ImmutableSortedSet.Builder<FragmentOptionsForOutput?> =
                com.google.common.collect.ImmutableSortedSet.orderedBy<FragmentOptionsForOutput?>(
                    java.util.Comparator.comparing<FragmentOptionsForOutput?, String?>(
                        java.util.function.Function { e: FragmentOptionsForOutput? -> e.getName() })
                )
            config.getOptions().getFragmentClasses().stream()
                .map({ optionsClass -> config.getOptions().get(optionsClass) })
                .forEach(
                    { fragmentOptionsInstance ->
                        fragmentOptions.add(
                            FragmentOptionsForOutput(
                                fragmentOptionsInstance.getOptionsClass().getName(),
                                getOrderedNativeOptions(fragmentOptionsInstance)
                            )
                        )
                    })
            fragmentOptions.add(
                FragmentOptionsForOutput(
                    UserDefinedFragment.DESCRIPTIVE_NAME, getOrderedUserDefinedOptions(config)
                )
            )

            return ConfigurationForOutput(
                skyKey.toString(),
                configHash,
                config.getMnemonic(),
                config.isExecConfiguration(),
                fragments.build().asList(),
                fragmentOptions.build().asList()
            )
        }

        /**
         * Returns a [FragmentOptions]'s native option settings in canonical order.
         * 
         * 
         * While actual option values are objects, we serialize them to strings to prevent command
         * output from interpreting them more deeply than we want for simple "name=value" output.
         */
        private fun getOrderedNativeOptions(
            options: FragmentOptions
        ): com.google.common.collect.ImmutableSortedMap<String?, String?> {
            return options.asMap().entries.stream() // While technically part of CoreOptions, --define is practically a user-definable flag so
                // we include it in the user-defined fragment for clarity. See getOrderedUserDefinedOptions.
                .filter { entry: MutableMap.MutableEntry<String?, Any?>? ->
                    !(options.getOptionsClass() == CoreOptions::class.java
                            && entry!!.key == "define")
                }
                .collect(
                    TODO("Cannot convert element")
                ) < java.util.Map.Entry < String
            TODO(
                """
                |Cannot convert element
                |With text:
                |Object>, String, String>toImmutableSortedMap(
                |                Ordering.<String>natural(), Map.Entry::getKey, e -> String.valueOf(e.getValue()))
                """.trimMargin()
            )
        }

        /**
         * Returns a configuration's user-definable settings in canonical order.
         * 
         * 
         * While actual option values are objects, we serialize them to strings to prevent command
         * output from interpreting them more deeply than we want for simple "name=value" output.
         */
        private fun getOrderedUserDefinedOptions(
            config: BuildConfigurationValue
        ): com.google.common.collect.ImmutableSortedMap<String?, String?> {
            val ans: com.google.common.collect.ImmutableSortedMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableSortedMap.naturalOrder<String?, String?>()

            // Starlark-defined options:
            for (entry in config.getOptions().getStarlarkOptions().entrySet()) {
                ans.put(entry.key.toString(), entry.value.toString())
            }

            // --define:
            for (entry in config
                .getOptions()
                .get(CoreOptions::class.java)
                .getNormalizedCommandLineBuildVariables()
                .entrySet()) {
                ans.put("--define:" + entry.key, com.google.common.base.Verify.verifyNotNull<String?>(entry.value))
            }
            return ans.buildOrThrow()
        }
    }
}
