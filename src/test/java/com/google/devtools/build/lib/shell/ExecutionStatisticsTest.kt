// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.shell

import com.google.devtools.build.lib.vfs.DigestHashFunction

/** Tests for [ExecutionStatistics].  */
@RunWith(JUnit4::class)
class ExecutionStatisticsTest {
    private var workingDir: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createFileSystem() {
        val testFS: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        workingDir = com.google.devtools.build.lib.testutil.TestUtils.createUniqueTmpDir(testFS)
    }

    @Throws(java.lang.Exception::class)
    private fun createExecutionStatisticsProtoFile(
        executionStatisticsProto: com.google.devtools.build.lib.shell.Protos.ExecutionStatistics
    ): Path {
        val encodedProtoFile: Path = workingDir.getRelative("encoded_action_execution_proto")
        BufferedOutputStream(encodedProtoFile.getOutputStream()).use { bufferedOutputStream ->
            executionStatisticsProto.writeTo(bufferedOutputStream)
        }
        return encodedProtoFile
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoResourceUsage_whenNoResourceUsageProto() {
        val executionStatisticsProto: com.google.devtools.build.lib.shell.Protos.ExecutionStatistics =
            com.google.devtools.build.lib.shell.Protos.ExecutionStatistics.getDefaultInstance()
        val protoFilename: Path = createExecutionStatisticsProtoFile(executionStatisticsProto)

        val resourceUsage: java.util.Optional<ExecutionStatistics.ResourceUsage>? =
            ExecutionStatistics.getResourceUsage(protoFilename)
        Truth.assertThat(resourceUsage).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStatiticsProvided_fromProtoFilename() {
        val riggedUserExecutionTime: java.time.Duration = java.time.Duration.ofSeconds(42).plusNanos(19790000)
        val riggedSystemExecutionTime: java.time.Duration = java.time.Duration.ofSeconds(33).plusNanos(290000)
        val riggedMaximumResidentSetSize: Long = 1
        val riggedIntegralSharedMemorySize: Long = 2
        val riggedIntegralUnsharedDataSize: Long = 3
        val riggedIntegralUnsharedStackSize: Long = 4
        val riggedPageReclaims: Long = 5
        val riggedPageFaults: Long = 6
        val riggedSwaps: Long = 7
        val riggedBlockInputOperations: Long = 8
        val riggedBlockOutputOperations: Long = 9
        val riggedIpcMessagesSent: Long = 10
        val riggedIpcMessagesReceived: Long = 11
        val riggedSignalsReceived: Long = 12
        val riggedVoluntaryContextSwitches: Long = 13
        val riggedInvoluntaryContextSwitches: Long = 14

        val resourceUsageProto: com.google.devtools.build.lib.shell.Protos.ResourceUsage? =
            com.google.devtools.build.lib.shell.Protos.ResourceUsage.newBuilder()
                .setUtimeSec(riggedUserExecutionTime.getSeconds())
                .setUtimeUsec((riggedUserExecutionTime.getNano() / 1000).toLong())
                .setStimeSec(riggedSystemExecutionTime.getSeconds())
                .setStimeUsec((riggedSystemExecutionTime.getNano() / 1000).toLong())
                .setMaxrss(riggedMaximumResidentSetSize)
                .setIxrss(riggedIntegralSharedMemorySize)
                .setIdrss(riggedIntegralUnsharedDataSize)
                .setIsrss(riggedIntegralUnsharedStackSize)
                .setMinflt(riggedPageReclaims)
                .setMajflt(riggedPageFaults)
                .setNswap(riggedSwaps)
                .setInblock(riggedBlockInputOperations)
                .setOublock(riggedBlockOutputOperations)
                .setMsgsnd(riggedIpcMessagesSent)
                .setMsgrcv(riggedIpcMessagesReceived)
                .setNsignals(riggedSignalsReceived)
                .setNvcsw(riggedVoluntaryContextSwitches)
                .setNivcsw(riggedInvoluntaryContextSwitches)
                .build()

        val executionStatisticsProto: com.google.devtools.build.lib.shell.Protos.ExecutionStatistics =
            com.google.devtools.build.lib.shell.Protos.ExecutionStatistics.newBuilder()
                .setResourceUsage(resourceUsageProto)
                .build()
        val protoFilename: Path = createExecutionStatisticsProtoFile(executionStatisticsProto)

        val maybeResourceUsage: java.util.Optional<ExecutionStatistics.ResourceUsage>? =
            ExecutionStatistics.getResourceUsage(protoFilename)
        Truth.assertThat(maybeResourceUsage).isPresent()
        val resourceUsage: ExecutionStatistics.ResourceUsage = maybeResourceUsage.get()

        assertThat(resourceUsage.getUserExecutionTime()).isEqualTo(riggedUserExecutionTime)
        assertThat(resourceUsage.getSystemExecutionTime()).isEqualTo(riggedSystemExecutionTime)
        assertThat(resourceUsage.getMaximumResidentSetSize()).isEqualTo(riggedMaximumResidentSetSize)
        assertThat(resourceUsage.getIntegralSharedMemorySize())
            .isEqualTo(riggedIntegralSharedMemorySize)
        assertThat(resourceUsage.getIntegralUnsharedDataSize())
            .isEqualTo(riggedIntegralUnsharedDataSize)
        assertThat(resourceUsage.getIntegralUnsharedStackSize())
            .isEqualTo(riggedIntegralUnsharedStackSize)
        assertThat(resourceUsage.getPageReclaims()).isEqualTo(riggedPageReclaims)
        assertThat(resourceUsage.getPageFaults()).isEqualTo(riggedPageFaults)
        assertThat(resourceUsage.getSwaps()).isEqualTo(riggedSwaps)
        assertThat(resourceUsage.getBlockInputOperations()).isEqualTo(riggedBlockInputOperations)
        assertThat(resourceUsage.getBlockOutputOperations()).isEqualTo(riggedBlockOutputOperations)
        assertThat(resourceUsage.getIpcMessagesSent()).isEqualTo(riggedIpcMessagesSent)
        assertThat(resourceUsage.getIpcMessagesReceived()).isEqualTo(riggedIpcMessagesReceived)
        assertThat(resourceUsage.getSignalsReceived()).isEqualTo(riggedSignalsReceived)
        assertThat(resourceUsage.getVoluntaryContextSwitches())
            .isEqualTo(riggedVoluntaryContextSwitches)
        assertThat(resourceUsage.getInvoluntaryContextSwitches())
            .isEqualTo(riggedInvoluntaryContextSwitches)
    }
}
