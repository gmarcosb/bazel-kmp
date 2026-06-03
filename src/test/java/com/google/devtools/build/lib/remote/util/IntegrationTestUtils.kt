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
package com.google.devtools.build.lib.remote.util

import com.google.devtools.build.lib.shell.Subprocess

/** Integration test utilities.  */
object IntegrationTestUtils {
    private val WORKER_RLOCATIONPATH = ("io_bazel/src/tools/remote/worker"
            + (if (OS.getCurrent() === OS.WINDOWS) ".exe" else ""))

    /**
     * Manages a remote worker instance as a [TestRule].
     * 
     * 
     * Should be kept in a static variable annotated with both [org.junit.ClassRule] and
     * [org.junit.Rule].
     */
    /**
     * Manages a remote worker instance as a [TestRule].
     * 
     * 
     * Should be kept in a static variable annotated with both [org.junit.ClassRule] and
     * [org.junit.Rule].
     */
    @kotlin.jvm.JvmOverloads
    fun createWorker(useHttp: Boolean = false): WorkerInstance {
        // The worker directory must not be a subdirectory of the test temporary directory for two
        // reasons:
        // 1. It should be preserved between individual tests so that the worker can be kept running.
        // 2. Even if that wasn't needed, JUnit runs "after" methods of rules after those of
        //    superclasses, which means that BuildIntegrationtestCase's cleanup method would attempt
        //    to delete the worker directory before the worker is stopped, which fails on Windows.
        val workerTmpDir: Path
        try {
            workerTmpDir = java.nio.file.Files.createTempDirectory(systemTmpDir(), "remote.")
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
        return WorkerInstance(useHttp, workerTmpDir)
    }

    private fun systemTmpDir(): Path? {
        if (OS.getCurrent() === OS.WINDOWS) {
            return Path.of(java.lang.System.getenv("TEMP"))
        }
        var tmpdir: String? = java.lang.System.getenv("TMPDIR")
        if (tmpdir == null) {
            tmpdir = "/tmp"
        }
        return Path.of(tmpdir)
    }

    @Throws(IOException::class)
    private fun ensureMkdir(path: Path?) {
        if (!java.nio.file.Files.notExists(path)) {
            throw IOException(path.toString() + " already exists")
        }
        java.nio.file.Files.createDirectories(path)
    }

    class WorkerInstance private constructor(private val useHttp: Boolean, dir: Path) : org.junit.rules.TestRule {
        private val stdPath: Path
        private val stdoutPath: Path
        private val stderrPath: Path
        private val workPath: Path
        private val casPath: Path

        private var port: Int? = null
        private var process: Subprocess? = null

        init {
            this.stdPath = dir.resolve("std")
            this.stdoutPath = stdPath.resolve("stdout")
            this.stderrPath = stdPath.resolve("stderr")
            this.workPath = dir.resolve("work_path")
            this.casPath = dir.resolve("cas_path")
        }

        override fun apply(
            base: org.junit.runners.model.Statement,
            description: org.junit.runner.Description
        ): org.junit.runners.model.Statement? {
            if (description.isSuite()) {
                return object : org.junit.runners.model.Statement() {
                    @Throws(Throwable::class)
                    override fun evaluate() {
                        start()
                        try {
                            base.evaluate()
                        } finally {
                            stop()
                        }
                    }
                }
            } else if (description.isTest()) {
                return object : org.junit.runners.model.Statement() {
                    @Throws(Throwable::class)
                    override fun evaluate() {
                        try {
                            base.evaluate()
                        } finally {
                            reset()
                        }
                    }
                }
            } else {
                return base
            }
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun start() {
            com.google.common.base.Preconditions.checkState(process == null)
            com.google.common.base.Preconditions.checkState(port == null)

            ensureMkdir(workPath)
            ensureMkdir(casPath)
            ensureMkdir(stdPath)
            java.nio.file.Files.createFile(stdoutPath)
            java.nio.file.Files.createFile(stderrPath)
            val runfiles: Runfiles = Runfiles.preload().withSourceRepository("")
            val workerPath: String? = runfiles.rlocation(WORKER_RLOCATIONPATH)
            val env: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            env.putAll(java.lang.System.getenv())
            env.putAll(runfiles.getEnvVars())
            port = FreePortFinder.pickUnusedRandomPort()
            process =
                SubprocessBuilder(java.lang.System.getenv())
                    .setEnv(env.buildKeepingLast())
                    .setStdout(stdoutPath.toFile())
                    .setStderr(stderrPath.toFile())
                    .setArgv(
                        com.google.common.collect.ImmutableList.of<E?>(
                            workerPath,
                            "--work_path=" + workPath,
                            "--cas_path=" + casPath,
                            (if (useHttp) "--http_listen_port=" else "--listen_port=") + port
                        )
                    )
                    .start()
            waitForPortOpen(process, port!!)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun waitForPortOpen(process: Subprocess, port: Int) {
            val addr: InetSocketAddress = InetSocketAddress("localhost", port)
            val timeout: IOException = IOException("Timed out while trying to connect to worker")
            for (i in 0..19) {
                if (!process.isAlive) {
                    throw IOException(
                        java.lang.String.format(
                            ("Worker died while trying to connect\n"
                                    + "----- STDOUT -----\n%s\n"
                                    + "----- STDERR -----\n%s\n"),
                            this.stdout, this.stderr
                        )
                    )
                }

                try {
                    java.nio.channels.SocketChannel.open().use { socketChannel ->
                        socketChannel.configureBlocking( /* block= */true)
                        socketChannel.connect(addr)
                    }
                    return
                } catch (e: IOException) {
                    timeout.addSuppressed(e)
                    java.lang.Thread.sleep(1000)
                }
            }
            throw timeout
        }

        @Throws(IOException::class)
        private fun stop() {
            com.google.common.base.Preconditions.checkNotNull<Any?>(process)
            process.destroyAndWait()
            process = null

            deleteTree(stdPath)
            deleteTree(workPath)
            deleteTree(casPath)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun reset() {
            // The DiskCacheClient in the worker expects the CAS subdirectories to exist.
            val toClear: MutableList<Path?>?
            java.nio.file.Files.list(casPath).use { stream ->
                toClear = stream.toList()
            }
            for (path in toClear!!) {
                deleteTree(path)
                ensureMkdir(path)
            }
        }

        val stdout: String
            get() {
                try {
                    val out: ByteArray = java.nio.file.Files.readAllBytes(stdoutPath)
                    return String(out, java.nio.charset.StandardCharsets.UTF_8)
                } catch (e: IOException) {
                    throw java.lang.RuntimeException(e)
                }
            }

        val stderr: String
            get() {
                try {
                    val out: ByteArray = java.nio.file.Files.readAllBytes(stderrPath)
                    return String(out, java.nio.charset.StandardCharsets.UTF_8)
                } catch (e: IOException) {
                    throw java.lang.RuntimeException(e)
                }
            }

        fun getPort(): Int {
            return port!!
        }

        fun getCasPath(): PathFragment {
            return PathFragment.create(casPath.toString())
        }

        companion object {
            @Throws(IOException::class)
            private fun deleteTree(path: Path?) {
                val toDelete: MutableList<Path>?
                java.nio.file.Files.walk(path).use { stream ->
                    toDelete = stream.sorted(java.util.Comparator.reverseOrder<Path?>()).toList()
                }
                for (p in toDelete!!) {
                    java.nio.file.Files.delete(p)
                }
            }
        }
    }
}
