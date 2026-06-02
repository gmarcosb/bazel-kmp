// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.io.IOException
import java.math.BigInteger
import java.util.TreeSet

/**
 * The Starlark class defines the most important entry points, constants, and functions needed by
 * all clients of the Starlark interpreter.
 */
class Starlark private constructor() // uninstantiable
{
    /** A type representing no argument passed to `StarlarkMethod`s  */
    @javax.annotation.concurrent.Immutable
    class UnboundMarker private constructor() : net.starlark.java.eval.StarlarkValue {
        override fun toString(): String {
            return "<unbound>"
        }

        override fun isImmutable(): Boolean {
            return true
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            printer.append("<unbound>")
        }
    }

    fun toImmutableMap(
    )

    /**
     * An `IllegalArgumentException` subclass for when a non-Starlark object is encountered in a
     * context where a Starlark value (`String`, `Boolean`, or `StarlarkValue`) was
     * expected.
     */
    class InvalidStarlarkValueException private constructor(invalidClass: java.lang.Class<*>?) :
        java.lang.IllegalArgumentException("invalid Starlark value: " + (if (invalidClass == null) "null" else invalidClass)) {
        private val invalidClass: java.lang.Class<*>?

        fun getInvalidClass(): java.lang.Class<*>? {
            return invalidClass
        }

        init {
            this.invalidClass = invalidClass
        }
    }

    /**
     * Decorates a [RuntimeException] with its Starlark stack, to help maintainers locate
     * problematic source expressions.
     * 
     * 
     * The original exception can be retrieved using [.getCause].
     */
    class UncheckedEvalException private constructor(
        cause: java.lang.RuntimeException,
        thread: net.starlark.java.eval.StarlarkThread
    ) : java.lang.RuntimeException(
        net.starlark.java.eval.Starlark.Companion.createUncheckedEvalMessage(cause, thread),
        cause
    ) {
        init {
            thread.fillInStackTrace(this)
        }
    }

    /**
     * Decorates an [Error] with its Starlark stack, to help maintainers locate problematic
     * source expressions.
     * 
     * 
     * The original exception can be retrieved using [.getCause].
     */
    class UncheckedEvalError private constructor(
        cause: java.lang.Error,
        thread: net.starlark.java.eval.StarlarkThread
    ) : java.lang.Error(net.starlark.java.eval.Starlark.Companion.createUncheckedEvalMessage(cause, thread), cause) {
        init {
            thread.fillInStackTrace(this)
        }
    }

    companion object {
        /** The Starlark None value.  */
        @kotlin.jvm.JvmField
        val NONE: net.starlark.java.eval.NoneType = net.starlark.java.eval.NoneType.Companion.NONE

        /**
         * A sentinel value passed to optional parameters of StarlarkMethod-annotated methods to indicate
         * that no argument value was supplied.
         */
        @kotlin.jvm.JvmField
        val UNBOUND: Any = net.starlark.java.eval.Starlark.UnboundMarker()

        /**
         * The universal bindings predeclared in every Starlark file, such as None, True, len, and range.
         */
        @kotlin.jvm.JvmField
        val UNIVERSE: com.google.common.collect.ImmutableMap<String?, Any?> =
            net.starlark.java.eval.Starlark.Companion.makeUniverse()

        /** The Starlark types of the entries in [.UNIVERSE].  */
        val UNIVERSAL_SYMBOL_TYPES: com.google.common.collect.ImmutableMap<String?, net.starlark.java.syntax.StarlarkType?>? =
            net.starlark.java.eval.Starlark.Companion.UNIVERSE.entrySet().stream()
                .collect()

        private fun makeUniverse(): com.google.common.collect.ImmutableMap<String?, Any?> {
            val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.builder<String?, Any?>()
            env //
                .put("False", false)
                .put("True", true)
                .put("None", net.starlark.java.eval.Starlark.Companion.NONE)
            addMethods(env, net.starlark.java.eval.MethodLibrary())
            return env.build()
        }

        /**
         * Reports whether the argument is a legal Starlark value: a string, boolean, or StarlarkValue.
         */
        fun valid(x: Any?): Boolean {
            return x is String || x is Boolean || x is net.starlark.java.eval.StarlarkValue
        }

        /**
         * Returns `x` if it is a [.valid] Starlark value, otherwise throws
         * InvalidStarlarkValueException.
         */
        fun <T> checkValid(x: T?): T? {
            if (!net.starlark.java.eval.Starlark.Companion.valid(x)) {
                throw net.starlark.java.eval.Starlark.InvalidStarlarkValueException(if (x == null) null else x.getClass())
            }
            return x
        }

        /** Reports whether `x` is Java null or Starlark None.  */
        @kotlin.jvm.JvmStatic
        fun isNullOrNone(x: Any?): Boolean {
            return x == null || x === net.starlark.java.eval.Starlark.Companion.NONE
        }

        /** Reports whether a Starlark value is assumed to be deeply immutable.  */ // TODO(adonovan): eliminate the concept of querying for immutability. It is currently used for
        // only one purpose, the precondition for adding an element to a Depset, but Depsets should check
        // hashability, like Dicts. (Similarly, querying for hashability should go: just attempt to hash a
        // value, and be prepared for it to fail.) In practice, a value may be immutable, either
        // inherently (e.g. string) or because it has become frozen, but we don't need to query for it.
        // Just attempt a mutation and be prepared for it to fail.
        // It is inefficient and potentially inconsistent to ask before doing.
        //
        // The main obstacle is that although depsets disallow (say) lists as keys even when frozen,
        // they permit a tuple of lists, or a struct containing lists, and many users exploit this.
        @kotlin.jvm.JvmStatic
        fun isImmutable(x: Any): Boolean {
            // NB: This is used as the basis for accepting objects in Depsets,
            // as well as for accepting objects as keys for Starlark dicts.

            if (x is String || x is Boolean) {
                return true
            } else if (x is net.starlark.java.eval.StarlarkValue) {
                return (x as net.starlark.java.eval.StarlarkValue).isImmutable()
            } else {
                throw net.starlark.java.eval.Starlark.InvalidStarlarkValueException(x.getClass())
            }
        }

        /**
         * Returns normally if the Starlark value is hashable and thus suitable as a dict key.
         * 
         * @throws EvalException otherwise.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun checkHashable(x: Any?) {
            if (x is String) {
                // Strings are the most common dict keys. Check them first, since `instanceof StarlarkValue`
                // (an interface) is slower than `instanceof String` (a final class).
            } else if (x is net.starlark.java.eval.StarlarkValue) {
                (x as net.starlark.java.eval.StarlarkValue).checkHashable()
            } else {
                // Throw if the type is bad. Otherwise it's a Boolean, which is hashable.
                net.starlark.java.eval.Starlark.Companion.checkValid<Any?>(x)
            }
        }

        /**
         * Converts a Java value `x` to a Starlark one, if x is not already a valid Starlark value.
         * An Integer, Long, or BigInteger is converted to a Starlark int, a double is converted to a
         * Starlark float, a Java List or Map is converted to a Starlark list or dict, respectively, and
         * null becomes [.NONE]. Any other non-Starlark value causes the function to throw
         * InvalidStarlarkValueException.
         * 
         * 
         * Elements of Lists and Maps must be valid Starlark values; they are not recursively
         * converted. (This avoids excessive unintended deep copying.)
         * 
         * 
         * This function is applied to the results of StarlarkMethod-annotated Java methods.
         */
        fun fromJava(x: Any?, mutability: net.starlark.java.eval.Mutability?): Any? {
            if (x == null) {
                return net.starlark.java.eval.Starlark.Companion.NONE
            } else if (net.starlark.java.eval.Starlark.Companion.valid(x)) {
                return x
            } else if (x is Number) {
                if (x is Int) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(x)
                } else if (x is Long) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(x)
                } else if (x is BigInteger) {
                    return net.starlark.java.eval.StarlarkInt.Companion.of(x as BigInteger)
                } else if (x is Double) {
                    return net.starlark.java.eval.StarlarkFloat.Companion.of(x)
                }
            } else if (x is MutableList<*>) {
                return net.starlark.java.eval.StarlarkList.Companion.copyOf(mutability, x)
            } else if (x is MutableMap<*, *>) {
                return net.starlark.java.eval.Dict.Companion.copyOf(mutability, x)
            } else if (x is MutableSet<*>) {
                return net.starlark.java.eval.StarlarkSet.Companion.copyOf(mutability, x)
            }
            throw net.starlark.java.eval.Starlark.InvalidStarlarkValueException(x.getClass())
        }

        /**
         * Converts a Starlark method's bound, non-None parameter value to a Java Optional wrapping that
         * value, and an unbound or None value to an empty Optional.
         * 
         * 
         * This is typically used in [StarlarkMethod] implementations, with a parameter whose
         * [Param.allowedTypes] is set to be `{T}` or `{NoneType, T}`.
         * 
         * @throws ClassCastException if value is bound and non-None but is not of the expected class
         */
        fun <T> toJavaOptional(x: Any?, expectedClass: java.lang.Class<T?>): java.util.Optional<T?> {
            if (x === net.starlark.java.eval.Starlark.Companion.UNBOUND || x === net.starlark.java.eval.Starlark.Companion.NONE) {
                return java.util.Optional.empty<T?>()
            } else {
                return java.util.Optional.of<T?>(expectedClass.cast(x))
            }
        }

        /**
         * Returns the truth value of a valid Starlark value, as if by the Starlark expression `bool(x)`.
         */
        @kotlin.jvm.JvmStatic
        fun truth(x: Any): Boolean {
            if (x is Boolean) {
                return x
            } else if (x is net.starlark.java.eval.StarlarkValue) {
                return (x as net.starlark.java.eval.StarlarkValue).truth()
            } else if (x is String) {
                return !x.isEmpty()
            } else {
                throw net.starlark.java.eval.Starlark.InvalidStarlarkValueException(x.getClass())
            }
        }

        /**
         * Checks whether the Freezable Starlark value is frozen or temporarily immutable due to active
         * iterators.
         * 
         * @throws EvalException if the value is not mutable.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun checkMutable(x: net.starlark.java.eval.Mutability.Freezable) {
            if (x.mutability().isFrozen()) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "trying to mutate a frozen %s value",
                    net.starlark.java.eval.Starlark.Companion.type(x)
                )
            }
            if (x.updateIteratorCount(0)) {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "%s value is temporarily immutable due to active for-loop iteration",
                    net.starlark.java.eval.Starlark.Companion.type(x)
                )
            }
        }

        /**
         * Returns an iterable view of `x` if it is an iterable Starlark value; throws EvalException
         * otherwise.
         * 
         * 
         * Whereas the interpreter temporarily freezes the iterable value by bracketing `for`
         * loops and comprehensions in calls to [Freezable.updateIteratorCount], iteration using
         * this method does not freeze the value. Callers should exercise care not to mutate the
         * underlying object during iteration.
         */
        @kotlin.jvm.JvmStatic
        @Throws(net.starlark.java.eval.EvalException::class)
        fun toIterable(x: Any): Iterable<*> {
            if (x is net.starlark.java.eval.StarlarkIterable<*>) {
                return x as Iterable<*>
            }
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "type '%s' is not iterable",
                net.starlark.java.eval.Starlark.Companion.type(x)
            )
        }

        /**
         * Returns a new array of class Object[] containing the elements of Starlark iterable value `x`. A Starlark value is iterable if it implements [StarlarkIterable].
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun toArray(x: Any): Array<Any?> {
            // Specialize Sequence and Dict to avoid allocation and/or indirection.
            if (x is net.starlark.java.eval.Sequence<*>) {
                return (x as net.starlark.java.eval.Sequence<*>).toArray()
            } else if (x is net.starlark.java.eval.Dict<*, *>) {
                return (x as net.starlark.java.eval.Dict<*, *>).keySet().toArray()
            } else {
                return com.google.common.collect.Iterables.toArray<Any?>(
                    net.starlark.java.eval.Starlark.Companion.toIterable(
                        x
                    ), Any::class.java
                )
            }
        }

        /**
         * Returns the length of a Starlark string, sequence (such as a list or tuple), dict, or other
         * iterable, as if by the Starlark expression `len(x)`, or -1 if the value is valid but has
         * no length.
         */
        @kotlin.jvm.JvmStatic
        fun len(x: Any?): Int {
            if (x is String) {
                return x.length()
            } else if (x is net.starlark.java.eval.Sequence<*>) {
                return (x as net.starlark.java.eval.Sequence<*>).size()
            } else if (x is net.starlark.java.eval.Dict<*, *>) {
                return (x as net.starlark.java.eval.Dict<*, *>).size()
            } else if (x is net.starlark.java.eval.StarlarkSet<*>) {
                return (x as net.starlark.java.eval.StarlarkSet<*>).size()
            } else if (x is net.starlark.java.eval.StarlarkIterable<*>) {
                // Iterables.size runs in constant time if x implements Collection.
                return com.google.common.collect.Iterables.size(x as Iterable<*>)
            } else {
                Object > net.starlark.java.eval.Starlark.Companion.checkValid<Any?>(x)
                return -1 // valid but not a sequence
            }
        }

        /** Returns the type of the given Starlark value.  */
        fun getStarlarkType(
            value: Any,
            semantics: net.starlark.java.eval.StarlarkSemantics
        ): net.starlark.java.syntax.StarlarkType {
            return when (value) {
                -> net.starlark.java.syntax.Types.STR
                -> net.starlark.java.syntax.Types.BOOL
                -> {
                    var type: net.starlark.java.syntax.StarlarkType? = x.getStarlarkType(semantics)
                    if (type == null) {
                        type = net.starlark.java.eval.CallUtils.getBuiltinManager(semantics)
                            .getClassStarlarkType(value.getClass())
                    }
                    if (type != null) type else net.starlark.java.syntax.Types.ANY
                }

                else -> {
                    Object > net.starlark.java.eval.Starlark.Companion.checkValid<Any?>(value) // throws
                    throw java.lang.AssertionError("unreachable")
                }
            }
        }

        /** Returns the name of the type of a value as if by the Starlark expression `type(x)`.  */
        @kotlin.jvm.JvmStatic
        fun type(x: Any): String? {
            return net.starlark.java.eval.Starlark.Companion.classType(x.getClass())
        }

        /**
         * Returns the name of the type of instances of class c.
         * 
         * 
         * This function accepts any class, not just those of legal Starlark values, and may be used
         * for reporting error messages involving arbitrary Java classes, for example at the interface
         * between Starlark and Java.
         */
        fun classType(c: java.lang.Class<*>): String? {
            // Check for "direct hits" first to avoid needing to scan for annotations.
            if (c == String::class.java) {
                return "string"
            } else if (net.starlark.java.eval.StarlarkInt::class.java.isAssignableFrom(c)) {
                return "int"
            } else if (c == Boolean::class.java) {
                return "bool"
            } else if (c == net.starlark.java.eval.StarlarkFloat::class.java) {
                return "float"
            }

            // Shortcut for the most common types.
            // These cases can be handled by `getStarlarkBuiltin`
            // but `getStarlarkBuiltin` is quite expensive.
            if (net.starlark.java.eval.StarlarkList::class.java.isAssignableFrom(c)) {
                return "list"
            } else if (net.starlark.java.eval.Tuple::class.java.isAssignableFrom(c)) {
                return "tuple"
            } else if (net.starlark.java.eval.Dict::class.java.isAssignableFrom(c)) {
                return "dict"
            } else if (c == net.starlark.java.eval.NoneType::class.java) {
                return "NoneType"
            } else if (c == net.starlark.java.eval.StarlarkFunction::class.java) {
                return "function"
            } else if (c == net.starlark.java.eval.RangeList::class.java) {
                return "range"
            } else if (c == net.starlark.java.eval.Starlark.UnboundMarker::class.java) {
                return "unbound"
            }

            // Abstract types, often used as parameter types.
            // Note == not isAssignableFrom: we don't want any
            // concrete types to inherit these names.
            if (c == net.starlark.java.eval.StarlarkIterable::class.java) {
                return "iterable"
            } else if (c == net.starlark.java.eval.Sequence::class.java) {
                return "sequence"
            } else if (c == net.starlark.java.eval.StarlarkCallable::class.java) {
                return "callable"
            } else if (c == net.starlark.java.eval.Structure::class.java) {
                return "structure"
            }

            val module: net.starlark.java.annot.StarlarkBuiltin? =
                net.starlark.java.annot.StarlarkAnnotations.getStarlarkBuiltin(c)
            if (module != null) {
                return module.name
            }

            if (c == Any::class.java) {
                // "unknown" is another unfortunate choice.
                // Object.class does mean "unknown" when talking about the type parameter
                // of a collection (List<Object>), but it also means "any" when used
                // as an argument to Sequence.cast, and more generally it means "value".
                return "unknown"
            } else if (MutableList::class.java.isAssignableFrom(c)) {
                // Any class of java.util.List that isn't a Sequence.
                return "List"
            } else if (MutableMap::class.java.isAssignableFrom(c)) {
                // Any class of java.util.Map that isn't a Dict.
                return "Map"
            } else if (c == Int::class.java) {
                // Integer is not a legal Starlark value, but it does appear as
                // the return type for many built-in functions.
                return "int"
            } else if (c == Void.TYPE) {
                // Built-in void methods return None to Starlark.
                return "NoneType"
            } else if (c == Boolean::class.javaPrimitiveType) {
                // Built-in function may return boolean.
                return "bool"
            } else {
                val simpleName: String = c.getSimpleName()
                return if (simpleName.isEmpty()) c.getName() else simpleName
            }
        }

        /**
         * Returns the name of the type of instances of `c` after being converted to Starlark values
         * by [.fromJava], or "unknown" for `Object.class`, since that is used as a wildcard
         * type by evaluation machinery.
         * 
         * 
         * Note that `void.class` is treated as "NoneType" since void methods will return None to
         * Starlark.
         * 
         * @throws InvalidStarlarkValueException if `c` is not `Object.class` and [     ][.fromJava] would throw for instances of `c`.
         */
        fun classTypeFromJava(c: java.lang.Class<*>): String? {
            if (c == Void.TYPE // Method.invoke on void-returning methods returns null; we treat it as None
                || c == String::class.java
                || c == Boolean::class.javaPrimitiveType
                || c == Boolean::class.java
                || net.starlark.java.eval.StarlarkValue::class.java.isAssignableFrom(c)
                || c == Any::class.java
            ) {
                return net.starlark.java.eval.Starlark.Companion.classType(c)
            } else if (c == Int::class.javaPrimitiveType
                || c == Int::class.java
                || c == Long::class.javaPrimitiveType
                || c == Long::class.java
                || BigInteger::class.java.isAssignableFrom(c)
            ) {
                return net.starlark.java.eval.Starlark.Companion.classType(net.starlark.java.eval.StarlarkInt::class.java)
            } else if (c == Double::class.javaPrimitiveType || c == Double::class.java) {
                return net.starlark.java.eval.Starlark.Companion.classType(net.starlark.java.eval.StarlarkFloat::class.java)
            } else if (MutableList::class.java.isAssignableFrom(c)) {
                return net.starlark.java.eval.Starlark.Companion.classType(net.starlark.java.eval.StarlarkList::class.java)
            } else if (MutableMap::class.java.isAssignableFrom(c)) {
                return net.starlark.java.eval.Starlark.Companion.classType(net.starlark.java.eval.Dict::class.java)
            }
            throw net.starlark.java.eval.Starlark.InvalidStarlarkValueException(c)
        }

        /**
         * The ordering relation over (some) Starlark values.
         * 
         * 
         * Starlark values are ordered as follows.
         * 
         * 
         *  * `False < True`.
         *  * int values are ordered according to mathematical tradition.
         *  * float values are ordered according to IEEE 754, with the exception of NaN values: all NaN
         * values compare equal to each other and greater than +Inf. The zero values 0.0 and -0.0
         * compare equal.
         *  * int and float values may be compared. The comparison is mathematically exact, even if
         * neither argument may be exactly converted to the type of the other. This is the only
         * permitted case of comparisons between values of different types. NaN values compare
         * greater than all integers.
         *  * Strings are ordered lexicographically by their elements (chars). So too are lists and
         * tuples, though lists are not comparable with tuples.
         *  * If x implements Comparable, its `compareTo(y)` method may be called to determine
         * the comparison if x and y have the same [.type], though not necessary the same Java
         * class.
         *  * Ordered comparison of any other values is an error (ClassCastException).
         * 
         * 
         * 
         * This method defines a strict weak ordering that is consistent with [Object.equals].
         */
        @kotlin.jvm.JvmField
        val ORDERING: com.google.common.collect.Ordering<Any?> = object : com.google.common.collect.Ordering<Any?>() {
            override fun compare(x: Any, y: Any): Int {
                return net.starlark.java.eval.Starlark.Companion.compareUnchecked(x, y)
            }
        }

        /**
         * Defines the strict weak ordering of Starlark values used for sorting and the comparison
         * operators. Throws ClassCastException on failure.
         */
        fun compareUnchecked(x: Any, y: Any): Int {
            if (net.starlark.java.eval.Starlark.Companion.sameType(x, y)) {
                // Ordered? e.g. string, int, bool, float.
                if (x is Comparable<*>) {
                    val xcomp = x as Comparable<Any?>
                    return xcomp.compareTo(y)
                }
            } else {
                // different types

                if (x is net.starlark.java.eval.StarlarkFloat && y is net.starlark.java.eval.StarlarkInt) {
                    // float < int
                    val xf: Double = (x as net.starlark.java.eval.StarlarkFloat).toDouble()
                    return if (java.lang.Double.isNaN(xf)) +1 else -net.starlark.java.eval.StarlarkInt.Companion.compareIntAndDouble(
                        y as net.starlark.java.eval.StarlarkInt,
                        xf
                    )
                } else if (x is net.starlark.java.eval.StarlarkInt && y is net.starlark.java.eval.StarlarkFloat) {
                    // int < float
                    val yf: Double = (y as net.starlark.java.eval.StarlarkFloat).toDouble()
                    return if (java.lang.Double.isNaN(yf)) -1 else net.starlark.java.eval.StarlarkInt.Companion.compareIntAndDouble(
                        x as net.starlark.java.eval.StarlarkInt,
                        yf
                    )
                }
            }

            throw java.lang.ClassCastException(
                java.lang.String.format(
                    "unsupported comparison: %s <=> %s",
                    net.starlark.java.eval.Starlark.Companion.type(x),
                    net.starlark.java.eval.Starlark.Companion.type(y)
                )
            )
        }

        private fun sameType(x: Any, y: Any): Boolean {
            return x.getClass() == y.getClass() || net.starlark.java.eval.Starlark.Companion.type(x) == net.starlark.java.eval.Starlark.Companion.type(
                y
            )
        }

        /** Returns the string form of a value as if by the Starlark expression `str(x)`.  */
        fun str(x: Any?, semantics: net.starlark.java.eval.StarlarkSemantics?): String {
            return net.starlark.java.eval.Printer().str(x, semantics).toString()
        }

        /** Returns the string form of a value as if by the Starlark expression `repr(x)`.  */
        fun repr(x: Any?, semantics: net.starlark.java.eval.StarlarkSemantics?): String {
            return net.starlark.java.eval.Printer().repr(x, semantics).toString()
        }

        /** Returns a string formatted as if by the Starlark expression `pattern % arguments`.  */
        fun format(
            semantics: net.starlark.java.eval.StarlarkSemantics?,
            pattern: String?,
            vararg arguments: Any?
        ): String {
            val pr: net.starlark.java.eval.Printer = net.starlark.java.eval.Printer()
            net.starlark.java.eval.Printer.Companion.format(pr, semantics, pattern, *arguments)
            return pr.toString()
        }

        /** Returns a string formatted as if by the Starlark expression `pattern % arguments`.  */
        fun formatWithList(
            semantics: net.starlark.java.eval.StarlarkSemantics?, pattern: String, arguments: MutableList<*>
        ): String {
            val pr: net.starlark.java.eval.Printer = net.starlark.java.eval.Printer()
            net.starlark.java.eval.Printer.Companion.formatWithList(pr, semantics, pattern, arguments)
            return pr.toString()
        }

        /**
         * Returns a Starlark doc string with each line trimmed and dedented to the minimal common
         * indentation level (except for the first line, which is always fully trimmed), and with leading
         * and trailing empty lines removed, following the PEP-257 algorithm. See
         * https://peps.python.org/pep-0257/#handling-docstring-indentation
         * 
         * 
         * For whitespace trimming, we use the same definition of whitespace as the Starlark `string.strip` method.
         * 
         * 
         * Following PEP-257, we expand tabs in the doc string with tab size 8 before dedenting.
         * Starlark does not use tabs for indentation, but Starlark string values may contain tabs, so we
         * choose to expand them for consistency with Python.
         * 
         * 
         * The intent is to turn documentation strings like
         * 
         * <pre>
         * """Heading
         * 
         * Details paragraph
         * """
        </pre> * 
         * 
         * and
         * 
         * <pre>
         * """
         * Heading
         * 
         * Details paragraph
         * """
        </pre> * 
         * 
         * into the desired "Heading\n\nDetails paragraph" form, and avoid the risk of documentation
         * processors interpreting indented parts of the original string as special formatting (e.g. code
         * blocks in the case of Markdown).
         */
        // TODO: Pass in StarlarkSemantics as an argument rather than using StarlarkSemantics.DEFAULT.
        @kotlin.jvm.JvmStatic
        fun trimDocString(docString: String): String {
            val lines: com.google.common.collect.ImmutableList<String?> =
                net.starlark.java.eval.Starlark.Companion.expandTabs(docString, 8).lines()
                    .collect(TODO("Cannot convert element"))<String> com . google . common . collect . ImmutableList . toImmutableList < kotlin . Any ? > ()

            if (lines.isEmpty()) {
                return ""
            }
            // First line is special: we fully strip it and ignore it for leading spaces calculation
            val firstLineTrimmed: String =
                net.starlark.java.eval.StringModule.Companion.INSTANCE.stripSemantics(
                    lines.get(0),
                    net.starlark.java.eval.Starlark.Companion.NONE,
                    net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                )
            val subsequentLines: Iterable<String> = com.google.common.collect.Iterables.skip<String?>(lines, 1)
            val minLeadingSpaces: Int = java.lang.Integer.MAX_VALUE
            for (line in subsequentLines) {
                val strippedLeading: String =
                    net.starlark.java.eval.StringModule.Companion.INSTANCE.lstripSemantics(
                        line,
                        net.starlark.java.eval.Starlark.Companion.NONE,
                        net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                    )
                if (!strippedLeading.isEmpty()) {
                    val leadingSpaces: Int = line.length() - strippedLeading.length()
                    minLeadingSpaces = java.lang.Math.min(leadingSpaces, minLeadingSpaces)
                }
            }
            if (minLeadingSpaces == java.lang.Integer.MAX_VALUE) {
                minLeadingSpaces = 0
            }

            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            result.append(firstLineTrimmed)
            for (line in subsequentLines) {
                // Length check ensures we ignore leading empty lines
                if (result.length() > 0) {
                    result.append("\n")
                }
                if (line.length() > minLeadingSpaces) {
                    result.append(
                        net.starlark.java.eval.StringModule.Companion.INSTANCE.rstripSemantics(
                            line.substring(minLeadingSpaces),
                            net.starlark.java.eval.Starlark.Companion.NONE,
                            net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                        )
                    )
                }
            }
            // Remove trailing empty lines
            return net.starlark.java.eval.StringModule.Companion.INSTANCE.rstripSemantics(
                result.toString(),
                net.starlark.java.eval.Starlark.Companion.NONE,
                net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
            )
        }

        /**
         * Expands tab characters to one or more spaces, producing the same indentation level at any given
         * point on any given line as would be expected when rendering the string with a given tab size; a
         * Java port of Python's `str.expandtabs`.
         */
        @kotlin.jvm.JvmStatic
        fun expandTabs(line: String, tabSize: Int): String {
            if (!line.contains("\t")) {
                // Don't alloc in the fast case.
                return line
            }
            com.google.common.base.Preconditions.checkArgument(tabSize > 0)
            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            var col = 0
            for (i in 0..<line.length()) {
                val c: Char = line.charAt(i)
                when (c) {
                    '\n', '\r' -> {
                        result.append(c)
                        col = 0
                    }

                    '\t' -> {
                        val spaces = tabSize - col % tabSize
                        val j = 0
                        while (j < spaces) {
                            result.append(' ')
                            j++
                        }
                        col += spaces
                    }

                    else -> {
                        result.append(c)
                        col++
                    }
                }
            }
            return result.toString()
        }

        /** Returns a slice of a sequence as if by the Starlark operation `x[start:stop:step]`.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun slice(
            mu: net.starlark.java.eval.Mutability?, x: Any, startObj: Any, stopObj: Any, stepObj: Any
        ): Any? {
            val n: Int
            if (x is String) {
                n = x.length()
            } else if (x is net.starlark.java.eval.Sequence<*>) {
                n = (x as net.starlark.java.eval.Sequence<*>).size()
            } else {
                throw net.starlark.java.eval.Starlark.Companion.errorf(
                    "invalid slice operand: %s",
                    net.starlark.java.eval.Starlark.Companion.type(x)
                )
            }

            var start: Int
            var stop: Int
            val step: Int

            // step
            if (stepObj === net.starlark.java.eval.Starlark.Companion.NONE) {
                step = 1
            } else {
                step = net.starlark.java.eval.Starlark.Companion.toInt(stepObj, "slice step")
                if (step == 0) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf("slice step cannot be zero")
                }
            }

            // start, stop
            if (step > 0) {
                // positive stride: default indices are [0:n].
                if (startObj === net.starlark.java.eval.Starlark.Companion.NONE) {
                    start = 0
                } else {
                    start = net.starlark.java.syntax.SyntaxUtils.toSliceBound(
                        net.starlark.java.eval.Starlark.Companion.toInt(
                            startObj,
                            "start index"
                        ), n
                    )
                }

                if (stopObj === net.starlark.java.eval.Starlark.Companion.NONE) {
                    stop = n
                } else {
                    stop = net.starlark.java.syntax.SyntaxUtils.toSliceBound(
                        net.starlark.java.eval.Starlark.Companion.toInt(
                            stopObj,
                            "stop index"
                        ), n
                    )
                }

                if (stop < start) {
                    stop = start // => empty result
                }
            } else {
                // negative stride: default indices are effectively [n-1:-1],
                // though to get this effect using explicit indices requires
                // [n-1:-1-n:-1] because of the treatment of negative values.
                if (startObj === net.starlark.java.eval.Starlark.Companion.NONE) {
                    start = n - 1
                } else {
                    start = net.starlark.java.syntax.SyntaxUtils.toReverseSliceBound(
                        net.starlark.java.eval.Starlark.Companion.toInt(
                            startObj,
                            "start index"
                        ), n
                    )
                }

                if (stopObj === net.starlark.java.eval.Starlark.Companion.NONE) {
                    stop = -1
                } else {
                    stop = net.starlark.java.syntax.SyntaxUtils.toReverseSliceBound(
                        net.starlark.java.eval.Starlark.Companion.toInt(
                            stopObj,
                            "stop index"
                        ), n
                    )
                }

                if (start < stop) {
                    start = stop // => empty result
                }
            }

            // slice operation
            if (x is String) {
                return net.starlark.java.eval.StringModule.Companion.slice(x, start, stop, step)
            } else {
                return (x as net.starlark.java.eval.Sequence<*>).getSlice(mu, start, stop, step)
            }
        }

        /**
         * Returns the signed 32-bit value of a Starlark int. Throws an exception including `what`
         * if x is not a Starlark int or its value is not exactly representable as a Java int.
         * 
         * @throws IllegalArgumentException if x is an Integer, which is not a Starlark value.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun toInt(x: Any, what: String?): Int {
            if (x is net.starlark.java.eval.StarlarkInt) {
                return (x as net.starlark.java.eval.StarlarkInt).toInt(what)
            }
            require(!x is Int) { "Integer is not a legal Starlark value" }
            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "got %s for %s, want int",
                net.starlark.java.eval.Starlark.Companion.type(x),
                what
            )
        }

        /**
         * Calls the function-like value `fn` in the specified thread, passing it the given
         * positional and named arguments, as if by the Starlark expression `fn(*args, **kwargs)`.
         * 
         * 
         * See also [.callViaArgumentProcessor] and [.positionalOnlyCall].
         */
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun call(
            thread: net.starlark.java.eval.StarlarkThread,
            fn: Any,
            args: MutableList<Any?>,
            kwargs: MutableMap<String?, Any?>
        ): Any? {
            val callable: net.starlark.java.eval.StarlarkCallable =
                net.starlark.java.eval.Starlark.Companion.getStarlarkCallable(thread, fn)
            val argumentProcessor: net.starlark.java.eval.StarlarkCallable.ArgumentProcessor =
                net.starlark.java.eval.Starlark.Companion.requestArgumentProcessor(thread, callable)
            for (arg in args) {
                argumentProcessor.addPositionalArg(arg)
            }
            for (e in kwargs.entrySet()) {
                argumentProcessor.addNamedArg(
                    e.getKey(),
                    net.starlark.java.eval.Starlark.Companion.checkValid<Any?>(e.getValue())
                )
            }
            return net.starlark.java.eval.Starlark.Companion.callViaArgumentProcessor(
                thread,
                callable,
                argumentProcessor
            )
        }

        /**
         * Calls the a function-like value in the specified thread via the given ArgumentProcessor which
         * previously has been returned by [.requestArgumentProcessor] and has been populated with
         * the arguments.
         * 
         * 
         * If the call throws an unchecked throwable, regardless of whether it originates in a
         * user-defined built-in function or a bug in the interpreter itself, the throwable is wrapped by
         * [UncheckedEvalException] (for [RuntimeException]) or [UncheckedEvalError]
         * (for [Error]). The [stack trace][Throwable.getStackTrace] will reflect the
         * Starlark call stack rather than the Java call stack. The original throwable (and the Java call
         * stack) may be retrieved using [Throwable.getCause].
         */
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun callViaArgumentProcessor(
            thread: net.starlark.java.eval.StarlarkThread,
            callable: net.starlark.java.eval.StarlarkCallable?,
            argumentProcessor: net.starlark.java.eval.StarlarkCallable.ArgumentProcessor
        ): Any? {
            thread.push(callable)
            try {
                return argumentProcessor.call(thread)
            } catch (ex: UncheckedEvalException) {
                throw ex // already wrapped
            } catch (ex: UncheckedEvalError) {
                throw ex
            } catch (ex: java.lang.RuntimeException) {
                throw net.starlark.java.eval.Starlark.UncheckedEvalException(ex, thread)
            } catch (ex: java.lang.Error) {
                throw net.starlark.java.eval.Starlark.UncheckedEvalError(ex, thread)
            } catch (ex: net.starlark.java.eval.EvalException) {
                // If this exception was newly thrown, set its stack.
                throw ex.ensureStack(thread)
            } finally {
                thread.pop()
            }
        }

        /**
         * Calls the function-like value `fn` in the specified thread, passing it only positional
         * arguments in the "fastcall" array representation.
         * 
         * 
         * The caller must not subsequently modify or even inspect the array.
         * 
         * 
         * If the call throws an unchecked throwable, regardless of whether it originates in a
         * user-defined built-in function or a bug in the interpreter itself, the throwable is wrapped by
         * [UncheckedEvalException] (for [RuntimeException]) or [UncheckedEvalError]
         * (for [Error]). The [stack trace][Throwable.getStackTrace] will reflect the
         * Starlark call stack rather than the Java call stack. The original throwable (and the Java call
         * stack) may be retrieved using [Throwable.getCause].
         */
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun positionalOnlyCall(
            thread: net.starlark.java.eval.StarlarkThread,
            callable: net.starlark.java.eval.StarlarkCallable,
            vararg positional: Any?
        ): Any? {
            // LINT.IfChange(positionalOnlyCall)
            thread.push(callable)
            try {
                return callable.positionalOnlyCall(thread, *positional)
            } catch (ex: UncheckedEvalException) {
                throw ex // already wrapped
            } catch (ex: UncheckedEvalError) {
                throw ex
            } catch (ex: java.lang.RuntimeException) {
                throw net.starlark.java.eval.Starlark.UncheckedEvalException(ex, thread)
            } catch (ex: java.lang.Error) {
                throw net.starlark.java.eval.Starlark.UncheckedEvalError(ex, thread)
            } catch (ex: net.starlark.java.eval.EvalException) {
                // If this exception was newly thrown, set its stack.
                throw ex.ensureStack(thread)
            } finally {
                thread.pop()
            }
            // LINT.ThenChange(:fastcall)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun getStarlarkCallable(
            thread: net.starlark.java.eval.StarlarkThread,
            fn: Any
        ): net.starlark.java.eval.StarlarkCallable {
            val callable: net.starlark.java.eval.StarlarkCallable
            if (fn is net.starlark.java.eval.StarlarkCallable) {
                callable = fn
            } else {
                // @StarlarkMethod(selfCall)?
                val desc: net.starlark.java.eval.MethodDescriptor? =
                    thread.getBuiltinManager().getSelfCallMethodDescriptor(fn.getClass())
                if (desc == null) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf(
                        "'%s' object is not callable",
                        net.starlark.java.eval.Starlark.Companion.type(fn)
                    )
                }
                callable = net.starlark.java.eval.BuiltinFunction.Companion.of(fn, desc)
            }
            return callable
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun requestArgumentProcessor(
            thread: net.starlark.java.eval.StarlarkThread?, callable: net.starlark.java.eval.StarlarkCallable
        ): net.starlark.java.eval.StarlarkCallable.ArgumentProcessor {
            return callable.requestArgumentProcessor(thread)
        }

        private fun createUncheckedEvalMessage(
            cause: Throwable,
            thread: net.starlark.java.eval.StarlarkThread
        ): String {
            val msg = cause.getClass().getSimpleName() + " thrown during Starlark evaluation"
            val context: String? = thread.getContextDescription()
            return if (com.google.common.base.Strings.isNullOrEmpty(context)) msg else msg + " (" + context + ")"
        }

        /**
         * Returns a new EvalException with no location and an error message produced by Java-style string
         * formatting (`String.format(format, args)`). Use `errorf("%s", msg)` to produce an
         * error message from a non-constant expression `msg`.
         */
        @kotlin.jvm.JvmStatic
        @com.google.errorprone.annotations.FormatMethod
        @com.google.errorprone.annotations.CheckReturnValue // don't forget to throw it
        fun errorf(format: String, vararg args: Any?): net.starlark.java.eval.EvalException {
            return net.starlark.java.eval.EvalException(java.lang.String.format(format, *args))
        }

        // --- methods related to attributes (fields and methods) ---
        /**
         * Reports whether the value `x` has a field or method of the given name, as if by the
         * Starlark expression `hasattr(x, name)`.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun hasattr(semantics: net.starlark.java.eval.StarlarkSemantics, x: Any, name: String?): Boolean {
            return net.starlark.java.eval.Starlark.Companion.hasattr(
                net.starlark.java.eval.CallUtils.getBuiltinManager(
                    semantics
                ), x, name
            )
        }

        /**
         * Optimized version of [.hasattr] that avoids a map
         * lookup for the [BuiltinManager].
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun hasattr(thread: net.starlark.java.eval.StarlarkThread, x: Any, name: String?): Boolean {
            return net.starlark.java.eval.Starlark.Companion.hasattr(thread.getBuiltinManager(), x, name)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun hasattr(manager: net.starlark.java.eval.CallUtils.BuiltinManager, x: Any, name: String?): Boolean {
            return (x is net.starlark.java.eval.Structure && (x as net.starlark.java.eval.Structure).getValue(name) != null)
                    || manager.getAnnotatedMethods(x.getClass()).containsKey(name)
        }

        /**
         * Returns the named field or method of value `x`, as if by the Starlark expression `getattr(x, name, defaultValue)`. If the value has no such attribute, getattr returns `defaultValue` if non-null, or throws an EvalException otherwise.
         */
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun getattr(
            mu: net.starlark.java.eval.Mutability?,
            semantics: net.starlark.java.eval.StarlarkSemantics,
            x: Any,
            name: String?,
            defaultValue: Any?
        ): Any? {
            return net.starlark.java.eval.Starlark.Companion.getattr(
                mu,
                semantics,
                net.starlark.java.eval.CallUtils.getBuiltinManager(semantics),
                x,
                name,
                defaultValue
            )
        }

        /**
         * Optimized version of [.getattr]
         * that avoids a map lookup for the [BuiltinManager].
         */
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun getattr(thread: net.starlark.java.eval.StarlarkThread, x: Any, name: String?, defaultValue: Any?): Any? {
            return net.starlark.java.eval.Starlark.Companion.getattr(
                thread.mutability(),
                thread.getSemantics(),
                thread.getBuiltinManager(),
                x,
                name,
                defaultValue
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        private fun getattr(
            mu: net.starlark.java.eval.Mutability?,
            semantics: net.starlark.java.eval.StarlarkSemantics?,
            manager: net.starlark.java.eval.CallUtils.BuiltinManager,
            x: Any,
            name: String?,
            defaultValue: Any?
        ): Any? {
            // StarlarkMethod-annotated field or method?
            val method: net.starlark.java.eval.MethodDescriptor? = manager.getAnnotatedMethods(x.getClass()).get(name)
            if (method != null) {
                if (method.isStructField()) {
                    return method.callField(x, semantics, mu)
                } else {
                    return net.starlark.java.eval.BuiltinFunction.Companion.of(x, method)
                }
            }

            // user-defined field?
            if (x is net.starlark.java.eval.Structure) {
                val field: Any? = x.getValue(semantics, name)
                if (field != null) {
                    return net.starlark.java.eval.Starlark.Companion.checkValid<Any?>(field)
                }

                if (defaultValue != null) {
                    return defaultValue
                }

                val error: String? = x.getErrorMessageForUnknownField(name)
                if (error != null) {
                    throw net.starlark.java.eval.Starlark.Companion.errorf("%s", error)
                }
            } else if (defaultValue != null) {
                return defaultValue
            }

            throw net.starlark.java.eval.Starlark.Companion.errorf(
                "'%s' value has no field or method '%s'%s",
                net.starlark.java.eval.Starlark.Companion.type(x),
                name,
                net.starlark.java.spelling.SpellChecker.didYouMean(
                    name,
                    net.starlark.java.eval.Starlark.Companion.dir(mu, manager, x)
                )
            )
        }

        /**
         * Returns a new sorted list containing the names of the Starlark-accessible fields and methods of
         * the specified value, as if by the Starlark expression `dir(x)`.
         */
        fun dir(
            mu: net.starlark.java.eval.Mutability?,
            semantics: net.starlark.java.eval.StarlarkSemantics,
            x: Any
        ): net.starlark.java.eval.StarlarkList<String?>? {
            return net.starlark.java.eval.Starlark.Companion.dir(
                mu,
                net.starlark.java.eval.CallUtils.getBuiltinManager(semantics),
                x
            )
        }

        /**
         * Optimized version of [.dir] that avoids a map
         * lookup for the [BuiltinManager].
         */
        fun dir(thread: net.starlark.java.eval.StarlarkThread, x: Any): net.starlark.java.eval.StarlarkList<String?>? {
            return net.starlark.java.eval.Starlark.Companion.dir(thread.mutability(), thread.getBuiltinManager(), x)
        }

        private fun dir(
            mu: net.starlark.java.eval.Mutability?, manager: net.starlark.java.eval.CallUtils.BuiltinManager, x: Any
        ): net.starlark.java.eval.StarlarkList<String?>? {
            // Order the fields alphabetically.
            val fields: MutableSet<String?> = TreeSet<String?>()
            if (x is net.starlark.java.eval.Structure) {
                fields.addAll((x as net.starlark.java.eval.Structure).getFieldNames())
            }
            fields.addAll(manager.getAnnotatedMethods(x.getClass()).keySet())
            return net.starlark.java.eval.StarlarkList.Companion.copyOf<String?>(mu, fields)
        }

        // --- methods related to StarlarkBuiltin-annotated classes ---
        /**
         * Returns a map of Java methods and corresponding StarlarkMethod annotations for each annotated
         * Java method of the specified class. Elements are ordered by Java method name, which is not
         * necessarily the same as the Starlark attribute name. The set of enabled methods is determined
         * by [StarlarkSemantics.DEFAULT]. Excludes the `selfCall` method, if any.
         * 
         * 
         * Most callers should use [.dir] and [.getattr] instead.
         */
        // TODO(adonovan): move to StarlarkAnnotations; it's a static property of the annotations.
        fun getMethodAnnotations(clazz: java.lang.Class<*>?): com.google.common.collect.ImmutableMap<java.lang.reflect.Method?, net.starlark.java.annot.StarlarkMethod?> {
            val result: com.google.common.collect.ImmutableMap.Builder<java.lang.reflect.Method?, net.starlark.java.annot.StarlarkMethod?> =
                com.google.common.collect.ImmutableMap.builder<java.lang.reflect.Method?, net.starlark.java.annot.StarlarkMethod?>()
            for (desc in net.starlark.java.eval.CallUtils.getBuiltinManager(net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT)
                .getAnnotatedMethods(clazz)
                .values()) {
                result.put(desc.getMethod(), desc.getAnnotation())
            }
            return result.build()
        }

        /**
         * Returns the `StarlarkMethod(selfCall=true)`-annotated Java method of the specified Java
         * class that is called when Starlark calls an instance of that class like a function. It returns
         * null if no such method exists.
         */
        fun getSelfCallMethod(
            semantics: net.starlark.java.eval.StarlarkSemantics,
            clazz: java.lang.Class<*>?
        ): java.lang.reflect.Method? {
            return net.starlark.java.eval.CallUtils.getBuiltinManager(semantics).getSelfCallMethod(clazz)
        }

        /**
         * Adds to the environment `env` all Starlark methods of value `v`, filtered by the
         * given semantics. Starlark methods are Java methods of `v` with a [StarlarkMethod]
         * annotation whose `structField` and `selfCall` flags are both false.
         * 
         * @throws IllegalArgumentException if any method annotation's [StarlarkMethod.structField]
         * flag is true.
         */
        /** Equivalent to `addMethods(env, v, StarlarkSemantics.DEFAULT)`.  */
        @kotlin.jvm.JvmOverloads
        fun addMethods(
            env: com.google.common.collect.ImmutableMap.Builder<String?, Any?>,
            v: Any,
            semantics: net.starlark.java.eval.StarlarkSemantics = net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
        ) {
            val cls: java.lang.Class<*> = v.getClass()
            // TODO(adonovan): rather than silently skip the selfCall method, reject it.
            for (e in net.starlark.java.eval.CallUtils.getBuiltinManager(semantics).getAnnotatedMethods(cls)
                .entrySet()) {
                val name: String? = e.getKey()

                // We cannot accept fields, as they are inherently problematic:
                // what if the Java method call fails, or gets interrupted?
                require(
                    !e.getValue().isStructField()
                ) { java.lang.String.format("addMethods(%s): method %s has structField=true", cls.getName(), name) }

                env.put(name, net.starlark.java.eval.BuiltinFunction.Companion.of(v, e.getValue()))
            }
        }

        /**
         * Tags a program with static type information and performs static type checking, if enabled by
         * the given semantics; no-op otherwise.
         * 
         * @return the program with a type table attached if any form of type checking was enabled by
         * `semantics`; or the original program otherwise.
         * @throws SyntaxError.Exception if there were type tagging or static type checker errors.
         */
        @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
        fun maybeWithTypeInfo(
            prog: net.starlark.java.syntax.Program,
            module: net.starlark.java.eval.Module?,
            semantics: net.starlark.java.eval.StarlarkSemantics,
            loader: net.starlark.java.syntax.TypeTagger.Loader?
        ): net.starlark.java.syntax.Program {
            val staticTypeChecking: Boolean =
                semantics.getBool(net.starlark.java.eval.StarlarkSemantics.Companion.EXPERIMENTAL_STARLARK_STATIC_TYPE_CHECKING)
            val dynamicTypeChecking: Boolean =
                semantics.getBool(net.starlark.java.eval.StarlarkSemantics.Companion.EXPERIMENTAL_STARLARK_DYNAMIC_TYPE_CHECKING)
            if (staticTypeChecking || dynamicTypeChecking) {
                return net.starlark.java.eval.Starlark.Companion.withTypeInfo(prog, module, staticTypeChecking, loader)
            } else {
                return prog
            }
        }

        /**
         * Tags a program with static type information and (if `staticTypeChecking` is requested)
         * performs static type checking.
         * 
         * 
         * This is the unconditionally-type-tagging version of [.maybeWithTypeInfo].
         * 
         * @return the program with a type table attached
         * @throws SyntaxError.Exception if there were type tagging or static type checker errors.
         */
        @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
        fun withTypeInfo(
            prog: net.starlark.java.syntax.Program,
            module: net.starlark.java.eval.Module?,
            staticTypeChecking: Boolean,
            loader: net.starlark.java.syntax.TypeTagger.Loader?
        ): net.starlark.java.syntax.Program {
            val typeTable: net.starlark.java.syntax.TypeTable =
                net.starlark.java.syntax.TypeTagger.tagProgram(prog, module, loader)
            if (typeTable.ok() && staticTypeChecking) {
                net.starlark.java.syntax.TypeChecker.checkProgram(prog, typeTable, module)
            }
            if (!typeTable.ok()) {
                throw net.starlark.java.syntax.SyntaxError.Exception(typeTable.errors())
            }
            return prog.withTypeTable(typeTable)
        }

        /**
         * Parses the input as a file, resolves it in the specified module environment, compiles it, and
         * executes it in the specified thread. On success it returns None, unless the file's final
         * statement is an expression, in which case its value is returned.
         * 
         * @throws SyntaxError.Exception if there were (static) scanner, parser, resolver, type tagger, or
         * static type checker errors.
         * @throws EvalException if there was a (dynamic) evaluation error.
         * @throws InterruptedException if the Java thread was interrupted during evaluation.
         */
        @Throws(
            net.starlark.java.syntax.SyntaxError.Exception::class,
            net.starlark.java.eval.EvalException::class,
            java.lang.InterruptedException::class
        )
        fun execFile(
            input: net.starlark.java.syntax.ParserInput?,
            options: net.starlark.java.syntax.FileOptions?,
            module: net.starlark.java.eval.Module,
            thread: net.starlark.java.eval.StarlarkThread
        ): Any? {
            val file: net.starlark.java.syntax.StarlarkFile =
                net.starlark.java.syntax.StarlarkFile.parse(input, options)
            val prog: net.starlark.java.syntax.Program =
                net.starlark.java.eval.Starlark.Companion.maybeWithTypeInfo(
                    net.starlark.java.syntax.Program.compileFile(file, module),
                    module,
                    thread.getSemantics(),
                    thread.getLoader()
                )
            return net.starlark.java.eval.Starlark.Companion.execFileProgram(prog, module, thread)
        }

        /** Variant of [.execFile] that creates a module for the given predeclared environment.  */ // TODO(adonovan): is this needed?
        @Throws(
            net.starlark.java.syntax.SyntaxError.Exception::class,
            net.starlark.java.eval.EvalException::class,
            java.lang.InterruptedException::class
        )
        fun execFile(
            input: net.starlark.java.syntax.ParserInput?,
            options: net.starlark.java.syntax.FileOptions?,
            predeclared: MutableMap<String?, Any?>?,
            thread: net.starlark.java.eval.StarlarkThread
        ): Any? {
            val module: net.starlark.java.eval.Module =
                net.starlark.java.eval.Module.Companion.withPredeclared(thread.getSemantics(), predeclared)
            return net.starlark.java.eval.Starlark.Companion.execFile(input, options, module, thread)
        }

        /**
         * Executes a compiled Starlark file (as obtained from [Program.compileFile]) in the given
         * StarlarkThread. On success it returns None, unless the file's final statement is an expression,
         * in which case its value is returned.
         * 
         * 
         * This method does not perform type tagging or static type checking. If type tagging or type
         * checking is needed, first use [.withTypeInfo] to obtain a type-tagged/checked version of
         * `prog`.
         * 
         * @throws EvalException if there was a (dynamic) evaluation error.
         * @throws InterruptedException if the Java thread was interrupted during evaluation.
         */
        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        fun execFileProgram(
            prog: net.starlark.java.syntax.Program,
            module: net.starlark.java.eval.Module,
            thread: net.starlark.java.eval.StarlarkThread
        ): Any? {
            val rfn: net.starlark.java.syntax.Resolver.Function = prog.getResolvedFunction()

            // A given Module may be passed to execFileProgram multiple times in sequence,
            // for different compiled Programs. (This happens in the REPL, and in
            // EvaluationTestCase scenarios. It is not true of the go.starlark.net
            // implementation, and it complicates things significantly.
            // It would be nice to stop doing that.)
            //
            // Therefore StarlarkFunctions from different Programs (files) but initializing
            // the same Module need different mappings from the Program's numbering of
            // globals to the Module's numbering of globals, and to access a global requires
            // two array lookups.
            val globalIndex: IntArray = module.getIndicesOfGlobals(rfn.getGlobals())

            if (module.getDocumentation() == null) {
                val documentation: String? = rfn.getDocumentation()
                if (documentation != null) {
                    module.setDocumentation(net.starlark.java.eval.Starlark.Companion.trimDocString(documentation))
                }
            }

            val toplevel: net.starlark.java.eval.StarlarkFunction =
                net.starlark.java.eval.StarlarkFunction(
                    rfn,
                    prog.getTypeTable(),
                    module,
                    globalIndex,  /* defaultValues= */
                    net.starlark.java.eval.Tuple.Companion.empty(),  /* freevars= */
                    net.starlark.java.eval.Tuple.Companion.empty(),
                    thread.getNextIdentityToken()
                )
            val result: Any? = net.starlark.java.eval.Starlark.Companion.positionalOnlyCall(thread, toplevel)
            if (prog.getTypeTable() != null) {
                // For globals that don't have a declared static type, we export the value's dynamic type.
                // We export the dynamic type of the value (rather than the inferred static type) because it's
                // likely to be more useful to users who load() this module; they would want to type-check
                // on the real set of fields of a Bazel struct or provider, or the real named args to a rule
                // or macro. A module can annotate a global with a wider type to avoid exposing the dynamic
                // type as part of its API.
                //
                // Exporting the dynamic type does result in one wart: the exported type might not be a
                // subtype of the inferred static type, due to the invariance rule for mutable collections.
                // For example, we might statically infer global X to be list[int|float] and export its
                // value's dynamic type as list[int] - but list[int] is not a subtype of list[int|float].
                // Since the exported values are frozen, it may be possible to fix this wart by introducing
                // frozenlist, frozendict, etc.
                // TODO: #27370 - Ensure this mechanism works for REPL.
                for (i in globalIndex) {
                    val value: Any? = module.getGlobalByIndex(i)
                    if (value != null && module.getGlobalTypeByIndex(i) == null) {
                        module.setGlobalTypeByIndex(
                            i,
                            net.starlark.java.eval.Starlark.Companion.getStarlarkType(value, thread.getSemantics())
                        )
                    }
                }
            }
            return result
        }

        /**
         * Parses the input as an expression, resolves it in the specified module environment, compiles
         * it, evaluates it, and returns its value.
         * 
         * @throws SyntaxError.Exception if there were (static) scanner, parser, resolver, type tagger, or
         * static type checker errors.
         * @throws EvalException if there was a (dynamic) evaluation error.
         * @throws InterruptedException if the Java thread was interrupted during evaluation.
         */
        @Throws(
            net.starlark.java.syntax.SyntaxError.Exception::class,
            net.starlark.java.eval.EvalException::class,
            java.lang.InterruptedException::class
        )
        fun eval(
            input: net.starlark.java.syntax.ParserInput?,
            options: net.starlark.java.syntax.FileOptions?,
            module: net.starlark.java.eval.Module,
            thread: net.starlark.java.eval.StarlarkThread
        ): Any? {
            val fn: net.starlark.java.eval.StarlarkFunction =
                net.starlark.java.eval.Starlark.Companion.newExprFunction(
                    input, options, module, thread.getSemantics(), thread.getNextIdentityToken()
                )
            return net.starlark.java.eval.Starlark.Companion.positionalOnlyCall(thread, fn)
        }

        /** Variant of [.eval] that creates a module for the given predeclared environment.  */ // TODO(adonovan): is this needed?
        @Throws(
            net.starlark.java.syntax.SyntaxError.Exception::class,
            net.starlark.java.eval.EvalException::class,
            java.lang.InterruptedException::class
        )
        fun eval(
            input: net.starlark.java.syntax.ParserInput?,
            options: net.starlark.java.syntax.FileOptions?,
            predeclared: MutableMap<String?, Any?>?,
            thread: net.starlark.java.eval.StarlarkThread
        ): Any? {
            val module: net.starlark.java.eval.Module =
                net.starlark.java.eval.Module.Companion.withPredeclared(thread.getSemantics(), predeclared)
            return net.starlark.java.eval.Starlark.Companion.eval(input, options, module, thread)
        }

        /**
         * Parses the input as an expression, resolves it in the specified module environment, and returns
         * a callable no-argument Starlark function value that computes and returns the value of the
         * expression.
         * 
         * @throws SyntaxError.Exception if there were scanner, parser, resolver, type tagger, or static
         * type checker errors.
         */
        @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
        private fun newExprFunction(
            input: net.starlark.java.syntax.ParserInput?,
            options: net.starlark.java.syntax.FileOptions?,
            module: net.starlark.java.eval.Module,
            semantics: net.starlark.java.eval.StarlarkSemantics,
            referenceIdentity: net.starlark.java.eval.SymbolGenerator.Symbol<*>?
        ): net.starlark.java.eval.StarlarkFunction {
            val expr: net.starlark.java.syntax.Expression? = net.starlark.java.syntax.Expression.parse(input)
            var prog: net.starlark.java.syntax.Program =
                net.starlark.java.syntax.Program.compileExpr(expr, module, options)
            // loader is null because expressions cannot contain load statements
            prog =
                net.starlark.java.eval.Starlark.Companion.maybeWithTypeInfo(prog, module, semantics,  /* loader= */null)
            val rfn: net.starlark.java.syntax.Resolver.Function = prog.getResolvedFunction()
            val globalIndex: IntArray = module.getIndicesOfGlobals(rfn.getGlobals()) // see execFileProgram
            return net.starlark.java.eval.StarlarkFunction(
                rfn,
                prog.getTypeTable(),
                module,
                globalIndex,  /* defaultValues= */
                net.starlark.java.eval.Tuple.Companion.empty(),  /* freevars= */
                net.starlark.java.eval.Tuple.Companion.empty(),
                referenceIdentity
            )
        }

        /**
         * Starts the CPU profiler with the specified sampling period, writing a pprof profile to `out`. All running Starlark threads are profiled. May be called concurrent with Starlark
         * execution.
         * 
         * @throws IllegalStateException exception if the Starlark profiler is already running or if the
         * operating system's profiling resources for this process are already in use.
         */
        fun startCpuProfile(out: java.io.OutputStream?, period: java.time.Duration?): Boolean {
            return net.starlark.java.eval.CpuProfiler.Companion.start(out, period)
        }

        /**
         * Stops the profiler and waits for the log to be written. Throws an unchecked exception if the
         * profiler was not already started by a prior call to [.startCpuProfile].
         */
        @Throws(IOException::class)
        fun stopCpuProfile() {
            net.starlark.java.eval.CpuProfiler.Companion.stop()
        }
    }
}
