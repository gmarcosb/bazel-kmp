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
package com.google.devtools.build.lib.rules.java

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.lib.concurrent.ThreadSafety
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark
import net.starlark.java.syntax.Location
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableList

/** Provider for users of Java plugins.  */
@ThreadSafety.Immutable
@AutoValue
abstract class JavaPluginInfo : NativeInfo(), JavaPluginInfoApi<Artifact?, JavaPluginData?, JavaOutput?> {
    val provider: com.google.devtools.build.lib.packages.Provider?
        get() = providerType()

    /** Provider class for [JavaPluginInfo] objects in rules_java itself.  */
    class RulesJavaProvider private constructor() :
        Provider(BzlLoadValue.keyForBuild(Label.parseCanonicalUnchecked("//java/private:java_info.bzl")))

    /** Provider class for [JavaPluginInfo] objects.  */
    open class Provider private constructor(
        key: BzlLoadValue.Key? = BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                JavaSemantics.Companion.RULES_JAVA_PROVIDER_LABELS_PREFIX + "java/private:java_info.bzl"
            )
        )
    ) : StarlarkProviderWrapper<JavaPluginInfo?>(key, PROVIDER_NAME), com.google.devtools.build.lib.packages.Provider {
        val isExported: Boolean
            get() = true

        val printableName: String
            get() = PROVIDER_NAME

        val location: Location
            get() = Location.BUILTIN

        @Throws(RuleErrorException::class)
        public override fun wrap(value: Info): JavaPluginInfo? {
            if (value is JavaPluginInfo) {
                return value
            } else if (value is StructImpl) {
                try {
                    val info: StructImpl = value as StructImpl
                    return AutoValue_JavaPluginInfo(
                        JavaOutput.Companion.wrapSequence(
                            Sequence.cast<T?>(info.getValue("java_outputs"), Any::class.java, "java_outputs")
                        ),
                        JavaPluginData.Companion.wrap(info.getValue("plugins")),
                        JavaPluginData.Companion.wrap(info.getValue("api_generating_plugins")),
                        value.getProvider()
                    )
                } catch (e: EvalException) {
                    throw RuleErrorException(e)
                }
            } else {
                throw RuleErrorException(
                    "got element of type " + Starlark.type(value) + ", want JavaPluginInfo"
                )
            }
        }
    }

    /** Information about a Java plugin, except for whether it generates API.  */
    @ThreadSafety.Immutable
    @AutoValue
    abstract class JavaPluginData : JavaPluginDataApi {
        /**
         * Returns the class that should be passed to javac in order to run the annotation processor
         * this class represents.
         */
        abstract fun processorClasses(): NestedSet<String?>?

        /** Returns the artifacts to add to the runtime classpath for this plugin.  */
        abstract fun processorClasspath(): NestedSet<Artifact?>?

        abstract fun data(): NestedSet<Artifact?>?

        val processorJarsForStarlark: Depset
            get() = Depset.of(Artifact::class.java, processorClasspath())

        val processorClassesForStarlark: Depset
            get() = Depset.of(String::class.java, processorClasses())

        val processorDataForStarlark: Depset
            get() = Depset.of(Artifact::class.java, data())

        val isEmpty: Boolean
            get() = processorClasses().isEmpty() && processorClasspath().isEmpty() && data().isEmpty()

        companion object {
            private val EMPTY: JavaPluginData = AutoValue_JavaPluginInfo_JavaPluginData(
                NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
                NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER),
                NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
            )

            fun create(
                processorClasses: NestedSet<String?>,
                processorClasspath: NestedSet<Artifact?>,
                data: NestedSet<Artifact?>
            ): JavaPluginData {
                if (processorClasses.isEmpty() && processorClasspath.isEmpty() && data.isEmpty()) {
                    return empty()
                }
                return AutoValue_JavaPluginInfo_JavaPluginData(
                    processorClasses, processorClasspath, data
                )
            }

            @kotlin.jvm.JvmStatic
            fun empty(): JavaPluginData {
                return EMPTY
            }

            fun merge(plugins: Iterable<JavaPluginData>): JavaPluginData {
                val processorClasses: NestedSetBuilder<String?> = NestedSetBuilder.naiveLinkOrder()
                val processorClasspath: NestedSetBuilder<Artifact?> = NestedSetBuilder.naiveLinkOrder()
                val data: NestedSetBuilder<Artifact?> = NestedSetBuilder.naiveLinkOrder()
                for (plugin in plugins) {
                    processorClasses.addTransitive(plugin.processorClasses())
                    processorClasspath.addTransitive(plugin.processorClasspath())
                    data.addTransitive(plugin.data())
                }
                return create(processorClasses.build(), processorClasspath.build(), data.build())
            }

            @Throws(EvalException::class, RuleErrorException::class)
            fun wrap(obj: Any): JavaPluginData {
                if (obj is JavaPluginData) {
                    return obj
                } else if (obj is StructImpl) {
                    return create(
                        Depset.cast(obj.getValue("processor_classes"), String::class.java, "processor_classes"),
                        Depset.cast(obj.getValue("processor_jars"), Artifact::class.java, "processor_jars"),
                        Depset.cast(obj.getValue("processor_data"), Artifact::class.java, "processor_data")
                    )
                }
                throw RuleErrorException("Should never happen! Got unexpected type: " + obj.getClass())
            }
        }
    }

    abstract override fun plugins(): JavaPluginData?

    abstract override fun apiGeneratingPlugins(): JavaPluginData?

    protected abstract fun providerType(): com.google.devtools.build.lib.packages.Provider?

    val isEmpty: Boolean
        /** Returns true if the provider has no associated data.  */
        get() =// apiGeneratingPlugins is a subset of plugins, so checking if plugins is empty is sufficient
            plugins()!!.isEmpty

    /**
     * Returns true if the provider has any associated annotation processors (regardless of whether it
     * has a classpath or data).
     */
    fun hasProcessors(): Boolean {
        // apiGeneratingPlugins is a subset of plugins, so checking if plugins is empty is sufficient
        return !plugins()!!.processorClasses().isEmpty()
    }

    companion object {
        const val PROVIDER_NAME: String = "JavaPluginInfo"
        @kotlin.jvm.JvmField
        val PROVIDER: Provider = JavaPluginInfo.Provider()
        val RULES_JAVA_PROVIDER: Provider = JavaPluginInfo.RulesJavaProvider()

        private val EMPTY: JavaPluginInfo = AutoValue_JavaPluginInfo(
            ImmutableList.of<E?>(), JavaPluginData.Companion.empty(), JavaPluginData.Companion.empty(), PROVIDER
        )

        private val EMPTY_RULES_JAVA: JavaPluginInfo = AutoValue_JavaPluginInfo(
            ImmutableList.of<E?>(),
            JavaPluginData.Companion.empty(),
            JavaPluginData.Companion.empty(),
            RULES_JAVA_PROVIDER
        )

        @Throws(RuleErrorException::class)
        fun wrap(info: Info): JavaPluginInfo? {
            // this wrapped instance is not propagated back to Starlark, so we don't need every type
            // we just use the single type that is checked for in tests
            return PROVIDER.wrap(info)
        }

        @VisibleForTesting
        @Throws(RuleErrorException::class)
        fun get(target: ConfiguredTarget): JavaPluginInfo {
            // we just use the single type that is checked for in tests
            return target.get(PROVIDER)
        }

        fun mergeWithoutJavaOutputs(a: JavaPluginInfo, b: JavaPluginInfo): JavaPluginInfo? {
            return if (a.isEmpty)
                b
            else
                if (b.isEmpty) a else mergeWithoutJavaOutputs(ImmutableList.of<JavaPluginInfo?>(a, b), a.providerType())
        }

        fun mergeWithoutJavaOutputs(
            providers: Iterable<JavaPluginInfo>,
            providerType: com.google.devtools.build.lib.packages.Provider
        ): JavaPluginInfo? {
            val plugins: MutableList<JavaPluginData> = ArrayList<JavaPluginData>()
            val apiGeneratingPlugins: MutableList<JavaPluginData> = ArrayList<JavaPluginData>()
            for (provider in providers) {
                if (!provider.plugins()!!.isEmpty) {
                    plugins.add(provider.plugins()!!)
                }
                if (!provider.apiGeneratingPlugins()!!.isEmpty) {
                    apiGeneratingPlugins.add(provider.apiGeneratingPlugins()!!)
                }
            }
            if (plugins.isEmpty() && apiGeneratingPlugins.isEmpty()) {
                return empty(providerType)
            }
            return AutoValue_JavaPluginInfo(
                ImmutableList.of<E?>(),
                JavaPluginData.Companion.merge(plugins),
                JavaPluginData.Companion.merge(apiGeneratingPlugins),
                providerType
            )
        }

        fun empty(providerType: com.google.devtools.build.lib.packages.Provider): JavaPluginInfo {
            if (providerType.equals(RULES_JAVA_PROVIDER)) {
                return EMPTY_RULES_JAVA
            }
            return EMPTY
        }

        /**
         * Translates the plugin information from a [JavaInfo] instance.
         * 
         * @param javaInfo the [JavaInfo] instance
         * @return a [JavaPluginInfo] instance
         * @throws EvalException if there are any errors accessing Starlark values
         * @throws RuleErrorException if the `plugins` or `api_generating_plugins` fields are
         * of an incompatible type
         */
        @Throws(EvalException::class, RuleErrorException::class)
        fun fromStarlarkJavaInfo(javaInfo: StructImpl): JavaPluginInfo? {
            val providerType: com.google.devtools.build.lib.packages.Provider = javaInfo.getProvider()
            val plugins = JavaPluginData.Companion.wrap(javaInfo.getValue("plugins"))
            val apiGeneratingPlugins =
                JavaPluginData.Companion.wrap(javaInfo.getValue("api_generating_plugins"))
            if (plugins.isEmpty && apiGeneratingPlugins.isEmpty) {
                return empty(providerType)
            }
            return AutoValue_JavaPluginInfo(
                ImmutableList.of<E?>(), plugins, apiGeneratingPlugins, providerType
            )
        }
    }
}
