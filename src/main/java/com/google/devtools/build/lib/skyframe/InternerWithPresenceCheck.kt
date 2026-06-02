// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Strong [Interner] that also exposes whether there is a canonical representative for the
 * given sample object via [.getCanonical].
 */
class InternerWithPresenceCheck<T> : com.google.common.collect.Interner<T?> {
    private val map: ConcurrentMap<T?, T?> = ConcurrentHashMap<T?, T?>()

    override fun intern(sample: T?): T? {
        val canonical: T? = map.putIfAbsent(com.google.common.base.Preconditions.checkNotNull<T?>(sample), sample)
        return if (canonical == null) sample else canonical
    }

    /**
     * Returns the canonical representative for `sample` if it is present. Unlike [ ][.intern], does not store `sample`. In other words, this method does not mutate the
     * interner.
     */
    fun getCanonical(sample: T?): T? {
        return map.get(sample)
    }
}
