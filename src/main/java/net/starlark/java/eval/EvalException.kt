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

/** An EvalException indicates an Starlark evaluation error.  */
open class EvalException @kotlin.jvm.JvmOverloads constructor(
    message: String?,
    cause: Throwable? = null as Throwable?,
    includeStackTrace: Boolean = true
) : java.lang.Exception(com.google.common.base.Preconditions.checkNotNull<String?>(message), cause) {
    // The call stack associated with this error.
    // It is initially null, but is set by the interpreter to a non-empty
    // stack when popping a frame. Thus an exception newly created by a
    // built-in function has no stack until it is thrown out of a function call.
    private var callstack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry>? =
        null
    private val includeStackTrace: Boolean

    /**
     * Constructs an EvalException with a message and optional cause and defaulting stack trace to
     * true.
     * 
     * 
     * The cause does not affect the error message, so callers should incorporate `cause.getMessage()` into `message` if desired, or call `EvalException(Throwable)`.
     */
    constructor(message: String?, cause: Throwable?) : this(
        com.google.common.base.Preconditions.checkNotNull<String?>(
            message
        ), cause,  /* includeStackTrace= */true
    )

    /** Constructs an EvalException using the same message as the cause exception.  */
    constructor(cause: Throwable) : this(
        net.starlark.java.eval.EvalException.Companion.getCauseMessage(cause),
        cause,  /* includeStackTrace= */
        true
    )

    /**
     * Fills in the callstack if it hasn't been set yet.
     * 
     * @param callstack the Starlark callstack; must not be empty.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun withCallStack(callstack: MutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>): EvalException {
        com.google.common.base.Preconditions.checkArgument(!callstack.isEmpty(), "Callstack cannot be empty")
        if (this.callstack == null) {
            this.callstack =
                com.google.common.collect.ImmutableList.copyOf<net.starlark.java.eval.StarlarkThread.CallStackEntry?>(
                    callstack
                )
        }
        return this
    }

    /** Returns the error message. Does not include call stack or cause.  */
    override fun getMessage(): String? {
        return super.getMessage()
    }

    /**
     * Returns the call stack associated with this error, outermost call first. A newly constructed
     * exception has an empty stack, but an exception that has been thrown out of a Starlark function
     * call has its stack populated automatically. The identity of the thrown exception does not
     * change.
     * 
     * 
     * EvalException is widely used to indicate the failure of basic operations on Starlark values,
     * such as those corresponding to the Starlark expressions `x.f`, `x[i]`, `x+y`,
     * and so on, even when these failing operations occur outside the context of a StarlarkThread or
     * the interpreter. EvalExceptions from such failures do not have an associated stack.
     * 
     * 
     * For best results, when handling an EvalException, print the stack, using [ ][.getMessageWithStack] to display multiple complete lines of output, only if the exception
     * resulted from Starlark evaluation. For an EvalException with no stack, use [.getMessage]
     * to obtain a message suitable for incorporating into a larger error.
     */
    fun getCallStack(): com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?> {
        return if (callstack != null) callstack else com.google.common.collect.ImmutableList.of<net.starlark.java.eval.StarlarkThread.CallStackEntry?>()
    }

    /** Returns the innermost non-builtin location in the call stack, or null if there is none.  */
    fun getInnermostLocation(): net.starlark.java.syntax.Location? {
        if (callstack == null) {
            return null
        }
        return callstack.reverse().stream()
            .map<net.starlark.java.syntax.Location?>(java.util.function.Function { entry: net.starlark.java.eval.StarlarkThread.CallStackEntry? -> entry.location })
            .filter(java.util.function.Predicate { location: net.starlark.java.syntax.Location? -> location !== net.starlark.java.syntax.Location.BUILTIN })
            .findFirst()
            .orElse(null)
    }

    /** Returns the error message along with its call stack. May be overridden by subclasses.  */
    override fun toString(): String {
        return getMessageWithStack()!!
    }

    /**
     * Returns the error message along with its call stack, if any. Equivalent to `getMessageWithStack(newSourceReader())`.
     */
    fun getMessageWithStack(): String? {
        return getMessageWithStack(net.starlark.java.eval.EvalException.Companion.newSourceReader())
    }

    /**
     * Returns the error message along with its call stack, if any (see [.getCallStack]). The
     * source line for each stack frame is obtained from the provided SourceReader.
     */
    fun getMessageWithStack(src: SourceReader): String? {
        if (includeStackTrace && callstack != null) {
            return net.starlark.java.eval.EvalException.Companion.formatCallStack(callstack, getMessage(), src)
        }
        return getMessage()
    }

    /**
     * A SourceReader reads the line of source denoted by a Location to be displayed in a formatted
     * stack trace.
     */
    interface SourceReader {
        /** Returns a single line of source code (sans newline), or null if unavailable.  */
        fun readline(loc: net.starlark.java.syntax.Location?): String?
    }

    /**
     * Constructs an EvalException with a message and optional cause and bool indicating if the error
     * should contain a stack trace.
     * 
     * 
     * The cause does not affect the error message, so callers should incorporate `cause.getMessage()` into `message` if desired, or call `EvalException(Throwable)`.
     */
    /** Constructs an EvalException. Use [Starlark.errorf] if you want string formatting.  */
    init {
        this.includeStackTrace = includeStackTrace
    }

    // Ensures that this exception holds a call stack, taking the current
    // stack (which must be non-empty) from the thread if not.
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun ensureStack(thread: net.starlark.java.eval.StarlarkThread): EvalException {
        if (callstack == null) {
            this.callstack = thread.getCallStack()
            check(!callstack.isEmpty()) { "empty callstack" }
        }
        return this
    }

    companion object {
        private fun getCauseMessage(cause: Throwable): String? {
            val msg: String? = cause.getMessage()
            return if (msg != null) msg else cause.toString()
        }

        /**
         * Sets the function used to obtain a SourceReader when subsequently formatting a call stack.
         * 
         * 
         * The default supplier returns SourceReaders that read from the file system, but a
         * security-conscious client may wish to disable this capability or provide an alternative.
         */
        @kotlin.jvm.Synchronized
        fun setSourceReaderSupplier(f: java.util.function.Supplier<SourceReader?>) {
            net.starlark.java.eval.EvalException.Companion.sourceReaderSupplier = f
        }

        /** Returns a new SourceReader. See [.setSourceReaderSupplier].  */
        @kotlin.jvm.Synchronized
        fun newSourceReader(): SourceReader? {
            return net.starlark.java.eval.EvalException.Companion.sourceReaderSupplier.get()
        }

        private var sourceReaderSupplier: java.util.function.Supplier<SourceReader?> = java.util.function.Supplier {
            net.starlark.java.eval.EvalException.SourceReader { loc: net.starlark.java.syntax.Location? ->
                try {
                    val content: String = com.google.common.io.Files.asCharSource(
                        java.io.File(loc.file()),
                        java.nio.charset.StandardCharsets.UTF_8
                    ).read()
                    return@SourceReader com.google.common.collect.Iterables.get<String?>(
                        com.google.common.base.Splitter.on(
                            "\n"
                        ).split(content), loc.line() - 1, null
                    )
                } catch (unused: Throwable) {
                    // ignore any failure (e.g. security manager rejecting I/O)
                }
                null
            }
        }

        /**
         * Formats the given call stack and error message. Provided as a separate function from [ ][.getMessageWithStack] so that clients may modify the stack and/or error before formatting it.
         * The source line for each stack frame is obtained from the provided SourceReader.
         */
        fun formatCallStack(
            callstack: MutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry>,
            message: String?,
            src: SourceReader
        ): String {
            val buf: java.lang.StringBuilder = java.lang.StringBuilder()
            var n: Int = callstack.size() // n > 0
            var prefix = "Error: "
            // If the topmost frame is a built-in, don't show it.
            // Instead just prefix the name of the built-in onto the error message.
            val leaf: net.starlark.java.eval.StarlarkThread.CallStackEntry = callstack.get(n - 1)
            if (leaf.location == net.starlark.java.syntax.Location.BUILTIN) {
                prefix = "Error in " + leaf.name + ": "
                n--
            }
            if (n > 0) {
                buf.append("Traceback (most recent call last):\n")
                for (i in 0..<n) {
                    val fr: net.starlark.java.eval.StarlarkThread.CallStackEntry = callstack.get(i)
                    // 'File "file.bzl", line 1, column 2, in fn'
                    buf.append(java.lang.String.format("\tFile \"%s\", ", fr.location.file()))
                    if (fr.location.line() != 0) {
                        buf.append("line ").append(fr.location.line()).append(", ")
                        if (fr.location.column() != 0) {
                            buf.append("column ").append(fr.location.column()).append(", ")
                        }
                    }
                    buf.append("in ").append(fr.name).append('\n')

                    // source line
                    val line = src.readline(fr.location)
                    if (line != null) {
                        buf.append("\t\t").append(line.trim()).append('\n')
                    }
                }
            }
            buf.append(prefix).append(message)
            return buf.toString()
        }
    }
}
