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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.events.Event

/**
 * A helper class for calling Starlark functions from Java, where the argument values are supplied
 * by the fields of a Structure, as in the case of computed attribute defaults and computed implicit
 * outputs.
 */
// TODO(brandjon): Consider eliminating this class by executing the callback in the same thread as
// the caller, i.e. in the thread evaluating a BUILD file. This might not be possible for implicit
// outputs without some refactoring to cache the result of the computation (currently RuleContext
// seems to reinvoke the callback).
class StarlarkCallbackHelper(
    callback: net.starlark.java.eval.StarlarkFunction,
    starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
) {
    private val callback: net.starlark.java.eval.StarlarkFunction

    // These fields, parts of the state of the loading-phase
    // thread that instantiated a rule, must be propagated to
    // the child threads (implicit outputs, attribute defaults).
    // This includes any other thread-local state, such as
    // PackageFactory.PackageContext.
    // TODO(adonovan): it would be cleaner and less error prone to
    // perform these callbacks in the actual loading-phase thread,
    // at the end of BUILD file execution.
    // Alternatively (or additionally), we could put PackageContext
    // into BazelStarlarkContext so there's only a single blob of state.
    private val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?

    init {
        this.callback = callback
        this.starlarkSemantics = starlarkSemantics
    }

    fun getParameterNames(): com.google.common.collect.ImmutableList<String> {
        return callback.getParameterNames()
    }

    // TODO(adonovan): opt: all current callers are forced to construct a temporary Structure.
    // Instead, make them supply a map.
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun call(eventHandler: EventHandler?, struct: net.starlark.java.eval.Structure, vararg arguments: Any?): Any? {
        try {
            net.starlark.java.eval.Mutability.create("callback", callback).use { mu ->
                // TODO(brandjon): In principle, if we're creating a new symbol generator here, we should have
                // a unique owner object to associate it with for distinguishing reference-equality objects.
                // But I don't think implicit outputs or computed defaults care about identity.
                val thread: net.starlark.java.eval.StarlarkThread =
                    net.starlark.java.eval.StarlarkThread.createTransient(mu, starlarkSemantics)
                thread.setPrintHandler(Event.makeDebugPrintHandler(eventHandler))
                return net.starlark.java.eval.Starlark.call(
                    thread,
                    callback,
                    buildArgumentList(struct, *arguments),  /*kwargs=*/
                    com.google.common.collect.ImmutableMap.of<String?, Any?>()
                )
            }
        } catch (e: java.lang.ClassCastException) { // TODO(adonovan): investigate
            throw net.starlark.java.eval.EvalException(e)
        } catch (e: java.lang.IllegalArgumentException) {
            throw net.starlark.java.eval.EvalException(e)
        }
    }

    /**
     * Creates a list of actual arguments that contains the given arguments and all attribute values
     * required from the specified structure.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun buildArgumentList(
        struct: net.starlark.java.eval.Structure,
        vararg arguments: Any?
    ): com.google.common.collect.ImmutableList<Any?> {
        val builder: com.google.common.collect.ImmutableList.Builder<Any?> =
            com.google.common.collect.ImmutableList.builder<Any?>()
        val names: com.google.common.collect.ImmutableList<String> = getParameterNames()
        val requiredParameters: Int = names.size() - arguments.size
        for (pos in 0..<requiredParameters) {
            val name: String = names.get(pos)
            val value: Any? = struct.getValue(name)
            requireNotNull(value) { struct.getErrorMessageForUnknownField(name) }
            builder.add(value)
        }
        return builder.add(*arguments).build()
    }
}
