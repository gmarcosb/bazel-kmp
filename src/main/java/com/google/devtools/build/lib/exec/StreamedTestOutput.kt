// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.exec.TestLogHelper
import com.google.devtools.build.lib.exec.TestLogHelper.FilterTestHeaderOutputStream
import com.google.devtools.build.lib.util.io.FileWatcher
import com.google.devtools.build.lib.util.io.OutErr
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Implements the --test_output=streamed option.  */
class StreamedTestOutput(outErr: OutErr, testLogPath: com.google.devtools.build.lib.vfs.Path) : java.io.Closeable {
    private val headerFilter: FilterTestHeaderOutputStream
    private val watcher: FileWatcher
    private val testLogPath: com.google.devtools.build.lib.vfs.Path
    private val outErr: OutErr

    init {
        this.testLogPath = testLogPath
        this.outErr = outErr
        this.headerFilter = TestLogHelper.getHeaderFilteringOutputStream(outErr.getOutputStream())
        this.watcher = FileWatcher(testLogPath, OutErr.create(headerFilter, headerFilter), false)
        watcher.start()
    }

    @Throws(IOException::class)
    override fun close() {
        watcher.stopPumping()
        try {
            // The watcher thread might leak if the following call is interrupted.
            // This is a relatively minor issue since the worst it could do is
            // write one additional line from the test.log to the console later on
            // in the build.
            watcher.join()
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            watcher.interrupt()
            com.google.common.util.concurrent.Uninterruptibles.joinUninterruptibly(
                watcher, JOIN_ON_INTERRUPT_GRACE_PERIOD_SECONDS.toLong(), TimeUnit.SECONDS
            )
            com.google.common.base.Preconditions.checkState(
                !watcher.isAlive(),
                "Watcher thread failed to exit for %s seconds after interrupt",
                JOIN_ON_INTERRUPT_GRACE_PERIOD_SECONDS
            )
        }

        // It's unclear if writing this after interrupt is desirable, but it's been this way forever.
        if (!headerFilter.foundHeader()) {
            testLogPath.getInputStream().use { input ->
                com.google.common.io.ByteStreams.copy(input, outErr.getOutputStream())
            }
        }
    }

    @get:com.google.common.annotations.VisibleForTesting
    val fileWatcher: FileWatcher
        get() = watcher

    companion object {
        private const val JOIN_ON_INTERRUPT_GRACE_PERIOD_SECONDS = 30
    }
}
