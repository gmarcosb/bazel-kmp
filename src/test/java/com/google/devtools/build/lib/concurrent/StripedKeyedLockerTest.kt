// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import com.google.common.base.Preconditions
import com.google.common.util.concurrent.MoreExecutors
import com.google.devtools.build.lib.concurrent.KeyedLocker.AutoUnlocker
import com.google.devtools.build.lib.testutil.TestUtils
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.util.function.Supplier

/** Tests for [StripedKeyedLocker].  */
@RunWith(JUnit4::class)
class StripedKeyedLockerTest {
    private var locker: KeyedLocker<String?>? = null
    private var executorService: ExecutorService? = null
    private val throwableFromRunnable: AtomicReference<Throwable?> = AtomicReference<Throwable?>()

    private fun makeFreshLocker(): KeyedLocker<String?> {
        return StripedKeyedLocker(17)
    }

    @Before
    fun setUp() {
        locker = makeFreshLocker()
        executorService = Executors.newFixedThreadPool(NUM_EXECUTOR_THREADS)
    }

    @After
    fun shutdownExecutor() {
        locker = null
        MoreExecutors.shutdownAndAwaitTermination(
            executorService, TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
        )
        if (throwableFromRunnable.get() != null) {
            throw RuntimeException("Uncaught from thread", throwableFromRunnable.get())
        }
    }

    private fun makeLockInvoker(key: String?): Supplier<AutoUnlocker> {
        return Supplier { locker.writeLock(key) }
    }

    private fun makeLockFn1(): Supplier<AutoUnlocker> {
        return makeLockInvoker("1")
    }

    private fun makeLockFn2(): Supplier<AutoUnlocker> {
        return makeLockInvoker("2")
    }

    @Test
    fun simpleSingleThreaded_noUnlocks() {
        val lockFn1: Supplier<AutoUnlocker> = makeLockFn1()
        val lockFn2: Supplier<AutoUnlocker> = makeLockFn2()
        lockFn1.get()
        lockFn2.get()
        lockFn1.get()
        lockFn2.get()
    }

    @Test
    fun simpleSingleThreaded_withUnlocks() {
        val lockFn1: Supplier<AutoUnlocker> = makeLockFn1()
        val lockFn2: Supplier<AutoUnlocker> = makeLockFn2()
        lockFn1.get().use { unlockerCat1 ->
            lockFn2.get()
                .use { unlockerDog1 -> lockFn1.get().use { unlockerCat2 -> lockFn2.get().use { unlockerDog2 -> } } }
        }
    }

    @Test
    fun doubleUnlockOnSameAutoUnlockerNotAllowed() {
        val unlocker: AutoUnlocker = makeLockFn1().get()
        unlocker.close()
        Assert.assertThrows<T?>(IllegalUnlockException::class.java, unlocker::close)
    }

    @Test
    fun unlockOnDifferentAutoUnlockersAllowed() {
        val lockFn: Supplier<AutoUnlocker> = makeLockFn1()
        val unlocker1: AutoUnlocker = lockFn.get()
        val unlocker2: AutoUnlocker = lockFn.get()
        unlocker1.close()
        unlocker2.close()
    }

    @Test
    fun threadLocksMultipleTimesBeforeUnlocking() {
        val lockFn: Supplier<AutoUnlocker> = makeLockFn1()
        val currentThreadIdRef: AtomicReference<Long?> = AtomicReference<Long?>(-1L)
        val count: AtomicInteger = AtomicInteger(0)
        val runnable =
            Runnable {
                val currentThreadId = Thread.currentThread().getId()
                lockFn.get().use { unlocker1 ->
                    currentThreadIdRef.set(currentThreadId)
                    lockFn.get().use { unlocker2 ->
                        Truth.assertThat(currentThreadIdRef.get()).isEqualTo(currentThreadId)
                        lockFn.get().use { unlocker3 ->
                            Truth.assertThat(currentThreadIdRef.get()).isEqualTo(currentThreadId)
                            lockFn.get().use { unlocker4 ->
                                Truth.assertThat(currentThreadIdRef.get()).isEqualTo(currentThreadId)
                                lockFn.get().use { unlocker5 ->
                                    Truth.assertThat(currentThreadIdRef.get()).isEqualTo(currentThreadId)
                                    count.incrementAndGet()
                                }
                            }
                        }
                    }
                }
            }
        for (i in 0..<NUM_EXECUTOR_THREADS) {
            executorService.execute(wrap(runnable))
        }
        assertThatExecutorShutsDown()
        Truth.assertThat(count.get()).isEqualTo(NUM_EXECUTOR_THREADS)
    }

    @Test
    fun unlockOnOtherThreadNotAllowed() {
        val unlockerRef: AtomicReference<AutoUnlocker?> = AtomicReference<AutoUnlocker?>()
        val unlockerRefSetLatch: CountDownLatch = CountDownLatch(1)
        val runnable2Executed: AtomicBoolean = AtomicBoolean(false)
        val runnable1 =
            Runnable {
                unlockerRef.set(makeLockFn1().get())
                unlockerRefSetLatch.countDown()
            }
        val runnable2 =
            Runnable {
                try {
                    unlockerRefSetLatch.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    throw IllegalStateException(e)
                }
                Assert.assertThrows<IllegalMonitorStateException?>(
                    IllegalMonitorStateException::class.java,
                    ThrowingRunnable { Preconditions.checkNotNull<Any?>(unlockerRef.get()).close() })
                runnable2Executed.set(true)
            }
        executorService.execute(wrap(runnable1))
        executorService.execute(wrap(runnable2))
        assertThatExecutorShutsDown()
        Truth.assertThat(runnable2Executed.get()).isTrue()
    }

    private fun runRefCountingSanity(lockFn: Supplier<AutoUnlocker>) {
        val unlockers: MutableSet<AutoUnlocker?> = HashSet<AutoUnlocker?>()
        for (i in 0..999) {
            lockFn.get().use { unlocker ->
                Truth.assertThat(unlockers.add(unlocker)).isTrue()
            }
        }
    }

    @Test
    fun refCountingSanity() {
        runRefCountingSanity(makeLockFn1())
    }

    @Test
    fun simpleMultiThreaded_mutualExclusion() {
        val runnableLatch: CountDownLatch = CountDownLatch(NUM_EXECUTOR_THREADS)
        val mutexCounter: AtomicInteger = AtomicInteger(0)
        val runnableCounter: AtomicInteger = AtomicInteger(0)
        val runnable =
            Runnable {
                runnableLatch.countDown()
                try {
                    // Wait until all the Runnables are ready to try to acquire the lock all at once.
                    runnableLatch.await(TestUtils.WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    throw IllegalStateException(e)
                }
                makeLockFn1().get().use { unlocker ->
                    runnableCounter.incrementAndGet()
                    Truth.assertThat(mutexCounter.incrementAndGet()).isEqualTo(1)
                    Truth.assertThat(mutexCounter.decrementAndGet()).isEqualTo(0)
                }
            }
        for (i in 0..<NUM_EXECUTOR_THREADS) {
            executorService.execute(wrap(runnable))
        }
        assertThatExecutorShutsDown()
        Truth.assertThat(runnableCounter.get()).isEqualTo(NUM_EXECUTOR_THREADS)
    }

    private fun wrap(runnable: Runnable): Runnable {
        return Runnable {
            try {
                runnable.run()
            } catch (e: Throwable) {
                throwableFromRunnable.compareAndSet(null, e)
            }
        }
    }

    private fun assertThatExecutorShutsDown() {
        Truth.assertWithMessage("Shouldn't have been interrupted")
            .that(ExecutorUtil.interruptibleShutdown(executorService))
            .isFalse()
    }

    companion object {
        private const val NUM_EXECUTOR_THREADS = 1000
    }
}
