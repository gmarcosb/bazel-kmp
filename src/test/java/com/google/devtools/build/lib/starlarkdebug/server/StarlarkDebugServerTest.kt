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
import com.google.devtools.build.lib.starlarkdebug.server.StarlarkDebugServerTest.Companion.clearIds

/** Integration tests for [StarlarkDebugServer].  */
@RunWith(JUnit4::class)
class StarlarkDebugServerTest {
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)
    private val events: EventCollectionApparatus =
        EventCollectionApparatus(com.google.devtools.build.lib.events.EventKind.ALL_EVENTS)
    private val dummyObjectMap: ThreadObjectMap = ThreadObjectMap()

    private var client: com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient? = null
    private var server: StarlarkDebugServer? = null

    /**
     * Returns the [Value] proto message corresponding to the given object and label. Subsequent
     * calls may return values with different IDs.
     */
    private fun getValueProto(label: String?, value: Any): Value {
        return DebuggerSerialization.getValueProto(dummyObjectMap, label, value)
    }

    private fun getChildren(value: Value): com.google.common.collect.ImmutableList<Value?> {
        val `object`: Any? = dummyObjectMap.getValue(value.getId())
        return if (`object` != null)
            DebuggerSerialization.getChildren(dummyObjectMap, `object`)
        else
            com.google.common.collect.ImmutableList.of<Value?>()
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUpServerAndClient() {
        val serverSocket: ServerSocket = serverSocket
        val future: java.util.concurrent.Future<StarlarkDebugServer?> =
            executor.submit(
                java.lang.Runnable {
                    StarlarkDebugServer.createAndWaitForConnection(
                        events.reporter(), serverSocket, false, DebugCallback.noop()
                    )
                })
        client = com.google.devtools.build.lib.starlarkdebug.server.MockDebugClient()
        client.connect(
            serverSocket.getInetAddress(), serverSocket.getLocalPort(), java.time.Duration.ofSeconds(10)
        )

        server = future.get(10, TimeUnit.SECONDS)
        Truth.assertThat(server).isNotNull()
        Debug.setDebugger(server)
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun shutDown() {
        if (client != null) {
            client.close()
        }
        if (server != null) {
            server.close()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStartDebuggingResponseReceived() {
        val response: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(1)
                    .setStartDebugging(StartDebuggingRequest.getDefaultInstance())
                    .build()
            )
        assertThat(response)
            .isEqualTo(
                DebugEvent.newBuilder()
                    .setSequenceNumber(1)
                    .setStartDebugging(StartDebuggingResponse.newBuilder().build())
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPausedUntilStartDebuggingRequestReceived() {
        val buildFile: net.starlark.java.syntax.ParserInput = createInput("/a/build/file/BUILD", "x = [1,2,3]")

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadName: String? = evaluationThread.getName()
        val threadId: Long = evaluationThread.getId()

        // wait for BUILD evaluation to start
        var event: DebugEvent? = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val expectedLocation: Location =
            DebugEventHelper.getLocationProto(
                net.starlark.java.syntax.Location.fromFileLineColumn("/a/build/file/BUILD", 1, 1)
            )

        assertThat(event)
            .isEqualTo(
                DebugEventHelper.threadPausedEvent(
                    StarlarkDebuggingProtos.PausedThread.newBuilder()
                        .setId(threadId)
                        .setName(threadName)
                        .setPauseReason(PauseReason.INITIALIZING)
                        .setLocation(expectedLocation)
                        .build()
                )
            )

        sendStartDebuggingRequest()
        event = client.waitForEvent(DebugEvent::hasThreadContinued, java.time.Duration.ofSeconds(5))
        assertThat(event).isEqualTo(DebugEventHelper.threadContinuedEvent(threadId))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResumeAllThreads() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        // evaluate in two separate worker threads
        execInWorkerThread(buildFile, null)
        execInWorkerThread(buildFile, null)

        // wait for both breakpoints to be hit
        val paused: Boolean =
            client.waitForEvents(
                java.util.function.Predicate { list: MutableList<DebugEvent?>? ->
                    list.stream().filter(DebugEvent::hasThreadPaused).count() == 2L
                },
                java.time.Duration.ofSeconds(5)
            )

        Truth.assertThat(paused).isTrue()

        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(45)
                .setContinueExecution(ContinueExecutionRequest.getDefaultInstance())
                .build()
        )

        val resumed: Boolean =
            client.waitForEvents(
                java.util.function.Predicate { list: MutableList<DebugEvent?>? ->
                    list.stream().filter(DebugEvent::hasThreadContinued).count() == 2L
                },
                java.time.Duration.ofSeconds(5)
            )

        Truth.assertThat(resumed).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPauseAtBreakpoint() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadName: String? = evaluationThread.getName()
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        val event: DebugEvent? = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val expectedThreadState: StarlarkDebuggingProtos.PausedThread =
            StarlarkDebuggingProtos.PausedThread.newBuilder()
                .setName(threadName)
                .setId(threadId)
                .setPauseReason(PauseReason.HIT_BREAKPOINT)
                .setLocation(breakpoint.toBuilder().setColumnNumber(1))
                .build()

        assertThat(event).isEqualTo(DebugEventHelper.threadPausedEvent(expectedThreadState))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoNotPauseAtUnsatisfiedConditionalBreakpoint() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            z = 1
            
            """.trimIndent()
            )

        val breakpoints: com.google.common.collect.ImmutableList<Breakpoint> =
            com.google.common.collect.ImmutableList.of<E>(
                Breakpoint.newBuilder()
                    .setLocation(Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD"))
                    .setExpression("x[0] == 2")
                    .build(),
                Breakpoint.newBuilder()
                    .setLocation(Location.newBuilder().setLineNumber(3).setPath("/a/build/file/BUILD"))
                    .setExpression("x[0] == 1")
                    .build()
            )
        setBreakpoints(breakpoints)

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadName: String? = evaluationThread.getName()
        val threadId: Long = evaluationThread.getId()
        val expectedBreakpoint: Breakpoint = breakpoints.get(1)

        val event: DebugEvent? = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))
        assertThat(event)
            .isEqualTo(
                DebugEventHelper.threadPausedEvent(
                    StarlarkDebuggingProtos.PausedThread.newBuilder()
                        .setName(threadName)
                        .setId(threadId)
                        .setLocation(expectedBreakpoint.getLocation().toBuilder().setColumnNumber(1))
                        .setPauseReason(PauseReason.HIT_BREAKPOINT)
                        .build()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPauseAtSatisfiedConditionalBreakpoint() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val location: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        val breakpoint: Breakpoint =
            Breakpoint.newBuilder().setLocation(location).setExpression("x[0] == 1").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadName: String? = evaluationThread.getName()
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        val event: DebugEvent? = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val expectedThreadState: StarlarkDebuggingProtos.PausedThread =
            StarlarkDebuggingProtos.PausedThread.newBuilder()
                .setName(threadName)
                .setId(threadId)
                .setPauseReason(PauseReason.HIT_BREAKPOINT)
                .setLocation(location.toBuilder().setColumnNumber(1))
                .build()

        assertThat(event).isEqualTo(DebugEventHelper.threadPausedEvent(expectedThreadState))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPauseAtInvalidConditionBreakpointWithError() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val location: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        val breakpoint: Breakpoint =
            Breakpoint.newBuilder().setLocation(location).setExpression("z[0] == 1").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadName: String? = evaluationThread.getName()
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        val event: DebugEvent? = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val expectedThreadState: StarlarkDebuggingProtos.PausedThread =
            StarlarkDebuggingProtos.PausedThread.newBuilder()
                .setName(threadName)
                .setId(threadId)
                .setPauseReason(PauseReason.CONDITIONAL_BREAKPOINT_ERROR)
                .setLocation(location.toBuilder().setColumnNumber(1))
                .setConditionalBreakpointError(
                    StarlarkDebuggingProtos.Error.newBuilder().setMessage("name 'z' is not defined")
                )
                .build()

        assertThat(event).isEqualTo(DebugEventHelper.threadPausedEvent(expectedThreadState))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListFramesForInvalidThread() {
        sendStartDebuggingRequest()
        val event: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(1)
                    .setListFrames(ListFramesRequest.newBuilder().setThreadId(20).build())
                    .build()
            )
        assertThat(event.hasError()).isTrue()
        com.google.common.truth.Subject.contains("Thread 20 is not paused")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleListFramesRequest() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val frames: ListFramesResponse = listFrames(threadId)
        assertThat(frames.getFrameCount()).isEqualTo(1)
        assertFramesEqualIgnoringValueIdentifiers(
            frames.getFrame(0),
            Frame.newBuilder()
                .setFunctionName(StarlarkThread.TOP_LEVEL)
                .setLocation(breakpoint.toBuilder().setColumnNumber(1))
                .addScope(
                    Scope.newBuilder()
                        .setName("global")
                        .addBinding(
                            getValueProto(
                                "x",
                                StarlarkList.immutableOf(
                                    StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3)
                                )
                            )
                        )
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListFramesWithUninitializedCellRequest() {
        sendStartDebuggingRequest()
        val bzlFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/foo.bzl",
                """
            def outer():
               x = [1,2,3] # <- breakpoint

               def inner():
                   x.append(4)

               inner()

            outer()
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/foo.bzl").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val module: Module = Module.create()
        val evaluationThread: java.lang.Thread = execInWorkerThread(bzlFile, module)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val frames: ListFramesResponse = listFrames(threadId)
        assertThat(frames.getFrameCount()).isEqualTo(2)
        // critically x is not present as a local
        assertFramesEqualIgnoringValueIdentifiers(
            frames.getFrame(0),
            Frame.newBuilder()
                .setFunctionName("outer")
                .setLocation(breakpoint.toBuilder().setColumnNumber(4))
                .addScope(
                    Scope.newBuilder()
                        .setName("global")
                        .addBinding(getValueProto("outer", module.getGlobal("outer")))
                )
                .build()
        )
        assertFramesEqualIgnoringValueIdentifiers(
            frames.getFrame(1),
            Frame.newBuilder()
                .setFunctionName(StarlarkThread.TOP_LEVEL)
                .setLocation(breakpoint.toBuilder().setLineNumber(9).setColumnNumber(6))
                .addScope(
                    Scope.newBuilder()
                        .setName("global")
                        .addBinding(getValueProto("outer", module.getGlobal("outer")))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListFramesWithInitializedCellRequest() {
        sendStartDebuggingRequest()
        val bzlFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/foo.bzl",
                """
            def outer():
               x = [1]
               pass          # <- breakpoint

               def inner():
                   x.append(4)

               inner()

            outer()
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(3).setPath("/a/build/file/foo.bzl").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val module: Module = Module.create()
        val evaluationThread: java.lang.Thread = execInWorkerThread(bzlFile, module)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val frames: ListFramesResponse = listFrames(threadId)
        assertThat(frames.getFrameCount()).isEqualTo(2)
        // critically x is present as a local
        assertFramesEqualIgnoringValueIdentifiers(
            frames.getFrame(0),
            Frame.newBuilder()
                .setFunctionName("outer")
                .setLocation(breakpoint.toBuilder().setColumnNumber(4))
                .addScope(
                    Scope.newBuilder()
                        .setName("local")
                        .addBinding(getValueProto("x", StarlarkList.immutableOf(StarlarkInt.of(1))))
                )
                .addScope(
                    Scope.newBuilder()
                        .setName("global")
                        .addBinding(getValueProto("outer", module.getGlobal("outer")))
                )
                .build()
        )
        assertFramesEqualIgnoringValueIdentifiers(
            frames.getFrame(1),
            Frame.newBuilder()
                .setFunctionName(StarlarkThread.TOP_LEVEL)
                .setLocation(breakpoint.toBuilder().setLineNumber(10).setColumnNumber(6))
                .addScope(
                    Scope.newBuilder()
                        .setName("global")
                        .addBinding(getValueProto("outer", module.getGlobal("outer")))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetChildrenRequest() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val frames: ListFramesResponse = listFrames(threadId)
        val xValue: Value = frames.getFrame(0).getScope(0).getBinding(0)

        assertValuesEqualIgnoringId(
            xValue,
            getValueProto(
                "x",
                StarlarkList.immutableOf(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))
            )
        )

        val children: MutableList<Value?> = getChildren(xValue)

        Truth.assertThat(children)
            .isEqualTo(
                com.google.common.collect.ImmutableList.of<Any?>(
                    getValueProto("[0]", 1), getValueProto("[1]", 2), getValueProto("[2]", 3)
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testListFramesShadowedBinding() {
        sendStartDebuggingRequest()
        val bzlFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/test.bzl",
                """
            a = 1
            c = 3
            def fn():
              a = 2
              b = 1
              b + 1
            fn()
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setPath("/a/build/file/test.bzl").setLineNumber(6).build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val module: Module = Module.create()
        val evaluationThread: java.lang.Thread = execInWorkerThread(bzlFile, module)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val frames: ListFramesResponse = listFrames(threadId)
        assertThat(frames.getFrameCount()).isEqualTo(2)

        assertFramesEqualIgnoringValueIdentifiers(
            frames.getFrame(0),
            Frame.newBuilder()
                .setFunctionName("fn")
                .setLocation(breakpoint.toBuilder().setColumnNumber(3))
                .addScope(
                    Scope.newBuilder()
                        .setName("local")
                        .addBinding(getValueProto("a", StarlarkInt.of(2)))
                        .addBinding(getValueProto("b", StarlarkInt.of(1)))
                )
                .addScope(
                    Scope.newBuilder()
                        .setName("global")
                        .addBinding(getValueProto("c", StarlarkInt.of(3)))
                        .addBinding(getValueProto("fn", module.getGlobal("fn")))
                )
                .build()
        )

        assertFramesEqualIgnoringValueIdentifiers(
            frames.getFrame(1),
            Frame.newBuilder()
                .setFunctionName(StarlarkThread.TOP_LEVEL)
                .setLocation(
                    Location.newBuilder()
                        .setPath("/a/build/file/test.bzl")
                        .setLineNumber(7)
                        .setColumnNumber(3)
                )
                .addScope(
                    Scope.newBuilder()
                        .setName("global")
                        .addBinding(getValueProto("a", StarlarkInt.of(1)))
                        .addBinding(getValueProto("c", StarlarkInt.of(3)))
                        .addBinding(getValueProto("fn", module.getGlobal("fn")))
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvaluateRequestWithExpression() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val response: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(123)
                    .setEvaluate(
                        EvaluateRequest.newBuilder().setThreadId(threadId).setStatement("x[1]").build()
                    )
                    .build()
            )
        assertThat(response.hasEvaluate()).isTrue()
        assertThat(response.getEvaluate().getResult())
            .isEqualTo(getValueProto("Evaluation result", StarlarkInt.of(2)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvaluateRequestWithAssignmentStatement() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val response: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(123)
                    .setEvaluate(
                        EvaluateRequest.newBuilder()
                            .setThreadId(threadId)
                            .setStatement("x = [5,6]")
                            .build()
                    )
                    .build()
            )
        assertThat(response.getEvaluate().getResult())
            .isEqualTo(getValueProto("Evaluation result", Starlark.NONE))

        val frames: ListFramesResponse = listFrames(threadId)
        com.google.common.truth.Subject.contains(
            getValueProto(
                "x", StarlarkList.of( /*mutability=*/null, StarlarkInt.of(5), StarlarkInt.of(6))
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvaluateRequestWithExpressionStatementMutatingState() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val response: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(123)
                    .setEvaluate(
                        EvaluateRequest.newBuilder()
                            .setThreadId(threadId)
                            .setStatement("x.append(4)")
                            .build()
                    )
                    .build()
            )
        assertThat(response.getEvaluate().getResult())
            .isEqualTo(getValueProto("Evaluation result", Starlark.NONE))

        val frames: ListFramesResponse = listFrames(threadId)
        com.google.common.truth.Subject.contains(
            getValueProto(
                "x",
                StarlarkList.immutableOf(
                    StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3), StarlarkInt.of(4)
                )
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvaluateRequestThrowingException() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/BUILD",
                """
            x = [1,2,3]
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/BUILD").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val response: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(123)
                    .setEvaluate(
                        EvaluateRequest.newBuilder().setThreadId(threadId).setStatement("z[0]").build()
                    )
                    .build()
            )
        assertThat(response.hasError()).isTrue()
        assertThat(response.getError().getMessage()).isEqualTo("name 'z' is not defined")
    }

    // b/143713658
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEvaluateRequest_resolvesGlobalsAndLocals() {
        sendStartDebuggingRequest()
        val buildFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/foo.bzl",
                """
            _global = [1,2,3]

            def _func(my_arg):
              pass

            _func(my_arg = [4,5,6])
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(4).setPath("/a/build/file/foo.bzl").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(buildFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val responseForGlobal: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(123)
                    .setEvaluate(
                        EvaluateRequest.newBuilder()
                            .setThreadId(threadId)
                            .setStatement("_global[1]")
                            .build()
                    )
                    .build()
            )

        assertThat(responseForGlobal.hasEvaluate()).isTrue()
        assertThat(responseForGlobal.getEvaluate().getResult())
            .isEqualTo(getValueProto("Evaluation result", StarlarkInt.of(2)))

        val responseForLocal: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(124)
                    .setEvaluate(
                        EvaluateRequest.newBuilder()
                            .setThreadId(threadId)
                            .setStatement("my_arg[1]")
                            .build()
                    )
                    .build()
            )

        assertThat(responseForLocal.hasError()).isTrue()
        assertThat(responseForLocal.getError().getMessage()).isEqualTo("name 'my_arg' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStepIntoFunction() {
        sendStartDebuggingRequest()
        val bzlFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/test.bzl",
                """
            def fn():
              a = 2
              return a
            x = fn()
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(4).setPath("/a/build/file/test.bzl").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(bzlFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        var event: DebugEvent? = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        assertThat(event.getThreadPaused().getThread().getLocation().getLineNumber()).isEqualTo(4)

        client.unnumberedEvents.clear()
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(2)
                .setContinueExecution(
                    ContinueExecutionRequest.newBuilder()
                        .setThreadId(threadId)
                        .setStepping(Stepping.INTO)
                        .build()
                )
                .build()
        )
        event = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        // check we're paused inside the function
        assertThat(listFrames(threadId).getFrameCount()).isEqualTo(2)

        // and verify the location and pause reason as well
        val expectedLocation: Location? = breakpoint.toBuilder().setLineNumber(2).setColumnNumber(3).build()

        val pausedThread: StarlarkDebuggingProtos.PausedThread = event.getThreadPaused().getThread()
        assertThat(pausedThread.getPauseReason()).isEqualTo(PauseReason.STEPPING)
        assertThat(pausedThread.getLocation()).isEqualTo(expectedLocation)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStepOverFunction() {
        sendStartDebuggingRequest()
        val bzlFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/test.bzl",
                """
            def fn():
              a = 2
              return a
            x = fn()
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(4).setPath("/a/build/file/test.bzl").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(bzlFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        var event: DebugEvent? = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        assertThat(event.getThreadPaused().getThread().getLocation().getLineNumber()).isEqualTo(4)

        client.unnumberedEvents.clear()
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(2)
                .setContinueExecution(
                    ContinueExecutionRequest.newBuilder()
                        .setThreadId(threadId)
                        .setStepping(Stepping.OVER)
                        .build()
                )
                .build()
        )
        event = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val expectedLocation: Location? = breakpoint.toBuilder().setLineNumber(5).setColumnNumber(1).build()
        val pausedThread: PausedThread = event.getThreadPaused().getThread()
        assertThat(pausedThread.getPauseReason()).isEqualTo(PauseReason.STEPPING)
        assertThat(pausedThread.getLocation()).isEqualTo(expectedLocation)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStepOutOfFunction() {
        sendStartDebuggingRequest()
        val bzlFile: net.starlark.java.syntax.ParserInput =
            createInput(
                "/a/build/file/test.bzl",
                """
            def fn():
              a = 2
              return a
            x = fn()
            y = [2,3,4]
            
            """.trimIndent()
            )

        val breakpoint: Location =
            Location.newBuilder().setLineNumber(2).setPath("/a/build/file/test.bzl").build()
        setBreakpoints(com.google.common.collect.ImmutableList.of<E?>(breakpoint))

        val evaluationThread: java.lang.Thread = execInWorkerThread(bzlFile, null)
        val threadId: Long = evaluationThread.getId()

        // wait for breakpoint to be hit
        client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        assertThat(listFrames(threadId).getFrameCount()).isEqualTo(2)

        client.unnumberedEvents.clear()
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(2)
                .setContinueExecution(
                    ContinueExecutionRequest.newBuilder()
                        .setThreadId(threadId)
                        .setStepping(Stepping.OUT)
                        .build()
                )
                .build()
        )
        val event: DebugEvent? = client.waitForEvent(DebugEvent::hasThreadPaused, java.time.Duration.ofSeconds(5))

        val pausedThread: PausedThread = event.getThreadPaused().getThread()
        val expectedLocation: Location? = breakpoint.toBuilder().setLineNumber(5).setColumnNumber(1).build()

        assertThat(pausedThread.getPauseReason()).isEqualTo(PauseReason.STEPPING)
        assertThat(pausedThread.getLocation()).isEqualTo(expectedLocation)
    }

    @Throws(java.lang.Exception::class)
    private fun setBreakpoints(locations: MutableCollection<Location?>) {
        setBreakpoints(
            locations
                .stream()
                .map<Any?> { l: Location? -> Breakpoint.newBuilder().setLocation(l).build() }
                .collect(Collectors.toList()))
    }

    @Throws(java.lang.Exception::class)
    private fun setBreakpoints(breakpoints: Iterable<Breakpoint>?) {
        val response: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(10)
                    .setSetBreakpoints(SetBreakpointsRequest.newBuilder().addAllBreakpoint(breakpoints))
                    .build()
            )
        assertThat(response.hasSetBreakpoints()).isTrue()
        assertThat(response.getSequenceNumber()).isEqualTo(10)
    }

    @Throws(java.lang.Exception::class)
    private fun sendStartDebuggingRequest() {
        client.sendRequestAndWaitForResponse(
            DebugRequest.newBuilder()
                .setSequenceNumber(1)
                .setStartDebugging(StartDebuggingRequest.getDefaultInstance())
                .build()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun listFrames(threadId: Long): ListFramesResponse {
        val event: DebugEvent? =
            client.sendRequestAndWaitForResponse(
                DebugRequest.newBuilder()
                    .setSequenceNumber(1)
                    .setListFrames(ListFramesRequest.newBuilder().setThreadId(threadId).build())
                    .build()
            )
        assertThat(event.hasListFrames()).isTrue()
        assertThat(event.getSequenceNumber()).isEqualTo(1)
        return event.getListFrames()
    }

    companion object {
        @get:Throws(IOException::class)
        private val serverSocket: ServerSocket
            get() = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

        private fun createInput(filename: String?, vararg lines: String?): net.starlark.java.syntax.ParserInput {
            return net.starlark.java.syntax.ParserInput.fromString(
                com.google.common.base.Joiner.on("\n").join(lines),
                filename
            )
        }

        /**
         * Creates and starts a worker thread parsing, resolving, and executing the given Starlark file to
         * populate the specified module, or if none is given, in a fresh module with a default
         * environment.
         */
        private fun execInWorkerThread(
            input: net.starlark.java.syntax.ParserInput?,
            module: Module?
        ): java.lang.Thread {
            val javaThread: java.lang.Thread =
                java.lang.Thread(
                    java.lang.Runnable {
                        try {
                            Mutability.create("test").use { mu ->
                                val thread: StarlarkThread? =
                                    StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
                                Starlark.execFile(
                                    input,
                                    net.starlark.java.syntax.FileOptions.DEFAULT,
                                    if (module != null) module else Module.create(),
                                    thread
                                )
                            }
                        } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
                            throw java.lang.AssertionError(ex)
                        } catch (ex: EvalException) {
                            throw java.lang.AssertionError(ex)
                        } catch (ex: java.lang.InterruptedException) {
                            throw java.lang.AssertionError(ex)
                        }
                    })
            javaThread.start()
            return javaThread
        }

        /**
         * Asserts that the given frames are equal after clearing the identifier from all [Value]s.
         */
        private fun assertFramesEqualIgnoringValueIdentifiers(frame1: Frame, frame2: Frame) {
            assertThat(Companion.clearIds(frame1)).isEqualTo(Companion.clearIds(frame2))
        }

        private fun clearIds(frame: Frame): Frame {
            val builder: Frame.Builder = frame.toBuilder()
            for (i in 0..<frame.getScopeCount()) {
                builder.setScope(i, clearIds(builder.getScope(i)))
            }
            return builder.build()
        }

        private fun clearIds(scope: Scope): Scope {
            val builder: Scope.Builder = scope.toBuilder()
            for (i in 0..<scope.getBindingCount()) {
                builder.getBindingBuilder(i).clearId()
            }
            return builder.build()
        }

        private fun assertValuesEqualIgnoringId(value1: Value, value2: Value) {
            assertThat(clearId(value1)).isEqualTo(clearId(value2))
        }

        private fun clearId(value: Value): Value {
            return value.toBuilder().clearId().build()
        }
    }
}
