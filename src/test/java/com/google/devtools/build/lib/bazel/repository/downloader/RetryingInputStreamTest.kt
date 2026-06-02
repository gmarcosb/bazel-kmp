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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth
import com.google.devtools.build.lib.bazel.repository.downloader.RetryingInputStream.Reconnector
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URLConnection

/** Unit tests for [RetryingInputStream].  */
@RunWith(JUnit4::class)
class RetryingInputStreamTest {
    private val delegate: InputStream = Mockito.mock<InputStream>(InputStream::class.java)
    private val newDelegate: InputStream = Mockito.mock<InputStream>(InputStream::class.java)
    private val reconnector: Reconnector = Mockito.mock<Reconnector>(Reconnector::class.java)
    private val connection: URLConnection? = Mockito.mock<URLConnection?>(URLConnection::class.java)
    private val stream = RetryingInputStream(delegate, reconnector)

    @After
    @Throws(Exception::class)
    fun after() {
        Mockito.verifyNoMoreInteractions(delegate, newDelegate, reconnector)
    }

    @Test
    @Throws(Exception::class)
    fun close_callsDelegate() {
        stream.close()
        Mockito.verify<InputStream?>(delegate).close()
    }

    @Test
    @Throws(Exception::class)
    fun available_callsDelegate() {
        stream.available()
        Mockito.verify<InputStream?>(delegate).available()
    }

    @Test
    @Throws(Exception::class)
    fun read_callsdelegate() {
        stream.read()
        Mockito.verify<InputStream?>(delegate).read()
    }

    @Test
    @Throws(Exception::class)
    fun bufferRead_callsdelegate() {
        val buffer = ByteArray(1024)
        stream.read(buffer)
        Mockito.verify<InputStream?>(delegate)
            .read(ArgumentMatchers.same<ByteArray?>(buffer), ArgumentMatchers.eq(0), ArgumentMatchers.eq(1024))
    }

    @Test
    @Throws(Exception::class)
    fun readInterrupted_alwaysPassesThrough() {
        Mockito.`when`<Int?>(delegate.read()).thenThrow(InterruptedIOException())
        Assert.assertThrows<InterruptedIOException?>(
            InterruptedIOException::class.java,
            ThrowingRunnable { stream.read() })
        Mockito.verify<InputStream?>(delegate).read()
    }

    @Test
    @Throws(Exception::class)
    fun readTimesOut_retries() {
        Mockito.`when`<Int?>(delegate.read()).thenReturn(1).thenThrow(SocketTimeoutException())
        Mockito.`when`<URLConnection>(
            reconnector.connect(
                ArgumentMatchers.any<Throwable?>(Throwable::class.java), ArgumentMatchers.any<ImmutableMap<*, *>?>(
                    ImmutableMap::class.java
                )
            )
        ).thenReturn(connection)
        Mockito.`when`<InputStream?>(connection!!.getInputStream()).thenReturn(newDelegate)
        Mockito.`when`<Int?>(newDelegate.read()).thenReturn(2)
        Mockito.`when`<String?>(connection.getHeaderField("Content-Range")).thenReturn("bytes 1-42/42")
        Truth.assertThat(stream.read()).isEqualTo(1)
        Truth.assertThat(stream.read()).isEqualTo(2)
        Mockito.verify<Reconnector?>(reconnector)
            .connect(
                ArgumentMatchers.any<Throwable?>(Throwable::class.java),
                ArgumentMatchers.eq<ImmutableMap<String?, MutableList<String?>?>?>(
                    ImmutableMap.of<String?, MutableList<String?>?>("Range", ImmutableList.of<String?>("bytes=1-"))
                )
            )
        Mockito.verify<InputStream?>(delegate, Mockito.times(2)).read()
        Mockito.verify<InputStream?>(delegate).close()
        Mockito.verify<InputStream?>(newDelegate).read()
    }

    @Test
    @Throws(Exception::class)
    fun failureWhenNoBytesAreRead_doesntUseRange() {
        Mockito.`when`<Int?>(delegate.read()).thenThrow(SocketTimeoutException())
        Mockito.`when`<Int?>(newDelegate.read()).thenReturn(1)
        Mockito.`when`<URLConnection>(
            reconnector.connect(
                ArgumentMatchers.any<Throwable?>(Throwable::class.java), ArgumentMatchers.any<ImmutableMap<*, *>?>(
                    ImmutableMap::class.java
                )
            )
        ).thenReturn(connection)
        Mockito.`when`<InputStream?>(connection!!.getInputStream()).thenReturn(newDelegate)
        Truth.assertThat(stream.read()).isEqualTo(1)
        Mockito.verify<Reconnector?>(reconnector).connect(
            ArgumentMatchers.any<Throwable?>(Throwable::class.java),
            ArgumentMatchers.eq<ImmutableMap<String?, MutableList<String?>?>?>(
                ImmutableMap.of<String?, MutableList<String?>?>()
            )
        )
        Mockito.verify<InputStream?>(delegate).read()
        Mockito.verify<InputStream?>(delegate).close()
        Mockito.verify<InputStream?>(newDelegate).read()
    }

    @Test
    @Throws(Exception::class)
    fun reconnectFails_alwaysPassesThrough() {
        Mockito.`when`<Int?>(delegate.read()).thenThrow(IOException())
        Mockito.`when`<URLConnection>(
            reconnector.connect(
                ArgumentMatchers.any<Throwable?>(Throwable::class.java), ArgumentMatchers.any<ImmutableMap<*, *>?>(
                    ImmutableMap::class.java
                )
            )
        )
            .thenThrow(IOException())
        Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { stream.read() })
        Mockito.verify<InputStream?>(delegate).read()
        Mockito.verify<InputStream?>(delegate).close()
        Mockito.verify<Reconnector?>(reconnector).connect(
            ArgumentMatchers.any<Throwable?>(Throwable::class.java), ArgumentMatchers.any<ImmutableMap<*, *>?>(
                ImmutableMap::class.java
            )
        )
    }

    @Test
    @Throws(Exception::class)
    fun maxRetries_givesUp() {
        Mockito.`when`<Int?>(delegate.read())
            .thenReturn(1)
            .thenThrow(IOException())
            .thenThrow(IOException())
            .thenThrow(IOException())
            .thenThrow(SocketTimeoutException())
        Mockito.`when`<URLConnection>(
            reconnector.connect(
                ArgumentMatchers.any<Throwable?>(Throwable::class.java), ArgumentMatchers.any<ImmutableMap<*, *>?>(
                    ImmutableMap::class.java
                )
            )
        ).thenReturn(connection)
        Mockito.`when`<InputStream?>(connection!!.getInputStream()).thenReturn(delegate)
        Mockito.`when`<String?>(connection.getHeaderField("Content-Range")).thenReturn("bytes 1-42/42")
        stream.read()
        val e = Assert.assertThrows<SocketTimeoutException>(
            SocketTimeoutException::class.java,
            ThrowingRunnable { stream.read() })
        Truth.assertThat<Throwable?>(e.getSuppressed()).hasLength(3)
        Mockito.verify<Reconnector?>(reconnector, Mockito.times(3))
            .connect(
                ArgumentMatchers.any<Throwable?>(Throwable::class.java),
                ArgumentMatchers.eq<ImmutableMap<String?, MutableList<String?>?>?>(
                    ImmutableMap.of<String?, MutableList<String?>?>("Range", ImmutableList.of<String?>("bytes=1-"))
                )
            )
        Mockito.verify<InputStream?>(delegate, Mockito.times(5)).read()
        Mockito.verify<InputStream?>(delegate, Mockito.times(3)).close()
    }
}
