// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.StarlarkThreadContext

/** Starlark function that determines whether aspect should be propagated to the target.  */
class AspectPropagationPredicate(
    predicate: net.starlark.java.eval.StarlarkFunction?,
    semantics: net.starlark.java.eval.StarlarkSemantics?
) {
    private val predicate: net.starlark.java.eval.StarlarkFunction?
    private val semantics: net.starlark.java.eval.StarlarkSemantics?

    init {
        this.predicate = predicate
        this.semantics = semantics
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    fun evaluate(
        context: StarlarkAspectPropagationContextApi?, eventHandler: ExtendedEventHandler?
    ): Boolean {
        val starlarkResult = runPropagationPredicate(context, eventHandler)

        if (starlarkResult is Boolean) {
            return starlarkResult
        }
        throw net.starlark.java.eval.EvalException("Expected a boolean")
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    private fun runPropagationPredicate(
        context: StarlarkAspectPropagationContextApi?, eventHandler: ExtendedEventHandler?
    ): Any? {
        net.starlark.java.eval.Mutability.create("aspect_propagation_predicate").use { mu ->
            val thread: net.starlark.java.eval.StarlarkThread =
                net.starlark.java.eval.StarlarkThread.createTransient(mu, semantics)
            thread.setPrintHandler(Event.makeDebugPrintHandler(eventHandler))

            AspectPropagationThreadContext().storeInThread(thread)
            return net.starlark.java.eval.Starlark.positionalOnlyCall(thread, predicate, context)
        }
    }

    private class AspectPropagationThreadContext : StarlarkThreadContext(null)

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || getClass() != o.getClass()) {
            return false
        }
        val that = o as AspectPropagationPredicate
        return predicate == that.predicate && semantics == that.semantics
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(predicate, semantics)
    }
}
