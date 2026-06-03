// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.shell.Subprocess

/**
 * Runs an external process that holds a shared or exclusive lock on a file.
 * 
 * 
 * This is needed for testing because the JVM does not allow overlapping locks.
 */
class ExternalFileSystemLock private constructor(lockPath: Path, shared: Boolean) : java.lang.AutoCloseable {
    private val subprocess: Subprocess

    init {
        val binaryPath: String? = Runfiles.preload().withSourceRepository("").rlocation(HELPER_PATH)
        this.subprocess =
            SubprocessBuilder(java.lang.System.getenv())
                .setArgv(
                    com.google.common.collect.ImmutableList.of<E?>(
                        binaryPath, lockPath.getPathString(), if (shared) "shared" else "exclusive", "sleep"
                    )
                )
                .start()
        // Wait for child to report that the lock has been acquired.
        // We could read the entire stdout/stderr here to obtain additional debugging information,
        // but for some reason that hangs forever on Windows, even if we close them on the child side.
        if (subprocess.inputStream.read() !== '!') {
            throw IOException("external helper process failed")
        }
    }

    @Throws(IOException::class)
    override fun close() {
        // Wait for process to exit and release the lock.
        subprocess.destroyAndWait()
    }

    companion object {
        init {
            WindowsSubprocessFactory.maybeInstallWindowsSubprocessFactory()
        }

        private val HELPER_PATH =
            ("io_bazel/src/test/java/com/google/devtools/build/lib/testutil/external_file_system_lock_helper"
                    + (if (OS.getCurrent() === OS.WINDOWS) ".exe" else ""))

        @Throws(IOException::class)
        fun getShared(lockPath: Path): ExternalFileSystemLock {
            return ExternalFileSystemLock(lockPath, true)
        }

        @Throws(IOException::class)
        fun getExclusive(lockPath: Path): ExternalFileSystemLock {
            return ExternalFileSystemLock(lockPath, false)
        }
    }
}
