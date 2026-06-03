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
package com.google.devtools.build.lib.server

import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.build.lib.server.ServerWatcherRunnable.ProcMeminfoLowMemoryChecker
import com.google.devtools.build.lib.testutil.ManualClock
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.util.OS
import org.junit.Test
import java.time.Duration

/** Tests for [ServerWatcherRunnable].  */
@RunWith(JUnit4::class)
class ServerWatcherRunnableTest {
    private var clock: ManualClock? = null
    private var mockGrpcCommandServer: GrpcCommandServer? = null

    @Before
    fun setManualClock() {
        clock = ManualClock()
        mockGrpcCommandServer = Mockito.mock<GrpcCommandServer?>(GrpcCommandServer::class.java)
        BlazeClock.setClock(clock!!)
    }

    @Test
    @Throws(Exception::class)
    fun testBasicIdleCheck() {
        val mockCommands: CommandManager = Mockito.mock<CommandManager>(CommandManager::class.java)
        val underTest: ServerWatcherRunnable =
            ServerWatcherRunnable(
                mockGrpcCommandServer,  /* maxIdleSeconds= */
                10,  /* shutdownOnLowSysMem= */
                false,
                mockCommands
            )
        val thread: Thread = Thread(underTest)
        Mockito.`when`<T?>(mockCommands.isEmpty()).thenReturn(true)
        val checkIdleCounter: AtomicInteger = AtomicInteger()
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                checkIdleCounter.incrementAndGet()
                Mockito.verify<Any?>(mockGrpcCommandServer, Mockito.never()).shutdown()
                clock!!.advanceMillis(Duration.ofSeconds(5).toMillis())
                null
            })
            .`when`<Any?>(mockCommands)
            .waitForChange(ArgumentMatchers.anyLong())

        thread.start()
        thread.join(TestUtils.WAIT_TIMEOUT_MILLISECONDS)

        Mockito.verify<Any?>(mockGrpcCommandServer).shutdown()
        Truth.assertThat(checkIdleCounter.get()).isEqualTo(2)
    }

    @Test
    @Throws(Exception::class)
    fun runLowAbsoluteHighPercentageMemoryCheck() {
        if (!usingLinux()) {
            return
        }
        Truth.assertThat(doesIdleLowMemoryCheckShutdown( /*freeRamKb=*/5000,  /*totalRamKb=*/10000))
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun runHighAbsoluteLowPercentageMemoryCheck() {
        if (!usingLinux()) {
            return
        }
        Truth.assertThat(doesIdleLowMemoryCheckShutdown( /*freeRamKb=*/1L shl 21,  /*totalRamKb=*/1L shl 30))
            .isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun runLowAbsoluteLowPercentageMemoryCheck() {
        if (!usingLinux()) {
            return
        }
        Truth.assertThat(doesIdleLowMemoryCheckShutdown( /*freeRamKb=*/5000,  /*totalRamKb=*/1000000))
            .isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun testshutdownOnLowSysMemDisabled() {
        if (!usingLinux()) {
            return
        }
        Truth.assertThat(
            doesIdleLowMemoryCheckShutdown( /*freeRamKb=*/
                5000,  /*totalRamKb=*/1000000,  /*shutdownOnLowSysMem=*/false
            )
        )
            .isFalse()
    }

    @Throws(Exception::class)
    private fun doesIdleLowMemoryCheckShutdown(
        freeRamKb: Long, totalRamKb: Long, shutdownOnLowSysMem: Boolean = true
    ): Boolean {
        val mockCommandManager: CommandManager = Mockito.mock<CommandManager>(CommandManager::class.java)
        val mockParser: ProcMeminfoParser = Mockito.mock<ProcMeminfoParser>(ProcMeminfoParser::class.java)
        val underTest: ServerWatcherRunnable =
            ServerWatcherRunnable(
                mockGrpcCommandServer,  // Shut down after an hour if we see no memory issues.
                /* maxIdleSeconds= */
                Duration.ofHours(1).toSeconds(),
                shutdownOnLowSysMem,
                mockCommandManager,
                ProcMeminfoLowMemoryChecker({ mockParser })
            )
        val thread: Thread = Thread(underTest)
        Mockito.`when`<T?>(mockCommandManager.isEmpty()).thenReturn(true)
        val serverWatcherLoopCounter: AtomicInteger = AtomicInteger()

        Mockito.`when`<T?>(mockParser.getFreeRamKb()).thenReturn(freeRamKb)
        Mockito.`when`<T?>(mockParser.getTotalKb()).thenReturn(totalRamKb)
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                serverWatcherLoopCounter.incrementAndGet()
                clock!!.advanceMillis(Duration.ofMinutes(1).toMillis())
                null
            })
            .`when`<Any?>(mockCommandManager)
            .waitForChange(Duration.ofSeconds(5).toMillis())

        thread.start()
        thread.join(TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        Mockito.verify<Any?>(mockGrpcCommandServer).shutdown()

        // If we shut down due to memory pressure, it will only be after 5 minutes of being idle.
        return serverWatcherLoopCounter.get() == 5
    }

    private fun usingLinux(): Boolean {
        return OS.getCurrent() == OS.LINUX
    }
}
