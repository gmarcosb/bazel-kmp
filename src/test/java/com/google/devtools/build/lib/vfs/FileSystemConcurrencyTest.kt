// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.testutil.TestThread
import com.google.devtools.build.lib.testutil.TestThread.TestRunnable
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * (Slow) tests of FileSystem under concurrency.
 * 
 * These tests are nondeterministic but provide good coverage nonetheless.
 */
@RunWith(JUnit4::class)
class FileSystemConcurrencyTest {
    var workingDir: Path? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeFileSystem() {
        val testFS: FileSystem = com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem()

        // Resolve symbolic links in the temp dir:
        workingDir =
            testFS.getPath(java.io.File(com.google.devtools.build.lib.testutil.TestUtils.tmpDir()).getCanonicalPath())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConcurrentSymlinkModifications() {
        val xFile: Path? = workingDir.getRelative("file")
        FileSystemUtils.createEmptyFile(xFile)

        val xLinkToFile: Path = workingDir.getRelative("link")

        val run: AtomicBoolean = AtomicBoolean(true)
        val createThread: TestThread =
            TestThread(
                TestRunnable {
                    while (run.get()) {
                        if (!xLinkToFile.exists()) {
                            xLinkToFile.createSymbolicLink(xFile)
                        }
                    }
                })
        val deleteThread: TestThread =
            TestThread(
                TestRunnable {
                    while (run.get()) {
                        if (xLinkToFile.exists(Symlinks.NOFOLLOW)) {
                            xLinkToFile.delete()
                        }
                    }
                })
        createThread.start()
        deleteThread.start()
        java.lang.Thread.sleep(1000)
        run.set(false)
        createThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        deleteThread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
    }
}
