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

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Ascii
import com.google.common.base.Predicates
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.actions.Artifact
import net.starlark.java.eval.*
import java.util.function.Function
import java.util.function.Predicate

/** A module that contains Starlark utilities for Java support.  */
class JavaStarlarkCommon
    (private val javaSemantics: JavaSemantics) :
    JavaCommonApi<Artifact?, ConstraintValueInfo?, StarlarkRuleContext?, StarlarkActionFactory?> {
    @Throws(EvalException::class, LabelSyntaxException::class)
    private fun checkJavaToolchainIsDeclaredOnRule(ruleContext: RuleContext) {
        val toolchainInfo: ToolchainInfo? =
            ruleContext.getToolchainInfo(Label.parseCanonical(javaSemantics.getJavaToolchainType()))
        if (toolchainInfo == null) {
            val ruleLocation: String? = ruleContext.getRule().getLocation().toString()
            val ruleClass: String? = ruleContext.getRule().getRuleClassObject().getName()
            throw Starlark.errorf(
                "Rule '%s' in '%s' must declare '%s' toolchain in order to use java_common. See"
                        + " https://github.com/bazelbuild/bazel/issues/18970.",
                ruleClass, ruleLocation, javaSemantics.getJavaToolchainType()
            )
        }
    }

    @Throws(EvalException::class, LabelSyntaxException::class)
    override fun checkJavaToolchainIsDeclaredOnRuleForStarlark(
        actions: StarlarkActionFactory, thread: StarlarkThread?
    ) {
        checkPrivateAccess(thread)
        checkJavaToolchainIsDeclaredOnRule(actions.getRuleContext())
    }

    @Throws(
        EvalException::class,
        TypeException::class,
        RuleErrorException::class,
        LabelSyntaxException::class,
        InterruptedException::class
    )
    override fun createHeaderCompilationAction(
        ctx: StarlarkRuleContext,
        toolchain: Info,
        headerJar: Artifact?,
        headerDepsProto: Artifact?,
        pluginInfo: Info?,
        sourceFiles: Depset,
        sourceJars: Sequence<*>?,
        compileTimeClasspath: Depset,
        directJars: Depset,
        bootClassPathUnchecked: Any?,
        compileTimeJavaDeps: Depset,
        javacOpts: Depset?,
        strictDepsMode: String,
        targetLabel: Label?,
        injectingRuleKind: Any?,
        enableDirectClasspath: Boolean,
        additionalInputs: Sequence<*>?,
        headerCompilationJar: Artifact?,
        headerCompilationDirectDeps: Depset
    ) {
        checkJavaToolchainIsDeclaredOnRule(ctx.getRuleContext())
        val attributesBuilder =
            JavaTargetAttributes.Builder()
                .addSourceJars(Sequence.cast<Artifact?>(sourceJars, Artifact::class.java, "source_jars"))
                .addSourceFiles(sourceFiles.toList(Artifact::class.java))
                .addDirectJars(directJars.getSet(Artifact::class.java))
                .addHeaderCompilationDirectJars(headerCompilationDirectDeps.getSet(Artifact::class.java))
                .setCompileTimeClassPathEntriesWithPrependedDirectJars(
                    compileTimeClasspath.getSet(Artifact::class.java)
                )
                .setStrictJavaDeps(getStrictDepsMode(Ascii.toUpperCase(strictDepsMode)))
                .setTargetLabel(targetLabel)
                .setInjectingRuleKind(
                    if (injectingRuleKind === Starlark.NONE) null else injectingRuleKind as String?
                )
                .addPlugin(JavaPluginInfo.Companion.wrap(pluginInfo))
                .addCompileTimeDependencyArtifacts(compileTimeJavaDeps.getSet(Artifact::class.java))
        if (bootClassPathUnchecked is Info) {
            val bootClassPathInfo: BootClassPathInfo = BootClassPathInfo.Companion.wrap(bootClassPathUnchecked as Info?)
            if (!bootClassPathInfo.isEmpty()) {
                attributesBuilder.setBootClassPath(bootClassPathInfo)
            }
        }
        val compilationHelper =
            JavaCompilationHelper(
                ctx.getRuleContext(),
                javaSemantics,
                tokenizeJavaOptions(Depset.cast(javacOpts, String::class.java, "javac_opts")),
                attributesBuilder,
                JavaToolchainProvider.Companion.wrap(toolchain),
                Sequence.cast<Artifact?>(additionalInputs, Artifact::class.java, "additional_inputs")
                    .getImmutableList()
            )
        compilationHelper.enableDirectClasspath(enableDirectClasspath)
        compilationHelper.createHeaderCompilationAction(
            headerJar, headerCompilationJar, headerDepsProto
        )
    }

    @Throws(
        EvalException::class,
        TypeException::class,
        RuleErrorException::class,
        LabelSyntaxException::class,
        InterruptedException::class
    )
    override fun createCompilationAction(
        ctx: StarlarkRuleContext,
        javaToolchain: Info,
        output: Artifact?,
        manifestProto: Artifact?,
        pluginInfo: Info?,
        compileTimeClasspath: Depset,
        directJars: Depset,
        bootClassPathUnchecked: Any?,
        javaBuilderJvmFlags: Depset?,
        compileTimeJavaDeps: Depset,
        javacOpts: Depset?,
        strictDepsMode: String,
        targetLabel: Label?,
        depsProto: Any?,
        genClass: Any?,
        genSource: Any?,
        nativeHeader: Any?,
        sourceFiles: Any?,
        sourceJars: Sequence<*>?,
        resources: Sequence<*>?,
        resourceJars: Any?,
        classpathResources: Sequence<*>?,
        sourcepath: Sequence<*>?,
        injectingRuleKind: Any?,
        enableJSpecify: Boolean,
        enableDirectClasspath: Boolean,
        additionalInputs: Sequence<*>?,
        additionalOutputs: Sequence<*>?
    ) {
        checkJavaToolchainIsDeclaredOnRule(ctx.getRuleContext())
        val outputs: JavaCompileOutputs<Artifact?> =
            JavaCompileOutputs.Companion.builder<Artifact?>()
                .output(output)
                .depsProto(if (depsProto === Starlark.NONE) null else depsProto as Artifact?)
                .genClass(if (genClass === Starlark.NONE) null else genClass as Artifact?)
                .genSource(if (genSource === Starlark.NONE) null else genSource as Artifact?)
                .nativeHeader(if (nativeHeader === Starlark.NONE) null else nativeHeader as Artifact?)
                .manifestProto(manifestProto)
                .build()
        val attributesBuilder =
            JavaTargetAttributes.Builder()
                .addSourceJars(Sequence.cast<Artifact?>(sourceJars, Artifact::class.java, "source_jars"))
                .addSourceFiles(Depset.noneableCast(sourceFiles, Artifact::class.java, "sources").toList())
                .addDirectJars(directJars.getSet(Artifact::class.java))
                .setCompileTimeClassPathEntriesWithPrependedDirectJars(
                    compileTimeClasspath.getSet(Artifact::class.java)
                )
                .addClassPathResources(
                    Sequence.cast<Artifact?>(classpathResources, Artifact::class.java, "classpath_resources")
                )
                .setStrictJavaDeps(getStrictDepsMode(Ascii.toUpperCase(strictDepsMode)))
                .setTargetLabel(targetLabel)
                .setInjectingRuleKind(
                    if (injectingRuleKind === Starlark.NONE) null else injectingRuleKind as String?
                )
                .setSourcePath(
                    Sequence.cast<Artifact?>(sourcepath, Artifact::class.java, "source_path").getImmutableList()
                )
                .addPlugin(JavaPluginInfo.Companion.wrap(pluginInfo))
                .addAdditionalOutputs(
                    Sequence.cast<Artifact?>(additionalOutputs, Artifact::class.java, "additional_outputs")
                )
        if (bootClassPathUnchecked is Info) {
            val bootClassPathInfo: BootClassPathInfo = BootClassPathInfo.Companion.wrap(bootClassPathUnchecked as Info?)
            if (!bootClassPathInfo.isEmpty()) {
                attributesBuilder.setBootClassPath(bootClassPathInfo)
            }
        }
        for (resource in Sequence.cast<Artifact>(resources, Artifact::class.java, "resources")) {
            attributesBuilder.addResource(
                JavaHelper.getJavaResourcePath(javaSemantics, ctx.getRuleContext(), resource), resource
            )
        }
        attributesBuilder.addResourceJars(
            Depset.noneableCast(resourceJars, Artifact::class.java, "resource_jars")
        )
        attributesBuilder.addCompileTimeDependencyArtifacts(compileTimeJavaDeps.getSet(Artifact::class.java))
        val compilationHelper =
            JavaCompilationHelper(
                ctx.getRuleContext(),
                javaSemantics,
                tokenizeJavaOptions(Depset.cast(javacOpts, String::class.java, "javac_opts")),
                attributesBuilder,
                JavaToolchainProvider.Companion.wrap(javaToolchain),
                Sequence.cast<Artifact?>(additionalInputs, Artifact::class.java, "additional_inputs")
                    .getImmutableList()
            )
        compilationHelper.javaBuilderJvmFlags(
            Depset.cast(javaBuilderJvmFlags, String::class.java, "javabuilder_jvm_flags")
        )
        compilationHelper.enableJspecify(enableJSpecify)
        compilationHelper.enableDirectClasspath(enableDirectClasspath)
        compilationHelper.createCompileAction(outputs)
    }

    @Throws(EvalException::class)
    override fun getTargetKind(target: Any?, thread: StarlarkThread?): String? {
        var target = target
        checkPrivateAccess(thread)
        if (target is MergedConfiguredTarget) {
            target = target.getBaseConfiguredTarget()
        }
        if (target is ConfiguredTarget) {
            target = target.getActual()
        }
        if (target is AbstractConfiguredTarget) {
            return target.getRuleClassString()
        }
        return ""
    }

    @Throws(EvalException::class, TypeException::class)
    override fun collectNativeLibsDirs(libraries: Depset, thread: StarlarkThread?): Sequence<String?>? {
        checkPrivateAccess(thread)
        val nativeLibraries: ImmutableList<Artifact?> =
            getDynamicLibrariesForLinking(libraries.getSet(StarlarkInfo::class.java))
        val uniqueDirs: ImmutableList<String?> =
            nativeLibraries.stream()
                .filter(
                    Predicate { nativeLibrary: Artifact? ->
                        val name: String? = nativeLibrary.getFilename()
                        if (CppFileTypes.INTERFACE_SHARED_LIBRARY.matches(name)) {
                            return@filter false
                        }
                        require(
                            CppFileTypes.SHARED_LIBRARY.matches(name)
                                    || CppFileTypes.VERSIONED_SHARED_LIBRARY.matches(name)
                        ) { "not a shared library :" + nativeLibrary.prettyPrint() }
                        true
                    })
                .map<Any?>(Function { artifact: Artifact? ->
                    artifact.getRunfilesPath().getParentDirectory().getPathString()
                })
                .distinct()
                .collect(ImmutableList.toImmutableList<Any?>())
        return StarlarkList.immutableCopyOf<String?>(uniqueDirs)
    }

    @Throws(EvalException::class, TypeException::class)
    override fun getRuntimeClasspathForArchive(
        runtimeClasspath: Depset, excludedArtifacts: Depset, thread: StarlarkThread?
    ): Depset {
        checkPrivateAccess(thread)
        if (excludedArtifacts.isEmpty()) {
            return runtimeClasspath
        } else {
            return Depset.of(
                Artifact::class.java,
                NestedSetBuilder.wrap(
                    Order.STABLE_ORDER,
                    Iterables.filter(
                        runtimeClasspath.toList(Artifact::class.java),
                        Predicates.not<T?>(Predicates.`in`<T?>(excludedArtifacts.getSet().toSet()))
                    )
                )
            )
        }
    }

    @Throws(EvalException::class)
    override fun checkProviderInstances(
        providers: Sequence<*>, what: String?, providerType: ProviderApi, thread: StarlarkThread?
    ) {
        checkPrivateAccess(thread)
        if (providerType is Provider) {
            for (i in providers.indices) {
                val elem: Any = providers.get(i)
                if (!isInstanceOfProvider(elem, providerType as Provider?)) {
                    throw Starlark.errorf(
                        "at index %d of %s, got element of type %s, want %s",
                        i, what, printableType(elem), (providerType as Provider).getPrintableName()
                    )
                }
            }
        } else {
            throw Starlark.errorf("wanted Provider, got %s", Starlark.type(providerType))
        }
    }

    @Throws(EvalException::class)
    override fun isLegacyGoogleApiEnabled(thread: StarlarkThread): Boolean {
        checkPrivateAccess(thread)
        return thread.getSemantics().getBool(BuildLanguageOptions.EXPERIMENTAL_GOOGLE_LEGACY_API)
    }

    @Throws(EvalException::class)
    override fun isJavaInfoMergeRuntimeModuleFlagsEnabled(thread: StarlarkThread): Boolean {
        checkPrivateAccess(thread)
        return thread
            .getSemantics()
            .getBool(BuildLanguageOptions.INCOMPATIBLE_JAVA_INFO_MERGE_RUNTIME_MODULE_FLAGS)
    }

    override fun incompatibleDisableNonExecutableJavaBinary(thread: StarlarkThread): Boolean {
        return thread
            .getSemantics()
            .getBool(BuildLanguageOptions.INCOMPATIBLE_DISABLE_NON_EXECUTABLE_JAVA_BINARY)
    }

    @Throws(InterruptedException::class)
    override fun expandJavaOpts(
        ctx: StarlarkRuleContext, attr: String?, tokenize: Boolean, execPaths: Boolean
    ): Sequence<*>? {
        val expander: Expander
        if (execPaths) {
            expander = ctx.getRuleContext().getExpander().withExecLocations(ImmutableMap.of<K?, V?>())
        } else {
            expander = ctx.getRuleContext().getExpander().withDataLocations()
        }
        if (tokenize) {
            return StarlarkList.immutableCopyOf<T?>(expander.tokenized(attr))
        } else {
            return StarlarkList.immutableCopyOf<T?>(expander.list(attr))
        }
    }

    @Throws(EvalException::class)
    override fun tokenizeJavacOpts(opts: Sequence<*>?): Sequence<*>? {
        return StarlarkList.immutableCopyOf<String?>(
            JavaHelper.tokenizeJavaOptions(Sequence.noneableCast<String?>(opts, String::class.java, "opts"))
        )
    }

    companion object {
        private fun getStrictDepsMode(strictDepsMode: String): StrictDepsMode {
            when (strictDepsMode) {
                "OFF" -> return StrictDepsMode.OFF
                "ERROR", "DEFAULT" -> return StrictDepsMode.ERROR
                "WARN" -> return StrictDepsMode.WARN
                else -> throw IllegalArgumentException(
                    ("StrictDepsMode "
                            + strictDepsMode
                            + " not allowed."
                            + " Only OFF and ERROR values are accepted.")
                )
            }
        }

        @Throws(EvalException::class)
        fun checkPrivateAccess(thread: StarlarkThread?) {
            BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        }

        private fun getDynamicLibrariesForLinking(
            libraries: NestedSet<StarlarkInfo?>
        ): ImmutableList<Artifact?> {
            val dynamicLibrariesForLinkingBuilder: ImmutableList.Builder<Artifact?> = ImmutableList.builder<Artifact?>()
            for (libraryToLink in libraries.toList()) {
                if (libraryToLink.getValue("interface_library") is Artifact) {
                    dynamicLibrariesForLinkingBuilder.add(artifact)
                } else if (libraryToLink.getValue("dynamic_library") is Artifact) {
                    dynamicLibrariesForLinkingBuilder.add(artifact)
                }
            }
            return dynamicLibrariesForLinkingBuilder.build()
        }

        @kotlin.jvm.JvmStatic
        @VisibleForTesting
        fun printableType(elem: Any): String? {
            if (elem is StarlarkInfoWithSchema) {
                return elem.getProvider().getPrintableName()
            } else if (elem is NativeInfo) {
                return elem.getProvider().getPrintableName()
            }
            return Starlark.type(elem)
        }

        fun isInstanceOfProvider(obj: Any?, provider: Provider): Boolean {
            if (obj is NativeInfo) {
                return obj.getProvider().getKey().equals(provider.getKey())
            } else if (obj is StarlarkInfoWithSchema) {
                return obj.getProvider().getKey().equals(provider.getKey())
            } else if (obj is StarlarkInfoNoSchema) {
                return obj.getProvider().getKey().equals(provider.getKey())
            }
            return false
        }
    }
}
