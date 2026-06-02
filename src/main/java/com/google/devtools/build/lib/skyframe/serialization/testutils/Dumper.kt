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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.testutils.Canonizer
import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.TextSink
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.ClosedClassInfo
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.PrimitiveInfo
import com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector
import com.google.devtools.build.lib.skyframe.serialization.testutils.GraphTraverser
import java.util.HexFormat
import java.util.IdentityHashMap

/**
 * A utility for creating high fidelity string dumps of arbitrary objects.
 * 
 * 
 * Uses reflection to perform depth-first traversal of arbitrary objects and formats them as an
 * indented, multiline string.
 * 
 * 
 * This class exists mainly to help test and debug serialization. Consequently, it skips `transient` fields. It also performs reference-based memoization to handle cyclic structures or
 * structures that would have an exponential path structure, for example, `NestedSets`.
 * 
 * 
 * This class also supports value-based deduplication when calling [ ][.dumpStructureWithEquivalenceReduction]. Instead of using (only) using references for
 * deduplication, uses object identifiers computed by [Canonizer] for deduplication.
 */
class Dumper private constructor(canonicalIdentifiers: IdentityHashMap<Any?, *>?) : GraphDataCollector<TextSink?> {
    /**
     * Canonical identifiers for references.
     * 
     * 
     * Even if this is present, not all references will have canonical identifiers. In particular,
     * anything where [Dumper.shouldInline] is true will not have identifiers.
     */
    // optional behavior
    private val canonicalIdentifiers: IdentityHashMap<Any?, *>?

    /**
     * Stores the index at which each object is traversed.
     * 
     * 
     * When an object is encountered again, it is represented with just its type and previous index
     * instead of being fully expanded.
     */
    private val traversalIndex: IdentityHashMap<Any?, Int?> = IdentityHashMap<Any?, Int?>()

    internal class TextSink(out: java.lang.StringBuilder) :
        com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Sink {
        private val out: java.lang.StringBuilder
        private var indent = 0
        var isFirst: Boolean = true

        init {
            this.out = out
        }

        override fun completeAggregate() {
            deindent()
            emitNewlineAndIndent()
            out.append("]")
        }

        private fun output(label: String?, text: String?) {
            emitNewlineAndIndent()
            if (label != null) {
                out.append(label)
            }
            out.append(text)
        }

        private fun indent() {
            indent += SPACES_PER_INDENT
        }

        private fun deindent() {
            indent -= SPACES_PER_INDENT
        }

        private fun emitNewlineAndIndent() {
            if (isFirst) {
                isFirst = false // suppresses the first newline
                return
            }
            out.append("\n").append(" ".repeat(indent))
        }

        companion object {
            private const val SPACES_PER_INDENT = 2
        }
    }

    override fun outputNull(label: String?, sink: TextSink) {
        sink.output(label, "null")
    }

    override fun outputSerializationConstant(
        label: String?, type: java.lang.Class<*>, tag: Int, sink: TextSink
    ) {
        sink.output(label, getTypeName(type) + "[SERIALIZATION_CONSTANT:" + tag + ']')
    }

    override fun outputWeakReference(label: String?, sink: TextSink) {
        sink.output(label, java.lang.ref.WeakReference::class.java.getCanonicalName())
    }

    override fun outputInlineObject(label: String?, type: java.lang.Class<*>?, obj: Any, sink: TextSink) {
        sink.output(label, obj.toString())
    }

    override fun outputPrimitive(info: PrimitiveInfo, parent: Any?, sink: TextSink) {
        sink.output(info.name() + '=', info.getText(parent))
    }

    override fun checkCache(
        label: String?,
        type: java.lang.Class<*>,
        obj: Any?,
        sink: TextSink
    ): com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor? {
        val nextId: Int = traversalIndex.size()
        val identifier: Any?
        if (canonicalIdentifiers != null && ((canonicalIdentifiers.get(obj).also { identifier = it }) != null)) {
            // There's a identifier for `obj`. Uses it to lookup a reference ID.
            val previousIndex: Int? = traversalIndex.putIfAbsent(identifier, nextId)
            if (previousIndex != null) {
                // An object having this identifier has been observed previously. Outputs only a
                // backreference.
                sink.output(label, getDescriptor(type, previousIndex).toString())
                return null
            }
        } else {
            // No identifier is available. Deduplicates by object reference.
            val previousIndex: Int? = traversalIndex.putIfAbsent(obj, nextId)
            if (previousIndex != null) {
                // This instance has been observed previously. Outputs only a backreference.
                sink.output(label, getDescriptor(type, previousIndex).toString())
                return null
            }
        }
        return getDescriptor(type, nextId)
    }

    override fun outputByteArray(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor?,
        bytes: ByteArray,
        sink: TextSink
    ) {
        sink.output(label, descriptor.toString() + " [" + HEX_FORMAT.formatHex(bytes) + ']')
    }

    override fun outputInlineArray(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor,
        arr: Any?,
        sink: TextSink
    ) {
        val builder: java.lang.StringBuilder = java.lang.StringBuilder(descriptor.toString()).append(" [")
        val length: Int = java.lang.reflect.Array.getLength(arr)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        builder.append(']')
        sink.output(label, builder.toString())
    }

    override fun outputEmptyAggregate(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor?,
        unused: Any?,
        sink: TextSink
    ) {
        sink.output(label, descriptor.toString() + " []")
    }

    override fun initAggregate(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor?,
        unused: Any?,
        sink: TextSink
    ): TextSink {
        sink.output(label, descriptor.toString() + " [")
        sink.indent()
        return sink
    }

    init {
        this.canonicalIdentifiers = canonicalIdentifiers
    }

    companion object {
        private val HEX_FORMAT: HexFormat = HexFormat.of().withUpperCase()

        /**
         * Formats an arbitrary object into a string.
         * 
         * 
         * The format is verbose and suitable for tests and debugging.
         * 
         * @return a multiline String representation of `obj` without a trailing newline
         */
        fun dumpStructure(registry: ObjectCodecRegistry?, obj: Any?): String {
            return dumpStructure(registry,  /* canonicalIdentifiers= */null, obj)
        }

        fun dumpStructure(obj: Any?): String {
            return dumpStructure( /* registry= */null, obj)
        }

        @kotlin.jvm.JvmStatic
        private fun dumpStructure(
            registry: ObjectCodecRegistry?,
            canonicalIdentifiers: IdentityHashMap<Any?, *>?,
            obj: Any?
        ): String {
            val out: java.lang.StringBuilder = java.lang.StringBuilder()
            val sink = TextSink(out)
            GraphTraverser<TextSink?>(registry, Dumper(canonicalIdentifiers))
                .traverseObject( /* label= */null, obj, sink)
            return out.toString()
        }

        /**
         * Formats an arbitrary object into a string.
         * 
         * 
         * Similar to [.dumpStructure] but applies identifier-based deduplication.
         */
        fun dumpStructureWithEquivalenceReduction(
            registry: ObjectCodecRegistry?, obj: Any?
        ): String {
            return dumpStructure(registry, Canonizer.Companion.computeIdentifiers(registry, obj), obj)
        }

        @kotlin.jvm.JvmStatic
        fun dumpStructureWithEquivalenceReduction(obj: Any?): String {
            return dumpStructureWithEquivalenceReduction( /* registry= */null, obj)
        }

        fun getTypeName(type: java.lang.Class<*>): String? {
            if (com.google.common.collect.ImmutableList::class.java.isAssignableFrom(type)) {
                return com.google.common.collect.ImmutableList::class.java.getCanonicalName()
            }
            if (com.google.common.collect.ImmutableSortedSet::class.java.isAssignableFrom(type)) {
                return com.google.common.collect.ImmutableSortedSet::class.java.getCanonicalName()
            }
            if (com.google.common.collect.ImmutableSet::class.java.isAssignableFrom(type)) {
                return com.google.common.collect.ImmutableSet::class.java.getCanonicalName()
            }
            var name: String? = type.getCanonicalName()
            if (name == null) {
                // According to the documentation for `Class.getCanonicalName`, not all classes have one.
                // Falls back on the name in such cases. (It's unclear if this code is reachable because
                // synthetic types are inlined).
                name = type.getName()
            }
            return name
        }

        fun shouldInline(type: java.lang.Class<*>): Boolean {
            if (type.isArray()) {
                return false
            }
            if (MutableCollection::class.java.isAssignableFrom(type) || MutableMap::class.java.isAssignableFrom(type)) {
                // These types have custom handling and do not depend on reflective class information.
                return false
            }
            return type.isPrimitive()
                    || DIRECT_INLINE_TYPES.contains(type)
                    || type.isSynthetic() // Enums have a lazily initialized hashCode that can cause nondeterminism. Their inline
                    // representations are sufficient.
                    || type.isEnum() // Reflectively inaccessible classes will be represented directly using their string
                    // representations as there's nothing else we can do with them.
                    //
                    // TODO: b/331765692 - this might cause a loss of fidelity. Consider including a hash of
                    // the serialized representation in such cases.
                    || FieldInfoCache.getClassInfo(type) is ClosedClassInfo
        }

        private val WRAPPER_TYPES: com.google.common.collect.ImmutableSet<java.lang.Class<*>?> =
            com.google.common.collect.ImmutableSet.of<java.lang.Class<*>?>(
                Byte::class.java,
                Short::class.java,
                Int::class.java,
                Long::class.java,
                Float::class.java,
                Double::class.java,
                Boolean::class.java,
                Char::class.java
            )

        private val DIRECT_INLINE_TYPES: com.google.common.collect.ImmutableSet<java.lang.Class<*>?> =
            com.google.common.collect.ImmutableSet.builder<java.lang.Class<*>?>()
                .addAll(WRAPPER_TYPES) // Treats Strings as values for readability of the output. It might be good to make this
                // configurable later on.
                .add(String::class.java) // The string representation of a Class is sufficient to identify it.
                .add(java.lang.Class::class.java)
                .build()

        private fun getDescriptor(
            type: java.lang.Class<*>,
            id: Int
        ): com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor {
            return com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor(
                getTypeName(type), id
            )
        }
    }
}
