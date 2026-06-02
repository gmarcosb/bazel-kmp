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

import java.util.LinkedHashMap

/**
 * The StarlarkCallable interface is implemented by all Starlark values that may be called from
 * Starlark like a function, including built-in functions and methods, Starlark functions, and
 * application-defined objects (such as rules, aspects, and providers in Bazel).
 * 
 * 
 * It defines two methods: `fastcall`, for performance, or `call` for convenience. By
 * default, `fastcall` delegates to `call`, and call throws an exception, so an
 * implementer may override either one.
 */
interface StarlarkCallable : net.starlark.java.eval.StarlarkValue {
    /**
     * Defines the "convenient" implementation of function calling for a callable value.
     * 
     * 
     * Do not call this function directly. Use the [Starlark.call] function to make a call,
     * as it handles necessary book-keeping such as maintenance of the call stack, exception handling,
     * and so on.
     * 
     * 
     * The default implementation throws an EvalException.
     * 
     * 
     * See [Starlark.fastcall] for basic information about function calls.
     * 
     * @param thread the StarlarkThread in which the function is called
     * @param args a tuple of the arguments passed by position
     * @param kwargs a new, mutable dict of the arguments passed by keyword. Iteration order is
     * determined by keyword order in the call expression.
     */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun call(
        thread: net.starlark.java.eval.StarlarkThread?,
        args: net.starlark.java.eval.Tuple?,
        kwargs: net.starlark.java.eval.Dict<String?, Any?>?
    ): Any? {
        throw net.starlark.java.eval.Starlark.Companion.errorf("function %s not implemented", getName())
    }

    /**
     * Defines the "fast" implementation variant of function calling with only positional arguments.
     * 
     * 
     * Do not call this function directly. Use the [Starlark.easycall] function to make a
     * call, as it handles necessary book-keeping such as maintenance of the call stack, exception
     * handling, and so on.
     * 
     * 
     * The fastcall implementation takes ownership of the `positional` array, and may retain
     * it indefinitely or modify it. The caller must not modify or even access the array after making
     * the call.
     * 
     * 
     * The default implementation forwards the call to `call`.
     * 
     * @param thread the StarlarkThread in which the function is called
     * @param positional a list of positional arguments
     */
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun positionalOnlyCall(thread: net.starlark.java.eval.StarlarkThread, vararg positional: Any?): Any? {
        val argumentProcessor = requestArgumentProcessor(thread)
        for (value in positional) {
            argumentProcessor.addPositionalArg(value)
        }
        return argumentProcessor.call(thread)
    }

    /**
     * Defines a helper object for invoking a StarlarkCallable.
     * 
     * 
     * An ArgumentProcessor implementation is returned by [.requestArgumentProcessor]. The
     * ArgumentProcessor implementation must then be used to first place the arguments, and then its
     * method [.call] is used to make the invocation.
     */
    class ArgumentProcessor(thread: net.starlark.java.eval.StarlarkThread) {
        protected val thread: net.starlark.java.eval.StarlarkThread

        init {
            this.thread = thread
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        abstract fun addPositionalArg(value: Any?)

        @Throws(net.starlark.java.eval.EvalException::class)
        abstract fun addNamedArg(name: String?, value: Any?)

        abstract fun getCallable(): StarlarkCallable?

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        abstract fun call(thread: net.starlark.java.eval.StarlarkThread?): Any?

        /**
         * Throws a given `EvalException` from inside [.addPositionalArg] or [ ][.addNamedArg].
         * 
         * 
         * In the Starlark evaluation model, the work of ArgumentProcessor is logically part of the
         * callable's evaluation, so the stack trace for any exceptions thrown during argument
         * processing needs to contain the name and location of the callable. This method pushes the
         * stack before throwing the exception, ensuring that the stack trace is as expected.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        protected fun pushCallableAndThrow(e: net.starlark.java.eval.EvalException) {
            thread.push(getCallable())
            throw e
        }
    }

    /**
     * A default implementation of ArgumentProcessor that simply stores the arguments in a list and a
     * LinkedHashMap and then passes them to the StarlarkCallable.call() method.
     */
    class DefaultArgumentProcessor internal constructor(
        owner: StarlarkCallable,
        thread: net.starlark.java.eval.StarlarkThread
    ) : ArgumentProcessor(thread) {
        private val owner: StarlarkCallable
        private val positional: java.util.ArrayList<Any?>
        private val named: LinkedHashMap<String?, Any?>

        init {
            this.owner = owner
            this.positional = java.util.ArrayList<Any?>()
            this.named = com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<String?, Any?>(0)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addPositionalArg(value: Any?) {
            positional.add(value)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addNamedArg(name: String?, value: Any?) {
            if (named.put(name, value) != null) {
                pushCallableAndThrow(
                    net.starlark.java.eval.Starlark.Companion.errorf(
                        "%s got multiple values for parameter '%s'",
                        owner,
                        name
                    )
                )
            }
        }

        override fun getCallable(): StarlarkCallable {
            return owner
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        override fun call(thread: net.starlark.java.eval.StarlarkThread): Any? {
            return owner.call(
                thread,
                net.starlark.java.eval.Tuple.Companion.wrap(positional.toArray()),
                net.starlark.java.eval.Dict.Companion.wrap<String?, Any?>(thread.mutability(), named)
            )
        }
    }

    /**
     * Returns an [ArgumentProcessor] which the callable can implement for faster calling.
     * 
     * 
     * By default this returns a [DefaultArgumentProcessor] which can be used to call the
     * callable via [.call].
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun requestArgumentProcessor(thread: net.starlark.java.eval.StarlarkThread): ArgumentProcessor {
        return net.starlark.java.eval.StarlarkCallable.DefaultArgumentProcessor(this, thread)
    }

    /** Returns the form this callable value should take in a stack trace.  */
    fun getName(): String?

    /**
     * Returns the location of the definition of this callable value, or BUILTIN if it was not defined
     * in Starlark code.
     */
    fun getLocation(): net.starlark.java.syntax.Location? {
        return net.starlark.java.syntax.Location.BUILTIN
    }
}
