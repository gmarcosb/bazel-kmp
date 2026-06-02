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
import com.google.common.collect.*
import com.google.devtools.build.lib.packages.DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME
import com.google.devtools.build.lib.vfs.FileSystemUtils
import java.util.function.Consumer
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * A helper class for compiling Java targets. It contains method to create the various intermediate
 * Artifacts for using ijars and source ijars.
 * 
 * 
 * Also supports the creation of resource and source only Jars.
 */
class JavaCompilationHelper(
    ruleContext: RuleContext,
    semantics: JavaSemantics,
    javacOpts: ImmutableList<String?>?,
    attributes: JavaTargetAttributes.Builder,
    javaToolchainProvider: JavaToolchainProvider?,
    additionalInputsForDatabinding: ImmutableList<Artifact?>?
) {
    private val ruleContext: RuleContext
    private val javaToolchain: JavaToolchainProvider
    private val attributes: JavaTargetAttributes.Builder
    private var builtAttributes: JavaTargetAttributes? = null

    /**
     * Gets the value of the "javacopts" attribute combining them with the default options. If the
     * current rule has no javacopts attribute, this method only returns the default options.
     */
    private val javacOpts: ImmutableList<String?>
    private var javaBuilderJvmFlags: NestedSet<String?> = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    private val semantics: JavaSemantics
    private val additionalInputsForDatabinding: ImmutableList<Artifact?>?
    private var enableJspecify = true
    private var enableDirectClasspath = true
    private val execGroup: String?

    init {
        this.ruleContext = ruleContext
        this.javaToolchain = Preconditions.checkNotNull<JavaToolchainProvider>(javaToolchainProvider)
        this.attributes = attributes
        this.javacOpts = javacOptsInterner.intern(javacOpts)
        this.semantics = semantics
        this.additionalInputsForDatabinding = additionalInputsForDatabinding

        if (ruleContext.useAutoExecGroups()) {
            this.execGroup = semantics.getJavaToolchainType()
        } else {
            this.execGroup = DEFAULT_EXEC_GROUP_NAME
        }
    }

    fun javaBuilderJvmFlags(javaBuilderJvmFlags: NestedSet<String?>) {
        this.javaBuilderJvmFlags = javaBuilderJvmFlags
    }

    fun enableJspecify(enableJspecify: Boolean) {
        this.enableJspecify = enableJspecify
    }

    fun getAttributes(): JavaTargetAttributes {
        if (builtAttributes == null) {
            builtAttributes = attributes.build()
        }
        return builtAttributes!!
    }

    fun enableDirectClasspath(enableDirectClasspath: Boolean) {
        this.enableDirectClasspath = enableDirectClasspath
    }

    fun getRuleContext(): RuleContext {
        return ruleContext
    }

    private val analysisEnvironment: AnalysisEnvironment
        get() = ruleContext.getAnalysisEnvironment()

    private val configuration: BuildConfigurationValue
        get() = ruleContext.getConfiguration()

    private val javaConfiguration: JavaConfiguration
        get() = ruleContext.getFragment(JavaConfiguration::class.java)

    @Throws(RuleErrorException::class, InterruptedException::class)
    fun createCompileAction(outputs: JavaCompileOutputs<Artifact?>) {
        var outputs: JavaCompileOutputs<Artifact?> = outputs
        if (outputs.genClass() != null) {
            createGenJarAction(
                outputs.output(),
                outputs.manifestProto(),
                outputs.genClass(),
                javaToolchain.getJavaRuntime()
            )
        }

        var attributes = getAttributes()

        val jspecifyInfo: JspecifyInfo? = javaToolchain.jspecifyInfo()
        val jspecify =
            enableJspecify
                    && this.javaConfiguration.experimentalEnableJspecify()
                    && jspecifyInfo != null && jspecifyInfo.matches(ruleContext.getLabel())
        if (jspecify) {
            // JSpecify requires these on the compile-time classpath; see b/187113128
            // Add them as non-direct deps (for the purposes of Strict Java Deps) to still require an
            // explicit dep if they're directly used by the compiled source.
            attributes =
                attributes.appendAdditionalTransitiveClassPathEntries(
                    jspecifyInfo.jspecifyImplicitDeps
                )
        }

        var sourceJars: ImmutableList<Artifact?> = attributes.getSourceJars()
        var plugins: JavaPluginData = attributes.plugins().plugins()
        val resourceJars: MutableList<Artifact?> = ArrayList<Artifact?>()

        val turbineAnnotationProcessing =
            usesAnnotationProcessing()
                    && this.javaConfiguration.experimentalTurbineAnnotationProcessing()
        if (turbineAnnotationProcessing) {
            val turbineResources: Artifact = turbineOutput(outputs.output(), "-turbine-resources.jar")
            resourceJars.add(turbineResources)
            val outputJar: Artifact = turbineOutput(outputs.output(), "-turbine-apt.jar")
            val turbineJdeps: Artifact = turbineOutput(outputs.output(), "-turbine-apt.jdeps")
            val turbineGensrc: Artifact? =
                if (outputs.genSource() != null)
                    outputs.genSource()
                else
                    turbineOutput(outputs.output(), "-turbine-apt-gensrc.jar")

            val builder =
                this.javaHeaderCompileActionBuilder
            builder.setOutputJar(outputJar)
            builder.setOutputDepsProto(turbineJdeps)
            builder.setPlugins(plugins)
            builder.setResourceOutputJar(turbineResources)
            builder.setGensrcOutputJar(turbineGensrc)
            builder.setManifestOutput(outputs.manifestProto())
            builder.setAdditionalOutputs(attributes.getAdditionalOutputs())
            // TODO(cushon): GraalVM/native-image doesn't support service-loading for Dagger SPI plugins
            builder.enableHeaderCompilerDirect(false)
            builder.build(javaToolchain)

            // The sources generated by the turbine annotation processing action are added to the list of
            // source jars passed to JavaBuilder.
            sourceJars =
                ImmutableList.copyOf<Artifact?>(
                    Iterables.concat<Artifact?>(
                        sourceJars,
                        ImmutableList.of<Artifact?>(turbineGensrc)
                    )
                )
        }

        if (separateResourceJar(resourceJars, attributes)) {
            val originalOutput: Artifact? = outputs.output()
            outputs =
                outputs.withOutput(
                    ruleContext.getDerivedArtifact(
                        FileSystemUtils.appendWithoutExtension(
                            outputs
                                .output()
                                .getOutputDirRelativePath(this.configuration.isSiblingRepositoryLayout()),
                            "-class"
                        ),
                        outputs.output().getRoot()
                    )
                )
            resourceJars.add(outputs.output())
            createResourceJarAction(originalOutput, ImmutableList.copyOf<Artifact?>(resourceJars))
        }

        var optimizedJar: Artifact? = null
        if (this.javaConfiguration.runLocalJavaOptimizations()) {
            optimizedJar = outputs.output()
            outputs =
                outputs.withOutput(
                    ruleContext.getDerivedArtifact(
                        FileSystemUtils.replaceExtension(
                            outputs
                                .output()
                                .getOutputDirRelativePath(this.configuration.isSiblingRepositoryLayout()),
                            "-pre-optimization.jar"
                        ),
                        outputs.output().getRoot()
                    )
                )
        }

        var javacopts = this.javacOpts
        if (jspecify) {
            plugins =
                JavaPluginData.Companion.merge(
                    ImmutableList.of<JavaPluginData?>(plugins, jspecifyInfo.jspecifyProcessor)
                )
            val jspecifyOpts: ImmutableList<String?> = jspecifyInfo.jspecifyJavacopts
            javacopts =
                javacOptsInterner.intern(
                    ImmutableList.builderWithExpectedSize<String?>(javacopts.size() + jspecifyOpts.size())
                        .addAll(javacopts) // Add JSpecify options last to discourage overriding them, at least for now.
                        .addAll(jspecifyOpts)
                        .build()
                )
        }

        val builder =
            JavaCompileActionBuilder(ruleContext, javaToolchain, execGroup)

        val classpathMode: JavaClasspathMode? = this.javaConfiguration.getReduceJavaClasspath()
        builder.setClasspathMode(classpathMode)
        builder.setAdditionalInputs(additionalInputsForDatabinding)
        val label: Label? = ruleContext.getLabel()
        builder.setTargetLabel(label)
        val coverageArtifact: Artifact? = maybeCreateCoverageArtifact(outputs.output())
        builder.setCoverageArtifact(coverageArtifact)
        val bootClassPathInfo = this.bootclasspathOrDefault
        builder.setBootClassPath(bootClassPathInfo)
        val classpath: NestedSet<Artifact?>? =
            NestedSetBuilder.< Artifact > naiveLinkOrder < Artifact ? > ()
                .addTransitive(bootClassPathInfo.auxiliary())
                .addTransitive(attributes.getCompileTimeClassPath())
                .build()
        if (!bootClassPathInfo.auxiliary().isEmpty()) {
            builder.setClasspathEntries(classpath)
            builder.setDirectJars(
                NestedSetBuilder.< Artifact > naiveLinkOrder < Artifact ? > ()
                    .addTransitive(bootClassPathInfo.auxiliary())
                    .addTransitive(attributes.getDirectJars())
                    .build()
            )
        } else {
            builder.setClasspathEntries(attributes.getCompileTimeClassPath())
            builder.setDirectJars(attributes.getDirectJars())
        }
        builder.setSourcePathEntries(attributes.getSourcePath())
        builder.setToolsJars(javaToolchain.getTools())
        builder.setJavaBuilder(
            javaToolchain.getJavaBuilder().withAdditionalJvmFlags(javaBuilderJvmFlags)
        )
        if (!turbineAnnotationProcessing) {
            builder.setGenSourceOutput(outputs.genSource())
            builder.setAdditionalOutputs(attributes.getAdditionalOutputs())
            builder.setPlugins(plugins)
            builder.setManifestOutput(outputs.manifestProto())
        } else {
            // Don't do annotation processing, but pass the processorpath through to allow service-loading
            // Error Prone plugins.
            builder.setPlugins(
                JavaPluginData.Companion.create( /* processorClasses= */
                    NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                    plugins.processorClasspath(),
                    plugins.data()
                )
            )
        }
        builder.setOutputs(outputs)

        val sourceFiles: ImmutableSet<Artifact?>? = attributes.getSourceFiles()
        builder.setSourceFiles(sourceFiles)
        builder.setSourceJars(sourceJars)
        builder.setJavacOpts(javacopts)
        builder.setUtf8Environment(semantics.utf8Environment(ruleContext.getExecutionPlatform()))
        builder.setJavacExecutionInfo(executionInfoInterner.intern(this.executionInfo))
        builder.setCompressJar(true)
        builder.setExtraData(computePerPackageData(ruleContext, javaToolchain))
        builder.setStrictJavaDeps(attributes.getStrictJavaDeps())
        semantics
            .getFixDepsTool(ruleContext.getRule(), this.javaConfiguration)
            .ifPresent(Consumer { depsTool: String? -> builder.setFixDepsTool(depsTool) })
        builder.setCompileTimeDependencyArtifacts(attributes.getCompileTimeDependencyArtifacts())
        builder.setTargetLabel(
            if (attributes.getTargetLabel() == null) label else attributes.getTargetLabel()
        )
        builder.setInjectingRuleKind(attributes.getInjectingRuleKind())

        if (coverageArtifact != null) {
            ruleContext.registerAction(
                LazyWritePathsFileAction(
                    ruleContext.getActionOwner(execGroup),
                    coverageArtifact,
                    NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().addAll(sourceFiles)
                        .build(),  /* filesToIgnore= */
                    ImmutableSet.of<E?>(),
                    false
                )
            )
        }

        val javaCompileAction = builder.build()
        ruleContext.getAnalysisEnvironment().registerAction(javaCompileAction)

        if (optimizedJar != null) {
            val optimizerLabel: NamedLabel? = this.javaConfiguration.getBytecodeOptimizer()
            createLocalOptimizationAction(
                outputs.output(),
                optimizedJar,
                NestedSetBuilder.< Artifact > naiveLinkOrder < Artifact ? > ()
                    .addTransitive(bootClassPathInfo.bootclasspath())
                    .addTransitive(classpath)
                    .build(),
                javaToolchain.getLocalJavaOptimizationConfiguration(),
                javaToolchain.getBytecodeOptimizer()!!.tool(),
                optimizerLabel.name
            )
        }
    }

    /**
     * If there are sources and no resource, the only output is from the javac action. Otherwise
     * create a separate jar for the compilation and add resources with singlejar.
     */
    private fun separateResourceJar(
        resourceJars: MutableList<Artifact?>, attributes: JavaTargetAttributes
    ): Boolean {
        return !resourceJars.isEmpty() || !attributes.getResources().isEmpty() || !attributes.getResourceJars()
            .isEmpty() || !attributes.getClassPathResources().isEmpty()
    }

    @get:Throws(RuleErrorException::class)
    private val executionInfo: ImmutableMap<String?, String?>
        get() {
            val modifiableExecutionInfo =
                ImmutableMap.builder<String?, String?>()
            modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_PATH_MAPPING, "1")
            if (javaToolchain.getJavacSupportsWorkers()) {
                modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_WORKERS, "1")
            }
            if (javaToolchain.getJavacSupportsMultiplexWorkers()) {
                modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_MULTIPLEX_WORKERS, "1")
            }
            if (javaToolchain.getJavacSupportsWorkerCancellation()) {
                modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_WORKER_CANCELLATION, "1")
            }
            if (javaToolchain.getJavacSupportsWorkerMultiplexSandboxing()) {
                modifiableExecutionInfo.put(ExecutionRequirements.SUPPORTS_MULTIPLEX_SANDBOXING, "1")
            }
            val executionInfo =
                ImmutableMap.builder<String?, String?>()
            executionInfo.putAll(
                this.configuration
                    .modifiedExecutionInfo(
                        modifiableExecutionInfo.buildOrThrow(), JavaCompileActionBuilder.Companion.MNEMONIC
                    )
            )
            executionInfo.putAll(
                TargetUtils.getExecutionInfo(ruleContext.getRule(), ruleContext.isAllowTagsPropagation())
            )

            return executionInfo.buildKeepingLast()
        }

    @get:Throws(RuleErrorException::class)
    val bootclasspathOrDefault: BootClassPathInfo
        /** Returns the bootclasspath explicit set in attributes if present, or else the default.  */
        get() {
            val attributes = getAttributes()
            if (!attributes.getBootClassPath().isEmpty()) {
                return attributes.getBootClassPath()
            } else {
                return javaToolchain.getBootclasspath()
            }
        }

    /**
     * Creates an [Artifact] needed by `JacocoCoverageRunner`.
     * 
     * 
     * The [Artifact] is created in the same directory as the given `compileJar` and
     * has the suffix `-paths-for-coverage.txt`.
     * 
     * 
     * Returns `null` if `compileJar` should not be instrumented.
     */
    private fun maybeCreateCoverageArtifact(compileJar: Artifact): Artifact? {
        if (!shouldInstrumentJar()) {
            return null
        }
        val packageRelativePath: PathFragment =
            compileJar.getRootRelativePath().relativeTo(ruleContext.getPackageDirectory())
        val path: PathFragment? =
            FileSystemUtils.replaceExtension(packageRelativePath, "-paths-for-coverage.txt")
        return ruleContext.getPackageRelativeArtifact(path, compileJar.getRoot())
    }

    private fun shouldInstrumentJar(): Boolean {
        val ruleContext: RuleContext = getRuleContext()
        return this.configuration.isCodeCoverageEnabled()
                && attributes.hasSourceFiles()
                && InstrumentedFilesCollector.shouldIncludeLocalSources(
            ruleContext.getConfiguration(), ruleContext.getLabel(), ruleContext.isTestTarget()
        )
    }

    private fun turbineOutput(classJar: Artifact, newExtension: String?): Artifact {
        return this.analysisEnvironment
            .getDerivedArtifact(
                FileSystemUtils.replaceExtension(
                    classJar.getOutputDirRelativePath(this.configuration.isSiblingRepositoryLayout()),
                    newExtension
                ),
                classJar.getRoot()
            )
    }

    /**
     * Creates the Action that compiles ijars from source.
     * 
     * @param outputJar the jar output of this java compilation
     * @param headerDeps the .jdeps output of this java compilation
     */
    @Throws(RuleErrorException::class, InterruptedException::class)
    fun createHeaderCompilationAction(
        outputJar: Artifact?, headerCompilationOutputJar: Artifact?, headerDeps: Artifact?
    ) {
        val attributes = getAttributes()

        // only run API-generating annotation processors during header compilation
        val plugins: JavaPluginData? = attributes.plugins().apiGeneratingPlugins()

        val builder =
            this.javaHeaderCompileActionBuilder
        builder.setOutputJar(outputJar)
        builder.setHeaderCompilationOutputJar(headerCompilationOutputJar)
        builder.setOutputDepsProto(headerDeps)
        builder.setPlugins(plugins)
        builder.enableDirectClasspath(enableDirectClasspath)
        semantics
            .getFixDepsTool(ruleContext.getRule(), this.javaConfiguration)
            .ifPresent(Consumer { fixDepsTool: String? -> builder.setFixDepsTool(fixDepsTool) })
        builder.build(javaToolchain)
    }

    @get:Throws(RuleErrorException::class)
    private val javaHeaderCompileActionBuilder: JavaHeaderCompileAction.Builder
        get() {
            val attributes = getAttributes()
            val builder: JavaHeaderCompileAction.Builder =
                JavaHeaderCompileAction.Companion.newBuilder(ruleContext)
            builder.setSourceFiles(attributes.getSourceFiles())
            builder.setSourceJars(attributes.getSourceJars())
            builder.setClasspathEntries(attributes.getCompileTimeClassPath())
            builder.setBootclasspathEntries(this.bootclasspathOrDefault.bootclasspath())
            // Exclude any per-package configured data (see computePerPackageData).
            // It is used to allow Error Prone checks to load additional data,
            // and Error Prone doesn't run during header compilation.
            builder.setJavacOpts(this.javacOpts)
            builder.setStrictJavaDeps(attributes.getStrictJavaDeps())
            builder.setCompileTimeDependencyArtifacts(attributes.getCompileTimeDependencyArtifacts())
            builder.setHeaderCompilationDirectJars(attributes.getHeaderCompilationDirectJars())
            builder.setDirectJars(attributes.getDirectJars())
            builder.setTargetLabel(attributes.getTargetLabel())
            builder.setInjectingRuleKind(attributes.getInjectingRuleKind())
            builder.setAdditionalInputs(additionalInputsForDatabinding)
            builder.setToolsJars(javaToolchain.getTools())
            builder.setExecGroup(execGroup)
            builder.setUtf8Environment(semantics.utf8Environment(ruleContext.getExecutionPlatform()))
            builder.enableParallelism(semantics.turbineParallelism())
            return builder
        }

    /** Returns whether this target uses annotation processing.  */
    fun usesAnnotationProcessing(): Boolean {
        val attributes = getAttributes()
        return this.javacOpts.contains("-processor") || attributes.plugins().hasProcessors()
    }

    @Throws(RuleErrorException::class)
    private fun createGenJarAction(
        classJar: Artifact?, manifestProto: Artifact?, genClassJar: Artifact?, hostJavabase: JavaRuntimeInfo
    ) {
        getRuleContext()
            .registerAction(
                Builder()
                    .addInput(manifestProto)
                    .addInput(classJar)
                    .addOutput(genClassJar)
                    .addTransitiveInputs(hostJavabase.javaBaseInputs())
                    .setJarExecutable(
                        hostJavabase.javaBinaryExecPathFragment(),
                        getGenClassJar(ruleContext),
                        javaToolchain.getJvmOptions()
                    )
                    .addCommandLine(
                        CustomCommandLine.builder()
                            .addExecPath("--manifest_proto", manifestProto)
                            .addExecPath("--class_jar", classJar)
                            .addExecPath("--output_jar", genClassJar)
                            .build()
                    )
                    .setProgressMessage("Building genclass jar %{output}")
                    .setMnemonic("JavaSourceJar")
                    .setExecGroup(execGroup)
                    .build(getRuleContext())
            )
    }

    /** Returns the GenClass deploy jar Artifact.  */
    @Throws(RuleErrorException::class)
    private fun getGenClassJar(ruleContext: RuleContext): Artifact? {
        val genClass: Artifact? = javaToolchain.getGenClass()
        if (genClass != null) {
            return genClass
        }
        return ruleContext.getPrerequisiteArtifact("\$genclass")
    }

    @Throws(RuleErrorException::class)
    private fun createResourceJarAction(resourceJar: Artifact?, extraJars: ImmutableList<Artifact?>?) {
        Preconditions.checkNotNull<Any?>(resourceJar, "resource jar output must not be null")
        val attributes = getAttributes()
        ResourceJarActionBuilder()
            .setAdditionalInputs(
                NestedSetBuilder.wrap(Order.STABLE_ORDER, additionalInputsForDatabinding)
            )
            .setJavaToolchain(javaToolchain)
            .setOutputJar(resourceJar)
            .setResources(attributes.getResources())
            .setClasspathResources(attributes.getClassPathResources())
            .setResourceJars(
                NestedSetBuilder.fromNestedSet(attributes.getResourceJars()).addAll(extraJars).build()
            )
            .build(semantics, ruleContext, execGroup)
    }

    private fun createLocalOptimizationAction(
        unoptimizedOutputJar: Artifact?,
        optimizedOutputJar: Artifact?,
        classpath: NestedSet<Artifact?>?,
        configs: MutableList<Artifact?>,
        optimizer: FilesToRunProvider?,
        mnemonic: String?
    ) {
        val command: CustomCommandLine.Builder =
            CustomCommandLine.builder()
                .add("-runtype", "LOCAL_ONLY")
                .addExecPath("-injars", unoptimizedOutputJar)
                .addExecPath("-outjars", optimizedOutputJar)
                .addExecPaths(CustomCommandLine.VectorArg.addBefore("-libraryjars").each(classpath))
        for (config in configs) {
            command.addPrefixedExecPath("@", config)
        }

        getRuleContext()
            .registerAction(
                Builder()
                    .addInput(unoptimizedOutputJar)
                    .addTransitiveInputs(classpath)
                    .addInputs(configs)
                    .addOutput(optimizedOutputJar)
                    .setExecutable(optimizer)
                    .addCommandLine(
                        command.build(),
                        ParamFileInfo.builder(ParameterFile.ParameterFileType.UNQUOTED).build()
                    )
                    .setProgressMessage("Optimizing jar %{label}")
                    .setMnemonic(mnemonic)
                    .setExecGroup(execGroup)
                    .build(getRuleContext())
            )
    }

    companion object {
        private val javacOptsInterner: Interner<ImmutableList<String?>> =
            BlazeInterners.newWeakInterner<ImmutableList<String?>?>()
        private val executionInfoInterner: Interner<ImmutableMap<String?, String?>?> =
            BlazeInterners.newWeakInterner<ImmutableMap<String?, String?>?>()

        /** Returns the per-package configured runfiles.  */
        @Throws(RuleErrorException::class)
        private fun computePerPackageData(
            ruleContext: RuleContext, toolchain: JavaToolchainProvider
        ): NestedSet<Artifact?> {
            // Do not use streams here as they create excessive garbage.
            val data: NestedSetBuilder<Artifact?> = NestedSetBuilder.naiveLinkOrder()
            for (provider in toolchain.packageConfiguration()) {
                if (provider.matches(ruleContext.getLabel())) {
                    data.addTransitive(provider.data())
                }
            }
            return data.build()
        }
    }
}
