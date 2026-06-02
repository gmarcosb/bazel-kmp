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
package com.google.devtools.build.lib.rules.java

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Joiner
import com.google.common.base.Predicate
import com.google.common.base.Strings
import com.google.common.collect.*
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata.mergeMaps
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.vfs.Path
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.StarlarkList
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap

/** Action that represents a Java compilation.  */
@ThreadCompatible
@ThreadSafety.Immutable
class JavaCompileAction(
    compilationType: CompilationType,
    owner: ActionOwner,
    tools: NestedSet<Artifact?>?,
    progressMessage: OnDemandString,
    mandatoryInputs: NestedSet<Artifact?>?,
    transitiveInputs: NestedSet<Artifact?>,
    directJars: NestedSet<Artifact?>,
    outputs: ImmutableSet<Artifact?>,
    env: ActionEnvironment,
    executionInfo: ImmutableMap<String?, String?>?,
    extraActionInfoSupplier: ExtraActionInfoSupplier?,
    executableLine: CommandLine,
    flagLine: CommandLine,
    configuration: BuildConfigurationValue,
    dependencyArtifacts: NestedSet<Artifact?>,
    outputDepsProto: Artifact?,
    classpathMode: JavaClasspathMode
) : AbstractAction(owner, allInputs(mandatoryInputs, transitiveInputs, dependencyArtifacts), outputs), CommandAction {
    internal enum class CompilationType(mnemonic: String) {
        JAVAC("Javac"),

        // 'javac turbine' has been replaced by just 'turbine', but the mnemonic is unchanged for
        // continuity in the blaze performance logs, and to distinguish direct classpath actions
        // which use the 'Turbine' mnemonic.
        // TODO(b/230333695): consider renaming to a more descriptive name
        TURBINE("JavacTurbine");

        val mnemonic: String?

        init {
            this.mnemonic = mnemonic
        }
    }

    private val tools: NestedSet<Artifact?>?
    private val compilationType: CompilationType
    private val env: ActionEnvironment
    private val executionInfo: ImmutableMap<String?, String?>
    private val executableLine: CommandLine
    private val flagLine: CommandLine
    private val configuration: BuildConfigurationValue
    private val progressMessage: OnDemandString

    private val directJars: NestedSet<Artifact?>
    private val mandatoryInputs: NestedSet<Artifact?>?
    private val transitiveInputs: NestedSet<Artifact?>
    private val dependencyArtifacts: NestedSet<Artifact?>
    private val outputDepsProto: Artifact?
    private val classpathMode: JavaClasspathMode

    private val extraActionInfoSupplier: ExtraActionInfoSupplier?

    init {
        require(!outputs.stream().anyMatch(Artifact::isTreeArtifact)) {
            java.lang.String.format(
                "Unexpected tree artifact output(s): [%s] in JavaCompileAction for %s",
                outputs.stream()
                    .filter(Artifact::isTreeArtifact)
                    .map<Any?>(Artifact::getExecPathString)
                    .collect(Collectors.joining(",")),
                owner.getLabel()
            )
        }
        this.tools = tools
        this.compilationType = compilationType
        this.env = env
        this.executionInfo =
            configuration.modifiedExecutionInfo(executionInfo, compilationType.mnemonic)
        this.executableLine = executableLine
        this.flagLine = flagLine
        this.configuration = configuration
        this.progressMessage = progressMessage
        this.extraActionInfoSupplier = extraActionInfoSupplier
        this.directJars = directJars
        this.mandatoryInputs = mandatoryInputs
        this.transitiveInputs = transitiveInputs
        this.dependencyArtifacts = dependencyArtifacts
        this.outputDepsProto = outputDepsProto
        this.classpathMode = classpathMode
        checkState(
            outputDepsProto != null
                    || (classpathMode != JavaClasspathMode.BAZEL
                    && classpathMode != JavaClasspathMode.BAZEL_NO_FALLBACK),
            "Cannot have null outputDepsProto with reduced class path mode BAZEL %s",
            describe()
        )
    }

    public override fun getTools(): NestedSet<Artifact?>? {
        return tools
    }

    val environment: ActionEnvironment
        get() = env

    val mnemonic: String?
        get() = compilationType.mnemonic

    @Throws(CommandLineExpansionException::class, InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addUUID(GUID)
        fp.addInt(classpathMode.ordinal())
        val outputPathsMode: CoreOptions.OutputPathsMode? = PathMappers.getOutputPathsMode(configuration)
        val effectiveOutputPathsMode: CoreOptions.OutputPathsMode? =
            PathMappers.getEffectiveOutputPathsMode(outputPathsMode, this.mnemonic, getExecutionInfo())
        executableLine.addToFingerprint(
            actionKeyContext, inputMetadataProvider, effectiveOutputPathsMode, fp
        )
        flagLine.addToFingerprint(
            actionKeyContext, inputMetadataProvider, effectiveOutputPathsMode, fp
        )
        // As the classpath is no longer part of commandLines implicitly, we need to explicitly add
        // the transitive inputs to the key here.
        actionKeyContext.addNestedSetToFingerprint(fp, transitiveInputs)
        this.environment.addTo(fp)
        fp.addStringMap(executionInfo)
        PathMappers.addToFingerprint(
            this.mnemonic,
            getExecutionInfo(),
            getAdditionalArtifactsForPathMapping(),
            actionKeyContext,
            outputPathsMode,
            fp
        )
    }

    /**
     * Compute a reduced classpath that is comprised of the header jars of all the direct dependencies
     * and the jars needed to build those (read from the produced .jdeps file). This duplicates the
     * logic from `com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule#computeStrictClasspath`.
     */
    @VisibleForTesting
    @Throws(IOException::class, InterruptedException::class)
    fun getReducedClasspath(
        actionExecutionContext: ActionExecutionContext?, context: JavaCompileActionContext
    ): NestedSet<Artifact?> {
        val direct: HashSet<String?> = HashSet<String?>()
        for (directJar in directJars.toList()) {
            direct.add(directJar.getExecPathString())
        }
        context.addDependencies(dependencyArtifacts.toList(), actionExecutionContext, direct)
        val transitiveCollection: ImmutableList<Artifact?> = transitiveInputs.toList()
        val reducedJars: ImmutableList<Artifact?> =
            ImmutableList.copyOf<Artifact?>(
                Iterables.filter<Artifact?>(
                    transitiveCollection, Predicate { input: Artifact? -> direct.contains(input.getExecPathString()) })
            )
        return NestedSetBuilder.wrap(Order.STABLE_ORDER, reducedJars)
    }

    /**
     * Similar to [ ] but
     * additionally includes the spawn arguments, which change between direct and fallback
     * invocations.
     */
    internal interface ExtraActionInfoSupplier {
        fun extend(builder: ExtraActionInfo.Builder?, arguments: ImmutableList<String?>?)
    }

    @Throws(CommandLineExpansionException::class, InterruptedException::class)
    private fun getReducedSpawn(
        actionExecutionContext: ActionExecutionContext,
        reducedClasspath: NestedSet<Artifact?>?,
        fallback: Boolean
    ): JavaSpawn {
        val classpathLine: CustomCommandLine.Builder = CustomCommandLine.builder()
        val pathMapper: PathMapper? =
            PathMappers.create(
                this, PathMappers.getOutputPathsMode(configuration),  /* isStarlarkAction= */false
            )

        if (fallback) {
            classpathLine.addExecPaths("--classpath", transitiveInputs)
        } else {
            classpathLine.addExecPaths("--classpath", reducedClasspath)
        }

        if (classpathMode == JavaClasspathMode.BAZEL_NO_FALLBACK) {
            // No need of fallback logic, invoke SimpleJavaLibraryBuilder with a reduced --classpath
            classpathLine.add("--reduce_classpath_mode", "NONE")
        } else {
            // These flags instruct JavaBuilder that this is a compilation with a reduced classpath and
            // that it should report a special value back if a compilation error occurs that suggests
            // retrying with the full classpath.
            classpathLine.add("--reduce_classpath_mode", if (fallback) "BAZEL_FALLBACK" else "BAZEL_REDUCED")
        }

        val reducedCommandLine: CommandLines =
            CommandLines.builder()
                .addCommandLine(executableLine)
                .addCommandLine(flagLine, PARAM_FILE_INFO)
                .addCommandLine(classpathLine.build(), PARAM_FILE_INFO)
                .build()
        val expandedCommandLines: CommandLines.ExpandedCommandLines =
            reducedCommandLine.expand(
                actionExecutionContext.getInputMetadataProvider(),
                getPrimaryOutput().getExecPath(),
                pathMapper,
                configuration.getCommandLineLimits()
            )
        val inputs: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(mandatoryInputs)
                .addTransitive(if (fallback) transitiveInputs else reducedClasspath)
                .build()
        return JavaSpawn(
            expandedCommandLines,
            getEffectiveEnvironment(actionExecutionContext.getClientEnv()),
            getExecutionInfo(),
            inputs,  /* onlyMandatoryOutput= */
            if (fallback) null else outputDepsProto,
            pathMapper
        )
    }

    @Throws(CommandLineExpansionException::class, InterruptedException::class)
    private fun getFullSpawn(actionExecutionContext: ActionExecutionContext): JavaSpawn {
        val pathMapper: PathMapper? =
            PathMappers.create(
                this, PathMappers.getOutputPathsMode(configuration),  /* isStarlarkAction= */false
            )
        val expandedCommandLines: CommandLines.ExpandedCommandLines =
            this.commandLines
                .expand(
                    actionExecutionContext.getInputMetadataProvider(),
                    getPrimaryOutput().getExecPath(),
                    pathMapper,
                    configuration.getCommandLineLimits()
                )
        return JavaSpawn(
            expandedCommandLines,
            getEffectiveEnvironment(actionExecutionContext.getClientEnv()),
            getExecutionInfo(),
            getInputs(),  /* onlyMandatoryOutput= */
            null,
            pathMapper
        )
    }

    public override fun getEffectiveEnvironment(clientEnv: MutableMap<String?, String?>?): ImmutableMap<String?, String?> {
        val env: ActionEnvironment = this.environment
        val effectiveEnvironment: LinkedHashMap<String?, String?> =
            Maps.newLinkedHashMapWithExpectedSize<K?, V?>(env.estimatedSize())
        env.resolve(effectiveEnvironment, clientEnv)
        return ImmutableMap.copyOf<String?, String?>(effectiveEnvironment)
    }

    private fun wrapIOException(e: IOException?, message: String?): ActionExecutionException {
        return ActionExecutionException.fromExecException(
            EnvironmentalExecException(
                e, createFailureDetail(message, Code.REDUCED_CLASSPATH_FALLBACK_CLEANUP_FAILURE)
            ),
            this@JavaCompileAction
        )
    }

    @Throws(ActionExecutionException::class, InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        val reducedClasspath: NestedSet<Artifact?>?
        var spawn: Spawn?
        try {
            if (classpathMode == JavaClasspathMode.BAZEL
                || classpathMode == JavaClasspathMode.BAZEL_NO_FALLBACK
            ) {
                val context: JavaCompileActionContext =
                    actionExecutionContext.getContext(JavaCompileActionContext::class.java)
                try {
                    reducedClasspath = getReducedClasspath(actionExecutionContext, context)
                } catch (e: IOException) {
                    throw createActionExecutionException(e, Code.REDUCED_CLASSPATH_FAILURE)
                }
                spawn = getReducedSpawn(actionExecutionContext, reducedClasspath,  /* fallback= */false)
            } else {
                reducedClasspath = null
                spawn = getFullSpawn(actionExecutionContext)
            }
        } catch (e: CommandLineExpansionException) {
            throw createActionExecutionException(e, Code.COMMAND_LINE_EXPANSION_FAILURE)
        }

        val primaryResults: ImmutableList<SpawnResult?>
        try {
            primaryResults =
                actionExecutionContext
                    .getContext(SpawnStrategyResolver::class.java)
                    .exec(spawn, actionExecutionContext)
        } catch (e: ExecException) {
            throw ActionExecutionException.fromExecException(e, this)
        }

        if (reducedClasspath == null) {
            return ActionResult.create(primaryResults)
        }

        val dependencies: Deps.Dependencies =
            readFullOutputDeps(primaryResults, actionExecutionContext, spawn.getPathMapper())

        if (compilationType == CompilationType.TURBINE) {
            actionExecutionContext
                .getContext(JavaCompileActionContext::class.java)
                .insertDependencies(outputDepsProto, dependencies)
        }
        if (!dependencies.getRequiresReducedClasspathFallback()) {
            return ActionResult.create(primaryResults)
        }

        // Fall back to running with the full classpath. This requires first deleting potential
        // artifacts generated by the reduced action and clearing the metadata caches.
        try {
            deleteOutputs(
                actionExecutionContext.getExecRoot(),
                actionExecutionContext.getPathResolver(),  /* bulkDeleter= */
                null,  // We don't create any tree artifacts anyway.
                /* cleanupArchivedArtifacts= */
                false
            )
        } catch (e: IOException) {
            throw wrapIOException(e, "Failed to delete reduced action outputs")
        }

        actionExecutionContext.getOutputMetadataStore().resetOutputs(getOutputs())

        try {
            actionExecutionContext.getFileOutErr().clearOut()
            actionExecutionContext.getFileOutErr().clearErr()
        } catch (e: IOException) {
            throw wrapIOException(e, "Failed to clean reduced action stdout/stderr")
        }

        try {
            spawn = getReducedSpawn(actionExecutionContext, reducedClasspath,  /* fallback= */true)
        } catch (e: CommandLineExpansionException) {
            val detailedCode: Code? = Code.COMMAND_LINE_EXPANSION_FAILURE
            throw createActionExecutionException(e, detailedCode)
        }

        val fallbackResults: ImmutableList<SpawnResult?>
        try {
            fallbackResults =
                actionExecutionContext
                    .getContext(SpawnStrategyResolver::class.java)
                    .exec(spawn, actionExecutionContext)
        } catch (e: ExecException) {
            throw ActionExecutionException.fromExecException(e, this)
        }

        if (compilationType == CompilationType.TURBINE) {
            actionExecutionContext
                .getContext(JavaCompileActionContext::class.java)
                .insertDependencies(
                    outputDepsProto,
                    readFullOutputDeps(fallbackResults, actionExecutionContext, spawn.getPathMapper())
                )
        } else if (!spawn.getPathMapper().isNoop()) {
            // As a side effect, readFullOutputDeps rewrites the on-disk .jdeps file from mapped to
            // unmapped paths. To make path mapping fully transparent to consumers of this action's
            // output, we ensure that the file always contains unmapped paths.
            val unused: Deps.Dependencies =
                readFullOutputDeps(fallbackResults, actionExecutionContext, spawn.getPathMapper())
        }
        return ActionResult.create(
            ImmutableList.< E > copyOf < E ? > (Iterables.< T > concat < T ? > (primaryResults, fallbackResults
        )))
    }

    protected val rawProgressMessage: String?
        get() = progressMessage.toString()

    internal abstract class ProgressMessage(
        output: Artifact,
        sourceFiles: ImmutableSet<Artifact?>,
        sourceJars: ImmutableList<Artifact?>,
        plugins: JavaPluginData
    ) : OnDemandString() {
        private val output: Artifact
        private val sourceFiles: Int
        private val sourceJars: Int
        private val processorClasses: NestedSet<String?>

        init {
            this.output = output
            this.sourceFiles = sourceFiles.size()
            this.sourceJars = sourceJars.size()
            this.processorClasses = plugins.processorClasses()
        }

        abstract fun prefix(): String?

        override fun toString(): String {
            val sb = StringBuilder(prefix())
            sb.append(' ')
            sb.append(output.prettyPrint())
            sb.append(" (")
            var first = true
            first = appendCount(sb, first, sourceFiles, "source file")
            appendCount(sb, first, sourceJars, "source jar")
            sb.append(")")
            appendProcessorNames(sb, processorClasses)
            return sb.toString()
        }

        companion object {
            private fun appendProcessorNames(sb: StringBuilder, processorClasses: NestedSet<String?>) {
                if (processorClasses.isEmpty()) {
                    return
                }
                val shortNames: MutableList<String?> = ArrayList<String?>()
                for (name in processorClasses.toList()) {
                    // Annotation processor names are qualified class names. Omit the package part for the
                    // progress message, e.g. `com.google.Foo` -> `Foo`.
                    val idx: Int = name.lastIndexOf('.'.code)
                    val shortName: String = if (idx != -1) name.substring(idx + 1) else name
                    shortNames.add(shortName)
                }
                sb.append(" and running annotation processors (")
                Joiner.on(", ").appendTo(sb, shortNames)
                sb.append(")")
            }

            /**
             * Append an input count to the progress message, e.g. "2 source jars". If an input count has
             * already been appended, prefix with ", ".
             */
            private fun appendCount(sb: StringBuilder, first: Boolean, count: Int, name: String?): Boolean {
                var first = first
                if (count > 0) {
                    if (!first) {
                        sb.append(", ")
                    } else {
                        first = false
                    }
                    sb.append(count).append(' ').append(name)
                    if (count > 1) {
                        sb.append('s')
                    }
                }
                return first
            }
        }
    }

    @Throws(CommandLineExpansionException::class, InterruptedException::class)
    public override fun getExtraActionInfo(actionKeyContext: ActionKeyContext?): ExtraActionInfo.Builder? {
        val builder: ExtraActionInfo.Builder? = super.getExtraActionInfo(actionKeyContext)
        val commandLinesWithoutExecutable: CommandLines =
            CommandLines.builder()
                .addCommandLine(flagLine)
                .addCommandLine(this.fullClasspathLine)
                .build()
        if (extraActionInfoSupplier != null) {
            extraActionInfoSupplier.extend(builder, commandLinesWithoutExecutable.allArguments())
        }
        return builder
    }

    private inner class JavaSpawn(
        expandedCommandLines: CommandLines.ExpandedCommandLines,
        environment: MutableMap<String?, String?>?,
        executionInfo: MutableMap<String?, String?>?,
        inputs: NestedSet<Artifact?>?,
        onlyMandatoryOutput: Artifact?,
        pathMapper: PathMapper?
    ) : BaseSpawn(
        expandedCommandLines.arguments(),
        environment,
        executionInfo,
        this@JavaCompileAction,
        LOCAL_RESOURCES
    ) {
        private val inputs: NestedSet<ActionInput?>?
        private val onlyMandatoryOutput: Artifact?
        private val pathMapper: PathMapper?

        init {
            this.onlyMandatoryOutput = onlyMandatoryOutput
            this.inputs =
                NestedSetBuilder.< ActionInput > fromNestedSet < ActionInput ? > (inputs)
                    .addAll(expandedCommandLines.getParamFiles())
                    .build()
            this.pathMapper = pathMapper
        }

        val inputFiles: NestedSet<out ActionInput?>?
            get() = inputs

        public override fun isMandatoryOutput(output: ActionInput?): Boolean {
            return onlyMandatoryOutput == null || onlyMandatoryOutput.equals(output)
        }

        public override fun getPathMapper(): PathMapper? {
            return pathMapper
        }
    }

    @get:VisibleForTesting
    val commandLines: CommandLines
        get() = CommandLines.builder()
            .addCommandLine(executableLine)
            .addCommandLine(flagLine, PARAM_FILE_INFO)
            .addCommandLine(this.fullClasspathLine, PARAM_FILE_INFO)
            .build()

    private val fullClasspathLine: CommandLine
        get() {
            val classpathLine: CustomCommandLine.Builder =
                CustomCommandLine.builder().addExecPaths("--classpath", transitiveInputs)
            if (classpathMode == JavaClasspathMode.JAVABUILDER) {
                classpathLine.add("--reduce_classpath_mode", "JAVABUILDER_REDUCED")
                if (!dependencyArtifacts.isEmpty()) {
                    classpathLine.addExecPaths("--deps_artifacts", dependencyArtifacts)
                }
            }
            return classpathLine.build()
        }

    @get:Throws(EvalException::class, InterruptedException::class)
    val starlarkArgv: Sequence<String?>?
        get() {
            try {
                return StarlarkList.immutableCopyOf<String?>(this.arguments)
            } catch (ex: CommandLineExpansionException) {
                throw EvalException(ex)
            }
        }

    /** Returns the out-of-band execution data for this action.  */
    public override fun getExecutionInfo(): ImmutableMap<String?, String?>? {
        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            mergeMaps(super.getExecutionInfo(), executionInfo)
        if (outputDepsProto == null
            || !configuration.getFragment(JavaConfiguration::class.java).inmemoryJdepsFiles()
        ) {
            return result
        }
        return mergeMaps(
            result,
            ImmutableMap.of<K?, V?>(
                ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS,
                outputDepsProto.getExecPathString()
            )
        )
    }

    @get:Throws(CommandLineExpansionException::class, InterruptedException::class)
    val arguments: ImmutableList<String?>
        get() = ImmutableList.copyOf(this.commandLines.allArguments())

    val starlarkArgs: Sequence<CommandLineArgsApi>?
        get() {
            val result: ImmutableList.Builder<CommandLineArgsApi?> =
                ImmutableList.builder<CommandLineArgsApi?>()
            val directoryInputs: ImmutableSet<Artifact?>? =
                getInputs().toList().stream().filter(Artifact::isDirectory)
                    .collect(ImmutableSet.toImmutableSet<E?>())
            for (commandLine in this.commandLines.unpack()) {
                result.add(Args.forRegisteredAction(commandLine, directoryInputs))
            }
            return StarlarkList.immutableCopyOf<CommandLineArgsApi?>(result.build())
        }

    @get:VisibleForTesting
    val incompleteEnvironmentForTesting: ImmutableMap<String?, String?>
        get() = this.environment.getFixedEnv()

    val possibleInputsForTesting: NestedSet<Artifact?>?
        get() = null

    public override fun mayModifySpawnOutputsAfterExecution(): Boolean {
        // Causes of spawn output modification after execution:
        // - Fallback to the full classpath with --experimental_java_classpath=bazel.
        // - In-place rewriting of .jdeps files with --experimental_output_paths=strip.
        return true
    }

    /** Reads the full `.jdeps` output from the given spawn results.  */
    @Throws(ActionExecutionException::class)
    private fun readFullOutputDeps(
        results: MutableList<SpawnResult?>,
        actionExecutionContext: ActionExecutionContext,
        pathMapper: PathMapper
    ): Deps.Dependencies {
        val result: SpawnResult? = Iterables.getOnlyElement<SpawnResult?>(results)
        try {
            return createFullOutputDeps(
                result,
                outputDepsProto,
                getInputs(),
                getAdditionalArtifactsForPathMapping(),
                actionExecutionContext,
                pathMapper
            )
        } catch (e: IOException) {
            throw ActionExecutionException.fromExecException(
                EnvironmentalExecException(
                    e, createFailureDetail(".jdeps read IOException", Code.JDEPS_READ_IO_EXCEPTION)
                ),
                this
            )
        }
    }

    private fun createActionExecutionException(e: Exception, detailedCode: Code?): ActionExecutionException {
        val detailedExitCode: DetailedExitCode =
            DetailedExitCode.of(createFailureDetail(Strings.nullToEmpty(e.getMessage()), detailedCode))
        return ActionExecutionException(e, this,  /* catastrophe= */false, detailedExitCode)
    }

    companion object {
        private val LOCAL_RESOURCES: ResourceSet? = ResourceSet.createWithRamCpu( /* memoryMb= */750,  /* cpu= */1)
        private val GUID: UUID = UUID.fromString("e423747c-2827-49e6-b961-f6c08c10bb51")

        private val PARAM_FILE_INFO: ParamFileInfo? =
            ParamFileInfo.builder(ParameterFile.ParameterFileType.UNQUOTED).setUseAlways(true).build()

        /** Computes all of a [JavaCompileAction]'s inputs.  */
        private fun allInputs(
            mandatoryInputs: NestedSet<Artifact?>?,
            transitiveInputs: NestedSet<Artifact?>?,
            dependencyArtifacts: NestedSet<Artifact?>?
        ): NestedSet<Artifact?> {
            return NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(mandatoryInputs)
                .addTransitive(transitiveInputs)
                .addTransitive(dependencyArtifacts)
                .build()
        }

        /**
         * Locally rewrites a .jdeps file to replace missing config prefixes.
         * 
         * 
         * For example: `bazel-out/bin/foo/foo.jar -> bazel-out/x86-fastbuild/bin/foo/foo.jar`.
         * 
         * 
         * The executor may strip config prefixes from actions (i.e. remove `/x86-fastbuild/` or
         * equivalent from all input and output paths, command lines, and input file contents). This
         * provides better caching for actions that don't vary by --cpu or --compilation_mode. The full
         * paths must be re-created in Bazel's output tree to keep correct builds. For example, if an
         * otherwise cacheable action's input file *contents* differ across CPUs (like a CPU-dependent
         * generated source file), Bazel needs to maintain distinct paths for each instance. These paths
         * are chosen in Bazel's analysis phase, before it's possible to input contents. So all actions in
         * the output tree must conservatively keep full paths.
         * 
         * 
         * So this method's ultimate purpose is to translate the executor-optimized version of a .jdeps
         * to the original Bazel-safe version.
         * 
         * 
         * If the executor doesn't strip config prefixes (i.e. config stripping isn't turned on as a
         * feature), this is a trivial copy.
         * 
         * 
         * If config stripping is on, this method won't work with [ ][JavaConfiguration.JavaClasspathMode.JAVABUILDER]. That mode causes downstream Java compilations
         * to read this .jdeps on the executor. Since this method replaces config prefixes, the .jdeps
         * entries won't match the executor's stripped paths. This works best with [ ][JavaConfiguration.JavaClasspathMode.BAZEL], where Bazel directly processes the .jdeps on the
         * local filesystem. Those paths match.
         * 
         * @param spawnResult the executor action that created the possibly stripped .jdeps output
         * @param outputDepsProto path to the .jdeps output
         * @param actionInputs all inputs to the current action
         * @param additionalArtifactsForPathMapping any additional artifacts that may be referenced in the
         * .jdeps file by path
         * @param actionExecutionContext the action execution context
         * @return the full deps proto (also written to disk to satisfy the action's declared output)
         */
        @Throws(IOException::class)
        fun createFullOutputDeps(
            spawnResult: SpawnResult,
            outputDepsProto: Artifact,
            actionInputs: NestedSet<Artifact?>,
            additionalArtifactsForPathMapping: NestedSet<Artifact?>,
            actionExecutionContext: ActionExecutionContext,
            pathMapper: PathMapper
        ): Deps.Dependencies {
            val executorJdeps: Deps.Dependencies =
                readExecutorJdeps(spawnResult, outputDepsProto, actionExecutionContext)

            if (pathMapper.isNoop()) {
                return executorJdeps
            }

            // No paths to rewrite.
            if (executorJdeps.getDependencyCount() === 0) {
                return executorJdeps
            }

            // For each of the action's generated inputs, revert its mapped path back to its original path.
            val mappedToOriginalPath: BiMap<String?, PathFragment?> = HashBiMap.create<String?, PathFragment?>()
            for (actionInput in Iterables.concat(actionInputs.toList(), additionalArtifactsForPathMapping.toList())) {
                if (actionInput.isSourceArtifact()) {
                    continue
                }
                val mappedPath: String? = pathMapper.getMappedExecPathString(actionInput)
                val previousPath: PathFragment? = mappedToOriginalPath.put(mappedPath, actionInput.getExecPath())
                check(!(previousPath != null && previousPath != actionInput.getExecPath())) {
                    java.lang.String.format(
                        "Duplicate mapped path %s derived from %s and %s",
                        mappedPath, actionInput.getExecPath(), mappedToOriginalPath.get(mappedPath)
                    )
                }
            }

            // Rewrite the .jdeps proto with full paths.
            val outputRoot: PathFragment? = outputDepsProto.getExecPath().subFragment(0, 1)
            val fullDepsBuilder: Deps.Dependencies.Builder = Deps.Dependencies.newBuilder(executorJdeps)
            for (dep in fullDepsBuilder.getDependencyBuilderList()) {
                val pathOnExecutor: PathFragment = PathFragment.create(dep.getPath())
                val originalPath: PathFragment? = mappedToOriginalPath.get(pathOnExecutor.getPathString())
                // Source files, which do not lie under the output root, are not mapped. It is also possible
                // that a jdeps file contains a reference to a transitive classpath element that isn't an
                // input to the current action (see
                // https://github.com/google/turbine/commit/f9f2decee04a3c651671f7488a7c9d7952df88c8), just an
                // additional artifact marked for path mapping, and itself wasn't built with path mapping
                // enabled (e .g. due to path collisions). In that case, the path will already be unmapped and
                // we can leave it as is. For entirely unexpected paths, we still report an error.
                check(
                    !(originalPath == null && pathOnExecutor.subFragment(0, 1) == outputRoot
                            && !mappedToOriginalPath.containsValue(pathOnExecutor))
                ) {
                    java.lang.String.format(
                        "Missing original path for mapped path %s in %s%njdeps: %s%npath map: %s",
                        pathOnExecutor,
                        outputDepsProto.getExecPath(),
                        executorJdeps,
                        mappedToOriginalPath
                    )
                }
                dep.setPath(
                    if (originalPath == null) pathOnExecutor.getPathString() else originalPath.getPathString()
                )
            }
            val fullOutputDeps: Deps.Dependencies = fullDepsBuilder.build()

            // Write the updated proto back to the filesystem. If the executor produced in-memory-only
            // outputs (see getInMemoryOutput above), the filesystem version doesn't exist and we can skip
            // this. Note that in-memory and filesystem outputs aren't necessarily mutually exclusive.
            val fsPath: Path = actionExecutionContext.getInputPath(outputDepsProto)
            if (fsPath.exists()) {
                // Make sure to clear the output store cache if it has an entry from before the rewrite.
                actionExecutionContext
                    .getOutputMetadataStore()
                    .resetOutputs(ImmutableList.of<E?>(outputDepsProto))
                fsPath.setWritable(true)
                fsPath.getOutputStream().use { outputStream ->
                    fullOutputDeps.writeTo(outputStream)
                }
            }

            return fullOutputDeps
        }

        @Throws(IOException::class)
        private fun readExecutorJdeps(
            spawnResult: SpawnResult,
            outputDepsProto: Artifact?,
            actionExecutionContext: ActionExecutionContext
        ): Deps.Dependencies {
            val inMemoryOutput: ByteString? = spawnResult.getInMemoryOutput(outputDepsProto)
            if (inMemoryOutput == null)
                actionExecutionContext.getInputPath(outputDepsProto).getInputStream()
            else
                inMemoryOutput.newInput().use { inputStream ->
                    return Deps.Dependencies.parseFrom(inputStream, ExtensionRegistry.getEmptyRegistry())
                }
        }

        private fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setJavaCompile(JavaCompile.newBuilder().setCode(detailedCode))
                .build()
        }
    }
}
