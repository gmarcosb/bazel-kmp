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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata.mergeMaps

/** Action that represents some kind of C++ compilation step.  */
@AutoCodec
@ThreadCompatible
class CppCompileAction : AbstractAction, IncludeScannable, CommandAction, ActionWithDiscoveredInputsState {
    private val gcnoFile: Artifact?
    private val sourceFile: Artifact

    private val dotdFile: Artifact?
    private val configuration: BuildConfigurationValue
    private val mandatoryInputs: NestedSet<Artifact?>
    private val mandatorySpawnInputs: NestedSet<Artifact?>?
    private val allowedDerivedInputs: NestedSet<Artifact?>?

    /**
     * The set of input files that in addition to [CcCompilationContext.getDeclaredIncludeSrcs]
     * need to be added to the set of input artifacts of the action if we don't use input discovery.
     * They may be pruned after execution. See [.findUsedHeaders] for more details.
     */
    private val additionalPrunableHeaders: NestedSet<Artifact?>

    private val grepIncludes: Artifact?
    val isShareable: Boolean
    private val shouldScanIncludes: Boolean
    private val usePic: Boolean
    private val useHeaderModules: Boolean
    private val needsIncludeValidation: Boolean

    private val ccCompilationContext: CcCompilationContext
    private val builtinIncludeFiles: com.google.common.collect.ImmutableList<Artifact?>?

    // A list of files to include scan that are not source files, pcm files, or
    // included via a command-line "-include file.h". Actions that use non C++ files as source
    // files--such as Clif--may use this mechanism.
    private val additionalIncludeScanningRoots: com.google.common.collect.ImmutableList<Artifact?>

    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    val compileCommandLine: CompileCommandLine

    /**
     * The fingerprint of [.compileCommandLine]. This is computed lazily so that the command
     * line is not unnecessarily flattened outside of action execution.
     */
    private var commandLineKey: ByteArray?

    private val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>
    private val actionName: String
    private val progressMessagePrefix: String

    private val featureConfiguration: FeatureConfiguration

    private val builtInIncludeDirectories: com.google.common.collect.ImmutableList<PathFragment>

    // TODO(b/213594908): Make CppCompileAction immutable.
    /**
     * Set when the action prepares for execution. Used to preserve state between preparation and
     * execution.
     */
    private var additionalInputs: NestedSet<Artifact?>? = null

    /**
     * Used only during input discovery, when input discovery requires other actions to be executed
     * first.
     */
    private var usedModules: MutableSet<DerivedArtifact>? = null

    private var usedCpp20Modules: com.google.common.collect.ImmutableSet<Artifact?>? = null

    private var inputsDiscovered = false

    /**
     * This field is set only for C++ module compiles (compiling .cppmap files into .pcm files). It
     * stores the modules necessary for building this module as they will later also be required for
     * building users of this module. Such users can get to this data through this action's [ ]
     * 
     * 
     * This field is populated either based on the discovered headers in [.discoverInputs] or
     * extracted from the action inputs when restoring it from the action cache.
     */
    private var discoveredModules: NestedSet<Artifact?>? = null

    /**
     * Used modules that are not transitively used through other topLevelModules. This field can be
     * written and read concurrently, please use [.getTopLevelModules] and [ ][.setTopLevelModules] for accessing it.
     * 
     * 
     * We choose synchronized methods over `AtomicReference` to avoid the memory overhead.
     */
    private var topLevelModules: NestedSet<Artifact?>? = null

    private val moduleFiles: NestedSet<Artifact?>?
    private val modmapInputFile: Artifact

    /**
     * Creates a new action to compile C/C++ source files.
     * 
     * @param owner the owner of the action, usually the configured target that emitted it
     * @param featureConfiguration TODO(bazel-team): Add parameter description.
     * @param variables TODO(bazel-team): Add parameter description.
     * @param sourceFile the source file that should be compiled. `mandatoryInputs` must contain
     * this file
     * @param shouldScanIncludes a boolean indicating whether scanning of `sourceFile` is to be
     * performed looking for inclusions.
     * @param usePic TODO(bazel-team): Add parameter description.
     * @param mandatoryInputs any additional files that need to be present for the compilation to
     * succeed, can be empty but not null, for example, extra sources for FDO.
     * @param outputFile the object file that is written as result of the compilation
     * @param dotdFile the .d file that is generated as a side-effect of compilation
     * @param diagnosticsFile the .dia file that is generated as a side-effect of compilation
     * @param gcnoFile the coverage notes that are written in coverage mode, can be null
     * @param dwoFile the .dwo output file where debug information is stored for Fission builds (null
     * if Fission mode is disabled)
     * @param ccCompilationContext the `CcCompilationContext`
     * @param coptsFilter regular expression to remove options from `copts`
     * @param additionalIncludeScanningRoots list of additional artifacts to include-scan
     * @param actionName a string giving the name of this action for the purpose of toolchain
     * evaluation
     * @param progressMessagePrefix a string describing this action for cases when the same action is
     * run on the same file.
     * @param cppSemantics C++ compilation semantics
     * @param builtInIncludeDirectories - list of toolchain-defined builtin include directories.
     */
    internal constructor(
        owner: ActionOwner?,
        featureConfiguration: FeatureConfiguration,
        variables: CcToolchainVariables?,
        sourceFile: Artifact,
        configuration: BuildConfigurationValue,
        shareable: Boolean,
        shouldScanIncludes: Boolean,
        usePic: Boolean,
        useHeaderModules: Boolean,
        mandatoryInputs: NestedSet<Artifact?>,
        mandatorySpawnInputs: NestedSet<Artifact?>?,
        builtinIncludeFiles: com.google.common.collect.ImmutableList<Artifact?>?,
        additionalPrunableHeaders: NestedSet<Artifact?>,
        outputFile: Artifact?,
        dotdFile: Artifact?,
        diagnosticsFile: Artifact?,
        gcnoFile: Artifact?,
        dwoFile: Artifact?,
        ltoIndexingFile: Artifact?,
        ccCompilationContext: CcCompilationContext,
        coptsFilter: CoptsFilter?,
        additionalIncludeScanningRoots: com.google.common.collect.ImmutableList<Artifact?>?,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>,
        actionName: String,
        progressMessagePrefix: String,
        needsIncludeValidation: Boolean,
        builtInIncludeDirectories: com.google.common.collect.ImmutableList<PathFragment>,
        grepIncludes: Artifact?,
        additionalOutputs: com.google.common.collect.ImmutableList<Artifact?>,
        moduleFiles: NestedSet<Artifact?>?,
        modmapInputFile: Artifact
    ) : super(
        owner,
        mandatoryInputs,
        collectOutputs(
            com.google.common.base.Preconditions.checkNotNull<Artifact?>(outputFile, "outputFile"),
            dotdFile,
            diagnosticsFile,
            gcnoFile,
            dwoFile,
            ltoIndexingFile,
            additionalOutputs
        )
    ) {
        this.gcnoFile = gcnoFile
        this.sourceFile = sourceFile
        this.isShareable = shareable
        this.configuration = configuration
        this.mandatoryInputs = mandatoryInputs
        this.mandatorySpawnInputs = mandatorySpawnInputs
        this.additionalPrunableHeaders = additionalPrunableHeaders
        this.shouldScanIncludes = shouldScanIncludes
        this.usePic = usePic
        this.useHeaderModules = useHeaderModules
        this.ccCompilationContext = ccCompilationContext
        this.builtinIncludeFiles = builtinIncludeFiles
        this.additionalIncludeScanningRoots =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<Artifact?>>(
                additionalIncludeScanningRoots
            )
        this.compileCommandLine =
            buildCommandLine(coptsFilter, actionName, featureConfiguration, variables)
        this.executionInfo = executionInfo
        this.actionName = actionName
        this.progressMessagePrefix = progressMessagePrefix
        this.featureConfiguration = featureConfiguration
        this.needsIncludeValidation = needsIncludeValidation
        this.builtInIncludeDirectories = builtInIncludeDirectories
        this.additionalInputs = null
        this.usedModules = null
        this.topLevelModules = null
        this.grepIncludes = grepIncludes
        this.dotdFile = if (isGenerateDotdFile(sourceFile)) dotdFile else null

        val allowedDerivedInputsBuilder: NestedSetBuilder<Artifact?> =
            NestedSetBuilder.fromNestedSet(mandatoryInputs)
                .addTransitive(additionalPrunableHeaders)
                .addTransitive(ccCompilationContext.getDeclaredIncludeSrcs())
                .addTransitive(ccCompilationContext.getTransitiveModules(usePic))
                .add(getSourceFile())

        // The separate module is an allowed input to all compiles of this context except for its own
        // compile.
        val separateModule: Artifact? = ccCompilationContext.getSeparateHeaderModule(usePic)
        if (separateModule != null && !separateModule.equals(getPrimaryOutput())) {
            allowedDerivedInputsBuilder.add(separateModule)
        }
        if (moduleFiles != null) {
            allowedDerivedInputsBuilder.addTransitive(moduleFiles)
        }
        this.allowedDerivedInputs = allowedDerivedInputsBuilder.build()
        this.moduleFiles = moduleFiles
        this.modmapInputFile = modmapInputFile
    }

    /** Constructor for serialization.  */
    @AutoCodec.Instantiator
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal constructor(
        owner: ActionOwner?,
        mandatoryInputs: NestedSet<Artifact?>,
        rawOutputs: Any?,
        gcnoFile: Artifact?,
        sourceFile: Artifact,
        dotdFile: Artifact?,
        configuration: BuildConfigurationValue,
        mandatorySpawnInputs: NestedSet<Artifact?>?,
        allowedDerivedInputs: NestedSet<Artifact?>?,
        additionalPrunableHeaders: NestedSet<Artifact?>,
        grepIncludes: Artifact?,
        shareable: Boolean,
        shouldScanIncludes: Boolean,
        usePic: Boolean,
        useHeaderModules: Boolean,
        needsIncludeValidation: Boolean,
        ccCompilationContext: CcCompilationContext,
        builtinIncludeFiles: com.google.common.collect.ImmutableList<Artifact?>?,
        additionalIncludeScanningRoots: com.google.common.collect.ImmutableList<Artifact?>,
        compileCommandLine: CompileCommandLine,
        executionInfo: com.google.common.collect.ImmutableMap<String?, String?>,
        actionName: String,
        progressMessagePrefix: String,
        featureConfiguration: FeatureConfiguration,
        builtInIncludeDirectories: com.google.common.collect.ImmutableList<PathFragment>,
        moduleFiles: NestedSet<Artifact?>?,
        modmapInputFile: Artifact
    ) : super(owner, mandatoryInputs, rawOutputs) {
        this.gcnoFile = gcnoFile
        this.sourceFile = sourceFile
        this.dotdFile = dotdFile
        this.configuration = configuration
        this.mandatoryInputs = mandatoryInputs
        this.mandatorySpawnInputs = mandatorySpawnInputs
        this.allowedDerivedInputs = allowedDerivedInputs
        this.additionalPrunableHeaders = additionalPrunableHeaders
        this.grepIncludes = grepIncludes
        this.isShareable = shareable
        this.shouldScanIncludes = shouldScanIncludes
        this.usePic = usePic
        this.useHeaderModules = useHeaderModules
        this.needsIncludeValidation = needsIncludeValidation
        this.ccCompilationContext = ccCompilationContext
        this.builtinIncludeFiles = builtinIncludeFiles
        this.additionalIncludeScanningRoots = additionalIncludeScanningRoots
        this.compileCommandLine = compileCommandLine
        this.executionInfo = executionInfo
        this.actionName = actionName
        this.progressMessagePrefix = progressMessagePrefix
        this.featureConfiguration = featureConfiguration
        this.builtInIncludeDirectories = builtInIncludeDirectories
        this.moduleFiles = moduleFiles
        this.modmapInputFile = modmapInputFile
    }

    /**
     * Whether we should do "include scanning". Note that this does *not* mean whether we should parse
     * the .d files to determine which include files were used during compilation. Instead, this means
     * whether we should a) run the pre-execution include scanner (see `IncludeScanningContext`)
     * if one exists and b) whether the action inputs should be modified to match the results of that
     * pre-execution scanning and (if enabled) again after execution to match the results of the .d
     * file parsing.
     * 
     * 
     * This does *not* have anything to do with "hdrs_check".
     */
    @com.google.common.annotations.VisibleForTesting
    fun shouldScanIncludes(): Boolean {
        return shouldScanIncludes
    }

    fun useInMemoryDotdFiles(): Boolean {
        return cppConfiguration().getInmemoryDotdFiles()
    }

    private fun enabledCppCompileResourcesEstimation(): Boolean {
        return cppConfiguration().getExperimentalCppCompileResourcesEstimation()
    }

    val environment: ActionEnvironment
        get() = configuration.getActionEnvironment()

    override fun getBuiltInIncludeDirectories(): com.google.common.collect.ImmutableList<PathFragment> {
        return builtInIncludeDirectories
    }

    val builtInIncludeFiles: MutableList<Artifact>?
        get() = builtinIncludeFiles

    public override fun getMandatoryInputs(): NestedSet<Artifact?> {
        return mandatoryInputs
    }

    val mandatoryOutputs: com.google.common.collect.ImmutableSet<Artifact?>?
        get() {
            // Never prune orphaned modules files. To cut down critical paths, CppCompileActions do not
            // add modules files as inputs. Instead they rely on input discovery to recognize the needed
            // ones. However, orphan detection runs before input discovery and thus module files would be
            // discarded as orphans.
            // This is strictly better than marking all transitive modules as inputs, which would also
            // effectively disable orphan detection for .pcm files.
            val outputFile: Artifact = getPrimaryOutput()
            if (outputFile.isFileType(CppFileTypes.CPP_MODULE)) {
                return com.google.common.collect.ImmutableSet.of<Artifact?>(outputFile)
            }
            return super.getMandatoryOutputs()
        }

    /**
     * Returns the list of additional inputs found by dependency discovery, during action preparation.
     * [.discoverInputs] must be called before this method is called on
     * each action execution.
     */
    fun getAdditionalInputs(): NestedSet<Artifact?>? {
        return com.google.common.base.Preconditions.checkNotNull<NestedSet<Artifact?>?>(additionalInputs)
    }

    public override fun setAdditionalInputs(inputs: NestedSet<Artifact?>?) {
        this.additionalInputs = com.google.common.base.Preconditions.checkNotNull<NestedSet<Artifact?>?>(inputs)
    }

    /** Clears the discovered [.additionalInputs].  */
    private fun clearAdditionalInputs() {
        additionalInputs = null
    }

    public override fun discoversInputs(): Boolean {
        return isCpp20ModuleCompilationAction(actionName)
                || shouldScanIncludes
                || getDotdFile() != null || shouldParseShowIncludes()
    }

    protected override fun inputsDiscovered(): Boolean {
        return inputsDiscovered
    }

    protected override fun setInputsDiscovered(inputsDiscovered: Boolean) {
        this.inputsDiscovered = inputsDiscovered
    }

    @get:com.google.common.annotations.VisibleForTesting
    val possibleInputsForTesting: NestedSet<Artifact?>
        // productionVisibility = Visibility.PRIVATE
        get() = NestedSetBuilder.fromNestedSet(getInputs())
            .addTransitive(ccCompilationContext.getDeclaredIncludeSrcs())
            .addTransitive(additionalPrunableHeaders)
            .build()

    @kotlin.jvm.Synchronized
    private fun setTopLevelModules(value: NestedSet<Artifact?>?) {
        this.topLevelModules = value
    }

    @kotlin.jvm.Synchronized
    private fun getTopLevelModules(): NestedSet<Artifact?>? {
        return this.topLevelModules
    }

    /** Returns the results of include scanning.  */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    private fun findUsedHeaders(
        actionExecutionContext: ActionExecutionContext, headerData: IncludeScanningHeaderData?
    ): NestedSet<Artifact?>? {
        com.google.common.base.Preconditions.checkState(
            shouldScanIncludes, "findUsedHeaders() called although include scanning is disabled"
        )
        try {
            try {
                val includes: MutableList<Artifact?>? =
                    actionExecutionContext
                        .getContext(CppIncludeScanningContext::class.java)
                        .findAdditionalInputs(this, actionExecutionContext, headerData)
                if (includes == null) {
                    return null
                }

                Collections.sort<Artifact?>(includes, Artifact.EXEC_PATH_COMPARATOR)
                return NestedSetBuilder.wrap(Order.STABLE_ORDER, includes)
            } catch (e: UncheckedIOException) {
                throw EnvironmentalExecException(
                    e.getCause(),
                    createFailureDetail("Find used headers failure", Code.FIND_USED_HEADERS_IO_EXCEPTION)
                )
            }
        } catch (e: ExecException) {
            throw ActionExecutionException.fromExecException(e, "include scanning", this)
        }
    }

    /** Finds used modules based on results of include scanning.  */
    private fun findUsedModules(usedHeaders: MutableSet<Artifact?>?): MutableSet<DerivedArtifact>? {
        if (!useHeaderModules) {
            return null
        }
        val separate: Boolean =
            getPrimaryOutput().equals(ccCompilationContext.getSeparateHeaderModule(usePic))
        return ccCompilationContext.computeUsedModules(usePic, usedHeaders, separate)
    }

    /** Results of C++ module discovery.  */
    internal class CppDiscoveredModules(transitivelyUsed: NestedSet<Artifact?>?, topLevel: NestedSet<Artifact?>?) {
        val transitivelyUsed: NestedSet<Artifact?>?
        val topLevel: NestedSet<Artifact?>?

        init {
            this.transitivelyUsed = transitivelyUsed
            this.topLevel = topLevel
        }
    }

    /**
     * Computes the minimal set of modules required for the compilation, based on all used modules
     * that the include scanner found.
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    private fun findMinimalSetOfRequiredModules(
        actionExecutionContext: ActionExecutionContext, usedModules: MutableSet<DerivedArtifact>
    ): CppDiscoveredModules? {
        val transitivelyUsedModules: com.google.common.collect.ImmutableMap<Artifact?, NestedSet<Artifact?>?>? =
            computeTransitivelyUsedModules(
                actionExecutionContext.getEnvironmentForDiscoveringInputs(), usedModules
            )
        if (transitivelyUsedModules == null) {
            return null
        }

        val topLevel: MutableSet<Artifact?> =
            actionExecutionContext
                .getDiscoveredModulesPruner()
                .computeTopLevelModules(this, usedModules, transitivelyUsedModules)

        val topLevelModulesBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        val discoveredModulesBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        for (module in topLevel) {
            topLevelModulesBuilder.add(module)
            discoveredModulesBuilder.addTransitive(transitivelyUsedModules.get(module))
        }

        val topLevelModules: NestedSet<Artifact?>? = topLevelModulesBuilder.build()
        discoveredModulesBuilder.addTransitive(topLevelModules)
        return CppDiscoveredModules(discoveredModulesBuilder.buildInterruptibly(), topLevelModules)
    }

    // TODO(b/213594908): Remove this method from Action interface once CppCompileAction is immutable.
    public override fun prepareInputDiscovery() {
        // Make sure to clear the additional inputs potentially left over from an old build (in case we
        // ran discoverInputs, but not beginExecution).
        clearAdditionalInputs()
    }

    /**
     * This method returns null when a required SkyValue is missing and a Skyframe restart is
     * required.
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun discoverInputs(actionExecutionContext: ActionExecutionContext): NestedSet<Artifact?>? {
        com.google.common.base.Preconditions.checkArgument(
            !sourceFile.isFileType(CppFileTypes.CPP_MODULE)
                    || isCpp20ModuleCompilationAction(actionName)
        )

        if (additionalInputs == null) {
            val options: MutableList<String>
            try {
                options = this.compilerOptions
            } catch (e: CommandLineExpansionException) {
                val message: String? =
                    java.lang.String.format(
                        "failed to generate compile command for rule '%s: %s",
                        getOwner().getLabel(), e.getMessage()
                    )
                val code: DetailedExitCode = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE)
                throw ActionExecutionException(message, this,  /* catastrophe= */false, code)
            }
            commandLineKey = computeCommandLineKey(options)
            val systemIncludeDirs: com.google.common.collect.ImmutableList<PathFragment?> =
                getSystemIncludeDirs(options)
            val siblingLayout: Boolean =
                actionExecutionContext
                    .getOptions()
                    .getOptions(BuildLanguageOptions::class.java)
                    .getExperimentalSiblingRepositoryLayout()
            if (!shouldScanIncludes) {
                usedCpp20Modules = computeUsedCpp20Modules(actionExecutionContext)
                // When not actually doing include scanning, add all prunable headers to additionalInputs.
                // This is necessary because the inputs that can be pruned by .d file parsing must be
                // returned from discoverInputs() and they cannot be in mandatoryInputs. Thus, even with
                // include scanning turned off, we pretend that we "discover" these headers.
                additionalInputs =
                    NestedSetBuilder.fromNestedSet(ccCompilationContext.getDeclaredIncludeSrcs())
                        .addTransitive(additionalPrunableHeaders)
                        .addAll(usedCpp20Modules)
                        .build()
                if (needsIncludeValidation) {
                    verifyActionIncludePaths(systemIncludeDirs, siblingLayout)
                }
                return additionalInputs
            }
            val includeScanningHeaderDataBuilder: com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData.Builder? =
                ccCompilationContext.createIncludeScanningHeaderData(
                    actionExecutionContext.getEnvironmentForDiscoveringInputs(),
                    usePic,
                    useHeaderModules
                )
            if (includeScanningHeaderDataBuilder == null) {
                return null
            }
            // In theory, we could verify include paths even earlier, but we want to avoid the restart
            // above necessitating a double-execution.
            if (needsIncludeValidation) {
                verifyActionIncludePaths(systemIncludeDirs, siblingLayout)
            }
            val includeScanningHeaderData: IncludeScanningHeaderData? =
                includeScanningHeaderDataBuilder
                    .setSystemIncludeDirs(systemIncludeDirs)
                    .setCmdlineIncludes(getCmdlineIncludes(options))
                    .setIsValidUndeclaredHeader(this.validUndeclaredHeaderPredicate)
                    .build()
            additionalInputs = findUsedHeaders(actionExecutionContext, includeScanningHeaderData)
            if (additionalInputs == null) {
                return null
            }
            usedModules = findUsedModules(additionalInputs.toSet())
        }

        if (usedModules == null) {
            // There are two paths in which this can be reached:
            // 1. This is not a modular compilation or one without include scanning. In either case, we
            //    never compute used modules.
            // 2. This function has completed on a previous execution, adding all used modules to
            //    additionalInputs and resetting usedModules to null below.
            // In either case, there is nothing more to do here.
            return additionalInputs
        }

        val requiredModules =
            findMinimalSetOfRequiredModules(actionExecutionContext, usedModules)
        if (requiredModules == null) {
            return null
        }
        setTopLevelModules(requiredModules.topLevel)
        additionalInputs =
            NestedSetBuilder.fromNestedSet(additionalInputs)
                .addTransitive(requiredModules.transitivelyUsed)
                .build()
        if (getPrimaryOutput().isFileType(CppFileTypes.CPP_MODULE)
            && !isCpp20ModuleCompilationAction(actionName)
        ) {
            this.discoveredModules = requiredModules.transitivelyUsed
        }
        usedModules = null
        return additionalInputs
    }

    val originalInputs: NestedSet<Artifact?>
        get() = mandatoryInputs

    private val validUndeclaredHeaderPredicate: java.util.function.Predicate<Artifact?>?
        get() {
            if (getDotdFile() != null) {
                // If we'll be looking at .d files later, don't remove undeclared inputs now.
                return null
            }

            val cppConfiguration: CppConfiguration = cppConfiguration()
            val ignoreDirs: Iterable<PathFragment?> =
                if (cppConfiguration.isStrictSystemIncludes())
                    getBuiltInIncludeDirectories()
                else
                    this.validationIgnoredDirs
            val additionalPrunableHeadersSet: com.google.common.collect.ImmutableSet<Artifact?> =
                additionalPrunableHeaders.toSet()
            return java.util.function.Predicate { header: Artifact? ->
                additionalPrunableHeadersSet.contains(header)
                        || com.google.devtools.build.lib.vfs.FileSystemUtils.startsWithAny(
                    header.getExecPath(),
                    ignoreDirs
                )
            }
        }

    val primaryInput: Artifact
        get() = getSourceFile()

    /** Returns the path of the c/cc source for gcc.  */
    fun getSourceFile(): Artifact {
        return sourceFile
    }

    override fun getGrepIncludes(): Artifact? {
        return grepIncludes
    }

    /**
     * Set by [.discoverInputs]. Returns a subset of [.getAdditionalInputs] or an empty
     * [NestedSet], if this is not a compile action producing a C++ module.
     */
    override fun getDiscoveredModules(): NestedSet<Artifact?>? {
        return com.google.common.base.MoreObjects.firstNonNull<T?>(
            discoveredModules,
            NestedSetBuilder.emptySet(Order.STABLE_ORDER)
        )
    }

    /** Returns the path where the compiler should put the discovered dependency information.  */
    fun getDotdFile(): Artifact? {
        return dotdFile
    }

    fun getCcCompilationContext(): CcCompilationContext {
        return ccCompilationContext
    }

    val quoteIncludeDirs: MutableList<PathFragment>
        get() {
            val result: com.google.common.collect.ImmutableList.Builder<PathFragment?> =
                com.google.common.collect.ImmutableList.builder<PathFragment?>()
            result.addAll(ccCompilationContext.getQuoteIncludeDirs())
            val copts: com.google.common.collect.ImmutableList<String> =
                compileCommandLine.getCopts(PathMapper.NOOP)
            var i = 0
            while (i < copts.size()) {
                val opt: String = copts.get(i)
                if (opt.startsWith("-iquote")) {
                    if (opt.length() > 7) {
                        result.add(PathFragment.create(opt.substring(7).trim()))
                    } else if (i + 1 < copts.size()) {
                        i++
                        result.add(PathFragment.create(copts.get(i)))
                    } else {
                        java.lang.System.err.println("WARNING: dangling -iquote flag in options for " + prettyPrint())
                    }
                }
                i++
            }
            return result.build()
        }

    val includeDirs: MutableList<PathFragment>
        get() {
            val result: com.google.common.collect.ImmutableList.Builder<PathFragment?> =
                com.google.common.collect.ImmutableList.builder<PathFragment?>()
            result.addAll(ccCompilationContext.getIncludeDirs())
            for (opt in compileCommandLine.getCopts(PathMapper.NOOP)) {
                if (opt.startsWith("-I") || opt.startsWith("/I")) {
                    // We insist on the combined form "-Idir".
                    val includeDir: String = opt.substring(2)
                    if (includeDir.isEmpty()) {
                        continue
                    }
                    if (matchesCaseInsensitiveMsvc(includeDir)) {
                        // This is actually a "-imsvc", a system include dir.
                        continue
                    }
                    result.add(PathFragment.create(opt.substring(2)))
                }
            }
            return result.build()
        }

    val frameworkIncludeDirs: com.google.common.collect.ImmutableList<PathFragment?>?
        get() = ccCompilationContext.getFrameworkIncludeDirs()

    @get:Throws(CommandLineExpansionException::class)
    @get:com.google.common.annotations.VisibleForTesting
    val systemIncludeDirs: MutableList<PathFragment>
        get() = getSystemIncludeDirs(this.compilerOptions)

    private fun getSystemIncludeDirs(compilerOptions: MutableList<String>): com.google.common.collect.ImmutableList<PathFragment?> {
        // TODO(bazel-team): parsing the command line flags here couples us to gcc- and clang-cl-style
        // compiler command lines; use a different way to specify system includes (for example through a
        // system_includes attribute in cc_toolchain); note that that would disallow users from
        // specifying system include paths via the copts attribute.
        // Currently, this works together with the include_paths features because getCommandLine() will
        // get the system include paths from the {@code CcCompilationContext} instead.
        val result: com.google.common.collect.ImmutableList.Builder<PathFragment?> =
            com.google.common.collect.ImmutableList.builder<PathFragment?>()
        var i = 0
        while (i < compilerOptions.size()) {
            val opt = compilerOptions.get(i)
            var systemIncludeFlag: String? = null
            if (opt.startsWith("-isystem")) {
                systemIncludeFlag = "-isystem"
            } else if (matchesIncludeCaseInsensitiveMsvc(opt)) {
                systemIncludeFlag = opt.substring(0, 6)
            }
            if (systemIncludeFlag == null) {
                i++
                continue
            }

            if (opt.length() > systemIncludeFlag.length()) {
                result.add(PathFragment.create(opt.substring(systemIncludeFlag.length()).trim()))
            } else if (i + 1 < compilerOptions.size()) {
                i++
                result.add(PathFragment.create(compilerOptions.get(i)))
            } else {
                java.lang.System.err.println(
                    "WARNING: dangling " + systemIncludeFlag + " flag in options for " + prettyPrint()
                )
            }
            i++
        }
        return result.build()
    }

    private fun cppConfiguration(): CppConfiguration {
        return configuration.getFragment(CppConfiguration::class.java)
    }

    val mainIncludeScannerSource: Artifact?
        get() =// getIncludeScannerSources() needs to return the main file first. This is used for determining
            // what file command line includes should be interpreted relative to.
            this.includeScannerSources.get(0)

    val includeScannerSources: com.google.common.collect.ImmutableList<Artifact?>?
        get() {
            if (getSourceFile().isFileType(CppFileTypes.CPP_MODULE_MAP)) {
                val outputFile: Artifact = getPrimaryOutput()
                val isSeparate: Boolean = outputFile.equals(ccCompilationContext.getSeparateHeaderModule(usePic))
                // Expected 0 args, but got 1.
                com.google.common.base.Preconditions.checkState(
                    outputFile.equals(ccCompilationContext.getHeaderModule(usePic)) || isSeparate,
                    "Trying to build unknown module",
                    outputFile
                )

                // If this is an action that compiles the header module itself, the source we build is the
                // module map, and we need to include-scan all headers that are referenced in the module map.
                return ccCompilationContext.getHeaderModuleSrcs(isSeparate)
            }
            val builder: com.google.common.collect.ImmutableList.Builder<Artifact?> =
                com.google.common.collect.ImmutableList.builder<Artifact?>()
            builder.add(getSourceFile())
            builder.addAll(additionalIncludeScanningRoots)
            return builder.build()
        }

    @get:com.google.common.annotations.VisibleForTesting
    val defines: com.google.common.collect.ImmutableCollection<String?>?
        /**
         * Returns the list of "-D" arguments that should be used by this gcc invocation. Only used for
         * testing.
         */
        get() = ccCompilationContext.getDefines()

    @get:Throws(ActionExecutionException::class)
    @get:com.google.common.annotations.VisibleForTesting
    val incompleteEnvironmentForTesting: com.google.common.collect.ImmutableMap<String?, String?>
        get() {
            try {
                return getEffectiveEnvironment(com.google.common.collect.ImmutableMap.of<String?, String?>())
            } catch (e: CommandLineExpansionException) {
                val message: String? =
                    java.lang.String.format(
                        "failed to generate compile environment variables for rule '%s: %s",
                        getOwner().getLabel(), e.getMessage()
                    )
                val code: DetailedExitCode =
                    createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE)
                throw ActionExecutionException(message, this,  /* catastrophe= */false, code)
            }
        }

    @Throws(CommandLineExpansionException::class)
    public override fun getEffectiveEnvironment(clientEnv: MutableMap<String?, String?>?): com.google.common.collect.ImmutableMap<String?, String?> {
        return getEffectiveEnvironment(clientEnv, PathMapper.NOOP)
    }

    @Throws(CommandLineExpansionException::class)
    fun getEffectiveEnvironment(
        clientEnv: MutableMap<String?, String?>?, pathMapper: PathMapper?
    ): com.google.common.collect.ImmutableMap<String?, String?> {
        val env: ActionEnvironment = this.environment
        val environment: MutableMap<String?, String?> =
            com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<K?, V?>(env.estimatedSize())
        env.resolve(environment, clientEnv)

        if (!getExecutionInfo().containsKey(ExecutionRequirements.REQUIRES_DARWIN)) {
            // Linux: this prevents gcc/clang from writing the unpredictable (and often irrelevant) value
            // of getcwd() into the debug info. Not applicable to Darwin or Windows, which have no /proc.
            environment.put("PWD", "/proc/self/cwd")
        }

        environment.putAll(compileCommandLine.getEnvironment(pathMapper))
        return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(environment)
    }

    @get:Throws(CommandLineExpansionException::class)
    val arguments: MutableList<String?>
        get() = compileCommandLine.getArguments(this.overwrittenVariables, PathMapper.NOOP)

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val starlarkArgv: net.starlark.java.eval.Sequence<String?>?
        get() {
            try {
                return net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(this.arguments)
            } catch (ex: CommandLineExpansionException) {
                throw net.starlark.java.eval.EvalException(ex)
            }
        }

    val starlarkArgs: net.starlark.java.eval.Sequence<CommandLineArgsApi?>?
        get() {
            val directoryInputs: com.google.common.collect.ImmutableSet<Artifact?>? =
                getInputs().toList().stream().filter(Artifact::isDirectory)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())

            val commandLine: CommandLine = compileCommandLine.getFilteredFeatureConfigurationCommandLine(this)
            var paramFileInfo: ParamFileInfo? = null
            if (cppConfiguration().useArgsParamsFile()) {
                paramFileInfo =
                    ParamFileInfo.builder(ParameterFileType.GCC_QUOTED).setUseAlways(true).build()
            }
            val commandLineAndParamFileInfo: CommandLineAndParamFileInfo =
                CommandLineAndParamFileInfo(commandLine, paramFileInfo)

            val args: Args = Args.forRegisteredAction(commandLineAndParamFileInfo, directoryInputs)

            return net.starlark.java.eval.StarlarkList.immutableCopyOf<CommandLineArgsApi?>(
                com.google.common.collect.ImmutableList.of<CommandLineArgsApi?>(
                    args
                )
            )
        }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun getExtraActionInfo(actionKeyContext: ActionKeyContext?): ExtraActionInfo.Builder {
        val info: CppCompileInfo.Builder = CppCompileInfo.newBuilder()
        info.setTool(compileCommandLine.getToolPath())

        val options: MutableList<String> =
            compileCommandLine.getCompilerOptions(this.overwrittenVariables, PathMapper.NOOP)

        for (option in options) {
            info.addCompilerOption(option)
        }
        info.setOutputFile(getPrimaryOutput().getExecPathString())
        info.setSourceFile(getSourceFile().getExecPathString())
        if (inputsKnown()) {
            info.addAllSourcesAndHeaders(Artifact.toExecPaths(getInputs().toList()))
        } else {
            info.addSourcesAndHeaders(getSourceFile().getExecPathString())
            info.addAllSourcesAndHeaders(
                Artifact.toExecPaths(ccCompilationContext.getDeclaredIncludeSrcs().toList())
            )
        }
        // TODO(ulfjack): Extra actions currently ignore the client environment.
        for (envVariable in getEffectiveEnvironment( /* clientEnv= */com.google.common.collect.ImmutableMap.of<String?, String?>()).entrySet()) {
            info.addVariable(
                EnvironmentVariable.newBuilder()
                    .setName(envVariable.getKey())
                    .setValue(envVariable.getValue())
                    .build()
            )
        }

        try {
            return super.getExtraActionInfo(actionKeyContext)
                .setExtension(CppCompileInfo.cppCompileInfo, info.build())
        } catch (e: CommandLineExpansionException) {
            throw java.lang.AssertionError("CppCompileAction command line expansion cannot fail", e)
        }
    }

    @get:Throws(CommandLineExpansionException::class)
    @get:com.google.common.annotations.VisibleForTesting
    val compilerOptions: MutableList<String>
        /** Returns the compiler options.  */
        get() = compileCommandLine.getCompilerOptions( /* overwrittenVariables= */null, PathMapper.NOOP)

    public override fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
        return mergeMaps(super.getExecutionInfo(), executionInfo)
    }

    /**
     * Enforce that the includes actually visited during the compile were properly declared in the
     * rules.
     * 
     * 
     * The technique is to walk through all of the reported includes that gcc emits into the .d
     * file, and verify that they came from acceptable relative include directories. This is done in
     * two steps:
     * 
     * 
     * First, each included file is stripped of any include path prefix from `quoteIncludeDirs` to produce an effective relative include dir+name.
     * 
     * 
     * Second, the remaining directory is looked up in `declaredIncludeDirs`, a list of
     * acceptable dirs. This list contains a set of dir fragments that have been calculated by the
     * configured target to be allowable for inclusion by this source. If no match is found, an error
     * is reported and an exception is thrown.
     * 
     * @throws ActionExecutionException iff there was an undeclared dependency
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(ActionExecutionException::class)
    fun validateInclusions(
        actionExecutionContext: ActionExecutionContext?, inputsForValidation: NestedSet<Artifact?>
    ) {
        if (!needsIncludeValidation) {
            return
        }
        val errors: IncludeProblems = IncludeProblems()
        val allowedIncludes: MutableSet<Artifact?> = HashSet<Artifact?>()
        allowedIncludes.addAll(mandatoryInputs.toList())
        allowedIncludes.addAll(ccCompilationContext.getDeclaredIncludeSrcs().toList())
        allowedIncludes.addAll(additionalPrunableHeaders.toList())

        val ignoreDirs: Iterable<PathFragment?> =
            if (cppConfiguration().isStrictSystemIncludes())
                getBuiltInIncludeDirectories()
            else
                this.validationIgnoredDirs

        // Copy the nested sets to hash sets for fast contains checking, but do so lazily.
        // Avoid immutable sets here to limit memory churn.
        for (input in inputsForValidation.toList()) {
            if (!validateInclude(allowedIncludes, ignoreDirs, input)) {
                errors.add(input.getExecPath().toString())
            }
        }
        errors.assertProblemFree(
            ("undeclared inclusion(s) in rule '"
                    + this.getOwner().getLabel()
                    + "':\n"
                    + "this rule is missing dependency declarations for the following files "
                    + "included by '"
                    + getSourceFile().prettyPrint()
                    + "':"),
            this
        )
    }

    private val validationIgnoredDirs: Iterable<PathFragment>
        get() {
            val cxxSystemIncludeDirs: MutableList<PathFragment> = getBuiltInIncludeDirectories()
            return com.google.common.collect.Iterables.concat<PathFragment?>(
                cxxSystemIncludeDirs,
                ccCompilationContext.getSystemIncludeDirs(),
                ccCompilationContext.getExternalIncludeDirs()
            )
        }

    @com.google.common.annotations.VisibleForTesting
    @Throws(ActionExecutionException::class)
    fun verifyActionIncludePaths(
        systemIncludeDirs: MutableList<PathFragment?>, siblingRepositoryLayout: Boolean
    ) {
        val ignoredDirs: com.google.common.collect.ImmutableSet<PathFragment?> =
            com.google.common.collect.ImmutableSet.copyOf<PathFragment?>(
                this.validationIgnoredDirs
            )

        // We currently do not check the output of:
        // - getBuiltinIncludeDirs(): while in practice this doesn't happen, bazel can be configured
        //   to use an absolute system root, in which case the builtin include dirs might be absolute.
        val includePathsToVerify: Iterable<PathFragment> =
            com.google.common.collect.Iterables.concat<PathFragment?>(
                this.includeDirs,
                this.quoteIncludeDirs, systemIncludeDirs
            )
        for (includePath in includePathsToVerify) {
            // includePathsToVerify contains all paths that are added as -isystem directive on the command
            // line, most of which are added for include directives in the CcCompilationContext and are
            // thus also in ignoredDirs. The hash lookup prevents this from becoming O(N^2) for these.
            var includePath: PathFragment = includePath
            if (ignoredDirs.contains(includePath)
                || com.google.devtools.build.lib.vfs.FileSystemUtils.startsWithAny(includePath, ignoredDirs)
            ) {
                continue
            }

            // Two conditions:
            // 1. Paths cannot be absolute (e.g. multiple uplevels to /etc/passwd)
            // 2. For relative paths, one starting ../ is okay for getting to a sibling repository.
            val prefix: PathFragment? =
                if (siblingRepositoryLayout)
                    LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX
                else
                    LabelConstants.EXTERNAL_PATH_PREFIX
            if (includePath.startsWith(prefix)) {
                includePath = includePath.relativeTo(prefix)
            }
            if (includePath.isAbsolute() || includePath.containsUplevelReferences()) {
                val message: String? =
                    java.lang.String.format(
                        "The include path '%s' references a path outside of the execution root.",
                        includePath
                    )
                val code: DetailedExitCode =
                    createDetailedExitCode(message, Code.INCLUDE_PATH_OUTSIDE_EXEC_ROOT)
                throw ActionExecutionException(message, this,  /* catastrophe= */false, code)
            }
        }
    }

    /**
     * Recalculates this action's live input collection.
     * 
     * 
     * Can only be called if [.discoversInputs], and must be called after execution in that
     * case.
     */
    @com.google.common.annotations.VisibleForTesting // productionVisibility = Visibility.PRIVATE
    @ThreadCompatible
    fun updateActionInputs(discoveredInputs: NestedSet<Artifact?>?) {
        com.google.common.base.Preconditions.checkState(
            discoversInputs(), "Can't call if not discovering inputs: %s %s", discoveredInputs, this
        )
        Profiler.instance().profile(ProfilerTask.ACTION_UPDATE, this::describe).use { c ->
            val inputsBuilder: NestedSetBuilder<Artifact?> =
                NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().addTransitive(mandatoryInputs)
            if (discoveredInputs != null) {
                inputsBuilder.addTransitive(discoveredInputs)
            }
            super.updateInputs(inputsBuilder.build())
        }
    }

    val overwrittenVariables: CcToolchainVariables?
        get() {
            if (useHeaderModules) {
                // TODO(cmita): Avoid keeping state in CppCompileAction.
                // There are two cases for when this method might be called:
                // 1. After input discovery, after which toplevelModules is set (in discoverInputs()).
                // 2. After the action is loaded from the local action cache, leaving topLevelModules null.
                //
                // Ideally the same thing would be done in both cases, but as is, we just overestimate modules
                // in the latter case using the inputs from the action cache.
                // Note that this breaks the invariant that Actions are immutable after the analysis phase.
                val modules: NestedSet<Artifact?>? = if (shouldScanIncludes) getTopLevelModules() else null
                if (modules != null) {
                    return calculateModuleVariable(modules)
                } else {
                    return calculateModuleVariable(getInputs())
                }
            }
            return CcToolchainVariables.Companion.builder().build()
        }

    val schedulingDependencies: NestedSet<Artifact?>?
        get() = ccCompilationContext.getDeclaredIncludeSrcs()

    public override fun getAllowedDerivedInputs(): NestedSet<Artifact?>? {
        return allowedDerivedInputs
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * If this is compiling a module, restores the value of [.discoveredModules], which is
     * used to create the [com.google.devtools.build.lib.skyframe.ActionExecutionValue] after an
     * action cache hit.
     */
    @kotlin.jvm.Synchronized
    public override fun updateInputs(inputs: NestedSet<Artifact?>) {
        super.updateInputs(inputs)
        if (getPrimaryOutput().isFileType(CppFileTypes.CPP_MODULE)
            && !isCpp20ModuleCompilationAction(actionName)
        ) {
            discoveredModules =
                NestedSetBuilder.wrap(
                    Order.STABLE_ORDER,
                    com.google.common.collect.Iterables.filter<T?>(
                        inputs.toList(),
                        com.google.common.base.Predicate { input: T? -> input.isFileType(CppFileTypes.CPP_MODULE) })
                )
        }
    }

    protected val rawProgressMessage: String
        get() {
            var separator = ""
            if (!progressMessagePrefix.isEmpty()) {
                separator = ": "
            }
            return (progressMessagePrefix
                    + separator
                    + when (actionName) {
                CppActionNames.CPP_HEADER_ANALYSIS -> "Header analysis for "
                CppActionNames.CPP_MODULE_DEPS_SCANNING -> "Deps scanning for "
                else -> "Compiling "
            }
                    + getSourceFile().prettyPrint())
        }

    val declaredIncludeSrcs: NestedSet<Artifact?>?
        /** Returns explicitly listed header files.  */
        get() = ccCompilationContext.getDeclaredIncludeSrcs()

    /** For actions that discover inputs, the key must include input names.  */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun computeKey(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        computeKey(
            actionKeyContext,
            fp,
            this.environment,
            compileCommandLine.getEnvironment(PathMapper.NOOP),
            executionInfo,
            getCommandLineKey(),
            ccCompilationContext.getDeclaredIncludeSrcs(),
            mandatoryInputs,
            mandatorySpawnInputs,
            additionalPrunableHeaders,
            builtInIncludeDirectories,
            ccCompilationContext.getDeclaredIncludeSrcs(),
            this.mnemonic,
            PathMappers.getOutputPathsMode(configuration)
        )
    }

    @Throws(CommandLineExpansionException::class)
    private fun getCommandLineKey(): ByteArray? {
        if (commandLineKey == null) {
            // For the argv part of the cache key, ignore all compiler flags that explicitly denote module
            // file (.pcm) inputs. Depending on input discovery, some of the unused ones are removed from
            // the command line. However, these actually don't have an influence on the compile itself and
            // so ignoring them for the cache key calculation does not affect correctness. The compile
            // itself is fully determined by the input source files and module maps.
            // A better long-term solution would be to make the compiler to find them automatically and
            // never hand in the .pcm files explicitly on the command line in the first place.
            commandLineKey = computeCommandLineKey(this.compilerOptions)
        }
        return commandLineKey
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        val pathMapper: PathMapper? =
            PathMappers.create(
                this, PathMappers.getOutputPathsMode(configuration),  /* isStarlarkAction= */false
            )

        val argumentsAndParamFileActionInput =
            getArgumentsForExecute(pathMapper)

        if (shouldScanIncludes) {
            updateActionInputs(additionalInputs)
        }

        val spawnContext: ActionExecutionContext
        val showIncludesFilterForStdout: ShowIncludesFilter?
        val showIncludesFilterForStderr: ShowIncludesFilter?
        if (shouldParseShowIncludes()) {
            showIncludesFilterForStdout = ShowIncludesFilter(getSourceFile().getFilename())
            showIncludesFilterForStderr = ShowIncludesFilter(getSourceFile().getFilename())
            val originalOutErr: FileOutErr = actionExecutionContext.getFileOutErr()
            val tempOutErr: FileOutErr? = originalOutErr.childOutErr()
            spawnContext = actionExecutionContext.withFileOutErr(tempOutErr)
        } else {
            spawnContext = actionExecutionContext
            showIncludesFilterForStdout = null
            showIncludesFilterForStderr = null
        }

        var spawn: Spawn?
        try {
            spawn =
                createSpawn(
                    actionExecutionContext.getExecRoot(),
                    argumentsAndParamFileActionInput.arguments,
                    actionExecutionContext.getClientEnv(),
                    pathMapper,
                    argumentsAndParamFileActionInput.paramFileActionInput
                )
        } finally {
            clearAdditionalInputs()
        }

        var spawnResults: com.google.common.collect.ImmutableList<SpawnResult?>
        var dotDContents: ByteArray?

        try {
            spawnResults =
                actionExecutionContext.getContext(SpawnStrategyResolver::class.java).exec(spawn, spawnContext)
            // SpawnActionContext guarantees that the first list entry exists and corresponds to the
            // executed spawn.
            dotDContents = getDotDContents(spawnResults.get(0))
        } catch (e: ExecException) {
            throw ActionExecutionException.fromExecException(e, this@CppCompileAction)
        } catch (e: java.lang.InterruptedException) {
            copyTempOutErrToActionOutErrMaybe(
                spawnContext.getFileOutErr(),
                actionExecutionContext.getFileOutErr(),
                showIncludesFilterForStdout,
                showIncludesFilterForStderr
            )
            throw e
        } finally {
            copyTempOutErrToActionOutErrMaybe(
                spawnContext.getFileOutErr(),
                actionExecutionContext.getFileOutErr(),
                showIncludesFilterForStdout,
                showIncludesFilterForStderr
            )
        }

        ensureCoverageNotesFileExists(actionExecutionContext)

        val scanningContext: CppIncludeExtractionContext =
            actionExecutionContext.getContext(CppIncludeExtractionContext::class.java)
        val execRoot: com.google.devtools.build.lib.vfs.Path = actionExecutionContext.getExecRoot()
        val siblingRepositoryLayout: Boolean =
            actionExecutionContext
                .getOptions()
                .getOptions(BuildLanguageOptions::class.java)
                .getExperimentalSiblingRepositoryLayout()

        if (shouldParseShowIncludes()) {
            val discoveredInputs: NestedSet<Artifact?> =
                discoverInputsFromShowIncludesFilters(
                    execRoot,
                    scanningContext.getArtifactResolver(),
                    showIncludesFilterForStdout,
                    showIncludesFilterForStderr,
                    siblingRepositoryLayout,
                    pathMapper
                )
            updateActionInputs(discoveredInputs)
            validateInclusions(actionExecutionContext, discoveredInputs)
            return ActionResult.create(spawnResults)
        }

        if (getDotdFile() == null) {
            return ActionResult.create(spawnResults)
        }

        // Post-execute "include scanning", which modifies the action inputs to match what the
        // compile action actually used by incorporating the results of .d file parsing.
        var discoveredInputs: NestedSet<Artifact?> =
            discoverInputsFromDotdFiles(
                actionExecutionContext,
                execRoot,
                scanningContext.getArtifactResolver(),
                dotDContents,
                siblingRepositoryLayout,
                pathMapper
            )
        dotDContents = null // Garbage collect in-memory .d contents.

        if (usedCpp20Modules != null) {
            discoveredInputs =
                NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                    .addAll(usedCpp20Modules)
                    .addTransitive(discoveredInputs)
                    .build()
        }
        updateActionInputs(discoveredInputs)

        // hdrs_check: This cannot be switched off for C++ build actions,
        // because doing so would allow for incorrect builds.
        // HeadersCheckingMode.NONE should only be used for ObjC build actions.
        validateInclusions(actionExecutionContext, discoveredInputs)
        return ActionResult.create(spawnResults)
    }

    internal class ArgumentsAndParamFileActionInput(
      @kotlin.jvm.JvmField val arguments: MutableList<String>?,
      paramFileActionInput: ParamFileActionInput?
    ) {
        val paramFileActionInput: ParamFileActionInput?

        init {
            this.paramFileActionInput = paramFileActionInput
        }
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(ActionExecutionException::class)
    fun getArgumentsForExecute(pathMapper: PathMapper?): ArgumentsAndParamFileActionInput {
        var compilerOptions: MutableList<String>? = null
        try {
            compilerOptions =
                compileCommandLine.getCompilerOptions(this.overwrittenVariables, pathMapper)
        } catch (e: CommandLineExpansionException) {
            val message: String? =
                java.lang.String.format(
                    "failed to generate compile command for rule '%s: %s",
                    getOwner().getLabel(), e.getMessage()
                )
            val code: DetailedExitCode = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE)
            throw ActionExecutionException(message, this,  /* catastrophe= */false, code)
        }
        var args: MutableList<String>? = null // null means use a param file.
        if (!featureConfiguration.isEnabled(CppRuleClasses.COMPILER_PARAM_FILE)) {
            args = compileCommandLine.getArgumentsWithCompilerOptions(pathMapper, compilerOptions)
            if (featureConfiguration.isEnabled(CppRuleClasses.COMPILER_PARAM_FILE_ON_DEMAND)) {
                var totalLength = 0
                for (arg in args!!) {
                    totalLength += (arg.length() + 1)
                }
                if (totalLength > configuration.getCommandLineLimits().maxLength) {
                    // COMPILER_PARAM_FILE_ON_DEMAND is enabled and the command line is too long:
                    args = null // null means use a param file.
                }
            }
        }
        var paramFileActionInput: ParamFileActionInput? = null
        if (args == null) { // null means use a param file so we prepare one and update args.
            val outputFile: Artifact = getPrimaryOutput()
            val paramFilePath: PathFragment =
                outputFile
                    .getExecPath()
                    .getParentDirectory()
                    .getChild(outputFile.getFilename() + ".params")
            paramFileActionInput =
                ParamFileActionInput(
                    paramFilePath,
                    compilerOptions,  // TODO(b/132888308): Support MSVC, which has its own method of escaping strings.
                    ParameterFileType.GCC_QUOTED
                )
            args = compileCommandLine.getArgumentsWithParameterFile(pathMapper, paramFilePath)
        }
        return ArgumentsAndParamFileActionInput(args, paramFileActionInput)
    }

    @Throws(ActionExecutionException::class)
    private fun copyTempOutErrToActionOutErrMaybe(
        tempOutErr: FileOutErr,
        outErr: FileOutErr,
        showIncludesFilterForStdout: ShowIncludesFilter,
        showIncludesFilterForStderr: ShowIncludesFilter
    ) {
        // If parse_showincludes feature is enabled, instead of parsing dotD file we parse the
        // output of cl.exe caused by /showIncludes option.
        if (!shouldParseShowIncludes()) {
            return
        }

        try {
            tempOutErr.close()
            if (tempOutErr.hasRecordedStdout()) {
                tempOutErr.getOutputPath().getInputStream().use { `in` ->
                    com.google.common.io.ByteStreams.copy(
                        `in`, showIncludesFilterForStdout.getFilteredOutputStream(outErr.getOutputStream())
                    )
                }
            }
            if (tempOutErr.hasRecordedStderr()) {
                tempOutErr.getErrorPath().getInputStream().use { `in` ->
                    com.google.common.io.ByteStreams.copy(
                        `in`, showIncludesFilterForStderr.getFilteredOutputStream(outErr.getErrorStream())
                    )
                }
            }
        } catch (e: IOException) {
            throw ActionExecutionException.fromExecException(
                EnvironmentalExecException(
                    e, createFailureDetail("OutErr copy failure", Code.COPY_OUT_ERR_FAILURE)
                ),
                this@CppCompileAction
            )
        }
    }

    private fun getDotDContents(spawnResult: SpawnResult): ByteArray? {
        if (getDotdFile() != null) {
            val content: ByteString? = spawnResult.getInMemoryOutput(getDotdFile())
            if (content != null) {
                return content.toByteArray()
            }
        }
        return null
    }

    private fun shouldParseShowIncludes(): Boolean {
        return featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES)
    }

    /** Dynamically compute the dependencies of a compilation using C++20 modules.  */
    @Throws(ActionExecutionException::class)
    private fun computeUsedCpp20Modules(
        actionExecutionContext: ActionExecutionContext
    ): com.google.common.collect.ImmutableSet<Artifact?>? {
        if (!featureConfiguration.isEnabled(CppRuleClasses.CPP_MODULES)) {
            return com.google.common.collect.ImmutableSet.of<Artifact?>()
        }
        // Module dependency scanning only needs source and header files.
        if (actionName == CppActionNames.CPP_MODULE_DEPS_SCANNING) {
            return com.google.common.collect.ImmutableSet.of<Artifact?>()
        }
        if (!isCpp20ModuleCompilationAction(actionName)
            && !CppFileTypes.CPP_SOURCE.matches(sourceFile.getExecPath())
        ) {
            return com.google.common.collect.ImmutableSet.of<Artifact?>()
        }
        val usedModulePaths: com.google.common.collect.ImmutableSet<String?>?
        try {
            // Read the file paths as raw bytes, which matches Bazel's internal encoding of path strings
            // (see StringEncoding).
            usedModulePaths =
                com.google.common.collect.ImmutableSet.copyOf<String?>(
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readLinesAsLatin1(
                        actionExecutionContext.getInputPath(modmapInputFile)
                    )
                )
        } catch (e: IOException) {
            val message: String? =
                java.lang.String.format("failed to read modmap input: %s", modmapInputFile.getExecPathString())
            val code: DetailedExitCode = createDetailedExitCode(message, Code.MODMAP_INPUT_FILE_READ_FAILURE)
            throw ActionExecutionException(message, this,  /* catastrophe= */false, code)
        }
        // All module files referenced in the modmap input file are expected to be known modules. We
        // delegate error reporting to the compiler by silently skipping over unknown files.
        return moduleFiles.toList().stream()
            .filter({ moduleFile -> usedModulePaths.contains(moduleFile.getExecPathString()) })
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
    }

    @Throws(ActionExecutionException::class)
    fun createSpawn(
        execRoot: com.google.devtools.build.lib.vfs.Path,
        args: MutableList<String>?,
        clientEnv: MutableMap<String?, String?>?,
        pathMapper: PathMapper?,
        paramFileActionInput: ParamFileActionInput?
    ): Spawn? {
        // Intentionally not adding {@link CppCompileAction#inputsForInvalidation}, those are not needed
        // for execution.
        val inputsBuilder: NestedSetBuilder<ActionInput?> =
            NestedSetBuilder.< ActionInput > stableOrder < ActionInput ? > ().addTransitive(mandatorySpawnInputs)

        if (discoversInputs()) {
            inputsBuilder.addTransitive(getAdditionalInputs())
        }
        if (paramFileActionInput != null) {
            inputsBuilder.add(paramFileActionInput)
        }
        val inputs: NestedSet<ActionInput?> = inputsBuilder.build()

        val executionInfo: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            com.google.common.collect.ImmutableMap.builder<String?, String?>().putAll(getExecutionInfo())
        if (getDotdFile() != null && useInMemoryDotdFiles()) {
            /*
       * CppCompileAction does dotd file scanning locally inside the Bazel process and thus
       * requires the dotd file contents to be available locally. In remote execution, we
       * generally don't want to stage all remote outputs on the local file system and thus
       * we need to tell the remote strategy (if any) to at least make the .d file available
       * in-memory. We can do that via
       * {@link ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS}.
       */
            executionInfo.put(
                ExecutionRequirements.REMOTE_EXECUTION_INLINE_OUTPUTS, getDotdFile().getExecPathString()
            )
        }

        if (shouldParseShowIncludes()) {
            // Hack on Windows. The included headers dumped by cl.exe in stdout contain absolute paths.
            // When compiling the file from different workspace, the shared cache will cause header
            // dependency checking to fail. This was initially fixed by a hack (see
            // https://github.com/bazelbuild/bazel/issues/9172 for more details), but is broken again due
            // to cl/356735700. We require execution service to ignore caches from other workspace.
            executionInfo.put(
                ExecutionRequirements.DIFFERENTIATE_WORKSPACE_CACHE, execRoot.getBaseName()
            )
        }

        val mandatoryOutputs: com.google.common.collect.ImmutableSet<Artifact?>?
        if (gcnoFile == null) {
            mandatoryOutputs = null // All outputs must be created.
        } else {
            // In coverage mode, the .gcno file is not produced for an empty translation unit, but the
            // spawn should still succeed.
            val outputs: MutableCollection<Artifact> = getOutputs()
            val builder: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.builderWithExpectedSize<Artifact?>(outputs.size() - 1)
            for (output in outputs) {
                if (!output.equals(gcnoFile)) {
                    builder.add(output)
                }
            }
            mandatoryOutputs = builder.build()
        }

        try {
            return SimpleSpawn(
                this,
                com.google.common.collect.ImmutableList.< E > copyOf < E ? > (args),
                getEffectiveEnvironment(clientEnv, pathMapper),
                executionInfo.buildOrThrow(),
                inputs,  /* tools= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                getOutputs(),
                mandatoryOutputs,
                {
                    estimateResourceConsumptionLocal(
                        enabledCppCompileResourcesEstimation(),
                        this.mnemonic,
                        com.google.devtools.build.lib.util.OS.getCurrent(),
                        inputs.memoizedFlattenAndGetSize()
                    )
                },
                pathMapper
            )
        } catch (e: CommandLineExpansionException) {
            val message: String? =
                java.lang.String.format(
                    "failed to generate compile command for rule '%s: %s",
                    getOwner().getLabel(), e.getMessage()
                )
            val code: DetailedExitCode = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE)
            throw ActionExecutionException(message, this,  /* catastrophe= */false, code)
        }
    }

    @Throws(ActionExecutionException::class)
    private fun discoverInputsFromShowIncludesFilters(
        execRoot: com.google.devtools.build.lib.vfs.Path,
        artifactResolver: ArtifactResolver?,
        showIncludesFilterForStdout: ShowIncludesFilter,
        showIncludesFilterForStderr: ShowIncludesFilter,
        siblingRepositoryLayout: Boolean,
        pathMapper: PathMapper?
    ): NestedSet<Artifact?> {
        val stdoutDeps: MutableCollection<com.google.devtools.build.lib.vfs.Path?> =
            showIncludesFilterForStdout.getDependencies(execRoot)
        val stderrDeps: MutableCollection<com.google.devtools.build.lib.vfs.Path?> =
            showIncludesFilterForStderr.getDependencies(execRoot)
        if (stdoutDeps.isEmpty()
            && stderrDeps.isEmpty()
            && (showIncludesFilterForStdout.sawPotentialUnsupportedShowIncludesLine()
                    || showIncludesFilterForStderr.sawPotentialUnsupportedShowIncludesLine())
        ) {
            // /showIncludes parsing didn't result in any headers being found (unusual) *and* also
            // encountered a line that looked like /showIncludes output in an unsupported encoding.
            val message =
                ("While parsing the C++ compiler output for information about included headers, Bazel "
                        + "failed to find any headers but encountered a line that appears to be "
                        + "/showIncludes output in an unsupported encoding. This can result in incorrect "
                        + "incremental builds. If you are using the default Windows MSVC toolchain that "
                        + "ships with Bazel, ensure that the English language pack for Visual Studio is "
                        + "installed and then run 'bazel clean --expunge'.")
            val code: DetailedExitCode = createDetailedExitCode(message, Code.FIND_USED_HEADERS_IO_EXCEPTION)
            throw ActionExecutionException(message, this,  /* catastrophe= */false, code)
        }
        return HeaderDiscovery.discoverInputsFromDependencies(
            this,
            getSourceFile(),
            needsIncludeValidation,
            com.google.common.collect.ImmutableList.builderWithExpectedSize<com.google.devtools.build.lib.vfs.Path?>(
                stdoutDeps.size() + stderrDeps.size()
            )
                .addAll(stdoutDeps)
                .addAll(stderrDeps)
                .build(),
            getPermittedSystemIncludePrefixes(execRoot),
            getAllowedDerivedInputs(),
            execRoot,
            artifactResolver,
            siblingRepositoryLayout,
            pathMapper
        )
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(ActionExecutionException::class)
    fun discoverInputsFromDotdFiles(
        actionExecutionContext: ActionExecutionContext,
        execRoot: com.google.devtools.build.lib.vfs.Path,
        artifactResolver: ArtifactResolver?,
        dotDContents: ByteArray?,
        siblingRepositoryLayout: Boolean,
        pathMapper: PathMapper?
    ): NestedSet<Artifact?> {
        com.google.common.base.Preconditions.checkNotNull<Any?>(getDotdFile(), "Trying to scan .d file which is unset")
        return HeaderDiscovery.discoverInputsFromDependencies(
            this,
            getSourceFile(),
            needsIncludeValidation,
            processDepset(actionExecutionContext, execRoot, dotDContents).getDependencies(),
            getPermittedSystemIncludePrefixes(execRoot),
            getAllowedDerivedInputs(),
            execRoot,
            artifactResolver,
            siblingRepositoryLayout,
            pathMapper
        )
    }

    @Throws(ActionExecutionException::class)
    private fun processDepset(
        actionExecutionContext: ActionExecutionContext,
        execRoot: com.google.devtools.build.lib.vfs.Path?,
        dotDContents: ByteArray?
    ): DependencySet? {
        try {
            val depSet: DependencySet = DependencySet(execRoot)
            if (dotDContents != null && cppConfiguration().getInmemoryDotdFiles()) {
                return depSet.process(dotDContents)
            }
            return depSet.read(actionExecutionContext.getInputPath(getDotdFile()))
        } catch (e: IOException) {
            // Some kind of IO or parse exception--wrap & rethrow it to stop the build.
            val message = "error while parsing .d file: " + e.getMessage()
            throw ActionExecutionException(
                message, e, this, false, createDetailedExitCode(message, Code.D_FILE_PARSE_FAILURE)
            )
        }
    }

    private fun getPermittedSystemIncludePrefixes(execRoot: com.google.devtools.build.lib.vfs.Path): MutableList<com.google.devtools.build.lib.vfs.Path?> {
        val systemIncludePrefixes: MutableList<com.google.devtools.build.lib.vfs.Path?> =
            java.util.ArrayList<com.google.devtools.build.lib.vfs.Path?>()
        for (includePath in getBuiltInIncludeDirectories()) {
            if (includePath.isAbsolute()) {
                systemIncludePrefixes.add(execRoot.getFileSystem().getPath(includePath))
            }
        }
        return systemIncludePrefixes
    }

    /**
     * Gcc only creates a ".gcno" file if the compilation unit is non-empty. To ensure that the set of
     * outputs for a CppCompileAction remains consistent and doesn't vary dynamically depending on the
     * _contents_ of the input files, we create an empty ".gcno" file if gcc didn't create it.
     */
    @Throws(ActionExecutionException::class)
    private fun ensureCoverageNotesFileExists(actionExecutionContext: ActionExecutionContext) {
        if (gcnoFile == null) {
            return
        }
        if (!gcnoFile.isFileType(CppFileTypes.COVERAGE_NOTES)) {
            BugReport.sendNonFatalBugReport(
                java.lang.IllegalStateException(
                    "In coverage mode but gcno artifact is not correct type: " + gcnoFile + ", " + this
                )
            )
            return
        }
        val outputPath: com.google.devtools.build.lib.vfs.Path = actionExecutionContext.getInputPath(gcnoFile)
        if (outputPath.exists()) {
            return
        }
        try {
            com.google.devtools.build.lib.vfs.FileSystemUtils.createEmptyFile(outputPath)
        } catch (e: IOException) {
            val message = "Error creating file '" + outputPath + "': " + e.getMessage()
            val code: DetailedExitCode = createDetailedExitCode(message, Code.COVERAGE_NOTES_CREATION_FAILURE)
            throw ActionExecutionException(message, e, this, false, code)
        }
    }

    /**
     * When compiling with modules, the C++ compile action only has the `.pcm` files on its
     * inputs, which is not enough for extra actions that parse header files. Thus, re-run include
     * scanning and add headers to the inputs of the extra action, too.
     * 
     * 
     * This method returns null when a required SkyValue is missing and a Skyframe restart is
     * required.
     */
    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun getInputFilesForExtraAction(
        actionExecutionContext: ActionExecutionContext
    ): NestedSet<Artifact?>? {
        if (!shouldScanIncludes) {
            return NestedSetBuilder.fromNestedSet(ccCompilationContext.getDeclaredIncludeSrcs())
                .addTransitive(additionalPrunableHeaders)
                .build()
        }
        try {
            val includeScanningHeaderData: com.google.devtools.build.lib.rules.cpp.IncludeScanner.IncludeScanningHeaderData.Builder? =
                ccCompilationContext.createIncludeScanningHeaderData(
                    actionExecutionContext.getEnvironmentForDiscoveringInputs(),
                    usePic,
                    useHeaderModules
                )
            if (includeScanningHeaderData == null) {
                return null
            }
            val usedHeaders: NestedSet<Artifact?>? =
                findUsedHeaders(
                    actionExecutionContext,
                    includeScanningHeaderData
                        .setSystemIncludeDirs(this.systemIncludeDirs)
                        .setCmdlineIncludes(getCmdlineIncludes(this.compilerOptions))
                        .build()
                )
            if (usedHeaders == null) {
                return null
            }

            val usedModules: MutableSet<DerivedArtifact>? = findUsedModules(usedHeaders.toSet())
            if (usedModules == null) {
                return usedHeaders
            }
            val requiredModules =
                findMinimalSetOfRequiredModules(actionExecutionContext, usedModules)
            if (requiredModules == null) {
                return null
            }
            // Update state used when forming the command line.
            setTopLevelModules(requiredModules.topLevel)

            // Note that we do not update discoveredModules here even though it would allow to avoid
            // waste by sharing work with action execution (see discoverInputs).
            //
            // discoveredModules is stored only for compile actions that produce PCMs, but we need
            // them for all actions involving modules to correctly compute results of this function.
            // However, storing them for all actions would waste memory.
            // We instead choose to make this function more expensive and let the people using shadowed
            // actions and extra action pay the cost.
            return NestedSetBuilder.fromNestedSet(usedHeaders)
                .addTransitive(requiredModules.transitivelyUsed)
                .build()
        } catch (e: CommandLineExpansionException) {
            val message: String? =
                java.lang.String.format(
                    "failed to generate compile environment variables for rule '%s: %s",
                    getOwner().getLabel(), e.getMessage()
                )
            val code: DetailedExitCode = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE)
            throw ActionExecutionException(message, this,  /* catastrophe= */false, code)
        }
    }

    val mnemonic: String
        get() = actionNameToMnemonic(
            actionName, featureConfiguration, cppConfiguration().useCppCompileHeaderMnemonic()
        )

    public override fun describeKey(): String {
        val message: java.lang.StringBuilder = java.lang.StringBuilder()
        message.append(getProgressMessage())
        message.append('\n')
        // Outputting one argument per line makes it easier to diff the results.
        // The first element in getArguments() is actually the command to execute.
        var legend = "  Command: "
        try {
            for (argument in ShellEscaper.escapeAll(this.arguments)) {
                message.append(legend)
                message.append(argument)
                message.append('\n')
                legend = "  Argument: "
            }
        } catch (e: CommandLineExpansionException) {
            message.append("  Could not expand command line: ")
            message.append(e)
            message.append('\n')
        }

        for (src in this.declaredIncludeSrcs.toList()) {
            message.append("  Declared include source: ")
            message.append(ShellEscaper.escapeString(src.getExecPathString()))
            message.append('\n')
        }

        return message.toString()
    }

    fun getCompileCommandLine(): CompileCommandLine {
        return compileCommandLine
    }

    /** Returns true if Dotd file should be generated.  */
    private fun isGenerateDotdFile(sourceArtifact: Artifact): Boolean {
        return CppFileTypes.headerDiscoveryRequired(sourceArtifact)
                && !featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES)
    }

    companion object {
        private val GUID: UUID = UUID.fromString("97493805-894f-493a-be66-9a698f45c31d")

        @com.google.common.annotations.VisibleForTesting
        const val CPP_COMPILE_MNEMONIC: String = "CppCompile"

        @com.google.common.annotations.VisibleForTesting
        const val OBJC_COMPILE_MNEMONIC: String = "ObjcCompile"

        private fun collectOutputs(
            outputFile: Artifact?,
            dotdFile: Artifact?,
            diagnosticsFile: Artifact?,
            gcnoFile: Artifact?,
            dwoFile: Artifact?,
            ltoIndexingFile: Artifact?,
            additionalOutputs: com.google.common.collect.ImmutableList<Artifact?>
        ): com.google.common.collect.ImmutableSet<Artifact?> {
            val outputs: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.builder<Artifact?>()
            outputs.add(outputFile)
            if (gcnoFile != null) {
                outputs.add(gcnoFile)
            }
            outputs.addAll(additionalOutputs)
            if (dotdFile != null) {
                outputs.add(dotdFile)
            }
            if (diagnosticsFile != null) {
                outputs.add(diagnosticsFile)
            }
            if (dwoFile != null) {
                outputs.add(dwoFile)
            }
            if (ltoIndexingFile != null) {
                outputs.add(ltoIndexingFile)
            }
            return outputs.build()
        }

        fun buildCommandLine(
            coptsFilter: CoptsFilter?,
            actionName: String?,
            featureConfiguration: FeatureConfiguration?,
            variables: CcToolchainVariables?
        ): CompileCommandLine {
            return CompileCommandLine.Companion.builder(coptsFilter, actionName)
                .setFeatureConfiguration(featureConfiguration)
                .setVariables(variables)
                .build()
        }

        private val MSVC_CHARS: com.google.common.collect.ImmutableList<com.google.common.base.CharMatcher?> =
            com.google.common.collect.ImmutableList.of<com.google.common.base.CharMatcher?>(
                com.google.common.base.CharMatcher.anyOf("mM"),
                com.google.common.base.CharMatcher.anyOf("sS"),
                com.google.common.base.CharMatcher.anyOf("vV"),
                com.google.common.base.CharMatcher.anyOf("cC")
            )
        private val INCLUDE_PREFIX_CHARS: com.google.common.collect.ImmutableList<com.google.common.base.CharMatcher?> =
            com.google.common.collect.ImmutableList.of<com.google.common.base.CharMatcher?>(
                com.google.common.base.CharMatcher.anyOf("-/"), com.google.common.base.CharMatcher.anyOf("iI")
            )

        private fun substrMatchesChars(
            s: String,
            startPos: Int,
            substr: com.google.common.collect.ImmutableList<com.google.common.base.CharMatcher?>
        ): Boolean {
            for (i in substr.indices) {
                if (!substr.get(i).matches(s.charAt(startPos + i))) {
                    return false
                }
            }
            return true
        }

        private fun matchesCaseInsensitiveMsvc(s: String): Boolean {
            return s.length() >= 4 && substrMatchesChars(s, 0, MSVC_CHARS)
        }

        private fun matchesIncludeCaseInsensitiveMsvc(s: String): Boolean {
            return s.length() >= 6 && substrMatchesChars(s, 0, INCLUDE_PREFIX_CHARS)
                    && substrMatchesChars(s, 2, MSVC_CHARS)
        }

        private fun getCmdlineIncludes(args: MutableList<String>): com.google.common.collect.ImmutableList<String?> {
            val cmdlineIncludes: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            val argi = args.iterator()
            while (argi.hasNext()) {
                val arg = argi.next()
                if (arg == "-include" && argi.hasNext()) {
                    cmdlineIncludes.add(argi.next())
                } else if (arg.startsWith("-FI") || arg.startsWith("/FI")) {
                    if (arg.length() > 3) {
                        cmdlineIncludes.add(arg.substring(3).trim())
                    } else if (argi.hasNext()) {
                        cmdlineIncludes.add(argi.next())
                    }
                }
            }
            return cmdlineIncludes.build()
        }

        private fun validateInclude(
            allowedIncludes: MutableSet<Artifact?>, ignoreDirs: Iterable<PathFragment?>?, include: Artifact
        ): Boolean {
            // Only declared modules are added to an action and so they are always valid.
            return include.isFileType(CppFileTypes.CPP_MODULE)
                    ||  // TODO(b/145253507): Exclude objc module maps from check, due to bad interaction with
                    // local_objc_modules feature.
                    include.isFileType(CppFileTypes.OBJC_MODULE_MAP)
                    ||  // It's a declared include/
                    allowedIncludes.contains(include)
                    ||  // Ignore headers from built-in include directories.
                    com.google.devtools.build.lib.vfs.FileSystemUtils.startsWithAny(include.getExecPath(), ignoreDirs)
        }

        /**
         * Extracts all module (.pcm) files from potentialModules and returns a Variables object where
         * their exec paths are added to the value "module_files".
         */
        private fun calculateModuleVariable(
            potentialModules: NestedSet<Artifact?>
        ): CcToolchainVariables? {
            val usedModulePaths: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (input in potentialModules.toList()) {
                if (input.isFileType(CppFileTypes.CPP_MODULE)) {
                    usedModulePaths.add(input.getExecPathString())
                }
            }
            val variableBuilder: com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder =
                CcToolchainVariables.Companion.builder()
            variableBuilder.addStringSequenceVariable(
                CompileBuildVariables.MODULE_FILES.getVariableName(), usedModulePaths.build()
            )
            return variableBuilder.build()
        }

        /**
         * Estimates resource consumption when this action is executed locally. During investigation we
         * found linear dependency between used memory by action and number of inputs. For memory
         * estimation we are using form C + K * inputs, where C and K selected in such way, that more than
         * 95% of actions used less than C + K * inputs MB of memory during execution.
         */
        fun estimateResourceConsumptionLocal(
            enabled: Boolean, mnemonic: String?, os: com.google.devtools.build.lib.util.OS, inputs: Int
        ): ResourceSet {
            if (!enabled) {
                return AbstractAction.DEFAULT_RESOURCE_SET
            }

            if (mnemonic == null) {
                return AbstractAction.DEFAULT_RESOURCE_SET
            }

            when (mnemonic) {
                CPP_COMPILE_MNEMONIC -> when (os) {
                    com.google.devtools.build.lib.util.OS.DARWIN, com.google.devtools.build.lib.util.OS.LINUX -> return ResourceSet.createWithRamCpu( /* memoryMb= */
                        80 + 0.7 * inputs,  /* cpu= */
                        1
                    )

                    else -> return AbstractAction.DEFAULT_RESOURCE_SET
                }

                OBJC_COMPILE_MNEMONIC -> when (os) {
                    com.google.devtools.build.lib.util.OS.DARWIN -> return ResourceSet.createWithRamCpu( /* memoryMb= */
                        80 + 0.2 * inputs,  /* cpu= */
                        1
                    )

                    else -> return AbstractAction.DEFAULT_RESOURCE_SET
                }

                else -> return AbstractAction.DEFAULT_RESOURCE_SET
            }
        }

        // Separated into a helper method so that it can be called from CppCompileActionTemplate.
        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        fun computeKey(
            actionKeyContext: ActionKeyContext,
            fp: Fingerprint,
            env: ActionEnvironment,
            environmentVariables: MutableMap<String?, String?>,
            executionInfo: MutableMap<String?, String?>,
            commandLineKey: ByteArray?,
            declaredIncludeSrcs: NestedSet<Artifact?>?,
            mandatoryInputs: NestedSet<Artifact?>?,
            mandatorySpawnInputs: NestedSet<Artifact?>?,
            prunableHeaders: NestedSet<Artifact?>?,
            builtInIncludeDirectories: MutableList<PathFragment>,
            inputsForInvalidation: NestedSet<Artifact?>?,
            mnemonic: String?,
            outputPathsMode: OutputPathsMode?
        ) {
            fp.addUUID(GUID)
            env.addTo(fp)
            fp.addStringMap(environmentVariables)
            fp.addStringMap(executionInfo)
            fp.addBytes(commandLineKey)

            actionKeyContext.addNestedSetToFingerprint(fp, declaredIncludeSrcs)
            fp.addInt(0) // mark the boundary between input types
            actionKeyContext.addNestedSetToFingerprint(fp, mandatoryInputs)
            actionKeyContext.addNestedSetToFingerprint(fp, mandatorySpawnInputs)
            fp.addInt(0)
            actionKeyContext.addNestedSetToFingerprint(fp, prunableHeaders)

            /*
     * getArguments() above captures all changes which affect the compilation command and hence the
     * contents of the object file. But we need to also make sure that we re-execute the action if
     * any of the fields that affect whether {@link #validateInclusions} will report an error or
     * warning have changed, otherwise we might miss some errors.
     */
            fp.addPaths(builtInIncludeDirectories)

            // This is needed for CppLinkstampCompile.
            fp.addInt(0)
            actionKeyContext.addNestedSetToFingerprint(fp, inputsForInvalidation)

            PathMappers.addToFingerprint(
                mnemonic,
                executionInfo,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                actionKeyContext,
                outputPathsMode,
                fp
            )
        }

        fun computeCommandLineKey(compilerOptions: MutableList<String>): ByteArray? {
            val fp: Fingerprint = Fingerprint()
            fp.addStrings(compilerOptions)
            return fp.digestAndReset()
        }

        fun actionNameToMnemonic(
            actionName: String,
            featureConfiguration: FeatureConfiguration,
            useCppCompileHeaderMnemonic: Boolean
        ): String {
            when (actionName) {
                CppActionNames.OBJC_COMPILE, CppActionNames.OBJCPP_COMPILE -> return OBJC_COMPILE_MNEMONIC

                CppActionNames.LINKSTAMP_COMPILE ->         // When compiling shared native deps, e.g. when two java_binary rules have the same set of
                    // native dependencies, the CppCompileAction for link stamp data is shared also. This means
                    // that out of two CppCompileAction instances, only one is actually executed, which means
                    // that if extra actions are attached to both, one of the extra actions will find a
                    // CppCompileAction for which discoverInputs() hasn't been called and thus trigger an
                    // assertion. As a band-aid, change the mnemonic of said actions so that one can attach
                    // extra actions to regular CppCompileActions without tickling this bug.
                    return "CppLinkstampCompile"

                CppActionNames.CPP_HEADER_PARSING -> {
                    val suffix = if (useCppCompileHeaderMnemonic) "Header" else ""
                    return if (featureConfiguration.isEnabled(CppRuleClasses.LANG_OBJC))
                        OBJC_COMPILE_MNEMONIC + suffix
                    else
                        CPP_COMPILE_MNEMONIC + suffix
                }

                CppActionNames.CPP_HEADER_ANALYSIS -> return "CppHeaderAnalysis"
                CppActionNames.CPP_MODULE_DEPS_SCANNING -> return "CppDepsScanning"
                else -> return CPP_COMPILE_MNEMONIC
            }
        }

        /**
         * For the given `usedModules`, looks up modules discovered by their generating actions.
         * 
         * 
         * The returned value only contains a map from elements of `usedModules` to the [ ][.discoveredModules] required to use them. If dependent actions have not been executed yet (and
         * thus [.discoveredModules] aren't known yet, returns null.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun computeTransitivelyUsedModules(
            env: SkyFunction.Environment, usedModules: MutableSet<DerivedArtifact>
        ): com.google.common.collect.ImmutableMap<Artifact?, NestedSet<Artifact?>?>? {
            // Because SkyframeLookupResult.get call does not specify any exceptions where
            // SkyframeLookupResult is returned by env.getValuesAndExceptions, it is impossible for input
            // discovery to recover from exceptions thrown by spurious module deps (for instance, if a
            // commented-out include references a header file with an error in it). However, we generally
            // don't try to recover from errors around spurious includes discovered in the current build.
            // TODO(janakr): Can errors be aggregated here at least?
            val skyKeys: MutableCollection<SkyKey?> =
                com.google.common.collect.Collections2.transform<DerivedArtifact?, SkyKey?>(
                    usedModules,
                    DerivedArtifact::getGeneratingActionKey
                )
            val actionExecutionValues: SkyframeLookupResult = env.getValuesAndExceptions(skyKeys)
            if (env.valuesMissing()) {
                return null
            }
            val transitivelyUsedModules: com.google.common.collect.ImmutableMap.Builder<Artifact?, NestedSet<Artifact?>?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<Artifact?, NestedSet<Artifact?>?>(
                    usedModules.size()
                )
            for (module in usedModules) {
                com.google.common.base.Preconditions.checkState(
                    module.isFileType(CppFileTypes.CPP_MODULE), "Non-module? %s", module
                )
                val skyValue: SkyValue? = actionExecutionValues.get(module.getGeneratingActionKey())
                if (skyValue == null) {
                    return null
                }
                val value: ActionExecutionValue =
                    com.google.common.base.Preconditions.checkNotNull<ActionExecutionValue>(
                        skyValue as ActionExecutionValue,
                        module
                    )
                transitivelyUsedModules.put(module, value.getDiscoveredModules())
            }
            return transitivelyUsedModules.buildOrThrow()
        }

        private fun createDetailedExitCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(createFailureDetail(message, detailedCode))
        }

        private fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setCppCompile(CppCompile.newBuilder().setCode(detailedCode))
                .build()
        }

        fun isCpp20ModuleCompilationAction(actionName: String): Boolean {
            return actionName == CppActionNames.CPP20_MODULE_COMPILE
                    || actionName == CppActionNames.CPP20_MODULE_CODEGEN
        }
    }
}
