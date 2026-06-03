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

import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Tests for [MultiExecutorQueueVisitor].  */
@RunWith(JUnit4::class)
class MultiExecutorQueueVisitorTest {
    @Test
    fun testGetExecutorServiceByThreadPoolType_regular() {
        val regular: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)
        val cpuHeavy: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)

        val queueVisitor: MultiExecutorQueueVisitor =
            MultiExecutorQueueVisitor.createWithExecutorServices(
                regular, cpuHeavy, ExceptionHandlingMode.KEEP_GOING, ErrorClassifier.DEFAULT
            )

        assertThat(queueVisitor.getExecutorServiceByThreadPoolType(ThreadPoolType.REGULAR))
            .isEqualTo(regular)
    }

    @Test
    fun testGetExecutorServiceByThreadPoolType_cpuHeavy() {
        val regular: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)
        val cpuHeavy: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)

        val queueVisitor: MultiExecutorQueueVisitor =
            MultiExecutorQueueVisitor.createWithExecutorServices(
                regular, cpuHeavy, ExceptionHandlingMode.KEEP_GOING, ErrorClassifier.DEFAULT
            )

        assertThat(queueVisitor.getExecutorServiceByThreadPoolType(ThreadPoolType.CPU_HEAVY))
            .isEqualTo(cpuHeavy)
    }

    @Test
    fun testShutDownExecutorService_noThrowables() {
        val regular: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)
        val cpuHeavy: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)

        val queueVisitor: MultiExecutorQueueVisitor =
            MultiExecutorQueueVisitor.createWithExecutorServices(
                regular, cpuHeavy, ExceptionHandlingMode.KEEP_GOING, ErrorClassifier.DEFAULT
            )
        queueVisitor.shutdownExecutorService( /*catastrophe=*/null)

        Mockito.verify<ExecutorService?>(regular).shutdown()
        Mockito.verify<ExecutorService?>(cpuHeavy).shutdown()
    }

    @Test
    fun testShutDownExecutorService_withThrowable() {
        val regular: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)
        val cpuHeavy: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)

        val queueVisitor: MultiExecutorQueueVisitor =
            MultiExecutorQueueVisitor.createWithExecutorServices(
                regular, cpuHeavy, ExceptionHandlingMode.KEEP_GOING, ErrorClassifier.DEFAULT
            )
        val toBeThrown = RuntimeException()

        val thrown =
            Assert.assertThrows<Throwable?>(
                Throwable::class.java,
                ThrowingRunnable { queueVisitor.shutdownExecutorService( /*catastrophe=*/toBeThrown) })
        Truth.assertThat(thrown).isEqualTo(toBeThrown)
    }

    @Test
    fun testGetExecutorServiceByThreadPoolType_executionPhase() {
        val regular: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)
        val cpuHeavy: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)
        val executionPhase: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)

        val queueVisitor: MultiExecutorQueueVisitor =
            MultiExecutorQueueVisitor.createWithExecutorServices(
                regular,
                cpuHeavy,
                executionPhase,
                ExceptionHandlingMode.KEEP_GOING,
                ErrorClassifier.DEFAULT
            )

        assertThat(queueVisitor.getExecutorServiceByThreadPoolType(ThreadPoolType.EXECUTION_PHASE))
            .isEqualTo(executionPhase)
    }

    @Test
    fun testGetExecutorServiceByThreadPoolType_executionPhaseWithoutExecutor_throwsNPE() {
        val regular: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)
        val cpuHeavy: ExecutorService? = Mockito.mock<ExecutorService?>(ExecutorService::class.java)

        val queueVisitorWithoutExecutionPhasePool: MultiExecutorQueueVisitor =
            MultiExecutorQueueVisitor.createWithExecutorServices(
                regular, cpuHeavy, ExceptionHandlingMode.KEEP_GOING, ErrorClassifier.DEFAULT
            )

        Assert.assertThrows<NullPointerException?>(
            NullPointerException::class.java,
            ThrowingRunnable {
                queueVisitorWithoutExecutionPhasePool.getExecutorServiceByThreadPoolType(
                    ThreadPoolType.EXECUTION_PHASE
                )
            })
    }
}
