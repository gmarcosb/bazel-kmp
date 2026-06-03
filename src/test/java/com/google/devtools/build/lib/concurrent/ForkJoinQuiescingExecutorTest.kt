// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.atomic.AtomicReference

/** Tests for [com.google.devtools.build.lib.concurrent.ForkJoinQuiescingExecutor].  */
@RunWith(JUnit4::class)
class ForkJoinQuiescingExecutorTest {
    @Test
    @Throws(Exception::class)
    fun testExecuteFromTaskForksInSamePool() {
        // Spy as an easy way to track calls to #execute.
        val forkJoinPool = Mockito.spy<ForkJoinPool>(ForkJoinPool())
        try {
            val underTest: ForkJoinQuiescingExecutor =
                ForkJoinQuiescingExecutor.newBuilder().withOwnershipOf(forkJoinPool).build()

            val subtaskRanIn = AtomicReference<ForkJoinPool?>()
            val subTask = Runnable { subtaskRanIn.set(ForkJoinTask.getPool()) }

            val taskRanIn = AtomicReference<ForkJoinPool?>()
            underTest.execute(
                {
                    taskRanIn.set(ForkJoinTask.getPool())
                    underTest.execute(subTask)
                })
            underTest.awaitQuiescence( /*interruptWorkers=*/false)

            Truth.assertThat(taskRanIn.get()).isSameInstanceAs(forkJoinPool)
            Truth.assertThat(subtaskRanIn.get()).isSameInstanceAs(forkJoinPool)

            // Confirm only one thing (the first task) was submitted via execute, the other should have
            // gone through the ForkJoinTask#fork() machinery.
            Mockito.verify<ForkJoinPool?>(forkJoinPool, Mockito.times(1)).execute(
                ArgumentMatchers.any<Runnable?>(
                    Runnable::class.java
                )
            )
        } finally {
            // Avoid leaving dangling threads.
            forkJoinPool.shutdownNow()
        }
    }

    /** Confirm our fork-new-work-if-in-forkjoinpool logic works as expected.  */
    @Test
    @Throws(Exception::class)
    fun testExecuteFromTaskInDifferentPoolRunsInRightPool() {
        val forkJoinPool = ForkJoinPool()
        val otherForkJoinPool = ForkJoinPool()
        try {
            val originalExecutor: ForkJoinQuiescingExecutor =
                ForkJoinQuiescingExecutor.newBuilder().withOwnershipOf(forkJoinPool).build()
            val otherExecutor: ForkJoinQuiescingExecutor =
                ForkJoinQuiescingExecutor.newBuilder().withOwnershipOf(otherForkJoinPool).build()

            val subtaskRanIn = AtomicReference<ForkJoinPool?>()
            val subTask = Runnable { subtaskRanIn.set(ForkJoinTask.getPool()) }

            val taskRanIn = AtomicReference<ForkJoinPool?>()
            originalExecutor.execute(
                {
                    taskRanIn.set(ForkJoinTask.getPool())
                    otherExecutor.execute(subTask)
                })

            originalExecutor.awaitQuiescence( /*interruptWorkers=*/false)
            otherExecutor.awaitQuiescence( /*interruptWorkers=*/false)

            Truth.assertThat(taskRanIn.get()).isSameInstanceAs(forkJoinPool)
            Truth.assertThat(subtaskRanIn.get()).isSameInstanceAs(otherForkJoinPool)
        } finally {
            // Avoid leaving dangling threads.
            forkJoinPool.shutdownNow()
            otherForkJoinPool.shutdownNow()
        }
    }

    @Test
    @Throws(Exception::class)
    fun testAwaitTerminationShutsDownPool() {
        val forkJoinPool = ForkJoinPool()
        try {
            val underTest: ForkJoinQuiescingExecutor =
                ForkJoinQuiescingExecutor.newBuilder().withOwnershipOf(forkJoinPool).build()

            underTest.awaitTermination( /*interruptWorkers=*/false)

            Truth.assertThat(forkJoinPool.isTerminated()).isTrue()
        } finally {
            // Avoid leaving dangling threads.
            forkJoinPool.shutdownNow()
        }
    }
}
