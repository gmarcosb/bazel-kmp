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
package com.google.devtools.build.lib.events

import com.google.devtools.build.lib.util.io.OutErr
import java.io.IOException

/**
 * An event handler that prints to an OutErr stream pair in a
 * canonical format, for example:
 * <pre>
 * ERROR: /home/jrluser/src/workspace/x/BUILD:23:1: syntax error.
</pre> * 
 * This syntax is parseable by Emacs's compile.el.
 */
class PrintingEventHandler(outErr: OutErr, mask: MutableSet<com.google.devtools.build.lib.events.EventKind?>?) :
    com.google.devtools.build.lib.events.AbstractEventHandler(mask), com.google.devtools.build.lib.events.EventHandler {
    private var outErr: OutErr

    /**
     * Setup a printing event handler that prints events matching the mask.
     */
    init {
        this.outErr = outErr
    }

    /**
     * Setup a printing event handler that prints events matching the mask. Events are printed to the
     * System.out and System.err unless/until redirected by a call to setOutErr().
     */
    constructor(mask: MutableSet<com.google.devtools.build.lib.events.EventKind?>?) : this(OutErr.SYSTEM_OUT_ERR, mask)

    /**
     * Redirect all output to the specified OutErr stream pair.
     * Returns the previous OutErr.
     */
    fun setOutErr(outErr: OutErr): OutErr? {
        val prev: OutErr? = this.outErr
        this.outErr = outErr
        return prev
    }

    /**
     * Print a description of the specified event to the appropriate
     * output or error stream.
     */
    override fun handle(event: com.google.devtools.build.lib.events.Event) {
        if (!getEventMask().contains(event.getKind())) {
            handleFollowUpEvents(event)
            return
        }
        try {
            when (event.getKind()) {
                com.google.devtools.build.lib.events.EventKind.STDOUT -> {
                    outErr.getOutputStream().write(event.getMessageBytes())
                    outErr.getOutputStream().flush()
                }

                com.google.devtools.build.lib.events.EventKind.STDERR -> {
                    outErr.getErrorStream().write(event.getMessageBytes())
                    outErr.getErrorStream().flush()
                }

                else -> {
                    val builder: java.lang.StringBuilder = java.lang.StringBuilder()
                    builder.append(event.getKind()).append(": ")
                    if (event.getLocation() != null) {
                        builder.append(event.getLocation()).append(": ")
                    }
                    builder.append(event.getMessage()).append("\n")
                    outErr.getErrorStream().write(builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    outErr.getErrorStream().flush()
                }
            }
        } catch (e: IOException) {
            /*
       * Note: we can't print to System.out or System.err here,
       * because those will normally be set to streams which
       * translate I/O to STDOUT and STDERR events,
       * which would result in infinite recursion.
       */
            outErr.printErrLn(e.getMessage())
        }
        handleFollowUpEvents(event)
    }

    private fun handleFollowUpEvents(event: com.google.devtools.build.lib.events.Event) {
        val stderr: ByteArray? = event.getStdErr()
        if (stderr != null) {
            handle(
                com.google.devtools.build.lib.events.Event.Companion.of(
                    com.google.devtools.build.lib.events.EventKind.STDERR,
                    null,
                    stderr
                )
            )
        }
        val stdout: ByteArray? = event.getStdOut()
        if (stdout != null) {
            handle(
                com.google.devtools.build.lib.events.Event.Companion.of(
                    com.google.devtools.build.lib.events.EventKind.STDOUT,
                    null,
                    stdout
                )
            )
        }
    }

    companion object {
        /**
         * A convenient event-handler for terminal applications that prints all
         * errors and warnings it encounters to the error stream.
         * STDOUT and STDERR events pass their output directly
         * through to the corresponding streams.
         */
        @kotlin.jvm.JvmField
        val ERRORS_AND_WARNINGS_TO_STDERR: PrintingEventHandler =
            com.google.devtools.build.lib.events.PrintingEventHandler(com.google.devtools.build.lib.events.EventKind.Companion.ERRORS_AND_WARNINGS_AND_OUTPUT)

        /**
         * A convenient event-handler for terminal applications that prints all
         * errors it encounters to the error stream.
         * STDOUT and STDERR events pass their output directly
         * through to the corresponding streams.
         */
        val ERRORS_TO_STDERR: PrintingEventHandler =
            com.google.devtools.build.lib.events.PrintingEventHandler(com.google.devtools.build.lib.events.EventKind.Companion.ERRORS_AND_OUTPUT)
    }
}
