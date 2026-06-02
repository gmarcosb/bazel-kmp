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

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** Utility class for functionality related to Blaze commands.  */
object BlazeCommandUtils {
    /** Options classes used as startup options in Blaze core.  */
    private val DEFAULT_STARTUP_OPTIONS: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
        com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            BlazeServerStartupOptions::class.java,
            HostJvmStartupOptions::class.java
        )

    /** The set of option-classes that are common to all Blaze commands.  */
    private val COMMON_COMMAND_OPTIONS: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
        com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            UiOptions::class.java,
            CommonCommandOptions::class.java,
            KeepStateAfterBuildOption::class.java,
            ClientOptions::class.java,  // Starlark options aren't applicable to all commands, but making them a common option
            // allows users to put them in the common section of the bazelrc. See issue #3538.
            BuildLanguageOptions::class.java
        )

    fun getStartupOptions(
        suppliers: Iterable<com.google.devtools.build.lib.runtime.OptionsSupplier>
    ): com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        val options: MutableSet<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
            HashSet<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(DEFAULT_STARTUP_OPTIONS)
        for (supplier in suppliers) {
            com.google.common.collect.Iterables.addAll<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                options,
                supplier.getStartupOptions()
            )
        }

        return com.google.common.collect.ImmutableList.copyOf<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            options
        )
    }

    fun getCommonOptions(
        suppliers: Iterable<com.google.devtools.build.lib.runtime.OptionsSupplier>
    ): com.google.common.collect.ImmutableSet<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        val builder: com.google.common.collect.ImmutableSet.Builder<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
            com.google.common.collect.ImmutableSet.builder<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
        builder.addAll(COMMON_COMMAND_OPTIONS)
        for (supplier in suppliers) {
            builder.addAll(supplier.getCommonCommandOptions())
        }
        return builder.build()
    }

    /**
     * Returns the set of all options (including those inherited directly and transitively) for this
     * AbstractCommand's @Command annotation.
     * 
     * 
     * Why does metaprogramming always seem like such a bright idea in the beginning?
     */
    fun getOptions(
        clazz: java.lang.Class<out BlazeCommand?>,
        suppliers: Iterable<com.google.devtools.build.lib.runtime.OptionsSupplier>,
        ruleClassProvider: ConfiguredRuleClassProvider
    ): com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        val commandAnnotation: com.google.devtools.build.lib.runtime.Command =
            clazz.getAnnotation<com.google.devtools.build.lib.runtime.Command>(com.google.devtools.build.lib.runtime.Command::class.java)
        checkNotNull(commandAnnotation) { "@Command missing for " + clazz.getName() }

        val options: MutableSet<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
            HashSet<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(getCommonOptions(suppliers))
        Collections.addAll<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            options,
            *commandAnnotation.options
        )

        if (commandAnnotation.usesConfigurationOptions) {
            options.addAll(ruleClassProvider.getFragmentRegistry().getOptionsClasses())
        }

        for (supplier in suppliers) {
            com.google.common.collect.Iterables.addAll<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                options,
                supplier.getCommandOptions(commandAnnotation.name)
            )
        }

        for (base in commandAnnotation.inheritsOptionsFrom) {
            options.addAll(getOptions(base, suppliers, ruleClassProvider))
        }
        return com.google.common.collect.ImmutableList.copyOf<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            options
        )
    }

    /**
     * Returns the expansion of the specified help topic.
     * 
     * @param topic the name of the help topic; used in %{command} expansion.
     * @param help the text template of the help message. Certain %{x} variables will be expanded. A
     * prefix of "resource:" means use the .jar resource of that name.
     * @param helpVerbosity a tri-state verbosity option selecting between just names, names and
     * syntax, and full description.
     * @param productName the product name
     */
    fun expandHelpTopic(
        topic: String,
        help: String,
        commandClass: java.lang.Class<out BlazeCommand?>?,
        options: MutableCollection<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>?,
        helpVerbosity: com.google.devtools.common.options.HelpVerbosity?,
        productName: String
    ): String {
        val parser: com.google.devtools.common.options.OptionsParser =
            com.google.devtools.common.options.OptionsParser.builder().optionsClasses(options).build()

        val template: String?
        if (help.startsWith("resource:")) {
            val resourceName: String = help.substring("resource:".length())
            try {
                template = ResourceFileLoader.loadResource(commandClass, resourceName)
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(
                    ("failed to load help resource '"
                            + resourceName
                            + "' due to I/O error: "
                            + e.getMessage()),
                    e
                )
            }
        } else {
            template = help
        }

        check(template.contains("%{options}")) { "Help template for '" + topic + "' omits %{options}!" }

        val optionStr: String = parser.describeOptions(helpVerbosity).replace("%{product}", productName)

        return (template
            .replace("%{product}", productName)
            .replace("%{command}", topic)
            .replace("%{options}", optionStr)
            .trim()
                + "\n\n"
                + (if (helpVerbosity == com.google.devtools.common.options.HelpVerbosity.MEDIUM)
            "(Use 'help --long' for full details or --short to just enumerate options.)\n"
        else
            ""))
    }

    /**
     * The help page for this command.
     * 
     * @param verbosity a tri-state verbosity option selecting between just names, names and syntax,
     * and full description.
     */
    fun getUsage(
        commandClass: java.lang.Class<out BlazeCommand?>,
        verbosity: com.google.devtools.common.options.HelpVerbosity?,
        optionsSuppliers: Iterable<com.google.devtools.build.lib.runtime.OptionsSupplier>,
        ruleClassProvider: ConfiguredRuleClassProvider,
        productName: String
    ): String {
        val commandAnnotation: com.google.devtools.build.lib.runtime.Command =
            commandClass.getAnnotation<com.google.devtools.build.lib.runtime.Command>(com.google.devtools.build.lib.runtime.Command::class.java)
        return expandHelpTopic(
            commandAnnotation.name,
            commandAnnotation.help,
            commandClass,
            getOptions(commandClass, optionsSuppliers, ruleClassProvider),
            verbosity,
            productName
        )
    }
}
