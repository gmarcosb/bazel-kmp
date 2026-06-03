// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** A Starlark declared provider that encapsulates all providers that are needed by Java rules.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
open class JavaInfo private constructor(
    javaCcInfoProvider: JavaCcInfoProvider?,
    javaCompilationArgsProvider: JavaCompilationArgsProvider?,
    javaCompilationInfoProvider: JavaCompilationInfoProvider?,
    javaGenJarsProvider: JavaGenJarsProvider?,
    javaModuleFlagsProvider: JavaModuleFlagsProvider?,
    javaPluginInfo: JavaPluginInfo?,
    javaRuleOutputJarsProvider: JavaRuleOutputJarsProvider?,
    javaSourceJarsProvider: JavaSourceJarsProvider?,
    directRuntimeJars: com.google.common.collect.ImmutableList<Artifact?>?,
    neverlink: Boolean,
    javaConstraints: com.google.common.collect.ImmutableList<String?>?
) : NativeInfo(), JavaInfoApi<Artifact?, com.google.devtools.build.lib.rules.java.JavaOutput?, JavaPluginData?> {
    /** Marker interface for encapuslated providers  */
    interface JavaInfoInternalProvider

    private val providerJavaCompilationArgs: JavaCompilationArgsProvider?
    private val providerJavaSourceJars: JavaSourceJarsProvider?
    private val providerJavaRuleOutputJars: JavaRuleOutputJarsProvider?
    private val providerJavaGenJars: JavaGenJarsProvider?
    private val providerJavaCompilationInfo: JavaCompilationInfoProvider?
    private val providerJavaCcInfo: JavaCcInfoProvider?
    private val providerModuleFlags: JavaModuleFlagsProvider?
    private val providerJavaPlugin: JavaPluginInfo?

    /**
     * Contains the .jar files to be put on the runtime classpath by the configured target.
     * 
     * 
     * Unlike [JavaCompilationArgs.getRuntimeJars], it does not contain transitive runtime
     * jars, only those produced by the configured target itself.
     * 
     * 
     * The reason why this field exists is that neverlink libraries do not contain the compiled jar
     * in [JavaCompilationArgs.getRuntimeJars] and those are sometimes needed, for example,
     * for Proguarding (the compile time classpath is not enough because that contains only ijars)
     */
    private val directRuntimeJars: com.google.common.collect.ImmutableList<Artifact?>?

    /** Java constraints (e.g. "android") that are present on the target.  */
    private val javaConstraints: com.google.common.collect.ImmutableList<String?>?

    // Whether this library should be used only for compilation and not at runtime.
    val isNeverlink: Boolean

    val javaPluginInfo: JavaPluginInfo?
        get() = providerJavaPlugin

    /** Returns the instance for the provided providerClass, or <tt>null</tt> if not present.  */ // TODO(adonovan): rename these three overloads of getProvider to avoid
    // confusion with the unrelated no-arg Info.getProvider method.
    fun <P : JavaInfoInternalProvider?> getProvider(providerClass: java.lang.Class<P?>?): P? {
        if (providerClass == JavaCompilationArgsProvider::class.java) {
            return providerJavaCompilationArgs as P?
        } else if (providerClass == JavaSourceJarsProvider::class.java) {
            return providerJavaSourceJars as P?
        } else if (providerClass == JavaRuleOutputJarsProvider::class.java) {
            return providerJavaRuleOutputJars as P?
        } else if (providerClass == JavaGenJarsProvider::class.java) {
            return providerJavaGenJars as P?
        } else if (providerClass == JavaCompilationInfoProvider::class.java) {
            return providerJavaCompilationInfo as P?
        } else if (providerClass == JavaCcInfoProvider::class.java) {
            return providerJavaCcInfo as P?
        } else if (providerClass == JavaModuleFlagsProvider::class.java) {
            return providerModuleFlags as P?
        }
        throw java.lang.IllegalArgumentException("unexpected provider: " + providerClass)
    }

    init {
        this.directRuntimeJars = directRuntimeJars
        this.isNeverlink = neverlink
        this.javaConstraints = javaConstraints
        this.providerJavaCcInfo = javaCcInfoProvider
        this.providerJavaCompilationArgs = javaCompilationArgsProvider
        this.providerJavaCompilationInfo = javaCompilationInfoProvider
        this.providerJavaGenJars = javaGenJarsProvider
        this.providerModuleFlags = javaModuleFlagsProvider
        this.providerJavaPlugin = javaPluginInfo
        this.providerJavaRuleOutputJars = javaRuleOutputJarsProvider
        this.providerJavaSourceJars = javaSourceJarsProvider
    }

    private constructor(javaInfo: StructImpl) : this(
        JavaCcInfoProvider.Companion.fromStarlarkJavaInfo(javaInfo),
        JavaCompilationArgsProvider.Companion.fromStarlarkJavaInfo(javaInfo),
        JavaCompilationInfoProvider.Companion.fromStarlarkJavaInfo(javaInfo),
        JavaGenJarsProvider.Companion.from(javaInfo.getValue("annotation_processing")),
        JavaModuleFlagsProvider.Companion.fromStarlarkJavaInfo(javaInfo),
        JavaPluginInfo.fromStarlarkJavaInfo(javaInfo),
        JavaRuleOutputJarsProvider.Companion.fromStarlarkJavaInfo(javaInfo),
        JavaSourceJarsProvider.Companion.fromStarlarkJavaInfo(javaInfo),
        extractDirectRuntimeJars(javaInfo),
        extractNeverLink(javaInfo),
        extractConstraints(javaInfo)
    )

    val transitiveRuntimeJars: Depset
        get() = Depset.of(
            Artifact::class.java,
            getProviderAsNestedSet<JavaCompilationArgsProvider?, Any?>(
                JavaCompilationArgsProvider::class.java, JavaCompilationArgsProvider::runtimeJars
            )
        )

    val transitiveCompileTimeJars: Depset
        get() = Depset.of(
            Artifact::class.java,
            getProviderAsNestedSet<JavaCompilationArgsProvider?, Any?>(
                JavaCompilationArgsProvider::class.java,
                JavaCompilationArgsProvider::transitiveCompileTimeJars
            )
        )

    val compileTimeJars: Depset
        get() {
            val compileTimeJars: NestedSet<Artifact?>? =
                getProviderAsNestedSet<JavaCompilationArgsProvider?, Any?>(
                    JavaCompilationArgsProvider::class.java, JavaCompilationArgsProvider::directCompileTimeJars
                )
            return Depset.of(Artifact::class.java, compileTimeJars)
        }

    val fullCompileTimeJars: Depset
        get() {
            val fullCompileTimeJars: NestedSet<Artifact?>? =
                getProviderAsNestedSet<JavaCompilationArgsProvider?, Any?>(
                    JavaCompilationArgsProvider::class.java,
                    JavaCompilationArgsProvider::directFullCompileTimeJars
                )
            return Depset.of(Artifact::class.java, fullCompileTimeJars)
        }

    val sourceJars: net.starlark.java.eval.Sequence<Artifact?>
        get() {
            // TODO(#4221) change return type to NestedSet<Artifact>
            val sourceJars: com.google.common.collect.ImmutableList<Artifact?>? =
                if (providerJavaSourceJars == null) com.google.common.collect.ImmutableList.of<Artifact?>() else providerJavaSourceJars.sourceJars
            return StarlarkList.immutableCopyOf<Artifact?>(sourceJars)
        }

    @get:Deprecated("")
    val outputJars: JavaRuleOutputJarsProvider?
        get() = providerJavaRuleOutputJars

    val javaOutputs: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.rules.java.JavaOutput?>
        get() = if (providerJavaRuleOutputJars == null)
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.rules.java.JavaOutput?>()
        else
            providerJavaRuleOutputJars.javaOutputs

    val genJarsProvider: JavaGenJarsProvider?
        get() = providerJavaGenJars

    val compilationInfoProvider: JavaCompilationInfoProvider?
        get() = providerJavaCompilationInfo

    val runtimeOutputJars: net.starlark.java.eval.Sequence<Artifact?>
        get() = StarlarkList.immutableCopyOf<Artifact?>(getDirectRuntimeJars())

    fun getDirectRuntimeJars(): com.google.common.collect.ImmutableList<Artifact?>? {
        return directRuntimeJars
    }

    val transitiveSourceJars: Depset
        get() = Depset.of(
            Artifact::class.java,
            getProviderAsNestedSet<JavaSourceJarsProvider?, Any?>(
                JavaSourceJarsProvider::class.java, JavaSourceJarsProvider::transitiveSourceJars
            )
        )

    @get:Deprecated("Only use in tests")
    val transitiveNativeLibraries: NestedSet<LibraryToLink?>?
        /**
         * Returns the transitive set of CC native libraries required by the target.
         * 
         */
        get() = getProviderAsNestedSet<JavaCcInfoProvider?, Any?>(
            JavaCcInfoProvider::class.java,
            java.util.function.Function { x: JavaCcInfoProvider? -> x.ccInfo.getTransitiveCcNativeLibrariesForTests() })

    val transitiveNativeLibrariesForStarlark: Depset
        get() {
            throw java.lang.UnsupportedOperationException()
        }

    val javaModuleFlagsInfo: JavaModuleFlagsProviderApi?
        get() = if (providerModuleFlags == null) JavaModuleFlagsProvider.Companion.EMPTY else providerModuleFlags

    val transitiveFullCompileJars: Depset
        get() = Depset.of(
            Artifact::class.java,
            getProviderAsNestedSet<JavaCompilationArgsProvider?, Any?>(
                JavaCompilationArgsProvider::class.java,
                JavaCompilationArgsProvider::transitiveFullCompileTimeJars
            )
        )

    val compileTimeJavaDependencies: Depset
        get() = Depset.of(
            Artifact::class.java,
            getProviderAsNestedSet<JavaCompilationArgsProvider?, Any?>(
                JavaCompilationArgsProvider::class.java,
                JavaCompilationArgsProvider::compileTimeJavaDependencyArtifacts
            )
        )

    override fun plugins(): JavaPluginData? {
        return if (providerJavaPlugin == null) JavaPluginData.empty() else providerJavaPlugin.plugins()
    }

    override fun apiGeneratingPlugins(): JavaPluginData? {
        return if (providerJavaPlugin == null)
            JavaPluginData.empty()
        else
            providerJavaPlugin.apiGeneratingPlugins()
    }

    /** Returns all constraints set on the associated target.  */
    fun getJavaConstraints(): com.google.common.collect.ImmutableList<String?>? {
        return javaConstraints
    }

    val javaConstraintsStarlark: net.starlark.java.eval.Sequence<String?>
        get() = StarlarkList.immutableCopyOf<String?>(javaConstraints)

    override fun headerCompilationDirectDeps(): Depset {
        val headerCompilationDirectDeps: NestedSet<Artifact?>? =
            getProviderAsNestedSet<JavaCompilationArgsProvider?, Any?>(
                JavaCompilationArgsProvider::class.java,
                JavaCompilationArgsProvider::directHeaderCompilationJars
            )
        return Depset.of(Artifact::class.java, headerCompilationDirectDeps)
    }

    /**
     * Gets Provider, check it for not null and call function to get NestedSet&lt;S&gt; from it.
     * 
     * 
     * Gets provider from map. If Provider is null, return default, empty, stabled ordered
     * NestedSet. If provider is not null, then delegates to mapper all responsibility to fetch
     * required NestedSet from provider.
     * 
     * @param providerClass provider class. used as key to look up for provider.
     * @param mapper Function used to convert provider to NesteSet&lt;S&gt;
     * @param <P> type of Provider
     * @param <S> type of returned NestedSet items
    </S></P> */
    private fun <P : JavaInfoInternalProvider?, S> getProviderAsNestedSet(
        providerClass: java.lang.Class<P?>?, mapper: java.util.function.Function<P?, NestedSet<S?>?>
    ): NestedSet<S?>? {
        val provider = getProvider<P?>(providerClass)
        if (provider == null) {
            return NestedSetBuilder.< S > stableOrder < S ? > ().build()
        }
        return mapper.apply(provider)
    }

    override fun equals(otherObject: Any?): Boolean {
        if (this === otherObject) {
            return true
        }
        if (otherObject !is JavaInfo) {
            return false
        }

        return providerJavaCompilationArgs == otherObject.providerJavaCompilationArgs
                && providerJavaSourceJars == otherObject.providerJavaSourceJars
                && providerJavaRuleOutputJars == otherObject.providerJavaRuleOutputJars
                && providerJavaGenJars == otherObject.providerJavaGenJars
                && providerJavaCompilationInfo == otherObject.providerJavaCompilationInfo
                && providerJavaCcInfo == otherObject.providerJavaCcInfo
                && providerModuleFlags == otherObject.providerModuleFlags
                && providerJavaPlugin == otherObject.providerJavaPlugin
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(
            providerJavaCompilationArgs,
            providerJavaSourceJars,
            providerJavaRuleOutputJars,
            providerJavaGenJars,
            providerJavaCompilationInfo,
            providerJavaCcInfo,
            providerModuleFlags,
            providerJavaPlugin
        )
    }

    internal class RulesJavaJavaInfo private constructor(javaInfo: StructImpl) : JavaInfo(javaInfo) {
        override fun getProvider(): JavaInfoProvider {
            return RULES_JAVA_PROVIDER
        }
    }

    internal class WorkspaceJavaInfo private constructor(javaInfo: StructImpl) : JavaInfo(javaInfo) {
        override fun getProvider(): JavaInfoProvider {
            return WORKSPACE_PROVIDER
        }
    }

    /** Legacy Provider class for [JavaInfo] objects.  */
    class RulesJavaJavaInfoProvider private constructor() :
        JavaInfoProvider(keyForBuild(Label.parseCanonicalUnchecked("//java/private:java_info.bzl"))) {
        @Throws(RuleErrorException::class, TypeException::class, net.starlark.java.eval.EvalException::class)
        override fun makeNewInstance(info: StructImpl): JavaInfo {
            return RulesJavaJavaInfo(info)
        }
    }

    /** Legacy Provider class for [JavaInfo] objects in WORKSPACE mode.  */
    class WorkspaceJavaInfoProvider private constructor() :
        JavaInfoProvider(keyForBuild(Label.parseCanonicalUnchecked("@@rules_java//java/private:java_info.bzl"))) {
        @Throws(RuleErrorException::class, TypeException::class, net.starlark.java.eval.EvalException::class)
        override fun makeNewInstance(info: StructImpl): JavaInfo {
            return WorkspaceJavaInfo(info)
        }
    }

    /** Provider class for [JavaInfo] objects.  */
    open class JavaInfoProvider(key: BzlLoadValue.Key?) : StarlarkProviderWrapper<JavaInfo?>(key, STARLARK_NAME),
        Provider {
        private constructor() : this(
            keyForBuild(
                Label.parseCanonicalUnchecked(
                    JavaSemantics.RULES_JAVA_PROVIDER_LABELS_PREFIX + "java/private:java_info.bzl"
                )
            )
        )

        @Throws(RuleErrorException::class)
        public override fun wrap(info: Info?): JavaInfo? {
            if (info is JavaInfo) {
                return info
            } else if (info is StructImpl) {
                try {
                    return makeNewInstance(info)
                } catch (e: net.starlark.java.eval.EvalException) {
                    throw RuleErrorException(e)
                } catch (e: TypeException) {
                    throw RuleErrorException(e)
                }
            }
            throw RuleErrorException("got " + Starlark.type(info) + ", wanted JavaInfo")
        }

        @Throws(RuleErrorException::class, TypeException::class, net.starlark.java.eval.EvalException::class)
        protected open fun makeNewInstance(info: StructImpl): JavaInfo {
            return JavaInfo(info)
        }

        val isExported: Boolean
            get() = true

        val printableName: String
            get() = STARLARK_NAME

        val location: net.starlark.java.syntax.Location
            get() = net.starlark.java.syntax.Location.BUILTIN
    }

    // TODO: b/359437873 - generate with @AutoCodec.
    @com.google.errorprone.annotations.Keep
    private class JavaInfoValueSharingCodec : DeferredObjectCodec<JavaInfo?>() {
        val encodedClass: java.lang.Class<out JavaInfo?>
            get() = JavaInfo::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(context: SerializationContext, obj: JavaInfo?, codedOut: CodedOutputStream?) {
            context.putSharedValue(obj, null, JavaInfoCodec.Companion.INSTANCE, codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<JavaInfo?>? {
            val deferredValue: SimpleDeferredValue<JavaInfo?>? = SimpleDeferredValue.create()
            context.getSharedValue(
                codedIn, null, JavaInfoCodec.Companion.INSTANCE, deferredValue, SimpleDeferredValue::set
            )
            return deferredValue
        }
    }

    @com.google.errorprone.annotations.Keep
    private class JavaInfoCodec : DeferredObjectCodec<JavaInfo?>() {
        private val handlers: com.google.common.collect.ImmutableList<FieldHandler>

        init {
            this.handlers =
                com.google.common.collect.ImmutableList.copyOf(
                    DynamicCodec.getFieldHandlerMap(JavaInfo::class.java).values()
                )
        }

        public override fun autoRegister(): Boolean {
            // This is the internal implementation for the JavaInfo codec. Instead (auto) register
            // the external codec that does shared value serialization that uses this codec.
            return false
        }

        val encodedClass: java.lang.Class<out JavaInfo?>
            get() = JavaInfo::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(context: SerializationContext?, obj: JavaInfo?, codedOut: CodedOutputStream?) {
            for (handler in handlers) {
                handler.serialize(context, codedOut, obj)
            }
        }

        @Throws(SerializationException::class, IOException::class)  // TODO: b/331765692 - delete this
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream?
        ): DeferredValue<JavaInfo?> {
            val obj: JavaInfo?
            try {
                obj = unsafe().allocateInstance(JavaInfo::class.java) as JavaInfo?
            } catch (e: java.lang.InstantiationException) {
                throw SerializationException("Could not instantiate JavaInfo with Unsafe", e)
            }

            for (handler in handlers) {
                handler.deserialize(context, codedIn, obj)
            }

            return DeferredValue { obj }
        }

        companion object {
            val INSTANCE: JavaInfoCodec = JavaInfoCodec()
        }
    }

    companion object {
        const val STARLARK_NAME: String = "JavaInfo"

        // Not serialized
        val RULES_JAVA_PROVIDER: JavaInfoProvider = RulesJavaJavaInfoProvider()

        // Not serialized
        val WORKSPACE_PROVIDER: JavaInfoProvider = WorkspaceJavaInfoProvider()

        @SerializationConstant
        val provider: JavaInfoProvider = JavaInfoProvider()
            get() = Companion.field

        @Throws(RuleErrorException::class)
        fun transitiveRuntimeJars(target: TransitiveInfoCollection): NestedSet<Artifact?> {
            return transformStarlarkDepsetApi(
                target,
                java.util.function.Function { obj: JavaInfo? -> obj!!.transitiveRuntimeJars })
        }

        @Throws(RuleErrorException::class)
        private fun transformStarlarkDepsetApi(
            target: TransitiveInfoCollection, api: java.util.function.Function<JavaInfo?, Depset?>
        ): NestedSet<Artifact?> {
            val javaInfo = getJavaInfo(target)
            if (javaInfo != null) {
                try {
                    return api.apply(javaInfo).getSet(Artifact::class.java)
                } catch (e: TypeException) {
                    throw RuleErrorException(e.getMessage())
                }
            }
            return NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        fun <T> nullIfNone(`object`: Any?, type: java.lang.Class<T?>): T? {
            return if (`object` !== Starlark.NONE) type.cast(`object`) else null
        }

        val EMPTY_JAVA_INFO_FOR_TESTING: JavaInfo = JavaInfo(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            com.google.common.collect.ImmutableList.of<Artifact?>(),
            false,
            com.google.common.collect.ImmutableList.of<String?>()
        )

        /** Returns a provider of the specified class, fetched from the JavaInfo of the given target.  */
        @com.google.common.annotations.VisibleForTesting
        @Throws(RuleErrorException::class)
        fun <T : JavaInfoInternalProvider?> getProvider(
            providerClass: java.lang.Class<T?>?, target: TransitiveInfoCollection
        ): T? {
            val javaInfo = getJavaInfo(target)
            if (javaInfo == null) {
                return null
            }
            return javaInfo.getProvider<T?>(providerClass)
        }

        @Throws(RuleErrorException::class)
        fun getJavaInfo(target: TransitiveInfoCollection): JavaInfo? {
            var info: JavaInfo? = target.get(provider)
            if (info == null) {
                info = target.get(RULES_JAVA_PROVIDER)
            }
            if (info == null) {
                info = target.get(WORKSPACE_PROVIDER)
            }
            return info
        }

        @com.google.common.annotations.VisibleForTesting
        @Throws(RuleErrorException::class)
        fun wrap(info: Info): JavaInfo? {
            val key: Provider.Key = info.getProvider().getKey()
            if (key.equals(RULES_JAVA_PROVIDER.getKey())) {
                return RULES_JAVA_PROVIDER.wrap(info)
            } else if (key.equals(WORKSPACE_PROVIDER.getKey())) {
                return WORKSPACE_PROVIDER.wrap(info)
            } else {
                return provider.wrap(info)
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun extractDirectRuntimeJars(javaInfo: StructImpl): com.google.common.collect.ImmutableList<Artifact?>? {
            return net.starlark.java.eval.Sequence.cast<T?>(
                javaInfo.getValue("runtime_output_jars"), Artifact::class.java, "runtime_output_jars"
            )
                .getImmutableList()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun extractNeverLink(javaInfo: StructImpl): Boolean {
            val neverlink: Boolean? = Companion.nullIfNone<T?>(javaInfo.getValue("_neverlink"), Boolean::class.java)
            return neverlink != null && neverlink
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun extractConstraints(javaInfo: StructImpl): com.google.common.collect.ImmutableList<String?>? {
            val constraints: Any? = javaInfo.getValue("_constraints")
            if (constraints == null || constraints === Starlark.NONE) {
                return com.google.common.collect.ImmutableList.of<String?>()
            }
            return net.starlark.java.eval.Sequence.cast<String?>(constraints, String::class.java, "_constraints")
                .getImmutableList()
        }
    }
}
