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
package net.starlark.java.eval

import java.util.IdentityHashMap

/**
 * An object that manages the capability to mutate Starlark objects and their [ ]s. Collectively, the managed objects are called [Freezable]s.
 * 
 * 
 * Each `StarlarkThread`, and each of the mutable Starlark values that are created in that
 * `StarlarkThread`, holds a pointer to the same `Mutability` instance. Once the `StarlarkThread` is done evaluating, its `Mutability` is irreversibly closed ("frozen"). At
 * that point, it is no longer possible to change either the bindings in that `StarlarkThread`
 * or the state of its objects. This protects each `StarlarkThread` from unintentional and
 * unsafe modification.
 * 
 * 
 * `Mutability`s enforce isolation between `StarlarkThread`s; it is illegal for an
 * evaluation in one `StarlarkThread` to affect the bindings or values of another. In
 * particular, the `StarlarkThread` for any Starlark module is frozen before its symbols can
 * be imported for use by another module. Each individual `StarlarkThread`'s evaluation is
 * single-threaded, so this isolation also translates to thread safety. Any number of threads may
 * simultaneously access frozen data. (The `Mutability` itself is also thread-safe if and only
 * if it is frozen.}
 * 
 * 
 * Although the mutability pointer of a `Freezable` contains some debugging information
 * about its context, this should not affect the `Freezable`'s semantics. From a behavioral
 * point of view, the only thing that matters is whether the `Mutability` is frozen, not what
 * particular `Mutability` object is pointed to.
 * 
 * 
 * When a Starlark program iterates over a mutable sequence value in a for-loop or comprehension,
 * the sequence value becomes temporarily immutable for the duration of the loop. Conceptually, the
 * value maintains a counter of active iterations, and the interpreter notifies the `Freezable` value before and after the loop so that it can alter its counter by calling its `updateIteratorCount` method. While the counter value is nonzero, the value should cause all
 * attempts to mutate it to fail. The temporary immutability applies only to the sequence itself,
 * not to its elements. Once a mutable sequence becomes frozen, there is no need to count active
 * iterators (and doing so would be racy as frozen objects may be published to other Starlark
 * threads). The default implementation of `updateIteratorCount` uses a set of counters in the
 * Mutability, but a Freezable object may define a more efficient intrusive counter implementation.
 * 
 * 
 * We follow two disciplines to ensure safety. First, all mutation methods of a Freezable value
 * must confirm that the value's Mutability is not yet frozen, nor is the value temporarily
 * immutable due to active iterators.
 * 
 * 
 * Second, `Mutability`s are created using the try-with-resource style:
 * 
 * <pre>`try (Mutability mutability = Mutability.create(name, ...)) { ... } `</pre>
 * 
 * The general pattern is to create a `Mutability`, build an `StarlarkThread`, mutate
 * that `StarlarkThread` and its objects, and possibly return the result from within the
 * `try` block, relying on the try-with-resource construct to ensure that everything gets
 * frozen before the result is used. The only code that should create a `Mutability` without
 * using try-with-resource is test code that is not part of the Bazel jar.
 * 
 * 
 * We keep some (unchecked) invariants regarding where `Mutability` objects may appear
 * within a compound value.
 * 
 * 
 *  1. A compound value can never contain an unfrozen `Mutability` for any `StarlarkThread` except the one currently being evaluated.
 *  1. If a value has the special [.IMMUTABLE] `Mutability`, all of its contents are
 * themselves deeply immutable too (i.e. have frozen `Mutability`s).
 * 
 * 
 * It follows that, if these invariants hold, an unfrozen value cannot appear as the child of a
 * value whose `Mutability` is already frozen.
 * 
 * 
 * There is a special API for freezing individual values rather than whole `StarlarkThread`s. Because this API makes it easier to violate the above invariants, you should
 * avoid using it if at all possible; at the moment it is only used for serialization. Under this
 * API, you may call [Freezable.unsafeShallowFreeze] to reset a value's `Mutability`
 * pointer to be [.IMMUTABLE]. This operation has no effect on the `Mutability` itself.
 * It is up to the caller to preserve or restore the above invariants by ensuring that any deeply
 * contained values are also frozen. For safety and explicitness, this operation is disallowed
 * unless the `Mutability`'s [.allowsUnsafeShallowFreeze] method returns true.
 */
class Mutability private constructor(annotation: Array<Any?>, allowsUnsafeShallowFreeze: Boolean) :
    java.lang.AutoCloseable {
    // Maps each temporarily frozen Freezable value to the (positive) count of active iterators over
    // the value. This field is set to null when the Mutability becomes permanently frozen, at which
    // point there is no need to track iterators. This map does not contain Freezable values that
    // define their own implementation of updateIteratorCount.
    private var iteratorCount: IdentityHashMap<Freezable?, Int?>? =
        IdentityHashMap<Freezable?, Int?>(10) // 10 nested for-loops seems plenty

    // An optional list of values that are formatted with toString and joined with spaces to yield the
    // "annotation", an internal name describing the purpose of this Mutability.
    private val annotation: Array<Any?>

    /** Controls access to [Freezable.unsafeShallowFreeze].  */
    private val allowsUnsafeShallowFreeze: Boolean

    /** Returns the Mutability's "annotation", an internal name describing its purpose.  */
    fun getAnnotation(): String {
        // The annotation string is computed when needed, typically never,
        // to avoid the performance penalty of materializing it eagerly.
        return com.google.common.base.Joiner.on(" ").join(annotation)
    }

    override fun toString(): String {
        return (if (isFrozen()) "(" else "[") + getAnnotation() + (if (isFrozen()) ")" else "]")
    }

    fun isFrozen(): Boolean {
        return this.iteratorCount == null
    }

    // Defines the default behavior of mutable Freezable sequence values,
    // which become temporarily immutable while there are active iterators.
    private fun updateIteratorCount(x: Freezable?, delta: Int): Boolean {
        if (isFrozen()) {
            return false
        }
        var i: Int = this.iteratorCount.getOrDefault(x, 0)
        if (delta > 0) {
            i++
            this.iteratorCount.put(x, i)
        } else if (delta < 0) {
            i--
            if (i == 0) {
                this.iteratorCount.remove(x)
            } else if (i > 0) {
                this.iteratorCount.put(x, i)
            } else {
                throw java.lang.IllegalStateException("zero value in this.iteratorCount")
            }
        }
        return i > 0
    }

    /**
     * Freezes this `Mutability`, rendering all [Freezable] objects that refer to it
     * immutable.
     * 
     * 
     * Note that freezing does not directly touch all the `Freezables`, so this operation is
     * constant-time.
     * 
     * @return this object, in the fluent style
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun freeze(): Mutability {
        this.iteratorCount = null
        return this
    }

    override fun close() {
        // Avoid TSan errors due to concurrent writes to the shared IMMUTABLE instance (b/512786661).
        if (this != net.starlark.java.eval.Mutability.Companion.IMMUTABLE) {
            freeze()
        }
    }

    /**
     * Returns whether [Freezable]s having this `Mutability` allow the [ ][.unsafeShallowFreeze] operation.
     */
    fun allowsUnsafeShallowFreeze(): Boolean {
        return allowsUnsafeShallowFreeze
    }

    /**
     * An object that refers to a [Mutability] to decide whether to allow mutation. All [ ] Starlark objects created within a given [StarlarkThread] will share the same
     * `Mutability` as that `StarlarkThread`.
     */
    interface Freezable {
        /**
         * Returns the [Mutability] associated with this `Freezable`. This should not change
         * over the lifetime of the object, except by calling [.unsafeShallowFreeze] if
         * applicable.
         */
        fun mutability(): Mutability

        /**
         * Registers a change to this Freezable's iterator count and reports whether it is temporarily
         * immutable.
         * 
         * 
         * If the value is permanently frozen (`mutability().isFrozen()), this function is a no-op that returns false. <p>Otherwise, if delta is positive, this increments the count of active iterators over the value, causing it to appear temporarily frozen (if it wasn't already). If delta is negative, the counter is decremented, and if delta is zero the counter is unchanged. It is illegal to decrement the counter if it was already zero. The return value is true if the count is positive after the change, and false otherwise. <p>The default implementation stores the counter of iterators in a hash table in the Mutability, but a subclass of Freezable may define a more efficient implementation such as an integer field in the freezable value itself. <p>Call this function with a positive value when starting an iteration and with a negative value when ending it.`
         */
        fun updateIteratorCount(delta: Int): Boolean {
            return mutability().updateIteratorCount(this, delta)
        }

        /**
         * Freezes this object (and not its contents). Use with care.
         * 
         * 
         * This method is optional (i.e. may throw [UnsupportedOperationException]).
         * 
         * 
         * If this object's [Mutability] is 1) not frozen, and 2) has [ ][.allowsUnsafeShallowFreeze] return true, then the object's `Mutability` reference is
         * updated to point to [.IMMUTABLE]. Otherwise, this method throws [ ].
         * 
         * 
         * It is up to the caller to ensure that any contents of this `Freezable` are also
         * frozen in order to preserve/restore the invariant that an immutable value cannot contain a
         * mutable one. Note that thread-safety is not guaranteed otherwise.
         */
        fun unsafeShallowFreeze() {
            throw java.lang.UnsupportedOperationException()
        }

        companion object {
            /**
             * Throws [IllegalArgumentException] if the precondition for [.unsafeShallowFreeze]
             * is violated. To be used by implementors of [.unsafeShallowFreeze].
             */
            @kotlin.jvm.JvmStatic
            fun checkUnsafeShallowFreezePrecondition(
                freezable: Freezable
            ) {
                val mutability = freezable.mutability()
                require(!mutability.isFrozen()) { "cannot call unsafeShallowFreeze() on an object whose Mutability is already frozen" }
                require(mutability.allowsUnsafeShallowFreeze()) {
                    ("cannot call unsafeShallowFreeze() on a mutable object whose Mutability's "
                            + "allowsUnsafeShallowFreeze() == false")
                }
            }
        }
    }

    init {
        this.annotation = annotation
        this.allowsUnsafeShallowFreeze = allowsUnsafeShallowFreeze
    }

    companion object {
        /**
         * Creates a `Mutability`.
         * 
         * @param annotation a list of objects whose toString representations are joined with spaces to
         * yield the annotation, an internal name describing the purpose of this Mutability.
         */
        @kotlin.jvm.JvmStatic
        fun create(vararg annotation: Any?): Mutability {
            return net.starlark.java.eval.Mutability(annotation,  /*allowsUnsafeShallowFreeze=*/false)
        }

        /**
         * Creates a `Mutability` whose objects can be individually frozen; see docstrings for
         * [Mutability] and [Freezable.unsafeShallowFreeze].
         */
        @kotlin.jvm.JvmStatic
        fun createAllowingShallowFreeze(vararg annotation: Any?): Mutability {
            return net.starlark.java.eval.Mutability(annotation,  /*allowsUnsafeShallowFreeze=*/true)
        }

        /**
         * A `Mutability` indicating that a value is deeply immutable.
         * 
         * 
         * It is not associated with any particular [StarlarkThread].
         */
        @kotlin.jvm.JvmField
        val IMMUTABLE: Mutability = net.starlark.java.eval.Mutability.Companion.create("IMMUTABLE").freeze()
    }
}
