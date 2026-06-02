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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue
import com.google.devtools.build.lib.skyframe.serialization.analysis.AbstractValueOrFutureMap
import java.util.concurrent.ConcurrentMap

/**
 * Regular implementation of [AbstractValueOrFutureMap].
 * 
 * 
 * [.getValueOrFuture] only requires a key parameter.
 */
internal class ValueOrFutureMap<KeyT, ValueOrFutureT, ValueT : ValueOrFutureT?, FutureT : SettableFutureKeyedValue<FutureT?, KeyT?, ValueT?>?>
    (
    map: ConcurrentMap<KeyT?, ValueOrFutureT?>?,
    futureOrValueFactory: java.util.function.BiFunction<KeyT?, java.util.function.BiConsumer<KeyT?, ValueT?>?, ValueOrFutureT?>?,
    populator: java.util.function.Function<FutureT?, ValueOrFutureT?>,
    futureType: java.lang.Class<FutureT?>?
) : AbstractValueOrFutureMap<KeyT?, ValueOrFutureT?, ValueT?, FutureT?>(map, futureOrValueFactory, futureType) {
    private val populator: java.util.function.Function<FutureT?, ValueOrFutureT?>

    /**
     * Constructor.
     * 
     * @param populator function completes its provided settable `FutureT` instance and returns
     * a [ValueOrFutureT] instance. If `populator` returns an immediate `ValueT`, it will also be returned immediately by [.getValueOrFuture] instead of the
     * future. However, it's fine for `populator` to return its `FutureT` input.
     * @throws IllegalArgumentException if `FutureT` is not a subclass of [     ]
     */
    init {
        this.populator = populator
    }

    fun getValueOrFuture(key: KeyT?): ValueOrFutureT? {
        val result: ValueOrFutureT? = getOrCreateValueForSubclasses(key)
        if (futureType().isInstance(result)) {
            val future: FutureT? = futureType().cast(result)
            if (future.tryTakeOwnership()) {
                try {
                    return populator.apply(future)
                } finally {
                    future.verifyComplete()
                }
            }
        }
        return result
    }
}
