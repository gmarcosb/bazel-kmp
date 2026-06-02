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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.cmdline.ResolvedTargets
import java.util.LinkedHashSet

/**
 * Contains the result of the target pattern evaluation. This is a specialized container class for
 * the result of target pattern resolution. There is no restriction on the element type, but it will
 * usually be `Target` or `Label`.
 */
@javax.annotation.concurrent.Immutable
class ResolvedTargets<T> {
    private val hasError: Boolean
    private val targets: com.google.common.collect.ImmutableSet<T?>
    private val filteredTargets: com.google.common.collect.ImmutableSet<T?>

    constructor(targets: MutableSet<T?>, filteredTargets: MutableSet<T?>, hasError: Boolean) {
        this.targets = com.google.common.collect.ImmutableSet.copyOf<T?>(targets)
        this.filteredTargets = com.google.common.collect.ImmutableSet.copyOf<T?>(filteredTargets)
        this.hasError = hasError
    }

    constructor(targets: MutableSet<T?>, hasError: Boolean) {
        this.targets = com.google.common.collect.ImmutableSet.copyOf<T?>(targets)
        this.filteredTargets = com.google.common.collect.ImmutableSet.of<T?>()
        this.hasError = hasError
    }

    override fun toString(): String {
        return ("ResolvedTargets(" + targets + ", filtered=" + filteredTargets
                + ", hasError=" + hasError + ")")
    }

    fun hasError(): Boolean {
        return hasError
    }

    fun getTargets(): com.google.common.collect.ImmutableSet<T?> {
        return targets
    }

    fun getFilteredTargets(): com.google.common.collect.ImmutableSet<T?> {
        return filteredTargets
    }

    class Builder<T> private constructor(
        private var targets: MutableSet<T?> = LinkedHashSet<T?>(),
        private val filteredTargets: MutableSet<T?> = LinkedHashSet<T?>()
    ) {
        @kotlin.concurrent.Volatile
        private var hasError = false

        fun build(): ResolvedTargets<T?> {
            return ResolvedTargets<T?>(targets, filteredTargets, hasError)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun merge(other: ResolvedTargets<T?>): Builder<T?> {
            removeAll(other.filteredTargets)
            addAll(other.targets)
            if (other.hasError) {
                hasError = true
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(target: T?): Builder<T?> {
            targets.add(target)
            filteredTargets.remove(target)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAll(targets: MutableCollection<T?>?): Builder<T?> {
            this.targets.addAll(targets!!)
            this.filteredTargets.removeAll(targets)
            return this
        }

        fun remove(target: T?) {
            targets.remove(target)
            filteredTargets.add(target)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun removeAll(targets: MutableCollection<T?>?): Builder<T?> {
            this.filteredTargets.addAll(targets!!)
            this.targets.removeAll(targets)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun filter(predicate: com.google.common.base.Predicate<T?>): Builder<T?> {
            val oldTargets = targets
            targets = LinkedHashSet<T?>()
            for (target in oldTargets) {
                if (predicate.apply(target)) {
                    add(target)
                } else {
                    remove(target)
                }
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setError(): Builder<T?> {
            this.hasError = true
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun mergeError(hasError: Boolean): Builder<T?> {
            this.hasError = this.hasError or hasError
            return this
        }

        val isEmpty: Boolean
            get() = targets.isEmpty()
    }

    companion object {
        private val FAILED_RESULT: ResolvedTargets<*> = ResolvedTargets<Any?>(
            com.google.common.collect.ImmutableSet.of<Any?>(),
            com.google.common.collect.ImmutableSet.of<Any?>(),
            true
        )

        private val EMPTY_RESULT: ResolvedTargets<*> = ResolvedTargets<Any?>(
            com.google.common.collect.ImmutableSet.of<Any?>(),
            com.google.common.collect.ImmutableSet.of<Any?>(),
            false
        )

        @kotlin.jvm.JvmStatic
        fun <T> failed(): ResolvedTargets<T?>? {
            return FAILED_RESULT as ResolvedTargets<T?>?
        }

        @kotlin.jvm.JvmStatic
        fun <T> empty(): ResolvedTargets<T?>? {
            return EMPTY_RESULT as ResolvedTargets<T?>?
        }

        fun <T> of(target: T?): ResolvedTargets<T?> {
            return ResolvedTargets<T?>(com.google.common.collect.ImmutableSet.of<T?>(target), false)
        }

        /**
         * Returns a builder using concurrent sets, as long as you don't call filter.
         */
        fun <T> concurrentBuilder(): Builder<T?> {
            return com.google.devtools.build.lib.cmdline.ResolvedTargets.Builder<T?>(
                com.google.common.collect.Sets.newConcurrentHashSet<T?>(),
                com.google.common.collect.Sets.newConcurrentHashSet<T?>()
            )
        }

        @kotlin.jvm.JvmStatic
        fun <T> builder(): Builder<T?> {
            return com.google.devtools.build.lib.cmdline.ResolvedTargets.Builder<T?>()
        }
    }
}
