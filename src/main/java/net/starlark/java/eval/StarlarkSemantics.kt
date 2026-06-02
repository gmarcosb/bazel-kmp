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
//
package net.starlark.java.eval

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.TreeMap

/**
 * A StarlarkSemantics is an immutable set of optional name/value pairs that affect the dynamic
 * behavior of Starlark operators and built-in functions, both core and application-defined.
 * 
 * 
 * For extensibility, a StarlarkSemantics only records a name/value pair when the value differs
 * from the default value appropriate to that name. Values of most types are accessed using a [ ], which defines the name, type, and default value for an entry. Boolean values are accessed
 * using a string key; the string must have the prefix "+" or "-", indicating the default value: +
 * for true, - for false. The reason for the special treatment of boolean entries is that they may
 * enable or disable methods and parameters in the StarlarkMethod annotation system, and it is not
 * possible to refer to a Key from a Java annotation, only a string.
 * 
 * 
 * It is the client's responsibility to ensure that a StarlarkSemantics does not encounter
 * multiple Keys of the same name but different value types.
 * 
 * 
 * For Bazel's semantics options, see [packages.semantics.BuildLanguageOptions].
 * 
 * 
 * For options that affect the static behavior of the Starlark frontend (lexer, parser,
 * validator, compiler), see [FileOptions].
 */
class StarlarkSemantics private constructor(map: com.google.common.collect.ImmutableMap<String?, Any?>) {
    // A map entry must be accessed by Key iff its name has no [+-] prefix.
    // Key<Boolean> is permitted too.
    // The map keys are sorted but we avoid ImmutableSortedMap due to observed inefficiency.
    private val map: com.google.common.collect.ImmutableMap<String?, Any?>
    private val hashCode: Int

    protected constructor(otherSemantics: StarlarkSemantics) : this(otherSemantics.map)

    /** Returns the value of a boolean option, which must have a [+-] prefix.  */
    fun getBool(name: String): Boolean {
        val prefix: Char = name.charAt(0)
        com.google.common.base.Preconditions.checkArgument(prefix == '+' || prefix == '-')
        val defaultValue = prefix == '+'
        val v = map.get(name) as Boolean? // prefix => cast cannot fail
        return if (v != null) v else defaultValue
    }

    /** Returns the value of the option denoted by `key`.  */
    fun <T> get(key: Key<T?>): T? {
        val v// safe, if Key.names are unique
                = map.get(key.name) as T?
        return if (v != null) v else key.defaultValue
    }

    // TODO(bazel-team): This exists solely for BuiltinsInternalModule#getFlag, which allows a
    // (privileged) Starlark caller to programmatically retrieve a flag's value without knowing its
    // schema and default value. Reconsider whether we should support that use case from this class.
    /**
     * Returns the value of the option with the given name, or the default value if it is not set or
     * does not exist.
     */
    fun getGeneric(name: String?, defaultValue: Any?): Any? {
        var v: Any? = map.get(name)
        // Try boolean prefixes if that didn't work.
        if (v == null) {
            v = map.get("+" + name)
        }
        if (v == null) {
            v = map.get("-" + name)
        }
        return if (v != null) v else defaultValue
    }

    /** A Key identifies an option, providing its name, type, and default value.  */
    class Key<T>(name: String, defaultValue: T?) {
        val name: String
        val defaultValue: T?

        /**
         * Constructs a key. The name must not start with [+-]. The value must not be subsequently
         * modified.
         */
        init {
            val prefix: Char = name.charAt(0)
            com.google.common.base.Preconditions.checkArgument(prefix != '-' && prefix != '+')
            this.name = name
            this.defaultValue = com.google.common.base.Preconditions.checkNotNull<T?>(defaultValue)
        }

        override fun toString(): String {
            return this.name
        }
    }

    /**
     * Returns a new builder that initially holds the same key/value pairs as this StarlarkSemantics.
     */
    fun toBuilder(): Builder {
        return net.starlark.java.eval.StarlarkSemantics.Builder(TreeMap<String?, Any?>(map))
    }

    /** A Builder is a mutable container used to construct an immutable StarlarkSemantics.  */
    class Builder private constructor(map: TreeMap<String?, Any?>) {
        private val map: TreeMap<String?, Any?>

        init {
            this.map = map
        }

        /** Sets the value for the specified key.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun <T> set(key: Key<T?>, value: T?): Builder {
            if (value != key.defaultValue) {
                map.put(key.name, value)
            } else {
                map.remove(key.name)
            }
            return this
        }

        /** Sets the value for the boolean key, which must have a [+-] prefix.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBool(name: String, value: Boolean): Builder {
            val prefix: Char = name.charAt(0)
            com.google.common.base.Preconditions.checkArgument(prefix == '+' || prefix == '-')
            val defaultValue = prefix == '+'
            if (value != defaultValue) {
                map.put(name, value)
            } else {
                map.remove(name)
            }
            return this
        }

        /** Returns an immutable StarlarkSemantics.  */
        fun build(): StarlarkSemantics {
            if (map.isEmpty()) {
                return net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
            }
            return net.starlark.java.eval.StarlarkSemantics(
                com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(
                    map
                )
            )
        }
    }

    /**
     * Returns true if a feature attached to the given toggling flags should be enabled.
     * 
     * 
     *  * If both parameters are empty, this indicates the feature is not controlled by flags, and
     * should thus be enabled.
     *  * If the `enablingFlag` parameter is non-empty, this returns true if and only if that
     * flag is true. (This represents a feature that is only on if a given flag is *on*).
     *  * If the `disablingFlag` parameter is non-empty, this returns true if and only if
     * that flag is false. (This represents a feature that is only on if a given flag is *off*).
     *  * It is illegal to pass both parameters as non-empty.
     * 
     */
    fun isFeatureEnabledBasedOnTogglingFlags(enablingFlag: String, disablingFlag: String): Boolean {
        com.google.common.base.Preconditions.checkArgument(
            enablingFlag.isEmpty() || disablingFlag.isEmpty(),
            "at least one of 'enablingFlag' or 'disablingFlag' must be empty"
        )
        if (!enablingFlag.isEmpty()) {
            return this.getBool(enablingFlag)
        } else if (!disablingFlag.isEmpty()) {
            return !this.getBool(disablingFlag)
        } else {
            return true
        }
    }

    /**
     * Returns a possibly different [StarlarkSemantics] instance that is equivalent to this one
     * for the purpose of caching the methods available on any given Starlark class.
     */
    fun getBuiltinManagerCacheKey(): StarlarkSemantics {
        return this
    }

    override fun hashCode(): Int {
        return hashCode
    }

    override fun equals(that: Any?): Boolean {
        return this === that
                || (that is StarlarkSemantics && this.map == that.map)
    }

    /**
     * Returns a representation of this StarlarkSemantics' non-default key/value pairs in key order.
     */
    override fun toString(): String {
        // Print "StarlarkSemantics{k=v, ...}", without +/- prefixes.
        val buf: java.lang.StringBuilder = java.lang.StringBuilder()
        buf.append("StarlarkSemantics{")
        var sep = ""
        for (e in map.entrySet()) {
            val key: String = e.getKey()
            buf.append(sep)
            sep = ", "
            if (key.charAt(0) == '+' || key.charAt(0) == '-') {
                buf.append(key, 1, key.length())
            } else {
                buf.append(key)
            }
            buf.append('=').append(e.getValue())
        }
        return buf.append('}').toString()
    }

    init {
        this.map = map
        this.hashCode = map.hashCode()
    }

    companion object {
        /**
         * Returns the empty semantics, in which every option has its default value.
         * 
         * 
         * *Usage note:* Usually all Starlark evaluation contexts (i.e., [StarlarkThread]s
         * or other interpreter APIs that accept a `StarlarkSemantics`) within the same application
         * should use the same semantics. Otherwise, different pieces of code -- or even the same code
         * when executed in different capacities -- could produce diverging results. It is therefore
         * generally a code smell to use the `DEFAULT` semantics rather than propagating a `StarlarkSemantics` from another context.
         */
        @kotlin.jvm.JvmField
        val DEFAULT: StarlarkSemantics =
            net.starlark.java.eval.StarlarkSemantics(com.google.common.collect.ImmutableMap.of<String?, Any?>())

        /** Returns a new empty builder.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return net.starlark.java.eval.StarlarkSemantics.Builder(TreeMap<String?, Any?>())
        }

        // -- semantics options affecting the Starlark interpreter itself --
        /** Change the behavior of 'print' statements. Used in tests to verify flag propagation.  */
        const val PRINT_TEST_MARKER: String = "-print_test_marker"

        /**
         * Whether recursive function calls are allowed. This option is not exposed to Bazel, which
         * unconditionally prohibits recursion.
         */
        const val ALLOW_RECURSION: String = "-allow_recursion"

        /** Whether StarlarkSet objects may be constructed by the interpreter.  */
        const val EXPERIMENTAL_ENABLE_STARLARK_SET: String = "+experimental_enable_starlark_set"

        /** Whether the Starlark interpreter uses UTF-8 byte strings instead of UTF-16 strings.  */
        const val INTERNAL_BAZEL_ONLY_UTF_8_BYTE_STRINGS: String = "-internal_bazel_only_utf_8_byte_strings"

        /**
         * Whether static type checking should be performed.
         * 
         * 
         * Static type checking is not really relevant to evaluation per se, but we store it here in
         * order to thread it through to [FileOptions].
         */
        const val EXPERIMENTAL_STARLARK_STATIC_TYPE_CHECKING: String = "-experimental_starlark_static_type_checking"

        /** Whether dynamic type checking should be performed.  */
        const val EXPERIMENTAL_STARLARK_DYNAMIC_TYPE_CHECKING: String = "-experimental_starlark_dynamic_type_checking"

        /** Globally Override fail(stack_trace=) to true. Flag default is false.  */
        const val FORCE_STARLARK_STACK_TRACE: String = "-force_starlark_stack_trace"
    }
}
