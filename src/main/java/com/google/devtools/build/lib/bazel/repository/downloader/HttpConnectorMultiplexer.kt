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
import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.analysis.BlazeVersionInfo
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.EventHandler
import java.lang.String
import java.net.URI
import java.util.*
import kotlin.IllegalArgumentException
import kotlin.Throwable

/**
 * Class for establishing HTTP connections.
 * 
 * 
 * This is the most amazing way to download files ever. It makes Bazel builds as reliable as
 * Blaze builds in Google's internal hermetically sealed repository. But this class isn't just
 * reliable. It's also fast. It even works on the worst Internet connections in the farthest corners
 * of the Earth. You are just not going to believe how fast and reliable this design is. It's
 * incredible. Your builds are never going to break again due to downloads. You're going to be so
 * happy. Your developer community is going to be happy. Mr. Jenkins will be happy too. Everyone is
 * going to have such a magnificent developer experience due to the product excellence of this
 * class.
 */
@ThreadSafety.ThreadSafe
internal class HttpConnectorMultiplexer
/**
 * Creates a new instance.
 * 
 * 
 * Instances are thread safe and can be reused.
 */(
    private val eventHandler: EventHandler,
    private val connector: HttpConnector,
    private val httpStreamFactory: HttpStream.Factory
) {
    /**
     * Establishes reliable HTTP connection to a URL.
     * 
     * 
     * This routine supports HTTP redirects in an RFC compliant manner. It requests gzip content
     * encoding when appropriate in order to minimize bandwidth consumption when downloading
     * uncompressed files. It reports download progress. It enforces a SHA-256 checksum which
     * continues to be enforced even after this method returns.
     * 
     * @param url the URI to connect to. can be: file, http, or https
     * @param checksum checksum lazily checked on entire payload, or empty to disable
     * @param credentials the credentials
     * @param type extension, e.g. "tar.gz" to force on downloaded filename, or empty to not do this
     * @return an [InputStream] of response payload
     * @throws IOException if all mirrors are down and contains suppressed exception of each attempt
     * @throws InterruptedIOException if current thread is being cast into oblivion
     * @throws IllegalArgumentException if `urls` is empty or has an unsupported protocol
     */
    @kotlin.jvm.JvmOverloads
    @Throws(IOException::class)
    fun connect(
        url: URI?,
        checksum: Optional<Checksum?>?,
        headers: MutableMap<String?, MutableList<String?>?> = ImmutableMap.of<String?, MutableList<String?>?>(),
        credentials: Credentials? = StaticCredentials.EMPTY,
        type: Optional<String?>? = Optional.empty<String?>()
    ): HttpStream? {
        Preconditions.checkArgument(HttpUtils.isUrlSupportedByDownloader(url))
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
        val baseHeaders = ImmutableMap.Builder<String?, MutableList<String?>?>()
        baseHeaders.putAll(headers)
        // REQUEST_HEADERS should not be overridable by user provided headers
        baseHeaders.putAll(REQUEST_HEADERS)

        val headerFunction: Function<URI?, ImmutableMap<String?, MutableList<String?>?>?> =
            getHeaderFunction(baseHeaders.buildKeepingLast(), credentials, eventHandler)
        val connection = connector.connect(url, headerFunction)
        return httpStreamFactory.create(
            connection,
            url,
            checksum,
            Reconnector { cause: Throwable?, extraHeaders: ImmutableMap<String?, MutableList<String?>?>? ->
                eventHandler.handle(
                    Event.progress(String.format("Lost connection for %s due to %s", url, cause))
                )
                connector.connect(
                    HttpUtils.toUri(connection),
                    Function { newUrl: URI? ->
                        ImmutableMap.Builder<kotlin.String?, MutableList<kotlin.String?>?>()
                            .putAll(headerFunction.apply(newUrl))
                            .putAll(extraHeaders)
                            .buildOrThrow()
                    })
            },
            type
        )
    }

    companion object {
        private val REQUEST_HEADERS: ImmutableMap<kotlin.String?, MutableList<kotlin.String?>?> =
            ImmutableMap.of<kotlin.String?, MutableList<kotlin.String?>?>(
                "Accept-Encoding",
                ImmutableList.of<kotlin.String?>("gzip"),
                "User-Agent",
                ImmutableList.of<kotlin.String?>("Bazel/" + BlazeVersionInfo.instance().getReleaseName())
            )

        @VisibleForTesting
        fun getHeaderFunction(
            baseHeaders: MutableMap<kotlin.String?, MutableList<kotlin.String?>?>?,
            credentials: Credentials?,
            eventHandler: EventHandler
        ): Function<URI?, ImmutableMap<kotlin.String?, MutableList<kotlin.String?>?>?> {
            Preconditions.checkNotNull<MutableMap<kotlin.String?, MutableList<kotlin.String?>?>?>(baseHeaders)
            Preconditions.checkNotNull<Credentials?>(credentials)

            return Function { url: URI? ->
                val headers = ImmutableMap.Builder<kotlin.String?, MutableList<kotlin.String?>?>()
                headers.putAll(baseHeaders)
                try {
                    headers.putAll(credentials!!.getRequestMetadata(url))
                } catch (e: IOException) {
                    // If fetching credentials fails for any reason, still try to do the connection, not adding
                    // authentication information as we cannot look it up.
                    eventHandler.handle(
                        Event.warn("Error retrieving auth headers, continuing without: " + e.getMessage())
                    )
                }
                headers.buildKeepingLast()
            }
        }
    }
}
