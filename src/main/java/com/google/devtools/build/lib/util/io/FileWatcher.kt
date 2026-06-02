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
package com.google.devtools.build.lib.util.io

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * The FileWatcher dumps the contents of a files into an OutErr.
 * It then stays active and dumps any content to the OutErr that is
 * added to the file, until it is told to stop and all output has
 * been dumped.
 * 
 * This is useful to emulate streaming test output.
 */
@ThreadSafe
class FileWatcher(outputFile: com.google.devtools.build.lib.vfs.Path, output: OutErr, skipExisting: Boolean) :
    java.lang.Thread("Streaming Test Output Pump") {
    private val outputFile: com.google.devtools.build.lib.vfs.Path
    private val output: OutErr

    @kotlin.concurrent.Volatile
    private var finishPumping: Boolean
    private var toSkip: Long = 0

    /**
     * Creates a FileWatcher that will dump any output that is appended to
     * outputFile onto output. If skipExisting is true, the watcher will not dump
     * any output that is in outputFile when we construct the watcher. If
     * skipExisting is false, already existing output will be dumped, too.
     * 
     * @param outputFile the File to watch
     * @param output the outErr to dump the files contents to
     * @param skipExisting whether to dump already existing output or not.
     */
    init {
        this.outputFile = outputFile
        this.output = output
        finishPumping = false

        if (outputFile.exists() && skipExisting) {
            toSkip = outputFile.getFileSize()
        }
    }

    /**
     * Tells the FileWatcher to stop pumping output and finish.
     * The FileWatcher will only finish until there is no data left to display.
     * This means that it is rarely a good idea to unconditionally wait for the
     * FileWatcher thread to terminate -- Instead, it is better to have a timeout.
     */
    @ThreadSafe
    fun stopPumping() {
        finishPumping = true
    }

    override fun run() {
        try {
            // Wait until the file exists, or we have to abort.

            while (!outputFile.exists() && !finishPumping) {
                java.lang.Thread.sleep(WATCH_INTERVAL.toLong())
            }

            // Check that we did not have abort before the file was created.
            if (outputFile.exists()) {
                outputFile.getInputStream().use { inputStream ->
                    output.getOutputStream().use { outputStream ->
                        val buffer = ByteArray(1024)
                        while (!finishPumping || (inputStream.available() != 0)) {
                            if (inputStream.available() != 0) {
                                if (toSkip > 0) {
                                    toSkip -= inputStream.skip(toSkip)
                                } else {
                                    val read: Int = inputStream.read(buffer)
                                    if (read > 0) {
                                        outputStream.write(buffer, 0, read)
                                    }
                                }
                            } else {
                                java.lang.Thread.sleep(WATCH_INTERVAL.toLong())
                            }
                        }
                    }
                }
            }
        } catch (ex: IOException) {
            output.printOutLn("Failure reading or writing: " + ex.message)
        } catch (ex: java.lang.InterruptedException) {
            // Don't do anything.
        }
    }

    companion object {
        // How often we check for updates in the file we watch. (in ms)
        private const val WATCH_INTERVAL = 100
    }
}
