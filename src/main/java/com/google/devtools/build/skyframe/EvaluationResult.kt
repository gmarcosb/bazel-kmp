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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.skyframe.EvaluationResult
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.WalkableGraph
import java.util.Collections
import java.util.HashMap

/**
 * The result of a Skyframe [ParallelEvaluator.eval] call. Will contain all the successfully
 * evaluated values, retrievable through [.get]. As well, the [ErrorInfo] for the first
 * value that failed to evaluate (in the non-keep-going case), or any remaining values that failed
 * to evaluate (in the keep-going case) will be retrievable.
 * 
 * 
 * A node can never be successfully evaluated and fail to evaluate. Thus, if [.get] returns
 * non-null for some key, there is no stored error for that key, and vice versa.
 * 
 * @param <T> The type of the values that the caller has requested.
</T> */
class EvaluationResult<T : SkyValue?> private constructor(
    result: MutableMap<SkyKey?, T?>?,
    errorMap: MutableMap<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>?,
    catastrophe: java.lang.Exception?,
    walkableGraph: WalkableGraph?
) {
    private val catastrophe: java.lang.Exception?

    private val resultMap: MutableMap<SkyKey?, T?>
    private val errorMap: MutableMap<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo?>
    private val walkableGraph: WalkableGraph?

    /**
     * Constructor for the "completed" case. Used only by [Builder].
     */
    init {
        this.resultMap = com.google.common.base.Preconditions.checkNotNull<MutableMap<SkyKey?, T?>>(result)
        this.errorMap =
            com.google.common.base.Preconditions.checkNotNull<MutableMap<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo?>>(
                errorMap
            )
        this.catastrophe = catastrophe
        this.walkableGraph = walkableGraph
    }

    /**
     * Get a successfully evaluated value.
     */
    fun get(key: SkyKey?): T? {
        com.google.common.base.Preconditions.checkNotNull<MutableMap<SkyKey?, T?>?>(resultMap, key)
        return resultMap.get(key)
    }

    /**
     * @return Whether or not the eval successfully evaluated all requested values. True iff
     * [.getCatastrophe] or [.getError] returns non-null.
     */
    fun hasError(): Boolean {
        return catastrophe != null || !errorMap.isEmpty()
    }

    /**
     * Catastrophic error encountered during evaluation, if any. If the evaluation failed with a
     * catastrophe, this will be non-null.
     */
    fun getCatastrophe(): java.lang.Exception? {
        return catastrophe
    }

    /**
     * @return All successfully evaluated [SkyValue]s.
     */
    fun values(): MutableCollection<T?> {
        return Collections.unmodifiableCollection<T?>(resultMap.values())
    }

    /**
     * Returns [Map] of [SkyKey]s to [ErrorInfo]. Note that currently some of the
     * returned SkyKeys may not be the ones requested by the user. Moreover, the SkyKey is not
     * necessarily the cause of the error -- it is just the value that was being evaluated when the
     * error was discovered.
     */
    fun errorMap(): MutableMap<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo?> {
        return com.google.common.collect.ImmutableMap.copyOf<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo?>(
            errorMap
        )
    }

    /** Returns [ErrorInfo] for given `key` which must be present in errors.  */
    fun getError(key: SkyKey?): com.google.devtools.build.skyframe.ErrorInfo? {
        return com.google.common.base.Preconditions.checkNotNull<MutableMap<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo?>?>(
            errorMap,
            key
        ).get(key)
    }

    val error: com.google.devtools.build.skyframe.ErrorInfo?
        /**
         * Returns some error info. Convenience method equivalent to Iterables.getFirst([ ][.errorMap], null).getValue().
         */
        get() = com.google.common.collect.Iterables.getFirst<MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo?>?>(
            errorMap.entrySet(),
            null
        ).getValue()

    /**
     * @return Names of all values that were successfully evaluated. This collection is disjoint from
     * the keys in [.errorMap].
     */
    fun <S> keyNames(): MutableCollection<out S?> {
        return getNames<S?>(resultMap.keySet())
    }

    fun getWalkableGraph(): WalkableGraph? {
        return walkableGraph
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("catastrophe", catastrophe)
            .add("errorMap", errorMap)
            .add("resultMap", resultMap)
            .toString()
    }

    /**
     * Builder for [EvaluationResult].
     * 
     * 
     * This is intended only for use in alternative `MemoizingEvaluator` implementations.
     */
    class Builder<T : SkyValue?> {
        private val result: MutableMap<SkyKey?, T?> = HashMap<SkyKey?, T?>()
        private val errors: MutableMap<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo> =
            HashMap<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>()
        private var catastrophe: java.lang.Exception? = null
        private var walkableGraph: WalkableGraph? = null

        /** Adds a value to the result. An error for this key must not already be present.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addResult(key: SkyKey?, value: SkyValue?): Builder<T?> {
            result.put(key, com.google.common.base.Preconditions.checkNotNull<T?>(value as T?, key))
            // Expected 3 args, but got 2.
            com.google.common.base.Preconditions.checkState(
                !errors.containsKey(key), "%s in both result and errors: %s %s", value, errors
            )
            return this
        }

        /**
         * Adds an error to the result. A successful value for this key must not already be present.
         * Publicly visible only for testing: should be package-private.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addError(key: SkyKey?, error: com.google.devtools.build.skyframe.ErrorInfo?): Builder<T?> {
            errors.put(
                key,
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.ErrorInfo?>(
                    error,
                    key
                )
            )
            // Expected 3 args, but got 2.
            com.google.common.base.Preconditions.checkState(
                !result.containsKey(key), "%s in both result and errors: %s %s", error, result
            )
            if (error.isCatastrophic()) {
                setCatastrophe(error.getException())
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setWalkableGraph(walkableGraph: WalkableGraph?): Builder<T?> {
            this.walkableGraph = walkableGraph
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mergeFrom(otherResult: EvaluationResult<T?>): Builder<T?> {
            result.putAll(otherResult.resultMap)
            errors.putAll(otherResult.errorMap)
            catastrophe = otherResult.catastrophe
            return this
        }

        fun build(): EvaluationResult<T?> {
            return EvaluationResult<T?>(result, errors, catastrophe, walkableGraph)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCatastrophe(catastrophe: java.lang.Exception?): Builder<T?> {
            this.catastrophe = catastrophe
            return this
        }

        fun maybeEnsureCatastrophe(hasCatastrophe: Boolean) {
            if (!hasCatastrophe || catastrophe != null) {
                return
            }
            for (errorInfo in errors.values()) {
                if (errorInfo.getException() != null) {
                    catastrophe = errorInfo.getException()
                    return
                }
            }
            throw java.lang.IllegalStateException("Should have found exception in catastrophe: " + errors)
        }

        val isEmpty: Boolean
            get() = this.result.isEmpty() && this.errors.isEmpty()
    }

    companion object {
        private fun <S> getNames(keys: MutableCollection<SkyKey>): MutableCollection<out S?> {
            val names: MutableCollection<S?> = com.google.common.collect.Lists.newArrayListWithCapacity<S?>(keys.size())
            for (key in keys) {
                names.add(key.argument() as S?)
            }
            return names
        }

        @kotlin.jvm.JvmStatic
        fun <T : SkyValue?> builder(): Builder<T?> {
            return com.google.devtools.build.skyframe.EvaluationResult.Builder<T?>()
        }
    }
}
