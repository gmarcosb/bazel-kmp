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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/** Encapsulation of data stored by [NodeEntry] when the value has finished building.  */
abstract class ValueWithMetadata private constructor(value: SkyValue?) : SkyValue {
    protected val value: SkyValue?

    init {
        this.value = value
    }

    open fun hasError(): Boolean {
        return false
    }

    fun getValue(): SkyValue? {
        return value
    }

    abstract fun getErrorInfo(): com.google.devtools.build.skyframe.ErrorInfo?

    abstract fun getTransitiveEvents(): NestedSet<Reportable?>?

    /** Implementation of [ValueWithMetadata] for the value case.  */
    @com.google.common.annotations.VisibleForTesting
    open class ValueWithEvents private constructor(value: SkyValue?, transitiveEvents: NestedSet<Reportable?>?) :
        ValueWithMetadata(com.google.common.base.Preconditions.checkNotNull<SkyValue?>(value)) {
        private val transitiveEvents: NestedSet<Reportable?>

        init {
            this.transitiveEvents =
                com.google.common.base.Preconditions.checkNotNull<NestedSet<Reportable?>>(transitiveEvents)
        }

        override fun getErrorInfo(): com.google.devtools.build.skyframe.ErrorInfo? {
            return null
        }

        override fun getTransitiveEvents(): NestedSet<Reportable?> {
            return transitiveEvents
        }

        /**
         * We override equals so that if the same value is written to a [NodeEntry] twice, it can
         * verify that the two values are equal, and avoid incrementing its version.
         */
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is ValueWithEvents) {
                return false
            }

            // Shallow equals is a middle ground between using default equals, which might miss
            // nested sets with the same elements, and deep equality checking, which would be expensive.
            // All three choices are sound, since shallow equals and default equals are more
            // conservative than deep equals. Using shallow equals means that we may unnecessarily
            // consider some values unequal that are actually equal, but this is still a net win over
            // deep equals.
            return value == o.value && transitiveEvents.shallowEquals(o.transitiveEvents)
        }

        override fun hashCode(): Int {
            return 31 * value.hashCode() + transitiveEvents.shallowHashCode()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("value", value)
                .add("transitiveEvents size", transitiveEvents.memoizedFlattenAndGetSize())
                .toString()
        }

        companion object {
            private fun createValueWithEvents(
                value: SkyValue?, transitiveEvents: NestedSet<Reportable?>?
            ): ValueWithEvents {
                if (value is NotComparableSkyValue) {
                    return NotComparableValueWithEvents(value, transitiveEvents)
                } else {
                    return ValueWithEvents(value, transitiveEvents)
                }
            }
        }
    }

    private class NotComparableValueWithEvents(value: SkyValue?, transitiveEvents: NestedSet<Reportable?>?) :
        ValueWithEvents(value, transitiveEvents), NotComparableSkyValue

    /**
     * Implementation of [ValueWithMetadata] for the error case.
     * 
     * 
     * Mark NotComparableSkyValue because it's unlikely that re-evaluation gives the same error.
     */
    private class ErrorInfoValue(
        errorInfo: com.google.devtools.build.skyframe.ErrorInfo?,
        value: SkyValue?,
        transitiveEvents: NestedSet<Reportable?>?
    ) : ValueWithMetadata(value), NotComparableSkyValue {
        private val errorInfo: com.google.devtools.build.skyframe.ErrorInfo?
        private val transitiveEvents: NestedSet<Reportable?>

        init {
            this.errorInfo =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.ErrorInfo?>(
                    errorInfo
                )
            this.transitiveEvents =
                com.google.common.base.Preconditions.checkNotNull<NestedSet<Reportable?>>(transitiveEvents)
        }

        override fun hasError(): Boolean {
            return true
        }

        override fun getErrorInfo(): com.google.devtools.build.skyframe.ErrorInfo? {
            return errorInfo
        }

        override fun getTransitiveEvents(): NestedSet<Reportable?> {
            return transitiveEvents
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || getClass() != o.getClass()) {
                return false
            }

            val that = o as ErrorInfoValue

            // Shallow equals is a middle ground between using default equals, which might miss
            // nested sets with the same elements, and deep equality checking, which would be expensive.
            // All three choices are sound, since shallow equals and default equals are more
            // conservative than deep equals. Using shallow equals means that we may unnecessarily
            // consider some values unequal that are actually equal, but this is still a net win over
            // deep equals.
            return this.value == that.value
                    && this.errorInfo == that.errorInfo
                    && transitiveEvents.shallowEquals(that.transitiveEvents)
        }

        override fun hashCode(): Int {
            return 31 * java.util.Objects.hash(value, errorInfo) + transitiveEvents.shallowHashCode()
        }

        override fun toString(): String {
            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            if (value != null) {
                result.append("Value: ").append(value)
            }
            if (errorInfo != null) {
                if (result.length() > 0) {
                    result.append("; ")
                }
                result.append("Error: ").append(errorInfo)
            }
            return result.toString()
        }
    }

    companion object {
        private val NO_EVENTS: NestedSet<Reportable?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        /** Builds a value entry that has an error (and no value).  */
        fun error(
            errorInfo: com.google.devtools.build.skyframe.ErrorInfo?, transitiveEvents: NestedSet<Reportable?>
        ): ValueWithMetadata? {
            return normal(null, errorInfo, transitiveEvents) as ValueWithMetadata?
        }

        /**
         * Builds a SkyValue that has a value, and possibly an error, and possibly events/postables. If it
         * has only a value, returns just the value in order to save memory.
         */
        fun normal(
            value: SkyValue?,
            errorInfo: com.google.devtools.build.skyframe.ErrorInfo?,
            transitiveEvents: NestedSet<Reportable?>
        ): SkyValue {
            com.google.common.base.Preconditions.checkState(
                value != null || errorInfo != null, "Value and error cannot both be null"
            )
            if (errorInfo == null) {
                return if (transitiveEvents.isEmpty())
                    value
                else
                    ValueWithEvents.Companion.createValueWithEvents(value, transitiveEvents)
            }
            return ErrorInfoValue(errorInfo, value, transitiveEvents)
        }

        fun justValue(value: SkyValue?): SkyValue? {
            if (value is ValueWithMetadata) {
                return value.value
            }
            return value
        }

        fun wrapWithMetadata(value: SkyValue?): ValueWithMetadata {
            if (value is ValueWithMetadata) {
                return value
            }
            return ValueWithEvents.Companion.createValueWithEvents(value, NO_EVENTS)
        }

        fun getMaybeErrorInfo(value: SkyValue?): com.google.devtools.build.skyframe.ErrorInfo? {
            if (value is ErrorInfoValue) {
                return (value as ValueWithMetadata).getErrorInfo()
            }
            return null
        }

        fun getEvents(value: SkyValue?): NestedSet<Reportable?>? {
            if (value is ValueWithMetadata) {
                return value.getTransitiveEvents()
            }
            return NO_EVENTS
        }
    }
}
