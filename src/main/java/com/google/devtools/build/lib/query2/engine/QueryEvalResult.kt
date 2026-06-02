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

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.util.DetailedExitCode

/**
 * Information about the query evaluation, like if it was successful and number of elements
 * returned.
 */
open class QueryEvalResult internal constructor(success: Boolean, empty: Boolean, detailedExitCode: DetailedExitCode) {
    /**
     * Whether the query was successful. This can only be false if the query was run with `keep_going`, otherwise evaluation will throw a [QueryException].
     */
    @kotlin.jvm.JvmField
    val success: Boolean

    /** True if the query did not return any result;  */
    val isEmpty: Boolean

    /**
     * Returns [DetailedExitCode.success] if successful, and otherwise a [ ] describing the failure if unsuccessful.
     */
    @kotlin.jvm.JvmField
    val detailedExitCode: DetailedExitCode

    init {
        Preconditions.checkNotNull<DetailedExitCode?>(detailedExitCode)
        Preconditions.checkArgument(
            !success || detailedExitCode.isSuccess(),
            "successful query evaluations should not have non-success exit codes. detailedExitCode=%s",
            detailedExitCode
        )
        this.success = success
        this.isEmpty = empty
        this.detailedExitCode = detailedExitCode
    }

    override fun toString(): String {
        return (if (this.success) "Successful" else "Unsuccessful") + ", empty = " + this.isEmpty
    }

    companion object {
        fun success(empty: Boolean): QueryEvalResult {
            return QueryEvalResult(true, empty, DetailedExitCode.success())
        }

        fun failure(empty: Boolean, detailedExitCode: DetailedExitCode): QueryEvalResult {
            return QueryEvalResult(false, empty, detailedExitCode)
        }
    }
}
