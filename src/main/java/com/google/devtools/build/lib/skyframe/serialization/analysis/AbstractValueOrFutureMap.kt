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
import java.util.concurrent.ConcurrentMap


/**
 * A concurrent map of recursively populated futures or values.
 * 
 * 
 * This map achieves two goals in conjunction with the [SettableFutureKeyedValue].
 * 
 * 
 *  * It ensures that for each key, the future is created and populated only once (assuming that
 * the entry remains cached).
 *  * When the future succeeds, the future is unwrapped and only the value is retained.
 * 
 * 
 * 
 * In the case of an error, the future is retained, according to the [.map] implementation.
 * 
 * 
 * `FutureT` must be a subclass of `ValueOrFutureT`. Unfortunately, this constraint
 * cannot be expressed using Java generics in conjunction with the constraint that `FutureT`
 * extends [SettableFutureKeyedValue]. It is the caller's responsibility to ensure this
 * relationship holds.
 */
internal abstract class AbstractValueOrFutureMap<KeyT, ValueOrFutureT, ValueT : ValueOrFutureT?, FutureT : SettableFutureKeyedValue<FutureT?, KeyT?, ValueT?>?>
    (
    map: ConcurrentMap<KeyT?, ValueOrFutureT?>,
    futureOrValueFactory: java.util.function.BiFunction<KeyT?, java.util.function.BiConsumer<KeyT?, ValueT?>?, ValueOrFutureT?>,
    futureType: java.lang.Class<FutureT?>?
) : java.util.function.BiConsumer<KeyT?, ValueT?> {
    private val map: ConcurrentMap<KeyT?, ValueOrFutureT?>
    private val futureValueFactory: ValueOrFutureFactory
    private val futureType: java.lang.Class<FutureT?>?

    /**
     * Constructor.
     * 
     * @param futureValueFactory creates appropriate instances of [SettableFutureKeyedValue].
     * The key and consumer parameters are provided for use in [SettableFutureKeyedValue]'s
     * constructor.
     */
    init {
        this.map = map
        this.futureValueFactory = ValueOrFutureFactory(futureOrValueFactory)
        this.futureType = futureType
    }

    fun futureType(): java.lang.Class<FutureT?>? {
        return futureType
    }

    fun getOrCreateValueForSubclasses(key: KeyT?): ValueOrFutureT? {
        return map.computeIfAbsent(key, futureValueFactory)
    }

    @Deprecated("only used by {@link FutureValueFactory#apply}")
    override fun accept(key: KeyT?, value: ValueT?) {
        map.put(key, value)
    }

    private inner class ValueOrFutureFactory(futureOrValueFactory: java.util.function.BiFunction<KeyT?, java.util.function.BiConsumer<KeyT?, ValueT?>?, ValueOrFutureT?>) :
        java.util.function.Function<KeyT?, ValueOrFutureT?> {
        private val futureOrValueFactory: java.util.function.BiFunction<KeyT?, java.util.function.BiConsumer<KeyT?, ValueT?>?, ValueOrFutureT?>

        init {
            this.futureOrValueFactory = futureOrValueFactory
        }

        override fun apply(key: KeyT?): ValueOrFutureT? {
            return futureOrValueFactory.apply(key, this@AbstractValueOrFutureMap)
        }
    }
}
