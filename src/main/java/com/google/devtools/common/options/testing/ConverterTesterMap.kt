// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options.testing

import com.google.common.collect.ForwardingMap
import com.google.common.collect.ImmutableMap
import com.google.devtools.common.options.Converter
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.function.Consumer

/**
 * An immutable mapping from [Converter] classes to [ConverterTester]s which test them.
 * 
 * 
 * Note that the ConverterTesters within are NOT immutable.
 */
class ConverterTesterMap
private constructor(private val delegate: ImmutableMap<Class<out Converter<*>?>?, ConverterTester?>) :
    ForwardingMap<Class<out Converter<*>?>?, ConverterTester?>() {
    override fun delegate(): MutableMap<Class<out Converter<*>?>?, ConverterTester?> {
        return delegate
    }

    /** A builder to construct new [ConverterTesterMap]s.  */
    class Builder {
        private val delegate: ImmutableMap.Builder<Class<out Converter<*>?>?, ConverterTester?>

        init {
            this.delegate = ImmutableMap.builder<Class<out Converter<*>?>?, ConverterTester?>()
        }

        /**
         * Adds a new ConverterTester, mapping it to the class of converter it tests. Only one tester
         * for each class is permitted; duplicates will cause [.build] to fail.
         */
        @CanIgnoreReturnValue
        fun add(item: ConverterTester): Builder {
            delegate.put(item.getConverterClass(), item)
            return this
        }

        /**
         * Adds the entries from the given [ConverterTesterMap]. Only one tester for each class is
         * permitted; duplicates will cause [.build] to fail.
         */
        @CanIgnoreReturnValue
        fun addAll(map: ConverterTesterMap): Builder {
            // this is safe because we know the other map was constructed the same way this one was
            delegate.putAll(map)
            return this
        }

        /**
         * Adds all of the ConverterTesters from the given iterable. Only one tester for each class is
         * permitted; duplicates will cause [.build] to fail.
         */
        @CanIgnoreReturnValue
        fun addAll(items: Iterable<ConverterTester?>): Builder {
            items.forEach(Consumer { item: ConverterTester? -> this.add(item!!) })
            return this
        }

        fun build(): ConverterTesterMap {
            return ConverterTesterMap(delegate.build())
        }
    }
}
