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

import com.google.devtools.build.lib.events.util.EventCollectionApparatus

/** Unit tests for [DebugServerTransport].  */
@RunWith(JUnit4::class)
class DebugServerTransportTest {
    private val events: EventCollectionApparatus =
        EventCollectionApparatus(com.google.devtools.build.lib.events.EventKind.ALL_EVENTS)

    /** A simple debug client for testing purposes.  */
    private class MockDebugClient {
        var clientSocket: java.net.Socket? = null

        fun connect(timeout: java.time.Duration, serverSocket: ServerSocket) {
            val startTimeMillis: Long = java.lang.System.currentTimeMillis()
            var exception: IOException? = null
            while (java.lang.System.currentTimeMillis() - startTimeMillis < timeout.toMillis()) {
                try {
                    clientSocket = java.net.Socket()
                    clientSocket.connect(
                        InetSocketAddress(serverSocket.getInetAddress(), serverSocket.getLocalPort()),
                        100
                    )
                    return
                } catch (e: IOException) {
                    exception = e
                }
            }
            throw java.lang.RuntimeException("Couldn't connect to the debug server", exception)
        }

        @Throws(java.lang.Exception::class)
        fun readEvents(): MutableList<DebugEvent?> {
            val events: MutableList<DebugEvent?> = java.util.ArrayList<DebugEvent?>()
            while (clientSocket.getInputStream().available() != 0) {
                events.add(DebugEvent.parseDelimitedFrom(clientSocket.getInputStream()))
            }
            return events
        }

        @Throws(IOException::class)
        fun sendRequest(request: DebugRequest) {
            request.writeDelimitedTo(clientSocket.getOutputStream())
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConnectAndReceiveRequest() {
        val serverSocket: ServerSocket = serverSocket
        val future: java.util.concurrent.Future<DebugServerTransport?> =
            executor.submit(
                java.lang.Runnable {
                    DebugServerTransport.createAndWaitForClient(
                        events.reporter(), serverSocket, false
                    )
                })
        val client: MockDebugClient =
            com.google.devtools.build.lib.starlarkdebug.server.DebugServerTransportTest.MockDebugClient()
        client.connect(java.time.Duration.ofSeconds(10), serverSocket)

        val serverTransport: DebugServerTransport? = future.get(10, TimeUnit.SECONDS)
        Truth.assertThat(serverTransport).isNotNull()
        val request: DebugRequest =
            DebugRequest.newBuilder()
                .setSequenceNumber(10)
                .setStartDebugging(StartDebuggingRequest.getDefaultInstance())
                .build()
        client.sendRequest(request)

        assertThat(serverTransport.readClientRequest()).isEqualTo(request)
        serverTransport.close()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConnectAndPostEvent() {
        val serverSocket: ServerSocket = serverSocket
        val future: java.util.concurrent.Future<DebugServerTransport?> =
            executor.submit(
                java.lang.Runnable {
                    DebugServerTransport.createAndWaitForClient(
                        events.reporter(), serverSocket, false
                    )
                })
        val client: MockDebugClient =
            com.google.devtools.build.lib.starlarkdebug.server.DebugServerTransportTest.MockDebugClient()
        client.connect(java.time.Duration.ofSeconds(10), serverSocket)

        val serverTransport: DebugServerTransport? = future.get(10, TimeUnit.SECONDS)
        Truth.assertThat(serverTransport).isNotNull()
        val event: DebugEvent =
            DebugEvent.newBuilder()
                .setSequenceNumber(10)
                .setContinueExecution(ContinueExecutionResponse.getDefaultInstance())
                .build()
        serverTransport.postEvent(event)

        Truth.assertThat(client.readEvents()).containsExactly(event)
        serverTransport.close()
    }

    companion object {
        private val executor: ExecutorService = Executors.newFixedThreadPool(1)

        @get:Throws(IOException::class)
        private val serverSocket: ServerSocket
            get() {
                // For reasons only Apple knows, you cannot bind to IPv4-localhost when you run in a sandbox
                // that only allows loopback traffic, but binding to IPv6-localhost works fine. This would
                // however break on systems that don't support IPv6. So what we'll do is to try to bind to IPv6
                // and if that fails, try again with IPv4.
                try {
                    return ServerSocket(0, 1, InetAddress.getByName("[::1]"))
                } catch (e: SocketException) {
                    return ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                }
            }
    }
}
