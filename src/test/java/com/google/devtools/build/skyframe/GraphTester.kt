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

import com.google.devtools.build.lib.events.Event

/**
 * A helper class to create graphs and run skyframe tests over these graphs.
 * 
 * 
 * There are two types of values, computing values, which may not be set to a constant value,
 * and leaf values, which must be set to a constant value and may not have any dependencies.
 * 
 * 
 * Note that the value builder looks into the test values created here to determine how to
 * behave. However, skyframe will only re-evaluate the value and call the value builder if any of
 * its dependencies has changed. That means in order to change the set of dependencies of a value,
 * you need to also change one of its previous dependencies to force re-evaluation. Changing a
 * computing value does not mark it as modified.
 */
open class GraphTester {
    /** If true, uses the [SkyframeLookupResult.queryDep] interface to retrieve values.  */
    private var useQueryDep = false

    private val functionMap: MutableMap<SkyFunctionName?, SkyFunction?> = HashMap<SkyFunctionName?, SkyFunction?>()

    private val values: MutableMap<SkyKey?, TestFunction> = HashMap<SkyKey?, TestFunction>()
    private val modifiedValues: MutableSet<SkyKey?> = LinkedHashSet<SkyKey?>()

    fun setUseQueryDep(useQueryDep: Boolean) {
        this.useQueryDep = useQueryDep
    }

    fun getOrCreate(name: String?): TestFunction {
        return getOrCreate(skyKey(name))
    }

    fun getOrCreate(key: SkyKey?): TestFunction {
        return getOrCreate(key, false)
    }

    fun getOrCreate(key: SkyKey?, markAsModified: Boolean): TestFunction {
        var result = values.get(key)
        if (result == null) {
            result = TestFunction()
            values.put(key, result)
        } else if (markAsModified) {
            modifiedValues.add(key)
        }
        return result
    }

    fun set(key: String?, value: SkyValue?): TestFunction {
        return set(skyKey(key), value)
    }

    fun set(key: SkyKey?, value: SkyValue?): TestFunction {
        return getOrCreate(key, true).setConstantValue(value)
    }

    fun getModifiedValues(): com.google.common.collect.ImmutableSet<SkyKey?> {
        return com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(modifiedValues)
    }

    fun clearModifiedValues() {
        modifiedValues.clear()
    }

    val function: SkyFunction?
        get() = object : SkyFunction() {
            @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
            public override fun compute(key: SkyKey, env: Environment): SkyValue? {
                val builder: TestFunction = values.get(key)!!
                com.google.common.base.Preconditions.checkState(builder != null, "No TestFunction for %s", key)
                if (builder.builder != null) {
                    return builder.builder.compute(key, env)
                }
                if (builder.warning != null) {
                    env.getListener().handle(Event.warn(builder.warning))
                }
                if (builder.progress != null) {
                    env.getListener().handle(Event.progress(builder.progress))
                }
                if (builder.errorEvent != null) {
                    env.getListener().handle(Event.error(builder.errorEvent))
                }
                if (builder.postable != null) {
                    env.getListener().post(builder.postable)
                }
                val deps: MutableMap<SkyKey?, SkyValue> = LinkedHashMap<SkyKey?, SkyValue>()
                var oneMissing = false
                for (dep in builder.deps) {
                    val value: SkyValue? = if (useQueryDep) getValueUsingQueryDep(
                        dep,
                        env
                    ) else getValue(dep, env)
                    if (value == null) {
                        oneMissing = true
                    } else {
                        deps.put(dep.first, value)
                    }
                    com.google.common.base.Preconditions.checkState(
                        oneMissing == env.valuesMissing(), "%s %s %s", dep, value, env.valuesMissing()
                    )
                }
                if (env.valuesMissing()) {
                    return null
                }

                if (builder.hasTransientError) {
                    throw GenericFunctionException(
                        SomeErrorException(key.toString()), Transience.TRANSIENT
                    )
                }
                if (builder.hasError) {
                    throw GenericFunctionException(
                        SomeErrorException(key.toString()), Transience.PERSISTENT
                    )
                }

                if (builder.value != null) {
                    return builder.value
                }

                if (java.lang.Thread.interrupted()) {
                    throw java.lang.InterruptedException(key.toString())
                }

                return builder.computer!!.compute(deps, env)
            }

            public override fun extractTag(skyKey: SkyKey?): String? {
                val builder: TestFunction = values.get(skyKey)!!
                if (builder.builder != null) {
                    return builder.builder.extractTag(skyKey)
                }
                return builder.tag
            }
        }

    /** A value in the testing graph that is constructed in the tester.  */
    class TestFunction {
        // TODO(bazel-team): We could use a multiset here to simulate multi-pass dependency discovery.
        private val deps: MutableSet<Pair<SkyKey?, SkyValue?>> = LinkedHashSet<Pair<SkyKey?, SkyValue?>>()
        private var value: SkyValue? = null
        private var computer: ValueComputer? = null
        private var builder: SkyFunction? = null

        private var hasTransientError = false
        private var hasError = false

        private var warning: String? = null
        private var progress: String? = null
        private var errorEvent: String? = null
        private var postable: Postable? = null

        private var tag: String? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDependency(name: String?): TestFunction {
            return addDependency(skyKey(name))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDependency(key: SkyKey?): TestFunction {
            deps.add(Pair.of(key, null))
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun removeDependency(name: String?): TestFunction {
            return removeDependency(skyKey(name))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun removeDependency(key: SkyKey?): TestFunction {
            deps.remove(Pair.< SkyKey, SkyValue > of<SkyKey?, SkyValue?>(key, null))
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addErrorDependency(key: SkyKey?, altValue: SkyValue?): TestFunction {
            deps.add(Pair.of(key, altValue))
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setConstantValue(value: SkyValue?): TestFunction {
            com.google.common.base.Preconditions.checkState(this.computer == null)
            this.value = value
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun unsetConstantValue(): TestFunction {
            this.value = null
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setComputedValue(computer: ValueComputer?): TestFunction {
            com.google.common.base.Preconditions.checkState(this.value == null)
            this.computer = computer
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun unsetComputedValue(): TestFunction {
            this.computer = null
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBuilder(builder: SkyFunction?): TestFunction {
            com.google.common.base.Preconditions.checkState(this.value == null)
            com.google.common.base.Preconditions.checkState(this.computer == null)
            com.google.common.base.Preconditions.checkState(deps.isEmpty())
            com.google.common.base.Preconditions.checkState(!hasTransientError)
            com.google.common.base.Preconditions.checkState(!hasError)
            com.google.common.base.Preconditions.checkState(warning == null)
            com.google.common.base.Preconditions.checkState(progress == null)
            com.google.common.base.Preconditions.checkState(errorEvent == null)
            com.google.common.base.Preconditions.checkState(tag == null)
            this.builder = builder
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBuilderUnconditionally(builder: SkyFunction?): TestFunction {
            this.builder = builder
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setHasTransientError(hasError: Boolean): TestFunction {
            this.hasTransientError = hasError
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setHasError(hasError: Boolean): TestFunction {
            // TODO(bazel-team): switch to an enum for hasError.
            this.hasError = hasError
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setWarning(warning: String?): TestFunction {
            this.warning = warning
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setProgress(info: String?): TestFunction {
            this.progress = info
            return this
        }

        /**
         * Sets an error message to emit as an [Event]. Does not imply that the function throws an
         * error.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setErrorEvent(error: String?): TestFunction {
            this.errorEvent = error
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setTag(tag: String?): TestFunction {
            com.google.common.base.Preconditions.checkState(builder == null)
            this.tag = tag
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPostable(postable: Postable?): TestFunction {
            this.postable = postable
            return this
        }
    }

    private inner class DelegatingFunction : SkyFunction {
        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
            return this.function.compute(skyKey, env)
        }

        public override fun extractTag(skyKey: SkyKey?): String? {
            return this.function.extractTag(skyKey)
        }
    }

    val skyFunctionMap: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>
        get() = com.google.common.collect.ImmutableMap.copyOf<SkyFunctionName?, SkyFunction?>(functionMap)

    fun putDelegateFunction(functionName: SkyFunctionName?) {
        putSkyFunction(functionName, DelegatingFunction())
    }

    fun putSkyFunction(functionName: SkyFunctionName?, function: SkyFunction?) {
        functionMap.put(functionName, function)
    }

    /** Simple value class that stores strings.  */
    open class StringValue(val value: String) : SkyValue {
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is StringValue) {
                return false
            }
            return value == o.value
        }

        override fun hashCode(): Int {
            return value.hashCode()
        }

        override fun toString(): String {
            return "StringValue: " + value
        }

        companion object {
            fun of(string: String): StringValue {
                return com.google.devtools.build.skyframe.GraphTester.StringValue(string)
            }

            fun from(skyValue: SkyValue?): StringValue? {
                assertThat(skyValue).isInstanceOf(com.google.devtools.build.skyframe.GraphTester.StringValue::class.java)
                return skyValue as StringValue?
            }
        }
    }

    /** A StringValue that is also a NotComparableSkyValue.  */
    class NotComparableStringValue(value: String) : StringValue(value), NotComparableSkyValue {
        override fun equals(o: Any?): Boolean {
            throw java.lang.UnsupportedOperationException(value + " is incomparable - what are you doing?")
        }

        override fun hashCode(): Int {
            throw java.lang.UnsupportedOperationException(value + " is incomparable - what are you doing?")
        }
    }

    /**
     * A callback interface to provide the value computation.
     */
    interface ValueComputer {
        /** This is called when all the declared dependencies exist. It may request new dependencies.  */
        @Throws(java.lang.InterruptedException::class)
        fun compute(deps: MutableMap<SkyKey?, SkyValue>?, env: SkyFunction.Environment?): SkyValue?
    }

    @VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(arg: String?) : AbstractSkyKey<String?>(arg) {
        public override fun functionName(): SkyFunctionName {
            return SkyFunctionName.FOR_TESTING
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.skyframe.GraphTester.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner()

            private fun create(arg: String?): Key {
                return com.google.devtools.build.skyframe.GraphTester.Key.Companion.interner.intern(
                    com.google.devtools.build.skyframe.GraphTester.Key(
                        arg
                    )
                )
            }

            @VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.skyframe.GraphTester.Key.Companion.interner.intern(key)
            }
        }
    }

    @VisibleForSerialization
    @AutoCodec
    internal class NonHermeticKey private constructor(arg: String?) : AbstractSkyKey<String?>(arg) {
        public override fun functionName(): SkyFunctionName? {
            return FOR_TESTING_NONHERMETIC
        }

        val skyKeyInterner: SkyKeyInterner<NonHermeticKey?>
            get() = interner

        companion object {
            private val interner: SkyKeyInterner<NonHermeticKey?> = SkyKey.newInterner()

            private fun create(arg: String?): NonHermeticKey {
                return interner.intern(NonHermeticKey(arg))
            }

            @VisibleForSerialization
            @AutoCodec.Interner
            fun intern(nonHermeticKey: NonHermeticKey?): NonHermeticKey {
                return interner.intern(nonHermeticKey)
            }
        }
    }

    init {
        functionMap.put(NODE_TYPE, DelegatingFunction())
        functionMap.put(FOR_TESTING_NONHERMETIC, DelegatingFunction())
    }

    // TODO: b/324948927 - Remove this class along with `SkyKey#skipBatchPrefetch()` method.
    @VisibleForSerialization
    @AutoCodec
    internal class SkipBatchPrefetchKey(arg: String?) : AbstractSkyKey<String?>(arg), SkyKey {
        public override fun skipsBatchPrefetch(): Boolean {
            return true
        }

        public override fun functionName(): SkyFunctionName {
            return SkyFunctionName.FOR_TESTING
        }

        val skyKeyInterner: SkyKeyInterner<SkipBatchPrefetchKey?>
            get() = interner

        companion object {
            private val interner: SkyKeyInterner<SkipBatchPrefetchKey?> = SkyKey.newInterner()

            private fun create(arg: String?): SkipBatchPrefetchKey {
                return interner.intern(SkipBatchPrefetchKey(arg))
            }

            @VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: SkipBatchPrefetchKey?): SkipBatchPrefetchKey {
                return interner.intern(key)
            }
        }
    }

    companion object {
        val NODE_TYPE: SkyFunctionName? = SkyFunctionName.FOR_TESTING

        @Throws(java.lang.InterruptedException::class)
        private fun getValue(dep: Pair<SkyKey?, SkyValue?>, env: SkyFunction.Environment): SkyValue? {
            var value: SkyValue?
            if (dep.second == null) {
                value = env.getValue(dep.first)
            } else {
                try {
                    value = env.getValueOrThrow(dep.first, SomeErrorException::class.java)
                } catch (e: SomeErrorException) {
                    value = dep.second
                }
            }
            return value
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getValueUsingQueryDep(
            dep: Pair<SkyKey?, SkyValue?>, env: SkyFunction.Environment
        ): SkyValue? {
            var value: SkyValue?
            val lookupResult: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                env.getValuesAndExceptions(com.google.common.collect.ImmutableList.of<E?>(dep.first))
            if (dep.second == null) {
                val valueRef: AtomicReference<SkyValue?> = AtomicReference<SkyValue?>()
                val gotValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    lookupResult.queryDep(
                        dep.first,
                        { k, v ->
                            assertThat(k).isEqualTo(dep.first)
                            valueRef.set(v)
                        })
                if ((valueRef.get().also { value = it }) != null) {
                    assertThat(gotValue).isTrue()
                } else {
                    assertThat(gotValue).isFalse()
                }
            } else {
                val valueRef: AtomicReference<SkyValue?> = AtomicReference<SkyValue?>()
                val exceptionRef: AtomicReference<SomeErrorException?> = AtomicReference<SomeErrorException?>()
                val gotValue: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    lookupResult.queryDep(
                        dep.first,
                        object : QueryDepCallback() {
                            public override fun acceptValue(key: SkyKey?, value: SkyValue?) {
                                assertThat(key).isEqualTo(dep.first)
                                valueRef.set(value)
                            }

                            public override fun tryHandleException(key: SkyKey?, e: java.lang.Exception?): Boolean {
                                assertThat(key).isEqualTo(dep.first)
                                if (e is SomeErrorException) {
                                    exceptionRef.set(e)
                                    return true
                                }
                                return false
                            }
                        })
                if ((valueRef.get().also { value = it }) != null) {
                    assertThat(gotValue).isTrue()
                } else if (exceptionRef.get() != null) {
                    value = dep.second
                    assertThat(gotValue).isTrue()
                } else {
                    assertThat(gotValue).isFalse()
                }
            }
            return value
        }

        fun skyKey(key: String?): SkyKey {
            return com.google.devtools.build.skyframe.GraphTester.Key.Companion.create(key)
        }

        fun nonHermeticKey(key: String?): NonHermeticKey {
            return NonHermeticKey.Companion.create(key)
        }

        fun skipBatchPrefetchKey(key: String?): SkipBatchPrefetchKey {
            return SkipBatchPrefetchKey.Companion.create(key)
        }

        fun toSkyKeys(vararg names: String?): com.google.common.collect.ImmutableList<SkyKey?> {
            return toSkyKeys( /* useSkipBatchPrefetchKey= */false, *names)
        }

        fun toSkyKeys(
            useSkipBatchPrefetchKey: Boolean,
            vararg names: String?
        ): com.google.common.collect.ImmutableList<SkyKey?> {
            val result: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
                com.google.common.collect.ImmutableList.builder<SkyKey?>()
            for (element in names) {
                result.add(
                    if (useSkipBatchPrefetchKey) SkipBatchPrefetchKey.Companion.create(element) else com.google.devtools.build.skyframe.GraphTester.Key.Companion.create(
                        element
                    )
                )
            }
            return result.build()
        }

        val COPY: ValueComputer = ValueComputer { deps: MutableMap<SkyKey?, SkyValue>?, env: SkyFunction.Environment? ->
            com.google.common.collect.Iterables.getOnlyElement<SkyValue?>(deps!!.values)
        }

        val CONCATENATE: ValueComputer =
            ValueComputer { deps: MutableMap<SkyKey?, SkyValue>?, env: SkyFunction.Environment? ->
                val result: java.lang.StringBuilder = java.lang.StringBuilder()
                for (value in deps!!.values) {
                    result.append((value as StringValue).value)
                }
                com.google.devtools.build.skyframe.GraphTester.StringValue(result.toString())
            }

        fun formatter(key: SkyKey?, format: String): ValueComputer {
            return ValueComputer { deps: MutableMap<SkyKey?, SkyValue>?, env: SkyFunction.Environment? ->
                com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.of(
                    String.format(
                        format,
                        com.google.devtools.build.skyframe.GraphTester.StringValue.Companion.from(deps!!.get(key))
                            .getValue()
                    )
                )
            }
        }

        private val FOR_TESTING_NONHERMETIC: SkyFunctionName? =
            SkyFunctionName.createNonHermetic("FOR_TESTING_NONHERMETIC")
    }
}
