// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.runtime.BlockWaitingModule.Task

/** Tests for [BlockWaitingModule].  */
@RunWith(JUnit4::class)
class BlockWaitingModuleTest {
    @org.mockito.Mock
    var env: CommandEnvironment? = null

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubmitZeroTasks() {
        // arrange
        val m: BlockWaitingModule = BlockWaitingModule()

        // act
        m.beforeCommand(env)
        m.afterCommand()

        // nothing to assert
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubmitOneTask() {
        // arrange
        val m: BlockWaitingModule = BlockWaitingModule()
        val t: Task? = Mockito.mock<Task?>(Task::class.java)

        // act
        m.beforeCommand(env)
        m.submit(t)
        m.afterCommand()

        // assert
        Mockito.verify<Any?>(t).call()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubmitMultipleTasks() {
        // arrange
        val m: BlockWaitingModule = BlockWaitingModule()
        val t1: Task? = Mockito.mock<Task?>(Task::class.java)
        val t2: Task? = Mockito.mock<Task?>(Task::class.java)
        val t3: Task? = Mockito.mock<Task?>(Task::class.java)

        // act
        m.beforeCommand(env)
        m.submit(t1)
        m.submit(t2)
        m.submit(t3)
        m.afterCommand()

        // assert
        Mockito.verify<Any?>(t1).call()
        Mockito.verify<Any?>(t2).call()
        Mockito.verify<Any?>(t3).call()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTaskThrowsAbruptExitException() {
        // arrange
        val m: BlockWaitingModule = BlockWaitingModule()
        val t: Task? = Mockito.mock<Task?>(Task::class.java)
        doThrow(AbruptExitException(CRASH)).`when`<Any?>(t).call()

        // act
        m.beforeCommand(env)
        m.submit(t)

        // assert
        val e: Throwable = org.junit.Assert.assertThrows<T>(AbruptExitException::class.java, m::afterCommand)
        assertThat((e as AbruptExitException).getDetailedExitCode()).isEqualTo(CRASH)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTaskThrowsUnrecognizedException() {
        // arrange
        val m: BlockWaitingModule = BlockWaitingModule()
        val t: Task? = Mockito.mock<Task?>(Task::class.java)
        Mockito.doThrow(java.lang.IllegalStateException("illegal state")).`when`<Any?>(t).call()

        // act
        m.beforeCommand(env)
        m.submit(t)

        // assert
        val e: Throwable? = org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            m::afterCommand
        )
        Truth.assertThat(e).hasCauseThat().isInstanceOf(ExecutionException::class.java)
        Truth.assertThat(e).hasCauseThat().hasCauseThat().isInstanceOf(java.lang.IllegalStateException::class.java)
        Truth.assertThat(e).hasCauseThat().hasCauseThat().hasMessageThat().contains("illegal state")
    }

    companion object {
        private val CRASH: DetailedExitCode? = DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage("crash")
                .setCrash(Crash.newBuilder().setCode(Crash.Code.CRASH_UNKNOWN))
                .build()
        )
    }
}
