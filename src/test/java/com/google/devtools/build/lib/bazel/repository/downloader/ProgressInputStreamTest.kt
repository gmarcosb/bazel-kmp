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

import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.EventBusEventHandler
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.build.lib.events.Reporter
import com.google.devtools.build.lib.testutil.ManualClock
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.InputStream
import java.net.URI

/** Unit tests for [ProgressInputStream].  */
@RunWith(JUnit4::class)
class ProgressInputStreamTest {
    private val clock = ManualClock()
    private val eventHandler: EventHandler? = Mockito.mock<EventHandler?>(EventHandler::class.java)
    private val extendedEventHandler: ExtendedEventHandler =
        Reporter(EventBusEventHandler.createWithNewEventBus(), eventHandler)
    private val delegate: InputStream = Mockito.mock<InputStream>(InputStream::class.java)
    private val url: URI = URI.create("http://lol.example")
    private var stream = ProgressInputStream(
        Locale.US, clock, extendedEventHandler, 1, delegate, url, url, OptionalLong.empty()
    )

    @After
    @Throws(Exception::class)
    fun after() {
        Mockito.verifyNoMoreInteractions(eventHandler, delegate)
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
    fun readThrowsException_passesThrough() {
        Mockito.`when`<Int?>(delegate.read()).thenThrow(IOException())
        Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { stream.read() })
        Mockito.verify<InputStream?>(delegate).read()
    }

    @Test
    @Throws(Exception::class)
    fun readsAfterInterval_emitsProgressOnce() {
        Mockito.`when`<Int?>(delegate.read()).thenReturn(42)
        Truth.assertThat(stream.read()).isEqualTo(42)
        clock.advanceMillis(1)
        Truth.assertThat(stream.read()).isEqualTo(42)
        Truth.assertThat(stream.read()).isEqualTo(42)
        Mockito.verify<InputStream?>(delegate, Mockito.times(3)).read()
        Mockito.verify<EventHandler?>(eventHandler).handle(Event.progress("Downloading http://lol.example: 2 bytes"))
    }

    @Test
    @Throws(Exception::class)
    fun multipleIntervalsElapsed_showsMultipleProgress() {
        stream.read()
        stream.read()
        clock.advanceMillis(1)
        stream.read()
        stream.read()
        clock.advanceMillis(1)
        stream.read()
        stream.read()
        Mockito.verify<InputStream?>(delegate, Mockito.times(6)).read()
        Mockito.verify<EventHandler?>(eventHandler).handle(Event.progress("Downloading http://lol.example: 3 bytes"))
        Mockito.verify<EventHandler?>(eventHandler).handle(Event.progress("Downloading http://lol.example: 5 bytes"))
    }

    @Test
    @Throws(Exception::class)
    fun bufferReadsAfterInterval_emitsProgressOnce() {
        val buffer = ByteArray(1024)
        Mockito.`when`<Int?>(
            delegate.read(
                ArgumentMatchers.any<ByteArray?>(ByteArray::class.java),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyInt()
            )
        ).thenReturn(1024)
        Truth.assertThat(stream.read(buffer)).isEqualTo(1024)
        clock.advanceMillis(1)
        Truth.assertThat(stream.read(buffer)).isEqualTo(1024)
        Truth.assertThat(stream.read(buffer)).isEqualTo(1024)
        Mockito.verify<InputStream?>(delegate, Mockito.times(3))
            .read(ArgumentMatchers.same<ByteArray?>(buffer), ArgumentMatchers.eq(0), ArgumentMatchers.eq(1024))
        Mockito.verify<EventHandler?>(eventHandler)
            .handle(Event.progress("Downloading http://lol.example: 2,048 bytes"))
    }

    @Test
    @Throws(Exception::class)
    fun bufferReadsAfterIntervalInGermany_usesPeriodAsSeparator() {
        stream =
            ProgressInputStream(
                Locale.GERMANY,
                clock,
                extendedEventHandler,
                1,
                delegate,
                url,
                url,
                OptionalLong.empty()
            )
        val buffer = ByteArray(1024)
        Mockito.`when`<Int?>(
            delegate.read(
                ArgumentMatchers.any<ByteArray?>(ByteArray::class.java),
                ArgumentMatchers.anyInt(),
                ArgumentMatchers.anyInt()
            )
        ).thenReturn(1024)
        clock.advanceMillis(1)
        stream.read(buffer)
        Mockito.verify<InputStream?>(delegate)
            .read(ArgumentMatchers.same<ByteArray?>(buffer), ArgumentMatchers.eq(0), ArgumentMatchers.eq(1024))
        Mockito.verify<EventHandler?>(eventHandler)
            .handle(Event.progress("Downloading http://lol.example: 1.024 bytes"))
    }

    @Test
    @Throws(Exception::class)
    fun redirectedToDifferentServer_showsOriginalUrlWithVia() {
        stream =
            ProgressInputStream(
                Locale.US,
                clock,
                extendedEventHandler,
                1,
                delegate,
                URI.create("http://cdn.example/foo"),
                url,
                OptionalLong.empty()
            )
        Mockito.`when`<Int?>(delegate.read()).thenReturn(42)
        Truth.assertThat(stream.read()).isEqualTo(42)
        clock.advanceMillis(1)
        Truth.assertThat(stream.read()).isEqualTo(42)
        Truth.assertThat(stream.read()).isEqualTo(42)
        Mockito.verify<InputStream?>(delegate, Mockito.times(3)).read()
        Mockito.verify<EventHandler?>(eventHandler)
            .handle(Event.progress("Downloading http://lol.example via cdn.example: 2 bytes"))
    }

    @Test
    fun percentualProgress() {
        val event =
            DownloadProgressEvent(
                url, url, (25 * 1024 * 1024).toLong(), OptionalLong.of((100 * 1024 * 1024).toLong()), false
            )
        Truth.assertThat(event.getProgress()).isEqualTo("25.0 MiB (25.0%)")
    }

    @Test
    fun percentualProgress_zeroTotalBytes() {
        val event =
            DownloadProgressEvent(url, url, (25 * 1024 * 1024).toLong(), OptionalLong.of(0), false)
        Truth.assertThat(event.getProgress()).isEqualTo("25.0 MiB (100.0%)")
    }
}
