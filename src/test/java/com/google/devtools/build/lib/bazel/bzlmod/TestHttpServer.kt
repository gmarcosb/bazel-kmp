// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.repository.downloader.HttpStream.Factory.create
import com.google.devtools.build.lib.bazel.repository.downloader.ProgressInputStream.Factory.create
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.rules.ExternalResource
import java.net.InetSocketAddress

/** A fake HTTP server for testing.  */
class TestHttpServer : ExternalResource {
    private var server: HttpServer? = null
    private var authToken: String? = null

    constructor(authToken: String) {
        this.authToken = authToken
    }

    constructor()

    @Throws(Throwable::class)
    override fun before() {
        server = HttpServer.create(InetSocketAddress(0), 0)
    }

    override fun after() {
        server.stop(0)
    }

    fun start() {
        server.start()
    }

    @kotlin.jvm.JvmOverloads
    fun serve(path: String?, bytes: ByteArray, useAuth: Boolean = false) {
        server.createContext(
            path,
            HttpHandler { exchange: HttpExchange? ->
                if (useAuth) {
                    val tokens: MutableList<String?>? = exchange.getRequestHeaders().get("Authorization")
                    if (tokens == null || tokens.isEmpty() || (authToken != tokens.get(0))) {
                        exchange.sendResponseHeaders(401, -1)
                        return@createContext
                    }
                }
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.getResponseBody().use { os ->
                    os.write(bytes)
                }
            })
    }

    fun serve(path: String?, vararg lines: String?) {
        serve(path, JOINER.join(lines).toByteArray(java.nio.charset.StandardCharsets.UTF_8))
    }

    fun unserve(path: String?) {
        server.removeContext(path)
    }

    val url: String?
        get() = java.net.URI.create("http://[::1]:" + server.getAddress().getPort()).toString()

    companion object {
        private val JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on('\n')
    }
}
