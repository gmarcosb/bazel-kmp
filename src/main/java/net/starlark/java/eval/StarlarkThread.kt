// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.HashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * An StarlarkThread represents a Starlark thread.
 * 
 * 
 * It holds the stack of active Starlark and built-in function calls. In addition, it may hold
 * per-thread application state (see [.setThreadLocal]) that passes through Starlark functions
 * but does not directly affect them, such as information about the BUILD file being loaded.
 * 
 * 
 * StarlarkThreads are not thread-safe: they should be confined to a single Java thread.
 * 
 * 
 * Every StarlarkThread has an associated [Mutability], which should be created for that
 * thread, and closed once the thread's work is done. (A try-with-resources statement is handy for
 * this purpose.) Starlark values created by the thread are associated with the thread's Mutability,
 * so that when the Mutability is closed at the end of the computation, all the values created by
 * the thread become frozen. This pattern ensures that all Starlark values are frozen before they
 * are published to another thread, and thus that concurrently executing Starlark threads are free
 * from data races. Once a thread's mutability is frozen, the thread is unlikely to be useful for
 * further computation because it can no longer create mutable values. (This is occasionally
 * valuable in tests.)
 */
class StarlarkThread private constructor(
    mu: net.starlark.java.eval.Mutability?,
    semantics: net.starlark.java.eval.StarlarkSemantics,
    contextDescription: String,
    symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>
) {
    /** The mutability of values created by this thread.  */
    private val mutability: net.starlark.java.eval.Mutability

    // profiler state
    //
    // The profiler field (and savedThread) are set when we first observe during a
    // push (function call entry) that the profiler is active. They are unset
    // not in the corresponding pop, but when the last frame is popped, because
    // the profiler session might start in the middle of a call and/or run beyond
    // the lifetime of this thread.
    val cpuTicks: AtomicInteger = AtomicInteger()
    private var profiler: net.starlark.java.eval.CpuProfiler? = null
    private var savedThread: StarlarkThread? = null // saved StarlarkThread, when profiling reentrant evaluation

    private val threadLocals: MutableMap<java.lang.Class<*>?, Any?> = HashMap<java.lang.Class<*>?, Any?>()

    private val symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>

    private var interruptible = true

    private val builtinManager: net.starlark.java.eval.CallUtils.BuiltinManager?

    @kotlin.jvm.JvmField
    var steps: Long = 0 // count of logical computation steps executed so far
    @kotlin.jvm.JvmField
    var stepLimit: Long = java.lang.Long.MAX_VALUE // limit on logical computation steps

    /**
     * Returns the number of Starlark computation steps executed by this thread according to a
     * small-step semantics. (Today, that means exec, eval, and assign operations executed by the
     * tree-walking evaluator, but in future will mean byte code instructions; the two are not
     * commensurable.)
     */
    fun getExecutedSteps(): Long {
        return steps
    }

    /**
     * Increments the thread's number of executed Starlark computation steps by a specified delta.
     * Intended to be used by callers that perform custom off-thread computation and that want to
     * limit the sum of in-thread and off-thread computation steps to a common [ ][.getMaxExecutionSteps] budget.
     */
    fun incrementExecutedSteps(delta: Long) {
        this.steps += delta
    }

    /**
     * Sets the maximum number of Starlark computation steps that may be executed by this thread (see
     * [.getExecutedSteps]). When the step counter reaches or exceeds this value, execution
     * fails with an EvalException.
     */
    fun setMaxExecutionSteps(steps: Long) {
        this.stepLimit = steps
    }

    /**
     * Returns the maximum number of Starlark computation steps that may be executed by this thread.
     */
    fun getMaxExecutionSteps(): Long {
        return stepLimit
    }

    /**
     * Disables polling of the [java.lang.Thread.interrupted] flag during Starlark evaluation.
     */
    // TODO(adonovan): expose a public API for this if we can establish a stronger semantics. (There
    // are other ways besides polling for evaluation to be interrupted, such as calling certain
    // built-in functions.)
    fun ignoreThreadInterrupts() {
        interruptible = false
    }

    @Throws(java.lang.InterruptedException::class)
    fun checkInterrupt() {
        if (interruptible && java.lang.Thread.interrupted()) {
            throw java.lang.InterruptedException()
        }
    }

    /**
     * setThreadLocal saves `value` as a thread-local variable of this Starlark thread, keyed by
     * `key`, so that it can later be retrieved by `getThreadLocal(key)`.
     */
    fun <T> setThreadLocal(key: java.lang.Class<T?>?, value: T?) {
        threadLocals.put(key, value)
    }

    /**
     * getThreadLocal returns the value `v` supplied to the most recent `setThreadLocal(key, v)` call, or null if there was no prior call.
     */
    fun <T> getThreadLocal(key: java.lang.Class<T?>): T? {
        val v = threadLocals.get(key)
        return if (v == null) null else key.cast(v)
    }

    /** A Frame records information about an active function call.  */
    internal class Frame private constructor(thread: StarlarkThread?, fn: net.starlark.java.eval.StarlarkCallable) :
        net.starlark.java.eval.Debug.Frame {
        val thread: StarlarkThread?
        val fn: net.starlark.java.eval.StarlarkCallable // the called function

        val dbg: net.starlark.java.eval.Debug.Debugger? =
            net.starlark.java.eval.Debug.debugger.get() // the debugger, if active for this frame

        var result: Any? = net.starlark.java.eval.Starlark.Companion.NONE // the operand of a Starlark return statement

        // Current PC location. Initially fn.getLocation(); for Starlark functions,
        // it is updated at key points when it may be observed: calls, breakpoints, errors.
        private var loc: net.starlark.java.syntax.Location? = null

        // Indicates that setErrorLocation has been called already and the error
        // location (loc) should not be overwritten.
        private var errorLocationSet = false

        // The locals of this frame, if fn is a StarlarkFunction, otherwise null.
        // Set by StarlarkFunction.fastcall. Elements may be regular Starlark
        // values, or wrapped in StarlarkFunction.Cells if shared with a nested function.
        var locals: Array<Any?>?

        private var profileStartTimeNanos: Long = 0 // start time nanos of walltime call profiler

        init {
            this.thread = thread
            this.fn = fn
        }

        // Updates the PC location in this frame.
        fun setLocation(loc: net.starlark.java.syntax.Location) {
            this.loc = loc
        }

        // Sets location only the first time it is called,
        // to ensure that the location of the innermost expression
        // is used for errors.
        // (Once we switch to a bytecode interpreter, we can afford
        // to update fr.pc before each fallible operation, but until then
        // we must materialize Locations only after the fact of failure.)
        // Sets errorLocationSet.
        fun setErrorLocation(loc: net.starlark.java.syntax.Location) {
            if (!errorLocationSet) {
                errorLocationSet = true
                this.loc = loc
            }
        }

        override fun getFunction(): net.starlark.java.eval.StarlarkCallable {
            return fn
        }

        override fun getLocation(): net.starlark.java.syntax.Location {
            return loc
        }

        override fun getLocals(): com.google.common.collect.ImmutableMap<String?, Any?> {
            // TODO(adonovan): provide a more efficient API.
            val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            if (fn is net.starlark.java.eval.StarlarkFunction) {
                for (i in locals.indices) {
                    var local = locals!![i]
                    if (local is net.starlark.java.eval.StarlarkFunction.Cell) {
                        local = (local as net.starlark.java.eval.StarlarkFunction.Cell).x
                    }
                    if (local != null) {
                        val binding: net.starlark.java.syntax.Resolver.Binding =
                            (fn as net.starlark.java.eval.StarlarkFunction).rfn.getLocals().get(i)
                        if (binding is net.starlark.java.syntax.Resolver.ComprehensionBinding
                            && !binding.inScope(loc)
                        ) {
                            // Ignore comprehension variables when outside their comprehension's lexical scope.
                            continue
                        }
                        env.put(binding.getName(), local)
                    }
                }
            }
            // TODO(https://github.com/bazelbuild/bazel/issues/24931): comprehension variables are stored
            // in their enclosing function's locals, and can shadow the function's proper local variables
            // (as well as variables of their enclosing comprehension, since comprehensions can nest).
            // When this happens, we emit only the last comprehension binding which has the frame's `loc`
            // within its lexical scope (relying on the fact that when comprehensions are nested, the
            // resolver places inner comprehensions' variables after outer comprehensions' variables in
            // the function's locals list). However, this makes it impossible to examine the shadowed
            // variables' values in the debugger. The real fix would be to push a new debugger frame when
            // in a comprehension.
            return env.buildKeepingLast()
        }

        override fun toString(): String {
            return fn.getName() + "@" + loc
        }
    }

    /** The semantics options that affect how Starlark code is evaluated.  */
    private val semantics: net.starlark.java.eval.StarlarkSemantics?

    /** Whether recursive calls are allowed (cached from semantics).  */
    private val allowRecursion: Boolean

    /** PrintHandler for Starlark print statements.  */
    @kotlin.jvm.JvmField
    private var printHandler: PrintHandler =
        net.starlark.java.eval.StarlarkThread.PrintHandler { thread: StarlarkThread?, msg: String? ->
            net.starlark.java.eval.StarlarkThread.Companion.defaultPrintHandler(
                thread,
                msg
            )
        }

    /** Loader for Starlark load statements. Null if loading is disallowed.  */
    @kotlin.jvm.JvmField
    private var loader: Loader? = null

    private var uncheckedExceptionContext: UncheckedExceptionContext =
        net.starlark.java.eval.StarlarkThread.UncheckedExceptionContext { "" }

    /** Stack of active function calls.  */
    private val callstack: java.util.ArrayList<Frame> = java.util.ArrayList<Frame>()

    /** A hook for notifications of assignments at top level.  */
    var postAssignHook: PostAssignHook? = null

    /** Pushes a function onto the call stack.  */
    fun push(fn: net.starlark.java.eval.StarlarkCallable) {
        // Poll for newly installed CPU profiler.
        if (profiler == null) {
            this.profiler = net.starlark.java.eval.CpuProfiler.Companion.get()
            if (profiler != null) {
                // Associated current Java thread with this StarlarkThread.
                // (Save the previous association so we can restore it later.)
                this.savedThread = net.starlark.java.eval.CpuProfiler.Companion.setStarlarkThread(this)
            }
        }

        if (profiler != null) {
            if (callstack.isEmpty()) {
                // If this is the top-level frame, reset the CPU tick counter.
                cpuTicks.set(0)
            } else {
                // Record CPU ticks already accrued by the current frame, as otherwise they'd be
                // misattributed to the next frame.
                val ticks: Int = cpuTicks.getAndSet(0)
                if (ticks > 0) {
                    profiler.addEvent(ticks, callstack)
                }
            }
        }

        val fr: Frame = net.starlark.java.eval.StarlarkThread.Frame(this, fn)
        callstack.add(fr)

        // Notify debug tools of the thread's first push.
        if (callstack.size() == 1 && net.starlark.java.eval.Debug.threadHook != null) {
            net.starlark.java.eval.Debug.threadHook.onPushFirst(this)
        }

        fr.loc = fn.getLocation()

        // Start wall-time call profile span.
        val callProfiler: CallProfiler? = net.starlark.java.eval.StarlarkThread.Companion.callProfiler
        if (callProfiler != null) {
            fr.profileStartTimeNanos = callProfiler.start()
        }
    }

    /** Pops a function off the call stack.  */
    fun pop() {
        val last: Int = callstack.size() - 1
        val fr: Frame = callstack.get(last)

        if (profiler != null) {
            val ticks: Int = cpuTicks.getAndSet(0)
            if (ticks > 0) {
                profiler.addEvent(ticks, callstack)
            }

            // If this is the final pop in this thread,
            // unregister it from the profiler.
            if (last == 0) {
                // Restore the previous association (in case of reentrant evaluation).
                net.starlark.java.eval.CpuProfiler.Companion.setStarlarkThread(this.savedThread)
                this.savedThread = null
                this.profiler = null
            }
        }

        callstack.remove(last) // pop

        // End wall-time profile span.
        val callProfiler: CallProfiler? = net.starlark.java.eval.StarlarkThread.Companion.callProfiler
        if (callProfiler != null && fr.profileStartTimeNanos >= 0) {
            // Only record the context once since it is the same for all frames.
            val contextDescription = if (last == 0) getContextDescription() else null
            callProfiler.end(fr.profileStartTimeNanos, fr.fn, contextDescription)
        }

        // Notify debug tools of the thread's last pop.
        if (last == 0 && net.starlark.java.eval.Debug.threadHook != null) {
            net.starlark.java.eval.Debug.threadHook.onPopLast(this)
        }
    }

    /** Returns the mutability for values created by this thread.  */
    fun mutability(): net.starlark.java.eval.Mutability {
        return mutability
    }

    /**
     * A PrintHandler determines how a Starlark thread deals with print statements. It is invoked by
     * the built-in `print` function. Its default behavior is to write the message to standard
     * error, preceded by the location of the print statement, `thread.getCallerLocation()`.
     */
    fun interface PrintHandler {
        fun print(thread: StarlarkThread?, msg: String?)
    }

    /** Returns the PrintHandler for Starlark print statements.  */
    fun getPrintHandler(): PrintHandler {
        return printHandler
    }

    /** Sets the behavior of Starlark print statements executed by this thread.  */
    fun setPrintHandler(h: PrintHandler?) {
        this.printHandler = com.google.common.base.Preconditions.checkNotNull<PrintHandler>(h)
    }

    /**
     * A Loader determines the behavior of load statements executed by this thread. It returns the
     * named module, or null if not found.
     */
    @java.lang.FunctionalInterface
    interface Loader : net.starlark.java.syntax.TypeTagger.Loader {
        override fun load(module: String?): net.starlark.java.eval.Module?
    }

    /** Returns the loader for Starlark load statements.  */
    fun getLoader(): Loader? {
        return loader
    }

    /** Sets the behavior of Starlark load statements executed by this thread.  */
    fun setLoader(loader: Loader?) {
        this.loader = com.google.common.base.Preconditions.checkNotNull<Loader?>(loader)
    }

    /**
     * Supplies additional context to append to the message of [Starlark.UncheckedEvalException]
     * or [Starlark.UncheckedEvalError].
     */
    // TODO(brandjon): This seems unnecessary. Instead of implementing a hook that is mutated after
    // thread is constructed, we should be able to just attach this information at construction time.
    interface UncheckedExceptionContext {
        fun getContextForUncheckedException(): String?
    }

    fun setUncheckedExceptionContext(uncheckedExceptionContext: UncheckedExceptionContext?) {
        this.uncheckedExceptionContext =
            com.google.common.base.Preconditions.checkNotNull<UncheckedExceptionContext>(uncheckedExceptionContext)
    }

    fun getContextDescription(): String? {
        return uncheckedExceptionContext.getContextForUncheckedException()
    }

    /** Reports whether `fn` has been recursively reentered within this thread.  */
    fun isRecursiveCall(fn: net.starlark.java.eval.StarlarkFunction): Boolean {
        // Find fn buried within stack. (The top of the stack is assumed to be fn.)
        for (i in callstack.size() - 2 downTo 0) {
            val fr: Frame = callstack.get(i)
            // We compare code, not closure values, otherwise one can defeat the
            // check by writing the Y combinator.
            if (fr.fn is net.starlark.java.eval.StarlarkFunction && (fr.fn as net.starlark.java.eval.StarlarkFunction).rfn == fn.rfn) {
                return true
            }
        }
        return false
    }

    /**
     * Returns the location of the program counter in the enclosing call frame. If called from within
     * a built-in function, this is the location of the call expression that called the built-in. It
     * returns BUILTIN if called with fewer than two frames (such as within a test).
     */
    fun getCallerLocation(): net.starlark.java.syntax.Location? {
        return if (toplevel()) net.starlark.java.syntax.Location.BUILTIN else frame(1).loc
    }

    /**
     * Reports whether the call stack has less than two frames. Zero frames means an idle thread. One
     * frame means the function for the top-level statements of a file is active. More than that means
     * a function call is in progress.
     * 
     * 
     * Every use of this function is a hack to work around the lack of proper local vs global
     * identifier resolution at top level.
     */
    private fun toplevel(): Boolean {
        return callstack.size() < 2
    }

    // Returns the stack frame at the specified depth. 0 means top of stack, 1 is its caller, etc.
    fun frame(depth: Int): Frame? {
        return callstack.get(callstack.size() - 1 - depth)
    }

    /**
     * Specifies a hook function to be run after each assignment at top level.
     * 
     * 
     * This is a short-term hack to allow us to consolidate all StarlarkFile execution in one place
     * even while BzlLoadFunction implements the old "export" behavior, in which rules, aspects and
     * providers are "exported" as soon as they are assigned, not at the end of file execution.
     */
    fun setPostAssignHook(postAssignHook: PostAssignHook?) {
        this.postAssignHook = postAssignHook
    }

    /** A hook for notifications of assignments at top level.  */
    fun interface PostAssignHook {
        fun assign(name: String?, nameStartLocation: net.starlark.java.syntax.Location?, value: Any?)
    }

    fun getSemantics(): net.starlark.java.eval.StarlarkSemantics? {
        return semantics
    }

    /** Reports whether this thread is allowed to make recursive calls.  */
    fun isRecursionAllowed(): Boolean {
        return allowRecursion
    }

    // Implementation of Debug.getCallStack.
    // Intentionally obscured to steer most users to the simpler getCallStack.
    fun getDebugCallStack(): com.google.common.collect.ImmutableList<net.starlark.java.eval.Debug.Frame?> {
        return com.google.common.collect.ImmutableList.copyOf<net.starlark.java.eval.Debug.Frame?>(callstack)
    }

    fun getInnermostEnclosingStarlarkFunction(depth: Int): net.starlark.java.eval.StarlarkFunction? {
        var depth = depth
        com.google.common.base.Preconditions.checkArgument(depth >= 0)
        for (i in callstack.indices.reversed()) {
            val fr: net.starlark.java.eval.Debug.Frame = callstack.get(i)
            if (fr.getFunction() is net.starlark.java.eval.StarlarkFunction) {
                if (depth == 0) {
                    return fr.getFunction() as net.starlark.java.eval.StarlarkFunction
                }
                depth--
            }
        }
        return null
    }

    /** Returns the size of the callstack. This is needed for the debugger.  */
    fun getCallStackSize(): Int {
        return callstack.size()
    }

    /**
     * A CallStackEntry describes the name and PC location of an active function call. See [ ][.getCallStack].
     */
    @javax.annotation.concurrent.Immutable
    class CallStackEntry private constructor(name: String?, location: net.starlark.java.syntax.Location?) {
        @kotlin.jvm.JvmField
        val name: String
        @kotlin.jvm.JvmField
        val location: net.starlark.java.syntax.Location

        init {
            this.name = com.google.common.base.Preconditions.checkNotNull<String>(name)
            this.location =
                com.google.common.base.Preconditions.checkNotNull<net.starlark.java.syntax.Location>(location)
        }

        override fun toString(): String {
            return name + "@" + location
        }

        override fun hashCode(): Int {
            return 31 * name.hashCode() + location.hashCode()
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is CallStackEntry) {
                return false
            }
            val that = o
            return name == that.name && location == that.location
        }
    }

    /**
     * Returns information about this thread's current stack of active function calls, outermost call
     * first. For each function, it reports its name, and the location of its current program counter.
     * The result is immutable and does not reference interpreter data structures, so it may retained
     * indefinitely and safely shared with other threads.
     */
    fun getCallStack(): com.google.common.collect.ImmutableList<CallStackEntry?> {
        val stack: com.google.common.collect.ImmutableList.Builder<CallStackEntry?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<CallStackEntry?>(callstack.size())
        for (fr in callstack) {
            stack.add(net.starlark.java.eval.StarlarkThread.Companion.callStackEntry(fr.fn.getName(), fr.loc))
        }
        return stack.build()
    }

    /** Sets the given throwable's stack trace to a Java-style version of [.getCallStack].  */
    fun fillInStackTrace(throwable: Throwable) {
        val trace: Array<java.lang.StackTraceElement?> = arrayOfNulls<java.lang.StackTraceElement>(callstack.size())
        for (i in callstack.indices) {
            val frame: Frame = callstack.get(i)
            trace[trace.size - i - 1] =
                java.lang.StackTraceElement(
                    "<starlark>", frame.fn.getName(), frame.loc.file(), frame.loc.line()
                )
        }
        throwable.setStackTrace(trace)
    }

    override fun hashCode(): Int {
        throw java.lang.UnsupportedOperationException() // avoid nondeterminism
    }

    override fun equals(that: Any?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    override fun toString(): String {
        return java.lang.String.format("<StarlarkThread%s>", mutability)
    }

    /** CallProfiler records the start and end wall times of function calls.  */
    interface CallProfiler {
        fun start(): Long

        /**
         * Records the end time of a function call.
         * 
         * @param threadContext an optional description of the context in which the function is called.
         * Only non-null for the outermost function in a call stack.
         */
        fun end(startTimeNanos: Long, fn: net.starlark.java.eval.StarlarkCallable?, threadContext: String?)
    }

    fun getNextIdentityToken(): net.starlark.java.eval.SymbolGenerator.Symbol<*> {
        return symbolGenerator.generate()
    }

    fun getSymbolGenerator(): net.starlark.java.eval.SymbolGenerator<*> {
        return symbolGenerator
    }

    fun getOwner(): Any? {
        return symbolGenerator.getOwner()
    }

    fun getBuiltinManager(): net.starlark.java.eval.CallUtils.BuiltinManager? {
        return builtinManager
    }

    init {
        this.mutability = com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.Mutability>(mu)
        this.semantics = semantics
        this.allowRecursion = semantics.getBool(net.starlark.java.eval.StarlarkSemantics.Companion.ALLOW_RECURSION)
        if (!contextDescription.isEmpty()) {
            setUncheckedExceptionContext(net.starlark.java.eval.StarlarkThread.UncheckedExceptionContext { contextDescription })
        }
        this.symbolGenerator = symbolGenerator
        this.builtinManager = net.starlark.java.eval.CallUtils.getBuiltinManager(semantics)
    }

    companion object {
        private fun defaultPrintHandler(thread: StarlarkThread, msg: String?) {
            java.lang.System.err.println(thread.getCallerLocation().toString() + ": " + msg)
        }

        /**
         * Creates a StarlarkThread.
         * 
         * @param mu the (non-frozen) mutability of values created by this thread.
         * @param semantics the StarlarkSemantics for this thread. Note that it is generally a code smell
         * to use [StarlarkSemantics.DEFAULT] if the application permits customizing the
         * semantics (e.g. via command line flags). Usually, all Starlark evaluation contexts within
         * the same application would use the same `StarlarkSemantics` instance.
         * @param contextDescription a short description of this evaluation, added as context when an
         * exception is thrown as well as in profiles. The empty String can be used as a default
         * value.
         * @param symbolGenerator a supplier of deterministic, stable IDs for objects created by this
         * thread
         */
        // TODO(bazel-team): Consider merging contextDescription into the symbolGenerator.
        fun create(
            mu: net.starlark.java.eval.Mutability?,
            semantics: net.starlark.java.eval.StarlarkSemantics,
            contextDescription: String,
            symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>
        ): StarlarkThread {
            return net.starlark.java.eval.StarlarkThread(mu, semantics, contextDescription, symbolGenerator)
        }

        /**
         * Creates a StarlarkThread with an empty `contextDescription` and transient `symbolGenerator`.
         * 
         * 
         * See comments at [SymbolGenerator.createTransient] for when this is applicable.
         */
        fun createTransient(
            mu: net.starlark.java.eval.Mutability?,
            semantics: net.starlark.java.eval.StarlarkSemantics
        ): StarlarkThread {
            return net.starlark.java.eval.StarlarkThread(
                mu,
                semantics,  /* contextDescription= */
                "",
                net.starlark.java.eval.SymbolGenerator.Companion.createTransient()
            )
        }

        /**
         * The value of [CallStackEntry.name] for the implicit function that executes the top-level
         * statements of a file.
         */
        const val TOP_LEVEL: String = "<toplevel>"

        /** Creates a new [CallStackEntry].  */
        fun callStackEntry(name: String?, location: net.starlark.java.syntax.Location?): CallStackEntry {
            return net.starlark.java.eval.StarlarkThread.CallStackEntry(name, location)
        }

        /** Installs a global hook that will be notified of function calls.  */
        fun setCallProfiler(p: CallProfiler?) {
            net.starlark.java.eval.StarlarkThread.Companion.callProfiler = p
        }

        private var callProfiler: CallProfiler? = null
    }
}
