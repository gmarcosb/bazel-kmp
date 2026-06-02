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
package net.starlark.java.eval

import java.util.concurrent.atomic.AtomicReference

/** Debugger API.  */ // TODO(adonovan): move Debugger to Debug.Debugger.
object Debug {
    val debugger: AtomicReference<Debugger?> = AtomicReference<Debugger?>()

    /**
     * Installs a global hook that causes subsequently executed Starlark threads to notify the
     * debugger of important events. Closes any previously set debugger. Call `setDebugger(null)` to disable debugging.
     */
    @kotlin.jvm.JvmStatic
    fun setDebugger(dbg: Debugger?) {
        val prev: Debugger? = net.starlark.java.eval.Debug.debugger.getAndSet(dbg)
        if (prev != null) {
            prev.close()
        }
    }

    /**
     * Returns a copy of the current stack of call frames, outermost call first.
     * 
     * 
     * This function is intended for use only when execution of `thread` is stopped, for
     * example at a breakpoint. The resulting DebugFrames should not be retained after execution of
     * the thread has resumed. Most clients should instead use [StarlarkThread.getCallStack].
     */
    fun getCallStack(thread: net.starlark.java.eval.StarlarkThread): com.google.common.collect.ImmutableList<Frame?> {
        return thread.getDebugCallStack()
    }

    /**
     * Given a requested stepping behavior, returns a predicate over the context that tells the
     * debugger when to pause. (Debugger API)
     * 
     * 
     * The predicate will return true if we are at the next statement where execution should pause,
     * and it will return false if we are not yet at that statement. No guarantee is made about the
     * predicate's return value after we have reached the desired statement.
     * 
     * 
     * A null return value indicates that no further pausing should occur.
     */
    fun stepControl(th: net.starlark.java.eval.StarlarkThread, stepping: Stepping): ReadyToPause? {
        val depth: Int = th.getCallStackSize()
        when (stepping) {
            net.starlark.java.eval.Debug.Stepping.NONE -> return null
            net.starlark.java.eval.Debug.Stepping.INTO ->         // pause at the very next statement
                return net.starlark.java.eval.Debug.ReadyToPause { thread: net.starlark.java.eval.StarlarkThread? -> true }

            net.starlark.java.eval.Debug.Stepping.OVER -> return net.starlark.java.eval.Debug.ReadyToPause { thread: net.starlark.java.eval.StarlarkThread? -> thread.getCallStackSize() <= depth }
            net.starlark.java.eval.Debug.Stepping.OUT ->         // if we're at the outermost frame, same as NONE
                return if (depth == 0) null else net.starlark.java.eval.Debug.ReadyToPause { thread: net.starlark.java.eval.StarlarkThread? -> thread.getCallStackSize() < depth }
        }
        throw java.lang.IllegalArgumentException("Unsupported stepping type: " + stepping)
    }

    var threadHook: ThreadHook? = null

    /**
     * Installs a global hook that is notified each time a thread pushes or pops its top-level frame.
     * This interface is provided to support special tools; ordinary clients should have no need for
     * it.
     */
    @kotlin.jvm.JvmStatic
    fun setThreadHook(hook: ThreadHook?) {
        net.starlark.java.eval.Debug.threadHook = hook
    }

    /**
     * A simple interface for the Starlark interpreter to notify a debugger of events during
     * execution.
     */
    interface Debugger {
        /** Notify the debugger that execution is at the point immediately before `loc`.  */
        fun before(thread: net.starlark.java.eval.StarlarkThread?, loc: net.starlark.java.syntax.Location?)

        /** Notify the debugger that it will no longer receive events from the interpreter.  */
        fun close()
    }

    /** A Starlark value that can expose additional information to a debugger.  */
    interface ValueWithDebugAttributes : net.starlark.java.eval.StarlarkValue {
        /**
         * Returns a list of DebugAttribute of this value. For example, it can be the internal fields of
         * a value that are not accessible from Starlark, or the values inside a collection.
         */
        fun getDebugAttributes(): com.google.common.collect.ImmutableList<DebugAttribute?>?
    }

    /** A name/value pair used in the return value of getDebugAttributes.  */
    class DebugAttribute(
        val name: String?, // a legal Starlark value
        val value: Any?
    ) {
        init {
            this.value = value
        }
    }

    /** See stepControl  */
    interface ReadyToPause : java.util.function.Predicate<net.starlark.java.eval.StarlarkThread?>

    /**
     * Describes the stepping behavior that should occur when execution of a thread is continued.
     * (Debugger API)
     */
    enum class Stepping {
        /** Continue execution without stepping.  */
        NONE,

        /**
         * If the thread is paused on a statement that contains a function call, step into that
         * function. Otherwise, this is the same as OVER.
         */
        INTO,

        /**
         * Step over the current statement and any functions that it may call, stopping at the next
         * statement in the same frame. If no more statements are available in the current frame, same
         * as OUT.
         */
        OVER,

        /**
         * Continue execution until the current frame has been exited and then pause. If we are
         * currently in the outer-most frame, same as NONE.
         */
        OUT,
    }

    /** Debugger interface to the interpreter's internal call frame representation.  */
    interface Frame {
        /** Returns function called in this frame.  */
        fun getFunction(): net.starlark.java.eval.StarlarkCallable?

        /** Returns the location of the current program counter.  */
        fun getLocation(): net.starlark.java.syntax.Location?

        /** Returns the local environment of this frame.  */
        fun getLocals(): com.google.common.collect.ImmutableMap<String?, Any?>?
    }

    /**
     * Interface by which debugging tools are notified of a thread entering or leaving its top-level
     * frame.
     */
    interface ThreadHook {
        fun onPushFirst(thread: net.starlark.java.eval.StarlarkThread?)

        fun onPopLast(thread: net.starlark.java.eval.StarlarkThread?)
    }
}
