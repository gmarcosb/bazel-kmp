// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.util

import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get

/**
 * Returns a [Supplier] which caches the instance retrieved during the first call to
 * `get()` and returns that value on subsequent calls to `get()`. See:
 * [memoization](http://en.wikipedia.org/wiki/Memoization).
 * 
 * 
 * The returned supplier is thread-safe. The delegate's `get()` method will be invoked at
 * most once.
 * 
 * 
 * The returned supplier is not serializable.
 */
class MemoizingSupplier<T>(delegate: java.util.function.Supplier<T?>) : java.util.function.Supplier<T?> {
    private val delegate: java.util.function.Supplier<T?>

    @kotlin.concurrent.Volatile
    private var initialized = false
    private var instance: T? = null

    init {
        this.delegate = delegate
    }

    override fun get(): T? {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    initialized = true
                    instance = delegate.get()
                }
            }
        }
        return instance
    }
}

