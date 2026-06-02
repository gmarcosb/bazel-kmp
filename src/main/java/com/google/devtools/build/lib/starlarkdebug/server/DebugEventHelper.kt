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
import com.google.devtools.build.lib.starlarkdebugging.StarlarkDebuggingProtos
import net.starlark.java.eval.Debug
import net.starlark.java.eval.StarlarkFunction
import net.starlark.java.syntax.Location
import java.util.function.Consumer

/**
 * Helper class for constructing event or response protos to be sent from the debug server to a
 * debugger client.
 */
internal object DebugEventHelper {
    private const val NO_SEQUENCE_NUMBER: Long = 0

    fun error(message: String?): DebugEvent {
        return error(NO_SEQUENCE_NUMBER, message)
    }

    fun error(sequenceNumber: Long, message: String?): DebugEvent {
        return DebugEvent.newBuilder()
            .setSequenceNumber(sequenceNumber)
            .setError(Error.newBuilder().setMessage(message))
            .build()
    }

    fun setBreakpointsResponse(sequenceNumber: Long): DebugEvent {
        return DebugEvent.newBuilder()
            .setSequenceNumber(sequenceNumber)
            .setSetBreakpoints(SetBreakpointsResponse.getDefaultInstance())
            .build()
    }

    fun continueExecutionResponse(sequenceNumber: Long): DebugEvent {
        return DebugEvent.newBuilder()
            .setSequenceNumber(sequenceNumber)
            .setContinueExecution(ContinueExecutionResponse.getDefaultInstance())
            .build()
    }

    fun evaluateResponse(sequenceNumber: Long, value: Value?): DebugEvent {
        return DebugEvent.newBuilder()
            .setSequenceNumber(sequenceNumber)
            .setEvaluate(EvaluateResponse.newBuilder().setResult(value))
            .build()
    }

    fun listFramesResponse(sequenceNumber: Long, frames: MutableCollection<Frame?>?): DebugEvent {
        return DebugEvent.newBuilder()
            .setSequenceNumber(sequenceNumber)
            .setListFrames(ListFramesResponse.newBuilder().addAllFrame(frames))
            .build()
    }

    fun startDebuggingResponse(sequenceNumber: Long): DebugEvent {
        return DebugEvent.newBuilder()
            .setSequenceNumber(sequenceNumber)
            .setStartDebugging(StartDebuggingResponse.getDefaultInstance())
            .build()
    }

    fun pauseThreadResponse(sequenceNumber: Long): DebugEvent {
        return DebugEvent.newBuilder()
            .setSequenceNumber(sequenceNumber)
            .setPauseThread(PauseThreadResponse.getDefaultInstance())
            .build()
    }

    fun getChildrenResponse(sequenceNumber: Long, children: MutableCollection<Value?>?): DebugEvent {
        return DebugEvent.newBuilder()
            .setSequenceNumber(sequenceNumber)
            .setGetChildren(GetChildrenResponse.newBuilder().addAllChildren(children))
            .build()
    }

    fun threadPausedEvent(thread: PausedThread?): DebugEvent {
        return DebugEvent.newBuilder()
            .setThreadPaused(ThreadPausedEvent.newBuilder().setThread(thread))
            .build()
    }

    @kotlin.jvm.JvmStatic
    fun threadContinuedEvent(threadId: Long): DebugEvent {
        return DebugEvent.newBuilder()
            .setThreadContinued(ThreadContinuedEvent.newBuilder().setThreadId(threadId))
            .build()
    }

    @kotlin.jvm.JvmStatic
    fun getLocationProto(location: Location?): StarlarkDebuggingProtos.Location? {
        if (location == null) {
            return null
        }
        return StarlarkDebuggingProtos.Location.newBuilder()
            .setLineNumber(location.line())
            .setColumnNumber(location.column())
            .setPath(location.file())
            .build()
    }

    fun getFrameProto(objectMap: ThreadObjectMap?, frame: Debug.Frame): StarlarkDebuggingProtos.Frame {
        return StarlarkDebuggingProtos.Frame.newBuilder()
            .setFunctionName(frame.getFunction().getName())
            .addAllScope(getScopes(objectMap, frame))
            .setLocation(getLocationProto(frame.getLocation()))
            .build()
    }

    private fun getScopes(objectMap: ThreadObjectMap?, frame: Debug.Frame): ImmutableList<Scope?> {
        val moduleVars: MutableMap<String?, Any?> =
            if (frame.getFunction() is StarlarkFunction)
                (frame.getFunction() as StarlarkFunction).getModule().getGlobals()
            else
                ImmutableMap.of<String?, Any?>()

        val localVars = frame.getLocals()
        if (localVars.isEmpty()) {
            return ImmutableList.of<Scope?>(getScope(objectMap, "global", moduleVars))
        }

        val globalVars: MutableMap<String?, Any?> = LinkedHashMap<String?, Any?>(moduleVars)
        // remove shadowed bindings
        localVars.keys.forEach(Consumer { key: String? -> globalVars.remove(key) })

        return ImmutableList.of<Scope?>(
            getScope(objectMap, "local", localVars), getScope(objectMap, "global", globalVars)
        )
    }

    private fun getScope(
        objectMap: ThreadObjectMap?, name: String?, bindings: MutableMap<String?, Any?>
    ): StarlarkDebuggingProtos.Scope {
        val builder: StarlarkDebuggingProtos.Scope.Builder =
            StarlarkDebuggingProtos.Scope.newBuilder().setName(name)
        bindings.forEach { (s: String?, o: Any?) ->
            builder.addBinding(
                DebuggerSerialization.getValueProto(
                    objectMap,
                    s,
                    o
                )
            )
        }
        return builder.build()
    }

    fun convertSteppingEnum(stepping: StarlarkDebuggingProtos.Stepping): Debug.Stepping {
        when (stepping) {
            INTO -> return Debug.Stepping.INTO
            OUT -> return Debug.Stepping.OUT
            OVER -> return Debug.Stepping.OVER
            NONE -> return Debug.Stepping.NONE
            UNRECOGNIZED -> {}
        }
        throw IllegalArgumentException("Unsupported stepping type")
    }
}
