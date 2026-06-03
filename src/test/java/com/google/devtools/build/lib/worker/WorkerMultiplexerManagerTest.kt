// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat

/** Tests for [WorkerMultiplexerManager].  */
@RunWith(JUnit4::class)
class WorkerMultiplexerManagerTest {
    private var fileSystem: FileSystem? = null

    @Before
    fun setUp() {
        fileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
        WorkerMultiplexerManager.resetForTesting()
    }

    @org.junit.After
    fun tearDown() {
        WorkerMultiplexerManager.resetForTesting()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun instanceCreationRemovalTest() {
        val logFile: Path? = fileSystem.getPath("/tmp/logFilePath")
        // Create a WorkerProxy hash and request for a WorkerMultiplexer.
        val workerKey1: WorkerKey =
            WorkerKey(
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                fileSystem.getPath("/execRoot"),
                "mnemonic1",
                com.google.common.hash.HashCode.fromInt(1),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                false,
                false,
                false,  /* cancellable= */
                false,
                WorkerProtocolFormat.PROTO
            )
        val wm1: WorkerMultiplexer? = WorkerMultiplexerManager.getInstance(workerKey1, logFile)

        assertThat(WorkerMultiplexerManager.getMultiplexer(workerKey1)).isEqualTo(wm1)
        assertThat(WorkerMultiplexerManager.getRefCount(workerKey1)).isEqualTo(1)
        assertThat(WorkerMultiplexerManager.getInstanceCount()).isEqualTo(1)

        // Create another WorkerProxy hash and request for a WorkerMultiplexer.
        val workerKey2: WorkerKey =
            WorkerKey(
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                fileSystem.getPath("/execRoot"),
                "mnemonic2",
                com.google.common.hash.HashCode.fromInt(1),
                com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
                false,
                false,
                false,  /* cancellable= */
                false,
                WorkerProtocolFormat.PROTO
            )
        val wm2: WorkerMultiplexer? = WorkerMultiplexerManager.getInstance(workerKey2, logFile)

        assertThat(WorkerMultiplexerManager.getMultiplexer(workerKey2)).isEqualTo(wm2)
        assertThat(WorkerMultiplexerManager.getRefCount(workerKey2)).isEqualTo(1)
        assertThat(WorkerMultiplexerManager.getInstanceCount()).isEqualTo(2)

        // Use the same WorkerProxy hash, it shouldn't instantiate a new WorkerMultiplexer.
        val wm2Annex: WorkerMultiplexer? = WorkerMultiplexerManager.getInstance(workerKey2, logFile)

        assertThat(wm2).isEqualTo(wm2Annex)
        assertThat(WorkerMultiplexerManager.getRefCount(workerKey2)).isEqualTo(2)
        assertThat(WorkerMultiplexerManager.getInstanceCount()).isEqualTo(2)

        // Remove an instance. If reference count is larger than 0, instance shouldn't be destroyed.
        WorkerMultiplexerManager.removeInstance(workerKey2)

        assertThat(WorkerMultiplexerManager.getRefCount(workerKey2)).isEqualTo(1)
        assertThat(WorkerMultiplexerManager.getInstanceCount()).isEqualTo(2)

        // Remove an instance. Reference count is down to 0, instance should be destroyed.
        WorkerMultiplexerManager.removeInstance(workerKey2)

        org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable { WorkerMultiplexerManager.getMultiplexer(workerKey2) })
        assertThat(WorkerMultiplexerManager.getInstanceCount()).isEqualTo(1)

        // WorkerProxy hash not found.
        org.junit.Assert.assertThrows<T?>(
            UserExecException::class.java,
            org.junit.function.ThrowingRunnable { WorkerMultiplexerManager.removeInstance(workerKey2) })

        // Remove all the instances.
        WorkerMultiplexerManager.removeInstance(workerKey1)

        assertThat(WorkerMultiplexerManager.getInstanceCount()).isEqualTo(0)
    }
}
