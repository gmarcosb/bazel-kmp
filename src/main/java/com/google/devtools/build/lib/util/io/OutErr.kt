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

import java.io.IOException
import java.io.PrintStream
import java.io.PrintWriter

/**
 * A pair of output streams to be used for redirecting the output and error streams of a subprocess.
 */
open class OutErr protected constructor(out: java.io.OutputStream?, err: java.io.OutputStream?) : java.io.Closeable {
    private val out: java.io.OutputStream
    private val err: java.io.OutputStream

    init {
        this.out = com.google.common.base.Preconditions.checkNotNull<java.io.OutputStream>(out)
        this.err = com.google.common.base.Preconditions.checkNotNull<java.io.OutputStream>(err)
    }

    @Throws(IOException::class)
    override fun close() {
        // Ensure that we close both out and err even if one throws.
        try {
            out.close()
        } finally {
            if (out !== err) {
                err.close()
            }
        }
    }

    val systemPatcher: SystemPatcher
        /** Returns a [SystemPatcher] that uses this instance's out and err streams.  */
        get() = SystemPatcher(out, err)

    /**
     * Temporarily patches [System.out] and [System.err] with custom streams.
     * 
     * 
     * [.start] is called to signal the beginning of the scope of the patch. [.close]
     * ends the scope of the patch, returning [System.out] and [System.err] to what they
     * were when this instance was instantiated.
     */
    class SystemPatcher private constructor(overrideOut: java.io.OutputStream, overrideErr: java.io.OutputStream) :
        java.lang.AutoCloseable {
        private val savedOut: PrintStream?
        private val savedErr: PrintStream?
        private val outPatch: SwitchingPrintStream
        private val errPatch: SwitchingPrintStream

        init {
            this.savedOut = java.lang.System.out
            this.savedErr = java.lang.System.err
            this.outPatch = SwitchingPrintStream(overrideOut)
            this.errPatch = SwitchingPrintStream(overrideErr)
        }

        fun start() {
            java.lang.System.setOut(outPatch)
            java.lang.System.setErr(errPatch)
        }

        override fun close() {
            java.lang.System.setOut(savedOut)
            java.lang.System.setErr(savedErr)
            outPatch.switchBackTo(savedOut)
            errPatch.switchBackTo(savedErr)
        }
    }

    /**
     * Starts by streaming to `override`, then switches back to `saved`.
     * 
     * 
     * The switching strategy is used to guard against memory leaks. For example, if `override` is passed directly to [System.setErr], anyone may retain a reference to it via
     * [System.err]. Instead, they will get a reference to this class, which frees up `override` in [.switchBackTo].
     */
    private class SwitchingPrintStream(override: java.io.OutputStream) : PrintStream(override,  /*autoFlush=*/true) {
        fun switchBackTo(saved: java.io.OutputStream?) {
            out = saved
        }
    }

    open val outputStream: java.io.OutputStream
        get() = out

    open val errorStream: java.io.OutputStream
        get() = err

    /** Writes the specified string to the output stream, and flushes.  */
    fun printOut(s: String?) {
        val writer: PrintWriter = PrintWriter(out, true)
        writer.print(s)
        writer.flush()
    }

    fun printOutLn(s: String?) {
        printOut(s + "\n")
    }

    /** Writes the specified string to the error stream, and flushes.  */
    fun printErr(s: String?) {
        val writer: PrintWriter = PrintWriter(err, true)
        writer.print(s)
        writer.flush()
    }

    fun printErrLn(s: String?) {
        printErr(s + "\n")
    }

    companion object {
        @kotlin.jvm.JvmField
        val SYSTEM_OUT_ERR: OutErr = create(java.lang.System.out, java.lang.System.err)

        /** Creates a new OutErr instance from the specified output and error streams.  */
        fun create(out: java.io.OutputStream?, err: java.io.OutputStream?): OutErr {
            return OutErr(out, err)
        }
    }
}
