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

import com.google.devtools.build.skyframe.CycleInfo
import com.google.devtools.build.skyframe.SkyFunctionException.ReifiedSkyFunctionException
import com.google.devtools.build.skyframe.SkyKey

/**
 * Information about why a [SkyValue] failed to evaluate successfully.
 * 
 * 
 * This is intended only for use in alternative `MemoizingEvaluator` implementations.
 */
open class ErrorInfo(
    exception: java.lang.Exception?,
    cycles: com.google.common.collect.ImmutableList<CycleInfo?>,
    isDirectlyTransient: Boolean,
    isTransitivelyTransient: Boolean,
    isCatastrophic: Boolean
) {
    /** Whether the SkyKey of this ErrorInfo has a corresponding SkyValue.  */
    open fun hasValue(): Boolean {
        return false
    }

    private val exception: java.lang.Exception?
    private val cycles: com.google.common.collect.ImmutableList<CycleInfo?>

    /**
     * Returns true iff the error is directly transient, i.e. if there was a transient error
     * encountered during the computation itself.
     * 
     * 
     * A return of `true` implies that [.isTransitivelyTransient] is also `true`.
     */
    @kotlin.jvm.JvmField
    val isDirectlyTransient: Boolean

    /**
     * Returns true iff the error is transitively transient, i.e. if retrying the same computation
     * could lead to a different result.
     */
    @kotlin.jvm.JvmField
    val isTransitivelyTransient: Boolean

    /**
     * Returns true iff the error is catastrophic, i.e. it should halt even for a keepGoing update()
     * call.
     */
    @kotlin.jvm.JvmField
    val isCatastrophic: Boolean

    init {
        this.exception = exception
        this.cycles = cycles
        this.isDirectlyTransient = isDirectlyTransient
        this.isTransitivelyTransient = isTransitivelyTransient
        this.isCatastrophic = isCatastrophic
        // Expected 0 args, but got 1.
        com.google.common.base.Preconditions.checkArgument(
            exception != null || !cycles.isEmpty(),
            "At least one of exception and cycles must be present",
            this
        )
        // Expected 0 args, but got 1.
        com.google.common.base.Preconditions.checkArgument(
            !isDirectlyTransient || isTransitivelyTransient,
            "Cannot be directly transient but not transitively transient",
            this
        )
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is ErrorInfo) {
            return false
        }

        if (cycles != obj.cycles) {
            return false
        }

        // Don't check the specific exception as most exceptions don't implement equality but at least
        // check their types and messages are the same.
        if (exception !== obj.exception) {
            if (exception == null || obj.exception == null) {
                return false
            }
            // Class objects are singletons with a single class loader.
            if (exception.getClass() != obj.exception.getClass()) {
                return false
            }
            if (exception.getMessage() != obj.exception.getMessage()) {
                return false
            }
        }

        return isDirectlyTransient == obj.isDirectlyTransient && isTransitivelyTransient == obj.isTransitivelyTransient && isCatastrophic == obj.isCatastrophic
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(
            if (exception == null) null else exception.getClass(),
            if (exception == null) "" else exception.getMessage(),
            cycles,
            isDirectlyTransient,
            isTransitivelyTransient,
            isCatastrophic
        )
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("exception", exception)
            .add("cycles", cycles)
            .add("isCatastrophic", isCatastrophic)
            .add("isDirectlyTransient", isDirectlyTransient)
            .add("isTransitivelyTransient", isTransitivelyTransient)
            .toString()
    }

    /**
     * The exception thrown when building a value. May be null if value's only error is depending
     * on a cycle.
     * 
     * 
     * The exception is used for reporting and thus may ultimately be rethrown by the caller.
     * As well, during a --nokeep_going evaluation, if an error value is encountered from an earlier
     * --keep_going build, the exception to be thrown is taken from here.
     */
    fun getException(): java.lang.Exception? {
        return exception
    }

    val cycleInfo: com.google.common.collect.ImmutableList<CycleInfo?>
        /**
         * Any cycles found when building this value.
         * 
         * 
         * If there are a large number of cycles, only a limited number are returned here.
         * 
         * 
         * If this value has a child through which there are multiple paths to the same cycle, only one
         * path is returned here. However, if there are multiple paths to the same cycle, each of which
         * goes through a different child, each of them is returned here.
         */
        get() = cycles

    /**
     * Indicates that there's a value associated with the SkyKey that owns this ErrorInfo.
     * 
     * 
     * These should be de-prioritized among child ErrorInfos in [.fromChildErrors].
     */
    private class ErrorInfoWithValue(
        exception: java.lang.Exception?,
        cycles: com.google.common.collect.ImmutableList<CycleInfo?>,
        isDirectlyTransient: Boolean,
        isTransitivelyTransient: Boolean,
        isCatastrophic: Boolean
    ) : ErrorInfo(exception, cycles, isDirectlyTransient, isTransitivelyTransient, isCatastrophic) {
        override fun hasValue(): Boolean {
            return true
        }
    }

    companion object {
        /** Create an ErrorInfo from a [ReifiedSkyFunctionException].  */
        fun fromException(
            skyFunctionException: ReifiedSkyFunctionException, isTransitivelyTransient: Boolean
        ): ErrorInfo {
            val rootCauseException: java.lang.Exception? = skyFunctionException.getCause()
            return com.google.devtools.build.skyframe.ErrorInfo(
                com.google.common.base.Preconditions.checkNotNull<java.lang.Exception?>(
                    rootCauseException,
                    "Cause is null"
                ),  /*cycles=*/
                com.google.common.collect.ImmutableList.of<CycleInfo?>(),
                skyFunctionException.isTransient(),
                isTransitivelyTransient || skyFunctionException.isTransient(),
                skyFunctionException.isCatastrophic()
            )
        }

        /** Create an ErrorInfo from a [CycleInfo].  */
        fun fromCycle(cycleInfo: CycleInfo): ErrorInfo {
            return com.google.devtools.build.skyframe.ErrorInfo( /*exception=*/
                null,
                com.google.common.collect.ImmutableList.of<CycleInfo?>(cycleInfo),  /*isDirectlyTransient=*/
                false,  /*isTransitivelyTransient=*/
                false,  /*isCatastrophic=*/
                false
            )
        }

        /** A wrapper that indicates that there's a value associated with the ErrorInfo's SkyKey.  */
        fun withValue(wrapped: ErrorInfo): ErrorInfo {
            return ErrorInfoWithValue(
                wrapped.getException(),
                wrapped.cycleInfo,
                wrapped.isDirectlyTransient,
                wrapped.isTransitivelyTransient,
                wrapped.isCatastrophic
            )
        }

        /** Create an ErrorInfo from a collection of existing errors.  */
        fun fromChildErrors(currentValue: SkyKey?, childErrors: MutableCollection<ErrorInfo>): ErrorInfo {
            com.google.common.base.Preconditions.checkNotNull<SkyKey?>(currentValue, "currentValue must not be null")
            com.google.common.base.Preconditions.checkState(
                !childErrors.isEmpty(), "childErrors may not be empty %s", currentValue
            )

            val cycleBuilder: com.google.common.collect.ImmutableList.Builder<CycleInfo?> =
                com.google.common.collect.ImmutableList.builder<CycleInfo?>()
            var representativeException: java.lang.Exception? = null
            var representativeExceptionCameWithValue = false
            var isTransitivelyTransient = false
            var isCatastrophic = false
            for (child in childErrors) {
                // Child errors that come with a value indicates that the error was somehow tolerated.
                // Priorities should be given to those without values.
                // Otherwise, choose the first error.
                if (representativeException == null
                    || (representativeExceptionCameWithValue && child.exception != null)
                ) {
                    representativeException = child.exception
                    representativeExceptionCameWithValue = child.hasValue()
                }
                cycleBuilder.addAll(CycleInfo.Companion.prepareCycles(currentValue, child.cycles))
                isTransitivelyTransient = isTransitivelyTransient or child.isTransitivelyTransient
                isCatastrophic = isCatastrophic or child.isCatastrophic
            }

            return com.google.devtools.build.skyframe.ErrorInfo(
                representativeException,
                cycleBuilder.build(),  /* isDirectlyTransient= */
                false,
                isTransitivelyTransient,
                isCatastrophic
            )
        }
    }
}
