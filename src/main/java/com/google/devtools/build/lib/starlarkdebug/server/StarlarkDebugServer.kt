// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkdebug.server

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Throwables
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.events.Event
import net.starlark.java.eval.Debug
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.syntax.Location

/** Manages the network socket and debugging state for threads running Starlark code.  */
class StarlarkDebugServer private constructor(
    eventHandler: EventHandler,
    transport: DebugServerTransport,
    verboseLogging: Boolean,
    callback: DebugCallback
) : Debug.Debugger {
    private val eventHandler: EventHandler
    private val callback: DebugCallback

    /** Handles all thread-related state.  */
    private val threadHandler: ThreadHandler

    /** The server socket for the debug server.  */
    private val transport: DebugServerTransport

    private val verboseLogging: Boolean

    init {
        this.eventHandler = eventHandler
        this.callback = callback
        this.threadHandler = ThreadHandler()
        this.transport = transport
        this.verboseLogging = verboseLogging
        listenForClientRequests()
    }

    /**
     * Starts a worker thread to listen for and handle incoming client requests, returning any
     * relevant responses.
     */
    private fun listenForClientRequests() {
        val clientThread =
            Thread(
                Runnable {
                    try {
                        while (true) {
                            val request: StarlarkDebuggingProtos.DebugRequest? = transport.readClientRequest()
                            if (request == null) {
                                return@Runnable
                            }
                            val response: DebugEvent? = handleClientRequest(request)
                            if (response != null) {
                                transport.postEvent(response)
                            }
                        }
                    } catch (e: Throwable) {
                        if (!transport.isClosed()) {
                            eventHandler.handle(
                                Event.error(
                                    "Debug server listener thread died: "
                                            + Throwables.getStackTraceAsString(e)
                                )
                            )
                        }
                    } finally {
                        eventHandler.handle(
                            Event.info(
                                "Debug server listener thread closed; shutting down debug server and "
                                        + "resuming all threads"
                            )
                        )
                        close()
                    }
                })

        clientThread.setDaemon(true)
        clientThread.start()
    }

    override fun close() {
        try {
            if (verboseLogging) {
                eventHandler.handle(Event.debug("Closing debug server"))
            }
            transport.close()
            callback.onClose()
        } catch (e: IOException) {
            eventHandler.handle(
                Event.error(
                    "Error shutting down the debug server: " + Throwables.getStackTraceAsString(e)
                )
            )
        } finally {
            // ensure no threads are left paused, otherwise the build command will never complete
            threadHandler.resumeAllThreads()
        }
    }

    /**
     * Called by the interpreter before execution of the code at the specified location. Pauses the
     * execution of the current thread if there are conditions that should cause it to be paused, such
     * as a breakpoint being reached.
     * 
     * @param location the location of the statement or expression currently being executed
     */
    override fun before(thread: StarlarkThread?, location: Location?) {
        if (!transport.isClosed()) {
            threadHandler.pauseIfNecessary(thread, location, transport)
        }
    }

    /** Handles a request from the client, and returns the response, where relevant.  */
    private fun handleClientRequest(
        request: StarlarkDebuggingProtos.DebugRequest
    ): DebugEvent? {
        val sequenceNumber: Long = request.getSequenceNumber()
        try {
            when (request.getPayloadCase()) {
                START_DEBUGGING -> {
                    callback.beforeDebuggingStart(threadHandler.getBreakpointFilePaths())
                    threadHandler.resumeAllThreads()
                    return DebugEventHelper.startDebuggingResponse(sequenceNumber)
                }

                LIST_FRAMES -> return listFrames(sequenceNumber, request.getListFrames())
                SET_BREAKPOINTS -> return setBreakpoints(sequenceNumber, request.getSetBreakpoints())
                CONTINUE_EXECUTION -> return continueExecution(sequenceNumber, request.getContinueExecution())
                PAUSE_THREAD -> return pauseThread(sequenceNumber, request.getPauseThread())
                EVALUATE -> return evaluate(sequenceNumber, request.getEvaluate())
                GET_CHILDREN -> return getChildren(sequenceNumber, request.getGetChildren())
                PAYLOAD_NOT_SET -> DebugEventHelper.error(sequenceNumber, "No request payload found")
            }
            return DebugEventHelper.error(
                sequenceNumber, "Unhandled request type: " + request.getPayloadCase()
            )
        } catch (e: DebugRequestException) {
            return DebugEventHelper.error(sequenceNumber, e.message)
        }
    }

    /** Handles a `ListFramesRequest` and returns its response.  */
    @Throws(DebugRequestException::class)
    private fun listFrames(
        sequenceNumber: Long, request: StarlarkDebuggingProtos.ListFramesRequest
    ): DebugEvent? {
        val frames: MutableList<StarlarkDebuggingProtos.Frame?> = threadHandler.listFrames(request.getThreadId())
        return DebugEventHelper.listFramesResponse(sequenceNumber, frames)
    }

    /** Handles a `SetBreakpointsRequest` and returns its response.  */
    private fun setBreakpoints(
        sequenceNumber: Long, request: StarlarkDebuggingProtos.SetBreakpointsRequest
    ): DebugEvent? {
        threadHandler.setBreakpoints(request.getBreakpointList())
        return DebugEventHelper.setBreakpointsResponse(sequenceNumber)
    }

    /** Handles a `EvaluateRequest` and returns its response.  */
    @Throws(DebugRequestException::class)
    private fun evaluate(
        sequenceNumber: Long, request: StarlarkDebuggingProtos.EvaluateRequest
    ): DebugEvent? {
        return DebugEventHelper.evaluateResponse(
            sequenceNumber, threadHandler.evaluate(request.getThreadId(), request.getStatement())
        )
    }

    /** Handles a `GetChildrenRequest` and returns its response.  */
    @Throws(DebugRequestException::class)
    private fun getChildren(
        sequenceNumber: Long, request: StarlarkDebuggingProtos.GetChildrenRequest
    ): DebugEvent? {
        return DebugEventHelper.getChildrenResponse(
            sequenceNumber,
            threadHandler.getChildrenForValue(request.getThreadId(), request.getValueId())
        )
    }

    /** Handles a `ContinueExecutionRequest` and returns its response.  */
    @Throws(DebugRequestException::class)
    private fun continueExecution(
        sequenceNumber: Long, request: StarlarkDebuggingProtos.ContinueExecutionRequest
    ): DebugEvent? {
        val threadId: Long = request.getThreadId()
        if (threadId == 0L) {
            threadHandler.resumeAllThreads()
            return DebugEventHelper.continueExecutionResponse(sequenceNumber)
        }
        threadHandler.resumeThread(threadId, request.getStepping())
        return DebugEventHelper.continueExecutionResponse(sequenceNumber)
    }

    @Throws(DebugRequestException::class)
    private fun pauseThread(
        sequenceNumber: Long, request: StarlarkDebuggingProtos.PauseThreadRequest
    ): DebugEvent? {
        val threadId: Long = request.getThreadId()
        if (threadId == 0L) {
            threadHandler.pauseAllThreads()
        } else {
            threadHandler.pauseThread(threadId)
        }
        return DebugEventHelper.pauseThreadResponse(sequenceNumber)
    }

    /**
     * Callback for `StarlarkDebuggerModule` to reset analysis before debugging starts
     * 
     * 
     * We report the breakpoints set before debugging starts so that the corresponding Skyframe
     * nodes are deleted and re-analysis of those files is triggered.
     * 
     * 
     * The [.maybeBlockBeforeStart] method is needed because (for an incremental build) it's
     * also necessary that we block the build till breakpoints are set, so that the nodes are marked
     * dirty before any skyframe evaluation occurs.
     */
    interface DebugCallback {
        fun beforeDebuggingStart(breakPointPaths: ImmutableSet<String?>?) {}

        @Throws(InterruptedException::class)
        fun maybeBlockBeforeStart() {
        }

        fun onClose() {}

        companion object {
            @kotlin.jvm.JvmStatic
            fun noop(): DebugCallback {
                return object : DebugCallback {}
            }
        }
    }

    companion object {
        /**
         * Initializes debugging support, setting up any debugging-specific overrides, then opens the
         * debug server socket and blocks waiting for an incoming connection.
         * 
         * @param port the port on which the server should listen for connections
         * @param verboseLogging if true, debug-level events will be logged
         * @throws IOException if an I/O error occurs while opening the socket or waiting for a connection
         */
        @Throws(IOException::class)
        fun createAndWaitForConnection(
            eventHandler: EventHandler, port: Int, verboseLogging: Boolean, callback: DebugCallback
        ): StarlarkDebugServer {
            val serverSocket: ServerSocket = ServerSocket(port,  /* backlog */1)
            return Companion.createAndWaitForConnection(eventHandler, serverSocket, verboseLogging, callback)
        }

        /**
         * Initializes debugging support, setting up any debugging-specific overrides, then opens the
         * debug server socket and blocks waiting for an incoming connection.
         * 
         * @param verboseLogging if true, debug-level events will be logged
         * @throws IOException if an I/O error occurs while waiting for a connection
         */
        @VisibleForTesting
        @Throws(IOException::class)
        fun createAndWaitForConnection(
            eventHandler: EventHandler,
            serverSocket: ServerSocket?,
            verboseLogging: Boolean,
            callback: DebugCallback
        ): StarlarkDebugServer {
            val transport: DebugServerTransport =
                DebugServerTransport.Companion.createAndWaitForClient(eventHandler, serverSocket, verboseLogging)
            return StarlarkDebugServer(eventHandler, transport, verboseLogging, callback)
        }
    }
}
