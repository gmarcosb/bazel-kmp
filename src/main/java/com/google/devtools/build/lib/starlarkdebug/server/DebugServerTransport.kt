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
import com.google.devtools.build.lib.events.Event
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.function.Consumer

/**
 * Manages the connection to and communication to/from the debugger client. Reading and writing are
 * internally synchronized by [DebugServerTransport].
 */
internal class DebugServerTransport private constructor(
    eventHandler: EventHandler,
    serverSocket: ServerSocket,
    clientSocket: Socket,
    requestStream: InputStream,
    eventStream: OutputStream,
    verboseLogging: Boolean
) {
    private val eventHandler: EventHandler
    private val serverSocket: ServerSocket
    private val clientSocket: Socket
    private val requestStream: InputStream
    private val eventStream: OutputStream
    private val verboseLogging: Boolean

    init {
        this.eventHandler = eventHandler
        this.serverSocket = serverSocket
        this.clientSocket = clientSocket
        this.requestStream = requestStream
        this.eventStream = eventStream
        this.verboseLogging = verboseLogging
    }

    /**
     * Blocks waiting for a properly-formed client request. Returns null if the client connection is
     * closed.
     */
    fun readClientRequest(): DebugRequest? {
        synchronized(requestStream) {
            try {
                val request: DebugRequest? = DebugRequest.parseDelimitedFrom(requestStream)
                if (verboseLogging) {
                    eventHandler.handle(Event.debug("Received debug client request:\n" + request))
                }
                return request
            } catch (e: IOException) {
                handleParsingError(e)
                return null
            }
        }
    }

    private fun handleParsingError(e: IOException) {
        if (this.isClosed) {
            // an IOException is expected when the client disconnects -- no need to log an error
            return
        }
        val message = "Error parsing debug request: " + e.message
        postEvent(DebugEventHelper.error(message))
        eventHandler.handle(Event.error(message))
    }

    /** Posts a debug event.  */
    fun postEvent(event: DebugEvent) {
        if (verboseLogging) {
            eventHandler.handle(Event.debug("Sending debug event:\n" + event))
        }
        synchronized(eventStream) {
            try {
                event.writeDelimitedTo(eventStream)
            } catch (e: IOException) {
                eventHandler.handle(Event.error("Failed to send debug event to client: " + e.message))
            }
        }
    }

    @Throws(IOException::class)
    fun close() {
        clientSocket.close()
        serverSocket.close()
    }

    val isClosed: Boolean
        get() = serverSocket.isClosed() || clientSocket.isClosed()

    companion object {
        @kotlin.jvm.JvmField
        @VisibleForTesting
        var onListenPortCallbackForTests: Consumer<Int?>? = null

        /** Sets up the server transport and blocks while waiting for an incoming connection.  */
        @Throws(IOException::class)
        fun createAndWaitForClient(
            eventHandler: EventHandler, serverSocket: ServerSocket, verboseLogging: Boolean
        ): DebugServerTransport {
            // TODO(bazel-team): reject all connections after the first
            eventHandler.handle(Event.progress("Waiting for debugger..."))
            if (onListenPortCallbackForTests != null) {
                onListenPortCallbackForTests!!.accept(serverSocket.getLocalPort())
            }
            val clientSocket: Socket = serverSocket.accept()
            eventHandler.handle(Event.info("Debugger connection successfully established."))
            return DebugServerTransport(
                eventHandler,
                serverSocket,
                clientSocket,
                clientSocket.getInputStream(),
                clientSocket.getOutputStream(),
                verboseLogging
            )
        }
    }
}
