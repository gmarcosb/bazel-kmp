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
package com.google.devtools.build.lib.analysis.actions

/**
 * A pair of a string to be substituted and a string to substitute it with. For simplicity, these
 * are called key and value. All implementations must be immutable, and always return the identical
 * key. The returned values must be the same, though they need not be the same object.
 * 
 * 
 * It should be assumed that the [.getKey] invocation is cheap, and that the [ ][.getValue] invocation is expensive.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable // if the keys and values in the passed in lists and maps are all immutable
abstract class Substitution private constructor() {
    abstract fun getKey(): String

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    abstract fun getValue(): String?

    /* Not intended for use in production code */ // TODO(hvd): migrate usages and delete
    @com.google.common.annotations.VisibleForTesting
    fun getValueUnchecked(): String? {
        try {
            return getValue()
        } catch (e: net.starlark.java.eval.EvalException) {
            throw java.lang.IllegalStateException(e)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    private class StringSubstitution(private val key: String?, private val value: String?) : Substitution() {
        override fun getKey(): String? {
            return key
        }

        override fun getValue(): String? {
            return value
        }
    }

    private class ListSubstitution(private val key: String?, value: com.google.common.collect.ImmutableList<*>) :
        Substitution() {
        private val value: com.google.common.collect.ImmutableList<*>

        init {
            this.value = value
        }

        override fun getKey(): String? {
            return key
        }

        override fun getValue(): String {
            return com.google.common.base.Joiner.on(" ").join(value)
        }
    }

    override fun equals(`object`: Any?): Boolean {
        if (this === `object`) {
            return true
        }
        if (`object` is Substitution) {
            return `object`.getKey() == this.getKey()
                    && `object`.getValueUnchecked() == this.getValueUnchecked()
        }
        return false
    }

    override fun hashCode(): Int {
        return com.google.common.base.Objects.hashCode(getKey(), getValueUnchecked())
    }

    override fun toString(): String {
        return "Substitution(" + getKey() + " -> " + getValueUnchecked() + ")"
    }

    /**
     * A substitution with a fixed key, and a computed value. The computed value must not change over
     * the lifetime of an instance, though the [.getValue] method may return different String
     * objects.
     * 
     * 
     * It should be assumed that the [.getKey] invocation is cheap, and that the [ ][.getValue] invocation is expensive.
     */
    abstract class ComputedSubstitution(@javax.annotation.Nonnull key: String) : Substitution() {
        private val key: String

        init {
            com.google.common.base.Preconditions.checkNotNull<String?>(key)
            this.key = key
        }

        override fun getKey(): String {
            return key
        }
    }

    companion object {
        /** Returns an immutable Substitution instance for the given key and value.  */
        fun of(@javax.annotation.Nonnull key: String, @javax.annotation.Nonnull value: String): Substitution {
            com.google.common.base.Preconditions.checkNotNull<String?>(key)
            com.google.common.base.Preconditions.checkNotNull<String?>(value)
            return StringSubstitution(key, value)
        }

        /**
         * Returns an immutable Substitution instance for the key and list of values. The values will be
         * joined by spaces before substitution.
         */
        fun ofSpaceSeparatedList(
            @javax.annotation.Nonnull key: String,
            @javax.annotation.Nonnull value: com.google.common.collect.ImmutableList<*>
        ): Substitution {
            com.google.common.base.Preconditions.checkNotNull<String?>(key)
            com.google.common.base.Preconditions.checkNotNull(value)
            return ListSubstitution(key, value)
        }
    }
}
