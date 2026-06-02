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
package com.google.devtools.build.lib.cmdline

/**
 * Callback to be invoked when part of a result has been computed. Allows a client interested in the
 * result to process it as it is computed, for instance by streaming it, if it is too big to fit in
 * memory.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
interface BatchCallback<T, E> where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
    /**
     * Called when part of a result has been computed.
     * 
     * 
     * Note that this method can be called several times for a single `BatchCallback`.
     * Implementations should assume that multiple calls can happen.
     * 
     * @param partialResult Part of the result. May contain duplicates, either in the same call or
     * across calls.
     */
    @Throws(E::class, java.lang.InterruptedException::class)
    fun process(partialResult: Iterable<T?>?)

    /** [BatchCallback] that doesn't throw.  */
    interface SafeBatchCallback<T>
        : BatchCallback<T?, com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface.MarkerRuntimeException?>

    /** [SafeBatchCallback] that does precisely nothing.  */
    class NullCallback<T> : SafeBatchCallback<T?> {
        override fun process(partialResult: Iterable<T?>?) {}

        companion object {
            private val INSTANCE: NullCallback<Any?> =
                com.google.devtools.build.lib.cmdline.BatchCallback.NullCallback<Any?>()

            @kotlin.jvm.JvmStatic
            fun <T> instance(): NullCallback<T?>? {
                return com.google.devtools.build.lib.cmdline.BatchCallback.NullCallback.Companion.INSTANCE as NullCallback<T?>?
            }
        }
    }
}
