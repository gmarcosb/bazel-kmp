// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.io.ByteStreams
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.OutputStream
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Exercise [StreamMultiplexer] in a parallel setting and ensure there's
 * no corruption.
 */
@RunWith(JUnit4::class)
class StreamMultiplexerParallelStressTest {
    /**
     * Characters that could likely cause corruption (they're used as control
     * characters).
     */
    var toughCharsToTry: CharArray = charArrayOf('\n', '@', '1', '2', '\u0000', '0')

    /**
     * We use a demultiplexer as a simple checker only - that is, we don't care what the demultiplexer
     * writes, but we are taking advantage of its built in error checking.
     */
    var devNull: OutputStream = ByteStreams.nullOutputStream()

    var demux: StreamDemultiplexer = StreamDemultiplexer(1.toByte(), devNull, devNull, devNull)

    /**
     * The multiplexer under test.
     */
    var mux: StreamMultiplexer = StreamMultiplexer(demux)

    /**
     * Streams is the out / err / control output streams of the multiplexer which
     * we will write to in parallel.
     */
    var streams: Array<OutputStream> =
        arrayOf<OutputStream>(mux.createStdout(), mux.createStderr(), mux.createControl())

    /**
     * We will create a bunch of threads that write random data to the streams of
     * the mux.
     */
    internal inner class RandomDataPump(threadId: Int) : Callable<Any?> {
        private val random: Random

        init {
            random = Random(threadId * 0xdeadbeefL)
        }

        @Throws(Exception::class)
        override fun call(): Any? {
            Thread.yield()
            var out = streams[random.nextInt(2)]
            for (i in 0..9999) {
                when (random.nextInt(5)) {
                    0 -> out.write(random.nextInt())
                    1 -> {
                        val index = random.nextInt(toughCharsToTry.size)
                        out.write(toughCharsToTry[index].code)
                    }

                    2 -> {
                        val buffer = ByteArray(random.nextInt(312))
                        random.nextBytes(buffer)
                        out.write(buffer)
                    }

                    3 -> out.flush()
                    4 -> out = streams[random.nextInt(3)]
                    else -> {}
                }
            }
            return null
        }
    }

    @Test
    @Throws(Exception::class)
    fun testSingleThreadedStress() {
        RandomDataPump(1).call()
    }

    @Test
    @Throws(InterruptedException::class, ExecutionException::class)
    fun testMultiThreadedStress() {
        val service = Executors.newFixedThreadPool(50)

        val futures: MutableList<Future<*>> = ArrayList<Future<*>>()
        for (threadId in 0..49) {
            futures.add(service.submit<Any?>(RandomDataPump(threadId)))
        }
        for (future in futures) {
            future.get()
        }
    }
}
