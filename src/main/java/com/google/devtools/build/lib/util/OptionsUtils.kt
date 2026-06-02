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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.ShellEscaper
import com.google.devtools.build.lib.vfs.PathFragment

/** Blaze-specific option utilities.  */
object OptionsUtils {
    /**
     * Returns a string representation of the non-hidden specified options; option values are
     * shell-escaped.
     */
    fun asShellEscapedString(optionsList: Iterable<com.google.devtools.common.options.ParsedOptionDescription>): String {
        val result: java.lang.StringBuilder = java.lang.StringBuilder()
        for (option in optionsList) {
            if (option.isHidden()) {
                continue
            }
            if (result.length != 0) {
                result.append(' ')
            }
            result.append(option.getCanonicalFormWithValueEscaper(java.util.function.Function { unescaped: String? ->
                ShellEscaper.Companion.escapeString(
                    unescaped
                )
            }))
        }
        return result.toString()
    }

    /**
     * Returns a string representation of the non-hidden explicitly or implicitly specified options;
     * option values are shell-escaped.
     */
    fun asShellEscapedString(options: com.google.devtools.common.options.OptionsParsingResult): String {
        return OptionsUtils.asShellEscapedString(options.asCompleteListOfParsedOptions())
    }

    /**
     * Return a representation of the non-hidden specified options, as a list of string. No escaping
     * is done.
     */
    fun asArgumentList(optionsList: Iterable<com.google.devtools.common.options.ParsedOptionDescription>): MutableList<String?> {
        val builder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (option in optionsList) {
            if (option.isHidden()) {
                continue
            }
            builder.add(option.getCanonicalForm())
        }
        return builder.build()
    }

    /**
     * Return a representation of the non-hidden specified options, as a list of string. No escaping
     * is done.
     */
    fun asArgumentList(options: com.google.devtools.common.options.OptionsParsingResult): MutableList<String?> {
        return OptionsUtils.asArgumentList(options.asCompleteListOfParsedOptions())
    }

    /**
     * Returns a string representation of the non-hidden explicitly or implicitly specified options,
     * filtering out any sensitive options; option values are shell-escaped.
     */
    /**
     * Returns a string representation of the non-hidden explicitly or implicitly specified options,
     * filtering out any sensitive options; option values are shell-escaped.
     */
    @kotlin.jvm.JvmOverloads
    fun asFilteredShellEscapedString(
        options: com.google.devtools.common.options.OptionsParsingResult?,
        optionsList: Iterable<com.google.devtools.common.options.ParsedOptionDescription> = options.asCompleteListOfParsedOptions()
    ): String {
        return OptionsUtils.asShellEscapedString(optionsList)
    }

    private fun convertOptionsPathFragment(path: String): PathFragment {
        var path = path
        if (!path.isEmpty() && path.startsWith("~/")) {
            path = path.replace("~", com.google.common.base.StandardSystemProperty.USER_HOME.value())
        }
        return PathFragment.Companion.create(path)
    }

    /** Converter from String to PathFragment.  */
    class PathFragmentConverter : com.google.devtools.common.options.Converter.Contextless<PathFragment?>() {
        override fun convert(input: String?): PathFragment {
            return convertOptionsPathFragment(com.google.common.base.Preconditions.checkNotNull<String?>(input))
        }

        val typeDescription: String
            get() = "a path"
    }

    /** Converter from String to PathFragment. If the input is empty returns `null` instead.  */
    class EmptyToNullPathFragmentConverter : com.google.devtools.common.options.Converter.Contextless<PathFragment?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String): PathFragment? {
            if (input.isEmpty()) {
                return null
            }
            return convertOptionsPathFragment(input)
        }

        val typeDescription: String
            get() = "a path"
    }

    /** Converter from String to PathFragment requiring the provided path to be absolute.  */
    class AbsolutePathFragmentConverter : com.google.devtools.common.options.Converter.Contextless<PathFragment?>() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String?): PathFragment {
            val parsed: PathFragment =
                convertOptionsPathFragment(com.google.common.base.Preconditions.checkNotNull<String?>(input))
            if (!parsed.isAbsolute()) {
                throw com.google.devtools.common.options.OptionsParsingException(
                    String.format(
                        "Not an absolute path: '%s'",
                        input
                    )
                )
            }
            return parsed
        }

        val typeDescription: String
            get() = "an absolute path"
    }

    /** Converts from a colon-separated list of strings into a list of PathFragment instances.  */
    class PathFragmentListConverter

        :
        com.google.devtools.common.options.Converter.Contextless<com.google.common.collect.ImmutableList<PathFragment?>?>() {
        override fun convert(input: String): com.google.common.collect.ImmutableList<PathFragment?> {
            val result: com.google.common.collect.ImmutableList.Builder<PathFragment?> =
                com.google.common.collect.ImmutableList.builder<PathFragment?>()
            for (piece in input.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
                if (!piece.isEmpty()) {
                    result.add(convertOptionsPathFragment(piece))
                }
            }
            return result.build()
        }

        val typeDescription: String
            get() = "a colon-separated list of paths"
    }
}
