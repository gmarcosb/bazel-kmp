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

import com.google.devtools.build.lib.cmdline.Label

/**
 * Supplies the list of edges (attribute names or toolchain types) that should be propagated by an
 * aspect. It is extended by 2 classes:
 * 
 * 
 *  * FixedListSupplier: for the case when the list is fixed and known at the aspect definition
 * time.
 *  * FunctionSupplier: for the case when the list is computed for each target that aspect
 * visits.
 * 
 * 
 * The type <T> is String for `attr_aspects` and [Label] for `toolchains_aspects`.
</T> */
interface AspectPropagationEdgesSupplier<T> {
    /** A supplier of the edges that is fixed and known at the aspect definition time.  */
    class FixedListSupplier<T> private constructor(edges: com.google.common.collect.ImmutableSet<T?>?) :
        AspectPropagationEdgesSupplier<T?> {
        private val edges: com.google.common.collect.ImmutableSet<T?>?

        init {
            this.edges = edges
        }

        fun getList(): com.google.common.collect.ImmutableSet<T?>? {
            return edges
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || o !is FixedListSupplier<*>) {
                return false
            }

            return edges == o.edges
        }

        override fun hashCode(): Int {
            return java.util.Objects.hashCode(edges)
        }
    }

    /** A supplier of the edges that is computed for each target that aspect visits.  */
    class FunctionSupplier<T>
    private constructor(
        function: net.starlark.java.eval.StarlarkFunction?,
        semantics: net.starlark.java.eval.StarlarkSemantics?
    ) : AspectPropagationEdgesSupplier<T?> {
        private val function: net.starlark.java.eval.StarlarkFunction?
        private val semantics: net.starlark.java.eval.StarlarkSemantics?

        init {
            this.function = function
            this.semantics = semantics
        }

        @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
        abstract fun computeList(
            context: StarlarkAspectPropagationContextApi?, eventHandler: ExtendedEventHandler?
        ): com.google.common.collect.ImmutableSet<T?>?

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || o !is FunctionSupplier<*>) {
                return false
            }

            return function == o.function && semantics == o.semantics
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(function, semantics)
        }

        private class AspectPropagationEdgesThreadContext : StarlarkThreadContext(null)

        @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
        protected fun runFunction(
            context: StarlarkAspectPropagationContextApi?, eventHandler: ExtendedEventHandler?
        ): net.starlark.java.eval.StarlarkList<*> {
            net.starlark.java.eval.Mutability.create("aspect_propagation_edges").use { mu ->
                val thread: net.starlark.java.eval.StarlarkThread =
                    net.starlark.java.eval.StarlarkThread.createTransient(mu, semantics)
                thread.setPrintHandler(Event.makeDebugPrintHandler(eventHandler))
                AspectPropagationEdgesThreadContext().storeInThread(thread)
                val starlarkResult: Any? = net.starlark.java.eval.Starlark.positionalOnlyCall(thread, function, context)
                if (starlarkResult is net.starlark.java.eval.StarlarkList<*>) {
                    return starlarkResult
                }
                throw net.starlark.java.eval.EvalException("Expected a list")
            }
        }
    }

    /** A function supplier for `attr_aspects`.  */
    class AttrAspectsFunctionSupplier private constructor(
        function: net.starlark.java.eval.StarlarkFunction?,
        semantics: net.starlark.java.eval.StarlarkSemantics?
    ) : FunctionSupplier<String?>(function, semantics) {
        @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
        override fun computeList(
            context: StarlarkAspectPropagationContextApi?, eventHandler: ExtendedEventHandler?
        ): com.google.common.collect.ImmutableSet<String?> {
            return parseAttrAspects(runFunction(context, eventHandler),  /* allowAll= */false)
        }
    }

    /** A function supplier for `toolchains_aspects`.  */
    class ToolchainsAspectsFunctionSupplier private constructor(
        function: net.starlark.java.eval.StarlarkFunction?,
        semantics: net.starlark.java.eval.StarlarkSemantics?
    ) : FunctionSupplier<Label?>(function, semantics) {
        @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
        override fun computeList(
            context: StarlarkAspectPropagationContextApi?, eventHandler: ExtendedEventHandler?
        ): com.google.common.collect.ImmutableSet<Label?> {
            val listResult: net.starlark.java.eval.StarlarkList<*>? = runFunction(context, eventHandler)
            if (listResult == null || listResult.isEmpty()) {
                return com.google.common.collect.ImmutableSet.of<Label?>()
            }
            return com.google.common.collect.ImmutableSet.copyOf<Label?>(
                net.starlark.java.eval.Sequence.cast<Label?>(
                    listResult,
                    Label::class.java,
                    "toolchains_aspects"
                )
            )
        }
    }

    companion object {
        val DEFAULT_ATTR_ASPECTS_SUPPLIER: AspectPropagationEdgesSupplier<String?> =
            FixedListSupplier<String?>(com.google.common.collect.ImmutableSet.of<String?>())

        val DEFAULT_TOOLCHAINS_ASPECTS_SUPPLIER: FixedListSupplier<Label?> =
            FixedListSupplier<Label?>(com.google.common.collect.ImmutableSet.of<Label?>())

        @Throws(net.starlark.java.eval.EvalException::class)
        fun createForAttrAspects(
            rawAttrAspects: Any?, thread: net.starlark.java.eval.StarlarkThread
        ): AspectPropagationEdgesSupplier<String?> {
            if (rawAttrAspects is net.starlark.java.eval.StarlarkFunction) {
                return AttrAspectsFunctionSupplier(rawAttrAspects, thread.getSemantics())
            } else {
                return FixedListSupplier<String?>(parseAttrAspects(rawAttrAspects,  /* allowAll= */true))
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun createForToolchainsAspects(
            rawToolchainsAspects: Any?, thread: net.starlark.java.eval.StarlarkThread, labelConverter: LabelConverter
        ): AspectPropagationEdgesSupplier<Label?> {
            if (rawToolchainsAspects is net.starlark.java.eval.StarlarkFunction) {
                return ToolchainsAspectsFunctionSupplier(
                    rawToolchainsAspects, thread.getSemantics()
                )
            } else {
                return FixedListSupplier<Label?>(parseToolchainsAspects(rawToolchainsAspects, labelConverter))
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun parseAttrAspects(
            rawAttrAspects: Any?,
            allowAll: Boolean
        ): com.google.common.collect.ImmutableSet<String?> {
            val attrAspects: net.starlark.java.eval.Sequence<String> =
                net.starlark.java.eval.Sequence.cast<String?>(rawAttrAspects, String::class.java, "attr_aspects")

            val attrAspectsBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            for (attrName in attrAspects) {
                if (attrName == "*") {
                    if (!allowAll) {
                        throw net.starlark.java.eval.EvalException("'*' is not allowed in 'attr_aspects' list")
                    } else if (attrAspects.size() != 1) {
                        throw net.starlark.java.eval.EvalException("'*' must be the only string in 'attr_aspects' list")
                    }
                }
                if (!attrName.startsWith("_")) {
                    attrAspectsBuilder.add(attrName)
                } else {
                    // Implicit attribute names mean either implicit or late-bound attributes
                    // (``$attr`` or ``:attr``). Depend on both.
                    attrAspectsBuilder
                        .add(AttributeValueSource.COMPUTED_DEFAULT.convertToNativeName(attrName))
                        .add(AttributeValueSource.LATE_BOUND.convertToNativeName(attrName))
                }
            }

            return attrAspectsBuilder.build()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun parseToolchainsAspects(
            rawToolchainsAspects: Any?, labelConverter: LabelConverter
        ): com.google.common.collect.ImmutableSet<Label?> {
            val toolchainsAspects: net.starlark.java.eval.Sequence<String?> =
                net.starlark.java.eval.Sequence.cast<String?>(
                    rawToolchainsAspects,
                    String::class.java,
                    "toolchains_aspects"
                )

            if (toolchainsAspects.size() == 1 && toolchainsAspects.get(0) == "*") {
                return com.google.common.collect.ImmutableSet.of<Label?>(ALL_TOOLCHAINS)
            }

            val parsedLabels: com.google.common.collect.ImmutableSet.Builder<Label?> =
                com.google.common.collect.ImmutableSet.Builder<Label?>()
            for (input in toolchainsAspects) {
                if (input == "*") {
                    // This is already handled if the list has a single '*' item in it
                    throw net.starlark.java.eval.EvalException("'*' must be the only item in 'toolchains_aspects' list")
                }
                try {
                    val label: Label? = labelConverter.convert(input)
                    parsedLabels.add(label)
                } catch (e: LabelSyntaxException) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Unable to parse label '%s' in attribute '%s': %s",
                        input, "toolchains_aspects", e.getMessage()
                    )
                }
            }
            return parsedLabels.build()
        }

        val ALL_TOOLCHAINS: Label = Label.parseCanonicalUnchecked("//__toolchains_aspects__:all")
    }
}
