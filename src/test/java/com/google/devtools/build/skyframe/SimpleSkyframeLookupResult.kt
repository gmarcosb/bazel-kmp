// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.ValueOrUntypedException

/** Simple implementation of [SkyframeLookupResult].  */
class SimpleSkyframeLookupResult(
    valuesMissingCallback: java.lang.Runnable?,
    valuesOrExceptions: java.util.function.Function<SkyKey?, ValueOrUntypedException?>?
) : SkyframeLookupResult {
    private val valuesMissingCallback: java.lang.Runnable
    private val valuesOrExceptions: java.util.function.Function<SkyKey?, ValueOrUntypedException?>? = null

    init {
        .also {
            this.valuesMissingCallback = it
        }<Runnable> com . google . common . base . Preconditions . checkNotNull < java . lang . Runnable ? > (valuesMissingCallback)
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.valuesOrExceptions = <Function<SkyKey, ValueOrUntypedException>>checkNotNull(valuesOrExceptions);
            """.trimMargin()
        )
    }

    /** Similar to [.getOrThrow], but takes three exception class parameters.  */
    @Throws(E1::class, E2::class, E3::class)
    public override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> getOrThrow(
        skyKey: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): SkyValue? {
        val voe: ValueOrUntypedException =
            com.google.common.base.Preconditions.checkNotNull<ValueOrUntypedException>(
                valuesOrExceptions.apply(skyKey),
                "Missing value for %s",
                skyKey
            )
        val value: SkyValue? = voe.getValue()
        if (value != null) {
            return value
        }
        SkyFunctionException.throwIfInstanceOf(
            voe.getException(), exceptionClass1, exceptionClass2, exceptionClass3, null
        )
        valuesMissingCallback.run()
        return null
    }

    public override fun queryDep(key: SkyKey?, resultCallback: QueryDepCallback): Boolean {
        val voe: ValueOrUntypedException =
            com.google.common.base.Preconditions.checkNotNull<ValueOrUntypedException>(
                valuesOrExceptions.apply(key),
                "Missing value for %s",
                key
            )
        val value: SkyValue? = voe.getValue()
        if (value != null) {
            resultCallback.acceptValue(key, value)
            return true
        }
        val exception: java.lang.Exception? = voe.getException()
        if (exception != null && resultCallback.tryHandleException(key, exception)) {
            return true
        }
        valuesMissingCallback.run()
        return false
    }
}
