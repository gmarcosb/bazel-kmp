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
package com.google.devtools.build.docgen

import java.util.ArrayDeque
import java.util.Deque

/**
 * A utility class to check the generated documentations.
 */
object DocCheckerUtils {
    // TODO(bazel-team): remove elements from this list and clean up the tested documentations.
    private val UNCHECKED_HTML_TAGS: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>(
            "br", "li", "ul", "p"
        )

    private val TAG_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile(
        ("<([/]?[a-z0-9_]+)"
                + "([^>]*)"
                + ">"),
        java.util.regex.Pattern.CASE_INSENSITIVE
    )

    private val COMMENT_OR_BACKTICK_PATTERN: java.util.regex.Pattern =
        java.util.regex.Pattern.compile("<!--.*?-->|`.*`", java.util.regex.Pattern.CASE_INSENSITIVE)

    /**
     * Returns the first unmatched html tag of srcs or null if no such tag exists.
     * Note that this check is not performed on br, ul, li and p tags. The method also
     * prints some help in case an unmatched tag is found. The check is performed
     * inside comments too.
     */
    fun getFirstUnclosedTagAndPrintHelp(src: String): String? {
        return getFirstUnclosedTag(src, true)
    }

    fun getFirstUnclosedTag(src: String): String? {
        return getFirstUnclosedTag(src, false)
    }

    // TODO(bazel-team): run this on the Starlark docs too.
    private fun getFirstUnclosedTag(src: String, printHelp: Boolean): String? {
        var src = src
        val commentMatcher: java.util.regex.Matcher = COMMENT_OR_BACKTICK_PATTERN.matcher(src)
        src = commentMatcher.replaceAll("")
        val tagMatcher: java.util.regex.Matcher = TAG_PATTERN.matcher(src)
        val tagStack: Deque<String> = ArrayDeque<String>()
        while (tagMatcher.find()) {
            var tag: String = tagMatcher.group(1)
            val rest: String = tagMatcher.group(2)
            val strippedTag: String = tag.substring(1)

            // Ignoring self closing tags.
            if (!rest.endsWith("/") // Ignoring unchecked tags.
                && !UNCHECKED_HTML_TAGS.contains(tag) && !UNCHECKED_HTML_TAGS.contains(strippedTag)
            ) {
                if (tag.startsWith("/")) {
                    // Closing tag. Removing '/' from the beginning.
                    tag = strippedTag
                    val lastTag: String = tagStack.removeLast()
                    if (lastTag != tag) {
                        if (printHelp) {
                            java.lang.System.err.println(
                                ("Unclosed tag: " + lastTag + "\n"
                                        + "Trying to close with: " + tag + "\n"
                                        + "Stack of open tags: " + tagStack + "\n"
                                        + "Last 200 characters:\n"
                                        + src.substring(max(tagMatcher.start() - 200, 0), tagMatcher.start()))
                            )
                        }
                        return lastTag
                    }
                } else {
                    // Starting tag.
                    tagStack.addLast(tag)
                }
            }
        }
        return null
    }
}
