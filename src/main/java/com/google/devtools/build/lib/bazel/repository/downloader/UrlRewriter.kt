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

import com.google.auth.Credentials
import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.io.Closer
import com.google.devtools.build.lib.authandtls.Netrc
import com.google.devtools.build.lib.util.OS
import com.google.devtools.build.lib.vfs.Path
import net.starlark.java.syntax.Location
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.regex.Matcher
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/**
 * Helper class for taking URLs and converting them according to an optional config specified by
 * [com.google.devtools.build.lib.bazel.repository.RepositoryOptions.downloaderConfig].
 * 
 * 
 * The primary reason for doing this is to allow a bazel user to redirect particular URLs to
 * (eg.) local mirrors without needing to rewrite third party rulesets.
 */
class UrlRewriter @VisibleForTesting internal constructor(
    filePathsForErrorReporting: MutableList<String?>?,
    readers: MutableList<Reader?>?
) {
    private val config: UrlRewriterConfig

    init {
        Preconditions.checkNotNull<MutableList<Reader?>?>(readers, "UrlRewriterConfig source must be set")
        Preconditions.checkNotNull<MutableList<String?>?>(
            filePathsForErrorReporting, "UrlRewriterConfig filePath must be set"
        )
        Preconditions.checkArgument(
            filePathsForErrorReporting.size() == readers.size(),
            "filePath and readers size must be equal"
        )

        this.config = UrlRewriterConfig(filePathsForErrorReporting, readers)
    }

    /**
     * Rewrites `urls` using the configuration provided to [.getDownloaderUrlRewriter].
     * The returned list of URLs may be empty if the configuration used blocks all the input URLs.
     * 
     * @param urls The input list of [URL]s. May be empty.
     * @return The amended lists of URLs.
     */
    fun amend(urls: MutableList<URI?>?): ImmutableList<RewrittenURL?> {
        Objects.requireNonNull<MutableList<URI?>?>(urls, "URLS to check must be set but may be empty")

        return urls!!.stream().map<ImmutableList<RewrittenURL?>?>(Function { url: URI? -> this.rewrite(url) })
            .flatMap<RewrittenURL?>(
                Function { obj: ImmutableList<RewrittenURL?>? -> obj!!.stream() })
            .collect(ImmutableList.toImmutableList<RewrittenURL?>())
    }

    /**
     * Updates `authHeaders` using the userInfo available in the provided `urls`. Note
     * that if the same url is present in both `authHeaders` and **download config** then it
     * will be overridden with the value from **download config**.
     * 
     * @param urls The input list of [URL]s. May be empty.
     * @param authHeaders A map of the URLs and their corresponding auth tokens.
     * @return A map of the updated authentication headers.
     */
    fun updateAuthHeaders(
        urls: MutableList<RewrittenURL>,
        authHeaders: MutableMap<URI?, MutableMap<String?, MutableList<String?>?>?>,
        netrcCreds: Credentials?
    ): MutableMap<URI?, MutableMap<String?, MutableList<String?>?>?> {
        val updatedAuthHeaders: MutableMap<URI?, MutableMap<String?, MutableList<String?>?>?> =
            HashMap<URI?, MutableMap<String?, MutableList<String?>?>?>(authHeaders)

        for (url in urls) {
            // if URL was not re-written by UrlRewriter in first place, we should not attach auth headers
            // to it
            if (!url.rewritten()) {
                continue
            }

            val userInfo = url.url()!!.getUserInfo()
            if (userInfo != null) {
                val token =
                    "Basic " + Base64.getEncoder().encodeToString(userInfo.getBytes(StandardCharsets.ISO_8859_1))
                updatedAuthHeaders.put(
                    url.url(),
                    ImmutableMap.of<String?, MutableList<String?>?>("Authorization", ImmutableList.of<String?>(token))
                )
            } else if (netrcCreds != null) {
                try {
                    val urlAuthHeaders = netrcCreds.getRequestMetadata(url.url())
                    if (urlAuthHeaders == null || urlAuthHeaders.isEmpty()) {
                        continue
                    }
                    // there could be multiple Auth headers, take the first one
                    val firstAuthHeader: MutableMap.MutableEntry<String?, MutableList<String?>?> =
                        urlAuthHeaders.entrySet().stream().findFirst().get()
                    if (firstAuthHeader.getValue() != null && !firstAuthHeader.getValue().isEmpty()) {
                        updatedAuthHeaders.put(
                            url.url(),
                            ImmutableMap.of<String?, MutableList<String?>?>(
                                firstAuthHeader.getKey(), ImmutableList.of<String?>(firstAuthHeader.getValue().get(0))
                            )
                        )
                    }
                } catch (e: IOException) {
                    // If the credentials extraction failed, we're letting bazel try without credentials.
                }
            }
        }

        return ImmutableMap.copyOf<URI?, MutableMap<String?, MutableList<String?>?>?>(updatedAuthHeaders)
    }

    private fun rewrite(url: URI?): ImmutableList<RewrittenURL?> {
        Preconditions.checkNotNull<URI?>(url)

        // Cowardly refuse to rewrite non-HTTP(S) urls
        if (REWRITABLE_SCHEMES.stream()
                .noneMatch(Predicate { scheme: String? -> Ascii.equalsIgnoreCase(scheme, url!!.getScheme()) })
        ) {
            return ImmutableList.of<RewrittenURL?>(RewrittenURL.Companion.create(url, false))
        }

        val rewrittenUrls = applyRewriteRules(url!!)

        val toReturn = ImmutableList.builder<RewrittenURL?>()
        // Now iterate over the URLs
        for (consider in rewrittenUrls) {
            // If there's an allow entry, add it to the set to return and continue
            if (isAllowMatched(consider.url()!!)) {
                toReturn.add(consider)
                continue
            }

            // If there's no block that matches the domain, add it to the set to return and continue
            if (!isBlockMatched(consider.url()!!)) {
                toReturn.add(consider)
            }
        }

        return toReturn.build()
    }

    private fun isAllowMatched(url: URI): Boolean {
        for (host in config.getAllowList()) {
            if (isMatchingHostName(url, host)) {
                return true
            }
        }
        return false
    }

    private fun isBlockMatched(url: URI): Boolean {
        for (host in config.getBlockList()) {
            // Allow a wild-card block
            if ("*" == host) {
                return true
            }

            if (isMatchingHostName(url, host)) {
                return true
            }
        }
        return false
    }

    private fun applyRewriteRules(url: URI): ImmutableList<RewrittenURL> {
        val withoutScheme: String = url.toString().substring(url.getScheme().length() + 3)

        val rewrittenUrls = ImmutableSet.builder<String?>()

        var matchMade = false
        for (entry in config.getRewrites().entrySet()) {
            val matcher: Matcher = entry.getKey().matcher(withoutScheme)
            if (matcher.matches()) {
                matchMade = true

                for (replacement in entry.getValue()) {
                    rewrittenUrls.add(matcher.replaceFirst(replacement))
                }
            }
        }

        if (!matchMade) {
            return ImmutableList.of<RewrittenURL?>(RewrittenURL.Companion.create(url, false))
        }

        return rewrittenUrls.build().stream()
            .map<URI?>(Function { urlString: String? -> Companion.prefixWithProtocol(urlString!!, url.getScheme()) })
            .map<RewrittenURL?>(Function { plainUrl: URI? -> RewrittenURL.Companion.create(plainUrl, true) })
            .collect(ImmutableList.toImmutableList<RewrittenURL?>())
    }

    val allBlockedMessage: String?
        get() = config.getAllBlockedMessage()

    /** Holds the URL along with meta-info, such as whether URL was re-written or not.  */
    @AutoValue
    abstract class RewrittenURL {
        abstract fun url(): URI?

        abstract fun rewritten(): Boolean

        companion object {
            @kotlin.jvm.JvmStatic
            fun create(url: URI?, rewritten: Boolean): RewrittenURL {
                return AutoValue_UrlRewriter_RewrittenURL(url, rewritten)
            }
        }
    }

    companion object {
        private val REWRITABLE_SCHEMES: ImmutableSet<String?> = ImmutableSet.of<String?>("http", "https")

        /**
         * Obtain a new `UrlRewriter` configured with the specified config file.
         * 
         * @param configPaths Paths to the config file to use. May be null.
         */
        @Throws(UrlRewriterParseException::class)
        fun getDownloaderUrlRewriter(
            workspaceRoot: Path, configPaths: MutableList<PathFragment?>?
        ): UrlRewriter {
            // "empty" UrlRewriter shouldn't alter auth headers
            if (configPaths == null || configPaths.isEmpty()
                || configPaths.stream().anyMatch(Predicate { obj: PathFragment? -> obj.isEmpty() })
            ) {
                return UrlRewriter(ImmutableList.of<String?>(""), ImmutableList.of<Reader?>(StringReader("")))
            }

            // There have been reports (eg. https://github.com/bazelbuild/bazel/issues/22104) that
            // there are occasional errors when `configFile` can't be found, and when this happens
            // investigation suggests that the current working directory isn't the workspace root.
            val actualConfigPaths =
                configPaths.stream().map<Path?>(Function { other: PathFragment? -> workspaceRoot.getRelative(other) })
                    .toList()

            val notFoundConfigPaths =
                actualConfigPaths.stream().filter(Predicate.not<Path?>(Predicate { obj: Path? -> obj!!.exists() }))
                    .toList()
            if (!notFoundConfigPaths.isEmpty()) {
                throw UrlRewriterParseException(
                    java.lang.String.format(
                        "Unable to find downloader config file %s",
                        notFoundConfigPaths.stream()
                            .map<String?>(Function { obj: Path? -> obj!!.getPathString() })
                            .collect(Collectors.joining(","))
                    )
                )
            }

            // Java's try-with-resources doesn't handle dynamic amounts of AutoCloseable resources, so use
            // Closer to register and close.
            val closer = Closer.create()
            try { // For IOExceptions coming from Closer.
                try {
                    val readers: MutableList<Reader?> = ArrayList<Reader?>()
                    for (actualConfigPath in actualConfigPaths) {
                        val br: BufferedReader =
                            BufferedReader(InputStreamReader(actualConfigPath.getInputStream(), StandardCharsets.UTF_8))
                        closer.register<BufferedReader?>(br)
                        readers.add(br)
                    }
                    return UrlRewriter(
                        configPaths.stream().map<String?>(Function { obj: PathFragment? -> obj.getPathString() })
                            .toList(), readers
                    )
                } catch (e: Throwable) {
                    throw closer.rethrow<UrlRewriterParseException?>(e, UrlRewriterParseException::class.java)
                } finally {
                    closer.close()
                }
            } catch (e: IOException) {
                throw UrlRewriterParseException(e.getMessage())
            }
        }

        private fun isMatchingHostName(url: URI, host: String): Boolean {
            return host == url.getHost() || url.getHost().endsWith("." + host)
        }

        /** Prefixes url with protocol if not already prefixed by [.REWRITABLE_SCHEMES]  */
        private fun prefixWithProtocol(url: String, protocol: String?): URI {
            for (schemaPrefix in REWRITABLE_SCHEMES) {
                if (url.startsWith(schemaPrefix + "://")) {
                    return URI.create(url)
                }
            }
            return URI.create(protocol + "://" + url)
        }

        /**
         * Create a new [Credentials] object by parsing the .netrc file with following order to
         * search it:
         * 
         * 
         *  1. If environment variable $NETRC exists, use it as the path to the .netrc file
         *  1. Fallback to $HOME/.netrc or $USERPROFILE/.netrc
         * 
         * 
         * @return the [Credentials] object or `null` if there is no .netrc file.
         * @throws UrlRewriterParseException in case the credentials can't be constructed.
         */
        // TODO : consider re-using RemoteModule.newCredentialsFromNetrc
        @Throws(UrlRewriterParseException::class)
        fun newCredentialsFromNetrc(
            clientEnv: MutableMap<String?, String?>, workingDirectory: Path?
        ): Credentials? {
            val homeDir: Optional<String?>
            if (OS.getCurrent() == OS.WINDOWS) {
                homeDir = Optional.ofNullable<String?>(clientEnv.get("USERPROFILE"))
            } else {
                homeDir = Optional.ofNullable<String?>(clientEnv.get("HOME"))
            }
            val netrcFileString =
                Optional.ofNullable<String?>(clientEnv.get("NETRC"))
                    .orElseGet(Supplier {
                        homeDir.map<String?>(Function { home: String? -> home + "/.netrc" }).orElse(null)
                    })
            if (netrcFileString == null) {
                return null
            }
            val location = Location.fromFileLineColumn(netrcFileString, 0, 0)
            // In case Bazel is not started from a valid workspace.
            if (workingDirectory == null) {
                return null
            }
            // Using the getRelative() method ensures:
            //  - If netrcFileString is an absolute path, use as it is.
            //  - If netrcFileString is a relative path, it's resolved to an absolute path with the current
            //    working directory.
            val netrcFile = workingDirectory.getRelative(netrcFileString)
            if (netrcFile.exists()) {
                try {
                    val netrc: Netrc? = NetrcParser.parseAndClose(netrcFile.getInputStream())
                    return NetrcCredentials(netrc)
                } catch (e: IOException) {
                    throw UrlRewriterParseException(
                        "Failed to parse " + netrcFile.getPathString() + ": " + e.getMessage(), location
                    )
                }
            } else {
                return null
            }
        }
    }
}
