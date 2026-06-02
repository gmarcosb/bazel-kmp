// Copyright 2016 The Bazel Authors. All rights reserved.
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
import com.google.common.base.*
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.authandtls.StaticCredentials
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.ExtendedEventHandler
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.build.lib.vfs.FileSystemUtils
import com.google.devtools.build.lib.vfs.Path
import java.lang.String
import java.net.URI
import java.net.UnknownHostException
import java.util.Optional
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.function.Function
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.IllegalArgumentException
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.Throwable

/**
 * Bazel file downloader.
 * 
 * 
 * This class uses a [Downloader] to download files from external mirrors and writes them
 * to disk.
 */
class DownloadManager(
    downloadCache: DownloadCache,
    downloader: Downloader,
    bzlmodHttpDownloader: HttpDownloader,
    eventHandler: ExtendedEventHandler
) {
    private val downloadCache: DownloadCache
    private var distdir: ImmutableList<Path> = ImmutableList.of<Path?>()
    private var rewriter: UrlRewriter? = null
    private val downloader: Downloader
    private val bzlmodHttpDownloader: HttpDownloader
    private val eventHandler: ExtendedEventHandler
    private var disableDownload = false
    private var retries = 0
    private var netrcCreds: Credentials? = null
    private var credentialFactory: CredentialFactory = CredentialFactory { StaticCredentials() }

    /** Creates `Credentials` from a map of per-`URI` authentication headers.  */
    interface CredentialFactory {
        fun create(authHeaders: MutableMap<URI?, MutableMap<String?, MutableList<String?>?>?>?): Credentials?
    }

    fun setDistdir(distdir: MutableList<Path?>) {
        this.distdir = ImmutableList.copyOf<Path?>(distdir)
    }

    fun setUrlRewriter(rewriter: UrlRewriter?) {
        this.rewriter = rewriter
    }

    fun setDisableDownload(disableDownload: Boolean) {
        this.disableDownload = disableDownload
    }

    fun setRetries(retries: Int) {
        Preconditions.checkArgument(retries >= 0, "Invalid retries")
        this.retries = retries
    }

    fun setNetrcCreds(netrcCreds: Credentials?) {
        this.netrcCreds = netrcCreds
    }

    fun setCredentialFactory(credentialFactory: CredentialFactory) {
        this.credentialFactory = credentialFactory
    }

    fun startDownload(
        executorService: ExecutorService,
        originalUrls: MutableList<URI?>,
        headers: MutableMap<String?, MutableList<String?>?>?,
        authHeaders: MutableMap<URI?, MutableMap<String?, MutableList<String?>?>?>?,
        checksum: Optional<Checksum?>,
        canonicalId: String?,
        type: Optional<String>,
        output: Path,
        clientEnv: MutableMap<String?, String?>?,
        context: String?,
        downloadPhaser: Phaser,
        mayHardlink: Boolean
    ): Future<Path?>? {
        return executorService.submit<Path?>(
            Callable {
                if (downloadPhaser.register() != 0) {
                    // Not in download phase, must already have been cancelled.
                    throw InterruptedException()
                }
                try {
                    Profiler.instance().profile("fetching: " + context).use { c ->
                        return@submit downloadInExecutor(
                            originalUrls,
                            headers,
                            authHeaders,
                            checksum,
                            canonicalId,
                            type,
                            output,
                            clientEnv,
                            context,
                            mayHardlink
                        )
                    }
                } finally {
                    downloadPhaser.arrive()
                }
            })
    }

    @Throws(IOException::class, InterruptedException::class)
    fun finalizeDownload(download: Future<Path?>): Path? {
        try {
            return download.get()
        } catch (e: ExecutionException) {
            Throwables.throwIfInstanceOf<IOException?>(e.getCause(), IOException::class.java)
            Throwables.throwIfInstanceOf<InterruptedException?>(e.getCause(), InterruptedException::class.java)
            Throwables.throwIfUnchecked(e.getCause())
            throw IllegalStateException(e)
        }
    }

    /**
     * Downloads file to disk and returns path.
     * 
     * 
     * If the checksum and path to the repository cache is specified, attempt to load the file from
     * the [RepositoryCache]. If it doesn't exist, proceed to download the file and load it into
     * the cache prior to returning the value.
     * 
     * @param originalUrls list of mirror URLs with identical content
     * @param checksum valid checksum which is checked, or absent to disable
     * @param type extension, e.g. "tar.gz" to force on downloaded filename, or empty to not do this
     * @param output destination filename if `type` is *absent*, otherwise output directory
     * @param clientEnv environment variables in shell issuing this command
     * @param context the context in which the file was fetched; used only for reporting
     * @param mayHardlink whether the output is known not to be modified after download and thus may
     * be created as a hardlink to the cache copy
     * @throws IllegalArgumentException on parameter badness, which should be checked beforehand
     * @throws IOException if download was attempted and ended up failing
     * @throws InterruptedException if this thread is being cast into oblivion
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun downloadInExecutor(
        originalUrls: MutableList<URI?>,
        headers: MutableMap<String?, MutableList<String?>?>?,
        authHeaders: MutableMap<URI?, MutableMap<String?, MutableList<String?>?>?>?,
        checksum: Optional<Checksum?>,
        canonicalId: String?,
        type: Optional<String>,
        output: Path,
        clientEnv: MutableMap<String?, String?>?,
        context: String?,
        mayHardlink: Boolean
    ): Path? {
        if (Thread.interrupted()) {
            throw InterruptedException()
        }

        // TODO(andreisolo): This code path is inconsistent as the authHeaders are fetched from a
        //  .netrc only if it comes from a http_{archive,file,jar} - and it is handled directly
        //  by Starlark code -, or if a UrlRewriter is present. However, if it comes directly from a
        //  ctx.download{,_and_extract}, this not the case. Should be refactored to handle all .netrc
        //  parsing in one place, in Java code (similarly to #downloadAndReadOneUrl).
        var rewrittenUrls = ImmutableList.copyOf<URI?>(originalUrls)
        var rewrittenAuthHeaders = authHeaders

        if (rewriter != null) {
            val rewrittenUrlMappings: ImmutableList<RewrittenURL?> = rewriter!!.amend(originalUrls)
            rewrittenUrls =
                rewrittenUrlMappings.stream().map<URI?>(Function { obj: RewrittenURL? -> obj.url() })
                    .collect(ImmutableList.toImmutableList<URI?>())
            rewrittenAuthHeaders =
                rewriter!!.updateAuthHeaders(rewrittenUrlMappings, authHeaders, netrcCreds)
        }

        val mainUrl: URI? // The "main" URL for this request
        // Used for reporting only and determining the file name only.
        if (rewrittenUrls.isEmpty()) {
            if (type.isPresent() && !Strings.isNullOrEmpty(type.get())) {
                mainUrl = URI.create("http://nonexistent.example.org/cacheprobe." + type.get())
            } else {
                mainUrl = URI.create("http://nonexistent.example.org/cacheprobe")
            }
        } else {
            mainUrl = rewrittenUrls.get(0)
        }
        val destination = getDownloadDestination(mainUrl!!, type, output)
        val candidateFileNames: ImmutableSet<String?> = Companion.getCandidateFileNames(mainUrl, destination)

        // Is set to true if the value should be cached by the checksum value provided
        var isCachingByProvidedChecksum = false

        if (checksum.isPresent()) {
            val cacheKey: String? = checksum.get().toString()
            val cacheKeyType = checksum.get().getKeyType()
            try {
                eventHandler.post(
                    CacheProgress(mainUrl.toString(), "Checking in " + cacheKeyType + " cache")
                )
                val currentChecksum: String = DownloadCache.Companion.getChecksum(cacheKeyType, destination)
                if (currentChecksum == cacheKey) {
                    // No need to download.
                    return destination
                }
            } catch (e: IOException) {
                // Ignore error trying to hash. We'll attempt to retrieve from cache or just download again.
            } finally {
                eventHandler.post(CacheProgress(mainUrl.toString()))
            }

            if (downloadCache.isEnabled()) {
                isCachingByProvidedChecksum = true

                try {
                    val cachedDestination =
                        downloadCache.get(cacheKey, destination, cacheKeyType, canonicalId, mayHardlink)
                    if (cachedDestination != null) {
                        // Cache hit!
                        eventHandler.post(DownloadCacheHitEvent(context, cacheKey, mainUrl))
                        return cachedDestination
                    }
                } catch (e: IOException) {
                    // Ignore error trying to get. We'll just download again.
                }
            }

            if (rewrittenUrls.isEmpty()) {
                val message = StringBuilder("Cache miss and no url specified")
                if (!originalUrls.isEmpty()) {
                    message.append(" - ")
                    message.append(getRewriterBlockedAllUrlsMessage(originalUrls))
                }
                throw IOException(message.toString())
            }

            for (dir in distdir) {
                if (!dir.exists()) {
                    // This is not a warning (and probably we even should drop the message); it is
                    // perfectly fine to have a common rc-file pointing to a volume that is sometimes,
                    // but not always mounted.
                    eventHandler.handle(Event.info("non-existent distdir " + dir))
                } else if (!dir.isDirectory()) {
                    eventHandler.handle(Event.warn("distdir " + dir + " is not a directory"))
                } else {
                    for (name in candidateFileNames) {
                        var match = false
                        val candidate = dir.getRelative(name)
                        try {
                            eventHandler.post(
                                CacheProgress(
                                    mainUrl.toString(), "Checking " + cacheKeyType + " of " + candidate
                                )
                            )
                            match = DownloadCache.Companion.getChecksum(cacheKeyType, candidate) == cacheKey
                        } catch (e: IOException) {
                            // Not finding anything in a distdir is a normal case, so handle it absolutely
                            // quietly. In fact, it is common to specify a whole list of dist dirs,
                            // with the assumption that only one will contain an entry.
                        } finally {
                            eventHandler.post(CacheProgress(mainUrl.toString()))
                        }
                        if (match) {
                            if (isCachingByProvidedChecksum) {
                                try {
                                    downloadCache.put(cacheKey, candidate, cacheKeyType, canonicalId)
                                } catch (e: IOException) {
                                    eventHandler.handle(
                                        Event.warn("Failed to copy " + candidate + " to repository cache: " + e)
                                    )
                                }
                            }
                            destination.getParentDirectory()!!.createDirectoryAndParents()
                            FileSystemUtils.copyFile(candidate, destination)
                            return destination
                        }
                    }
                }
            }
        }

        if (disableDownload) {
            throw IOException(String.format("Failed to download %s: download is disabled.", context))
        }

        if (rewrittenUrls.isEmpty() && !originalUrls.isEmpty()) {
            throw IOException(getRewriterBlockedAllUrlsMessage(originalUrls))
        }

        var attempt = 0
        while (true) {
            try {
                downloader.download(
                    rewrittenUrls,
                    headers,
                    credentialFactory.create(rewrittenAuthHeaders),
                    checksum,
                    canonicalId,
                    destination,
                    eventHandler,
                    clientEnv,
                    type,
                    context
                )
                break
            } catch (e: InterruptedIOException) {
                throw InterruptedException(e.getMessage())
            } catch (e: IOException) {
                if (!shouldRetryDownload(e, attempt)) {
                    throw e
                }
            }
            ++attempt
        }

        if (isCachingByProvidedChecksum) {
            downloadCache.put(
                checksum.get().toString(), destination, checksum.get().getKeyType(), canonicalId
            )
        } else if (downloadCache.isEnabled()) {
            val unused = downloadCache.put(destination, DownloadCache.KeyType.SHA256, canonicalId)
        }

        return destination
    }

    private fun shouldRetryDownload(e: IOException, attempt: Int): Boolean {
        if (attempt >= retries) {
            return false
        }

        if (isRetryableException(e)) {
            return true
        }

        for (suppressed in e.getSuppressed()) {
            if (isRetryableException(suppressed)) {
                return true
            }
        }

        return false
    }

    private fun isRetryableException(e: Throwable?): Boolean {
        return e is ContentLengthMismatchException
                || e is SocketException
                || e is UnknownHostException
    }

    /**
     * Downloads the contents of one URL and reads it into a byte array.
     * 
     * 
     * This is only meant to be used for Bzlmod registry downloads as it ignores the value of
     * `--repository_disable_download`.
     * 
     * 
     * If the checksum and path to the repository cache is specified, attempt to load the file from
     * the [RepositoryCache]. If it doesn't exist, proceed to download the file and load it into
     * the cache prior to returning the value.
     * 
     * @param originalUrl the original URL of the file
     * @param clientEnv environment variables in shell issuing this command
     * @param checksum checksum of the file used to verify the content and obtain repository cache
     * hits
     * @throws IllegalArgumentException on parameter badness, which should be checked beforehand
     * @throws IOException if download was attempted and ended up failing
     * @throws InterruptedException if this thread is being cast into oblivion
     */
    @Throws(IOException::class, InterruptedException::class)
    fun downloadAndReadOneUrlForBzlmod(
        originalUrl: URI, clientEnv: MutableMap<kotlin.String?, kotlin.String?>?, checksum: Optional<Checksum?>
    ): ByteArray? {
        if (Thread.interrupted()) {
            throw InterruptedException()
        }

        if (downloadCache.isEnabled() && checksum.isPresent()) {
            val cacheKey: kotlin.String? = checksum.get().toString()
            try {
                val content = downloadCache.getBytes(cacheKey, checksum.get().getKeyType())
                if (content != null) {
                    // Cache hit!
                    eventHandler.post(
                        DownloadCacheHitEvent("Bazel module fetching", cacheKey, originalUrl)
                    )
                    return content
                }
            } catch (e: IOException) {
                // Ignore error trying to get. We'll just download again.
            }
        }

        var authHeaders: MutableMap<URI?, MutableMap<kotlin.String?, MutableList<kotlin.String?>?>?>? =
            ImmutableMap.of<URI?, MutableMap<kotlin.String?, MutableList<kotlin.String?>?>?>()
        var rewrittenUrls = ImmutableList.of<URI?>(originalUrl)

        if (netrcCreds != null) {
            try {
                val metadata = netrcCreds!!.getRequestMetadata(originalUrl)
                if (!metadata.isEmpty()) {
                    val headers: MutableMap.MutableEntry<kotlin.String?, MutableList<kotlin.String?>?> =
                        metadata.entrySet().iterator().next()
                    authHeaders =
                        ImmutableMap.of<URI?, MutableMap<kotlin.String?, MutableList<kotlin.String?>?>?>(
                            originalUrl,
                            ImmutableMap.of<kotlin.String?, MutableList<kotlin.String?>?>(
                                headers.getKey(),
                                ImmutableList.of<kotlin.String?>(headers.getValue().get(0))
                            )
                        )
                }
            } catch (e: IOException) {
                // If the credentials extraction failed, we're letting bazel try without credentials.
            }
        }

        if (rewriter != null) {
            val rewrittenUrlMappings: ImmutableList<RewrittenURL?> =
                rewriter!!.amend(ImmutableList.of<URI?>(originalUrl))
            rewrittenUrls =
                rewrittenUrlMappings.stream().map<URI?>(Function { obj: RewrittenURL? -> obj.url() })
                    .collect(ImmutableList.toImmutableList<URI?>())
            authHeaders = rewriter!!.updateAuthHeaders(rewrittenUrlMappings, authHeaders, netrcCreds)
        }

        if (rewrittenUrls.isEmpty()) {
            throw IOException(getRewriterBlockedAllUrlsMessage(ImmutableList.of<URI?>(originalUrl)))
        }

        var content: ByteArray
        var attempt = 0
        while (true) {
            try {
                content =
                    bzlmodHttpDownloader.downloadAndReadOneUrl(
                        rewrittenUrls.get(0),
                        credentialFactory.create(authHeaders),
                        checksum,
                        eventHandler,
                        clientEnv
                    )
                break
            } catch (e: InterruptedIOException) {
                throw InterruptedException(e.getMessage())
            } catch (e: IOException) {
                if (!shouldRetryDownload(e, attempt)) {
                    throw e
                }
            }
            ++attempt
        }
        checkNotNull(content) { "Unexpected error: file should have been downloaded." }

        if (downloadCache.isEnabled()) {
            if (checksum.isPresent()) {
                downloadCache.put(checksum.get().toString(), content, checksum.get().getKeyType())
            } else {
                downloadCache.put(content, DownloadCache.KeyType.SHA256)
            }
        }
        return content
    }

    private fun getRewriterBlockedAllUrlsMessage(originalUrls: MutableList<URI?>?): kotlin.String? {
        if (rewriter == null) {
            return null
        }
        val message = StringBuilder("Configured URL rewriter blocked all URLs: ")
        message.append(originalUrls)
        val rewriterMessage = rewriter!!.getAllBlockedMessage()
        if (rewriterMessage != null) {
            message.append(" - ").append(rewriterMessage)
        }
        return message.toString()
    }

    /**
     * Creates a new [DownloadManager].
     * 
     * @param downloader The (delegating) downloader to use to download files. Is either a
     * HttpDownloader, or a GrpcRemoteDownloader.
     * @param bzlmodHttpDownloader The downloader to use for downloading files from the bzlmod
     * registry.
     */
    init {
        this.downloadCache = downloadCache
        this.downloader = downloader
        this.bzlmodHttpDownloader = bzlmodHttpDownloader
        this.eventHandler = eventHandler
    }

    private fun getDownloadDestination(url: URI, type: Optional<kotlin.String>, output: Path): Path {
        if (!type.isPresent()) {
            return output
        }
        var basename = MoreObjects.firstNonNull<kotlin.String?>(Strings.emptyToNull(getUrlBaseName(url)), "temp")
        if (!type.get().isEmpty()) {
            val suffix = "." + type.get()
            if (!basename.endsWith(suffix)) {
                basename += suffix
            }
        }
        // The basename may contain characters that aren't legal in a path with all file systems. Those
        // characters won't matter for type determination.
        return output.getRelative(FS_UNSAFE_CHARS.replaceFrom(basename, '_'))
    }

    private class CacheProgress : ExtendedEventHandler.FetchProgress {
        private val originalUrl: kotlin.String?
        private val progress: kotlin.String?
        private val isFinished: Boolean

        internal constructor(originalUrl: kotlin.String?, progress: kotlin.String?) {
            this.originalUrl = originalUrl
            this.progress = progress
            this.isFinished = false
        }

        internal constructor(originalUrl: kotlin.String?) {
            this.originalUrl = originalUrl
            this.progress = ""
            this.isFinished = true
        }

        override fun getResourceIdentifier(): kotlin.String? {
            return originalUrl
        }

        override fun getProgress(): kotlin.String? {
            return progress
        }

        override fun isFinished(): Boolean {
            return isFinished
        }
    }

    companion object {
        // The complement of a conservative range of characters that are valid for all reasonable file
        // systems.
        private val FS_UNSAFE_CHARS: CharMatcher = CharMatcher.inRange('a', 'z')
            .or(CharMatcher.inRange('A', 'Z'))
            .or(CharMatcher.inRange('0', '9'))
            .or(CharMatcher.anyOf(".-_"))
            .negate()

        /**
         * Determine the list of filenames to look for in the distdirs. Note that an output name may be
         * specified that is unrelated to the primary URL. This happens, e.g., when the parameter output
         * is specified in ctx.download.
         */
        @kotlin.jvm.JvmStatic
        @VisibleForTesting
        fun getCandidateFileNames(url: URI, destination: Path): ImmutableSet<kotlin.String?> {
            val urlBaseName: kotlin.String? = getUrlBaseName(url)
            if (!Strings.isNullOrEmpty(urlBaseName) && urlBaseName != destination.getBaseName()) {
                return ImmutableSet.of<kotlin.String?>(urlBaseName, destination.getBaseName())
            } else {
                return ImmutableSet.of<kotlin.String?>(destination.getBaseName())
            }
        }

        private fun getUrlBaseName(url: URI): kotlin.String? {
            var path = url.getPath()
            if (path == null && url.isOpaque()) {
                // Match URL#getPath() behavior for opaque file URIs such as file:../archive.tgz.
                var rawPath = url.getRawSchemeSpecificPart()
                val queryStart: Int = rawPath.indexOf('?'.code)
                if (queryStart != -1) {
                    rawPath = rawPath.substring(0, queryStart)
                }
                path =
                    if (rawPath.isEmpty())
                        ""
                    else
                        URI.create(url.getScheme() + ":" + rawPath).getSchemeSpecificPart()
            }
            return if (path == null) "" else PathFragment.create(path).getBaseName()
        }
    }
}
