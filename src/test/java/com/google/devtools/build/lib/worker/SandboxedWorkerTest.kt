// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat

/** Tests for [SandboxedWorker].  */
@RunWith(JUnit4::class)
class SandboxedWorkerTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWritableDirs_withoutDevShm() {
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val workDir: Path = fs.getPath("/base/workDir")
        workDir.createDirectoryAndParents()

        // /dev/shm DOES NOT exist on this InMemoryFileSystem.
        val workerKey: WorkerKey = createWorkerKey(fs)
        val sandboxOptions: SandboxedWorker.WorkerSandboxOptions = createSandboxOptions(fs)

        val worker: SandboxedWorker =
            SandboxedWorker(
                workerKey,
                1,
                workDir,
                fs.getPath("/logFile"),
                WorkerOptions.DEFAULTS,
                sandboxOptions,  /* treeDeleter= */
                null,
                false,  /* cgroupFactory= */
                null
            )

        val writableDirs: com.google.common.collect.ImmutableSet<Path?>? = worker.getWritableDirs(workDir)

        Truth.assertThat(writableDirs).contains(fs.getPath("/tmp"))
        Truth.assertThat(writableDirs).doesNotContain(fs.getPath("/dev/shm"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWritableDirs_withDevShm() {
        val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val workDir: Path = fs.getPath("/base/workDir")
        workDir.createDirectoryAndParents()

        // Create /dev/shm
        fs.getPath("/dev/shm").createDirectoryAndParents()

        val workerKey: WorkerKey = createWorkerKey(fs)
        val sandboxOptions: SandboxedWorker.WorkerSandboxOptions = createSandboxOptions(fs)

        val worker: SandboxedWorker =
            SandboxedWorker(
                workerKey,
                1,
                workDir,
                fs.getPath("/logFile"),
                WorkerOptions.DEFAULTS,
                sandboxOptions,  /* treeDeleter= */
                null,
                false,  /* cgroupFactory= */
                null
            )

        val writableDirs: com.google.common.collect.ImmutableSet<Path?>? = worker.getWritableDirs(workDir)

        Truth.assertThat(writableDirs).contains(fs.getPath("/tmp"))
        Truth.assertThat(writableDirs).contains(fs.getPath("/dev/shm"))
    }

    private fun createWorkerKey(fs: FileSystem): WorkerKey {
        return WorkerKey(
            com.google.common.collect.ImmutableList.of<E?>(),
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            fs.getPath("/execRoot"),
            "dummy",
            com.google.common.hash.HashCode.fromInt(0),
            com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
            true,
            false,
            false,
            false,
            WorkerProtocolFormat.PROTO
        )
    }

    private fun createSandboxOptions(fs: FileSystem): SandboxedWorker.WorkerSandboxOptions {
        return WorkerSandboxOptions(
            fs.getPath("/sandboxBinary"),
            false,
            false,
            false,
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.common.collect.ImmutableSet.of<E?>(),
            0,
            com.google.common.collect.ImmutableSet.of<E?>(),
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
    }
}
