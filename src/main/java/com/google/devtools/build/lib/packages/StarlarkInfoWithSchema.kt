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
package com.google.devtools.build.lib.packages

/**
 * A struct-like Info (provider instance) for providers defined in Starlark that have a schema.
 * 
 * 
 * Maintainer's note: This class is memory-optimized in a way that can cause profiling
 * instability in some pathological cases. See [StarlarkProvider.optimizeField] for more
 * information.
 * 
 * 
 * Schemas with <= 5 fields (covering the majority of provider types in practice) each have their
 * own dedicated subclass to optimize for memory by forgoing an array.
 */
abstract class StarlarkInfoWithSchema private constructor(provider: StarlarkProvider) : StarlarkInfo() {
    private val provider: StarlarkProvider

    init {
        this.provider = provider
    }

    override fun getProvider(): com.google.devtools.build.lib.packages.Provider {
        return provider
    }

    @com.google.errorprone.annotations.ForOverride
    abstract fun getValueAt(i: Int): Any?

    @com.google.errorprone.annotations.ForOverride
    abstract fun setValueAt(i: Int, `val`: Any?)

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    open fun getValuesForSerialization(): Array<Any?> {
        val n: Int = provider.getFields().size()
        val table = arrayOfNulls<Any>(n)
        for (i in 0..<n) {
            table[i] = getValueAt(i)
        }
        return table
    }

    /**
     * Constructs a StarlarkInfo with calls forwarded from one of the StarlarkInfo ArgumentProcessor
     * implementations. Checks that each key is provided at most once, and is defined by the schema,
     * which must be sorted. This class exists solely for the StarlarkInfo ArgumentProcessors.
     */
    internal class StarlarkInfoFactory(provider: StarlarkProvider, thread: net.starlark.java.eval.StarlarkThread?) :
        com.google.devtools.build.lib.packages.StarlarkProvider.StarlarkInfoFactory(provider, thread) {
        private val fields: com.google.common.collect.ImmutableList<String?>?
        private val valueTable: Array<Any?>
        private var unexpected: MutableList<String?>?

        init {
            this.fields = provider.getFields()
            this.valueTable = arrayOfNulls<Any>(fields.size())
            this.unexpected = null
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        public override fun addNamedArg(name: String?, value: Any?) {
            val pos = indexOfField(name, fields)
            if (pos >= 0) {
                if (valueTable[pos] != null) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "got multiple values for parameter %s in call to instantiate provider %s",
                        name, provider.getPrintableName()
                    )
                }
                valueTable[pos] = provider.optimizeField(pos, value)
            } else {
                if (unexpected == null) {
                    unexpected = java.util.ArrayList<String?>()
                }
                unexpected!!.add(name)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        public override fun createFromArgs(): StarlarkInfoWithSchema {
            if (unexpected != null) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "got unexpected field%s '%s' in call to instantiate provider %s",
                    if (unexpected.size() > 1) "s" else "",
                    com.google.common.base.Joiner.on("', '").join(unexpected),
                    provider.getPrintableName()
                )
            }
            return create(provider, valueTable)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        public override fun createFromMap(map: MutableMap<String?, Any?>): StarlarkInfo {
            for (e in map.entrySet()) {
                addNamedArg(e.getKey(), e.getValue())
            }
            return createFromArgs()
        }
    }

    override fun getFieldNames(): com.google.common.collect.ImmutableList<String?> {
        val fieldNames: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.Builder<String?>()
        val fields: com.google.common.collect.ImmutableList<String?>? = provider.getFields()
        for (i in fields.indices) {
            if (getValueAt(i) != null) {
                fieldNames.add(fields.get(i))
            }
        }
        return fieldNames.build()
    }

    override fun isImmutable(): Boolean {
        // If the provider is not yet exported, the hash code of the object is subject to change.
        if (!provider.isExported()) {
            return false
        }
        val n: Int = provider.getFields().size()
        for (i in 0..<n) {
            val `val` = getValueAt(i)
            if (`val` != null
                && !(provider.isOptimised(i, `val`) // optimised fields might not be Starlark values
                        || net.starlark.java.eval.Starlark.isImmutable(`val`))
            ) {
                return false
            }
        }
        return true
    }

    override fun getValue(name: String?): Any? {
        val fields: com.google.common.collect.ImmutableList<String?>? = provider.getFields()
        val i = indexOfField(name, fields)
        return if (i >= 0) provider.retrieveOptimizedField(i, getValueAt(i)) else null
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun binaryOp(
        op: net.starlark.java.syntax.TokenKind?,
        that: Any?,
        thisLeft: Boolean
    ): StarlarkInfoWithSchema? {
        if (op == net.starlark.java.syntax.TokenKind.PLUS && that is StarlarkInfo) {
            val thatProvider: com.google.devtools.build.lib.packages.Provider = that.getProvider()
            if (provider != thatProvider) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "Cannot use '+' operator on instances of different providers (%s and %s)",
                    provider.getPrintableName(), thatProvider.getPrintableName()
                )
            }
            com.google.common.base.Preconditions.checkArgument(that is StarlarkInfoWithSchema, that)
            return if (thisLeft)
                plus(this, that as StarlarkInfoWithSchema) //
            else
                plus(that as StarlarkInfoWithSchema, this)
        }
        return null
    }

    override fun unsafeOptimizeMemoryLayout(): StarlarkInfoWithSchema {
        var sawTruthyValue = false
        val n: Int = provider.getFields().size()
        for (i in 0..<n) {
            val `val` = getValueAt(i)
            sawTruthyValue = sawTruthyValue || truth(`val`)
            if (`val` is net.starlark.java.eval.Compactable) {
                setValueAt(i, `val`.unsafeOptimizeMemoryLayout())
            }
        }
        return if (sawTruthyValue) this else nonTruthyInterner.intern(this)
    }

    /** For providers with no fields.  */
    private class Schema0(provider: StarlarkProvider) : StarlarkInfoWithSchema(provider) {
        override fun getValueAt(i: Int): Any? {
            throw java.lang.IndexOutOfBoundsException(i)
        }

        override fun setValueAt(i: Int, `val`: Any?) {
            throw java.lang.IndexOutOfBoundsException(i)
        }

        override fun hashCode(): Int {
            return 31 * getProvider().hashCode() + 1
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Schema0) {
                return false
            }
            return getProvider() == o.getProvider()
        }
    }

    /** For providers with 1 field.  */
    private class Schema1(provider: StarlarkProvider, v0: Any?) : StarlarkInfoWithSchema(provider) {
        private var v0: Any?

        init {
            this.v0 = v0
        }

        override fun getValueAt(i: Int): Any? {
            if (i == 0) {
                return v0
            }
            throw java.lang.IndexOutOfBoundsException(i)
        }

        override fun setValueAt(i: Int, `val`: Any?) {
            if (i == 0) {
                this.v0 = `val`
                return
            }
            throw java.lang.IndexOutOfBoundsException(i)
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(getProvider(), v0)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Schema1) {
                return false
            }
            return getProvider() == o.getProvider() && v0 == o.v0
        }
    }

    /** For providers with 2 fields.  */
    private class Schema2(provider: StarlarkProvider, v0: Any?, v1: Any?) : StarlarkInfoWithSchema(provider) {
        private var v0: Any?
        private var v1: Any?

        init {
            this.v0 = v0
            this.v1 = v1
        }

        override fun getValueAt(i: Int): Any? {
            return when (i) {
                0 -> v0
                1 -> v1
                else -> throw java.lang.IndexOutOfBoundsException(i)
            }
        }

        override fun setValueAt(i: Int, `val`: Any?) {
            when (i) {
                0 -> this.v0 = `val`
                1 -> this.v1 = `val`
                else -> throw java.lang.IndexOutOfBoundsException(i)
            }
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(getProvider(), v0, v1)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Schema2) {
                return false
            }
            return getProvider() == o.getProvider()
                    && v0 == o.v0
                    && v1 == o.v1
        }
    }

    /** For providers with 3 fields.  */
    private class Schema3(provider: StarlarkProvider, v0: Any?, v1: Any?, v2: Any?) : StarlarkInfoWithSchema(provider) {
        private var v0: Any?
        private var v1: Any?
        private var v2: Any?

        init {
            this.v0 = v0
            this.v1 = v1
            this.v2 = v2
        }

        override fun getValueAt(i: Int): Any? {
            return when (i) {
                0 -> v0
                1 -> v1
                2 -> v2
                else -> throw java.lang.IndexOutOfBoundsException(i)
            }
        }

        override fun setValueAt(i: Int, `val`: Any?) {
            when (i) {
                0 -> this.v0 = `val`
                1 -> this.v1 = `val`
                2 -> this.v2 = `val`
                else -> throw java.lang.IndexOutOfBoundsException(i)
            }
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(getProvider(), v0, v1, v2)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Schema3) {
                return false
            }
            return getProvider() == o.getProvider()
                    && v0 == o.v0
                    && v1 == o.v1
                    && v2 == o.v2
        }
    }

    /** For providers with 4 fields.  */
    private class Schema4(provider: StarlarkProvider, v0: Any?, v1: Any?, v2: Any?, v3: Any?) :
        StarlarkInfoWithSchema(provider) {
        private var v0: Any?
        private var v1: Any?
        private var v2: Any?
        private var v3: Any?

        init {
            this.v0 = v0
            this.v1 = v1
            this.v2 = v2
            this.v3 = v3
        }

        override fun getValueAt(i: Int): Any? {
            return when (i) {
                0 -> v0
                1 -> v1
                2 -> v2
                3 -> v3
                else -> throw java.lang.IndexOutOfBoundsException(i)
            }
        }

        override fun setValueAt(i: Int, `val`: Any?) {
            when (i) {
                0 -> this.v0 = `val`
                1 -> this.v1 = `val`
                2 -> this.v2 = `val`
                3 -> this.v3 = `val`
                else -> throw java.lang.IndexOutOfBoundsException(i)
            }
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(getProvider(), v0, v1, v2, v3)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Schema4) {
                return false
            }
            return getProvider() == o.getProvider()
                    && v0 == o.v0
                    && v1 == o.v1
                    && v2 == o.v2
                    && v3 == o.v3
        }
    }

    /** For providers with 5 fields.  */
    private class Schema5(provider: StarlarkProvider, v0: Any?, v1: Any?, v2: Any?, v3: Any?, v4: Any?) :
        StarlarkInfoWithSchema(provider) {
        private var v0: Any?
        private var v1: Any?
        private var v2: Any?
        private var v3: Any?
        private var v4: Any?

        init {
            this.v0 = v0
            this.v1 = v1
            this.v2 = v2
            this.v3 = v3
            this.v4 = v4
        }

        override fun getValueAt(i: Int): Any? {
            return when (i) {
                0 -> v0
                1 -> v1
                2 -> v2
                3 -> v3
                4 -> v4
                else -> throw java.lang.IndexOutOfBoundsException(i)
            }
        }

        override fun setValueAt(i: Int, `val`: Any?) {
            when (i) {
                0 -> this.v0 = `val`
                1 -> this.v1 = `val`
                2 -> this.v2 = `val`
                3 -> this.v3 = `val`
                4 -> this.v4 = `val`
                else -> throw java.lang.IndexOutOfBoundsException(i)
            }
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(getProvider(), v0, v1, v2, v3, v4)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Schema5) {
                return false
            }
            return getProvider() == o.getProvider()
                    && v0 == o.v0
                    && v1 == o.v1
                    && v2 == o.v2
                    && v3 == o.v3
                    && v4 == o.v4
        }
    }

    /** For providers with 6 or more fields.  */
    private class SchemaN(provider: StarlarkProvider, vs: Array<Any?>) : StarlarkInfoWithSchema(provider) {
        private val vs: Array<Any?>

        init {
            this.vs = vs
        }

        override fun getValueAt(i: Int): Any? {
            return vs[i]
        }

        override fun setValueAt(i: Int, `val`: Any?) {
            vs[i] = `val`
        }

        override fun getValuesForSerialization(): Array<Any?> {
            return vs
        }

        override fun hashCode(): Int {
            return 31 * getProvider().hashCode() + java.util.Arrays.hashCode(vs)
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is SchemaN) {
                return false
            }
            return getProvider() == o.getProvider() && java.util.Arrays.equals(vs, o.vs)
        }
    }

    companion object {
        /**
         * Interner for instances that have no [truthy][Starlark.truth] values.
         * 
         * 
         * Interning is limited to instances without truthy values for two reasons:
         * 
         * 
         *  1. This covers the most frequent category of duplicates in practice. Interning further may
         * not be worth the cost.
         *  1. Hashing truthy values can be arbitrarily expensive and potentially even dangerous due to
         * the possibility of object graph cycles.
         * 
         */
        private val nonTruthyInterner: com.google.common.collect.Interner<StarlarkInfoWithSchema?> =
            BlazeInterners.newWeakInterner()

        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        fun create(provider: StarlarkProvider, vs: Array<Any?>): StarlarkInfoWithSchema {
            return when (vs.length) {
                0 -> Schema0(provider)
                1 -> Schema1(provider, vs[0])
                2 -> Schema2(provider, vs[0], vs[1])
                3 -> Schema3(provider, vs[0], vs[1], vs[2])
                4 -> Schema4(provider, vs[0], vs[1], vs[2], vs[3])
                5 -> Schema5(provider, vs[0], vs[1], vs[2], vs[3], vs[4])
                else -> SchemaN(provider, vs)
            }
        }

        fun newStarlarkInfoFactory(
            provider: StarlarkProvider, thread: net.starlark.java.eval.StarlarkThread?
        ): com.google.devtools.build.lib.packages.StarlarkProvider.StarlarkInfoFactory {
            return com.google.devtools.build.lib.packages.StarlarkInfoWithSchema.StarlarkInfoFactory(provider, thread)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun plus(x: StarlarkInfoWithSchema, y: StarlarkInfoWithSchema): StarlarkInfoWithSchema {
            val n: Int = x.provider.getFields().size()

            val ztable = arrayOfNulls<Any>(n)
            for (i in 0..<n) {
                val xVal = x.getValueAt(i)
                val yVal = y.getValueAt(i)
                if (xVal != null && yVal != null) {
                    val schema: com.google.common.collect.ImmutableList<String?> = x.provider.getFields()
                    throw net.starlark.java.eval.Starlark.errorf(
                        "cannot add struct instances with common field '%s'",
                        schema.get(i)
                    )
                }
                ztable[i] = if (xVal != null) xVal else yVal
            }
            return create(x.provider, ztable)
        }

        /** Returns the index of the given named field in the given list of fields, or -1 if not found.  */
        private fun indexOfField(name: String?, fields: com.google.common.collect.ImmutableList<String?>): Int {
            if (fields.size() <= StarlarkInfo.Companion.BINARY_SEARCH_THRESHOLD) {
                return fields.indexOf(name)
            }
            val idx: Int = Collections.binarySearch<String?>(fields, name)
            return if (idx >= 0) idx else -1
        }

        /**
         * Augmented version of [Starlark.truth] that handles [NestedSet] and `null`.
         */
        private fun truth(`val`: Any?): Boolean {
            return when (`val`) {
                -> !nestedSet.isEmpty()
                null -> false
                else -> net.starlark.java.eval.Starlark.truth(`val`)
            }
        }
    }
}
