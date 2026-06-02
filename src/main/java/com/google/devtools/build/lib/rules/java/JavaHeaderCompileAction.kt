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

import com.google.common.base.Preconditions
import com.google.common.collect.*
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata.mergeMaps
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Objects
import java.util.function.Predicate
import java.util.stream.Stream

/**
 * Action for Java header compilation, to be used if --java_header_compilation is enabled.
 * 
 * 
 * The header compiler consumes the inputs of a java compilation, and produces an interface jar
 * that can be used as a compile-time jar by upstream targets. The header interface jar is
 * equivalent to the output of ijar, but unlike ijar the header compiler operates directly on Java
 * source files instead post-processing the class outputs of the compilation. Compiling the
 * interface jar from source moves javac off the build's critical path.
 * 
 * 
 * The implementation of the header compiler tool can be found under `//src/java_tools/buildjar/java/com/google/devtools/build/java/turbine`.
 */
class JavaHeaderCompileAction private constructor(
    owner: ActionOwner?,
    tools: NestedSet<Artifact?>?,
    inputs: NestedSet<Artifact?>?,
    outputs: Iterable<out Artifact?>?,
    resourceSetOrBuilder: ResourceSetOrBuilder?,
    commandLines: CommandLines?,
    env: ActionEnvironment?,
    executionInfo: ImmutableMap<String?, String?>?,
    progressMessage: CharSequence?,
    mnemonic: String?,
    outputPathsMode: OutputPathsMode?,
    private val insertDependencies: Boolean,
    private val inMemoryJdeps: Boolean,
    additionalArtifactsForPathMapping: NestedSet<Artifact?>?
) : SpawnAction(
    owner,
    tools,
    inputs,
    outputs,
    resourceSetOrBuilder,
    commandLines,
    env,
    executionInfo,
    progressMessage,
    mnemonic,
    outputPathsMode
) {
    private val additionalArtifactsForPathMapping: NestedSet<Artifact?>?

    init {
        this.additionalArtifactsForPathMapping = additionalArtifactsForPathMapping
    }

    val executionInfo: ImmutableMap<String?, String?>?
        get() {
            val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                super.getExecutionInfo()
            if (!inMemoryJdeps) {
                return result
            }
            val outputDepsProto: Artifact? = Iterables.get<T?>(getOutputs(), 1)
            return mergeMaps(
                result,
                ImmutableMap.of<K?, V?>(
                    ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS,
                    outputDepsProto.getExecPathString()
                )
            )
        }

    public override fun getAdditionalArtifactsForPathMapping(): NestedSet<Artifact?>? {
        return additionalArtifactsForPathMapping
    }

    protected override fun afterExecute(
        context: ActionExecutionContext, spawnResults: MutableList<SpawnResult?>, pathMapper: PathMapper
    ) {
        // The first entry represents the successful execution, see SpawnStrategy#exec
        val spawnResult: SpawnResult? = spawnResults.get(0)
        val outputDepsProto: Artifact? = Iterables.get<T?>(getOutputs(), 1)
        try {
            val fullOutputDeps: Deps.Dependencies? =
                JavaCompileAction.Companion.createFullOutputDeps(
                    spawnResult,
                    outputDepsProto,
                    getInputs(),
                    getAdditionalArtifactsForPathMapping(),
                    context,
                    pathMapper
                )
            val javaContext: JavaCompileActionContext? = context.getContext(JavaCompileActionContext::class.java)
            if (insertDependencies && javaContext != null) {
                javaContext.insertDependencies(outputDepsProto, fullOutputDeps)
            }
        } catch (e: IOException) {
            // Left empty. If we cannot read the .jdeps file now, we will read it later or throw an
            // appropriate error then.
        }
    }

    public override fun mayModifySpawnOutputsAfterExecution(): Boolean {
        // Causes of spawn output modification after execution:
        // - In-place rewriting of .jdeps files with --experimental_output_paths=strip.
        // TODO: Use separate files as action and spawn output to avoid in-place modification.
        return true
    }

    /** Builder for [JavaHeaderCompileAction].  */
    class Builder private constructor(ruleContext: RuleContext) {
        private val ruleContext: RuleContext

        private var outputJar: Artifact? = null
        private var headerCompilationOutputJar: Artifact? = null

        // Only non-null before set.
        private var outputDepsProto: Artifact? = null
        private var manifestOutput: Artifact? = null
        private var gensrcOutputJar: Artifact? = null
        private var resourceOutputJar: Artifact? = null
        private var additionalOutputs: ImmutableSet<Artifact?> = ImmutableSet.of<Artifact?>()
        private var sourceFiles: ImmutableSet<Artifact?> = ImmutableSet.of<Artifact?>()
        private var sourceJars: ImmutableList<Artifact?> = ImmutableList.of<Artifact?>()
        private var classpathEntries: NestedSet<Artifact?> = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
        private var bootclasspathEntries: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
        private var targetLabel: Label? = null
        private var injectingRuleKind: String? = null
        private var strictJavaDeps: StrictDepsMode? = StrictDepsMode.OFF
        private var directJars: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
        private var headerCompilationDirectJars: NestedSet<Artifact?> =
            NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
        private var compileTimeDependencyArtifacts: NestedSet<Artifact?>? =
            NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        private var javacOpts: ImmutableList<String?> = ImmutableList.of<String?>()
        private var plugins: JavaPluginData = JavaPluginData.Companion.empty()

        private var additionalInputs: ImmutableList<Artifact?> = ImmutableList.of<Artifact?>()
        private var toolsJars: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)

        private var enableHeaderCompilerDirect = true

        private var enableDirectClasspath = true

        private var execGroup: String? = DEFAULT_EXEC_GROUP_NAME

        private var utf8Environment: ImmutableMap<String?, String?>? = null

        private var parallelism = true

        private var fixDepsTool: String? = null

        init {
            this.ruleContext = ruleContext
        }

        @CanIgnoreReturnValue
        fun setFixDepsTool(fixDepsTool: String?): Builder {
            Preconditions.checkNotNull<String?>(fixDepsTool, "fixDepsTool must not be null")
            this.fixDepsTool = fixDepsTool
            return this
        }

        /** Sets the output jdeps file.  */
        @CanIgnoreReturnValue
        fun setOutputDepsProto(outputDepsProto: Artifact?): Builder {
            this.outputDepsProto = Preconditions.checkNotNull<Artifact?>(outputDepsProto)
            return this
        }

        @CanIgnoreReturnValue
        fun setManifestOutput(manifestOutput: Artifact?): Builder {
            this.manifestOutput = manifestOutput
            return this
        }

        @CanIgnoreReturnValue
        fun setGensrcOutputJar(gensrcOutputJar: Artifact?): Builder {
            this.gensrcOutputJar = gensrcOutputJar
            return this
        }

        @CanIgnoreReturnValue
        fun setResourceOutputJar(resourceOutputJar: Artifact?): Builder {
            this.resourceOutputJar = resourceOutputJar
            return this
        }

        /** Sets the direct dependency artifacts.  */
        @CanIgnoreReturnValue
        fun setDirectJars(directJars: NestedSet<Artifact?>?): Builder {
            Preconditions.checkNotNull<Any?>(directJars, "directJars must not be null")
            this.directJars = directJars
            return this
        }

        @CanIgnoreReturnValue
        fun setHeaderCompilationDirectJars(headerCompilationDirectJars: NestedSet<Artifact?>): Builder {
            Preconditions.checkNotNull<Any?>(
                headerCompilationDirectJars,
                "headerCompilationDirectJars must not be null"
            )
            this.headerCompilationDirectJars = headerCompilationDirectJars
            return this
        }

        /** Sets the .jdeps artifacts for direct dependencies.  */
        @CanIgnoreReturnValue
        fun setCompileTimeDependencyArtifacts(dependencyArtifacts: NestedSet<Artifact?>?): Builder {
            Preconditions.checkNotNull<Any?>(dependencyArtifacts, "dependencyArtifacts must not be null")
            this.compileTimeDependencyArtifacts = dependencyArtifacts
            return this
        }

        /** Sets Java compiler flags.  */
        @CanIgnoreReturnValue
        fun setJavacOpts(javacOpts: ImmutableList<String?>?): Builder {
            this.javacOpts = Preconditions.checkNotNull<ImmutableList<String?>>(javacOpts)
            return this
        }

        /** Sets the output jar.  */
        @CanIgnoreReturnValue
        fun setOutputJar(outputJar: Artifact?): Builder {
            Preconditions.checkNotNull<Any?>(outputJar, "outputJar must not be null")
            this.outputJar = outputJar
            return this
        }

        @CanIgnoreReturnValue
        fun setHeaderCompilationOutputJar(headerCompilationOutputJar: Artifact?): Builder {
            Preconditions.checkNotNull<Any?>(headerCompilationOutputJar, "headerCompilationOutputJar must not be null")
            this.headerCompilationOutputJar = headerCompilationOutputJar
            return this
        }

        @CanIgnoreReturnValue
        fun setAdditionalOutputs(outputs: ImmutableSet<Artifact?>): Builder {
            Preconditions.checkNotNull<ImmutableSet<Artifact?>?>(outputs, "outputs must not be null")
            this.additionalOutputs = outputs
            return this
        }

        /** Adds Java source files to compile.  */
        @CanIgnoreReturnValue
        fun setSourceFiles(sourceFiles: ImmutableSet<Artifact?>): Builder {
            Preconditions.checkNotNull<ImmutableSet<Artifact?>?>(sourceFiles, "sourceFiles must not be null")
            this.sourceFiles = sourceFiles
            return this
        }

        /** Adds a jar archive of Java sources to compile.  */
        @CanIgnoreReturnValue
        fun setSourceJars(sourceJars: ImmutableList<Artifact?>): Builder {
            Preconditions.checkNotNull<ImmutableList<Artifact?>?>(sourceJars, "sourceJars must not be null")
            this.sourceJars = sourceJars
            return this
        }

        /** Sets the compilation classpath entries.  */
        @CanIgnoreReturnValue
        fun setClasspathEntries(classpathEntries: NestedSet<Artifact?>): Builder {
            Preconditions.checkNotNull<Any?>(classpathEntries, "classpathEntries must not be null")
            this.classpathEntries = classpathEntries
            return this
        }

        /** Sets the compilation bootclasspath entries.  */
        @CanIgnoreReturnValue
        fun setBootclasspathEntries(bootclasspathEntries: NestedSet<Artifact?>?): Builder {
            Preconditions.checkNotNull<Any?>(bootclasspathEntries, "bootclasspathEntries must not be null")
            this.bootclasspathEntries = bootclasspathEntries
            return this
        }

        /** Sets the annotation processors classpath entries.  */
        @CanIgnoreReturnValue
        fun setPlugins(plugins: JavaPluginData): Builder {
            Preconditions.checkNotNull<JavaPluginData?>(plugins, "plugins must not be null")
            Preconditions.checkState(this.plugins.isEmpty())
            this.plugins = plugins
            return this
        }

        /** Sets the label of the target being compiled.  */
        @CanIgnoreReturnValue
        fun setTargetLabel(targetLabel: Label?): Builder {
            this.targetLabel = targetLabel
            return this
        }

        /** Sets the injecting rule kind of the target being compiled.  */
        @CanIgnoreReturnValue
        fun setInjectingRuleKind(injectingRuleKind: String?): Builder {
            this.injectingRuleKind = injectingRuleKind
            return this
        }

        /** Sets the Strict Java Deps mode.  */
        @CanIgnoreReturnValue
        fun setStrictJavaDeps(strictJavaDeps: StrictDepsMode?): Builder {
            Preconditions.checkNotNull<Any?>(strictJavaDeps, "strictJavaDeps must not be null")
            this.strictJavaDeps = strictJavaDeps
            return this
        }

        /** Sets additional inputs, e.g. for databinding support.  */
        @CanIgnoreReturnValue
        fun setAdditionalInputs(additionalInputs: ImmutableList<Artifact?>): Builder {
            Preconditions.checkNotNull<ImmutableList<Artifact?>?>(additionalInputs, "additionalInputs must not be null")
            this.additionalInputs = additionalInputs
            return this
        }

        /** Sets the tools jars.  */
        @CanIgnoreReturnValue
        fun setToolsJars(toolsJars: NestedSet<Artifact?>?): Builder {
            Preconditions.checkNotNull<Any?>(toolsJars, "toolsJars must not be null")
            this.toolsJars = toolsJars
            return this
        }

        /** Sets the exec group used for selecting execution platform of `JavaHeaderCompileAction`.  */
        @CanIgnoreReturnValue
        fun setExecGroup(execGroup: String?): Builder {
            Preconditions.checkNotNull<String?>(execGroup, "execGroup must not be null")
            this.execGroup = execGroup
            return this
        }

        @CanIgnoreReturnValue
        fun enableHeaderCompilerDirect(enableHeaderCompilerDirect: Boolean): Builder {
            this.enableHeaderCompilerDirect = enableHeaderCompilerDirect
            return this
        }

        @CanIgnoreReturnValue
        fun enableDirectClasspath(enableDirectClasspath: Boolean): Builder {
            this.enableDirectClasspath = enableDirectClasspath
            return this
        }

        @CanIgnoreReturnValue
        fun setUtf8Environment(utf8Environment: ImmutableMap<String?, String?>?): Builder {
            Preconditions.checkNotNull<ImmutableMap<String?, String?>?>(
                utf8Environment,
                "utf8Environment must not be null"
            )
            this.utf8Environment = utf8Environment
            return this
        }

        @CanIgnoreReturnValue
        fun enableParallelism(parallelism: Boolean): Builder {
            this.parallelism = parallelism
            return this
        }

        /** Builds and registers the action for a header compilation.  */
        @Throws(RuleErrorException::class, InterruptedException::class)
        fun build(javaToolchain: JavaToolchainProvider) {
            Object > Preconditions.checkNotNull<Any?>(outputDepsProto, "outputDepsProto must not be null")
            Preconditions.checkNotNull<ImmutableSet<Artifact?>?>(sourceFiles, "sourceFiles must not be null")
            Preconditions.checkNotNull<ImmutableList<Artifact?>?>(sourceJars, "sourceJars must not be null")
            Object > Preconditions.checkNotNull<Any?>(classpathEntries, "classpathEntries must not be null")
            Object > Preconditions.checkNotNull<Any?>(bootclasspathEntries, "bootclasspathEntries must not be null")
            Object > Preconditions.checkNotNull<Any?>(strictJavaDeps, "strictJavaDeps must not be null")
            Object > Preconditions.checkNotNull<Any?>(directJars, "directJars must not be null")
            Object > Preconditions.checkNotNull<Any?>(
                headerCompilationDirectJars,
                "headerCompilationDirectJars must not be null"
            )
            Object > Preconditions.checkNotNull<Any?>(
                compileTimeDependencyArtifacts, "compileTimeDependencyArtifacts must not be null"
            )
            Preconditions.checkNotNull<ImmutableMap<String?, String?>?>(
                utf8Environment,
                "utf8Environment must not be null"
            )

            // Invariant: if strictJavaDeps is OFF, then directJars and
            // dependencyArtifacts are ignored
            if (strictJavaDeps === StrictDepsMode.OFF) {
                directJars = NestedSetBuilder.emptySet(Order.NAIVE_LINK_ORDER)
                compileTimeDependencyArtifacts = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            }

            // Enable the direct classpath optimization if there are no annotation processors.
            // N.B. we only check if the processor classes are empty, we don't care if there is plugin
            // data or dependencies if there are no annotation processors to run. This differs from
            // javac where java_plugin may be used with processor_class unset to declare Error Prone
            // plugins.
            val useDirectClasspath = enableDirectClasspath && plugins.processorClasses().isEmpty()

            // Use the optimized 'direct' implementation if it is available, and either there are no
            // annotation processors or they are built in to the tool and listed in
            // java_toolchain.header_compiler_direct_processors.
            val processorClasses: ImmutableSet<String?> = plugins.processorClasses().toSet()
            val useHeaderCompilerDirect =
                enableHeaderCompilerDirect
                        && javaToolchain.getHeaderCompilerDirect() != null && javaToolchain.getHeaderCompilerBuiltinProcessors()
                    .containsAll(processorClasses)
            val javaConfiguration: JavaConfiguration =
                ruleContext.getConfiguration().getFragment(JavaConfiguration::class.java)
            val classpathMode: JavaClasspathMode? = javaConfiguration.getReduceJavaClasspath()
            if (!Collections.disjoint(
                    processorClasses, javaToolchain.getReducedClasspathIncompatibleProcessors()
                )
            ) {
                classpathMode = JavaClasspathMode.OFF
            }

            val actionEnvironment: ActionEnvironment? =
                ruleContext
                    .getConfiguration()
                    .getActionEnvironment()
                    .withAdditionalFixedVariables(utf8Environment)

            val progressMessage: OnDemandString =
                JavaHeaderCompileProgressMessage( /* output= */
                    outputJar,  /* sourceFiles= */
                    sourceFiles,  /* sourceJars= */
                    sourceJars,  /* plugins= */
                    plugins
                )

            val outputs: ImmutableSet.Builder<Artifact?> =
                ImmutableSet.builder<Artifact?>()
                    .add(outputJar)
                    .add(outputDepsProto)
                    .addAll(additionalOutputs)
            Stream.of<Any?>(gensrcOutputJar, resourceOutputJar, manifestOutput, headerCompilationOutputJar)
                .filter(Predicate { obj: Any? -> Objects.nonNull(obj) })
                .forEachOrdered(outputs::add)

            val mandatoryInputsBuilder: NestedSetBuilder<Artifact?> =
                NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                    .addAll(additionalInputs)
                    .addTransitive(bootclasspathEntries)
                    .addAll(sourceJars)
                    .addAll(sourceFiles)
                    .addTransitive(toolsJars)

            val headerCompiler =
                if (useHeaderCompilerDirect)
                    javaToolchain.getHeaderCompilerDirect()
                else
                    javaToolchain.getHeaderCompiler()
            // The header compiler is either a jar file that needs to be executed using
            // `java -jar <path>`, or an executable that can be run directly.
            headerCompiler!!.addInputs(mandatoryInputsBuilder)
            val commandLine: CustomCommandLine.Builder =
                CustomCommandLine.builder()
                    .addExecPath("--output", outputJar)
                    .addExecPath("--header_compilation_output", headerCompilationOutputJar)
                    .addExecPath("--gensrc_output", gensrcOutputJar)
                    .addExecPath("--resource_output", resourceOutputJar)
                    .addExecPath("--output_manifest_proto", manifestOutput)
                    .addExecPath("--output_deps", outputDepsProto)
                    .addExecPaths("--bootclasspath", bootclasspathEntries)
                    .addExecPaths("--sources", sourceFiles)
                    .addExecPaths("--source_jars", sourceJars)
                    .add("--injecting_rule_kind", injectingRuleKind)

            commandLine.add("--javacopts")
            if (!javacOpts.isEmpty()) {
                commandLine.addObject(javacOpts)
            }
            // See b/31371210, b/142059842, and b/464431616.
            commandLine.add("-Aexperimental_turbine_hjar")
            if (!parallelism) {
                commandLine.add("-XDnoParallel")
            }
            // terminate --javacopts with `--` to support javac flags that start with `--`
            commandLine.add("--")

            if (targetLabel != null) {
                commandLine.add("--target_label")
                if (targetLabel.getRepository().isMain()) {
                    commandLine.addLabel(targetLabel)
                } else {
                    // @-prefixed strings will be assumed to be params filenames and expanded,
                    // so add an extra @ to escape it.
                    commandLine.addPrefixedLabel(
                        "@", targetLabel, ruleContext.getAnalysisEnvironment().getMainRepoMapping()
                    )
                }
            }

            commandLine.add("--experimental_fix_deps_tool", fixDepsTool)

            val executionInfo = ImmutableMap.builder<String?, String?>()
            executionInfo.putAll(
                ruleContext
                    .getConfiguration()
                    .modifiedExecutionInfo(
                        ImmutableMap.of<K?, V?>(ExecutionRequirements.SUPPORTS_PATH_MAPPING, "1"),
                        JavaCompileActionBuilder.Companion.MNEMONIC
                    )
            )
            executionInfo.putAll(
                TargetUtils.getExecutionInfo(
                    ruleContext.getRule(), ruleContext.isAllowTagsPropagation()
                )
            )
            val cpuReservation = javaConfiguration.experimentalTurbineCpuReservation()
            if (cpuReservation > 1) {
                executionInfo.put("cpu:" + cpuReservation, "")
            }

            val actionOwner: ActionOwner? =
                if (ruleContext.useAutoExecGroups())
                    ruleContext.getActionOwner(execGroup)
                else
                    ruleContext.getActionOwner()

            if (useDirectClasspath) {
                val classpath: NestedSet<Artifact?>?
                val additionalArtifactsForPathMapping: NestedSet<Artifact?>?
                if (!headerCompilationDirectJars.isEmpty() || classpathEntries.isEmpty()) {
                    classpath = headerCompilationDirectJars
                    // When using the direct classpath optimization, Turbine generates .jdeps entries based on
                    // the transitive dependency information packages into META-INF/TRANSITIVE. When path
                    // mapping is used, these entries may have been subject to it when they were generated.
                    // Since the contents of that directory are not unmapped, we need to instead unmap the
                    // paths emitted in the .jdeps file, which requires knowing the full list of artifact
                    // paths even if they aren't inputs to the current action.
                    // https://github.com/google/turbine/commit/f9f2decee04a3c651671f7488a7c9d7952df88c8
                    additionalArtifactsForPathMapping = classpathEntries
                } else {
                    classpath = classpathEntries
                    additionalArtifactsForPathMapping = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
                }
                mandatoryInputsBuilder.addTransitive(classpath)

                commandLine.addExecPaths("--classpath", classpath)
                commandLine.add("--reduce_classpath_mode", "NONE")

                val allInputs: NestedSet<Artifact?>? = mandatoryInputsBuilder.build()
                val executableLine: CustomCommandLine? = headerCompiler.getCommandLine()

                ruleContext.registerAction(
                    JavaHeaderCompileAction( /* owner= */
                        actionOwner,  /* tools= */
                        NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* inputs= */
                        allInputs,  /* outputs= */
                        outputs.build(),  /* resourceSetOrBuilder= */
                        AbstractAction.DEFAULT_RESOURCE_SET,  /* commandLines= */
                        CommandLines.builder()
                            .addCommandLine(executableLine)
                            .addCommandLine(commandLine.build(), PARAM_FILE_INFO)
                            .build(),  /* env= */
                        actionEnvironment,  /* executionInfo= */
                        ruleContext
                            .getConfiguration()
                            .modifiedExecutionInfo(
                                executionInfo.buildKeepingLast(), DIRECT_CLASSPATH_MNEMONIC
                            ),  /* progressMessage= */
                        progressMessage,  /* mnemonic= */
                        DIRECT_CLASSPATH_MNEMONIC,  /* outputPathsMode= */
                        PathMappers.getOutputPathsMode(
                            ruleContext.getConfiguration()
                        ),  // If classPathMode == BAZEL, also make sure to inject the dependencies to be
                        // available to downstream actions. Else just do enough work to locally create the
                        // full .jdeps from the .stripped .jdeps produced on the executor.
                        /* insertDependencies= */
                        classpathMode == JavaClasspathMode.BAZEL
                                || classpathMode == JavaClasspathMode.BAZEL_NO_FALLBACK,
                        javaConfiguration.inmemoryJdepsFiles(),
                        additionalArtifactsForPathMapping
                    )
                )
                return
            }

            // If we get here the action requires annotation processing, so add additional inputs and
            // flags needed for the javac-based header compiler implementations that supports
            // annotation processing.
            if (!useHeaderCompilerDirect) {
                mandatoryInputsBuilder.addTransitive(plugins.processorClasspath())
                mandatoryInputsBuilder.addTransitive(plugins.data())
            }

            commandLine.addAll(
                "--builtin_processors",
                Sets.intersection<E?>(
                    plugins.processorClasses().toSet(),
                    javaToolchain.getHeaderCompilerBuiltinProcessors()
                )
            )
            commandLine.addAll("--processors", plugins.processorClasses())
            if (!useHeaderCompilerDirect) {
                commandLine.addExecPaths("--processorpath", plugins.processorClasspath())
            }
            if (strictJavaDeps !== StrictDepsMode.OFF) {
                commandLine.addExecPaths("--direct_dependencies", directJars)
            }

            val mandatoryInputs: NestedSet<Artifact?>? = mandatoryInputsBuilder.build()

            val executableLine: CustomCommandLine? = headerCompiler.getCommandLine()

            ruleContext.registerAction(
                JavaCompileAction( /* compilationType= */
                    CompilationType.TURBINE,  /* owner= */
                    actionOwner,  /* tools= */
                    toolsJars,  /* progressMessage= */
                    progressMessage,  /* mandatoryInputs= */
                    mandatoryInputs,  /* transitiveInputs= */
                    classpathEntries,  /* directJars= */
                    directJars,  /* outputs= */
                    outputs.build(),  /* env= */
                    actionEnvironment,  /* executionInfo= */
                    executionInfo.buildKeepingLast(),  /* extraActionInfoSupplier= */
                    null,  /* executableLine= */
                    executableLine,  /* flagLine= */
                    commandLine.build(),  /* configuration= */
                    ruleContext.getConfiguration(),  /* dependencyArtifacts= */
                    compileTimeDependencyArtifacts,  /* outputDepsProto= */
                    outputDepsProto,  /* classpathMode= */
                    classpathMode
                )
            )
        }

        private class JavaHeaderCompileProgressMessage(
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
            private val PARAM_FILE_INFO: ParamFileInfo? = ParamFileInfo.builder(UNQUOTED).build()
        }
    }

    companion object {
        private const val DIRECT_CLASSPATH_MNEMONIC = "Turbine"
        private const val PROGRESS_MESSAGE_PREFIX = "Compiling Java headers"

        fun newBuilder(ruleContext: RuleContext): Builder {
            return JavaHeaderCompileAction.Builder(ruleContext)
        }
    }
}
