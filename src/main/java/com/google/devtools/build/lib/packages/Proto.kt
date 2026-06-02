// Copyright 2023 The Bazel Authors. All rights reserved.
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

/** Proto defines the "proto" Starlark module of utilities for protocol message processing.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "proto",
    category = com.google.devtools.build.docgen.annot.DocCategory.TOP_LEVEL_MODULE,
    doc = "A module for protocol message processing."
)
class Proto  // Note: in due course this is likely to move to net.starlark.java.lib.proto.
// Do not add functions that would not belong there!
// Functions related to running the protocol compiler belong in proto_common.
private constructor() : net.starlark.java.eval.StarlarkValue {
    @net.starlark.java.annot.StarlarkMethod(
        name = "encode_text",
        doc = ("Returns the struct argument's encoding as a text-format protocol message.\n"
                + "The data structure must be recursively composed of strings, ints, floats, or"
                + " bools, or structs, sequences, and dicts of these types.\n"
                + "<p>A struct is converted to a message. Fields are emitted in name order.\n"
                + "Each struct field whose value is None is ignored.\n"
                + "<p>A sequence (such as a list or tuple) is converted to a repeated field.\n"
                + "Its elements must not be sequences or dicts.\n"
                + "<p>A dict is converted to a repeated field of messages with fields named 'key' and"
                + " 'value'.\n"
                + "Entries are emitted in iteration (insertion) order.\n"
                + "The dict's keys must be strings or ints, and its values must not be sequences or"
                + " dicts.\n"
                + "Examples:<br><pre class=language-python>proto.encode_text(struct(field=123))\n"
                + "# field: 123\n\n"
                + "proto.encode_text(struct(field=True))\n"
                + "# field: true\n\n"
                + "proto.encode_text(struct(field=[1, 2, 3]))\n"
                + "# field: 1\n"
                + "# field: 2\n"
                + "# field: 3\n\n"
                + "proto.encode_text(struct(field='text', ignored_field=None))\n"
                + "# field: \"text\"\n\n"
                + "proto.encode_text(struct(field=struct(inner_field='text', ignored_field=None)))\n"
                + "# field {\n"
                + "#   inner_field: \"text\"\n"
                + "# }\n\n"
                + "proto.encode_text(struct(field=[struct(inner_field=1), struct(inner_field=2)]))\n"
                + "# field {\n"
                + "#   inner_field: 1\n"
                + "# }\n"
                + "# field {\n"
                + "#   inner_field: 2\n"
                + "# }\n\n"
                + "proto.encode_text(struct(field=struct(inner_field=struct(inner_inner_field='text'))))\n"
                + "# field {\n"
                + "#    inner_field {\n"
                + "#     inner_inner_field: \"text\"\n"
                + "#   }\n"
                + "# }\n\n"
                + "proto.encode_text(struct(foo={4: 3, 2: 1}))\n"
                + "# foo: {\n"
                + "#   key: 4\n"
                + "#   value: 3\n"
                + "# }\n"
                + "# foo: {\n"
                + "#   key: 2\n"
                + "#   value: 1\n"
                + "# }\n"
                + "</pre>"),
        parameters = [net.starlark.java.annot.Param(
            name = "x",
            allowedTypes = [net.starlark.java.annot.ParamType(type = net.starlark.java.eval.Structure::class), net.starlark.java.annot.ParamType(
                type = net.starlark.java.lib.StarlarkEncodable::class
            )]
        )],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun encodeText(x: Any, thread: net.starlark.java.eval.StarlarkThread): String {
        val enc = TextEncoder(thread.getSemantics())
        enc.message(enc.toStructure(x))
        return enc.out.toString()
    }

    private class TextEncoder(semantics: net.starlark.java.eval.StarlarkSemantics?) {
        private val out: java.lang.StringBuilder = java.lang.StringBuilder()
        private var indent = 0
        private val semantics: net.starlark.java.eval.StarlarkSemantics?

        init {
            this.semantics = semantics
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun toStructure(x: Any): net.starlark.java.eval.Structure {
            var x = x
            val originalX = x
            if (x is net.starlark.java.lib.StarlarkEncodable) {
                x = x.objectForEncoding(semantics)
            }
            if (x !is net.starlark.java.eval.Structure) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "invalid message: got %s, want struct",
                    net.starlark.java.eval.Starlark.type(originalX)
                )
            }
            return x
        }

        // Encodes Structure x as a protocol message.
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun message(x: net.starlark.java.eval.Structure) {
            // For determinism, sort fields.
            val fields: MutableList<String> =
                com.google.common.collect.Ordering.natural<Comparable<*>?>()
                    .sortedCopy<String?>(
                        net.starlark.java.eval.Starlark.dir(
                            net.starlark.java.eval.Mutability.IMMUTABLE,
                            net.starlark.java.eval.StarlarkSemantics.DEFAULT,
                            x
                        )
                    )
            for (field in fields) {
                try {
                    val value: Any =
                        net.starlark.java.eval.Starlark.getattr(
                            net.starlark.java.eval.Mutability.IMMUTABLE,
                            net.starlark.java.eval.StarlarkSemantics.DEFAULT,
                            x,
                            field,
                            null
                        )
                    field(field, value)
                } catch (ex: net.starlark.java.eval.EvalException) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "in %s field .%s: %s",
                        net.starlark.java.eval.Starlark.type(x),
                        field,
                        ex.getMessage()
                    )
                }
            }
        }

        // Encodes Structure field (name, v) as a message field
        // (a repeated field, if v is a dict or sequence.)
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun field(name: String, v: Any) {
            var v = v
            if (v is net.starlark.java.lib.StarlarkEncodable) {
                v = v.objectForEncoding(semantics)
            }
            // dict or other mapping?
            if (v is MutableMap<*, *>) {
                for (entry in v.entrySet()) {
                    val key: Any = entry.getKey()
                    if (!(key is String || key is net.starlark.java.eval.StarlarkInt)) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "invalid dict key: got %s, want int or string", net.starlark.java.eval.Starlark.type(key)
                        )
                    }
                    emitLine(name, " {")
                    indent++
                    fieldElement("key", key) // can't fail
                    try {
                        fieldElement("value", entry.getValue())
                    } catch (ex: net.starlark.java.eval.EvalException) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "in value for dict key %s: %s",
                            net.starlark.java.eval.Starlark.repr(key, net.starlark.java.eval.StarlarkSemantics.DEFAULT),
                            ex.getMessage()
                        )
                    }
                    indent--
                    emitLine("}")
                }
                return
            }

            // list or tuple?
            if (v is net.starlark.java.eval.StarlarkIterable<*>) {
                var i = 0
                for (item in v) {
                    try {
                        fieldElement(name, item)
                    } catch (ex: net.starlark.java.eval.EvalException) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "at %s index %d: %s",
                            net.starlark.java.eval.Starlark.type(v),
                            i,
                            ex.getMessage()
                        )
                    }
                    i++
                }
                return
            }

            // non-repeated field
            if (v === net.starlark.java.eval.Starlark.NONE) {
                return
            }
            fieldElement(name, v)
        }

        // Emits field (name, v) as a message field, or one element of a repeated field.
        // v must be an int, float, string, bool, Structure, or a StarlarkEncodable encoding to one of
        // these.
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun fieldElement(name: String, v: Any) {
            var v = v
            if (v is net.starlark.java.lib.StarlarkEncodable) {
                v = v.objectForEncoding(semantics)
            }

            if (v is net.starlark.java.eval.Structure) {
                emitLine(name, " {")
                indent++
                message(v)
                indent--
                emitLine("}")
            } else if (v is String) {
                emitLine(
                    name, ": \"", v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"), "\""
                )
            } else if (v is net.starlark.java.eval.StarlarkInt || v is Boolean) {
                emitLine(name, ": ", v.toString())
            } else if (v is net.starlark.java.eval.StarlarkFloat) {
                var s = v.toString()
                // Encoding to textproto via proto.encode_text requires "inf" for "+inf".
                if (s == "+inf") {
                    s = "inf"
                }
                emitLine(name, ": ", s)
            } else {
                throw net.starlark.java.eval.Starlark.errorf(
                    "got %s, want string, int, float, bool, or struct",
                    net.starlark.java.eval.Starlark.type(v)
                )
            }
        }

        // Emits items on an indented line.
        fun emitLine(vararg items: String?) {
            for (i in 0..<indent) {
                out.append("  ")
            }
            for (item in items) {
                out.append(item)
            }
            out.append('\n')
        }
    }

    companion object {
        val INSTANCE: Proto = Proto()
    }
}
