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

import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.PrimitiveInfo

/**
 * Collects data from an object graph driven by [GraphTraverser].
 * 
 * 
 * The `label` parameter, common to many of the methods here, is a label that the parent
 * uses for a child object. For example, if the parent is an ordinary object and the child is one of
 * its members, the label is the field name. If the parent is a map, the label might be "key" or
 * "value".
 */
internal interface GraphDataCollector<SinkT : GraphDataCollector.Sink?> {
    /**
     * Receiver for child data, scoped to a particular parent.
     * 
     * 
     * The accept methods are specific to the policy implementation.
     */
    interface Sink {
        /** Called once all children have been traversed.  */
        fun completeAggregate()
    }

    fun outputNull(label: String?, sink: SinkT?)

    fun outputSerializationConstant(label: String?, type: java.lang.Class<*>?, tag: Int, sink: SinkT?)

    fun outputWeakReference(label: String?, sink: SinkT?)

    fun outputInlineObject(label: String?, type: java.lang.Class<*>?, obj: Any?, sink: SinkT?)

    fun outputPrimitive(info: PrimitiveInfo?, parent: Any?, sink: SinkT?)

    /**
     * Metadata about an object.
     * 
     * @param description describes the type of the object
     * @param traversalIndex the index at which this object was first encountered during traversal
     */
    @kotlin.jvm.JvmRecord
    data class Descriptor(val description: String?, val traversalIndex: Int) {
        override fun toString(): String {
            return description + "(" + traversalIndex + ")"
        }
    }

    /**
     * Checks whether `obj` already exists in the cache.
     * 
     * 
     * If it exists in the cache, populates `sink` appropriately and returns null. Otherwise,
     * returns a descriptor to be used (that does not include `label`).
     */
    fun checkCache(label: String?, type: java.lang.Class<*>?, obj: Any?, sink: SinkT?): Descriptor?

    fun outputByteArray(label: String?, descriptor: Descriptor?, bytes: ByteArray?, sink: SinkT?)

    /**
     * Outputs an array of elements of inlined type.
     * 
     * 
     * `arr` could be an array of primitives, which cannot be cast to `Object[]`.
     */
    fun outputInlineArray(label: String?, descriptor: Descriptor?, arr: Any?, sink: SinkT?)

    fun outputEmptyAggregate(label: String?, descriptor: Descriptor?, obj: Any?, sink: SinkT?)

    /**
     * Non-empty maps, collections, arrays and ordinary objects are handled using this method.
     * 
     * 
     * Initializes the output of the aggregate, `obj`. The returned [SinkT] should be
     * used for output of `obj`'s children. [SinkT.completeAggregate] must be called after
     * these children are complete.
     * 
     * @param descriptor a description of `obj`, for example, a text description of its type
     * @param sink where to write the aggregate
     */
    fun initAggregate(label: String?, descriptor: Descriptor?, obj: Any?, sink: SinkT?): SinkT?
}
