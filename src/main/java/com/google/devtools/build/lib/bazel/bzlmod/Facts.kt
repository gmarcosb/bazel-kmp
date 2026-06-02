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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import java.util.TreeMap

/**
 * A container for user-provided JSON-like data attached to a module extension that is persisted
 * across reevaluations of the extension.
 */
@AutoValue
@AutoCodec
@net.starlark.java.annot.StarlarkBuiltin(
    name = "Facts", doc = """
        User-provided data attached to a module extension that is persisted across reevaluations of
        the extension.

        This type supports dict-like access (e.g. `facts["key"]` and `facts.get("key")`) as well as
        membership tests (e.g. `"key" in facts`). It does not support iteration or methods like
        `keys()`, `items()`, or `len()`.
        
        """.trimIndent(), category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN
)
abstract class Facts : net.starlark.java.eval.StarlarkIndexable {
    abstract fun value(): net.starlark.java.eval.Dict<String?, Any?>?

    override fun getIndex(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Any? {
        return value().get(key)
    }

    override fun containsKey(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any?): Boolean {
        return value().containsKey(key)
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "get",
        doc = "Returns the value for <code>key</code> if it exists, or <code>default</code>.",
        parameters = [net.starlark.java.annot.Param(
            name = "key",
            doc = "The key to look up.",
            named = true
        ), net.starlark.java.annot.Param(
            name = "default",
            doc = "The value to return if <code>key</code> is not present.",
            named = true,
            defaultValue = "None"
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun get(key: String?, defaultValue: Any?): Any? {
        return value().getOrDefault(key, defaultValue)
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        // Don't leak the contents to Starlark.
        printer.append("Facts(<opaque, inspect with print()>)")
    }

    override fun debugPrint(printer: net.starlark.java.eval.Printer, thread: net.starlark.java.eval.StarlarkThread) {
        // Print the contents for debugging purposes.
        printer.append("Facts(")
        value().repr(printer, thread.getSemantics())
        printer.append(")")
    }

    val isImmutable: Boolean
        get() = true

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun checkHashable() {
        throw net.starlark.java.eval.Starlark.errorf(
            "unhashable type: '%s'",
            net.starlark.java.eval.Starlark.type(this)
        )
    }

    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.StarlarkType? {
        return net.starlark.java.syntax.Types.mapping(
            net.starlark.java.syntax.Types.STR,
            net.starlark.java.syntax.Types.ANY
        )
    }

    companion object {
        val EMPTY: Facts = AutoValue_Facts(net.starlark.java.eval.Dict.empty<K?, V?>())

        @Throws(net.starlark.java.eval.EvalException::class)
        fun validateAndCreate(value: Any?): Facts {
            return AutoValue_Facts(
                validateAndNormalize(
                    net.starlark.java.eval.Dict.cast<String?, Any?>(
                        value,
                        String::class.java,
                        Any::class.java,
                        "facts"
                    )
                )
            )
        }

        @AutoCodec.Instantiator
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        fun createUnchecked(value: net.starlark.java.eval.Dict<String?, Any?>?): Facts {
            return AutoValue_Facts(value)
        }

        // This limit only exists to prevent pathological uses of facts, which are meant to be
        // human-readable and friendly to VCS merges.
        private const val MAX_FACTS_DEPTH = 7

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun validateAndNormalize(facts: net.starlark.java.eval.Dict<String?, Any?>?): net.starlark.java.eval.Dict<String?, Any?>? {
            return validateAndNormalize(facts, MAX_FACTS_DEPTH) as net.starlark.java.eval.Dict<String?, Any?>?
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun validateAndNormalize(facts: Any?, remainingDepth: Int): Any? {
            if (remainingDepth < 0) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "Facts cannot be nested more than %s levels deep",
                    MAX_FACTS_DEPTH
                )
            }
            // Only permit types that can be serialized to JSON and ensure that they contain no information
            // not captured by equality by sorting dicts.
            return when (facts) {
                -> s
                -> n
                -> b
                -> f
                -> i
                -> {
                    val normalizedList = arrayOfNulls<Any>(list.size())
                    /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                    net.starlark.java.eval.StarlarkList.immutableOf<Any?>(*normalizedList)
                }

                -> {
                    // Turn a tuple into a list since JSON does not have a tuple type.
                    val normalizedList = arrayOfNulls<Any>(tuple.size())
                    /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
                    net.starlark.java.eval.StarlarkList.immutableOf<Any?>(*normalizedList)
                }

                -> {
                    val builder: TreeMap<String?, Any?> = TreeMap<String?, Any?>()
                    for (entry in dict.entrySet()) {
                        if (entry.getKey() !is String) {
                            throw net.starlark.java.eval.Starlark.errorf(
                                "Facts keys must be strings, got '%s' (%s)",
                                net.starlark.java.eval.Starlark.repr(
                                    entry,
                                    net.starlark.java.eval.StarlarkSemantics.DEFAULT
                                ), net.starlark.java.eval.Starlark.type(entry.getKey())
                            )
                        }
                        builder.put(string, validateAndNormalize(entry.getValue(), remainingDepth - 1))
                    }
                    net.starlark.java.eval.Dict.immutableCopyOf<String?, Any?>(builder)
                }

                else -> throw net.starlark.java.eval.Starlark.errorf(
                    "'%s' (%s) is not supported in facts",
                    net.starlark.java.eval.Starlark.repr(facts, net.starlark.java.eval.StarlarkSemantics.DEFAULT),
                    net.starlark.java.eval.Starlark.type(facts)
                )
            }
        }
    }
}
