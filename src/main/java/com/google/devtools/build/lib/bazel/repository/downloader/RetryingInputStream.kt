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

import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.concurrent.ThreadSafety
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.lang.String
import java.net.SocketTimeoutException
import java.net.URLConnection
import kotlin.ByteArray
import kotlin.Exception
import kotlin.Int
import kotlin.Long
import kotlin.Throwable
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * Input stream that reconnects on read timeouts and errors.
 * 
 * 
 * This class is not thread safe, but it is safe to message pass between threads.
 */
@ThreadSafety.ThreadCompatible
internal class RetryingInputStream(private var delegate: InputStream, private val reconnector: Reconnector) :
    InputStream() {
    /** Lambda for establishing a connection.  */
    internal interface Reconnector {
        /** Establishes a connection with the same parameters as what was passed to us initially.  */
        @Throws(IOException::class)
        fun connect(cause: Throwable?, extraHeaders: ImmutableMap<String?, MutableList<String?>?>?): URLConnection
    }

    private var toto: Long = 0
    private var resumes = 0
    private val suppressed = ArrayList<Throwable>()

    @Throws(IOException::class)
    override fun read(): Int {
        while (true) {
            try {
                val result = delegate.read()
                if (result != -1) {
                    toto++
                }
                return result
            } catch (e: IOException) {
                tryAgainIfPossible(e)
            }
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray?, offset: Int, length: Int): Int {
        while (true) {
            try {
                val amount = delegate.read(buffer, offset, length)
                if (amount != -1) {
                    toto += amount.toLong()
                }
                return amount
            } catch (e: IOException) {
                tryAgainIfPossible(e)
            }
        }
    }

    @Throws(IOException::class)
    override fun available(): Int {
        return delegate.available()
    }

    @Throws(IOException::class)
    override fun close() {
        delegate.close()
    }

    @Throws(IOException::class)
    private fun tryAgainIfPossible(cause: IOException?) {
        if (cause is InterruptedIOException && cause !is SocketTimeoutException) {
            throw cause
        }
        if (resumes >= MAX_RESUMES) {
            propagate<IOException?>(cause)
        }
        resumes++
        try {
            delegate.close()
        } catch (ignored: Exception) {
            // We know this connection failed so if it reminds us we're going to ignore it.
        }
        suppressed.add(cause!!)
        reconnectWhereWeLeftOff(cause)
    }

    @Throws(IOException::class)
    private fun reconnectWhereWeLeftOff(cause: IOException?) {
        try {
            val connection: URLConnection
            val amountRead = toto
            if (amountRead == 0L) {
                connection = reconnector.connect(cause, ImmutableMap.of<String?, MutableList<String?>?>())
            } else {
                connection =
                    reconnector.connect(
                        cause,
                        ImmutableMap.of<String?, MutableList<String?>?>(
                            "Range",
                            ImmutableList.of<String?>(String.format("bytes=%d-", amountRead))
                        )
                    )
                if (!Strings.nullToEmpty(connection.getHeaderField("Content-Range"))
                        .startsWith(String.format("bytes %d-", amountRead))
                ) {
                    throw IOException(
                        String.format(
                            "Tried to reconnect at offset %,d but server didn't support it", amountRead
                        )
                    )
                }
            }
            delegate = InterruptibleInputStream(connection.getInputStream())
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: IOException) {
            propagate<IOException?>(e)
        }
    }

    @Throws(T::class)
    private fun <T : Throwable?> propagate(error: T?) {
        for (e in suppressed) {
            error!!.addSuppressed(e)
        }
        throw error
    }

    companion object {
        private const val MAX_RESUMES = 3
    }
}
