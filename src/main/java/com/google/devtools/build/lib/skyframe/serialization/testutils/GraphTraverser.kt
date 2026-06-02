// Copyright 2024 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache
import com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector

/** Traverses an object graph, feeding information into a given [GraphDataCollector].  */
internal class GraphTraverser<SinkT : com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Sink?>(
    registry: ObjectCodecRegistry?,
    collector: GraphDataCollector<SinkT?>
) {
    private val registry: ObjectCodecRegistry?
    private val collector: GraphDataCollector<SinkT?>

    init {
        this.registry = registry
        this.collector = collector
    }

    fun traverseObject(label: String?, obj: Any?, sink: SinkT?) {
        if (obj == null) {
            collector.outputNull(label, sink)
            return
        }

        val type: java.lang.Class<*> = obj.getClass()

        if (registry != null) {
            val maybeConstantTag: Int? = registry.maybeGetTagForConstant(obj)
            if (maybeConstantTag != null) {
                collector.outputSerializationConstant(label, type, maybeConstantTag, sink)
                return
            }
        }

        if (java.lang.ref.WeakReference::class.java.isAssignableFrom(type)) {
            collector.outputWeakReference(label, sink)
            return
        }

        if (Dumper.Companion.shouldInline(type)) {
            collector.outputInlineObject(label, type, obj, sink)
            return
        }

        val descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor? =
            collector.checkCache(label, type, obj, sink)
        if (descriptor == null) {
            return  // cache hit
        }

        if (type.isArray()) {
            traverseArray(label, descriptor, type, obj, sink)
            return
        }

        if (obj is MutableMap<*, *>) {
            traverseMapEntries(label, descriptor, obj, sink)
            return
        }

        if (obj is MutableCollection<*>) {
            traverseCollectionElements(label, descriptor, obj, sink)
            return
        }

        traverseObjectFields(label, descriptor, type, obj, sink)
    }

    private fun traverseArray(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor?,
        type: java.lang.Class<*>,
        obj: Any?,
        sink: SinkT?
    ) {
        val componentType: java.lang.Class<*> = type.getComponentType()
        if (componentType == Byte::class.javaPrimitiveType) {
            collector.outputByteArray(label, descriptor, obj as ByteArray?, sink)
            return
        }

        // In theory, there could be special casing WeakReferences here, to match the handling in
        // `traverseObject`. However, since Java does not support generic arrays we don't expect to
        // encounter an array of WeakReferences.
        if (Dumper.Companion.shouldInline(componentType)) {
            collector.outputInlineArray(label, descriptor, obj, sink)
            return
        }

        val arr = obj as Array<Any?>
        if (arr.length == 0) {
            collector.outputEmptyAggregate(label, descriptor, obj, sink)
            return
        }

        val subSink: SinkT? = collector.initAggregate(label, descriptor, obj, sink)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        subSink.completeAggregate()
    }

    private fun traverseMapEntries(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor?,
        map: MutableMap<*, *>,
        sink: SinkT?
    ) {
        if (map.isEmpty()) {
            collector.outputEmptyAggregate(label, descriptor, map, sink)
            return
        }

        val subSink: SinkT? = collector.initAggregate(label, descriptor, map, sink)
        for (entry in map.entrySet()) {
            traverseObject( /* label= */"key=", entry.getKey(), subSink)
            traverseObject( /* label= */"value=", entry.getValue(), subSink)
        }
        subSink.completeAggregate()
    }

    private fun traverseCollectionElements(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor?,
        collection: MutableCollection<*>,
        sink: SinkT?
    ) {
        if (collection.isEmpty()) {
            collector.outputEmptyAggregate(label, descriptor, collection, sink)
            return
        }

        val subSink: SinkT? = collector.initAggregate(label, descriptor, collection, sink)
        for (next in collection) {
            traverseObject( /* label= */null, next, subSink)
        }
        subSink.completeAggregate()
    }

    private fun traverseObjectFields(
        label: String?,
        descriptor: com.google.devtools.build.lib.skyframe.serialization.testutils.GraphDataCollector.Descriptor?,
        type: java.lang.Class<*>?,
        obj: Any?,
        sink: SinkT?
    ) {
        val fieldInfo: com.google.common.collect.ImmutableList<FieldInfoCache.FieldInfo> =
            FieldInfoCache.getFieldInfo(type)
        if (fieldInfo.isEmpty()) {
            collector.outputEmptyAggregate(label, descriptor, obj, sink)
            return
        }

        val subSink: SinkT? = collector.initAggregate(label, descriptor, obj, sink)
        for (info in fieldInfo) {
            when (info) {
                -> collector.outputPrimitive(primitiveInfo, obj, subSink)
                -> traverseObject(objectInfo.name() + "=", objectInfo.getFieldValue(obj), subSink)
            }
        }
        subSink.completeAggregate()
    }
}
