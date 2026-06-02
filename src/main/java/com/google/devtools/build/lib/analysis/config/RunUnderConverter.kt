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

import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.LabelConverter

/** --run_under options converter.  */
class RunUnderConverter : com.google.devtools.common.options.Converter<RunUnder?> {
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    override fun convert(input: String, conversionContext: Any?): RunUnder {
        val runUnderList: MutableList<String> = java.util.ArrayList<String>()
        try {
            ShellUtils.tokenize(runUnderList, input)
        } catch (e: TokenizationException) {
            throw com.google.devtools.common.options.OptionsParsingException("Not a valid command prefix " + e.getMessage())
        }
        if (runUnderList.isEmpty()) {
            throw com.google.devtools.common.options.OptionsParsingException("Empty command")
        }
        val runUnderCommand = runUnderList.get(0)
        val runUnderSuffix: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.copyOf<String?>(runUnderList.subList(1, runUnderList.size()))
        if (runUnderCommand.startsWith("//") || runUnderCommand.startsWith("@")) {
            try {
                val runUnderLabel: com.google.devtools.build.lib.cmdline.Label? =
                    LABEL_CONVERTER.convert(runUnderCommand, conversionContext)
                return LabelRunUnder(input, runUnderSuffix, runUnderLabel)
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                throw com.google.devtools.common.options.OptionsParsingException("Not a valid label " + e.getMessage())
            }
        } else {
            return CommandRunUnder(input, runUnderSuffix, runUnderCommand)
        }
    }

    override fun getTypeDescription(): String {
        return "a prefix in front of command"
    }

    companion object {
        private val LABEL_CONVERTER: LabelConverter = LabelConverter()
    }
}
