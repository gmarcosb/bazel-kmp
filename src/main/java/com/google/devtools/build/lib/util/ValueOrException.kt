// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

/**
 * Contains a value, or the exception encountered while obtaining that value.
 * 
 * 
 * This class is intended to be used when an operation computes multiple values, some of which
 * may have succeeded and others may have failed, and it is necessary to return all of these values
 * or failures without throwing at the first failure encountered.
 */
// TODO(b/331799946): try to consolidate Bazel's "value or exception" sum types into this one
abstract class ValueOrException<V, E : java.lang.Exception?>  // Must be initialized through ofValue or ofException
private constructor() {
    /** Returns true if the ValueOrException holds a value.  */
    @kotlin.jvm.JvmField
    abstract val isPresent: Boolean

    /**
     * Returns the value if the ValueOrException holds a value.
     * 
     * @throws E if the ValueOrException holds an exception.
     */
    @Throws(E::class)
    abstract fun get(): V?

    val unchecked: V?
        /**
         * A variant of [.get] which throws an unchecked exception.
         * 
         * @throws IllegalStateException if the ValueOrException holds an exception.
         */
        get() {
            try {
                return get()
            } catch (e: java.lang.Exception) {
                throw java.lang.IllegalStateException(e)
            }
        }

    /**
     * Returns the exception if the ValueOrException holds an exception.
     * 
     * @throws IllegalStateException if the ValueOrException does not hold an exception.
     */
    @kotlin.jvm.JvmField
    abstract val exception: E?

    private class OfValue<V, E : java.lang.Exception?>(private val value: V?) : ValueOrException<V?, E?>() {
        override fun isPresent(): Boolean {
            return true
        }

        override fun get(): V? {
            return value
        }

        override fun getException(): E? {
            throw java.lang.IllegalStateException(
                "ValueOrException.getException() cannot be called on a ValueOrException holding a value"
            )
        }

        override fun toString(): String {
            return String.format("ValueOrException.OfValue[%s]", value)
        }

        /**
         * {@inheritDoc}
         * 
         * 
         * Value equality semantics; two `OfValue` objects are equal iff their contained values
         * are equal.
         */
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is OfValue<*, *>) {
                return false
            }
            return value == o.value
        }

        override fun hashCode(): Int {
            return value!!.hashCode()
        }
    }

    private class OfException<V, E : java.lang.Exception?>(exception: E?) : ValueOrException<V?, E?>() {
        private val exception: E?

        init {
            this.exception = com.google.common.base.Preconditions.checkNotNull<E?>(exception)
        }

        override fun isPresent(): Boolean {
            return false
        }

        @Throws(E::class)
        override fun get(): V? {
            throw exception
        }

        override fun getException(): E? {
            return exception
        }

        override fun toString(): String {
            return String.format("ValueOrException.OfException[%s]", exception)
        }

        /**
         * {@inheritDoc}
         * 
         * 
         * Value equality semantics; two `OfException` objects are equal iff their contained
         * exception objects are equal.
         */
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is OfException<*, *>) {
                return false
            }
            return exception == o.exception
        }

        override fun hashCode(): Int {
            return exception.hashCode()
        }
    }

    companion object {
        /** Constructs a ValueOrException holding a non-null value.  */
        fun <V, E : java.lang.Exception?> ofValue(value: V?): ValueOrException<V?, E?> {
            return OfValue<V?, E?>(com.google.common.base.Preconditions.checkNotNull<V?>(value))
        }

        /** Constructs a ValueOrException holding an exception.  */
        fun <V, E : java.lang.Exception?> ofException(exception: E?): ValueOrException<V?, E?> {
            return OfException<V?, E?>(com.google.common.base.Preconditions.checkNotNull<E?>(exception))
        }
    }
}
