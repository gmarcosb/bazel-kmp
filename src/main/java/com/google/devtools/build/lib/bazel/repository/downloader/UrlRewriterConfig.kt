// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.common.base.Preconditions
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMultimap
import com.google.common.collect.ImmutableSet
import net.starlark.java.syntax.Location
import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.io.UncheckedIOException
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Models the downloader config file. This file has a line-based format, with each line starting
 * with a directive and then the action to take. These directives are:
 * 
 * 
 *  * `allow hostName` Will allow access to the given host and subdomains
 *  * `block hostName` Will block access to the given host and subdomains
 *  * `rewrite pattern pattern` Rewrite a URL using the given pattern. Back references are
 * numbered from `$1`
 *  * `all_blocked_message message which may contain spaces` If the rewriter causes all
 * URLs for a particular resource to be blocked, this informational message will be rendered
 * to the user. This directive may only be present at most once.
 * 
 * 
 * The directives are applied in the order `rewrite, allow, block'. An example config may look like:
 * 
 * <pre>
 * all_blocked_message See example.com/blocked-bazel-fetches for more information.
 * block mvnrepository.com.example
 * block maven-central.storage.googleapis.com.example
 * block gitblit.github.io.example
 * rewrite repo.maven.apache.org.example/maven2/(.*) artifacts.example.com/libs-release/$1
 * 
 * block github.com.example
 * rewrite github.com.example/([^/]+)/([^/]+)/releases/download/([^/]+)/(.*) \
 * artifacts.example.com/github-releases/$1/$2/releases/download/$3/$4
 * rewrite github.com.example/([^/]+)/([^/]+)/archive/(.+).(tar.gz|zip) \
 * artifacts.example.com/api/vcs/downloadRelease/github.com.example/$1/$2/$3?ext=$4
</pre> * 
 * 
 * In addition, you can block all hosts using the `*` wildcard.
 * 
 * 
 * Comments within the config file are allowed, and must be on their own line preceded by a
 * `#`.
 */
internal class UrlRewriterConfig(filePathsForErrorReporting: MutableList<String?>, configs: MutableList<Reader>) {
    /** Returns all `allow` directives.  */
    // A set of domain names that should be accessible.
    val allowList: MutableSet<String?>

    /** Returns all `block` directives.  */
    // A set of domain names that should be blocked.
    val blockList: MutableSet<String?>

    // A set of patterns matching "everything in the url after the scheme" to rewrite rules.
    private val rewrites: ImmutableMultimap<Pattern?, String?>

    // Message to display if the rewriter caused all URLs to be blocked.
    @kotlin.jvm.JvmField
    val allBlockedMessage: String?

    /**
     * Constructor for a single file. The `config` will be read to completion.
     * 
     * @throws UrlRewriterParseException If the file contents was invalid.
     * @throws UncheckedIOException If any processing problems occur.
     */
    constructor(filePathForErrorReporting: String, config: Reader) : this(
        ImmutableList.of<String?>(
            filePathForErrorReporting
        ), ImmutableList.of<Reader?>(config)
    )

    /**
     * Constructor for multiple files. The `configs` will be read to completion.
     * 
     * @throws UrlRewriterParseException If the file(s) contents was invalid.
     * @throws UncheckedIOException If any processing problems occur.
     */
    init {
        Preconditions.checkArgument(
            filePathsForErrorReporting.size() == configs.size(),
            "Number of file paths do not match number of readers"
        )

        val allowList = ImmutableSet.builder<String?>()
        val blockList = ImmutableSet.builder<String?>()
        val rewrites = ImmutableMultimap.builder<Pattern?, String?>()
        val allBlockedMessage = StringBuilder()

        for (i in filePathsForErrorReporting.indices) {
            val filePathForErrorReporting = filePathsForErrorReporting.get(i)
            val config = configs.get(i)
            parseConfig(
                config, filePathForErrorReporting, allowList, blockList, rewrites, allBlockedMessage
            )
        }

        this.allowList = allowList.build()
        this.blockList = blockList.build()
        this.rewrites = rewrites.build()
        this.allBlockedMessage = if (allBlockedMessage.isEmpty()) null else allBlockedMessage.toString()
    }

    /**
     * Returns a [Map] of [Pattern] to match against, and the rewriting changes to apply
     * when matched.
     */
    fun getRewrites(): MutableMap<Pattern?, MutableCollection<String?>?> {
        return rewrites.asMap()
    }

    companion object {
        private val SPLITTER: Splitter = Splitter.onPattern("\\s+").omitEmptyStrings().trimResults()
        private const val ALL_BLOCKED_MESSAGE_DIRECTIVE = "all_blocked_message"

        @Throws(UrlRewriterParseException::class)
        private fun parseConfig(
            config: Reader,
            filePathForErrorReporting: String?,
            allowList: ImmutableSet.Builder<String?>,
            blockList: ImmutableSet.Builder<String?>,
            rewrites: ImmutableMultimap.Builder<Pattern?, String?>,
            allBlockedMessage: StringBuilder
        ) {
            try {
                BufferedReader(config).use { reader ->
                    var lineNumber = 1
                    var line: String? = reader.readLine()
                    while (line != null) {
                        // Find the first word
                        val parts: MutableList<String?> = SPLITTER.splitToList(line)
                        if (parts.isEmpty()) {
                            line = reader.readLine()
                            lineNumber++
                            continue
                        }

                        // Allow comments to use #
                        if (parts.get(0).startsWith("#")) {
                            line = reader.readLine()
                            lineNumber++
                            continue
                        }

                        val location = Location.fromFileLineColumn(filePathForErrorReporting, lineNumber, 0)

                        when (parts.get(0)) {
                            "allow" -> {
                                if (parts.size() != 2) {
                                    throw UrlRewriterParseException(
                                        "Only the host name is allowed after `allow`: " + line, location
                                    )
                                }
                                allowList.add(parts.get(1))
                            }

                            "block" -> {
                                if (parts.size() != 2) {
                                    throw UrlRewriterParseException(
                                        "Only the host name is allowed after `block`: " + line, location
                                    )
                                }
                                blockList.add(parts.get(1))
                            }

                            "rewrite" -> {
                                if (parts.size() != 3) {
                                    throw UrlRewriterParseException(
                                        "Only the matching pattern and rewrite pattern is allowed after `rewrite`: "
                                                + line,
                                        location
                                    )
                                }
                                try {
                                    rewrites.put(Pattern.compile(parts.get(1)), parts.get(2))
                                } catch (e: PatternSyntaxException) {
                                    throw UrlRewriterParseException(
                                        ("Invalid regex in `rewrite`: "
                                                + e.getDescription()
                                                + " at index "
                                                + e.getIndex()
                                                + " in `"
                                                + parts.get(1)
                                                + "`"),
                                        location
                                    )
                                }
                            }

                            ALL_BLOCKED_MESSAGE_DIRECTIVE -> {
                                if (parts.size() == 1) {
                                    throw UrlRewriterParseException(
                                        "all_blocked_message must be followed by a message", location
                                    )
                                }
                                if (!allBlockedMessage.isEmpty()) {
                                    throw UrlRewriterParseException(
                                        "At most one all_blocked_message directive is allowed", location
                                    )
                                }
                                allBlockedMessage.append(line.substring(ALL_BLOCKED_MESSAGE_DIRECTIVE.length() + 1))
                            }

                            else -> throw UrlRewriterParseException("Unable to parse: " + line, location)
                        }
                        line = reader.readLine()
                        lineNumber++
                    }
                }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }
    }
}
