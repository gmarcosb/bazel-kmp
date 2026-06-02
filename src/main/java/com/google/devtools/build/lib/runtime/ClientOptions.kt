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

import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction.name

/**
 * Options that the Bazel client passes to server, which are then incorporated into the environment.
 * 
 * 
 * The rc file options are parsed in their own right and appear, if applicable, in the final
 * value of the parsed options. The environment variables update the stored values in the
 * CommandEnvironment. These options should never be accessed directly from this class after command
 * environment initialization.
 */
@com.google.devtools.common.options.OptionsClass
abstract class ClientOptions : com.google.devtools.common.options.OptionsBase() {
    /**
     * A class representing a blazerc option. blazeRc is serial number of the rc file this option came
     * from, option is the name of the option and value is its value (or null if not specified).
     */
    class OptionOverride(val blazeRc: Int, val command: String?, val option: String?) {
        override fun toString(): String {
            return String.format("%d:%s=%s", blazeRc, command, option)
        }
    }

    /** Converter for --default_override. The format is: --default_override=blazerc:command=option.  */
    class OptionOverrideConverter : com.google.devtools.common.options.Converter.Contextless<OptionOverride?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): OptionOverride {
            val colonPos: Int = input.indexOf(':')
            val assignmentPos: Int = input.indexOf('=')

            if (colonPos < 0) {
                throw com.google.devtools.common.options.OptionsParsingException(ERROR_MESSAGE)
            }

            if (assignmentPos <= colonPos + 1) {
                throw com.google.devtools.common.options.OptionsParsingException(ERROR_MESSAGE)
            }

            val blazeRc: Int
            try {
                blazeRc = input.substring(0, colonPos).toInt()
            } catch (e: java.lang.NumberFormatException) {
                throw com.google.devtools.common.options.OptionsParsingException(ERROR_MESSAGE, e)
            }

            if (blazeRc < 0) {
                throw com.google.devtools.common.options.OptionsParsingException(ERROR_MESSAGE)
            }

            val command: String = input.substring(colonPos + 1, assignmentPos)
            val option: String = input.substring(assignmentPos + 1)

            return OptionOverride(blazeRc, command, option)
        }

        val typeDescription: String
            get() = "blazerc option override"

        companion object {
            const val ERROR_MESSAGE: String =
                "option overrides must be in form rcfile:command=option, where rcfile is a nonzero integer"
        }
    }

    @get:com.google.devtools.common.options.Option(
        name = "client_env",
        defaultValue = "null",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS],
        converter = com.google.devtools.common.options.Converters.AssignmentConverter::class,
        allowMultiple = true,
        help = "A system-generated parameter which specifies the client's environment"
    )
    abstract val clientEnv: MutableList<MutableMap.MutableEntry<String?, String?>?>?

    @get:com.google.devtools.common.options.Option(
        name = "default_override",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        converter = OptionOverrideConverter::class,
        help = ""
    )
    abstract val optionsOverrides: MutableList<OptionOverride?>?

    @get:com.google.devtools.common.options.Option(
        name = "rc_source",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.HIDDEN],
        help = ""
    )
    abstract val rcSource: MutableList<String?>?
}
