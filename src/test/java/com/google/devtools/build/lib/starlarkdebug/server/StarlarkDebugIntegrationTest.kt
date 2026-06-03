// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.buildtool.BuildResult

@RunWith(JUnit4::class)
class StarlarkDebugIntegrationTest : BuildIntegrationTestCase() {
    private val executor: ExecutorService = Executors.newFixedThreadPool(1)
    private val eventCollector: MutableCollection<com.google.devtools.build.lib.events.Event?> =
        ConcurrentLinkedQueue<com.google.devtools.build.lib.events.Event?>()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder.addBlazeModule(StarlarkDebuggerModule())

    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        addOptions("--experimental_skylark_debug", "--experimental_skylark_debug_server_port=0")
        eventCollector.clear()
        events.addHandler(EventCollector(com.google.devtools.build.lib.events.EventKind.ALL_EVENTS, eventCollector))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisResetBlocksOnDebuggingStart() {
        addOptions("--experimental_skylark_debug_reset_analysis")
        write("foo/BUILD", "genrule(name = 'foo', outs = ['foo.out'], cmd = 'touch $@')")

        // run async, otherwise this will just block on the result indefinitely
        val resultCf: CompletableFuture<BuildResult?> =
            CompletableFuture.supplyAsync<BuildResult?>(
                java.util.function.Supplier {
                    try {
                        return@supplyAsync buildTarget(java.util.function.Consumer { debugPort: Int? ->
                            Companion.createClient(
                                debugPort!!
                            )
                        }, "//foo")
                    } catch (e: java.lang.Exception) {
                        throw java.lang.RuntimeException(e)
                    }
                },
                Executors.newSingleThreadExecutor()
            )

        val unusedError: java.util.concurrent.TimeoutException? =
            org.junit.Assert.assertThrows<java.util.concurrent.TimeoutException?>(
                java.util.concurrent.TimeoutException::class.java,
                org.junit.function.ThrowingRunnable { resultCf.get(10, TimeUnit.SECONDS) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisResetWithNoBreakpoints() {
        addOptions("--experimental_skylark_debug_reset_analysis")
        write("foo/BUILD", "genrule(name = 'foo', outs = ['foo.out'], cmd = 'touch $@')")

        val result: BuildResult = buildTarget(
            java.util.function.Consumer { debugPort: Int? -> this.createClientAndSetBreakpoints(debugPort!!) },
            "//foo"
        )

        assertThat(result).isNotNull()
        assertThat(result.getSuccessfulTargets()).hasSize(1)
        MoreAsserts.assertDoesNotContainEvent(eventCollector, "did not receive breakpoints")
        assertContainsEvent(eventCollector, "resetting analysis for: []")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisResetWithBreakpoint() {
        addOptions("--experimental_skylark_debug_reset_analysis")
        write("foo/BUILD", "genrule(name = 'foo', outs = ['foo.out'], cmd = 'touch $@')")

        val result: BuildResult =
            buildTarget(java.util.function.Consumer { debugPort: Int? ->
                createClientAndSetBreakpoints(
                    debugPort!!,
                    "foo/BUILD"
                )
            }, "//foo")

        assertContainsEvent(
            eventCollector, java.util.regex.Pattern.compile("resetting analysis for: .*/foo/BUILD")
        )
        assertThat(result).isNotNull()
        assertThat(result.getSuccessfulTargets()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisResetWithBreakpointDeletesSkyframeFileNode() {
        write("foo/BUILD", "genrule(name = 'foo', outs = ['foo.out'], cmd = 'touch $@')")

        // first build to populate skyframe
        var result: BuildResult =
            buildTarget(java.util.function.Consumer { debugPort: Int? ->
                Companion.createClientAndStartDebugging(
                    debugPort!!
                )
            }, "//foo")
        assertThat(result).isNotNull()

        val deletedFiles: MutableSet<String?> = ConcurrentHashMap.newKeySet<String?>()
        injectListenerAtStartOfNextBuild(
            NotifyingHelper.Listener { key, type, order, context ->
                if (key.functionName() == SkyFunctions.FILE
                    && context == Reason.INVALIDATION
                ) {
                    deletedFiles.add((key.argument() as RootedPath).getRootRelativePath().getPathString())
                }
            })
        addOptions("--experimental_skylark_debug_reset_analysis")

        // rebuild with non-existent breakpoint
        result =
            buildTarget(java.util.function.Consumer { debugPort: Int? ->
                createClientAndSetBreakpoints(
                    debugPort!!,
                    "bar/BUILD"
                )
            }, "//foo")
        assertThat(result).isNotNull()
        Truth.assertThat(deletedFiles).isEmpty()

        // rebuild with breakpoint on build file
        result =
            buildTarget(java.util.function.Consumer { debugPort: Int? ->
                createClientAndSetBreakpoints(
                    debugPort!!,
                    "foo/BUILD"
                )
            }, "//foo")
        assertThat(result).isNotNull()
        Truth.assertThat(deletedFiles).contains("foo/BUILD")
    }

    @Throws(java.lang.Exception::class)
    private fun buildTarget(clientSetup: java.util.function.Consumer<Int?>, target: String?): BuildResult {
        DebugServerTransport.onListenPortCallbackForTests =
            java.util.function.Consumer { port: Int? ->
                val unused: java.util.concurrent.Future<*>? =
                    executor.submit(java.lang.Runnable { clientSetup.accept(port) })
            }
        return super.buildTarget(target)
    }

    private fun createClientAndSetBreakpoints(debugPort: Int, vararg paths: String?) {
        val client: com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient = createClient(debugPort)
        setBreakpoints(client, *paths)
        startDebugging(client)
    }

    private fun setBreakpoints(
        client: com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient,
        vararg paths: String?
    ) {
        val breakpoints: com.google.common.collect.ImmutableList<Breakpoint?> =
            java.util.Arrays.stream<String?>(paths)
                .map<Any?> { path: String? -> getWorkspace().getRelative(path).getPathString() }
                .map<Any?> { path: Any? ->
                    Breakpoint.newBuilder()
                        .setLocation(Location.newBuilder().setPath(path).build())
                        .build()
                }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        try {
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(sequenceIds.getAndIncrement())
                    .setSetBreakpoints(
                        SetBreakpointsRequest.newBuilder().addAllBreakpoint(breakpoints).build()
                    )
                    .build()
            )
        } catch (e: IOException) {
            throw java.lang.RuntimeException(e)
        }
    }

    companion object {
        private val sequenceIds: AtomicInteger = AtomicInteger(1)

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun createClient(debugPort: Int): com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient {
            val client: com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient =
                com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient()
            client.connect(InetAddress.getLoopbackAddress(), debugPort, java.time.Duration.ofSeconds(5))
            return client
        }

        private fun startDebugging(client: com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient) {
            try {
                client.sendRequestAndWaitForResponse(
                    DebugRequest.newBuilder()
                        .setSequenceNumber(sequenceIds.getAndIncrement())
                        .setStartDebugging(StartDebuggingRequest.getDefaultInstance())
                        .build()
                )
            } catch (e: IOException) {
                throw java.lang.RuntimeException(e)
            }
        }

        private fun createClientAndStartDebugging(debugPort: Int) {
            val client: com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient = createClient(debugPort)
            startDebugging(client)
        }
    }
}
