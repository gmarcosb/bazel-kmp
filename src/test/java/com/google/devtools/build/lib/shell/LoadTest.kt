// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.util.OS

/** Tests [Command] execution under load.  */
@RunWith(JUnit4::class)
class LoadTest {
    private var tempFile: java.io.File? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createTempFile() {
        // enable all log statements to ensure there are no problems with
        // logging code
        java.util.logging.Logger.getLogger("com.google.devtools.build.lib.shell.Command")
            .setLevel(java.util.logging.Level.FINEST)

        // create a temp file
        tempFile = java.io.File.createTempFile("LoadTest", "txt")
        if (tempFile.exists()) {
            tempFile.delete()
        }
        tempFile.deleteOnExit()

        PrintWriter(FileWriter(tempFile)).use { out ->
            val r: Random = Random()
            for (i in 0..99) {
                out.println(r.nextDouble().toString())
            }
        }
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun deleteTempFile() {
        tempFile.delete()
    }

    @org.junit.Test
    @Throws(Throwable::class)
    fun testLoad() {
        val runfiles: Runfiles = Runfiles.create()
        var catBin: String? =
            "io_bazel/src/test/java/com/google/devtools/build/lib/shell/cat_file"
        if (OS.getCurrent() === OS.WINDOWS) {
            catBin += ".exe"
        }
        catBin = runfiles.rlocation(catBin)

        val command: Command =
            Command(
                com.google.common.collect.ImmutableList.of<E?>(catBin, tempFile.getAbsolutePath()),
                java.lang.System.getenv()
            )
        val threads: Array<java.lang.Thread?> = arrayOfNulls<java.lang.Thread>(10)
        val exceptions: MutableList<Throwable> =
            Collections.synchronizedList<Throwable?>(java.util.ArrayList<Throwable?>())
        for (i in threads.indices) {
            threads[i] = java.lang.Thread(LoadThread(command, exceptions))
        }
        for (i in threads.indices) {
            threads[i].start()
        }
        for (i in threads.indices) {
            threads[i].join()
        }
        if (!exceptions.isEmpty()) {
            for (t in exceptions) {
                t.printStackTrace()
            }
            throw exceptions.get(0)
        }
    }

    private class LoadThread(command: Command, exception: MutableList<Throwable>) : java.lang.Runnable {
        private val command: Command
        private val exception: MutableList<Throwable>

        init {
            this.command = command
            this.exception = exception
        }

        override fun run() {
            try {
                for (i in 0..19) {
                    command.execute()
                }
            } catch (t: Throwable) {
                exception.add(t)
            }
        }
    }

    companion object {
        init {
            WindowsSubprocessFactory.maybeInstallWindowsSubprocessFactory()
        }
    }
}
