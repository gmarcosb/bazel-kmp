// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.engine

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/** A helper for deduping values.  */
@ThreadSafe
interface Uniquifier<T> {
    /**
     * Returns whether `newElement` has been seen before by [.unique] or
     * [.unique].
     * 
     * 
     * Please note the difference between this method and [.unique]!
     * 
     * 
     * This method is inherently racy wrt [.unique] and [.unique]. Only
     * use it if you know what you are doing.
     */
    fun uniquePure(newElement: T?): Boolean

    /**
     * Returns whether `newElement` has been seen before by [.unique] or [ ][.unique].
     */
    @Throws(QueryException::class)
    fun unique(newElement: T?): Boolean

    /**
     * Returns the subset of `newElements` that haven't been seen before by [.unique]
     * or [.unique].
     */
    @Throws(QueryException::class)
    fun unique(newElements: Iterable<T?>?): ImmutableList<T?>?
}
