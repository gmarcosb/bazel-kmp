// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules.java

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.lib.concurrent.ThreadSafety
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkValue
import java.util.*

/** Information about the JDK used by the `java_*` rules.  */
@ThreadSafety.Immutable
class JavaToolchainProvider private constructor(underlying: StarlarkInfo?) : StarlarkInfoWrapper(underlying) {
    override fun hashCode(): Int {
        try {
            // StructImpl.hashcode() is too expensive, just the label should be enough
            return this.toolchainLabel.hashCode()
        } catch (e: RuleErrorException) {
            throw IllegalStateException(e)
        }
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is JavaToolchainProvider) {
            return false
        }
        return underlying.equals(obj.underlying)
    }

    @get:Throws(RuleErrorException::class)
    val toolchainLabel: Label?
        /** Returns the label for this `java_toolchain`.  */
        get() = Preconditions.checkNotNull<Label?>(
            getUnderlyingValue<Label?>(
                "label",
                Label::class.java
            )
        )

    @get:Throws(RuleErrorException::class)
    val bootclasspath: BootClassPathInfo
        /** Returns the target Java bootclasspath.  */
        get() = BootClassPathInfo.Companion.wrap(getUnderlyingValue<Info?>("_bootclasspath_info", Info::class.java))

    @get:Throws(RuleErrorException::class)
    val tools: NestedSet<Artifact?>?
        /** Returns the [Artifact]s of compilation tools.  */
        get() = getUnderlyingNestedSet<Artifact?>("tools", Artifact::class.java)

    @get:Throws(RuleErrorException::class)
    val javaBuilder: JavaToolchainTool?
        /** Returns the [JavaToolchainTool] for JavaBuilder  */
        get() = JavaToolchainTool.Companion.fromStarlark(
            getUnderlyingValue<StructImpl?>("_javabuilder", StructImpl::class.java), this
        )

    @get:Throws(RuleErrorException::class)
    val headerCompiler: JavaToolchainTool?
        /** Returns the [JavaToolchainTool] for the header compiler  */
        get() = JavaToolchainTool.Companion.fromStarlark(
            getUnderlyingValue<StructImpl?>("_header_compiler", StructImpl::class.java), this
        )

    @get:Throws(RuleErrorException::class)
    val headerCompilerDirect: JavaToolchainTool?
        /**
         * Returns the [FilesToRunProvider] of the Header Compiler deploy jar for direct-classpath,
         * non-annotation processing actions.
         */
        get() = JavaToolchainTool.Companion.fromStarlark(
            getUnderlyingValue<StructImpl?>("_header_compiler_direct", StructImpl::class.java), this
        )

    @get:Throws(RuleErrorException::class)
    @get:VisibleForTesting
    val androidLint: StructImpl?
        get() = getUnderlyingValue<StructImpl?>("_android_linter", StructImpl::class.java)

    @Throws(RuleErrorException::class)
    fun jspecifyInfo(): JspecifyInfo? {
        return JspecifyInfo.Companion.fromStarlark(
            getUnderlyingValue<StarlarkValue?>(
                "_jspecify_info",
                StarlarkValue::class.java
            )
        )
    }

    @get:Throws(RuleErrorException::class)
    val bytecodeOptimizer: JavaToolchainTool?
        get() = JavaToolchainTool.Companion.fromStarlark(
            getUnderlyingValue<StructImpl?>("_bytecode_optimizer", StructImpl::class.java), this
        )

    @get:Throws(RuleErrorException::class)
    val localJavaOptimizationConfiguration: ImmutableList<Artifact>?
        get() = getUnderlyingSequence<Artifact?>("_local_java_optimization_config", Artifact::class.java)
            .getImmutableList()

    @get:Throws(RuleErrorException::class)
    val headerCompilerBuiltinProcessors: ImmutableSet<String?>
        /** Returns class names of annotation processors that are built in to the header compiler.  */
        get() = getUnderlyingNestedSet<String?>(
            "_header_compiler_builtin_processors",
            String::class.java
        ).toSet()

    @get:Throws(RuleErrorException::class)
    val reducedClasspathIncompatibleProcessors: ImmutableSet<String?>
        get() = getUnderlyingNestedSet<String?>(
            "_reduced_classpath_incompatible_processors",
            String::class.java
        )
            .toSet()

    @get:Throws(RuleErrorException::class)
    val singleJar: FilesToRunProvider?
        /** Returns the [FilesToRunProvider] of the SingleJar tool.  */
        get() = getUnderlyingValue<FilesToRunProvider?>("single_jar", FilesToRunProvider::class.java)

    @get:Throws(RuleErrorException::class)
    val genClass: Artifact?
        /** Returns the [Artifact] of the GenClass deploy jar  */
        get() = getUnderlyingValue<Artifact?>("_gen_class", Artifact::class.java)

    @get:Throws(RuleErrorException::class)
    @get:VisibleForTesting
    val timezoneData: Artifact?
        /**
         * Returns the [Artifact] of the latest timezone data resource jar that can be loaded by
         * Java 8 binaries.
         */
        get() = getUnderlyingValue<Artifact?>("_timezone_data", Artifact::class.java)

    @get:Throws(RuleErrorException::class)
    val ijar: FilesToRunProvider?
        /** Returns the ijar executable  */
        get() = getUnderlyingValue<FilesToRunProvider?>("ijar", FilesToRunProvider::class.java)

    @get:Throws(RuleErrorException::class)
    val jvmOptions: NestedSet<String?>?
        /**
         * Returns the NestedSet of default options for the JVM running the java compiler and associated
         * tools.
         */
        get() = getUnderlyingNestedSet<String?>("jvm_opt", String::class.java)

    @get:Throws(RuleErrorException::class)
    val javacSupportsWorkers: Boolean
        /** Returns whether JavaBuilders supports running as a persistent worker or not.  */
        get() = getUnderlyingValue<Boolean?>("_javac_supports_workers", Boolean::class.java)

    @get:Throws(RuleErrorException::class)
    val javacSupportsMultiplexWorkers: Boolean
        /** Returns whether JavaBuilders supports running persistent workers in multiplex mode  */
        get() = getUnderlyingValue<Boolean?>("_javac_supports_multiplex_workers", Boolean::class.java)

    @get:Throws(RuleErrorException::class)
    val javacSupportsWorkerCancellation: Boolean
        /** Returns whether JavaBuilders supports running persistent workers with cancellation  */
        get() = getUnderlyingValue<Boolean?>("_javac_supports_worker_cancellation", Boolean::class.java)

    @get:Throws(RuleErrorException::class)
    val javacSupportsWorkerMultiplexSandboxing: Boolean
        /** Returns whether JavaBuilders supports running multiplex persistent workers in sandbox mode  */
        get() = getUnderlyingValue<Boolean?>("_javac_supports_worker_multiplex_sandboxing", Boolean::class.java)

    /** Returns the global `java_package_configuration` data.  */
    @Throws(RuleErrorException::class)
    fun packageConfiguration(): ImmutableList<JavaPackageConfigurationProvider?> {
        return JavaPackageConfigurationProvider.Companion.wrapSequence(
            getUnderlyingSequence<StructImpl?>("_package_configuration", StructImpl::class.java)
        )
    }

    @get:Throws(RuleErrorException::class)
    val jacocoRunner: FilesToRunProvider?
        get() = getUnderlyingValue<FilesToRunProvider?>("jacocorunner", FilesToRunProvider::class.java)

    @get:Throws(RuleErrorException::class)
    val javaRuntime: JavaRuntimeInfo?
        get() = JavaRuntimeInfo.Companion.wrap(
            getUnderlyingValue<Info?>("java_runtime", Info::class.java),
            "java_runtime"
        )

    internal class JspecifyInfo(
        jspecifyProcessor: JavaPluginData?,
        jspecifyImplicitDeps: NestedSet<Artifact?>?,
        jspecifyJavacopts: ImmutableList<String?>?,
        jspecifyPackages: ImmutableList<PackageSpecificationProvider>?
    ) {
        fun matches(label: Label): Boolean {
            for (provider in this.jspecifyPackages!!) {
                for (specifications in provider.getPackageSpecifications().toList()) {
                    if (specifications.containsPackage(label.getPackageIdentifier())) {
                        return true
                    }
                }
            }
            return false
        }

        val jspecifyProcessor: JavaPluginData?
        val jspecifyImplicitDeps: NestedSet<Artifact?>?
        val jspecifyJavacopts: ImmutableList<String?>?
        val jspecifyPackages: ImmutableList<PackageSpecificationProvider>?

        init {
            this.jspecifyPackages = jspecifyPackages
            this.jspecifyJavacopts = jspecifyJavacopts
            this.jspecifyImplicitDeps = jspecifyImplicitDeps
            this.jspecifyProcessor = jspecifyProcessor
            Objects.requireNonNull<JavaPluginData?>(jspecifyProcessor, "jspecifyProcessor")
            Objects.requireNonNull<Any?>(jspecifyImplicitDeps, "jspecifyImplicitDeps")
            Objects.requireNonNull<ImmutableList<String?>?>(jspecifyJavacopts, "jspecifyJavacopts")
            Objects.requireNonNull<ImmutableList<PackageSpecificationProvider?>?>(jspecifyPackages, "jspecifyPackages")
        }

        companion object {
            @Throws(RuleErrorException::class)
            fun fromStarlark(value: StarlarkValue?): JspecifyInfo? {
                if (value == null || value === Starlark.NONE) {
                    return null
                } else if (value is StructImpl) {
                    try {
                        return JspecifyInfo(
                            JavaPluginData.Companion.wrap(value.getValue("processor")),
                            Depset.noneableCast(
                                value.getValue("implicit_deps"), Artifact::class.java, "implicit_deps"
                            ),
                            Sequence.noneableCast<T?>(value.getValue("javacopts"), String::class.java, "javacopts")
                                .getImmutableList(),
                            Sequence.noneableCast<T?>(
                                value.getValue("packages"), PackageSpecificationProvider::class.java, "packages"
                            )
                                .getImmutableList()
                        )
                    } catch (e: EvalException) {
                        throw RuleErrorException(e)
                    }
                } else {
                    throw RuleErrorException("expected JspecifyInfo, got: " + Starlark.type(value))
                }
            }
        }
    }

    private class RulesJavaProvider :
        Provider(BzlLoadValue.keyForBuild(Label.parseCanonicalUnchecked("//java/common/rules:java_toolchain.bzl")))

    private open class Provider(
        key: BzlLoadValue.Key? = BzlLoadValue.keyForBuild(
            Label.parseCanonicalUnchecked(
                JavaSemantics.Companion.RULES_JAVA_PROVIDER_LABELS_PREFIX
                        + "java/common/rules:java_toolchain.bzl"
            )
        )
    ) : StarlarkProviderWrapper<JavaToolchainProvider?>(key, "JavaToolchainInfo") {
        @Throws(RuleErrorException::class)
        public override fun wrap(value: Info): JavaToolchainProvider {
            if (value is StarlarkInfoWithSchema
                && value.getProvider().getKey().equals(getKey())
            ) {
                return JavaToolchainProvider(value as StarlarkInfo)
            } else {
                throw RuleErrorException(
                    "got value of type '" + Starlark.type(value) + "', want 'JavaToolchainInfo'"
                )
            }
        }
    }

    companion object {
        val RULES_JAVA_PROVIDER: StarlarkProviderWrapper<JavaToolchainProvider?> = RulesJavaProvider()
        @kotlin.jvm.JvmField
        val PROVIDER: StarlarkProviderWrapper<JavaToolchainProvider?> = Provider()

        @Throws(RuleErrorException::class)
        fun wrap(info: Info): JavaToolchainProvider {
            val key: com.google.devtools.build.lib.packages.Provider.Key = info.getProvider().getKey()
            if (key.equals(PROVIDER.getKey())) {
                return PROVIDER.wrap(info)
            } else if (key.equals(RULES_JAVA_PROVIDER.getKey())) {
                return RULES_JAVA_PROVIDER.wrap(info)
            } else {
                throw RuleErrorException("expected JavaToolchainInfo, got: " + key)
            }
        }

        /** Returns the Java Toolchain associated with the rule being analyzed or `null`.  */
        fun from(ruleContext: RuleContext): JavaToolchainProvider? {
            val toolchainInfo: ToolchainInfo? =
                ruleContext.getToolchainInfo(
                    ruleContext.getPrerequisite("\$java_toolchain_type").getLabel()
                )
            return from(toolchainInfo, ruleContext)
        }

        @VisibleForTesting
        fun from(collection: ProviderCollection): JavaToolchainProvider? {
            val toolchainInfo: ToolchainInfo? = collection.get(ToolchainInfo.PROVIDER)
            return from(toolchainInfo, null)
        }

        private fun from(
            toolchainInfo: ToolchainInfo?, errorConsumer: RuleErrorConsumer?
        ): JavaToolchainProvider? {
            if (toolchainInfo != null) {
                try {
                    val provider: JavaToolchainProvider? =
                        wrap(toolchainInfo.getValue("java", Info::class.java))
                    if (provider != null) {
                        return provider
                    }
                } catch (e: EvalException) {
                    if (errorConsumer != null) {
                        errorConsumer.ruleError(
                            java.lang.String.format("There was an error reading the Java toolchain: %s", e)
                        )
                    }
                } catch (e: RuleErrorException) {
                    if (errorConsumer != null) {
                        errorConsumer.ruleError(
                            java.lang.String.format("There was an error reading the Java toolchain: %s", e)
                        )
                    }
                }
            }
            if (errorConsumer != null) {
                errorConsumer.ruleError("The selected Java toolchain is not a JavaToolchainProvider")
            }
            return null
        }
    }
}
