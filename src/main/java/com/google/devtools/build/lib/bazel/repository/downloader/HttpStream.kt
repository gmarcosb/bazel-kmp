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

import com.google.common.base.Splitter
import com.google.common.base.Strings
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Iterables
import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.bazel.repository.downloader.RetryingInputStream.Reconnector
import com.google.devtools.build.lib.concurrent.ThreadSafety
import java.io.*
import java.lang.Long
import java.net.URI
import java.net.URLConnection
import java.util.*
import java.util.zip.GZIPInputStream
import javax.annotation.WillCloseWhenClosed
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Exception
import kotlin.Int
import kotlin.NumberFormatException
import kotlin.String

/**
 * Input stream that validates checksum resumes downloads on error.
 * 
 * 
 * This class is not thread safe, but it is safe to message pass its objects between threads.
 */
@ThreadSafety.ThreadCompatible
internal class HttpStream(
    @WillCloseWhenClosed delegate: InputStream?,
    /** Returns final redirected URI.  */
    val url: URI?
) : FilterInputStream(delegate) {
    /** Factory for [HttpStream].  */
    @ThreadSafety.ThreadSafe
    internal class Factory(private val progressInputStreamFactory: ProgressInputStream.Factory) {
        @kotlin.jvm.JvmOverloads
        @Throws(IOException::class)
        fun create(
            @WillCloseWhenClosed connection: URLConnection,
            originalUrl: URI,
            checksum: Optional<Checksum?>,
            reconnector: Reconnector?,
            type: Optional<String> = Optional.empty<String?>()
        ): HttpStream {
            var stream: InputStream = InterruptibleInputStream(connection.getInputStream())
            val connectionUrl = HttpUtils.toUri(connection)
            try {
                // If server supports range requests, we can retry on read errors. See RFC7233 § 2.3.
                var retrier: RetryingInputStream = null
                if (Iterables.contains(
                        Splitter.on(',')
                            .trimResults()
                            .split(Strings.nullToEmpty(connection.getHeaderField("Accept-Ranges"))),
                        "bytes"
                    )
                ) {
                    retrier = RetryingInputStream(stream, reconnector)
                    stream = retrier
                }

                var totalBytes = OptionalLong.empty()
                try {
                    val contentLength = connection.getHeaderField("Content-Length")
                    if (contentLength != null) {
                        totalBytes = OptionalLong.of(Long.parseUnsignedLong(contentLength))
                        stream = CheckContentLengthInputStream(stream, totalBytes.getAsLong())
                    }
                } catch (ignored: NumberFormatException) {
                    // ignored
                }

                stream = progressInputStreamFactory.create(stream, connectionUrl, originalUrl, totalBytes)

                // Determine if we need to transparently gunzip. See RFC2616 § 3.5 and § 14.11. Please note
                // that some web servers will send Content-Encoding: gzip even when we didn't request it if
                // the file is a .gz file. Therefore we take the type parameter from the rule http_archive
                // in consideration. If the repository/file that we are downloading is already compressed we
                // should not decompress it to preserve the desired file format.
                if (GZIP_CONTENT_ENCODING.contains(Strings.nullToEmpty(connection.getContentEncoding()))
                    && !GZIPPED_EXTENSIONS.contains(HttpUtils.getExtension(connectionUrl.getPath())) && !GZIPPED_EXTENSIONS.contains(
                        HttpUtils.getExtension(originalUrl.getPath())
                    ) && !typeIsGZIP(type)
                ) {
                    stream = GZIPInputStream(stream, GZIP_BUFFER_BYTES)
                }

                if (checksum.isPresent()) {
                    stream = HashInputStream(stream, checksum.get())
                    val buffer = ByteArray(PRECHECK_BYTES)
                    var read = 0
                    while (read < PRECHECK_BYTES) {
                        val amount: Int
                        amount = stream.read(buffer, read, PRECHECK_BYTES - read)
                        if (amount == -1) {
                            break
                        }
                        read += amount
                    }
                    if (read < PRECHECK_BYTES) {
                        stream.close()
                        stream = ByteStreams.limit(ByteArrayInputStream(buffer), read.toLong())
                    } else {
                        stream = SequenceInputStream(ByteArrayInputStream(buffer), stream)
                    }
                }
            } catch (e: Exception) {
                try {
                    stream.close()
                } catch (e2: IOException) {
                    e.addSuppressed(e2)
                }
                throw e
            }
            return HttpStream(stream, connectionUrl)
        }

        companion object {
            /**
             * Checks if the given type is GZIP
             * 
             * @param type extension, e.g. "tar.gz"
             * @return whether the type is GZIP or not
             */
            private fun typeIsGZIP(type: Optional<String>): Boolean {
                if (type.isPresent()) {
                    var t = type.get()

                    if (t.contains(".")) {
                        // We only want to look at the last extension.
                        t = HttpUtils.getExtension(t)
                    }

                    return GZIPPED_EXTENSIONS.contains(t)
                }
                return false
            }
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        val PRECHECK_BYTES: Int = 32 * 1024
        private const val GZIP_BUFFER_BYTES = 8192 // same as ByteStreams#copy
        private val GZIPPED_EXTENSIONS: ImmutableSet<String?> = ImmutableSet.of<String?>("gz", "tgz")
        private val GZIP_CONTENT_ENCODING: ImmutableSet<String?> = ImmutableSet.of<String?>("gzip", "x-gzip")
    }
}
