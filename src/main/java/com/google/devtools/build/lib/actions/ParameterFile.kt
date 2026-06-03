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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.unsafe.StringUnsafe

/**
 * Support for parameter file generation (as used by gcc and other tools, e.g. `gcc @param_file`. Note that the parameter file needs to be explicitly deleted after use.
 * Different tools require different parameter file formats, which can be selected via the [ ] enum.
 * 
 * 
 * Don't use this class for new code. Use the ParameterFileWriteAction instead!
 */
object ParameterFile {
    val PARAMETER_FILE: FileType? = FileType.of(".params")

    /** Derives a path from a given path by appending `".params"`.  */
    fun derivePath(original: PathFragment): PathFragment {
        return derivePath(original, "2")
    }

    /** Derives a path from a given path by appending `".params"`.  */
    fun derivePath(original: PathFragment, flavor: String?): PathFragment {
        return original.replaceName(original.getBaseName() + "-" + flavor + ".params")
    }

    /** Writes an argument list to a parameter file.  */
    @Throws(IOException::class)
    fun writeParameterFile(
        out: java.io.OutputStream, arguments: Iterable<String?>, type: ParameterFileType
    ) {
        when (type) {
            ParameterFileType.SHELL_QUOTED -> writeContent(out, ShellEscaper.escapeAll(arguments))
            ParameterFileType.GCC_QUOTED -> writeContent(out, GccParamFileEscaper.escapeAll(arguments))
            ParameterFileType.UNQUOTED -> writeContent(out, arguments)
            ParameterFileType.WINDOWS -> writeContent(out, WindowsParamFileEscaper.escapeAll(arguments))
        }
    }

    @Throws(IOException::class)
    private fun writeContent(out: java.io.OutputStream, arguments: Iterable<String?>) {
        for (line in arguments) {
            out.write(StringUnsafe.getInternalStringBytes(line))
            out.write('\n'.code)
        }
        out.flush()
    }

    /** Criterion shared by [.flagsOnly] and [.nonFlags].  */
    private fun isFlag(arg: String): Boolean {
        return arg.startsWith("--")
    }

    /**
     * Filters the given args to only flags (i.e. start with "--").
     * 
     * 
     * Note, this makes sense only if flags with values have previously been joined,
     * e.g."--foo=bar" rather than "--foo", "bar".
     */
    fun flagsOnly(args: Iterable<String?>): Iterable<String?> {
        return com.google.common.collect.Iterables.filter<String?>(
            args,
            com.google.common.base.Predicate { obj: ParameterFile?, arg: String -> isFlag(arg) })
    }

    /**
     * * Filters the given args to only non-flags (i.e. do not start with "--").
     * 
     * 
     * Note, this makes sense only if flags with values have previously been joined,
     * e.g."--foo=bar" rather than "--foo", "bar".
     */
    fun nonFlags(args: Iterable<String?>): Iterable<String?> {
        return com.google.common.collect.Iterables.filter<String?>(
            args,
            com.google.common.base.Predicate { arg: String? -> !ParameterFile.isFlag(arg!!) })
    }

    /** Different styles of parameter files.  */
    enum class ParameterFileType {
        /**
         * A parameter file with every parameter on a separate line. This format cannot handle newlines
         * in parameters. It is currently used for most tools, but may not be interpreted correctly if
         * parameters contain white space or other special characters. It should be avoided for new
         * development.
         */
        UNQUOTED,

        /**
         * A parameter file where each parameter is correctly quoted for shell use, and separated by
         * white space (space, tab, newline). This format is safe for all characters, but must be
         * specially supported by the tool. In particular, it must not be used with gcc and related
         * tools, which do not support this format as it is.
         */
        SHELL_QUOTED,

        /**
         * A parameter file where each parameter is correctly quoted for gcc or clang use, and separated
         * by white space (space, tab, newline).
         */
        GCC_QUOTED,

        /**
         * A parameter file where each parameter is correctly quoted for windows use. Double-quotes are
         * escaped, and each parameter that contains whitespace is surrounded in double-quotes.
         */
        WINDOWS,
    }
}
