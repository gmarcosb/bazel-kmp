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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.util.StringEncoding
import java.io.FilterOutputStream
import java.io.IOException
import java.util.Collections

/**
 * A Class for filtering the output of /showIncludes from MSVC compiler.
 * 
 * 
 * A discovered header file will be printed with prefix "Note: including file:", the path is
 * collected, and the line is suppressed from the actual output users can see.
 * 
 * 
 * Also suppress the basename of source file, which is printed unconditionally by MSVC compiler,
 * there is no way to turn it off.
 */
class ShowIncludesFilter(private val sourceFileName: String?) {
    private var filterShowIncludesOutputStream: FilterShowIncludesOutputStream? = null

    /**
     * Use this class to filter and collect the headers discovered by MSVC compiler, also filter out
     * the source file name printed unconditionally by the compiler.
     */
    class FilterShowIncludesOutputStream(out: java.io.OutputStream?, private val sourceFileName: String?) :
        FilterOutputStream(out) {
        private val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(4096)
        val dependencies: MutableCollection<String?> = java.util.ArrayList<String?>()
        private var sawPotentialUnsupportedShowIncludesLine = false

        @Throws(IOException::class)
        override fun write(b: Int) {
            buffer.write(b)
            if (b == NEWLINE) {
                var line = buffer.toString(java.nio.charset.StandardCharsets.ISO_8859_1)
                var prefixMatched = false
                for (prefix in SHOW_INCLUDES_PREFIXES) {
                    if (line.startsWith(prefix)) {
                        line = line.substring(prefix.length()).trim()
                        val m: java.util.regex.Matcher = EXECROOT_BASE_HEADER_PATTERN.matcher(line)
                        if (m.matches()) {
                            // Prefix the matched header path with "..\". This way, external repo header paths are
                            // resolved to "<execroot>\..\<repo name>\<path>", and main repo file paths are
                            // resolved to "<execroot>\..\<main repo>\<path>", which is nicely normalized to
                            // "<execroot>\<path>".
                            line = "..\\" + m.group("headerPath")
                        }
                        dependencies.add(line)
                        prefixMatched = true
                        break
                    }
                }
                // cl.exe also prints out the file name unconditionally, we need to also filter it out.
                if (!prefixMatched && line.trim() != sourceFileName) {
                    // When the toolchain definition failed to force an English locale, /showIncludes lines
                    // can use non-UTF8 encodings, which the checks above fail to detect. As this results in
                    // incorrect incremental builds, we emit a warning if the raw byte sequence comprising the
                    // line looks like it could be a /showIncludes line.
                    if (POTENTIAL_UNSUPPORTED_SHOW_INCLUDES_LINE.matcher(line.trim()).matches()) {
                        sawPotentialUnsupportedShowIncludesLine = true
                    }
                    buffer.writeTo(out)
                }
                buffer.reset()
            }
        }

        @Throws(IOException::class)
        override fun flush() {
            val line = buffer.toString(java.nio.charset.StandardCharsets.ISO_8859_1)

            // If this line starts or could start with a prefix.
            var startingWithAnyPrefix = false
            for (prefix in SHOW_INCLUDES_PREFIXES) {
                if (line.startsWith(prefix) || prefix.startsWith(line)) {
                    startingWithAnyPrefix = true
                    break
                }
            }

            if (!startingWithAnyPrefix // If this line starts or could start with the source file name.
                && !line.startsWith(sourceFileName) && !sourceFileName.startsWith(line)
            ) {
                buffer.writeTo(out)
                buffer.reset()
            }
            out.flush()
        }

        fun sawPotentialUnsupportedShowIncludesLine(): Boolean {
            return sawPotentialUnsupportedShowIncludesLine
        }

        companion object {
            private val NEWLINE: Int = '\n'.code

            // "Note: including file:" in 14 languages,
            // cl.exe will print different prefix according to the locale configured for MSVC.
            private val SHOW_INCLUDES_PREFIXES: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<String?>( // English
                    "Note: including file:",  // Traditional Chinese
                    "注意: 包含檔案:",  // Czech
                    "Poznámka: Včetně souboru:",  // German
                    "Hinweis: Einlesen der Datei:",  // French (non-breaking spaces before the colons)
                    "Remarque : inclusion du fichier :",  // Italian (the missing : is intentional, this appears to be a bug in MSVC)
                    "Nota: file incluso",  // Japanese
                    "メモ: インクルード ファイル:",  // Korean
                    "참고: 포함 파일:",  // Polish
                    "Uwaga: w tym pliku:",  // Portuguese
                    "Observação: incluindo arquivo:",  // Russian
                    "Примечание: включение файла:",  // Turkish
                    "Not: eklenen dosya:",  // Simplified Chinese
                    "注意: 包含文件:",  // Spanish
                    "Nota: inclusión del archivo:"
                )
                    .stream()
                    .map<String?>(java.util.function.Function { s: String? -> StringEncoding.unicodeToInternal(s) })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())

            // Grab everything under the execroot base so that external repository header files are covered
            // in the sibling repository layout.
            private val EXECROOT_BASE_HEADER_PATTERN: java.util.regex.Pattern =
                java.util.regex.Pattern.compile(".*execroot\\\\(?<headerPath>.*)")

            // Match a line of the form "fooo: bar:   C:\some\path\file.h". As this is relatively generic,
            // we require the line to include an absolute path with drive letter. If remote workers rewrite
            // the path to a relative one, we won't match it, but it is unlikely that such setups use an
            // unsupported encoding. We also exclude any matches that contain numbers: MSVC warnings and
            // errors always contain numbers, but the /showIncludes output doesn't in any encoding since all
            // codepages are ASCII-compatible.
            private val POTENTIAL_UNSUPPORTED_SHOW_INCLUDES_LINE: java.util.regex.Pattern =
                java.util.regex.Pattern.compile("[^:0-9]+:\\s+[^:0-9]+:\\s+[A-Za-z]:\\\\[^:]*\\\\execroot\\\\[^:]*")
        }
    }

    fun getFilteredOutputStream(outputStream: java.io.OutputStream?): FilterOutputStream {
        filterShowIncludesOutputStream =
            FilterShowIncludesOutputStream(outputStream, sourceFileName)
        return filterShowIncludesOutputStream
    }

    fun getDependencies(root: com.google.devtools.build.lib.vfs.Path): MutableCollection<com.google.devtools.build.lib.vfs.Path?> {
        val dependenciesInPath: MutableCollection<com.google.devtools.build.lib.vfs.Path?> =
            java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>()
        if (filterShowIncludesOutputStream != null) {
            for (dep in filterShowIncludesOutputStream!!.dependencies) {
                dependenciesInPath.add(root.getRelative(dep))
            }
        }
        return Collections.unmodifiableCollection<com.google.devtools.build.lib.vfs.Path?>(dependenciesInPath)
    }

    fun sawPotentialUnsupportedShowIncludesLine(): Boolean {
        return filterShowIncludesOutputStream != null
                && filterShowIncludesOutputStream!!.sawPotentialUnsupportedShowIncludesLine()
    }

    @get:com.google.common.annotations.VisibleForTesting
    val dependencies: MutableCollection<String?>
        get() = filterShowIncludesOutputStream!!.dependencies
}
