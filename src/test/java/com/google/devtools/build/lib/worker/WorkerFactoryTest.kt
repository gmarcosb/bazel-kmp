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
package com.google.devtools.build.lib.worker

import com.google.devtools.build.lib.actions.ExecutionRequirements.WorkerProtocolFormat

/** Tests for [WorkerFactory].  */
@RunWith(JUnit4::class)
class WorkerFactoryTest {
    val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    @org.junit.After
    fun tearDown() {
        WorkerMultiplexerManager.resetForTesting()
    }

    /**
     * Regression test for b/64689608: The execroot of the sandboxed worker process must end with the
     * workspace name, just like the normal execroot does.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sandboxedWorkerPathEndsWithWorkspaceName() {
        val workerBaseDir: Path? = fs.getPath("/outputbase/bazel-workers")
        val workerFactory: WorkerFactory =
            WorkerFactory(
                workerBaseDir,
                com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)
            )
        val workerKey: WorkerKey? = createWorkerKey( /* mustBeSandboxed= */true,  /* multiplex= */false)
        val sandboxedWorkerPath: Path = workerFactory.getSandboxedWorkerPath(workerKey, 1)

        assertThat(sandboxedWorkerPath.getBaseName()).isEqualTo("workspace")
    }

    /** WorkerFactory should create correct worker type based on WorkerKey.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun workerCreationTypeCheck() {
        val workerBaseDir: Path? = fs.getPath("/outputbase/bazel-workers")
        val workerFactory: WorkerFactory =
            WorkerFactory(
                workerBaseDir,
                com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)
            )
        val sandboxedWorkerKey: WorkerKey? =
            createWorkerKey( /* mustBeSandboxed= */true,  /* multiplex= */false)
        val sandboxedWorker: Worker = workerFactory.create(sandboxedWorkerKey)
        assertThat(sandboxedWorker.getClass()).isEqualTo(SandboxedWorker::class.java)

        val nonProxiedWorkerKey: WorkerKey? =
            createWorkerKey( /* mustBeSandboxed= */false,  /* multiplex= */false)
        val nonProxiedWorker: Worker = workerFactory.create(nonProxiedWorkerKey)
        assertThat(nonProxiedWorker.getClass()).isEqualTo(SingleplexWorker::class.java)

        val proxiedWorkerKey: WorkerKey? =
            createWorkerKey( /* mustBeSandboxed= */false,  /* multiplex= */true)
        val proxiedWorker: Worker = workerFactory.create(proxiedWorkerKey)
        // If proxied = true, WorkerProxy is created along with a WorkerMultiplexer.
        // Destroy WorkerMultiplexer to avoid unexpected behavior in WorkerMultiplexerManagerTest.
        WorkerMultiplexerManager.removeInstance(proxiedWorkerKey)
        assertThat(proxiedWorker.getClass()).isEqualTo(WorkerProxy::class.java)
    }

    /** Proxied workers with the same WorkerKey should share the log file.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiplexWorkersShareLogfiles() {
        val workerBaseDir: Path? = fs.getPath("/outputbase/bazel-workers")
        val workerFactory: WorkerFactory =
            WorkerFactory(
                workerBaseDir,
                com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)
            )

        val workerKey1: WorkerKey? =
            createWorkerKey( /* mustBeSandboxed= */false,  /* multiplex= */true, "arg1")
        val proxiedWorker1a: Worker = workerFactory.create(workerKey1)
        val proxiedWorker1b: Worker = workerFactory.create(workerKey1)
        val workerKey2: WorkerKey? =
            createWorkerKey( /* mustBeSandboxed= */false,  /* multiplex= */true, "arg2")
        val proxiedWorker2: Worker = workerFactory.create(workerKey2)
        assertThat(proxiedWorker1a.getLogFile()).isEqualTo(proxiedWorker1b.getLogFile())
        assertThat(proxiedWorker1a.getLogFile()).isNotEqualTo(proxiedWorker2.getLogFile())
    }

    /** WorkerFactory should create the base dir if needed and fail if that's impossible.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreate_createsWorkerDirectory() {
        val workerBaseDir: Path = fs.getPath("/outputbase/bazel-workers")
        val workerFactory: WorkerFactory =
            WorkerFactory(
                workerBaseDir,
                com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)
            )
        val sandboxedWorkerKey: WorkerKey? = createWorkerKey( /* mustBeSandboxed */true,  /* proxied */false)
        assertThat(workerBaseDir.isDirectory()).isFalse()
        workerFactory.create(sandboxedWorkerKey)
        assertThat(workerBaseDir.isDirectory()).isTrue()

        workerBaseDir.delete()
        workerBaseDir.createSymbolicLink(workerBaseDir.getRelative("whatevs"))
        assertThat(workerBaseDir.isDirectory()).isFalse()
        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { workerFactory.create(sandboxedWorkerKey) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoomedWorkerValidation() {
        val workerBaseDir: Path? = fs.getPath("/outputbase/bazel-workers")
        val workerFactory: WorkerFactory =
            WorkerFactory(
                workerBaseDir,
                com.google.devtools.common.options.Options.getDefaults<O?>(WorkerOptions::class.java)
            )

        val workerKey: WorkerKey? =
            createWorkerKey( /* mustBeSandboxed= */false,  /* multiplex= */false, "arg1")
        val worker: Worker = workerFactory.create(workerKey)

        assertThat(workerFactory.validateWorker(workerKey, worker)).isTrue()

        worker.getStatus().maybeUpdateStatus(Status.KILLED_DUE_TO_MEMORY_PRESSURE)

        assertThat(workerFactory.validateWorker(workerKey, worker)).isFalse()
    }

    protected fun createWorkerKey(mustBeSandboxed: Boolean, multiplex: Boolean, vararg args: String?): WorkerKey? {
        return WorkerKey( /* args= */
            com.google.common.collect.ImmutableList.< E > copyOf < E ? > (args),  /* env= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* execRoot= */
            fs.getPath("/outputbase/execroot/workspace"),  /* mnemonic= */
            "dummy",  /* workerFilesCombinedHash= */
            com.google.common.hash.HashCode.fromInt(0),  /* workerFilesWithDigests= */
            com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),  /* sandboxed= */
            mustBeSandboxed,  /* useInMemoryTracking= */
            false,  /* multiplex= */
            multiplex,  /* cancellable= */
            false,
            WorkerProtocolFormat.PROTO
        )
    }
}
