// Copyright 2014 The Bazel Authors. All rights reserved.
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

/**
 * Supplier whose value can be changed by clients who have a reference to it as a MutableSupplier.
 * Unlike an `AtomicReference`, clients who are passed a MutableSupplier as a Supplier cannot
 * change its value without a reckless cast.
 */
class MutableSupplier<T> : com.google.common.base.Supplier<T?> {
    private var `val`: T? = null

    override fun get(): T? {
        return `val`
    }

    /**
     * Sets the value of the object supplied. Do not cast a Supplier to a MutableSupplier in order to
     * call this method!
     */
    fun set(newVal: T?) {
        `val` = newVal
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(javaClass)
            .add("val", `val`).toString()
    }
}
