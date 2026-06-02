// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.actions.Substitution

/** Implementation of the `TemplateDict` Starlark type  */
class TemplateDict private constructor() : TemplateDictApi {
    private val substitutions: MutableList<Substitution?> = java.util.ArrayList<Substitution?>()

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun addArgument(
        key: String?,
        value: String?,
        thread: net.starlark.java.eval.StarlarkThread?
    ): TemplateDictApi {
        substitutions.add(Substitution.of(key, value))
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun addJoined(
        key: String?,
        valuesSet: Depset,
        joinWith: String,
        mapEach: net.starlark.java.eval.StarlarkCallable?,
        uniquify: Boolean,
        formatJoined: Any?,
        allowClosure: Boolean,
        thread: net.starlark.java.eval.StarlarkThread
    ): TemplateDictApi {
        if (mapEach is net.starlark.java.eval.StarlarkFunction) {
            if (!allowClosure && mapEach.getModule().getGlobal(mapEach.getName()) !== mapEach) {
                throw net.starlark.java.eval.Starlark.errorf(
                    ("to avoid unintended retention of analysis data structures, "
                            + "the map_each function (declared at %s) must be declared "
                            + "by a top-level def statement"),
                    mapEach.getLocation()
                )
            }
        }
        substitutions.add(
            LazySubstitution(
                key,
                thread.getSemantics(),
                valuesSet,
                mapEach,
                uniquify,
                joinWith,
                if (formatJoined !== net.starlark.java.eval.Starlark.NONE) formatJoined as String? else null
            )
        )
        return this
    }

    val all: Iterable<out Substitution>
        get() = substitutions

    private class LazySubstitution(
        key: String?,
        semantics: net.starlark.java.eval.StarlarkSemantics?,
        valuesSet: Depset,
        mapEach: net.starlark.java.eval.StarlarkCallable?,
        uniquify: Boolean,
        joinWith: String,
        formatJoined: String?
    ) : ComputedSubstitution(key) {
        private val semantics: net.starlark.java.eval.StarlarkSemantics?
        private val valuesSet: Depset
        private val mapEach: net.starlark.java.eval.StarlarkCallable?
        private val uniquify: Boolean
        private val joinWith: String
        private val formatJoined: String?

        init {
            this.semantics = semantics
            this.valuesSet = valuesSet
            this.mapEach = mapEach
            this.uniquify = uniquify
            this.joinWith = joinWith
            this.formatJoined = formatJoined
        }

        @get:Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        val value: String?
            get() {
                net.starlark.java.eval.Mutability.create("expand_template").use { mutability ->
                    val execThread: net.starlark.java.eval.StarlarkThread? =
                        net.starlark.java.eval.StarlarkThread.create(
                            mutability,
                            semantics,
                            "map_each callback",  // The map_each callback should not create any persistent state beyond the returned
                            // String value.
                            net.starlark.java.eval.SymbolGenerator.createTransient()
                        )
                    val values: com.google.common.collect.ImmutableList<*> = valuesSet.toList()
                    var parts: MutableList<String?> =
                        java.util.ArrayList<String?>(values.size)
                    for (`val` in values) {
                        val ret: Any? =
                            net.starlark.java.eval.Starlark.positionalOnlyCall(execThread, mapEach, `val`)
                        if (ret is String) {
                            parts.add(ret)
                        } else if (ret is net.starlark.java.eval.Sequence<*>) {
                            for (v in ret) {
                                if (v !is String) {
                                    throw net.starlark.java.eval.Starlark.errorf(
                                        ("Function provided to map_each must return string, None, or list of strings,"
                                                + " but returned list containing element '%s' of type %s for key '%s' and"
                                                + " value: %s"),
                                        v, net.starlark.java.eval.Starlark.type(v), getKey(), `val`
                                    )
                                }
                                parts.add(v)
                            }
                        } else if (ret !== net.starlark.java.eval.Starlark.NONE) {
                            throw net.starlark.java.eval.Starlark.errorf(
                                "Function provided to map_each must return string, None, or list of strings, but "
                                        + "returned type %s for key '%s' and value: %s",
                                net.starlark.java.eval.Starlark.type(ret), getKey(), `val`
                            )
                        }
                    }
                    if (uniquify) {
                        // Stably deduplicate parts in-place.
                        val count = parts.size
                        val seen: HashSet<String?> =
                            com.google.common.collect.Sets.newHashSetWithExpectedSize<String?>(count)
                        var addIndex = 0
                        for (i in 0..<count) {
                            val `val` = parts.get(i)
                            if (seen.add(`val`)) {
                                parts.set(addIndex++, `val`)
                            }
                        }
                        parts = parts.subList(0, addIndex)
                    }
                    val joined: String = com.google.common.base.Joiner.on(joinWith).join(parts)
                    if (formatJoined != null) {
                        return net.starlark.java.eval.Starlark.format(semantics, formatJoined, joined)
                    }
                    return joined
                }
            }
    }

    companion object {
        fun newDict(): TemplateDictApi {
            return TemplateDict()
        }
    }
}
