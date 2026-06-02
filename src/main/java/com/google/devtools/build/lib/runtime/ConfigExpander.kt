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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.runtime.BlazeOptionHandler
import com.google.devtools.build.lib.runtime.CommonCommandOptions
import com.google.devtools.build.lib.runtime.RcChunkOfArgs
import java.util.HashSet
import java.util.LinkedHashSet

/** Encapsulates logic for performing --config option expansion.  */
internal object ConfigExpander {
    private val platformName: String?
        get() {
            when (com.google.devtools.build.lib.util.OS.getCurrent()) {
                com.google.devtools.build.lib.util.OS.LINUX -> return "linux"
                com.google.devtools.build.lib.util.OS.DARWIN -> return "macos"
                com.google.devtools.build.lib.util.OS.WINDOWS -> return "windows"
                com.google.devtools.build.lib.util.OS.FREEBSD -> return "freebsd"
                com.google.devtools.build.lib.util.OS.OPENBSD -> return "openbsd"
                else -> return com.google.devtools.build.lib.util.OS.getCurrent().getCanonicalName()
            }
        }

    /**
     * If --enable_platform_specific_config is true and the corresponding config definition exists, we
     * should enable the platform specific config.
     */
    private fun shouldEnablePlatformSpecificConfig(
        enablePlatformSpecificConfigDescription: com.google.devtools.common.options.OptionValueDescription?,
        commandToRcArgs: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs>,
        commandsToParse: MutableList<String>
    ): Boolean {
        if (enablePlatformSpecificConfigDescription == null
            || !enablePlatformSpecificConfigDescription.getValue() as Boolean
        ) {
            return false
        }

        for (commandName in commandsToParse) {
            val defaultConfigDef = commandName + ":" + platformName
            if (commandToRcArgs.containsKey(defaultConfigDef)) {
                return true
            }
        }
        return false
    }

    /**
     * Expands --config options present in the requested commands using the options configuration
     * provided in commandToRcArgs.
     * 
     * @param eventHandler collects any warnings encountered.
     * @param rcFileNotesConsumer collects any informational messages encountered.
     * @param optionsParser will parse the expanded --config representations.
     * @throws OptionsParsingException if a fatal problem with the configuration is encountered.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun expandConfigOptions(
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        commandToRcArgs: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs>,
        currentCommand: String?,
        commandsToParse: MutableList<String>,
        rcFileNotesConsumer: java.util.function.Consumer<String?>,
        optionsParser: com.google.devtools.common.options.OptionsParser,
        fallbackData: com.google.devtools.common.options.OpaqueOptionsData?
    ) {
        val configValueDescription: com.google.devtools.common.options.OptionValueDescription? =
            optionsParser.getOptionValueDescription("config")
        if (configValueDescription != null && configValueDescription.getCanonicalInstances() != null) {
            // Find the base set of configs. This does not include the config options that might be
            // recursively included.
            val configInstances: com.google.common.collect.ImmutableList<com.google.devtools.common.options.ParsedOptionDescription> =
                com.google.common.collect.ImmutableList.copyOf<com.google.devtools.common.options.ParsedOptionDescription?>(
                    configValueDescription.getCanonicalInstances()
                )

            // Expand the configs that are mentioned in the input. Flatten these expansions before parsing
            // them, to preserve order.
            for (configInstance in configInstances) {
                val configValueToExpand = configInstance.getConvertedValue() as String?
                val expansion: MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?> =
                    getExpansion(
                        eventHandler,
                        commandToRcArgs,
                        commandsToParse,
                        configValueToExpand,
                        rcFileNotesConsumer,
                        fallbackData
                    )
                val ignoredArgs: com.google.common.collect.ImmutableList<String?> =
                    optionsParser.parseArgsAsExpansionOfOption(
                        configInstance,
                        java.lang.String.format("expanded from --config=%s", configValueToExpand),
                        expansion
                    )
                if (!ignoredArgs.isEmpty()) {
                    rcFileNotesConsumer.accept(
                        java.lang.String.format(
                            "Ignored as unsupported by '%s': %s",
                            currentCommand, com.google.common.base.Joiner.on(' ').join(ignoredArgs)
                        )
                    )
                }
            }
        }

        val enablePlatformSpecificConfigDescription: com.google.devtools.common.options.OptionValueDescription? =
            optionsParser.getOptionValueDescription("enable_platform_specific_config")
        if (shouldEnablePlatformSpecificConfig(
                enablePlatformSpecificConfigDescription, commandToRcArgs, commandsToParse
            )
        ) {
            val expansion: MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?> =
                getExpansion(
                    eventHandler,
                    commandToRcArgs,
                    commandsToParse,
                    platformName,
                    rcFileNotesConsumer,
                    fallbackData
                )
            val optionToExpand: com.google.devtools.common.options.ParsedOptionDescription? =
                com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.common.options.ParsedOptionDescription?>(
                    enablePlatformSpecificConfigDescription.getCanonicalInstances()
                )
            val ignoredArgs: com.google.common.collect.ImmutableList<String?> =
                optionsParser.parseArgsAsExpansionOfOption(
                    optionToExpand, "enabled by --enable_platform_specific_config", expansion
                )
            if (!ignoredArgs.isEmpty()) {
                rcFileNotesConsumer.accept(
                    java.lang.String.format(
                        "Ignored as unsupported by '%s': %s",
                        currentCommand, com.google.common.base.Joiner.on(' ').join(ignoredArgs)
                    )
                )
            }
        }

        // At this point, we've expanded everything, identify duplicates, if any, to warn about
        // re-application.
        val configs: MutableList<String?> =
            optionsParser.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java).getConfigs()
        val configSet: MutableSet<String?> = HashSet<String?>()
        val duplicateConfigs: LinkedHashSet<String?> = LinkedHashSet<String?>()
        for (configValue in configs) {
            if (!configSet.add(configValue)) {
                duplicateConfigs.add(configValue)
            }
        }
        if (!duplicateConfigs.isEmpty()) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    java.lang.String.format(
                        "The following configs were expanded more than once: %s. For repeatable flags, "
                                + "repeats are counted twice and may lead to unexpected behavior.",
                        duplicateConfigs
                    )
                )
            )
        }
    }

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    private fun getExpansion(
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        commandToRcArgs: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs>,
        commandsToParse: MutableList<String>,
        configToExpand: String?,
        rcFileNotesConsumer: java.util.function.Consumer<String?>,
        fallbackData: com.google.devtools.common.options.OpaqueOptionsData?
    ): MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?> {
        val configAncestorSet: LinkedHashSet<String?> = LinkedHashSet<String?>()
        configAncestorSet.add(configToExpand)
        val longestChain: MutableList<String?> = java.util.ArrayList<String?>()
        val finalExpansion: MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?> =
            getExpansion(
                commandToRcArgs,
                commandsToParse,
                configAncestorSet,
                configToExpand,
                longestChain,
                rcFileNotesConsumer,
                fallbackData
            )

        // In order to prevent warning about a long chain of 13 configs at the 10, 11, 12, and 13
        // point, we identify the longest chain for this 'high-level' --config found and only warn
        // about it once. This may mean we missed a fork where each branch was independently long
        // enough to warn, but the single warning should convey the message reasonably.
        if (longestChain.size() >= 10) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    java.lang.String.format(
                        "There is a recursive chain of configs %s configs long: %s. This seems "
                                + "excessive, and might be hiding errors.",
                        longestChain.size(), longestChain
                    )
                )
            )
        }
        return finalExpansion
    }

    /**
     * @param configAncestorSet is the chain of configs that have led to this one getting expanded.
     * This should only contain the configs that expanded, recursively, to this one, and should
     * not contain "siblings," as it is used to detect cycles. `build:foo --config=bar`,
     * `build:bar --config=foo`, is a cycle, detected because this list will be [foo, bar]
     * when we find another 'foo' to expand. However, `build:foo --config=bar`, `build:foo --config=bar` is not a cycle just because bar is expanded twice, and the 1st bar
     * should not be in the parents list of the second bar.
     * @param longestChain will be populated with the longest inheritance chain of configs.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    private fun getExpansion(
        commandToRcArgs: com.google.common.collect.ListMultimap<String?, RcChunkOfArgs>,
        commandsToParse: MutableList<String>,
        configAncestorSet: LinkedHashSet<String?>,
        configToExpand: String?,
        longestChain: MutableList<String?>,
        rcFileNotesConsumer: java.util.function.Consumer<String?>,
        fallbackData: com.google.devtools.common.options.OpaqueOptionsData?
    ): MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?> {
        val expansion: MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?> =
            java.util.ArrayList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?>()
        var foundDefinition = false
        // The expansion order of rc files is first by command priority, and then in the order the
        // rc files were read, respecting import statement placement.
        for (commandToParse in commandsToParse) {
            val configDef = commandToParse + ":" + configToExpand
            for (rcArgs in commandToRcArgs.get(configDef)) {
                foundDefinition = true
                rcFileNotesConsumer.accept(
                    java.lang.String.format(
                        "Found applicable config definition %s in file %s: %s",
                        configDef, rcArgs.getRcFile(), java.lang.String.join(" ", rcArgs.getArgs())
                    )
                )

                // For each arg in the rcARgs chunk, we first check if it is a config, and if so, expand
                // it in place. We avoid cycles by tracking the parents of this config.
                for (arg in rcArgs.getArgs()) {
                    expansion.add(
                        com.google.devtools.common.options.OptionsParser.ArgAndFallbackData(
                            arg,
                            if (commandToParse == BlazeOptionHandler.Companion.COMMON_PSEUDO_COMMAND)
                                fallbackData
                            else
                                null
                        )
                    )
                    if (arg.length() >= 8 && arg.substring(0, 8) == "--config") {
                        // We have a config. Because we don't want to worry about formatting,
                        // we will only accept --config=value, and will not accept value on a following line.
                        val charOfConfigValue: Int = arg.indexOf('='.code)
                        if (charOfConfigValue < 0) {
                            throw com.google.devtools.common.options.OptionsParsingException(
                                java.lang.String.format(
                                    ("In file %s, the definition of config %s expands to another config "
                                            + "that either has no value or is not in the form --config=value. For "
                                            + "recursive config definitions, please do not provide the value in a "
                                            + "separate token, such as in the form '--config value'."),
                                    rcArgs.getRcFile(), configToExpand
                                )
                            )
                        }
                        val newConfigValue: String = arg.substring(charOfConfigValue + 1)
                        val extendedConfigAncestorSet: LinkedHashSet<String?> =
                            LinkedHashSet<String?>(configAncestorSet)
                        if (!extendedConfigAncestorSet.add(newConfigValue)) {
                            throw com.google.devtools.common.options.OptionsParsingException(
                                java.lang.String.format(
                                    "Config expansion has a cycle: config value %s expands to itself, "
                                            + "see inheritance chain %s",
                                    newConfigValue, extendedConfigAncestorSet
                                )
                            )
                        }
                        if (extendedConfigAncestorSet.size() > longestChain.size()) {
                            longestChain.clear()
                            longestChain.addAll(extendedConfigAncestorSet)
                        }

                        expansion.addAll(
                            getExpansion(
                                commandToRcArgs,
                                commandsToParse,
                                extendedConfigAncestorSet,
                                newConfigValue,
                                longestChain,
                                rcFileNotesConsumer,
                                fallbackData
                            )
                        )
                    }
                }
            }
        }

        if (!foundDefinition) {
            throw com.google.devtools.common.options.OptionsParsingException(
                "Config value '" + configToExpand + "' is not defined in any .rc file"
            )
        }
        return expansion
    }
}
