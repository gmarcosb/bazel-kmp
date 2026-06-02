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

import com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE

/** Handles the 'config' command on the Blaze command line.  */
@Command(
    name = "config",
    buildPhase = NONE,
    inheritsOptionsFrom = [BuildCommand::class],
    options = [ConfigOptions::class],
    usesConfigurationOptions = true,
    shortDescription = "Displays details of configurations.",
    allowResidue = true,
    completion = "string",
    hidden = true,
    help = "resource:config.txt"
)
class ConfigCommand : BlazeCommand {
    /** Defines the types of output this command can produce.  */
    enum class OutputType {
        TEXT,
        JSON
    }

    /** Options for the "config" command.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class ConfigOptions : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "dump_all",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            help = "If set, dump all known configurations instead of just the ids."
        )
        abstract val dumpAll: Boolean

        /** Converter for --output.  */
        class OutputTypeConverter : com.google.devtools.common.options.EnumConverter<OutputType?>(
            com.google.devtools.build.lib.runtime.commands.ConfigCommand.OutputType::class.java,
            "output type"
        )

        @get:com.google.devtools.common.options.Option(
            name = "output",
            converter = OutputTypeConverter::class,
            defaultValue = "text",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            help = "Formats the output of displayed results. Can be one of: 'text', 'json'. "
        )
        abstract val outputType: OutputType?
    }

    /**
     * Data structure defining the difference between two [BuildConfigurationValue]s from the
     * point of this command's output.
     * 
     * 
     * See [ConfigurationForOutput] for further details.
     */
    class ConfigurationDiffForOutput internal constructor(
        val configHash1: String,
        val configHash2: String,
        val fragmentsDiff: MutableList<FragmentDiffForOutput?>
    ) {
        override fun equals(o: Any?): Boolean {
            if (o is ConfigurationDiffForOutput) {
                return o.configHash1 == configHash1
                        && o.configHash2 == configHash2
                        && o.fragmentsDiff == fragmentsDiff
            }
            return false
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(configHash1, configHash2, fragmentsDiff)
        }
    }

    /**
     * Data structure defining the difference between two [BuildConfigurationValue]s for a given
     * [FragmentOptions]from the point of this command's output.
     * 
     * 
     * See [ConfigurationForOutput] for further details.
     */
    class FragmentDiffForOutput internal constructor(
        val name: String,
        optionsDiff: MutableMap<String?, com.google.devtools.build.lib.util.Pair<String?, String?>?>
    ) {
        val optionsDiff: MutableMap<String?, com.google.devtools.build.lib.util.Pair<String?, String?>?>

        init {
            this.optionsDiff = optionsDiff
        }

        override fun equals(o: Any?): Boolean {
            if (o is FragmentDiffForOutput) {
                return o.name == name && o.optionsDiff == optionsDiff
            }
            return false
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(name, optionsDiff)
        }
    }

    /**
     * Main entry point into the `blaze config` command.
     * 
     * 
     * Its purpose is to parse all options, figure out what variation of the command that implies,
     * run the right logic, and return the right exit code.
     */
    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        val configurations: com.google.common.collect.ImmutableSortedMap<BuildConfigurationKey?, BuildConfigurationValue?> =
            findConfigurations(env)
        if (configurations.isEmpty()) {
            val message =
                ("No configurations found. This can happen if the 'config' subcommand is used after "
                        + "files, including their metadata, have changed since the last invocation of "
                        + "another subcommand. Try running a 'build' or 'cquery' directly followed by "
                        + "'config'.")
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return createFailureResult(message, Code.CONFIGURATION_NOT_FOUND)
        }

        PrintWriter(
            OutputStreamWriter(env.getReporter().getOutErr().getOutputStream(), java.nio.charset.StandardCharsets.UTF_8)
        ).use { writer ->
            val configCommandOptions: ConfigOptions? = options.getOptions<ConfigOptions?>(ConfigOptions::class.java)
            val outputFormatter: ConfigCommandOutputFormatter =
                if (configCommandOptions!!.outputType == com.google.devtools.build.lib.runtime.commands.ConfigCommand.OutputType.TEXT)
                    com.google.devtools.build.lib.runtime.commands.ConfigCommandOutputFormatter.TextOutputFormatter(
                        writer
                    )
                else
                    com.google.devtools.build.lib.runtime.commands.ConfigCommandOutputFormatter.JsonOutputFormatter(
                        writer
                    )
            val fragmentDefs: com.google.common.collect.ImmutableSortedMap<java.lang.Class<out Fragment?>?, com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>?> =
                getFragmentDefs(env.getRuntime().getRuleClassProvider().getFragmentRegistry())
            if (options.getResidue().isEmpty()) {
                if (configCommandOptions.dumpAll) {
                    return reportAllConfigurations(outputFormatter, forOutput(configurations, fragmentDefs))
                } else {
                    return reportConfigurationIds(outputFormatter, forOutput(configurations, fragmentDefs))
                }
            } else if (options.getResidue().size == 1) {
                val configHash: String? = options.getResidue().get(0)
                return reportSingleConfiguration(
                    outputFormatter, env, forOutput(configurations, fragmentDefs), configHash
                )
            } else if (options.getResidue().size == 2) {
                val configHash1: String = options.getResidue().get(0)
                val configHash2: String = options.getResidue().get(1)
                return reportConfigurationDiff(
                    forOutput(configurations, fragmentDefs),
                    configHash1,
                    configHash2,
                    outputFormatter,
                    env
                )
            } else {
                val message = "Too many config ids."
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                return createFailureResult(message, Code.TOO_MANY_CONFIG_IDS)
            }
        }
    }

    companion object {
        /**
         * Returns all [BuildConfigurationValue]s in Skyframe as a map from their [ ] to instance.
         */
        private fun findConfigurations(env: CommandEnvironment): com.google.common.collect.ImmutableSortedMap<BuildConfigurationKey?, BuildConfigurationValue?> {
            val evaluator: MemoizingEvaluator =
                env.getRuntime().getWorkspace().getSkyframeExecutor().getEvaluator()
            return evaluator.getDoneValues().entries.stream()
                .filter { e: MutableMap.MutableEntry<SkyKey?, SkyValue?>? -> SkyFunctions.BUILD_CONFIGURATION == e!!.key.functionName() }
                .collect(
                    com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap<Any?, BuildConfigurationKey?, Any?>(
                        java.util.Comparator.comparing<Any?, Any?>(java.util.function.Function { e: Any? ->
                            e.getOptions().checksum()
                        }),
                        java.util.function.Function { e: Any? -> e.getKey() as BuildConfigurationKey? },
                        java.util.function.Function { e: Any? -> e.getValue() as BuildConfigurationValue? })
                )
        }

        /**
         * Returns the [Fragment]s and the [FragmentOptions] they require from Blaze's
         * runtime.
         * 
         * 
         * These are the fragments that Blaze "knows about", not necessarily the fragments in a [ ]. Trimming, in particular, strips fragments out of actual
         * configurations. It's safe to assume untrimmed configuration have all fragments listed here.
         */
        private fun getFragmentDefs(fragmentRegistry: FragmentRegistry): com.google.common.collect.ImmutableSortedMap<java.lang.Class<out Fragment?>?, com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>?> {
            return fragmentRegistry.getAllFragments().stream()
                .collect(
                    com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap<T?, K?, V?>(
                        FragmentClassSet.LEXICAL_FRAGMENT_SORTER,
                        java.util.function.Function { fragment: T? -> fragment },
                        java.util.function.Function { fragment: T? ->
                            com.google.common.collect.ImmutableSortedSet.copyOf(
                                java.util.Comparator.comparing<T?, U?>(java.util.function.Function { obj: T? -> obj.getName() }),
                                Fragment.requiredOptions(fragment)
                            )
                        })
                )
        }

        /**
         * Converts [.findConfigurations]'s output into a list of [ConfigurationForOutput]
         * instances.
         */
        private fun forOutput(
            asSkyKeyMap: com.google.common.collect.ImmutableSortedMap<BuildConfigurationKey?, BuildConfigurationValue?>,
            fragmentDefs: com.google.common.collect.ImmutableSortedMap<java.lang.Class<out Fragment?>?, com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>?>?
        ): com.google.common.collect.ImmutableSortedSet<ConfigurationForOutput?> {
            val ans: com.google.common.collect.ImmutableSortedSet.Builder<ConfigurationForOutput?> =
                com.google.common.collect.ImmutableSortedSet.orderedBy<ConfigurationForOutput?>(
                    java.util.Comparator.comparing<ConfigurationForOutput?, U?>(
                        java.util.function.Function { e: ConfigurationForOutput? -> e.configHash })
                )
            for (entry in asSkyKeyMap.entries) {
                val key: BuildConfigurationKey? = entry.key
                val config: BuildConfigurationValue = entry.value
                ans.add(
                    ConfigurationForOutput.getConfigurationForOutput(
                        key, config.checksum(), config, fragmentDefs
                    )
                )
            }
            return ans.build()
        }

        /**
         * Returns the configuration matching a hash prefix.
         * 
         * @param configurations collection of configurations to search
         * @param configPrefix prefix or exact value of the matching configuration's hash
         * @throws InvalidConfigurationException if not exactly one configuration matches
         */
        @Throws(InvalidConfigurationException::class)
        private fun getConfiguration(
            configurations: MutableCollection<ConfigurationForOutput?>, configPrefix: String?
        ): ConfigurationForOutput {
            val matches: com.google.common.collect.ImmutableList<ConfigurationForOutput?> =
                configurations.stream()
                    .filter { config: ConfigurationForOutput? -> doesConfigMatch(config, configPrefix) }
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<ConfigurationForOutput?>())
            if (matches.isEmpty()) {
                throw InvalidConfigurationException(
                    String.format("No configuration found with ID prefix %s", configPrefix)
                )
            } else if (matches.size > 1) {
                throw InvalidConfigurationException(
                    String.format(
                        ("Configuration identifier '%s' is ambiguous.\n"
                                + "'%s' is a prefix of multiple configurations:\n %s\n\n"
                                + "Use a sufficient prefix to uniquely identify one configuration."),
                        configPrefix,
                        configPrefix,
                        matches.stream().map<Any?>(ConfigurationForOutput::getConfigHash)
                            .collect(Collectors.joining("\n "))
                    )
                )
            }
            return com.google.common.collect.Iterables.getOnlyElement<ConfigurationForOutput>(matches)
        }

        private fun doesConfigMatch(config: ConfigurationForOutput, configPrefix: String?): Boolean {
            return config.configHash.startsWith(configPrefix)
        }

        /**
         * Reports the result of `blaze config --dump_all` and returns the appropriate command
         * exit code.
         */
        private fun reportAllConfigurations(
            writer: ConfigCommandOutputFormatter,
            configurations: com.google.common.collect.ImmutableSortedSet<ConfigurationForOutput?>?
        ): BlazeCommandResult {
            writer.writeConfigurations(configurations)
            return BlazeCommandResult.success()
        }

        /**
         * Reports the result of `blaze config` and returns the appropriate command exit code.
         */
        private fun reportConfigurationIds(
            writer: ConfigCommandOutputFormatter,
            configurations: com.google.common.collect.ImmutableSortedSet<ConfigurationForOutput?>?
        ): BlazeCommandResult {
            writer.writeConfigurationIDs(configurations)
            return BlazeCommandResult.success()
        }

        /**
         * Reports the result of `blaze config <configHash></configHash>` and returns the appropriate
         * command exit code.
         */
        private fun reportSingleConfiguration(
            writer: ConfigCommandOutputFormatter,
            env: CommandEnvironment,
            allConfigurations: com.google.common.collect.ImmutableSortedSet<ConfigurationForOutput?>,
            configHash: String?
        ): BlazeCommandResult {
            env.getReporter().handle(
                com.google.devtools.build.lib.events.Event.info(
                    String.format(
                        "Displaying config with id %s",
                        configHash
                    )
                )
            )
            try {
                writer.writeConfiguration(getConfiguration(allConfigurations, configHash))
                return BlazeCommandResult.success()
            } catch (e: InvalidConfigurationException) {
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                return createFailureResult(e.getMessage(), Code.CONFIGURATION_NOT_FOUND)
            }
        }

        /**
         * Reports the result of `blaze config <configHash1> <configHash2></configHash2></configHash1>` and returns the
         * appropriate command exit code.
         */
        private fun reportConfigurationDiff(
            allConfigs: com.google.common.collect.ImmutableSortedSet<ConfigurationForOutput?>,
            configHash1: String,
            configHash2: String,
            writer: ConfigCommandOutputFormatter,
            env: CommandEnvironment
        ): BlazeCommandResult {
            env.getReporter()
                .handle(
                    com.google.devtools.build.lib.events.Event.info(
                        String.format(
                            "Displaying diff between configs" + " %s and" + " %s",
                            configHash1, configHash2
                        )
                    )
                )
            try {
                val config1: ConfigurationForOutput = getConfiguration(allConfigs, configHash1)
                val config2: ConfigurationForOutput = getConfiguration(allConfigs, configHash2)
                val diffs: com.google.common.collect.Table<String?, String?, com.google.devtools.build.lib.util.Pair<Any?, Any?>?> =
                    diffConfigurations(config1, config2)
                writer.writeConfigurationDiff(getConfigurationDiffForOutput(configHash1, configHash2, diffs))
                return BlazeCommandResult.success()
            } catch (e: InvalidConfigurationException) {
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.getMessage()))
                return createFailureResult(e.getMessage(), Code.CONFIGURATION_NOT_FOUND)
            }
        }

        private fun diffConfigurations(
            config1: ConfigurationForOutput, config2: ConfigurationForOutput
        ): com.google.common.collect.Table<String?, String?, com.google.devtools.build.lib.util.Pair<Any?, Any?>?> {
            val diffs: com.google.common.collect.Table<String?, String?, com.google.devtools.build.lib.util.Pair<Any?, Any?>?> =
                com.google.common.collect.HashBasedTable.create<String?, String?, com.google.devtools.build.lib.util.Pair<Any?, Any?>?>()

            for (fragmentName in com.google.common.collect.Sets.union<E?>(
                config1.fragmentOptionNames(),
                config2.fragmentOptionNames()
            )) {
                val options1: FragmentOptionsForOutput? = config1.fragment(fragmentName)
                val options2: FragmentOptionsForOutput? = config2.fragment(fragmentName)
                diffs.row(fragmentName).putAll(diffOptions(options1, options2))
            }
            return diffs
        }

        private fun diffOptions(
            options1: FragmentOptionsForOutput?, options2: FragmentOptionsForOutput?
        ): MutableMap<String?, com.google.devtools.build.lib.util.Pair<Any?, Any?>?> {
            val optionNames1: MutableSet<String?> =
                if (options1 == null) com.google.common.collect.ImmutableSet.of<String?>() else options1.optionNames()
            val optionNames2: MutableSet<String?> =
                if (options2 == null) com.google.common.collect.ImmutableSet.of<String?>() else options2.optionNames()
            val diffs: MutableMap<String?, com.google.devtools.build.lib.util.Pair<Any?, Any?>?> =
                HashMap<String?, com.google.devtools.build.lib.util.Pair<Any?, Any?>?>()

            for (optionName in com.google.common.collect.Sets.union<String?>(optionNames1, optionNames2)) {
                val value1: String? = if (options1 == null) null else options1.getOption(optionName)
                val value2: String? = if (options2 == null) null else options2.getOption(optionName)

                if (value1 != value2) {
                    diffs.put(optionName, com.google.devtools.build.lib.util.Pair.of<Any?, Any?>(value1, value2))
                }
            }

            return diffs
        }

        private fun getConfigurationDiffForOutput(
            configHash1: String,
            configHash2: String,
            diffs: com.google.common.collect.Table<String?, String?, com.google.devtools.build.lib.util.Pair<Any?, Any?>?>
        ): ConfigurationDiffForOutput {
            val fragmentDiffs: com.google.common.collect.ImmutableSortedSet.Builder<FragmentDiffForOutput?>
            TODO(
                """
                |Cannot convert element
                |With text:
                |ImmutableSortedSet.<FragmentDiffForOutput>orderedBy(<FragmentDiffForOutput, String>comparing(e -> e.name)
                """.trimMargin()
            )

            diffs
                .rowKeySet()
                .forEach(
                    java.util.function.Consumer { fragmentName: String? ->
                        val sortedOptionDiffs: com.google.common.collect.ImmutableSortedMap<String?, com.google.devtools.build.lib.util.Pair<String?, String?>?> =
                            diffs.row(fragmentName).entries.stream()
                                .collect()
                        TODO(
                            """
                |Cannot convert element
                |With text:
                |String, Pair<String, String>>toImmutableSortedMap(
                |                              Ordering.<String>natural(),
                |                              Map.Entry::getKey,
                |                              e -> toNullableStringPair(e.getValue()))
                """.trimMargin()
                        )
                        fragmentDiffs.add(FragmentDiffForOutput(fragmentName!!, sortedOptionDiffs))
                    })
            return ConfigurationDiffForOutput(configHash1, configHash2, fragmentDiffs.build().asList())
        }

        private fun toNullableStringPair(pair: com.google.devtools.build.lib.util.Pair<Any?, Any?>): com.google.devtools.build.lib.util.Pair<String?, String?> {
            return com.google.devtools.build.lib.util.Pair.of<String?, String?>(
                pair.first.toString(),
                pair.second.toString()
            )
        }

        private fun createFailureResult(message: String?, detailedCode: Code?): BlazeCommandResult {
            return BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setConfigCommand(FailureDetails.ConfigCommand.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
