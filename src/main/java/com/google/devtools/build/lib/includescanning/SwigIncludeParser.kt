// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.includescanning

import com.google.common.base.CharMatcher

/** Parses swig files and extracts their includes (%include / %extern / %import).  */
internal class SwigIncludeParser : IncludeParser( /* hints= */null) {
    /** See javadoc for [IncludeParser.getFileType]  */
    override fun getFileType(): GrepIncludesFileType {
        return GrepIncludesFileType.SWIG
    }

    override fun expectIncludeKeyword(chars: ByteArray, pos: Int, end: Int): IncludesKeywordData {
        var pos = pos
        val start: Int = IncludeParser.Companion.skipWhitespace(chars, pos, end)
        if ((IncludeParser.Companion.expect(chars, start, end, "%include")
                .also { pos = it }) == -1 && (IncludeParser.Companion.expect(chars, start, end, "%extern")
                .also { pos = it }) == -1 && (IncludeParser.Companion.expect(chars, start, end, "%import")
                .also { pos = it }) == -1
        ) {
            return IncludesKeywordData.Companion.NONE
        }
        var npos: Int = IncludeParser.Companion.skipWhitespace(chars, pos, end)
        npos = skipParentheses(chars, npos, end)
        npos = IncludeParser.Companion.skipWhitespace(chars, npos, end)
        if (npos > pos) {
            return IncludesKeywordData.Companion.importOrSwig(npos)
        }
        return IncludesKeywordData.Companion.NONE
    }

    override fun isValidInclusionKind(kind: IncludeParser.Inclusion.Kind): Boolean {
        return !kind.isNext()
    }

    override fun createOtherInclusion(inclusionContent: String): Inclusion? {
        var inclusionContent = inclusionContent
        if (inclusionContent.startsWith("/")) {
            return null // Ignore absolute path names.
        }

        // Truncate comments after filename.
        var index: Int = inclusionContent.indexOf("//")
        if (index > 0) {
            inclusionContent = inclusionContent.substring(0, index)
        }
        index = inclusionContent.indexOf("/*")
        if (index > 0) {
            inclusionContent = inclusionContent.substring(0, index)
        }
        // Trim whitespace.
        inclusionContent = CharMatcher.whitespace().trimFrom(inclusionContent)

        // Treat swig inclusions w/o quotes or angle brackets as quoted inclusions.
        return if (inclusionContent.length() > 0) Inclusion.Companion.create(
            inclusionContent,
            IncludeParser.Inclusion.Kind.QUOTE
        ) else null
    }

    companion object {
        private fun skipParentheses(chars: ByteArray, pos: Int, end: Int): Int {
            // TODO(bazel-team): In theory this could be multiline, but the include scanner currently works
            // on a single line.
            var pos = pos
            var openedParentheses = 1
            if (pos >= end || chars[pos] != '('.code.toByte()) {
                return pos
            }
            pos++
            while (openedParentheses > 0 && pos < end) {
                if (chars[pos] == '('.code.toByte()) {
                    openedParentheses++
                } else if (chars[pos] == ')'.code.toByte()) {
                    openedParentheses--
                }
                pos++
            }
            return pos
        }
    }
}
