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

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Objects
import java.util.Optional
import java.util.function.Predicate
import java.util.stream.Stream

/** Java compilation action builder.  */
class JavaCompileActionBuilder(ruleContext: RuleContext, toolchain: JavaToolchainProvider, execGroup: String?) {
    @ThreadCompatible
    @ThreadSafety.Immutable
    private class JavaCompileExtraActionInfoSupplier
        (
        outputJar: Artifact,
        classpathEntries: NestedSet<Artifact?>,
        bootclasspathEntries: NestedSet<Artifact?>,
        system: Optional<PathFragment?>?,
        processorPath: NestedSet<Artifact?>,
        processorNames: NestedSet<String?>,
        sourceJars: ImmutableList<Artifact?>?,
        sourceFiles: ImmutableSet<Artifact?>?,
        javacOpts: ImmutableList<String?>?
    ) : ExtraActionInfoSupplier {
        private val outputJar: Artifact

        /** The list of classpath entries to specify to javac.  */
        private val classpathEntries: NestedSet<Artifact?>

        /** The list of bootclasspath entries to specify to javac.  */
        private val bootclasspathEntries: NestedSet<Artifact?>

        /** An argument to the javac >= 9 `--system` flag.  */
        private val system: Optional<PathFragment?>?

        /** The list of classpath entries to search for annotation processors.  */
        private val processorPath: NestedSet<Artifact?>

        /** The list of annotation processor classes to run.  */
        private val processorNames: NestedSet<String?>

        /** Set of additional Java source files to compile.  */
        private val sourceJars: ImmutableList<Artifact?>?

        /** The set of explicit Java source files to compile.  */
        private val sourceFiles: ImmutableSet<Artifact?>?

        /** The compiler options to pass to javac.  */
        private val javacOpts: ImmutableList<String?>?

        init {
            this.outputJar = outputJar
            this.classpathEntries = classpathEntries
            this.bootclasspathEntries = bootclasspathEntries
            this.system = system
            this.processorPath = processorPath
            this.processorNames = processorNames
            this.sourceJars = sourceJars
            this.sourceFiles = sourceFiles
            this.javacOpts = javacOpts
        }

        override fun extend(builder: ExtraActionInfo.Builder, arguments: ImmutableList<String?>?) {
            val info: JavaCompileInfo.Builder =
                JavaCompileInfo.newBuilder()
                    .addAllSourceFile(Artifact.toExecPaths(sourceFiles))
                    .addAllClasspath(Artifact.toExecPaths(classpathEntries.toList()))
                    .addAllBootclasspath(Artifact.toExecPaths(bootclasspathEntries.toList()))
                    .addAllSourcepath(Artifact.toExecPaths(sourceJars))
                    .addAllJavacOpt(javacOpts)
                    .addAllProcessor(processorNames.toList())
                    .addAllProcessorpath(Artifact.toExecPaths(processorPath.toList()))
                    .setOutputjar(outputJar.getExecPathString())
            if (system!!.isPresent()) {
                info.setSystem(system.get().toString())
            }
            info.addAllArgument(arguments)
            builder.setExtension(JavaCompileInfo.javaCompileInfo, info.build())
        }
    }

    private val ruleContext: RuleContext
    private val toolchain: JavaToolchainProvider
    private val execGroup: String?
    private var additionalOutputs: ImmutableSet<Artifact?> = ImmutableSet.of<Artifact?>()
    private var coverageArtifact: Artifact? = null
    private var sourceFiles: ImmutableSet<Artifact?> = ImmutableSet.of<Artifact?>()
    private var sourceJars: ImmutableList<Artifact?> = ImmutableList.of<Artifact?>()
    private var strictJavaDeps: StrictDepsMode = StrictDepsMode.ERROR
    private var fixDepsTool: String? = "add_dep"
    private var directJars: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
    private var compileTimeDependencyArtifacts: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    private var javacOpts: ImmutableList<String?> = ImmutableList.of<String?>()
    private var utf8Environment: ImmutableMap<String?, String?>? = null
    private var executionInfo: ImmutableMap<String?, String?>? = ImmutableMap.of<String?, String?>()
    private var compressJar = false
    private var classpathEntries: NestedSet<Artifact?> = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
    private var bootClassPath: BootClassPathInfo = BootClassPathInfo.Companion.empty()
    private var sourcePathEntries: ImmutableList<Artifact?> = ImmutableList.of<Artifact?>()
    private var javaBuilder: JavaToolchainTool? = null
    private var toolsJars: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
    private var plugins: JavaPluginData = JavaPluginData.Companion.empty()
    private var extraData: NestedSet<Artifact?> = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
    private var targetLabel: Label? = null
    private var injectingRuleKind: String? = null
    private var additionalInputs: ImmutableList<Artifact?> = ImmutableList.of<Artifact?>()
    private var genSourceOutput: Artifact? = null
    private var outputs: JavaCompileOutputs<Artifact?>? = null
    private var classpathMode: JavaClasspathMode? = null
    private var manifestOutput: Artifact? = null

    init {
        this.ruleContext = ruleContext
        this.toolchain = toolchain
        this.execGroup = execGroup
    }

    @Throws(RuleErrorException::class, InterruptedException::class)
    fun build(): JavaCompileAction {
        Preconditions.checkNotNull<ImmutableMap<String?, String?>?>(utf8Environment, "utf8Environment must not be null")

        // TODO(bazel-team): all the params should be calculated before getting here, and the various
        // aggregation code below should go away.

        // Invariant: if strictJavaDeps is OFF, then directJars and
        // dependencyArtifacts are ignored
        if (strictJavaDeps === StrictDepsMode.OFF) {
            directJars = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
            compileTimeDependencyArtifacts = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        // Invariant: if java_classpath is set to 'off', dependencyArtifacts are ignored
        if (!Collections.disjoint(
                plugins.processorClasses().toSet(),
                toolchain.getReducedClasspathIncompatibleProcessors()
            )
        ) {
            classpathMode = JavaClasspathMode.OFF
        }
        if (classpathMode == JavaClasspathMode.OFF) {
            compileTimeDependencyArtifacts = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        }

        val toolsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.compileOrder()
        javaBuilder!!.addInputs(toolsBuilder)
        toolsBuilder.addTransitive(toolsJars)

        val mandatoryInputsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        mandatoryInputsBuilder
            .addTransitive(plugins.processorClasspath())
            .addTransitive(plugins.data())
            .addTransitive(extraData)
            .addAll(sourceJars)
            .addAll(sourceFiles)
            .addTransitive(toolchain.getJavaRuntime().javaBaseInputs())
            .addTransitive(bootClassPath.bootclasspath())
            .addAll(sourcePathEntries)
            .addAll(additionalInputs)
            .addTransitive(bootClassPath.systemInputs())
        if (coverageArtifact != null) {
            mandatoryInputsBuilder.add(coverageArtifact)
        }

        val extraActionInfoSupplier =
            JavaCompileExtraActionInfoSupplier(
                outputs!!.output(),
                classpathEntries,
                bootClassPath.bootclasspath(),
                bootClassPath.systemPath(),
                plugins.processorClasspath(),
                plugins.processorClasses(),
                sourceJars,
                sourceFiles,
                javacOpts
            )

        // TODO(b/123076347): outputDepsProto should never be null if SJD is enabled
        if (strictJavaDeps === StrictDepsMode.OFF || outputs!!.depsProto() == null) {
            classpathMode = JavaClasspathMode.OFF
        }

        val tools: NestedSet<Artifact?>? = toolsBuilder.build()
        mandatoryInputsBuilder.addTransitive(tools)
        val mandatoryInputs: NestedSet<Artifact?>? = mandatoryInputsBuilder.build()

        val executableLine: CustomCommandLine? = javaBuilder!!.getCommandLine()

        val actionEnvironment: ActionEnvironment? =
            ruleContext
                .getConfiguration()
                .getActionEnvironment()
                .withAdditionalFixedVariables(utf8Environment)

        return JavaCompileAction( /* compilationType= */
            CompilationType.JAVAC,  /* owner= */
            ruleContext.getActionOwner(execGroup),  /* tools= */
            tools,  /* progressMessage= */
            JavaCompileProgressMessage( /* output= */
                outputs!!.output(),  /* sourceFiles= */
                sourceFiles,  /* sourceJars= */
                sourceJars,  /* plugins= */
                plugins
            ),  /* mandatoryInputs= */
            mandatoryInputs,  /* transitiveInputs= */
            classpathEntries,  /* directJars= */
            directJars,  /* outputs= */
            allOutputs(),  /* env= */
            actionEnvironment,  /* executionInfo= */
            executionInfo,  /* extraActionInfoSupplier= */
            extraActionInfoSupplier,  /* executableLine= */
            executableLine,  /* flagLine= */
            buildParamFileContents(javacOpts),  /* configuration= */
            ruleContext.getConfiguration(),  /* dependencyArtifacts= */
            compileTimeDependencyArtifacts,  /* outputDepsProto= */
            outputs!!.depsProto(),  /* classpathMode= */
            classpathMode
        )
    }

    private fun allOutputs(): ImmutableSet<Artifact?> {
        val result: ImmutableSet.Builder<Artifact?> =
            ImmutableSet.builder<Artifact?>().add(outputs!!.output()).addAll(additionalOutputs)
        Stream.of<Any?>(outputs!!.depsProto(), outputs!!.nativeHeader(), genSourceOutput, manifestOutput)
            .filter(Predicate { obj: Any? -> Objects.nonNull(obj) })
            .forEachOrdered(result::add)
        return result.build()
    }

    @Throws(RuleErrorException::class, InterruptedException::class)
    private fun buildParamFileContents(javacOpts: ImmutableList<String?>): CustomCommandLine {
        val result: CustomCommandLine.Builder = CustomCommandLine.builder()

        result.addExecPath("--output", outputs!!.output())
        result.addExecPath("--native_header_output", outputs!!.nativeHeader())
        result.addExecPath("--generated_sources_output", genSourceOutput)
        result.addExecPath("--output_manifest_proto", manifestOutput)
        if (compressJar) {
            result.add("--compress_jar")
        }
        result.addExecPath("--output_deps_proto", outputs!!.depsProto())
        result.addExecPaths("--bootclasspath", bootClassPath.bootclasspath())
        if (bootClassPath.systemPath().isPresent()) {
            result.addPath("--system", bootClassPath.systemPath().get())
        }
        result.addExecPaths("--sourcepath", sourcePathEntries)
        result.addExecPaths("--processorpath", plugins.processorClasspath())
        result.addAll("--processors", plugins.processorClasses())
        result.addExecPaths("--source_jars", sourceJars)
        result.addExecPaths("--sources", sourceFiles)
        if (!javacOpts.isEmpty()) {
            result.add("--javacopts").addObject(javacOpts)
            // terminate --javacopts with `--` to support javac flags that start with `--`
            result.add("--")
        }
        if (targetLabel != null) {
            result.add("--target_label")
            if (targetLabel.getRepository().isMain()) {
                result.addLabel(targetLabel)
            } else {
                // @-prefixed strings will be assumed to be filenames and expanded by
                // {@link JavaLibraryBuildRequest}, so add an extra &at; to escape it.
                result.addPrefixedLabel(
                    "@", targetLabel, ruleContext.getAnalysisEnvironment().getMainRepoMapping()
                )
            }
        }
        result.add("--injecting_rule_kind", injectingRuleKind)
        // strict_java_deps controls whether the mapping from jars to targets is
        // written out and whether we try to minimize the compile-time classpath.
        if (strictJavaDeps !== StrictDepsMode.OFF) {
            result.add("--strict_java_deps", strictJavaDeps.toString())
            result.addExecPaths("--direct_dependencies", directJars)
        }
        result.add("--experimental_fix_deps_tool", fixDepsTool)

        // Chose what artifact to pass to JavaBuilder, as input to jacoco instrumentation processor.
        if (coverageArtifact != null) {
            result.add("--post_processor")
            result.addExecPath(JACOCO_INSTRUMENTATION_PROCESSOR, coverageArtifact)
        }
        return result.build()
    }

    @CanIgnoreReturnValue
    fun setAdditionalOutputs(outputs: ImmutableSet<Artifact?>): JavaCompileActionBuilder {
        this.additionalOutputs = outputs
        return this
    }

    @CanIgnoreReturnValue
    fun setSourceFiles(sourceFiles: ImmutableSet<Artifact?>): JavaCompileActionBuilder {
        this.sourceFiles = sourceFiles
        return this
    }

    @CanIgnoreReturnValue
    fun setSourceJars(sourceJars: ImmutableList<Artifact?>?): JavaCompileActionBuilder {
        Preconditions.checkState(this.sourceJars.isEmpty())
        this.sourceJars =
            Preconditions.checkNotNull<ImmutableList<Artifact?>>(sourceJars, "sourceJars must not be null")
        return this
    }

    /** Sets the strictness of Java dependency checking, see [StrictDepsMode].  */
    @CanIgnoreReturnValue
    fun setStrictJavaDeps(strictDeps: StrictDepsMode): JavaCompileActionBuilder {
        strictJavaDeps = strictDeps
        return this
    }

    /** Sets the tool with which to fix dependency errors.  */
    @CanIgnoreReturnValue
    fun setFixDepsTool(depsTool: String?): JavaCompileActionBuilder {
        fixDepsTool = depsTool
        return this
    }

    /** Accumulates the given jar artifacts as being provided by direct dependencies.  */
    @CanIgnoreReturnValue
    fun setDirectJars(directJars: NestedSet<Artifact?>?): JavaCompileActionBuilder {
        this.directJars = Preconditions.checkNotNull<NestedSet<Artifact?>?>(directJars, "directJars must not be null")
        return this
    }

    @CanIgnoreReturnValue
    fun setCompileTimeDependencyArtifacts(
        dependencyArtifacts: NestedSet<Artifact?>?
    ): JavaCompileActionBuilder {
        Preconditions.checkNotNull<Any?>(compileTimeDependencyArtifacts, "dependencyArtifacts must not be null")
        this.compileTimeDependencyArtifacts = dependencyArtifacts
        return this
    }

    @CanIgnoreReturnValue
    fun setJavacOpts(copts: ImmutableList<String?>?): JavaCompileActionBuilder {
        this.javacOpts = Preconditions.checkNotNull<ImmutableList<String?>>(copts)
        return this
    }

    @CanIgnoreReturnValue
    fun setUtf8Environment(utf8Environment: ImmutableMap<String?, String?>?): JavaCompileActionBuilder {
        this.utf8Environment = Preconditions.checkNotNull<ImmutableMap<String?, String?>?>(utf8Environment)
        return this
    }

    @CanIgnoreReturnValue
    fun setJavacExecutionInfo(
        executionInfo: ImmutableMap<String?, String?>?
    ): JavaCompileActionBuilder {
        this.executionInfo = executionInfo
        return this
    }

    @CanIgnoreReturnValue
    fun setCompressJar(compressJar: Boolean): JavaCompileActionBuilder {
        this.compressJar = compressJar
        return this
    }

    @CanIgnoreReturnValue
    fun setClasspathEntries(classpathEntries: NestedSet<Artifact?>): JavaCompileActionBuilder {
        this.classpathEntries = classpathEntries
        return this
    }

    @CanIgnoreReturnValue
    fun setBootClassPath(bootClassPath: BootClassPathInfo): JavaCompileActionBuilder {
        this.bootClassPath = bootClassPath
        return this
    }

    @CanIgnoreReturnValue
    fun setSourcePathEntries(sourcePathEntries: ImmutableList<Artifact?>?): JavaCompileActionBuilder {
        this.sourcePathEntries = Preconditions.checkNotNull<ImmutableList<Artifact?>>(sourcePathEntries)
        return this
    }

    @CanIgnoreReturnValue
    fun setPlugins(plugins: JavaPluginData): JavaCompileActionBuilder {
        Preconditions.checkNotNull<JavaPluginData?>(plugins, "plugins must not be null")
        Preconditions.checkState(this.plugins.isEmpty())
        this.plugins = plugins
        return this
    }

    fun setExtraData(extraData: NestedSet<Artifact?>) {
        Preconditions.checkNotNull<Any?>(extraData, "extraData must not be null")
        Preconditions.checkState(this.extraData.isEmpty())
        this.extraData = extraData
    }

    /** Sets the tools jars.  */
    @CanIgnoreReturnValue
    fun setToolsJars(toolsJars: NestedSet<Artifact?>?): JavaCompileActionBuilder {
        Preconditions.checkNotNull<Any?>(toolsJars, "toolsJars must not be null")
        this.toolsJars = toolsJars
        return this
    }

    @CanIgnoreReturnValue
    fun setJavaBuilder(javaBuilder: JavaToolchainTool): JavaCompileActionBuilder {
        this.javaBuilder = javaBuilder
        return this
    }

    @CanIgnoreReturnValue
    fun setCoverageArtifact(coverageArtifact: Artifact?): JavaCompileActionBuilder {
        this.coverageArtifact = coverageArtifact
        return this
    }

    @CanIgnoreReturnValue
    fun setTargetLabel(targetLabel: Label?): JavaCompileActionBuilder {
        this.targetLabel = targetLabel
        return this
    }

    @CanIgnoreReturnValue
    fun setInjectingRuleKind(injectingRuleKind: String?): JavaCompileActionBuilder {
        this.injectingRuleKind = injectingRuleKind
        return this
    }

    @CanIgnoreReturnValue
    fun setAdditionalInputs(additionalInputs: ImmutableList<Artifact?>): JavaCompileActionBuilder {
        Preconditions.checkNotNull<ImmutableList<Artifact?>?>(additionalInputs, "additionalInputs must not be null")
        this.additionalInputs = additionalInputs
        return this
    }

    fun setGenSourceOutput(genSourceOutput: Artifact?) {
        this.genSourceOutput = genSourceOutput
    }

    fun setOutputs(outputs: JavaCompileOutputs<Artifact?>) {
        this.outputs = outputs
    }

    fun setClasspathMode(classpathMode: JavaClasspathMode?) {
        this.classpathMode = classpathMode
    }

    fun setManifestOutput(manifestOutput: Artifact?) {
        this.manifestOutput = manifestOutput
    }

    private class JavaCompileProgressMessage(
        output: Artifact?,
        sourceFiles: ImmutableSet<Artifact?>,
        sourceJars: ImmutableList<Artifact?>,
        plugins: JavaPluginData
    ) : ProgressMessage(output, sourceFiles, sourceJars, plugins) {
        override fun prefix(): String {
            return PROGRESS_MESSAGE_PREFIX
        }
    }

    companion object {
        private const val JACOCO_INSTRUMENTATION_PROCESSOR = "jacoco"
        private const val PROGRESS_MESSAGE_PREFIX = "Building"

        const val MNEMONIC: String = "Javac"

        /** Returns true if this is a Java compile action.  */
        fun isJavaCompileAction(action: ActionAnalysisMetadata?): Boolean {
            return action != null && action.getMnemonic().equals(MNEMONIC)
        }
    }
}
