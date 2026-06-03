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

import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos.DebugEvent

/** A basic implementation of a Starlark debugging client, for use in integration tests.  */
internal class MockDebugClient {
    private var clientSocket: java.net.Socket? = null

    val unnumberedEvents: MutableList<DebugEvent?> = java.util.ArrayList<DebugEvent?>()
    val responses: MutableMap<Long?, DebugEvent?> = HashMap<Long?, DebugEvent?>()

    private var readTask: java.util.concurrent.Future<*>? = null

    /** Connects to the debug server, and starts listening for events.  */
    fun connect(addr: InetAddress?, port: Int, timeout: java.time.Duration) {
        val startTimeMillis: Long = java.lang.System.currentTimeMillis()
        var exception: IOException? = null
        while (java.lang.System.currentTimeMillis() - startTimeMillis < timeout.toMillis()) {
            try {
                clientSocket = java.net.Socket()
                clientSocket.connect(InetSocketAddress(addr, port), 100)
                readTask =
                    com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient.Companion.readTaskExecutor.submit<Any?>(
                        java.util.concurrent.Callable {
                            while (true) {
                                eventReceived(DebugEvent.parseDelimitedFrom(clientSocket.getInputStream()))
                            }
                        })
                return
            } catch (e: IOException) {
                exception = e
            }
        }
        throw java.lang.RuntimeException("Couldn't connect to the debug server", exception)
    }

    @Throws(IOException::class)
    fun close() {
        if (clientSocket != null) {
            clientSocket.close()
        }
        if (readTask != null) {
            readTask.cancel(true)
        }
    }

    /**
     * Blocks waiting for an unnumbered event (not a direct response to a request). Returns null if no
     * event arrives before the timeout.
     */
    fun waitForEvent(predicate: java.util.function.Predicate<DebugEvent?>?, timeout: java.time.Duration): DebugEvent? {
        waitForEvents(java.util.function.Predicate { list: MutableList<DebugEvent?>? ->
            list.stream().anyMatch(predicate)
        }, timeout)
        return unnumberedEvents.stream().filter(predicate).findFirst().orElse(null)
    }

    /**
     * Blocks waiting for a condition on all unnumbered events to be satisfied. Returns true if the
     * condition was satisfied before the timeout.
     */
    fun waitForEvents(
        predicate: java.util.function.Predicate<MutableList<DebugEvent?>?>,
        timeout: java.time.Duration
    ): Boolean {
        val startTime: Long = java.lang.System.currentTimeMillis()
        synchronized(unnumberedEvents) {
            while (!predicate.test(com.google.common.collect.ImmutableList.copyOf<DebugEvent?>(unnumberedEvents))
                && java.lang.System.currentTimeMillis() - startTime < timeout.toMillis()
            ) {
                try {
                    (unnumberedEvents as java.lang.Object).wait(timeout.toMillis())
                } catch (e: java.lang.InterruptedException) {
                    throw java.lang.AssertionError(e)
                }
            }
        }
        return predicate.test(com.google.common.collect.ImmutableList.copyOf<DebugEvent?>(unnumberedEvents))
    }

    /**
     * Sends a [DebugRequest] to the server, and blocks waiting for a response.
     * 
     * @return the [DebugEvent] response from the server, or null if no response was received.
     */
    @Throws(IOException::class)
    fun sendRequestAndWaitForResponse(request: DebugRequest): DebugEvent? {
        request.writeDelimitedTo(clientSocket.getOutputStream())
        clientSocket.getOutputStream().flush()
        return waitForResponse(request.getSequenceNumber())
    }

    private fun eventReceived(event: DebugEvent) {
        if (event.getSequenceNumber() === 0) {
            synchronized(unnumberedEvents) {
                unnumberedEvents.add(event)
                (unnumberedEvents as java.lang.Object).notifyAll()
            }
            return
        }
        synchronized(responses) {
            val existing: DebugEvent? = responses.put(event.getSequenceNumber(), event)
            if (existing != null) {
                throw java.lang.AssertionError(
                    "There's already an event in the response queue corresponding to sequence number "
                            + event.getSequenceNumber()
                )
            }
            (responses as java.lang.Object).notifyAll()
        }
    }

    /**
     * Wait for a response from the debug server. Returns null if no response was received, or this
     * thread was interrupted.
     */
    private fun waitForResponse(sequence: Long): DebugEvent? {
        var response: DebugEvent? = null
        val startTime: Long = java.lang.System.currentTimeMillis()
        synchronized(responses) {
            while (response == null && shouldWaitForResponse(startTime)) {
                try {
                    (responses as java.lang.Object).wait(1000)
                } catch (e: java.lang.InterruptedException) {
                    throw java.lang.AssertionError(e)
                }
                response = responses.remove(sequence)
            }
        }
        return response
    }

    private fun shouldWaitForResponse(startTime: Long): Boolean {
        return clientSocket.isConnected()
                && !readTask.isDone() && java.lang.System.currentTimeMillis() - startTime < com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient.Companion.RESPONSE_TIMEOUT_MILLIS
    }

    companion object {
        private const val RESPONSE_TIMEOUT_MILLIS = 10000
        private val readTaskExecutor: ExecutorService = Executors.newFixedThreadPool(1)
    }
}
