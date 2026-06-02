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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos
import net.starlark.java.eval.*
import net.starlark.java.syntax.FileOptions
import net.starlark.java.syntax.Location
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.SyntaxError
import java.util.function.Supplier
import javax.annotation.concurrent.GuardedBy

/** Handles all thread-related state and debugging tasks.  */
internal class ThreadHandler {
    /** The state of a thread that is paused.  */
    private class PausedThreadState(
        val id: Long, val name: String?, val thread: StarlarkThread,
        /** The [Location] where execution is currently paused.  */
        val location: Location?
    ) {
        /** Used to block execution of threads  */
        val semaphore: Semaphore

        val objectMap: ThreadObjectMap

        init {
            this.semaphore = Semaphore(0)
            this.objectMap = ThreadObjectMap()
        }
    }

    /**
     * The state of a thread that is stepping, i.e. currently running but expected to stop at a
     * subsequent statement even without a breakpoint. This may include threads that have completed
     * running while stepping, since the ThreadHandler doesn't know when a thread terminates.
     */
    private class SteppingThreadState(
        /** Determines when execution should next be paused.  */
        val readyToPause: Debug.ReadyToPause
    )

    /** Whether threads are globally paused, and if so, why.  */
    private enum class DebuggerState {
        INITIALIZING,  // no StartDebuggingRequest has yet been received; all threads are paused
        ALL_THREADS_PAUSED,  // all threads are paused in response to a PauseThreadRequest with id=0
        RUNNING,  // normal running: threads are not globally paused
    }

    /** The debugger starts with all threads paused, until a StartDebuggingRequest is received.  */
    @kotlin.concurrent.Volatile
    private var debuggerState = DebuggerState.INITIALIZING

    /** A map from identifiers of paused threads to their state info.  */
    @GuardedBy("this")
    private val pausedThreads: MutableMap<Long?, PausedThreadState> = HashMap<Long?, PausedThreadState>()

    /** A map from identifiers of stepping threads to their state.  */
    @GuardedBy("this")
    private val steppingThreads: MutableMap<Long?, SteppingThreadState?> = HashMap<Long?, SteppingThreadState?>()

    /** All location-based breakpoints (the only type of breakpoint currently supported).  */
    @kotlin.concurrent.Volatile
    private var breakpoints: ImmutableMap<StarlarkDebuggingProtos.Location?, Breakpoint?> =
        ImmutableMap.of<StarlarkDebuggingProtos.Location?, Breakpoint?>()

    /**
     * True if the thread is currently performing a debugger-requested evaluation. If so, we don't
     * check for breakpoints during the evaluation.
     */
    private val servicingEvalRequest: ThreadLocal<Boolean?> = ThreadLocal.withInitial<Boolean?>(Supplier { false })

    /**
     * Threads which are not paused now, but that are set to be paused in the next checked execution
     * step as the result of a PauseThreadRequest.
     * 
     * 
     * Invariant: Every thread id in this set is also in [.steppingThreads], provided that we
     * are not in a synchronized block on the class instance.
     */
    private val threadsToPause: MutableSet<Long?> = ConcurrentHashMap.newKeySet<Long?>()

    /** Mark all current and future threads paused. Will take effect asynchronously.  */
    fun pauseAllThreads() {
        debuggerState = DebuggerState.ALL_THREADS_PAUSED
    }

    /** Mark the given thread paused. Will take effect asynchronously.  */
    @Throws(DebugRequestException::class)
    fun pauseThread(threadId: Long) {
        synchronized(this) {
            if (!steppingThreads.containsKey(threadId)) {
                val error =
                    if (pausedThreads.containsKey(threadId))
                        "Thread is already paused"
                    else
                        "Unknown thread: only threads which are currently stepping can be paused"
                throw DebugRequestException(error)
            }
            threadsToPause.add(threadId)
        }
    }

    fun setBreakpoints(breakpoints: MutableCollection<Breakpoint>) {
        val map: MutableMap<StarlarkDebuggingProtos.Location?, Breakpoint?> =
            HashMap<StarlarkDebuggingProtos.Location?, Breakpoint?>()
        for (breakpoint in breakpoints) {
            if (breakpoint.getConditionCase()
                !== StarlarkDebuggingProtos.Breakpoint.ConditionCase.LOCATION
            ) {
                continue
            }
            // all breakpoints cover the entire line, so unset the column number
            val location: StarlarkDebuggingProtos.Location? =
                breakpoint.getLocation().toBuilder().clearColumnNumber().build()
            map.put(location, breakpoint)
        }
        this.breakpoints = ImmutableMap.copyOf<StarlarkDebuggingProtos.Location?, Breakpoint?>(map)
    }

    val breakpointFilePaths: ImmutableSet<String?>
        get() = breakpoints.keys.stream()
            .map<Any?>(StarlarkDebuggingProtos.Location::getPath)
            .collect(ImmutableSet.toImmutableSet<Any?>())

    /**
     * Resumes all threads. Any currently stepping threads have their stepping behavior cleared, so
     * will run unconditionally.
     */
    fun resumeAllThreads() {
        threadsToPause.clear()
        debuggerState = DebuggerState.RUNNING
        synchronized(this) {
            for (thread in ImmutableList.copyOf<PausedThreadState?>(pausedThreads.values)) {
                // continue-all doesn't support stepping.
                resumePausedThread(thread, StarlarkDebuggingProtos.Stepping.NONE)
            }
            steppingThreads.clear()
        }
    }

    /**
     * Unpauses the given thread if it is currently paused. Also sets [.debuggerState] to
     * RUNNING. If the thread is not paused, but currently stepping, it clears the stepping behavior
     * so it will run unconditionally.
     */
    @Throws(DebugRequestException::class)
    fun resumeThread(threadId: Long, stepping: StarlarkDebuggingProtos.Stepping) {
        // once the user has requested any thread be resumed, don't continue pausing future threads
        debuggerState = DebuggerState.RUNNING
        synchronized(this) {
            threadsToPause.remove(threadId)
            if (steppingThreads.remove(threadId) != null) {
                return
            }
            val thread: PausedThreadState = pausedThreads.get(threadId)!!
            if (thread == null) {
                throw DebugRequestException(
                    String.format("Unknown thread %s: cannot resume.", threadId)
                )
            }
            resumePausedThread(thread, stepping)
        }
    }

    /** Unpauses a currently-paused thread.  */
    @GuardedBy("this")
    private fun resumePausedThread(
        thread: PausedThreadState, stepping: StarlarkDebuggingProtos.Stepping
    ) {
        pausedThreads.remove(thread.id)
        val readyToPause =
            Debug.stepControl(thread.thread, DebugEventHelper.convertSteppingEnum(stepping))
        if (readyToPause != null) {
            steppingThreads.put(thread.id, SteppingThreadState(readyToPause))
        }
        thread.semaphore.release()
    }

    fun pauseIfNecessary(thread: StarlarkThread, location: Location?, transport: DebugServerTransport) {
        if (servicingEvalRequest.get()) {
            return
        }
        var pauseReason: PauseReason?
        var error: Error? = null
        try {
            pauseReason = shouldPauseCurrentThread(thread, location)
        } catch (e: ConditionalBreakpointException) {
            pauseReason = PauseReason.CONDITIONAL_BREAKPOINT_ERROR
            error = Error.newBuilder().setMessage(e.message).build()
        }
        if (pauseReason == null) {
            return
        }
        val threadId = Thread.currentThread().getId()
        threadsToPause.remove(threadId)
        synchronized(this) {
            steppingThreads.remove(threadId)
        }
        pauseCurrentThread(thread, location, transport, pauseReason, error)
    }

    /** Handles a `ListFramesRequest` and returns its response.  */
    @Throws(DebugRequestException::class)
    fun listFrames(threadId: Long): ImmutableList<StarlarkDebuggingProtos.Frame?> {
        synchronized(this) {
            val thread: PausedThreadState = pausedThreads.get(threadId)!!
            if (thread == null) {
                throw DebugRequestException(
                    String.format("Thread %s is not paused or does not exist.", threadId)
                )
            }
            return Debug.getCallStack(thread.thread).stream()
                .map<Any?> { frame: Debug.Frame? -> DebugEventHelper.getFrameProto(thread.objectMap, frame) }
                .collect(ImmutableList.toImmutableList<Any?>())
                .reverse()
        }
    }

    @Throws(DebugRequestException::class)
    fun getChildrenForValue(threadId: Long, valueId: Long): ImmutableList<Value?>? {
        val objectMap: ThreadObjectMap?
        synchronized(this) {
            val thread: PausedThreadState = pausedThreads.get(threadId)!!
            if (thread == null) {
                throw DebugRequestException(
                    String.format("Thread %s is not paused or does not exist.", threadId)
                )
            }
            objectMap = thread.objectMap
        }
        val value = objectMap!!.getValue(valueId)
        if (value == null) {
            throw DebugRequestException("Couldn't retrieve children; object not found.")
        }
        return DebuggerSerialization.getChildren(objectMap, value)
    }

    @Throws(DebugRequestException::class)
    fun evaluate(threadId: Long, statement: String): StarlarkDebuggingProtos.Value? {
        val thread: StarlarkThread
        val objectMap: ThreadObjectMap?
        synchronized(this) {
            val threadState: PausedThreadState = pausedThreads.get(threadId)!!
            if (threadState == null) {
                throw DebugRequestException(
                    String.format("Thread %s is not paused or does not exist.", threadId)
                )
            }
            thread = threadState.thread
            objectMap = threadState.objectMap
        }
        // no need to evaluate within the synchronize block: for paused threads, the thread and
        // object map are only accessed in response to a client request, and requests are handled
        // serially
        // TODO(bazel-team): support asynchronous replies, and use finer-grained locks
        try {
            val result = doEvaluate(thread, statement)
            return DebuggerSerialization.getValueProto(objectMap, "Evaluation result", result)
        } catch (e: EvalException) {
            throw DebugRequestException(e.getMessageWithStack())
        } catch (e: SyntaxError.Exception) {
            throw DebugRequestException(e.message)
        } catch (e: InterruptedException) {
            throw DebugRequestException(e.message)
        }
    }

    /**
     * Executes the Starlark statements code in the environment defined by the provided [ ]. If the last statement is an expression, doEvaluate returns its value,
     * otherwise it returns null.
     * 
     * 
     * The caller is responsible for ensuring that the associated Starlark thread isn't currently
     * running.
     */
    @Throws(SyntaxError.Exception::class, EvalException::class, InterruptedException::class)
    private fun doEvaluate(thread: StarlarkThread, content: String): Any? {
        try {
            servicingEvalRequest.set(true)

            // TODO(adonovan): opt: don't parse and resolve the expression every time we hit a breakpoint
            // (!).
            val input = ParserInput.fromString(content, "<debug eval>")
            // TODO(adonovan): the module or call frame should be a parameter to doEvaluate.
            val module = Module.ofInnermostEnclosingStarlarkFunction(thread)
            return Starlark.execFile(input, FileOptions.DEFAULT, module, thread)
        } finally {
            servicingEvalRequest.set(false)
        }
    }

    /**
     * Pauses the current thread's execution, blocking until it's resumed via a
     * ContinueExecutionRequest.
     */
    private fun pauseCurrentThread(
        thread: StarlarkThread,
        location: Location?,
        transport: DebugServerTransport,
        pauseReason: PauseReason?,
        conditionalBreakpointError: Error?
    ) {
        val threadId = Thread.currentThread().getId()

        val pausedState =
            PausedThreadState(threadId, Thread.currentThread().getName(), thread, location)
        synchronized(this) {
            pausedThreads.put(threadId, pausedState)
        }
        val threadProto: StarlarkDebuggingProtos.PausedThread =
            getPausedThreadProto(pausedState, pauseReason, conditionalBreakpointError)
        transport.postEvent(DebugEventHelper.threadPausedEvent(threadProto))
        pausedState.semaphore.acquireUninterruptibly()
        transport.postEvent(DebugEventHelper.threadContinuedEvent(threadId))
    }

    @Throws(ConditionalBreakpointException::class)
    private fun shouldPauseCurrentThread(thread: StarlarkThread, location: Location?): PauseReason? {
        val threadId = Thread.currentThread().getId()
        val state = debuggerState
        if (state == DebuggerState.ALL_THREADS_PAUSED) {
            return PauseReason.ALL_THREADS_PAUSED
        }
        if (state == DebuggerState.INITIALIZING) {
            return PauseReason.INITIALIZING
        }
        if (threadsToPause.contains(threadId)) {
            return PauseReason.PAUSE_THREAD_REQUEST
        }
        if (hasBreakpointMatchedAtLocation(thread, location)) {
            return PauseReason.HIT_BREAKPOINT
        }

        // TODO(bazel-team): if contention becomes a problem, consider changing 'threads' to a
        // concurrent map, and synchronizing on individual entries
        synchronized(this) {
            val steppingState = steppingThreads.get(threadId)
            if (steppingState != null && steppingState.readyToPause.test(thread)) {
                return PauseReason.STEPPING
            }
        }
        return null
    }

    /**
     * Returns true if there's a breakpoint at the current location, with a satisfied condition if
     * relevant.
     */
    @Throws(ConditionalBreakpointException::class)
    private fun hasBreakpointMatchedAtLocation(thread: StarlarkThread, location: Location?): Boolean {
        // breakpoints is volatile, so taking a local copy
        val breakpoints: ImmutableMap<StarlarkDebuggingProtos.Location?, Breakpoint?> =
            this.breakpoints
        if (breakpoints.isEmpty()) {
            return false
        }
        var locationProto: StarlarkDebuggingProtos.Location? = DebugEventHelper.getLocationProto(location)
        if (locationProto == null) {
            return false
        }
        locationProto = locationProto.toBuilder().clearColumnNumber().build()
        val breakpoint: Breakpoint? = breakpoints.get(locationProto)
        if (breakpoint == null) {
            return false
        }
        val condition: String = breakpoint.getExpression()
        if (condition.isEmpty()) {
            return true
        }
        try {
            return Starlark.truth(doEvaluate(thread, condition))
        } catch (e: EvalException) {
            throw ConditionalBreakpointException(e.getMessageWithStack())
        } catch (e: SyntaxError.Exception) {
            throw ConditionalBreakpointException(e.message)
        } catch (e: InterruptedException) {
            throw ConditionalBreakpointException(e.message)
        }
    }

    companion object {
        /** Returns a `Thread` proto builder with information about the given thread.  */
        private fun getPausedThreadProto(
            thread: PausedThreadState,
            pauseReason: PauseReason?,
            conditionalBreakpointError: Error?
        ): StarlarkDebuggingProtos.PausedThread {
            val builder: StarlarkDebuggingProtos.PausedThread.Builder =
                StarlarkDebuggingProtos.PausedThread.newBuilder()
                    .setId(thread.id)
                    .setName(thread.name)
                    .setPauseReason(pauseReason)
                    .setLocation(DebugEventHelper.getLocationProto(thread.location))
            if (conditionalBreakpointError != null) {
                builder.setConditionalBreakpointError(conditionalBreakpointError)
            }
            return builder.build()
        }
    }
}
