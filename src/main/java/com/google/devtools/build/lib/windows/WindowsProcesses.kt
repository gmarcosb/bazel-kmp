// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.windows

import com.google.devtools.build.lib.jni.JniLoader.loadJni

/** Process management on Windows.  */
object WindowsProcesses {
    val INVALID: Long = -1

    init {
        com.google.devtools.build.lib.jni.JniLoader.loadJni()
    }

    /**
     * Creates a process with the specified Windows command line.
     * 
     * 
     * Appropriately quoting arguments is the responsibility of the caller.
     * 
     * @param argv0 the binary to run; must be unquoted; must be either an absolute, normalized
     * Windows path with a drive letter (e.g. "c:\foo\bar app.exe") or a single file name (e.g.
     * "foo app.exe")
     * @param argvRest the rest of the command line, i.e. argv[1:] (needs to be quoted Windows style)
     * @param env the environment of the new process. null means inherit that of the Bazel server
     * @param cwd the working directory of the new process. If null, the same as that of the current
     * process.
     * @param stdoutFile the file the stdout should be redirected to. If null, [.getStdout] will
     * work.
     * @param stderrFile the file the stdout should be redirected to. If null, [.getStderr] will
     * work.
     * @param redirectErrorStream whether we merge the process's standard error and standard output.
     * @return the opaque identifier of the created process
     */
    @kotlin.jvm.JvmStatic
    @kotlin.jvm.JvmOverloads
    external fun createProcess(
        argv0: String?,
        argvRest: String?,
        env: ByteArray?,
        cwd: String?,
        stdoutFile: String?,
        stderrFile: String?,
        redirectErrorStream: Boolean = false
    ): Long

    /**
     * Writes data from the given array to the stdin of the specified process.
     * 
     * 
     * Blocks until either some data was written or the process is terminated.
     * 
     * @return the number of bytes written, or -1 if an error occurs.
     */
    @kotlin.jvm.JvmStatic
    external fun writeStdin(process: Long, bytes: ByteArray?, offset: Int, length: Int): Int

    /** Closes the stdin of the specified process.  */
    @kotlin.jvm.JvmStatic
    external fun closeStdin(process: Long)

    /** Returns an opaque identifier of stdout stream for the process.  */
    @kotlin.jvm.JvmStatic
    external fun getStdout(process: Long): Long

    /** Returns an opaque identifier of stderr stream for the process.  */
    @kotlin.jvm.JvmStatic
    external fun getStderr(process: Long): Long

    /**
     * Returns an estimate of the number of bytes available to read on the stream. Unlike [ ][InputStream.available], this returns 0 on closed or broken streams.
     */
    @kotlin.jvm.JvmStatic
    external fun streamBytesAvailable(stream: Long): Int

    /**
     * Reads data from the stream into the given array. `stream` should come from [ ][.getStdout] or [.getStderr].
     * 
     * 
     * Blocks until either some data was read or the process is terminated.
     * 
     * @return the number of bytes read, 0 on EOF, or -1 if there was an error.
     */
    @kotlin.jvm.JvmStatic
    external fun readStream(stream: Long, bytes: ByteArray?, offset: Int, length: Int): Int

    /**
     * Waits until the given process terminates. If timeout is non-negative, it indicates the number
     * of milliseconds before the call times out.
     * 
     * 
     * Return values:
     *  * 0: Process finished
     *  * 1: Timeout
     *  * 2: Something went wrong
     */
    @kotlin.jvm.JvmStatic
    external fun waitFor(process: Long, timeout: Long): Int

    /**
     * Returns the exit code of the process. Throws `IllegalStateException` if something goes
     * wrong.
     */
    @kotlin.jvm.JvmStatic
    external fun getExitCode(process: Long): Int

    /** Returns the process ID of the given process or -1 if there was an error.  */
    external fun getProcessPid(process: Long): Int

    /** Terminates the given process. Returns true if the termination was successful.  */
    @kotlin.jvm.JvmStatic
    external fun terminate(process: Long): Boolean

    /**
     * Releases the native data structures associated with the process.
     * 
     * 
     * Calling any other method on the same process after this call will result in the JVM crashing
     * or worse.
     */
    @kotlin.jvm.JvmStatic
    external fun deleteProcess(process: Long)

    /**
     * Closes the stream
     * 
     * @param stream should come from [.getStdout] or [.getStderr].
     */
    @kotlin.jvm.JvmStatic
    external fun closeStream(stream: Long)

    /**
     * Returns a string representation of the last error caused by any call on the given process or
     * the empty string if the last operation was successful.
     * 
     * 
     * Does **NOT** terminate the process if it is still running.
     * 
     * 
     * After this call returns, subsequent calls will return the empty string if there was no
     * failed operation in between.
     */
    @kotlin.jvm.JvmStatic
    external fun processGetLastError(process: Long): String?

    @kotlin.jvm.JvmStatic
    external fun streamGetLastError(process: Long): String?
}
