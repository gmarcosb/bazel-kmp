// Copyright 2017 The Bazel Authors. All rights reserved.
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

import java.io.IOException

/**
 * Defines an [ArgsPreProcessor] that will determine if the arguments list contains a "params"
 * file that contains a list of options to be parsed.
 * 
 * 
 * Params files are used when the argument list of [Option] exceed the shells commandline
 * length. A params file argument is defined as a path starting with @. It will also be the only
 * entry in an argument list.
 */
abstract class ParamsFilePreProcessor internal constructor(fs: java.nio.file.FileSystem) :
    com.google.devtools.common.options.ArgsPreProcessor {
    private val fs: java.nio.file.FileSystem

    init {
        this.fs = fs
    }

    /**
     * Parses the param file path and replaces the arguments list with the contents if one exists.
     * 
     * @param args A list of arguments that may contain @&lt;path&gt; to a params file.
     * @return A list of arguments suitable for parsing.
     * @throws OptionsParsingException if the path does not exist.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    override fun preProcess(args: MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?>): MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?> {
        if (!args.isEmpty() && args.get(0).arg.startsWith("@")) {
            if (args.size() > 1) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        com.google.devtools.common.options.ParamsFilePreProcessor.Companion.TOO_MANY_ARGS_ERROR_MESSAGE_FORMAT,
                        com.google.common.collect.Lists.transform<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?, String?>(
                            args,
                            com.google.common.base.Function { argAndFallbackData: com.google.devtools.common.options.OptionsParser.ArgAndFallbackData? -> argAndFallbackData.arg })
                    ),
                    args.get(0).arg
                )
            }
            val path: java.nio.file.Path? = fs.getPath(args.get(0).arg.substring(1))
            try {
                return com.google.devtools.common.options.OptionsParser.ArgAndFallbackData.Companion.wrapWithFallbackData(
                    parse(path),
                    args.get(0).fallbackData
                )
            } catch (e: java.lang.RuntimeException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        com.google.devtools.common.options.ParamsFilePreProcessor.Companion.ERROR_MESSAGE_FORMAT,
                        path,
                        e.getMessage()
                    ), args.get(0).arg, e
                )
            } catch (e: IOException) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        com.google.devtools.common.options.ParamsFilePreProcessor.Companion.ERROR_MESSAGE_FORMAT,
                        path,
                        e.getMessage()
                    ), args.get(0).arg, e
                )
            }
        }
        return args
    }

    /**
     * Parses the paramsFile and returns a list of argument tokens to be further processed by the
     * [OptionsParser].
     * 
     * @param paramsFile The path of the params file to parse.
     * @return a list of argument tokens.
     * @throws IOException if there is an error reading paramsFile.
     * @throws OptionsParsingException if there is an error reading paramsFile.
     */
    @Throws(IOException::class, com.google.devtools.common.options.OptionsParsingException::class)
    protected abstract fun parse(paramsFile: java.nio.file.Path?): MutableList<String?>?

    companion object {
        const val ERROR_MESSAGE_FORMAT: String = "Error reading params file: %s %s"

        const val TOO_MANY_ARGS_ERROR_MESSAGE_FORMAT: String = "A params file must be the only argument: %s"

        const val UNFINISHED_QUOTE_MESSAGE_FORMAT: String = "Unfinished quote %s at %s"
    }
}
