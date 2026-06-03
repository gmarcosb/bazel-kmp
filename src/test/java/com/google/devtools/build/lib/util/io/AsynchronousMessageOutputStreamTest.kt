// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.io

import com.google.devtools.build.lib.runtime.commands.proto.BazelFlagsProto.FlagInfo
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ThreadLocalRandom
import kotlin.collections.ArrayList

/** Tests [AsynchronousMessageOutputStream].  */
@RunWith(JUnit4::class)
class AsynchronousMessageOutputStreamTest {
    private val random: Random = ThreadLocalRandom.current()

    @Before
    fun initMocks() {
        MockitoAnnotations.initMocks(this)
    }

    @After
    fun validateMocks() {
        Mockito.validateMockitoUsage()
    }

    private fun generateRandomMessage(): Message {
        val b: FlagInfo.Builder = FlagInfo.newBuilder()
        b.setName(generateRandomString() + "a") // Name is required, cannot be empty.
        b.setHasNegativeFlag(random.nextBoolean())
        b.setDocumentation(generateRandomString())
        val commandsSize = random.nextInt(5)
        for (i in 0..<commandsSize) {
            b.addCommands(generateRandomString())
        }
        return b.build()
    }

    private fun generateRandomString(): String {
        val len = random.nextInt(RAND_STRING_LENGTH + 1)
        val data = CharArray(len)
        for (i in 0..<len) {
            data[i] = RAND_CHARS[random.nextInt(RAND_CHARS.size)]
        }
        return String(data)
    }

    @Test
    @Throws(Exception::class)
    fun testConcurrentProtoWrites() {
        val filename = "/logFile"
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val logPath: Path? = fileSystem.getPath(filename)
        val out: AsynchronousMessageOutputStream<Message?> = AsynchronousMessageOutputStream(logPath)
        val messages: ArrayList<Message?> = ArrayList<Message?>()
        for (i in 0..99) {
            messages.add(generateRandomMessage())
        }
        val writers = arrayOfNulls<Thread>(messages.size / 10)
        val start: CountDownLatch = CountDownLatch(writers.size)
        for (i in writers.indices) {
            val startIndex: Int = i * 10
            val thread: Thread = object : Thread() {
                override fun run() {
                    try {
                        start.countDown()
                        start.await()
                    } catch (e: InterruptedException) {
                        return
                    }
                    for (j in startIndex..<startIndex + 10) {
                        out.write(messages.get(j))
                    }
                }
            }
            writers[i] = thread
            thread.start()
        }
        for (i in writers.indices) {
            writers[i]!!.join()
        }
        out.close()
        val readMessages: ArrayList<Message?> = ArrayList<Message?>()
        fileSystem.getPath(filename).getInputStream().use { `in` ->
            for (i in messages.indices) {
                readMessages.add(FlagInfo.parseDelimitedFrom(`in`))
            }
        }
        Truth.assertThat(readMessages).containsExactlyElementsIn(messages)
    }

    @Test
    @Throws(Exception::class)
    fun testFailedClosePropagatesIOException() {
        val failingOutputStream: OutputStream = object : OutputStream() {
            @Throws(IOException::class)
            override fun write(b: Int) {
            }

            @Throws(IOException::class)
            override fun close() {
                throw IOException("foo")
            }
        }
        val out: AsynchronousMessageOutputStream<Message?> =
            AsynchronousMessageOutputStream("", failingOutputStream)
        out.write(generateRandomMessage())
        val expected: IOException? =
            Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { out.close() })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("foo")
    }

    @Test
    @Throws(Exception::class)
    fun testFailedClosePropagatesUncheckedException() {
        val failingOutputStream: OutputStream = object : OutputStream() {
            @Throws(IOException::class)
            override fun write(b: Int) {
            }

            @Throws(IOException::class)
            override fun close() {
                throw RuntimeException("foo")
            }
        }
        val out: AsynchronousMessageOutputStream<Message?> =
            AsynchronousMessageOutputStream("", failingOutputStream)
        out.write(generateRandomMessage())
        val expected =
            Assert.assertThrows<RuntimeException?>(RuntimeException::class.java, ThrowingRunnable { out.close() })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("foo")
    }

    @Test
    @Throws(Exception::class)
    fun testFailedWritePropagatesIOException() {
        val failingOutputStream: OutputStream = object : OutputStream() {
            @Throws(IOException::class)
            override fun write(b: Int) {
                throw IOException("foo")
            }

            @Throws(IOException::class)
            override fun close() {
            }
        }
        val out: AsynchronousMessageOutputStream<Message?> =
            AsynchronousMessageOutputStream("", failingOutputStream)
        out.write(generateRandomMessage())
        out.write(generateRandomMessage())
        val expected: IOException? =
            Assert.assertThrows<IOException?>(IOException::class.java, ThrowingRunnable { out.close() })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("foo")
    }

    @Test
    @Throws(Exception::class)
    fun testFailedWritePropagatesUncheckedException() {
        val failingOutputStream: OutputStream = object : OutputStream() {
            @Throws(IOException::class)
            override fun write(b: Int) {
                throw RuntimeException("foo")
            }

            @Throws(IOException::class)
            override fun close() {
            }
        }
        val out: AsynchronousMessageOutputStream<Message?> =
            AsynchronousMessageOutputStream("", failingOutputStream)
        out.write(generateRandomMessage())
        out.write(generateRandomMessage())
        val expected =
            Assert.assertThrows<RuntimeException?>(RuntimeException::class.java, ThrowingRunnable { out.close() })
        Truth.assertThat(expected).hasMessageThat().isEqualTo("foo")
    }

    @Test
    @Throws(Exception::class)
    fun testWriteAfterCloseThrowsException() {
        val out: AsynchronousMessageOutputStream<Message?> =
            AsynchronousMessageOutputStream("", ByteArrayOutputStream())
        out.write(generateRandomMessage())
        out.close()

        Assert.assertThrows<IllegalStateException?>(
            IllegalStateException::class.java,
            ThrowingRunnable { out.write(generateRandomMessage()) })
    }

    companion object {
        private val RAND_CHARS: CharArray = "abcdefghijklmnopqrstuvwxzy0123456789-".toCharArray()
        private const val RAND_STRING_LENGTH = 10
    }
}
