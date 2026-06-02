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
package com.google.devtools.common.options

import java.util.LinkedHashMap

/**
 * Interface for parsing options from a single options specification class.
 * 
 * 
 * The [Options.parse] method in this class has no clear use case.
 * Instead, use the [OptionsParser] class directly, as in this code snippet:
 * 
 * <pre>
 * OptionsParser parser = OptionsParser.builder()
 * .optionsClasses(FooOptions.class)
 * .build();
 * try {
 * parser.parse(FooOptions.class, args);
 * } catch (OptionsParsingException e) {
 * System.err.print("Error parsing options: " + e.getMessage());
 * System.err.print(options.getUsage());
 * System.exit(1);
 * }
 * FooOptions foo = parser.getOptions(FooOptions.class);
 * List&lt;String&gt; otherArguments = parser.getResidue();
</pre> * 
 * 
 * Using this class in this case actually results in more code.
 * 
 * @see OptionsParser for parsing options from multiple options specification classes.
 */
class Options<O : com.google.devtools.common.options.OptionsBase?> private constructor(
  @kotlin.jvm.JvmField private val options: O?,
  @kotlin.jvm.JvmField private val remainingArgs: Array<String?>?
) {
    /**
     * Returns an instance of options class O.
     */
    fun getOptions(): O? {
        return options
    }

    /**
     * Returns the arguments that we didn't parse.
     */
    fun getRemainingArgs(): Array<String?>? {
        return remainingArgs
    }

    companion object {
        /**
         * Parse the options provided in args, given the specification in
         * optionsClass.
         */
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun <O : com.google.devtools.common.options.OptionsBase?> parse(
            optionsClass: java.lang.Class<O?>?,
            vararg args: String?
        ): Options<O?> {
            val parser: com.google.devtools.common.options.OptionsParser =
                com.google.devtools.common.options.OptionsParser.Companion.builder().optionsClasses(optionsClass)
                    .build()
            parser.parse(
                com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE,
                null,
                java.util.Arrays.asList<String?>(*args)
            )
            val remainingArgs: MutableList<String?> = parser.getResidue()
            return com.google.devtools.common.options.Options<O?>(
                parser.getOptions<O?>(optionsClass), remainingArgs.toArray<String?>(
                    arrayOfNulls<String>(0)
                )
            )
        }

        /**
         * Returns an options object at its default values.  The returned object may
         * be freely modified by the caller, by assigning its fields.
         */
        fun <O : com.google.devtools.common.options.OptionsBase?> getDefaults(optionsClass: java.lang.Class<O?>?): O? {
            try {
                return com.google.devtools.common.options.Options.Companion.parse<O?>(
                    optionsClass,
                    *arrayOfNulls<String>(0)
                ).getOptions()
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                val message = "Error while parsing defaults: " + e.getMessage()
                throw java.lang.AssertionError(message)
            }
        }

        /**
         * Returns a usage string (renders the help information, the defaults, and
         * of course the option names).
         */
        fun getUsage(optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?): String {
            val usage: java.lang.StringBuilder = java.lang.StringBuilder()
            com.google.devtools.common.options.OptionsUsage.getUsage(optionsClass, usage)
            return usage.toString()
        }

        /**
         * Returns a mapping from option names to values, for each option on the given options class,
         * including inherited ones. The mapping is a copy, so subsequent mutations to it or to this
         * object are independent. Entries are sorted alphabetically.
         */
        fun <O : com.google.devtools.common.options.OptionsBase?> toMap(options: O?): MutableMap<String?, Any?> {
            val definitions: com.google.common.collect.ImmutableList<out com.google.devtools.common.options.OptionDefinition> =
                com.google.devtools.common.options.IsolatedOptionsData.Companion.getAllOptionDefinitionsForClass(options.getOptionsClass())
            val map: LinkedHashMap<String?, Any?> =
                com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<String?, Any?>(definitions.size())
            for (definition in definitions) {
                map.put(definition.getOptionName(), definition.getValue(options))
            }
            return map
        }
    }
}
