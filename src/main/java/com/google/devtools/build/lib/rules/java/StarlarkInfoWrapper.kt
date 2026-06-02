// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.collect.nestedset.Depset
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark

/** Base class for sharing utility code between wrapped Starlark provider instances  */
internal abstract class StarlarkInfoWrapper protected constructor(underlying: StructImpl) {
    protected val underlying: StructImpl

    init {
        this.underlying = underlying
    }

    @Throws(RuleErrorException::class)
    protected fun <T> getUnderlyingValue(key: String?, type: Class<T?>?): T? {
        try {
            if (underlying.getValue(key) === Starlark.NONE) {
                return null
            } else {
                return underlying.getValue(key, type)
            }
        } catch (e: EvalException) {
            throw RuleErrorException(e)
        }
    }

    @Throws(RuleErrorException::class)
    protected fun <T> getUnderlyingNestedSet(key: String?, type: Class<T?>?): NestedSet<T?> {
        try {
            return Depset.noneableCast(noneIfNull(underlying.getValue(key)), type, key)
        } catch (e: EvalException) {
            throw RuleErrorException(e)
        }
    }

    @Throws(RuleErrorException::class)
    protected fun <T> getUnderlyingSequence(key: String?, type: Class<T?>?): Sequence<T?>? {
        try {
            return Sequence.noneableCast<T?>(noneIfNull(underlying.getValue(key)), type, key)
        } catch (e: EvalException) {
            throw RuleErrorException(e)
        }
    }

    companion object {
        private fun noneIfNull(value: Any?): Any? {
            return if (value == null) Starlark.NONE else value
        }
    }
}
