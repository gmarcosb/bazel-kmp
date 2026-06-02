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
package com.google.devtools.build.lib.starlarkdebug.server

import com.google.common.collect.ImmutableList
import com.google.common.collect.Ordering
import net.starlark.java.eval.*
import java.lang.reflect.Array

/** Helper class for creating [StarlarkDebuggingProtos.Value] from Starlark objects.  */
internal object DebuggerSerialization {
    @kotlin.jvm.JvmStatic
    fun getValueProto(objectMap: ThreadObjectMap, label: String?, value: Any): Value {
        // TODO(bazel-team): prune cycles, and provide a way to limit breadth/depth of children reported
        val hasChildren = hasChildren(value)
        return Value.newBuilder()
            .setLabel(label) // TODO(bazel-team): omit type details for non-Starlark values
            .setType(Starlark.type(value))
            .setDescription(getDescription(value))
            .setHasChildren(hasChildren)
            .setId(if (hasChildren) objectMap.registerValue(value) else 0)
            .build()
    }

    private fun getDescription(value: Any?): String {
        if (value is String) {
            return value
        }
        return Starlark.repr(value, StarlarkSemantics.DEFAULT)
    }

    private fun hasChildren(value: Any): Boolean {
        if (value is MutableMap<*, *>) {
            return !value.isEmpty()
        }
        if (value is MutableMap.MutableEntry<*, *>) {
            return true
        }
        if (value is Iterable<*>) {
            return value.iterator().hasNext()
        }
        if (value.javaClass.isArray()) {
            return Array.getLength(value) > 0
        }
        if (value is Debug.ValueWithDebugAttributes) {
            return true
        }
        if (value is StarlarkInt) {
            return false
        }
        if (value is Structure || value is StarlarkValue) {
            // assuming Structure's have at least one child as a temporary optimization
            // TODO(bazel-team): remove once child-listing logic is moved to StarlarkValue
            return true
        }
        // fallback to assuming there are no children
        return false
    }

    @kotlin.jvm.JvmStatic
    fun getChildren(objectMap: ThreadObjectMap, value: Any): ImmutableList<Value?> {
        if (value is MutableMap<*, *>) {
            return DebuggerSerialization.getChildren(objectMap, value.entries)
        }
        if (value is MutableMap.MutableEntry<*, *>) {
            return DebuggerSerialization.getChildren(objectMap, value)
        }
        if (value is Iterable<*>) {
            return DebuggerSerialization.getChildren(objectMap, value)
        }
        if (value.javaClass.isArray()) {
            return getArrayChildren(objectMap, value)
        }
        if (value is Debug.ValueWithDebugAttributes) {
            return getDebugAttributes(objectMap, value)
        }
        // TODO(bazel-team): move child-listing logic to StarlarkValue where practical
        if (value is Structure) {
            return DebuggerSerialization.getChildren(objectMap, value)
        }
        if (value is StarlarkValue) {
            return DebuggerSerialization.getChildren(objectMap, value)
        }
        // fallback to assuming there are no children
        return ImmutableList.of<Value?>()
    }

    private fun getChildren(
        objectMap: ThreadObjectMap, classObject: Structure
    ): ImmutableList<Value?> {
        val builder: ImmutableList.Builder<Value?> = ImmutableList.builder<Value?>()
        for (key in Ordering.natural<Comparable<*>?>().immutableSortedCopy<String?>(classObject.getFieldNames())) {
            try {
                val value = classObject.getValue(key)
                if (value != null) {
                    builder.add(getValueProto(objectMap, key, value))
                }
            } catch (e: EvalException) {
                // silently ignore errors
            } catch (e: IllegalArgumentException) {
            }
        }
        return builder.build()
    }

    private fun getChildren(
        objectMap: ThreadObjectMap, starlarkValue: StarlarkValue?
    ): ImmutableList<Value?> {
        val semantics = StarlarkSemantics.DEFAULT // TODO(adonovan): obtain from thread.
        val fieldNames: StarlarkList<String?>
        try {
            fieldNames = Starlark.dir(Mutability.IMMUTABLE, semantics, starlarkValue)
        } catch (e: IllegalArgumentException) {
            // silently return no children
            return ImmutableList.of<Value?>()
        }
        val children: ImmutableList.Builder<Value?> = ImmutableList.builder<Value?>()
        for (fieldName in fieldNames) {
            try {
                children.add(
                    getValueProto(
                        objectMap,
                        fieldName,
                        Starlark.getattr(Mutability.IMMUTABLE, semantics, starlarkValue, fieldName, null)
                    )
                )
            } catch (e: EvalException) {
                // silently ignore errors
            } catch (e: InterruptedException) {
            } catch (e: IllegalArgumentException) {
            }
        }
        return children.build()
    }

    private fun getChildren(
        objectMap: ThreadObjectMap, entry: MutableMap.MutableEntry<*, *>
    ): ImmutableList<Value?> {
        return ImmutableList.of<Value?>(
            DebuggerSerialization.getValueProto(objectMap, "key", entry.key!!),
            DebuggerSerialization.getValueProto(objectMap, "value", entry.value!!)
        )
    }

    private fun getChildren(objectMap: ThreadObjectMap, iterable: Iterable<*>): ImmutableList<Value?> {
        val builder: ImmutableList.Builder<Value?> = ImmutableList.builder<Value?>()
        var index = 0
        for (value in iterable) {
            builder.add(DebuggerSerialization.getValueProto(objectMap, String.format("[%d]", index++), value!!))
        }
        return builder.build()
    }

    private fun getDebugAttributes(
        objectMap: ThreadObjectMap, value: Debug.ValueWithDebugAttributes
    ): ImmutableList<Value?> {
        val attributes: ImmutableList.Builder<Value?> = ImmutableList.builder<Value?>()
        for (attr in value.getDebugAttributes()) {
            attributes.add(getValueProto(objectMap, attr.name, attr.value))
        }
        return attributes.build()
    }

    private fun getArrayChildren(objectMap: ThreadObjectMap, array: Any?): ImmutableList<Value?> {
        val builder: ImmutableList.Builder<Value?> = ImmutableList.builder<Value?>()
        var index = 0
        for (i in 0..<Array.getLength(array)) {
            builder.add(getValueProto(objectMap, String.format("[%d]", index++), Array.get(array, i)))
        }
        return builder.build()
    }
}
