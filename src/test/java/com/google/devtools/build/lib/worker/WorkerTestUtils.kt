// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ExecutionRequirements
import com.google.devtools.build.lib.worker.WorkerTestUtils.createWorkerKey

/** Utilities that come in handy when unit-testing the worker code.  */
object WorkerTestUtils {
    /** A helper method to create a fake Spawn with the given execution info.  */
    fun createSpawn(executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?): Spawn {
        return createSpawn(com.google.common.collect.ImmutableList.of<String?>(), executionInfo)
    }

    fun createSpawn(
        arguments: com.google.common.collect.ImmutableList<String?>?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>?
    ): Spawn {
        return SimpleSpawn(
            NullAction(),
            arguments,  /* environment= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            executionInfo,  /* inputs= */
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
            com.google.common.collect.ImmutableSet.of<E?>(),
            ResourceSet.ZERO
        )
    }

    /** A helper method to create a WorkerKey through WorkerParser.  */
    fun createWorkerKey(
        protocolFormat: WorkerProtocolFormat?,
        fs: FileSystem,
        mnemonic: String?,
        multiplex: Boolean,
        sandboxed: Boolean,
        dynamic: Boolean,
        vararg args: String?
    ): WorkerKey? {
        val workerOptions: WorkerOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(WorkerOptions::class.java)
        workerOptions.workerMultiplex = multiplex
        workerOptions.workerSandboxing = sandboxed

        return createWorkerKeyFromOptions(
            protocolFormat,
            fs.getPath("/outputbase"),
            workerOptions,
            dynamic,
            createSpawn(execRequirementsBuilder(mnemonic).buildOrThrow()),
            args
        )
    }

    fun createWorkerKey(
        fileSystem: FileSystem?, mnemonic: String?, proxied: Boolean, vararg args: String?
    ): WorkerKey? {
        return createWorkerKey(
            WorkerProtocolFormat.PROTO,
            fileSystem,
            mnemonic,
            proxied,  /* sandboxed= */
            false,  /* dynamic= */
            false,
            args
        )
    }

    fun createWorkerKey(protocolFormat: WorkerProtocolFormat?, fs: FileSystem): WorkerKey? {
        return WorkerTestUtils.createWorkerKey(protocolFormat, fs, false)
    }

    fun createWorkerKey(
        mnemonic: String?, fs: FileSystem?, multiplex: Boolean, sandboxed: Boolean
    ): WorkerKey? {
        return createWorkerKey(
            WorkerProtocolFormat.PROTO, fs, mnemonic, multiplex, sandboxed,  /* dynamic= */false
        )
    }

    fun createWorkerKey(mnemonic: String?, fs: FileSystem?, sandboxed: Boolean): WorkerKey? {
        return createWorkerKey(
            WorkerProtocolFormat.PROTO,
            fs,
            mnemonic,  /* multiplex= */
            false,
            sandboxed,  /* dynamic= */
            false
        )
    }

    fun createWorkerKey(mnemonic: String?, fs: FileSystem?): WorkerKey? {
        return createWorkerKey(
            WorkerProtocolFormat.PROTO,
            fs,
            mnemonic,  /* multiplex= */
            false,  /* sandboxed= */
            false,  /* dynamic= */
            false
        )
    }

    fun createWorkerKey(
        protocolFormat: WorkerProtocolFormat?, fs: FileSystem, dynamic: Boolean
    ): WorkerKey? {
        return createWorkerKey(
            protocolFormat,
            fs,  /* mnemonic= */
            "dummy",  /* multiplex= */
            true,  /* sandboxed= */
            true,
            dynamic,  /* args...= */
            "arg1",
            "arg2",
            "arg3"
        )
    }

    fun createWorkerKey(
        fs: FileSystem?, multiplex: Boolean, sandboxed: Boolean, dynamic: Boolean
    ): WorkerKey? {
        return createWorkerKey(
            WorkerProtocolFormat.PROTO,
            fs,  /* mnemonic= */
            "dummy",
            multiplex,
            sandboxed,
            dynamic,  /* args...= */
            "arg1",
            "arg2",
            "arg3"
        )
    }

    /**
     * Creates a worker key based on a set of options. The `extraRequirements` are added to the
     * [Spawn] execution info with the value "1". The "supports-workers" and
     * "supports-multiplex-workers" execution requirements are always set.
     * 
     * @param outputBase Global (for the test) outputBase.
     */
    fun createWorkerKeyWithRequirements(
        outputBase: Path,
        workerOptions: WorkerOptions?,
        mnemonic: String?,
        dynamic: Boolean,
        vararg extraRequirements: String?
    ): WorkerKey {
        val builder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            execRequirementsBuilder(mnemonic)
        for (req in extraRequirements) {
            builder.put(req, "1")
        }
        val spawn: Spawn = createSpawn(builder.buildOrThrow())

        return WorkerParser.createWorkerKey(
            spawn,  /* workerArgs= */
            com.google.common.collect.ImmutableList.of<E?>(),  /* env= */
            com.google.common.collect.ImmutableMap.of<K?, V?>("env1", "foo", "env2", "bar"),  /* execRoot= */
            outputBase.getChild("execroot"),  /* workerFilesCombinedHash= */
            com.google.common.hash.HashCode.fromInt(0),  /* workerFiles= */
            com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
            workerOptions,
            dynamic,
            WorkerProtocolFormat.PROTO
        )
    }

    fun execRequirementsBuilder(mnemonic: String?): com.google.common.collect.ImmutableMap.Builder<String?, String?> {
        return com.google.common.collect.ImmutableMap.builder<String?, String?>()
            .put(ExecutionRequirements.WORKER_KEY_MNEMONIC, mnemonic)
            .put(ExecutionRequirements.REQUIRES_WORKER_PROTOCOL, "proto")
            .put(ExecutionRequirements.SUPPORTS_WORKERS, "1")
            .put(ExecutionRequirements.SUPPORTS_MULTIPLEX_WORKERS, "1")
    }

    fun createWorkerKeyFromOptions(
        protocolFormat: WorkerProtocolFormat?,
        outputBase: Path,
        workerOptions: WorkerOptions?,
        dynamic: Boolean,
        spawn: Spawn?,
        vararg args: String?
    ): WorkerKey {
        return WorkerParser.createWorkerKey(
            spawn,  /* workerArgs= */
            com.google.common.collect.ImmutableList.< E > copyOf < E ? > (args),  /* env= */
            com.google.common.collect.ImmutableMap.of<K?, V?>("env1", "foo", "env2", "bar"),  /* execRoot= */
            outputBase.getChild("execroot"),  /* workerFilesCombinedHash= */
            com.google.common.hash.HashCode.fromInt(0),  /* workerFiles= */
            com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
            workerOptions,
            dynamic,
            protocolFormat
        )
    }

    fun createTestWorkerPool(worker: Worker): WorkerPool {
        return object : WorkerPool() {
            public override fun getMaxTotalPerKey(key: WorkerKey?): Int {
                return 1
            }

            public override fun getNumActive(key: WorkerKey?): Int {
                return 0
            }

            public override fun hasAvailableQuota(key: WorkerKey?): Boolean {
                return true
            }

            @Throws(java.lang.InterruptedException::class)
            public override fun evictWorkers(workerIdsToEvict: com.google.common.collect.ImmutableSet<Int?>?): com.google.common.collect.ImmutableSet<Int?> {
                return com.google.common.collect.ImmutableSet.of<Int?>()
            }

            @get:Throws(java.lang.InterruptedException::class)
            val idleWorkers: com.google.common.collect.ImmutableSet<Int?>
                get() = com.google.common.collect.ImmutableSet.of<Int?>()

            @Throws(IOException::class, java.lang.InterruptedException::class)
            public override fun borrowWorker(key: WorkerKey?): Worker {
                return worker
            }

            public override fun returnWorker(key: WorkerKey?, worker: Worker?) {}

            @Throws(java.lang.InterruptedException::class)
            public override fun invalidateWorker(worker: Worker?) {
            }

            public override fun reset() {}

            public override fun close() {}
        }
    }

    /** A worker that uses a fake subprocess for I/O.  */
    internal class TestWorker(
        workerKey: WorkerKey?,
        workerId: Int,
        workDir: Path?,
        logFile: Path?,
        val fakeSubprocess: FakeSubprocess?,
        options: WorkerOptions?
    ) : SingleplexWorker(workerKey, workerId, workDir, logFile, options, null) {
        protected override fun createProcess(clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?): Subprocess? {
            return fakeSubprocess
        }
    }

    /**
     * The [Worker] object uses a [Subprocess] to interact with persistent worker
     * binaries. Since this test is strictly testing [Worker] and not any outside persistent
     * worker binaries, a [FakeSubprocess] instance is used to fake the [InputStream] and
     * [OutputStream] that normally write and read from a persistent worker.
     */
    internal class FakeSubprocess : Subprocess {
        private val inputStream: java.io.InputStream?
        private val outputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        private val errStream: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0))
        private var wasDestroyed = false

        /** Creates a fake Subprocess that writes `bytes` to its "stdout".  */
        constructor(bytes: ByteArray) {
            inputStream = ByteArrayInputStream(bytes)
        }

        constructor(responseStream: java.io.InputStream?) {
            this.inputStream = responseStream
        }

        public override fun getInputStream(): java.io.InputStream? {
            return inputStream
        }

        public override fun getOutputStream(): java.io.OutputStream {
            return outputStream
        }

        val errorStream: java.io.InputStream
            get() = errStream

        @kotlin.jvm.Synchronized
        public override fun destroy(): Boolean {
            for (stream in arrayOf<java.io.Closeable>(inputStream, outputStream, errStream)) {
                try {
                    stream.close()
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException(e)
                }
            }

            wasDestroyed = true
            return true
        }

        public override fun exitValue(): Int {
            return 0
        }

        public override fun finished(): Boolean {
            return true
        }

        public override fun timedout(): Boolean {
            return false
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun waitFor() {
            // Do nothing.
        }

        public override fun close() {
            // Do nothing.
        }

        @get:kotlin.jvm.Synchronized
        val isAlive: Boolean
            get() = !wasDestroyed

        val processId: Long
            get() = 0
    }
}
