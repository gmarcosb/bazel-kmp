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
import java.io.PrintStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The reporter is the primary means of reporting events such as errors, warnings, progress
 * information and diagnostic information to the user. It is not intended as a logging mechanism for
 * developer-only messages; use a Logger for that.
 * 
 * 
 * The reporter instance is consumed by the build system, and passes events using [ ][.handle] or [.post] to [EventHandler] instances. The latter only
 * occurs to the [EventHandler] instances that are also [ExtendedEventHandler]
 * instances.
 * 
 * 
 * The reporter's main use is in the blaze runtime and its lifetime is the lifetime of the blaze
 * server.
 * 
 * 
 * Thread-safe: calls to `#report` may be made on any thread. Handlers may be run in an
 * arbitrary thread (but right now, they will not be run concurrently).
 */
class Reporter : com.google.devtools.build.lib.events.ExtendedEventHandler {
    /** Set of [EventHandler] registered in this reporter.  */
    private val eventHandlers: ConcurrentLinkedQueue<com.google.devtools.build.lib.events.EventHandler> =
        ConcurrentLinkedQueue<com.google.devtools.build.lib.events.EventHandler>()

    /**
     * An OutErr that sends all of its output to this Reporter. Each write will (when flushed) get
     * mapped to an EventKind.STDOUT or EventKind.STDERR event.
     */
    private val outErrToReporter: OutErr =
        com.google.devtools.build.lib.events.Reporter.Companion.outErrForReporter(this)

    @kotlin.concurrent.Volatile
    private var outputFilter: com.google.devtools.build.lib.events.OutputFilter =
        com.google.devtools.build.lib.events.OutputFilter.Companion.OUTPUT_EVERYTHING
    private var ansiAllowingHandler: com.google.devtools.build.lib.events.EventHandler? = null
    private var ansiStrippingHandler: com.google.devtools.build.lib.events.EventHandler? = null
    private var ansiAllowingHandlerRegistered = false

    constructor()

    /**
     * A copy constructor, to make it convenient to replicate a reporter config for temporary
     * configuration changes.
     */
    constructor(template: Reporter) {
        eventHandlers.addAll(template.eventHandlers)
    }

    /** Constructor which configures a reporter with the specified handlers.  */
    constructor(vararg handlers: com.google.devtools.build.lib.events.EventHandler?) {
        for (handler in handlers) {
            addHandler(handler)
        }
    }

    val outErr: OutErr
        /**
         * Returns an OutErr that sends all of its output to this Reporter. Each write to the OutErr will
         * cause an EventKind.STDOUT or EventKind.STDERR event.
         */
        get() = outErrToReporter

    /** Registers an [EventHandler] in this reporter.  */
    fun addHandler(handler: com.google.devtools.build.lib.events.EventHandler?) {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.events.EventHandler?>(handler)
        eventHandlers.add(handler)
    }

    /**
     * Removes an [EventHandler] from this reporter. If the handler wasn't registered in this
     * reporter this method is a no-op.
     */
    fun removeHandler(handler: com.google.devtools.build.lib.events.EventHandler?) {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.events.EventHandler?>(handler)
        eventHandlers.remove(handler)
    }

    /**
     * Handle the provided [Event] using all the [EventHandler] registered in this
     * reporter.
     */
    override fun handle(e: com.google.devtools.build.lib.events.Event) {
        if (e.getKind() != com.google.devtools.build.lib.events.EventKind.ERROR && e.getKind() != com.google.devtools.build.lib.events.EventKind.DEBUG && e.getTag() != null && !showOutput(
                e.getTag()
            )
        ) {
            return
        }

        for (handler in eventHandlers) {
            handler.handle(e)
        }
    }

    /**
     * Post the provided [com.google.devtools.build.lib.events.ExtendedEventHandler.Postable] to
     * all the [ExtendedEventHandler] registered in this reporter.
     */
    override fun post(obj: com.google.devtools.build.lib.events.ExtendedEventHandler.Postable?) {
        for (eventHandler in eventHandlers) {
            if (eventHandler is com.google.devtools.build.lib.events.ExtendedEventHandler) {
                eventHandler.post(obj)
            }
        }
    }

    /**
     * Triggers the cleanup from each [ExtendedEventHandler] registered in this reporter.
     * 
     * 
     * This method is called when the reporter is no longer needed.
     */
    override fun cleanup() {
        for (eventHandler in eventHandlers) {
            if (eventHandler is com.google.devtools.build.lib.events.ExtendedEventHandler) {
                eventHandler.cleanup()
            }
        }
    }

    /**
     * Reports the start of a particular task. Is a wrapper around report() with event kind START.
     * Should always be matched by a corresponding call to finishTask() with the same message, except
     * that the leading percentage progress indicator (if any) in the message may differ.
     */
    fun startTask(location: net.starlark.java.syntax.Location?, message: String?) {
        handle(
            com.google.devtools.build.lib.events.Event.Companion.of(
                com.google.devtools.build.lib.events.EventKind.START,
                location,
                message
            )
        )
    }

    /**
     * Reports the end of a particular task. Is a wrapper around report() with event kind FINISH.
     * Should always be matched by a corresponding call to startTask() with the same message, except
     * that the leading percentage progress indicator (if any) in the message may differ.
     */
    fun finishTask(location: net.starlark.java.syntax.Location?, message: String?) {
        handle(
            com.google.devtools.build.lib.events.Event.Companion.of(
                com.google.devtools.build.lib.events.EventKind.FINISH,
                location,
                message
            )
        )
    }

    @kotlin.jvm.JvmOverloads
    fun error(location: net.starlark.java.syntax.Location?, message: String?, error: Throwable? = null) {
        handle(com.google.devtools.build.lib.events.Event.Companion.error(location, message))
        if (error != null) {
            error.printStackTrace(PrintStream(this.outErr.getErrorStream()))
        }
    }

    /** Returns true iff the given tag matches the output filter.  */
    fun showOutput(tag: String?): Boolean {
        return outputFilter.showOutput(tag)
    }

    fun setOutputFilter(outputFilter: com.google.devtools.build.lib.events.OutputFilter) {
        this.outputFilter = outputFilter
    }

    /**
     * Registers an ANSI-control-code-allowing EventHandler with an ANSI-stripping EventHandler that
     * is already registered with the reporter. The ANSI-stripping handler can then be replaced with
     * the ANSI-allowing handler by calling `#switchToAnsiAllowingHandler` which calls `removeHandler` for the ANSI-stripping handler and then `addHandler` for the ANSI-allowing
     * handler.
     */
    @kotlin.jvm.Synchronized
    fun registerAnsiAllowingHandler(
        ansiStrippingHandler: com.google.devtools.build.lib.events.EventHandler?,
        ansiAllowingHandler: com.google.devtools.build.lib.events.EventHandler?
    ) {
        this.ansiAllowingHandler = ansiAllowingHandler
        this.ansiStrippingHandler = ansiStrippingHandler
        ansiAllowingHandlerRegistered = true
    }

    /**
     * Restores the ANSI-allowing EventHandler registered using [.registerAnsiAllowingHandler].
     */
    @kotlin.jvm.Synchronized
    fun switchToAnsiAllowingHandler() {
        if (ansiAllowingHandlerRegistered) {
            removeHandler(ansiStrippingHandler)
            addHandler(ansiAllowingHandler)
            ansiStrippingHandler = null
            ansiAllowingHandler = null
            ansiAllowingHandlerRegistered = false
        }
    }

    companion object {
        fun outErrForReporter(rep: com.google.devtools.build.lib.events.EventHandler?): OutErr {
            return OutErr.create( // We don't use BufferedOutputStream here, because in general the Blaze
                // code base assumes that the output streams are not buffered.
                com.google.devtools.build.lib.events.ReporterStream(
                    rep,
                    com.google.devtools.build.lib.events.EventKind.STDOUT
                ),
                com.google.devtools.build.lib.events.ReporterStream(
                    rep,
                    com.google.devtools.build.lib.events.EventKind.STDERR
                )
            )
        }
    }
}
