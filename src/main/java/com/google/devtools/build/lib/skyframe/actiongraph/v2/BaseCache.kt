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
package com.google.devtools.build.lib.skyframe.actiongraph.v2

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Basic class to abstract action graph cache functionality.
 */
internal abstract class BaseCache<K, P>(protected val aqueryOutputHandler: AqueryOutputHandler?) {
    private val cache: MutableMap<K?, Int?> = ConcurrentHashMap<K?, Int?>()

    // protobuf interprets the value 0 as "default value" for uint64, thus treating the field as
    // "unset". We should start from 1 instead.
    private val nextId = AtomicInteger(1)

    protected open fun transformToKey(data: K?): K? {
        // In most cases, the data is the key but it can be overridden by subclasses.
        return data
    }

    /**
     * Store the data in the internal cache, if it's not yet present. Return the generated id. Ids are
     * positive and unique.
     * 
     * 
     * Stream the proto to output, the first time it's generated.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun dataToIdAndStreamOutputProto(data: K?): Int {
        var id = -1
        val key = transformToKey(data)
        var shouldOutputProto = false

        // Double-checked locking here:
        // Once cache.get(key) != null it won't be changed again.
        if (cache.get(key) == null) {
            synchronized(this) {
                if (cache.get(key) == null) {
                    id = nextId.getAndIncrement()
                    // Note that this cannot be replaced by computeIfAbsent since createProto is a recursive
                    // operation for the case of nested sets which will call dataToId on the same object and
                    // thus computeIfAbsent again.
                    cache.put(key, id)
                    shouldOutputProto = true
                }
            }
        }
        if (shouldOutputProto) {
            val proto = createProto(data, id)
            toOutput(proto)
        }
        return cache.get(key)!!
    }

    @Throws(IOException::class, InterruptedException::class)
    abstract fun createProto(key: K?, id: Int): P?

    @Throws(IOException::class)
    abstract fun toOutput(proto: P?)
}
