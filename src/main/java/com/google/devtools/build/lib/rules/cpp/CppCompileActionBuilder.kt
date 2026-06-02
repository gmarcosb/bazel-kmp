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

import com.google.devtools.build.lib.actions.ActionEnvironment

/** Builder class to construct C++ compile actions.  */
class CppCompileActionBuilder : net.starlark.java.eval.StarlarkValue {
    private val owner: ActionOwner?
    private var shareable: Boolean
    private val configuration: BuildConfigurationValue
    private var featureConfiguration: FeatureConfiguration? = null
    private var variables: CcToolchainVariables? = CcToolchainVariables.Companion.empty()
    private var sourceFile: Artifact? = null
    private val mandatoryInputsBuilder: NestedSetBuilder<Artifact?>
    private var outputFile: Artifact? = null
    private var modmapFile: Artifact? = null
    private var modmapInputFile: Artifact? = null
    private var moduleFiles: NestedSet<Artifact?>? = null
    private var dwoFile: Artifact? = null
    private var ltoIndexingFile: Artifact? = null
    private var dotdFile: Artifact? = null
    private var diagnosticsFile: Artifact? = null
    private var gcnoFile: Artifact? = null
    private var ccCompilationContext: CcCompilationContext? = null
    private val pluginOpts: MutableList<String?> = java.util.ArrayList<String?>()
    private var coptsFilter: CoptsFilter? = CoptsFilter.Companion.alwaysPasses()
    private var extraSystemIncludePrefixes: com.google.common.collect.ImmutableList<PathFragment?>? =
        com.google.common.collect.ImmutableList.of<PathFragment?>()
    private var usePic = false
    private val cppConfiguration: CppConfiguration
    private val additionalIncludeScanningRoots: java.util.ArrayList<Artifact?>
    private var shouldScanIncludes: Boolean? = null
    var executionInfo: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        private set
    private val ccToolchain: CcToolchainProvider
    private var actionName: String? = null
    private var progressMessagePrefix: String? = ""
    private var buildInfoHeaderArtifacts: com.google.common.collect.ImmutableList<Artifact?> =
        com.google.common.collect.ImmutableList.of<Artifact?>()
    private var cacheKeyInputs: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    private var additionalPrunableHeaders: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    private var additionalOutputs: com.google.common.collect.ImmutableList<Artifact?>? =
        com.google.common.collect.ImmutableList.of<Artifact?>()
    private var needsIncludeValidation = false

    // New fields need to be added to the copy constructor.
    /** Creates a builder from an owner and a configuration.  */
    constructor(owner: ActionOwner?, ccToolchain: CcToolchainProvider, configuration: BuildConfigurationValue) {
        this.owner = owner
        this.shareable = false
        this.configuration = configuration
        this.cppConfiguration = configuration.getFragment(CppConfiguration::class.java)
        this.mandatoryInputsBuilder = NestedSetBuilder.stableOrder()
        this.additionalIncludeScanningRoots = java.util.ArrayList<Artifact?>()
        this.ccToolchain = ccToolchain
    }

    /** Creates a builder from a rule and a configuration.  */
    constructor(
        actionConstructionContext: ActionConstructionContext,
        ccToolchain: CcToolchainProvider,
        configuration: BuildConfigurationValue,
        cppToolchainType: String?
    ) : this(getActionOwner(actionConstructionContext, cppToolchainType), ccToolchain, configuration)

    /** Creates a builder that is a copy of another builder.  */
    constructor(other: CppCompileActionBuilder) {
        this.owner = other.owner
        this.shareable = other.shareable
        this.featureConfiguration = other.featureConfiguration
        this.sourceFile = other.sourceFile
        this.mandatoryInputsBuilder =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ()
                .addTransitive(other.mandatoryInputsBuilder.build())
        this.additionalIncludeScanningRoots = java.util.ArrayList<Artifact?>()
        this.additionalIncludeScanningRoots.addAll(other.additionalIncludeScanningRoots)
        this.outputFile = other.outputFile
        this.dwoFile = other.dwoFile
        this.ltoIndexingFile = other.ltoIndexingFile
        this.dotdFile = other.dotdFile
        this.gcnoFile = other.gcnoFile
        this.ccCompilationContext = other.ccCompilationContext
        this.pluginOpts.addAll(other.pluginOpts)
        this.coptsFilter = other.coptsFilter
        this.extraSystemIncludePrefixes = other.extraSystemIncludePrefixes
        this.cppConfiguration = other.cppConfiguration
        this.configuration = other.configuration
        this.usePic = other.usePic
        this.shouldScanIncludes = other.shouldScanIncludes
        this.executionInfo = LinkedHashMap<String?, String?>(other.executionInfo)
        this.ccToolchain = other.ccToolchain
        this.actionName = other.actionName
        this.progressMessagePrefix = other.progressMessagePrefix
        this.additionalOutputs = other.additionalOutputs
        this.needsIncludeValidation = other.needsIncludeValidation
        this.moduleFiles = other.moduleFiles
        this.modmapFile = other.modmapFile
        this.modmapInputFile = other.modmapInputFile
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setSourceFile(sourceFile: Artifact?): CppCompileActionBuilder {
        com.google.common.base.Preconditions.checkState(
            this.sourceFile == null,
            "New source file %s trying to overwrite old source file %s",
            sourceFile,
            this.sourceFile
        )
        return setSourceFileUnchecked(sourceFile)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setSourceFile(sourceFile: Artifact.TreeFileArtifact?): CppCompileActionBuilder {
        com.google.common.base.Preconditions.checkState(
            this.sourceFile !is Artifact.TreeFileArtifact,
            "New source file %s trying to overwrite old source file %s also a tree file artifact",
            sourceFile,
            this.sourceFile
        )
        return setSourceFileUnchecked(sourceFile)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun setSourceFileUnchecked(sourceFile: Artifact?): CppCompileActionBuilder {
        this.sourceFile = sourceFile
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setAdditionalOutputs(additionalOutputs: com.google.common.collect.ImmutableList<Artifact?>?): CppCompileActionBuilder {
        this.additionalOutputs = additionalOutputs
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setNeedsIncludeValidation(needsIncludeValidation: Boolean): CppCompileActionBuilder {
        this.needsIncludeValidation = needsIncludeValidation
        return this
    }

    fun getOwner(): ActionOwner? {
        return owner
    }

    fun getSourceFile(): Artifact? {
        return sourceFile
    }

    fun getCcCompilationContext(): CcCompilationContext? {
        return ccCompilationContext
    }

    fun getActionName(): String {
        if (actionName != null) {
            return actionName!!
        }
        val sourcePath: PathFragment = sourceFile.getExecPath()
        if (CppFileTypes.CPP_MODULE_MAP.matches(sourcePath)) {
            return CppActionNames.CPP_MODULE_COMPILE
        } else if (CppFileTypes.CPP_HEADER.matches(sourcePath)) {
            // TODO(bazel-team): Handle C headers that probably don't work in C++ mode.
            if (featureConfiguration.isEnabled(CppRuleClasses.PARSE_HEADERS)) {
                return CppActionNames.CPP_HEADER_PARSING
            }
            // CcCommon.collectCAndCppSources() ensures we do not add headers to
            // the compilation artifacts unless 'parse_headers' is set.
            throw java.lang.IllegalStateException()
        } else if (CppFileTypes.C_SOURCE.matches(sourcePath)) {
            return CppActionNames.C_COMPILE
        } else if (CppFileTypes.CPP_SOURCE.matches(sourcePath)) {
            return CppActionNames.CPP_COMPILE
        } else if (CppFileTypes.OBJC_SOURCE.matches(sourcePath)) {
            return CppActionNames.OBJC_COMPILE
        } else if (CppFileTypes.OBJCPP_SOURCE.matches(sourcePath)) {
            return CppActionNames.OBJCPP_COMPILE
        } else if (CppFileTypes.ASSEMBLER.matches(sourcePath)) {
            return CppActionNames.ASSEMBLE
        } else if (CppFileTypes.ASSEMBLER_WITH_C_PREPROCESSOR.matches(sourcePath)) {
            return CppActionNames.PREPROCESS_ASSEMBLE
        } else if (CppFileTypes.CLIF_INPUT_PROTO.matches(sourcePath)) {
            return CppActionNames.CLIF_MATCH
        } else if (CppFileTypes.CPP_MODULE.matches(sourcePath)) {
            return CppActionNames.CPP_MODULE_CODEGEN
        }
        // CcCompilationHelper ensures CppCompileAction only gets instantiated for supported file types.
        throw java.lang.IllegalStateException()
    }

    /**
     * Builds the Action as configured and performs some validations on the action. Throws [ ] to report errors. Prefer [ ][CppCompileActionBuilder.buildOrThrowRuleError] over this method whenever
     * possible (meaning whenever you have access to [RuleContext]).
     * 
     * 
     * This method may be called multiple times to create multiple compile actions (usually after
     * calling some setters to modify the generated action).
     */
    fun buildOrThrowIllegalStateException(): CppCompileAction {
        try {
            return buildAndVerify()
        } catch (e: UnconfiguredActionConfigException) {
            throw java.lang.IllegalStateException(e)
        } catch (e: net.starlark.java.eval.EvalException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    /** Exception thrown when the action is not configured in the toolchain.  */
    class UnconfiguredActionConfigException private constructor(actionName: String?) :
        java.lang.Exception(java.lang.String.format("Expected action_config for '%s' to be configured", actionName))

    /**
     * Builds the Action as configured and performs some validations on the action. Uses given [ ] to collect validation errors.
     */
    @Throws(UnconfiguredActionConfigException::class, net.starlark.java.eval.EvalException::class)
    fun buildAndVerify(): CppCompileAction {
        com.google.common.base.Preconditions.checkNotNull<FeatureConfiguration?>(featureConfiguration)
        val useHeaderModules = useHeaderModules()

        val actionName = getActionName()
        if (featureConfiguration.actionIsConfigured(actionName)) {
            for (executionRequirement in featureConfiguration.getToolRequirementsForAction(actionName)) {
                executionInfo.put(executionRequirement, "")
            }
        } else {
            throw UnconfiguredActionConfigException(actionName)
        }

        // If include scanning is enabled, we can use the filegroup without header files - they are
        // found by include scanning.  We still need the system framework headers since they are not
        // being scanned right now, but those are placed in the "compile" file group.
        // TODO(djasper): When not include scanning, getCompilerFiles() should be enough here, but that
        // doesn't currently include GRTE headers.
        var compilerFilesWithoutIncludes: NestedSet<Artifact?> =
            ccToolchain.getCompilerFilesWithoutIncludes()
        if (compilerFilesWithoutIncludes.isEmpty()) {
            compilerFilesWithoutIncludes = ccToolchain.getCompilerFiles()
        }
        addTransitiveMandatoryInputs(
            if (getShouldScanIncludes())
                compilerFilesWithoutIncludes
            else
                if (configuration.getFragment(CppConfiguration::class.java).useSpecificToolFiles()
                    && !getSourceFile().isTreeArtifact()
                )
                    (if (getActionName() == CppActionNames.ASSEMBLE)
                        ccToolchain.getAsFiles()
                    else
                        ccToolchain.getCompilerFiles())
                else
                    ccToolchain.getAllFiles()
        )

        val realMandatorySpawnInputs: NestedSet<Artifact?> = buildMandatoryInputs()
        val realMandatoryInputs: NestedSet<Artifact?>? =
            NestedSet.< Artifact > builder < Artifact ? > (Order.STABLE_ORDER)
                .addTransitive(realMandatorySpawnInputs)
                .addTransitive(cacheKeyInputs)
                .build()
        val prunableHeaders: NestedSet<Artifact?>? = additionalPrunableHeaders

        configuration.modifyExecutionInfo(
            executionInfo,
            CppCompileAction.Companion.actionNameToMnemonic(
                actionName, featureConfiguration, cppConfiguration.useCppCompileHeaderMnemonic()
            )
        )

        // Copying the collections is needed to make the builder reusable.
        return CppCompileAction(
            owner,
            featureConfiguration,
            variables,
            sourceFile,
            configuration,
            shareable,
            shouldScanIncludes,
            usePic,
            useHeaderModules,
            realMandatoryInputs,
            realMandatorySpawnInputs,
            this.builtinIncludeFiles,
            prunableHeaders,
            outputFile,
            dotdFile,
            diagnosticsFile,
            gcnoFile,
            dwoFile,
            ltoIndexingFile,
            ccCompilationContext,
            coptsFilter,
            com.google.common.collect.ImmutableList.copyOf<Artifact?>(additionalIncludeScanningRoots),
            com.google.common.collect.ImmutableMap.copyOf<String?, String?>(executionInfo),
            actionName,
            progressMessagePrefix,
            needsIncludeValidation,
            this.builtinIncludeDirectories,
            ccToolchain.getGrepIncludes(),
            additionalOutputs,
            moduleFiles,
            modmapInputFile
        )
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    private val builtinIncludeFiles: com.google.common.collect.ImmutableList<Artifact?>?
        get() {
            val builtinIncludeFiles: com.google.common.collect.ImmutableList<Artifact?> =
                ccToolchain.getBuiltinIncludeFiles()
            if (buildInfoHeaderArtifacts.isEmpty()) {
                return builtinIncludeFiles
            }
            if (builtinIncludeFiles.isEmpty()) {
                return buildInfoHeaderArtifacts
            }
            return com.google.common.collect.ImmutableList.builderWithExpectedSize<Artifact?>(
                builtinIncludeFiles.size() + buildInfoHeaderArtifacts.size()
            )
                .addAll(builtinIncludeFiles)
                .addAll(buildInfoHeaderArtifacts)
                .build()
        }

    private fun shouldParseShowIncludes(): Boolean {
        return featureConfiguration.isEnabled(CppRuleClasses.PARSE_SHOWINCLUDES)
    }

    /** Returns the list of mandatory inputs for the [CppCompileAction] as configured.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun buildMandatoryInputs(): NestedSet<Artifact?> {
        com.google.common.base.Preconditions.checkNotNull<CcCompilationContext?>(ccCompilationContext)
        val realMandatoryInputsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.compileOrder()
        realMandatoryInputsBuilder.addTransitive(mandatoryInputsBuilder.build())
        realMandatoryInputsBuilder.addAll(this.builtinIncludeFiles)
        if (useHeaderModules() && !getShouldScanIncludes()) {
            realMandatoryInputsBuilder.addTransitive(ccCompilationContext.getTransitiveModules(usePic))
        }
        ccCompilationContext.addAdditionalInputs(realMandatoryInputsBuilder)
        realMandatoryInputsBuilder.add(com.google.common.base.Preconditions.checkNotNull<T?>(sourceFile))
        if (ccToolchain.getGrepIncludes() != null) {
            realMandatoryInputsBuilder.add(ccToolchain.getGrepIncludes())
        }
        if (!getShouldScanIncludes() && dotdFile == null && !shouldParseShowIncludes()) {
            realMandatoryInputsBuilder.addTransitive(ccCompilationContext.getDeclaredIncludeSrcs())
            realMandatoryInputsBuilder.addTransitive(additionalPrunableHeaders)
        }
        if (modmapFile != null) {
            realMandatoryInputsBuilder.add(modmapFile)
                .add(com.google.common.base.Preconditions.checkNotNull<T?>(modmapInputFile))
        }
        return realMandatoryInputsBuilder.build()
    }

    val prunableHeaders: NestedSet<Artifact?>?
        get() = additionalPrunableHeaders

    val inputsForInvalidation: NestedSet<Artifact?>?
        get() = ccCompilationContext.getDeclaredIncludeSrcs()

    private fun useHeaderModules(sourceFile: Artifact? = this.sourceFile): Boolean {
        com.google.common.base.Preconditions.checkNotNull<FeatureConfiguration?>(featureConfiguration)
        com.google.common.base.Preconditions.checkNotNull<Any?>(sourceFile)
        return featureConfiguration.isEnabled(CppRuleClasses.USE_HEADER_MODULES)
                && (sourceFile.isFileType(CppFileTypes.CPP_SOURCE)
                || sourceFile.isFileType(CppFileTypes.CPP_HEADER)
                || sourceFile.isFileType(CppFileTypes.CPP_MODULE_MAP))
    }

    /**
     * Set action name that is used to pick the right action_config and features from [ ]. By default the action name is decided from the source filetype.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setActionName(actionName: String?): CppCompileActionBuilder {
        com.google.common.base.Preconditions.checkState(
            this.actionName == null,
            "New actionName %s trying to overwrite old name %s",
            actionName,
            this.actionName
        )
        this.actionName = actionName
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setProgressMessagePrefix(progressMessagePrefix: String?): CppCompileActionBuilder {
        this.progressMessagePrefix = progressMessagePrefix
        return this
    }

    /** Sets the feature configuration to be used for the action.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setFeatureConfiguration(
        featureConfiguration: FeatureConfiguration
    ): CppCompileActionBuilder {
        com.google.common.base.Preconditions.checkNotNull<FeatureConfiguration?>(featureConfiguration)
        this.featureConfiguration = featureConfiguration
        return this
    }

    fun getFeatureConfiguration(): FeatureConfiguration {
        return featureConfiguration
    }

    /** Sets the feature build variables to be used for the action.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setVariables(variables: CcToolchainVariables?): CppCompileActionBuilder {
        this.variables = variables
        return this
    }

    /** Returns the build variables to be used for the action.  */
    fun getVariables(): CcToolchainVariables? {
        return variables
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addExecutionInfo(executionInfo: MutableMap<String?, String?>?): CppCompileActionBuilder {
        this.executionInfo.putAll(executionInfo!!)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addMandatoryInputs(artifacts: NestedSet<Artifact?>?): CppCompileActionBuilder {
        mandatoryInputsBuilder.addTransitive(artifacts)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addMandatoryInputs(artifacts: MutableList<Artifact?>?): CppCompileActionBuilder {
        mandatoryInputsBuilder.addAll(artifacts)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addTransitiveMandatoryInputs(artifacts: NestedSet<Artifact?>?): CppCompileActionBuilder {
        mandatoryInputsBuilder.addTransitive(artifacts)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addAdditionalIncludeScanningRoots(
        additionalIncludeScanningRoots: MutableList<Artifact?>?
    ): CppCompileActionBuilder {
        this.additionalIncludeScanningRoots.addAll(additionalIncludeScanningRoots)
        return this
    }

    fun useDotdFile(sourceFile: Artifact): Boolean {
        return CppFileTypes.headerDiscoveryRequired(sourceFile) && !useHeaderModules(sourceFile)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setOutputs(
        outputFile: Artifact?, dotdFile: Artifact?, diagnosticsFile: Artifact?
    ): CppCompileActionBuilder {
        this.outputFile = outputFile
        this.dotdFile = dotdFile
        this.diagnosticsFile = diagnosticsFile
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setDwoFile(dwoFile: Artifact?): CppCompileActionBuilder {
        this.dwoFile = dwoFile
        return this
    }

    /**
     * Set the minimized bitcode file emitted by this (ThinLTO) compilation that can be used in place
     * of the full bitcode outputFile in the LTO indexing step.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setLtoIndexingFile(ltoIndexingFile: Artifact?): CppCompileActionBuilder {
        this.ltoIndexingFile = ltoIndexingFile
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setGcnoFile(gcnoFile: Artifact?): CppCompileActionBuilder {
        this.gcnoFile = gcnoFile
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCcCompilationContext(
        ccCompilationContext: CcCompilationContext?
    ): CppCompileActionBuilder {
        this.ccCompilationContext = ccCompilationContext
        return this
    }

    /** Sets whether the CompileAction should use pic mode.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setPicMode(usePic: Boolean): CppCompileActionBuilder {
        this.usePic = usePic
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setShareable(shareable: Boolean): CppCompileActionBuilder {
        this.shareable = shareable
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setShouldScanIncludes(shouldScanIncludes: Boolean): CppCompileActionBuilder {
        this.shouldScanIncludes = shouldScanIncludes
        return this
    }

    fun getShouldScanIncludes(): Boolean {
        if (shouldScanIncludes != null) {
            return shouldScanIncludes!!
        }

        val moduleDepsScanningAction =
            actionName != null && actionName == CppActionNames.CPP_MODULE_DEPS_SCANNING

        // Three things have to be true to perform #include scanning:
        //  1. The toolchain configuration has to generally enable it.
        //     This is the default unless the --nocc_include_scanning flag was specified.
        //  2. The action is not CPP_MODULE_DEPS_SCANNING.
        //  3. The rule or package must not disable it via 'features = ["-cc_include_scanning"]'.
        //     Normally the scanner is enabled, but rules with precisely specified
        //     dependencies not understood by the scanner can selectively disable it.
        //  4. The file must not be a not-for-preprocessing assembler source file.
        //     Assembler without C preprocessing can use the '.include' pseudo-op which is not
        //     understood by the include scanner, so we'll disable scanning, and instead require
        //     the declared sources to state (possibly overapproximate) the dependencies.
        //     Assembler with preprocessing can also use '.include', but supporting both kinds
        //     of inclusion for that use-case is ridiculous.
        shouldScanIncludes =
            configuration.getFragment(CppConfiguration::class.java).shouldScanIncludes()
                    && featureConfiguration.getRequestedFeatures().contains("cc_include_scanning")
                    && !moduleDepsScanningAction && !sourceFile.isFileType(CppFileTypes.ASSEMBLER) && !sourceFile.isFileType(
                CppFileTypes.CPP_MODULE
            )
        return shouldScanIncludes!!
    }

    val toolchain: CcToolchainProvider
        get() = ccToolchain

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCoptsFilter(coptsFilter: CoptsFilter?): CppCompileActionBuilder {
        this.coptsFilter = com.google.common.base.Preconditions.checkNotNull<CoptsFilter?>(coptsFilter)
        return this
    }

    fun getCoptsFilter(): CoptsFilter? {
        return coptsFilter
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setBuildInfoHeaderArtifacts(
        buildInfoHeaderArtifacts: com.google.common.collect.ImmutableList<Artifact?>
    ): CppCompileActionBuilder {
        this.buildInfoHeaderArtifacts = buildInfoHeaderArtifacts
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCacheKeyInputs(cacheKeyInputs: NestedSet<Artifact?>?): CppCompileActionBuilder {
        this.cacheKeyInputs = cacheKeyInputs
        return this
    }

    val actionEnvironment: ActionEnvironment
        get() = configuration.getActionEnvironment()

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setAdditionalPrunableHeaders(
        additionalPrunableHeaders: NestedSet<Artifact?>?
    ): CppCompileActionBuilder {
        this.additionalPrunableHeaders =
            com.google.common.base.Preconditions.checkNotNull<NestedSet<Artifact?>?>(additionalPrunableHeaders)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setModmapFile(modmapFile: Artifact?): CppCompileActionBuilder {
        this.modmapFile = modmapFile
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setModmapInputFile(modmapInputFile: Artifact?): CppCompileActionBuilder {
        this.modmapInputFile = modmapInputFile
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setModuleFiles(moduleFiles: NestedSet<Artifact?>?): CppCompileActionBuilder {
        this.moduleFiles = moduleFiles
        return this
    }

    @get:Throws(net.starlark.java.eval.EvalException::class)
    val builtinIncludeDirectories: com.google.common.collect.ImmutableList<PathFragment?>?
        get() = ccToolchain.getBuiltInIncludeDirectories()

    fun shouldCompileHeaders(): Boolean {
        com.google.common.base.Preconditions.checkNotNull<FeatureConfiguration?>(featureConfiguration)
        return CcToolchainProvider.Companion.shouldProcessHeaders(featureConfiguration, cppConfiguration)
    }

    companion object {
        fun getActionOwner(
            actionConstructionContext: ActionConstructionContext, cppToolchainType: String?
        ): ActionOwner? {
            var actionOwner: ActionOwner? = null
            if (actionConstructionContext is RuleContext
                && actionConstructionContext.useAutoExecGroups()
            ) {
                actionOwner =
                    actionConstructionContext.getActionOwner(
                        Label.parseCanonicalUnchecked(cppToolchainType).toString()
                    )
            }
            return if (actionOwner == null) actionConstructionContext.getActionOwner() else actionOwner
        }
    }
}
