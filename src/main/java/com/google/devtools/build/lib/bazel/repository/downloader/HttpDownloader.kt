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
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Iterables
import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildEventId.FetchId
import com.google.devtools.build.lib.buildeventstream.FetchEvent
import com.google.devtools.build.lib.clock.Clock
import com.google.devtools.build.lib.clock.JavaClock
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.ExtendedEventHandler
import com.google.devtools.build.lib.util.Sleeper
import com.google.devtools.build.lib.vfs.Path
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration
import java.util.Optional
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/**
 * HTTP implementation of [Downloader].
 * 
 * 
 * This class uses [HttpConnectorMultiplexer] to connect to HTTP mirrors and then reads the
 * file to disk.
 * 
 * 
 * This class is (outside of tests) a singleton instance, living in `BazelRepositoryModule`.
 */
class HttpDownloader @kotlin.jvm.JvmOverloads constructor(
    private val maxAttempts: Int = 0,
    private val maxRetryTimeout: Duration? = Duration.ZERO,
    maxParallelDownloads: Int = 8,
    timeoutScaling: Float = 1.0f
) : Downloader {
    private val semaphore: Semaphore
    private val timeoutScaling: Float

    init {
        semaphore = Semaphore(maxParallelDownloads, true)
        this.timeoutScaling = timeoutScaling
    }

    @Throws(IOException::class, InterruptedException::class)
    override fun download(
        urls: MutableList<URI>,
        headers: MutableMap<String?, MutableList<String?>?>?,
        credentials: Credentials?,
        checksum: Optional<Checksum?>?,
        canonicalId: String?,
        destination: Path,
        eventHandler: ExtendedEventHandler,
        clientEnv: MutableMap<String?, String?>?,
        type: Optional<String?>?,
        context: String?
    ) {
        val multiplexer = setUpConnectorMultiplexer(eventHandler, clientEnv)

        // Iterate over urls and download the file falling back to the next url if previous failed,
        // while reporting progress to the CLI.
        var success = false

        var ioExceptions: MutableList<IOException> = ImmutableList.of<IOException?>()

        for (url in urls) {
            semaphore.acquire()

            try {
                multiplexer.connect(url, checksum, headers, credentials, type).use { payload ->
                    destination.getOutputStream().use { out ->
                        try {
                            ByteStreams.copy(payload, out)
                        } catch (e: SocketTimeoutException) {
                            // SocketTimeoutExceptions are InterruptedIOExceptions; however they do not signify
                            // an external interruption, but simply a failed download due to some server timing
                            // out. So rethrow them as ordinary IOExceptions.
                            throw IOException(e)
                        }
                        success = true
                        break
                    }
                }
            } catch (e: InterruptedIOException) {
                throw InterruptedException(e.getMessage())
            } catch (e: IOException) {
                if (ioExceptions.isEmpty()) {
                    ioExceptions = ArrayList<IOException>(1)
                }
                ioExceptions.add(e)
                eventHandler.handle(
                    Event.warn("Download from " + url + " failed: " + e.getClass() + " " + e.getMessage())
                )
                continue
            } finally {
                semaphore.release()
                eventHandler.post(FetchEvent(url.toString(), FetchId.Downloader.HTTP, success))
            }
        }

        if (!success) {
            val exception: IOException =
                IOException(
                    ("Error downloading "
                            + urls
                            + " to "
                            + destination
                            + (if (ioExceptions.isEmpty())
                        ""
                    else
                        ": " + Iterables.getLast<IOException?>(ioExceptions).getMessage()))
                )

            for (cause in ioExceptions) {
                exception.addSuppressed(cause)
            }

            throw exception
        }
    }

    /** Downloads the contents of one URL and reads it into a byte array.  */
    @Throws(IOException::class, InterruptedException::class)
    fun downloadAndReadOneUrl(
        url: URI?,
        credentials: Credentials?,
        checksum: Optional<Checksum?>?,
        eventHandler: ExtendedEventHandler?,
        clientEnv: MutableMap<String?, String?>?
    ): ByteArray? {
        val multiplexer = setUpConnectorMultiplexer(eventHandler, clientEnv)

        val out = ByteArrayOutputStream()
        semaphore.acquire()
        try {
            multiplexer.connect(
                url,
                checksum,
                ImmutableMap.of<String?, MutableList<String?>?>(),
                credentials,
                Optional.empty<String?>()
            ).use { payload ->
                ByteStreams.copy(payload, out)
            }
        } catch (e: SocketTimeoutException) {
            // SocketTimeoutExceptions are InterruptedIOExceptions; however they do not signify
            // an external interruption, but simply a failed download due to some server timing
            // out. So rethrow them as ordinary IOExceptions.
            throw IOException(e)
        } catch (e: InterruptedIOException) {
            throw InterruptedException(e.getMessage())
        } finally {
            semaphore.release()
            // TODO(wyv): Do we need to report any event here?
        }
        return out.toByteArray()
    }

    private fun setUpConnectorMultiplexer(
        eventHandler: ExtendedEventHandler?, clientEnv: MutableMap<String?, String?>?
    ): HttpConnectorMultiplexer {
        val proxyHelper = ProxyHelper(clientEnv)
        val connector =
            HttpConnector(
                LOCALE,
                eventHandler,
                proxyHelper,
                SLEEPER,
                timeoutScaling,
                maxAttempts,
                maxRetryTimeout
            )
        val progressInputStreamFactory =
            ProgressInputStream.Factory(LOCALE, CLOCK, eventHandler)
        val httpStreamFactory = HttpStream.Factory(progressInputStreamFactory)
        return HttpConnectorMultiplexer(eventHandler, connector, httpStreamFactory)
    }

    companion object {
        private val CLOCK: Clock = JavaClock()
        private val SLEEPER: Sleeper = JavaSleeper()
        private val LOCALE: Locale? = Locale.getDefault()
    }
}
