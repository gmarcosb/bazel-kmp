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

import com.google.common.base.Ascii
import com.google.common.base.Function
import com.google.common.base.Strings
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.analysis.BlazeVersionInfo
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.build.lib.util.Sleeper
import java.io.InputStream
import java.lang.String
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.net.UnknownHostException
import java.time.Duration
import javax.annotation.WillClose
import kotlin.Any
import kotlin.Float
import kotlin.IllegalArgumentException
import kotlin.Int
import kotlin.RuntimeException
import kotlin.Throwable
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * Class for establishing connections to HTTP servers for downloading files.
 * 
 * 
 * This class must be used in conjunction with [HttpConnectorMultiplexer].
 * 
 * 
 * Instances are thread safe and can be reused.
 */
@ThreadSafety.ThreadSafe
internal class HttpConnector @kotlin.jvm.JvmOverloads constructor(
    locale: Locale?,
    eventHandler: EventHandler,
    proxyHelper: ProxyHelper,
    sleeper: Sleeper,
    timeoutScaling: Float = 1.0f,
    maxAttempts: Int = 0,
    maxRetryTimeout: Duration = Duration.ZERO
) {
    private val locale: Locale?
    private val eventHandler: EventHandler
    private val proxyHelper: ProxyHelper
    private val sleeper: Sleeper
    private val timeoutScaling: Float
    private val maxAttempts: Int
    private val maxRetryTimeout: Duration

    init {
        this.locale = locale
        this.eventHandler = eventHandler
        this.proxyHelper = proxyHelper
        this.sleeper = sleeper
        this.timeoutScaling = timeoutScaling
        this.maxAttempts = if (maxAttempts > 0) maxAttempts else MAX_ATTEMPTS
        this.maxRetryTimeout = maxRetryTimeout
    }

    private fun scale(unscaled: Int): Int {
        return Math.round(unscaled * timeoutScaling)
    }

    @Throws(IOException::class)
    fun connect(
        originalUrl: URI, requestHeaders: Function<URI?, ImmutableMap<String?, MutableList<String?>?>?>
    ): URLConnection? {
        var originalUrl = originalUrl
        if (Thread.interrupted()) {
            throw InterruptedIOException()
        }
        var url = originalUrl
        if (HttpUtils.isProtocol(url, "file")) {
            return url.toURL().openConnection()
        }
        val suppressions: MutableList<Throwable> = ArrayList<Throwable>()
        var retries = 0
        var redirects = 0
        var connectTimeout = scale(MIN_CONNECT_TIMEOUT_MS)
        while (true) {
            var connection: HttpURLConnection? = null
            try {
                val proxyInfo = proxyHelper.createProxyIfNeeded(url)
                connection = url.toURL().openConnection(proxyInfo.proxy()) as HttpURLConnection?
                // For HTTP connections through authenticated proxies, set the Proxy-Authorization header.
                // For HTTPS, Java's HttpURLConnection handles CONNECT tunneling internally using the
                // Authenticator we set in ProxyHelper.
                if (proxyInfo.hasCredentials()) {
                    connection!!.setRequestProperty(
                        "Proxy-Authorization", proxyInfo.getProxyAuthorizationHeader()
                    )
                }
                val isAlreadyCompressed =
                    COMPRESSED_EXTENSIONS.contains(HttpUtils.getExtension(url.getPath()))
                            || COMPRESSED_EXTENSIONS.contains(HttpUtils.getExtension(originalUrl.getPath()))
                connection!!.setInstanceFollowRedirects(false)
                for (entry in requestHeaders.apply(url).entrySet()) {
                    if (isAlreadyCompressed && Ascii.equalsIgnoreCase(entry.getKey(), "Accept-Encoding")) {
                        // We're not going to ask for compression if we're downloading a file that already
                        // appears to be compressed.
                        continue
                    }
                    val key: String? = entry.getKey()
                    for (value in entry.getValue()) {
                        connection.addRequestProperty(key, value)
                    }
                }
                if (connection.getRequestProperty("User-Agent") == null) {
                    connection.setRequestProperty("User-Agent", USER_AGENT_VALUE)
                }
                connection.setConnectTimeout(connectTimeout)
                // The read timeout is always large because it stays in effect after this method.
                connection.setReadTimeout(scale(READ_TIMEOUT_MS))
                // Java tries to abstract HTTP error responses for us. We don't want that. So we're going
                // to try and undo any IOException that doesn't appear to be a legitimate I/O exception.
                var code: Int
                try {
                    connection.connect()
                    code = connection.getResponseCode()
                } catch (ignored: FileNotFoundException) {
                    code = connection.getResponseCode()
                } catch (e: SSLException) {
                    // Check if the exception is due to a permanent error, such as a certificate validation
                    // issue.
                    // These errors are unlikely to be resolved by retrying.
                    if (e.getMessage() != null
                        && (e.getMessage().contains("certificate")
                                || e.getMessage().contains("CertPathValidatorException"))
                    ) {
                        val message = "TLS error: " + e.getMessage()
                        eventHandler.handle(Event.progress(message))
                        val httpException: IOException = UnrecoverableHttpException(message)
                        httpException.addSuppressed(e)
                        throw httpException
                    }
                    // Otherwise, treat it as a potentially transient network error and let it fall through
                    // to the standard IOException handler for retries.
                    throw e
                } catch (e: UnknownHostException) {
                    val message = "Unknown host: " + e.getMessage()
                    eventHandler.handle(Event.progress(message))
                    val httpException: IOException = UnrecoverableHttpException(message)
                    httpException.addSuppressed(e)
                    throw httpException
                } catch (e: IllegalArgumentException) {
                    // This will happen if the user does something like specify a port greater than 2^16-1.
                    throw UnrecoverableHttpException(e.getMessage())
                } catch (e: IOException) {
                    // Some HTTP error status codes are converted to IOExceptions, which we can only
                    // disambiguate from other IOExceptions by checking the exception message. We need to be
                    // careful because some exceptions (e.g., SocketTimeoutException) may have a null message.
                    if (e.getMessage() == null || !e.getMessage().startsWith("Server returned")) {
                        throw e
                    }
                    code = connection.getResponseCode()
                }
                // 206 means partial content and only happens if caller specified Range. See RFC7233 § 4.1.
                if (code == 200 || code == 206) {
                    return connection
                } else if (code == 301 || code == 302 || code == 303 || code == 307) {
                    readAllBytesAndClose(connection.getInputStream())
                    if (++redirects == MAX_REDIRECTS) {
                        eventHandler.handle(Event.progress("Redirect loop detected in " + originalUrl))
                        throw UnrecoverableHttpException("Redirect loop detected")
                    }
                    url = HttpUtils.getLocation(connection)
                    if (code == 301) {
                        originalUrl = url
                    }
                } else if (code == 403) {
                    // jart@ has noticed BitBucket + Amazon AWS downloads frequently flake with this code.
                    throw IOException(describeHttpResponse(connection))
                } else if (code == 408) {
                    // The 408 (Request Timeout) status code indicates that the server did not receive a
                    // complete request message within the time that it was prepared to wait. Server SHOULD
                    // send the "close" connection option (Section 6.1 of [RFC7230]) in the response, since
                    // 408 implies that the server has decided to close the connection rather than continue
                    // waiting.  If the client has an outstanding request in transit, the client MAY repeat
                    // that request on a new connection. Quoth RFC7231 § 6.5.7
                    throw IOException(describeHttpResponse(connection))
                } else if (code == 429) {
                    // The 429 (Too Many Requests) status code could result from Bazel temporarily overloading
                    // the server and is typically resolved by retrying.
                    throw IOException(describeHttpResponse(connection))
                } else if (code < 500 // 4xx means client seems to have erred quoth RFC7231 § 6.5
                    || code == 501 // Server doesn't support function quoth RFC7231 § 6.6.2
                    || code == 505
                ) {  // Server refuses to support version quoth RFC7231 § 6.6.6
                    // This is a permanent error so we're not going to retry.
                    readAllBytesAndClose(connection.getErrorStream())
                    if (code == 404 || code == 410) {
                        // For Not Found, we throw a separate unrecoverable exception so that callers can
                        // distinguish between the resource being not found and the server being unavailable.
                        throw FileNotFoundException(describeHttpResponse(connection))
                    }
                    throw UnrecoverableHttpException(describeHttpResponse(connection))
                } else {
                    // However we will retry on some 5xx errors, particularly 500, 502 and 503.
                    throw IOException(describeHttpResponse(connection))
                }
            } catch (e: UnrecoverableHttpException) {
                throw e
            } catch (e: FileNotFoundException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw UnrecoverableHttpException(e.getMessage())
            } catch (e: IOException) {
                if (connection != null) {
                    // If we got here, it means we might not have consumed the entire payload of the
                    // response, if any. So we're going to force this socket to disconnect and not be
                    // reused. This is particularly important if multiple threads end up establishing
                    // connections to multiple mirrors simultaneously for a large file. We don't want to
                    // download that large file twice.
                    connection.disconnect()
                }
                // We don't respect the Retry-After header (RFC7231 § 7.1.3) because it's rarely used and
                // tends to be too conservative when it is. We're already being good citizens by using
                // exponential backoff with jitter. Furthermore RFC law didn't use the magic word "MUST".
                var rawTimeout = Math.scalb(MIN_RETRY_DELAY_MS.toDouble(), retries)
                if (!maxRetryTimeout.isZero()) {
                    rawTimeout = Math.min(rawTimeout, maxRetryTimeout.toMillis().toDouble())
                }
                var timeout = ((0.75 + Math.random() / 2) * rawTimeout).toInt()
                if (e is SocketTimeoutException) {
                    eventHandler.handle(Event.progress("Timeout connecting to " + url))
                    connectTimeout = Math.min(connectTimeout * 2, scale(MAX_CONNECT_TIMEOUT_MS))
                    // If we got connect timeout, we're already doing exponential backoff, so no point
                    // in sleeping too.
                    timeout = 1
                } else if (e is InterruptedIOException) {
                    // Please note that SocketTimeoutException is a subtype of InterruptedIOException.
                    throw e
                }
                if (++retries == maxAttempts) {
                    if (e is SocketTimeoutException) {
                        // SocketTimeoutExceptions are InterruptedIOExceptions; however they do not signify
                        // an external interruption, but simply a failed download due to some server timing
                        // out. So rethrow them as ordinary IOExceptions.
                        e = IOException(e.getMessage(), e)
                    } else {
                        eventHandler
                            .handle(Event.progress(format("Error connecting to %s: %s", url, e.getMessage())))
                    }
                    for (suppressed in suppressions) {
                        e.addSuppressed(suppressed)
                    }
                    throw e
                }
                // Java 7 allows us to create a tree of all errors that led to the ultimate failure.
                suppressions.add(e)
                eventHandler.handle(
                    Event.progress(format("Failed to connect to %s trying again in %,dms", url, timeout))
                )
                url = originalUrl
                try {
                    sleeper.sleepMillis(timeout.toLong())
                } catch (translated: InterruptedException) {
                    throw InterruptedIOException()
                }
            } catch (e: RuntimeException) {
                if (connection != null) {
                    connection.disconnect()
                }
                eventHandler.handle(Event.progress(format("Unknown error connecting to %s: %s", url, e)))
                throw e
            }
        }
    }

    @Throws(IOException::class)
    private fun describeHttpResponse(connection: HttpURLConnection): String? {
        return format(
            "%s returned %d %s",
            connection.getRequestMethod(),
            connection.getResponseCode(),
            Strings.nullToEmpty(connection.getResponseMessage())
        )
    }

    private fun format(format: String, vararg args: Any?): String? {
        return String.format(locale, format, *args)
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
        private const val MAX_REDIRECTS = 40
        private const val MIN_RETRY_DELAY_MS = 100
        private const val MIN_CONNECT_TIMEOUT_MS = 1000
        private const val MAX_CONNECT_TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 20000
        private val COMPRESSED_EXTENSIONS: ImmutableSet<kotlin.String?> =
            ImmutableSet.of<kotlin.String?>("bz2", "gz", "jar", "tgz", "war", "xz", "zip")
        private val USER_AGENT_VALUE = "bazel/" + BlazeVersionInfo.instance().getVersion()

        // Exhausts all bytes in an HTTP to make it easier for Java infrastructure to reuse sockets.
        @Throws(IOException::class)
        private fun readAllBytesAndClose(
            @WillClose stream: InputStream?
        ) {
            if (stream != null) {
                ByteStreams.exhaust(stream)
                stream.close()
            }
        }
    }
}
