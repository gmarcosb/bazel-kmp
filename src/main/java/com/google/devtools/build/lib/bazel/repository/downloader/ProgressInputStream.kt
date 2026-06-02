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

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.clock.Clock
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.ExtendedEventHandler
import java.io.IOException
import java.io.InputStream
import java.lang.String
import java.net.URI
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import javax.annotation.WillCloseWhenClosed
import kotlin.ByteArray
import kotlin.Int
import kotlin.Long

/**
 * Input stream that reports progress on total bytes read as the download progresses.
 * 
 * 
 * This class is not thread safe, but it is safe to message pass its objects between threads.
 */
@ThreadSafety.ThreadCompatible
internal class ProgressInputStream(
    locale: Locale?,
    clock: Clock,
    eventHandler: ExtendedEventHandler,
    intervalMs: Long,
    delegate: InputStream,
    url: URI,
    originalUrl: URI,
    totalBytes: OptionalLong?
) : InputStream() {
    /** Factory for [ProgressInputStream].  */
    @ThreadSafety.ThreadSafe
    internal class Factory(
        private val locale: Locale?,
        private val clock: Clock,
        private val eventHandler: ExtendedEventHandler
    ) {
        fun create(
            @WillCloseWhenClosed delegate: InputStream,
            url: URI,
            originalUrl: URI,
            totalBytes: OptionalLong?
        ): InputStream {
            return ProgressInputStream(
                locale,
                clock,
                eventHandler,
                PROGRESS_INTERVAL_MS,
                delegate,
                url,
                originalUrl,
                totalBytes
            )
        }
    }

    private val locale: Locale?
    private val clock: Clock
    private val eventHandler: ExtendedEventHandler
    private val delegate: InputStream
    private val intervalMs: Long
    private val url: URI
    private val originalUrl: URI
    private val totalBytes: OptionalLong?
    private val toto = AtomicLong()
    private val nextEvent: AtomicLong

    init {
        Preconditions.checkArgument(intervalMs >= 0)
        this.locale = locale
        this.clock = clock
        this.eventHandler = eventHandler
        this.intervalMs = intervalMs
        this.delegate = delegate
        this.url = url
        this.originalUrl = originalUrl
        this.totalBytes = totalBytes
        this.nextEvent = AtomicLong(clock.currentTimeMillis() + intervalMs)
        eventHandler.post(DownloadProgressEvent(originalUrl, url, 0, totalBytes, false))
    }

    @Throws(IOException::class)
    override fun read(): Int {
        val result = delegate.read()
        if (result != -1) {
            reportProgress(toto.incrementAndGet())
        }
        return result
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray?, offset: Int, length: Int): Int {
        val amount = delegate.read(buffer, offset, length)
        if (amount > 0) {
            reportProgress(toto.addAndGet(amount.toLong()))
        }
        return amount
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return delegate.available()
    }

    @Throws(IOException::class)
    override fun close() {
        delegate.close()
        eventHandler.post(DownloadProgressEvent(originalUrl, url, toto.get(), totalBytes, true))
    }

    private fun reportProgress(bytesRead: Long) {
        val now = clock.currentTimeMillis()
        if (now < nextEvent.get()) {
            return
        }
        var via = ""
        if (url.getHost() != null && url.getHost() != originalUrl.getHost()) {
            via = " via " + url.getHost()
        }
        eventHandler.post(DownloadProgressEvent(originalUrl, url, bytesRead, totalBytes, false))
        eventHandler.handle(
            Event.progress(
                String.format(locale, "Downloading %s%s: %,d bytes", originalUrl, via, bytesRead)
            )
        )
        nextEvent.set(now + intervalMs)
    }

    companion object {
        private const val PROGRESS_INTERVAL_MS: Long = 200
    }
}
