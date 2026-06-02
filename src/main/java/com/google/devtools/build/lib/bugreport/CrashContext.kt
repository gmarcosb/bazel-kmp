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
package com.google.devtools.build.lib.bugreport

import com.google.common.base.MoreObjects
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.EventHandler
import com.google.errorprone.annotations.CanIgnoreReturnValue

/** Context describing when a [Crash] occurred and how it should be handled.  */
class CrashContext private constructor(private val haltJvm: Boolean, private val returnIfCrashInProgress: Boolean) {
    var args: ImmutableList<String?> = ImmutableList.of<String?>()
        private set
    private var sendBugReport = true
    var extraOomInfo: String? = ""
        private set

    /**
     * Sets the path at which to write a heap dump when handling [OutOfMemoryError].
     * 
     * 
     * The path *must* end in `.hprof` for the heap dump to succeed.
     * 
     * 
     * If not called, there will be no heap dump.
     */
    @kotlin.jvm.JvmField
    var heapDumpPath: String? = null
    var eventHandler: EventHandler? =
        EventHandler { event: Event? -> System.err.println(event!!.getKind().toString() + ": " + event.getMessage()) }
        private set

    /** Sets the arguments that [BugReporter] should include with the bug report.  */
    @CanIgnoreReturnValue
    fun withArgs(vararg args: String?): CrashContext {
        this.args = ImmutableList.copyOf<String?>(args)
        return this
    }

    /** Sets the arguments that [BugReporter] should include with the bug report.  */
    @CanIgnoreReturnValue
    fun withArgs(args: MutableList<String?>): CrashContext {
        this.args = ImmutableList.copyOf<String?>(args)
        return this
    }

    /** Disables bug reporting.  */
    @CanIgnoreReturnValue
    fun withoutBugReport(): CrashContext {
        sendBugReport = false
        return this
    }

    /**
     * Sets a custom additional message that should be including when handling an [ ].
     */
    @CanIgnoreReturnValue
    fun withExtraOomInfo(extraOomInfo: String?): CrashContext {
        this.extraOomInfo = extraOomInfo
        return this
    }

    /**
     * Sets the [EventHandler] that should be notified about the [EventKind.FATAL] crash
     * event.
     * 
     * 
     * If this method is not called, the event is printed to [System.err].
     */
    @CanIgnoreReturnValue
    fun reportingTo(eventHandler: EventHandler?): CrashContext {
        this.eventHandler = eventHandler
        return this
    }

    fun shouldHaltJvm(): Boolean {
        return haltJvm
    }

    fun shouldSendBugReport(): Boolean {
        return sendBugReport
    }

    fun returnIfCrashInProgress(): Boolean {
        return returnIfCrashInProgress
    }

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
            .add("haltJvm", haltJvm)
            .add("args", args)
            .add("sendBugReport", sendBugReport)
            .add("extraOomInfo", extraOomInfo)
            .add("eventHandler", eventHandler)
            .toString()
    }

    companion object {
        /**
         * Creates a [CrashContext] that instructs [BugReporter] to halt the JVM when handling
         * a crash.
         * 
         * 
         * This should only be used when it is not feasible to conduct an orderly shutdown, for example
         * a crash in an async thread.
         */
        @kotlin.jvm.JvmStatic
        fun halt(): CrashContext {
            return CrashContext( /* haltJvm= */true,  /* returnIfCrashInProgress= */false)
        }

        /**
         * Creates a [CrashContext] that instructs [BugReporter] *not* to halt the JVM
         * when handling a crash.
         * 
         * 
         * The caller is responsible for terminating the server with an appropriate exit code.
         */
        @kotlin.jvm.JvmStatic
        fun keepAlive(): CrashContext {
            return CrashContext( /* haltJvm= */false,  /* returnIfCrashInProgress= */false)
        }

        /**
         * Creates a [CrashContext] that instructs [BugReporter] to halt the JVM when handling
         * a crash if there is no other crash in progress, and return otherwise.
         * 
         * 
         * This should only be used when it is not feasible to conduct an orderly shutdown, for example
         * a crash in an async thread, and where that async thread must make progress while another crash
         * is already shutting down the `BlazeRuntime`. This can prevent deadlocks during shutdown.
         */
        @kotlin.jvm.JvmStatic
        fun haltOrReturnIfCrashInProgress(): CrashContext {
            return CrashContext( /* haltJvm= */true,  /* returnIfCrashInProgress= */true)
        }
    }
}
