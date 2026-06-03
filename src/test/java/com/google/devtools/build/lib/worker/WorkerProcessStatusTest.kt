// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics.WorkerStatus

/** Tests for [WorkerProcessStatus].  */
@RunWith(TestParameterInjector::class)
class WorkerProcessStatusTest {
    @org.junit.Test
    @TestParameters(
        "{status: NOT_STARTED, isValid: false}",
        "{status: ALIVE, isValid: false}",
        "{status: PENDING_KILL_DUE_TO_UNKNOWN, isValid: false}",
        "{status: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION, isValid: false}",
        "{status: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION, isValid: false}",
        "{status: PENDING_KILL_DUE_TO_IO_EXCEPTION, isValid: false}",
        "{status: PENDING_KILL_DUE_TO_MEMORY_PRESSURE, isValid: false}",
        "{status: KILLED_UNKNOWN, isValid: true}",
        "{status: KILLED_DUE_TO_INTERRUPTED_EXCEPTION, isValid: true}",
        "{status: KILLED_DUE_TO_USER_EXEC_EXCEPTION, isValid: true}",
        "{status: KILLED_DUE_TO_IO_EXCEPTION, isValid: true}",
        "{status: KILLED_DUE_TO_MEMORY_PRESSURE, isValid: true}"
    )
    fun testIsKilled(status: Status?, isValid: Boolean) {
        assertThat(createStatusOf(status).isKilled()).isEqualTo(isValid)
    }

    @org.junit.Test
    @TestParameters(
        "{status: NOT_STARTED, isValid: true}",
        "{status: ALIVE, isValid: true}",
        "{status: PENDING_KILL_DUE_TO_UNKNOWN, isValid: false}",
        "{status: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION, isValid: false}",
        "{status: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION, isValid: false}",
        "{status: PENDING_KILL_DUE_TO_IO_EXCEPTION, isValid: false}",
        "{status: PENDING_KILL_DUE_TO_MEMORY_PRESSURE, isValid: false}",
        "{status: KILLED_UNKNOWN, isValid: false}",
        "{status: KILLED_DUE_TO_INTERRUPTED_EXCEPTION, isValid: false}",
        "{status: KILLED_DUE_TO_USER_EXEC_EXCEPTION, isValid: false}",
        "{status: KILLED_DUE_TO_IO_EXCEPTION, isValid: false}",
        "{status: KILLED_DUE_TO_MEMORY_PRESSURE, isValid: false}"
    )
    fun testIsValid(status: Status?, isValid: Boolean) {
        assertThat(createStatusOf(status).isValid()).isEqualTo(isValid)
    }

    @org.junit.Test
    @TestParameters(
        "{pending: PENDING_KILL_DUE_TO_UNKNOWN, killed: KILLED_UNKNOWN}",
        "{pending: PENDING_KILL_DUE_TO_MEMORY_PRESSURE, killed:" + " KILLED_DUE_TO_MEMORY_PRESSURE}",
        "{pending: KILLED_DUE_TO_INTERRUPTED_EXCEPTION, killed: KILLED_DUE_TO_INTERRUPTED_EXCEPTION}",
        "{pending: PENDING_KILL_DUE_TO_IO_EXCEPTION, killed: KILLED_DUE_TO_IO_EXCEPTION}",
        "{pending: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION, killed: KILLED_DUE_TO_USER_EXEC_EXCEPTION}"
    )
    fun testSetKilled(pending: Status?, killed: Status?) {
        val status: WorkerProcessStatus = createStatusOf(pending)
        status.setKilled()
        assertThat(status.get()).isEqualTo(killed)
    }

    @org.junit.Test
    @TestParameters(
        "{status: NOT_STARTED, proto: NOT_STARTED}",
        "{status: ALIVE, proto: ALIVE}",
        "{status: PENDING_KILL_DUE_TO_UNKNOWN, proto: ALIVE}",
        "{status: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION, proto: ALIVE}",
        "{status: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION, proto: ALIVE}",
        "{status: PENDING_KILL_DUE_TO_IO_EXCEPTION, proto: ALIVE}",
        "{status: PENDING_KILL_DUE_TO_MEMORY_PRESSURE, proto: ALIVE}",
        "{status: KILLED_UNKNOWN, proto: KILLED_UNKNOWN}",
        "{status: KILLED_DUE_TO_INTERRUPTED_EXCEPTION, proto: KILLED_DUE_TO_INTERRUPTED_EXCEPTION}",
        "{status: KILLED_DUE_TO_USER_EXEC_EXCEPTION, proto: KILLED_DUE_TO_USER_EXEC_EXCEPTION}",
        "{status: KILLED_DUE_TO_IO_EXCEPTION, proto: KILLED_DUE_TO_IO_EXCEPTION}",
        "{status: KILLED_DUE_TO_MEMORY_PRESSURE, proto: KILLED_DUE_TO_MEMORY_PRESSURE}"
    )
    fun testToWorkerStatus(status: Status?, proto: WorkerStatus?) {
        assertThat(createStatusOf(status).toWorkerStatus()).isEqualTo(proto)
    }

    private fun createStatusOf(status: Status?): WorkerProcessStatus {
        val workerProcessStatus: WorkerProcessStatus = WorkerProcessStatus()
        workerProcessStatus.maybeUpdateStatus(status)
        return workerProcessStatus
    }

    @org.junit.Test
    @TestParameters(
        "{from: NOT_STARTED, to: ALIVE}" /* Transition ALIVE to intermediate states. */,
        "{from: ALIVE, to: PENDING_KILL_DUE_TO_UNKNOWN}",
        "{from: ALIVE, to: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION}",
        "{from: ALIVE, to: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION}",
        "{from: ALIVE, to: PENDING_KILL_DUE_TO_IO_EXCEPTION}",
        "{from: ALIVE, to: PENDING_KILL_DUE_TO_MEMORY_PRESSURE}" /* Transition ALIVE directly to killed states. */,
        "{from: ALIVE, to: KILLED_UNKNOWN}",
        "{from: ALIVE, to: KILLED_DUE_TO_IO_EXCEPTION}",
        "{from: ALIVE, to: KILLED_DUE_TO_INTERRUPTED_EXCEPTION}",
        "{from: ALIVE, to: KILLED_DUE_TO_USER_EXEC_EXCEPTION}",
        "{from: ALIVE, to: KILLED_DUE_TO_MEMORY_PRESSURE}" /* Transitions between pending causes. */,
        "{from: PENDING_KILL_DUE_TO_UNKNOWN, to: PENDING_KILL_DUE_TO_IO_EXCEPTION}",
        "{from: PENDING_KILL_DUE_TO_UNKNOWN, to: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION}",
        "{from: PENDING_KILL_DUE_TO_UNKNOWN, to: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION}" // All pending causes should be able to transition to pending kill by memory pressure, because
        // that is an explicit decision by Bazel to kill the worker.
        ,
        "{from: PENDING_KILL_DUE_TO_UNKNOWN, to: PENDING_KILL_DUE_TO_MEMORY_PRESSURE}",
        "{from: PENDING_KILL_DUE_TO_IO_EXCEPTION, to: PENDING_KILL_DUE_TO_MEMORY_PRESSURE}",
        "{from: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION, to: PENDING_KILL_DUE_TO_MEMORY_PRESSURE}",
        "{from: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION, to: PENDING_KILL_DUE_TO_MEMORY_PRESSURE}" /* Transition from pending to their kill causes. */,
        "{from: PENDING_KILL_DUE_TO_UNKNOWN, to: KILLED_UNKNOWN}",
        "{from: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION, to: KILLED_DUE_TO_INTERRUPTED_EXCEPTION}",
        "{from: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION, to: KILLED_DUE_TO_USER_EXEC_EXCEPTION}",
        "{from: PENDING_KILL_DUE_TO_IO_EXCEPTION, to: KILLED_DUE_TO_IO_EXCEPTION}",
        "{from: PENDING_KILL_DUE_TO_MEMORY_PRESSURE, to: KILLED_DUE_TO_MEMORY_PRESSURE}" /* Transitions between killed causes. */,
        "{from: KILLED_UNKNOWN, to: KILLED_DUE_TO_INTERRUPTED_EXCEPTION}",
        "{from: KILLED_UNKNOWN, to: KILLED_DUE_TO_USER_EXEC_EXCEPTION}",
        "{from: KILLED_UNKNOWN, to: KILLED_DUE_TO_IO_EXCEPTION}" // All killed causes should be able to transition to be killed by memory pressure, because
        // that is an explicit decision by Bazel to kill the worker.
        ,
        "{from: KILLED_UNKNOWN, to: KILLED_DUE_TO_MEMORY_PRESSURE}",
        "{from: KILLED_DUE_TO_INTERRUPTED_EXCEPTION, to: KILLED_DUE_TO_MEMORY_PRESSURE}",
        "{from: KILLED_DUE_TO_USER_EXEC_EXCEPTION, to: KILLED_DUE_TO_MEMORY_PRESSURE}",
        "{from: KILLED_DUE_TO_IO_EXCEPTION, to: KILLED_DUE_TO_MEMORY_PRESSURE}"
    )
    fun testMaybeUpdateStatus_successfulUpdate(from: Status?, to: Status?) {
        val fromStatus: WorkerProcessStatus = createStatusOf(from)
        assertThat(fromStatus.get()).isEqualTo(from)
        assertThat(fromStatus.maybeUpdateStatus(to)).isTrue()
        assertThat(fromStatus.get()).isEqualTo(to)
    }

    @org.junit.Test
    @TestParameters( /* Here we test some of many state transitions that shouldn't happen and thus are a NOOP. */"{from: ALIVE, to: NOT_STARTED}",
        "{from: PENDING_KILL_DUE_TO_UNKNOWN, to: ALIVE}",
        "{from: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION, to: ALIVE}",
        "{from: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION, to: ALIVE}",
        "{from: PENDING_KILL_DUE_TO_IO_EXCEPTION, to: ALIVE}",
        "{from: PENDING_KILL_DUE_TO_MEMORY_PRESSURE, to: ALIVE}" // A pending known kill status shouldn't go back to a pending unknown kill status.
        ,
        "{from: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION, to: PENDING_KILL_DUE_TO_UNKNOWN}",
        "{from: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION, to: PENDING_KILL_DUE_TO_UNKNOWN}",
        "{from: PENDING_KILL_DUE_TO_IO_EXCEPTION, to: PENDING_KILL_DUE_TO_UNKNOWN}",
        "{from: PENDING_KILL_DUE_TO_MEMORY_PRESSURE, to: PENDING_KILL_DUE_TO_UNKNOWN}" // Killed statuses shouldn't go back to their pending kill statuses.
        ,
        "{from: KILLED_UNKNOWN, to: PENDING_KILL_DUE_TO_UNKNOWN}",
        "{from: KILLED_DUE_TO_INTERRUPTED_EXCEPTION, to: PENDING_KILL_DUE_TO_INTERRUPTED_EXCEPTION}",
        "{from: KILLED_DUE_TO_USER_EXEC_EXCEPTION, to: PENDING_KILL_DUE_TO_USER_EXEC_EXCEPTION}",
        "{from: KILLED_DUE_TO_IO_EXCEPTION, to: PENDING_KILL_DUE_TO_IO_EXCEPTION}",
        "{from: KILLED_DUE_TO_MEMORY_PRESSURE, to: PENDING_KILL_DUE_TO_MEMORY_PRESSURE}" // Killed due to memory pressure should have the highest priority.
        ,
        "{from: KILLED_DUE_TO_MEMORY_PRESSURE, to: KILLED_UNKNOWN}",
        "{from: KILLED_DUE_TO_MEMORY_PRESSURE, to: KILLED_DUE_TO_INTERRUPTED_EXCEPTION}",
        "{from: KILLED_DUE_TO_MEMORY_PRESSURE, to: KILLED_DUE_TO_USER_EXEC_EXCEPTION}",
        "{from: KILLED_DUE_TO_MEMORY_PRESSURE, to: KILLED_DUE_TO_IO_EXCEPTION}"
    )
    fun testMaybeUpdateStatus_noUpdate(from: Status?, to: Status?) {
        val fromStatus: WorkerProcessStatus = createStatusOf(from)
        assertThat(fromStatus.get()).isEqualTo(from)
        assertThat(fromStatus.maybeUpdateStatus(to)).isFalse()
        assertThat(fromStatus.get()).isEqualTo(from)
    }
}
