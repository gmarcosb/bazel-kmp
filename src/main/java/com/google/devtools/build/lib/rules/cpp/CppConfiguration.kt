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

/**
 * This class represents the C/C++ parts of the [BuildConfigurationValue], including the exec
 * architecture, target architecture, compiler version, and a standard library version.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@RequiresOptions(options = [CppOptions::class])
class CppConfiguration(options: BuildOptions) : Fragment(), CppConfigurationApi<InvalidConfigurationException?> {
    /** An enumeration of all the tools that comprise a toolchain.  */
    enum class Tool(namePart: String) {
        AR("ar"),
        CPP("cpp"),
        GCC("gcc"),
        GCOV("gcov"),
        GCOVTOOL("gcov-tool"),
        LD("ld"),
        LLVM_COV("llvm-cov"),
        NM("nm"),
        OBJCOPY("objcopy"),
        OBJDUMP("objdump"),
        STRIP("strip"),
        DWP("dwp"),
        LLVM_PROFDATA("llvm-profdata");

        val namePart: String?

        init {
            this.namePart = namePart
        }
    }

    /**
     * Values for the --hdrs_check option. Note that Bazel only supports and will default to "strict".
     */
    enum class HeadersCheckingMode {
        /**
         * Legacy behavior: Silently allow any source header file in any of the directories of the
         * containing package to be included by sources in this rule and dependent rules.
         */
        LOOSE,

        /** Disallow undeclared headers.  */
        STRICT;

        companion object {
            fun getValue(value: String): HeadersCheckingMode {
                if (value.equalsIgnoreCase("loose") || value.equalsIgnoreCase("warn")) {
                    return HeadersCheckingMode.LOOSE
                }
                if (value.equalsIgnoreCase("strict")) {
                    return HeadersCheckingMode.STRICT
                }
                throw java.lang.IllegalArgumentException()
            }
        }
    }

    /**
     * --dynamic_mode parses to DynamicModeFlag, but AUTO will be translated based on platform,
     * resulting in a DynamicMode value.
     */
    enum class DynamicMode {
        OFF,
        DEFAULT,
        FULLY
    }

    /** This enumeration is used for the --strip option.  */
    enum class StripMode(mode: String) {
        ALWAYS("always"),  // Always strip.
        SOMETIMES("sometimes"),  // Strip iff compilationMode == FASTBUILD.
        NEVER("never"); // Never strip.

        private val mode: String?

        init {
            this.mode = mode
        }

        override fun toString(): String {
            return mode!!
        }
    }

    private val fdoPath: String?
    private val fdoOptimizeLabel: Label?

    private val csFdoAbsolutePath: String?
    private val propellerOptimizeAbsoluteCCProfile: String?
    private val propellerOptimizeAbsoluteLdProfile: String?

    private val conlyopts: com.google.common.collect.ImmutableList<String?>

    private val copts: com.google.common.collect.ImmutableList<String?>
    private val cxxopts: com.google.common.collect.ImmutableList<String?>
    private val objcopts: com.google.common.collect.ImmutableList<String?>

    private val linkopts: com.google.common.collect.ImmutableList<String?>
    private val ltoindexOptions: com.google.common.collect.ImmutableList<String?>
    private val ltobackendOptions: com.google.common.collect.ImmutableList<String?>

    private val cppOptions: CppOptions

    // The dynamic mode for linking.
    private val stripBinaries: Boolean
    private val compilationMode: CompilationMode
    private val collectCodeCoverage: Boolean

    private val appleGenerateDsym: Boolean

    init {
        val cppOptions: CppOptions = options.get(CppOptions::class.java)

        val commonOptions: CoreOptions = options.get(CoreOptions::class.java)
        val compilationMode: CompilationMode = commonOptions.getCompilationMode()

        val linkoptsBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        linkoptsBuilder.addAll(cppOptions.getLinkoptList())
        if (cppOptions.getExperimentalOmitfp()) {
            linkoptsBuilder.add("-Wl,--eh-frame-hdr")
        }

        val fdoPathData = FdoPathData.Companion.get(cppOptions)

        var csFdoAbsolutePath: PathFragment? = null
        if (cppOptions.getCsFdoAbsolutePathForBuild() != null) {
            if (!cppOptions.getEnableFdoProfileAbsolutePath()) {
                throw InvalidConfigurationException(
                    ("Please use --cs_fdo_optimize instead of an absolute path set with"
                            + " --cs_fdo_absolute_path.Using absolute paths may be temporary reenabled with"
                            + " --enable_fdo_profile_absolute_path")
                )
            }
            csFdoAbsolutePath = PathFragment.create(cppOptions.getCsFdoAbsolutePathForBuild())
            if (!csFdoAbsolutePath.isAbsolute()) {
                throw InvalidConfigurationException(
                    ("Path of '"
                            + csFdoAbsolutePath.getPathString()
                            + "' in --cs_fdo_absolute_path is not an absolute path.")
                )
            }
            try {
                com.google.devtools.build.lib.vfs.FileSystemUtils.checkBaseName(csFdoAbsolutePath.getBaseName())
            } catch (e: java.lang.IllegalArgumentException) {
                throw InvalidConfigurationException(e)
            }
        }

        var propellerOptimizeAbsoluteCCProfile: PathFragment? = null
        if (cppOptions.getPropellerOptimizeAbsoluteCCProfile() != null) {
            if (!cppOptions.getEnablePropellerOptimizeAbsolutePath()) {
                throw InvalidConfigurationException(
                    ("Please use --propeller_optimize instead of an absolute path set with"
                            + " --propeller_optimize_absolute_cc_profile. Using absolute paths may be temporary"
                            + " reenabled with --enable_propeller_optimize_absolute_paths")
                )
            }
            propellerOptimizeAbsoluteCCProfile =
                PathFragment.create(cppOptions.getPropellerOptimizeAbsoluteCCProfile())
            if (!propellerOptimizeAbsoluteCCProfile.isAbsolute()) {
                throw InvalidConfigurationException(
                    ("Path of '"
                            + propellerOptimizeAbsoluteCCProfile.getPathString()
                            + "' in --propeller_optimize_absolute_cc_profile is not an absolute path.")
                )
            }
            try {
                com.google.devtools.build.lib.vfs.FileSystemUtils.checkBaseName(propellerOptimizeAbsoluteCCProfile.getBaseName())
            } catch (e: java.lang.IllegalArgumentException) {
                throw InvalidConfigurationException(e)
            }
        }

        var propellerOptimizeAbsoluteLdProfile: PathFragment? = null
        if (cppOptions.getPropellerOptimizeAbsoluteLdProfile() != null) {
            if (!cppOptions.getEnablePropellerOptimizeAbsolutePath()) {
                throw InvalidConfigurationException(
                    ("Please use --propeller_optimize instead of an absolute path set with"
                            + " --propeller_optimize_absolute_ld_profile. Using absolute paths may be temporary"
                            + " reenabled with --enable_fdo_profile_absolute_path")
                )
            }
            propellerOptimizeAbsoluteLdProfile =
                PathFragment.create(cppOptions.getPropellerOptimizeAbsoluteLdProfile())
            if (!propellerOptimizeAbsoluteLdProfile.isAbsolute()) {
                throw InvalidConfigurationException(
                    ("Path of '"
                            + propellerOptimizeAbsoluteLdProfile.getPathString()
                            + "' in --propeller_optimize_absolute_ld_profile is not an absolute path.")
                )
            }
            try {
                com.google.devtools.build.lib.vfs.FileSystemUtils.checkBaseName(propellerOptimizeAbsoluteLdProfile.getBaseName())
            } catch (e: java.lang.IllegalArgumentException) {
                throw InvalidConfigurationException(e)
            }
        }

        this.fdoPath = if (fdoPathData.fdoPath == null) null else fdoPathData.fdoPath.getPathString()
        this.fdoOptimizeLabel = fdoPathData.fdoProfileLabel
        this.csFdoAbsolutePath = if (csFdoAbsolutePath == null) null else csFdoAbsolutePath.getPathString()
        this.propellerOptimizeAbsoluteCCProfile =
            if (propellerOptimizeAbsoluteCCProfile == null)
                null
            else
                propellerOptimizeAbsoluteCCProfile.getPathString()
        this.propellerOptimizeAbsoluteLdProfile =
            if (propellerOptimizeAbsoluteLdProfile == null)
                null
            else
                propellerOptimizeAbsoluteLdProfile.getPathString()
        this.conlyopts = com.google.common.collect.ImmutableList.copyOf<String?>(cppOptions.getConlyoptList())
        this.copts = com.google.common.collect.ImmutableList.copyOf<String?>(cppOptions.getCoptList())
        this.cxxopts = com.google.common.collect.ImmutableList.copyOf<String?>(cppOptions.getCxxoptList())
        this.objcopts = com.google.common.collect.ImmutableList.copyOf<String?>(cppOptions.getObjcoptList())
        this.linkopts = linkoptsBuilder.build()
        this.ltoindexOptions = com.google.common.collect.ImmutableList.copyOf<String?>(cppOptions.getLtoindexoptList())
        this.ltobackendOptions =
            com.google.common.collect.ImmutableList.copyOf<String?>(cppOptions.getLtobackendoptList())
        this.cppOptions = cppOptions
        this.stripBinaries =
            cppOptions.getStripBinaries() == StripMode.ALWAYS
                    || (cppOptions.getStripBinaries() == StripMode.SOMETIMES
                    && compilationMode === CompilationMode.FASTBUILD)
        this.compilationMode = compilationMode
        this.collectCodeCoverage = commonOptions.getCollectCodeCoverage()
        this.appleGenerateDsym = cppOptions.getAppleGenerateDsym()
    }

    private class FdoPathData(fdoPath: PathFragment?, fdoProfileLabel: Label?) {
        val fdoPath: PathFragment?
        val fdoProfileLabel: Label?

        init {
            this.fdoPath = fdoPath
            this.fdoProfileLabel = fdoProfileLabel
        }

        companion object {
            @Throws(InvalidConfigurationException::class)
            private fun get(cppOptions: CppOptions): FdoPathData {
                var fdoPath: PathFragment? = null
                var fdoProfileLabel: Label? = null

                if (cppOptions.getFdoOptimize() != null) {
                    try {
                        fdoProfileLabel = Label.parseCanonical(cppOptions.getFdoOptimize())
                        return FdoPathData(fdoPath, fdoProfileLabel)
                    } catch (ignored: LabelSyntaxException) {
                        // This isn't a Label, so just continue trying other flags.
                    }

                    if (!cppOptions.getEnableFdoProfileAbsolutePath()) {
                        throw InvalidConfigurationException(
                            ("Please use --fdo_profile instead of an absolute path set with --fdo_optimize. Using"
                                    + " absolute paths may be temporary reenabled with"
                                    + " --enable_fdo_profile_absolute_path")
                        )
                    }

                    // Try to process the flag value as a path.
                    fdoPath = PathFragment.create(cppOptions.getFdoOptimize())
                    if (!fdoPath.isAbsolute()) {
                        throw InvalidConfigurationException(
                            ("Path of '"
                                    + fdoPath.getPathString()
                                    + "' in --fdo_optimize has to be either an absolute path or a label.")
                        )
                    }
                    try {
                        // We don't check for file existence, but at least the filename should be well-formed.
                        com.google.devtools.build.lib.vfs.FileSystemUtils.checkBaseName(fdoPath.getBaseName())
                    } catch (e: java.lang.IllegalArgumentException) {
                        throw InvalidConfigurationException(e)
                    }
                }
                return FdoPathData(fdoPath, fdoProfileLabel)
            }
        }
    }

    @get:StarlarkConfigurationField(name = "zipper", doc = "The zipper label for FDO.")
    val fdoZipper: Label?
        get() {
            if (getFdoOptimizeLabel() != null || this.fdoProfileLabel != null || fdoPath != null || this.memProfProfileLabel != null || this.xFdoProfileLabel != null) {
                return Label.parseCanonicalUnchecked(BAZEL_TOOLS_REPO + "//tools/zip:unzip_fdo")
            }
            return null
        }

    /** Returns the configured current compilation mode.  */
    fun getCompilationMode(): CompilationMode {
        return compilationMode
    }

    @net.starlark.java.annot.StarlarkMethod(name = "compilation_mode", useStarlarkThread = true, documented = false)
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getCompilationModeForStarlark(thread: net.starlark.java.eval.StarlarkThread?): String {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return compilationMode.toString()
    }

    @net.starlark.java.annot.StarlarkMethod(name = "lto_index_options", documented = false, useStarlarkThread = true)
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getLtoIndexOptionsForStarlark(thread: net.starlark.java.eval.StarlarkThread?): com.google.common.collect.ImmutableList<String?> {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return ltoindexOptions
    }

    @get:net.starlark.java.annot.StarlarkMethod(name = "lto_backend_options", documented = false, structField = true)
    val ltoBackendOptions: com.google.common.collect.ImmutableList<String?>
        /** Returns the set of command-line LTO backend options.  */
        get() = ltobackendOptions

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "minimum_os_version",
        doc = "The minimum OS version for C/C++ compilation.",
        allowReturnNones = true
    )
    val minimumOsVersion: String?
        get() = cppOptions.getMinimumOsVersion()

    val dynamicModeFlag: DynamicMode?
        /** Returns the value of the --dynamic_mode flag.  */
        get() = cppOptions.getDynamicMode()

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "dynamic_mode",
        doc = "Whether C/C++ binaries/tests were requested to be linked dynamically."
    )
    val dynamicModeFlagString: String?
        get() = cppOptions.getDynamicMode().name()

    fun useArgsParamsFile(): Boolean {
        return cppOptions.getUseArgsParamsFile()
    }

    /** Returns whether or not to strip the binaries.  */
    fun shouldStripBinaries(): Boolean {
        return stripBinaries
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun shouldStripBinariesForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return stripBinaries
    }

    val stripOpts: com.google.common.collect.ImmutableList<String?>
        /**
         * Returns the additional options to pass to strip when generating a `<name>.stripped`
         * binary by this build.
         */
        get() = com.google.common.collect.ImmutableList.copyOf<String?>(cppOptions.getStripoptList())

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getStripOptsStarlark(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.Sequence<String?>? {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(this.stripOpts)
    }

    val saveTemps: Boolean
        /** Returns whether temporary outputs from gcc will be saved.  */
        get() = cppOptions.getSaveTemps()

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getSaveTempsForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return this.saveTemps
    }

    val perFileCopts: com.google.common.collect.ImmutableList<PerLabelOptions?>
        /**
         * Returns the [PerLabelOptions] to apply to the gcc command line, if the label of the
         * compiled file matches the regular expression.
         */
        get() = com.google.common.collect.ImmutableList.copyOf<PerLabelOptions?>(cppOptions.getPerFileCopts())

    val perFileLtoBackendOpts: com.google.common.collect.ImmutableList<PerLabelOptions?>
        /**
         * Returns the [PerLabelOptions] to apply to the LTO Backend command line, if the compiled
         * object matches the regular expression.
         */
        get() = com.google.common.collect.ImmutableList.copyOf<PerLabelOptions?>(cppOptions.getPerFileLtoBackendOpts())

    /** Returns the custom malloc library label.  */
    @StarlarkConfigurationField(name = "custom_malloc", doc = "The label specified in --custom_malloc")
    override fun customMalloc(): Label? {
        return cppOptions.getCustomMalloc()
    }

    /** Returns whether we are processing headers in dependencies of built C++ targets.  */
    fun processHeadersInDependencies(): Boolean {
        return cppOptions.getProcessHeadersInDependencies()
    }

    /** Returns true if --fission contains the current compilation mode.  */
    fun fissionIsActiveForCurrentCompilationMode(): Boolean {
        return cppOptions.getFissionModes().contains(compilationMode)
    }

    /** Returns true if --build_test_dwp is set on this build.  */
    fun buildTestDwpIsActivated(): Boolean {
        return cppOptions.getBuildTestDwp()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun buildTestDwpIsActivatedStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return buildTestDwpIsActivated()
    }

    /**
     * Returns true if all C++ compilations should produce position-independent code, links should
     * produce position-independent executables, and dependencies with equivalent pre-built pic and
     * nopic versions should apply the pic versions. Returns false if default settings should be
     * applied (i.e. make no special provisions for pic code).
     */
    fun forcePic(): Boolean {
        return cppOptions.getForcePic()
    }

    /** Returns true if --start_end_lib is set on this build.  */
    fun startEndLibIsRequested(): Boolean {
        return cppOptions.getUseStartEndLib()
    }

    @net.starlark.java.annot.StarlarkMethod(name = "start_end_lib", documented = false, useStarlarkThread = true)
    @Throws(net.starlark.java.eval.EvalException::class)
    fun startEndLibIsRequestedForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return cppOptions.getUseStartEndLib()
    }

    fun experimentalLinkStaticLibrariesOnce(): Boolean {
        return cppOptions.getExperimentalLinkStaticLibrariesOnce()
    }

    @net.starlark.java.annot.StarlarkMethod(name = "legacy_whole_archive", documented = false, useStarlarkThread = true)
    @Throws(net.starlark.java.eval.EvalException::class)
    fun legacyWholeArchiveForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return cppOptions.getLegacyWholeArchive()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "incompatible_remove_legacy_whole_archive",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun removeLegacyWholeArchiveForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return cppOptions.getRemoveLegacyWholeArchive()
    }

    val inmemoryDotdFiles: Boolean
        get() = cppOptions.getInmemoryDotdFiles()

    val useInterfaceSharedLibraries: Boolean
        get() = cppOptions.getUseInterfaceSharedObjects()

    @net.starlark.java.annot.StarlarkMethod(
        name = "interface_shared_objects",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getUseInterfaceSharedLibrariesforStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return cppOptions.getUseInterfaceSharedObjects()
    }

    val isOmitfp: Boolean
        /** Returns whether this configuration will use libunwind for stack unwinding.  */
        get() = cppOptions.getExperimentalOmitfp()

    /** Returns flags passed to Bazel by --copt option.  */
    override fun getCopts(): com.google.common.collect.ImmutableList<String?> {
        if (this.isOmitfp) {
            return com.google.common.collect.ImmutableList.builder<String?>()
                .add("-fomit-frame-pointer")
                .add("-fasynchronous-unwind-tables")
                .add("-DNO_FRAME_POINTER")
                .addAll(copts)
                .build()
        }
        return copts
    }

    /** Returns flags passed to Bazel by --cxxopt option.  */
    override fun getCxxopts(): com.google.common.collect.ImmutableList<String?> {
        return cxxopts
    }

    /** Returns flags passed to Bazel by --conlyopt option.  */
    override fun getConlyopts(): com.google.common.collect.ImmutableList<String?> {
        return conlyopts
    }

    /** Returns flags passed to Bazel by --objccopt option.  */
    override fun getObjcopts(): com.google.common.collect.ImmutableList<String?> {
        return objcopts
    }

    /** Returns flags passed to Bazel by --linkopt option.  */
    override fun getLinkopts(): com.google.common.collect.ImmutableList<String?> {
        return linkopts
    }

    public override fun reportInvalidOptions(
        reporter: com.google.devtools.build.lib.events.EventHandler,
        buildOptions: BuildOptions
    ) {
        val cppOptions: CppOptions = buildOptions.get(CppOptions::class.java)
        if (stripBinaries) {
            var warn: Boolean = cppOptions.getCoptList().contains("-g")
            for (opt in cppOptions.getPerFileCopts()) {
                warn = warn or opt.options.contains("-g")
            }
            if (warn) {
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        ("Stripping enabled, but '--copt=-g' (or --per_file_copt=...@-g) specified. "
                                + "Debug information will be generated and then stripped away. This is "
                                + "probably not what you want! Use '-c dbg' for debug mode, or use "
                                + "'--strip=never' to disable stripping")
                    )
                )
            }
        }

        // FDO
        if (cppOptions.getFdoOptimize() != null && cppOptions.getFdoProfileLabel() != null) {
            reporter.handle(com.google.devtools.build.lib.events.Event.error("Both --fdo_optimize and --fdo_profile specified"))
        }

        if (cppOptions.getFdoInstrumentForBuild() != null) {
            if (cppOptions.getFdoOptimize() != null || cppOptions.getFdoProfileLabel() != null) {
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        "Cannot instrument and optimize for FDO at the same time. Remove one of the "
                                + "'--fdo_instrument' and '--fdo_optimize/--fdo_profile' options"
                    )
                )
            }
            if (!cppOptions.getCoptList().contains("-Wno-error")) {
                // This is effectively impossible. --fdo_instrument adds this value, and only invocation
                // policy could remove it.
                reporter.handle(com.google.devtools.build.lib.events.Event.error("Cannot instrument FDO without --copt including -Wno-error."))
            }
        }

        // This is an assertion check vs. user error because users can't trigger this state.
        // TODO(b/253313672): uncomment the below and check tests don't fail. This was originally set
        // check the exec configuration doesn't apply FDO settings. With the host configuration gone
        // we should migrate this check to the exec config. Since there's a chance of breakage it's best
        // to test this as its own dedicated change.
        // Verify.verify(
        //   !(buildOptions.get(CoreOptions.class).isExec && cppOptions.isFdo()),
        // "FDO state should not propagate to the exec configuration");
    }

    @Throws(Fragment.OutputDirectoriesContext.AddToMnemonicException::class)
    public override fun processForOutputPathMnemonic(ctx: OutputDirectoriesContext) {
        ctx.markAsExplicitInOutputPathFor("cc_output_directory_tag")
        if (!cppOptions.getOutputDirectoryTag().isEmpty()) {
            ctx.addToMnemonic(cppOptions.getOutputDirectoryTag())
        }
    }

    /** Returns true if we should share identical native libraries between different targets.  */
    fun shareNativeDeps(): Boolean {
        return cppOptions.getShareNativeDeps()
    }

    val isStrictSystemIncludes: Boolean
        get() = cppOptions.getStrictSystemIncludes()

    val fdoInstrument: String?
        get() = cppOptions.getFdoInstrumentForBuild()

    @net.starlark.java.annot.StarlarkMethod(
        name = "fdo_path",
        documented = false,
        useStarlarkThread = true,
        allowReturnNones = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getFdoPathForStarlark(thread: net.starlark.java.eval.StarlarkThread?): String? {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return if (fdoPath == null) null else fdoPath.toString()
    }

    @StarlarkConfigurationField(name = "fdo_optimize", doc = "The label specified in --fdo_optimize")
    fun getFdoOptimizeLabel(): Label? {
        return fdoOptimizeLabel
    }

    val cSFdoInstrument: String?
        get() = cppOptions.getCsFdoInstrumentForBuild()

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun csFdoInstrumentStarlark(thread: net.starlark.java.eval.StarlarkThread?): String? {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return this.cSFdoInstrument
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "cs_fdo_path",
        documented = false,
        useStarlarkThread = true,
        allowReturnNones = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getCsFdoPathForStarlark(thread: net.starlark.java.eval.StarlarkThread?): String? {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return csFdoAbsolutePath
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "propeller_optimize_absolute_cc_profile",
        documented = false,
        useStarlarkThread = true,
        allowReturnNones = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getPropellerOptimizeAbsoluteCcProfileForStarlark(thread: net.starlark.java.eval.StarlarkThread?): String? {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return propellerOptimizeAbsoluteCCProfile
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "propeller_optimize_absolute_ld_profile",
        documented = false,
        allowReturnNones = true,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getPropellerOptimizeAbsoluteLdProfileForStarlark(thread: net.starlark.java.eval.StarlarkThread?): String? {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return propellerOptimizeAbsoluteLdProfile
    }

    @get:net.starlark.java.annot.StarlarkMethod(
        name = "_fdo_prefetch_hints_label",
        documented = false,
        allowReturnNones = true,
        structField = true
    )
    @get:StarlarkConfigurationField(name = "fdo_prefetch_hints", doc = "The label specified in --fdo_prefetch_hints")
    val fdoPrefetchHintsLabel: Label?
        get() = cppOptions.getFdoPrefetchHintsLabel()

    @get:StarlarkConfigurationField(name = "fdo_profile", doc = "The label specified in --fdo_profile")
    val fdoProfileLabel: Label?
        get() = cppOptions.getFdoProfileLabel()

    @get:StarlarkConfigurationField(name = "cs_fdo_profile", doc = "The label specified in --cs_fdo_profile")
    val cSFdoProfileLabel: Label?
        get() = cppOptions.getCsFdoProfileLabel()

    @get:StarlarkConfigurationField(name = "propeller_optimize", doc = "The label specified in --propeller_optimize")
    val propellerOptimizeLabel: Label?
        get() {
            if (cppOptions.getFdoInstrumentForBuild() != null
                || cppOptions.getCsFdoInstrumentForBuild() != null
            ) {
                return null
            }
            return cppOptions.getPropellerOptimizeLabel()
        }

    @get:StarlarkConfigurationField(name = "xbinary_fdo", doc = "The label specified in --xbinary_fdo")
    val xFdoProfileLabel: Label?
        get() {
            if (cppOptions.getFdoOptimizeForBuild() != null || cppOptions.getFdoInstrumentForBuild() != null || cppOptions.getFdoProfileLabel() != null || collectCodeCoverage) {
                return null
            }

            return cppOptions.getXfdoProfileLabel()
        }

    @get:StarlarkConfigurationField(name = "memprof_profile", doc = "The memprof profile label for cc_toolchain rule")
    val memProfProfileLabel: Label?
        get() = cppOptions.getMemProfProfileLabel()

    fun useLLVMCoverageMapFormat(): Boolean {
        return cppOptions.getUseLLVMCoverageMapFormat()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "use_llvm_coverage_map_format",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun useLlvmCoverageMapFormatStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return useLLVMCoverageMapFormat()
    }

    @get:StarlarkConfigurationField(name = "libc_top", doc = "The libc_top label for cc_toolchain.")
    val libcTopLabel: Label?
        /**
         * Returns the value of the libc top-level directory (--grte_top) as specified on the command line
         */
        get() = cppOptions.getLibcTopLabel()

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getLibcTopLabelStarlark(thread: net.starlark.java.eval.StarlarkThread?): Label? {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return this.libcTopLabel
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun shareNativeDepsStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return shareNativeDeps()
    }

    @net.starlark.java.annot.StarlarkMethod(name = "_dont_enable_host_nonhost", documented = false, structField = true)
    fun dontEnableHostNonhost(): Boolean {
        return cppOptions.getDontEnableHostNonhost()
    }

    fun collectCodeCoverage(): Boolean {
        return collectCodeCoverage
    }

    fun saveFeatureState(): Boolean {
        return cppOptions.getSaveFeatureState()
    }

    fun useSpecificToolFiles(): Boolean {
        return cppOptions.getUseSpecificToolFiles()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "incompatible_use_specific_tool_files",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun useSpecificToolFilesForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return cppOptions.getUseSpecificToolFiles()
    }

    fun disableNoCopts(): Boolean {
        return cppOptions.getDisableNoCopts()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun disableNocoptsStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return disableNoCopts()
    }

    override fun appleGenerateDsym(): Boolean {
        return appleGenerateDsym
    }

    fun useCppCompileHeaderMnemonic(): Boolean {
        return cppOptions.getUseCppCompileHeaderMnemonic()
    }

    fun generateLlvmLCov(): Boolean {
        return cppOptions.getGenerateLlvmLcov()
    }

    /** Returns true iff we should do "include scanning" during this build.  */
    fun shouldScanIncludes(): Boolean {
        return cppOptions.getIncludeScanning() || cppOptions.getIncludeScanningInternal()
    }

    @net.starlark.java.annot.StarlarkMethod(name = "include_scanning", documented = false, useStarlarkThread = true)
    @Throws(net.starlark.java.eval.EvalException::class)
    fun shouldScanIncludesForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return shouldScanIncludes()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "should_generate_dotd_files",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun shouldGenerateDotdFilesStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return cppOptions.getGenerateDotdFiles()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "objc_should_generate_dotd_files",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun objcShouldGenerateDotdFilesStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return cppOptions.getObjcGenerateDotdFiles()
    }

    override fun objcGenerateLinkmap(): Boolean {
        return cppOptions.getObjcGenerateLinkmap()
    }

    fun objcEnableBinaryStripping(): Boolean {
        return cppOptions.getObjcEnableBinaryStripping()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "objc_enable_binary_stripping",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun objcEnableBinaryStrippingForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return cppOptions.getObjcEnableBinaryStripping()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "experimental_cc_implementation_deps",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun experimentalCcImplementationDepsForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return experimentalCcImplementationDeps()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "experimental_cpp_modules",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun experimentalCppModulesForStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return experimentalCppModules()
    }

    fun experimentalCcImplementationDeps(): Boolean {
        return cppOptions.getExperimentalCcImplementationDeps()
    }

    fun experimentalCppModules(): Boolean {
        return cppOptions.getExperimentalCppModules()
    }

    val experimentalCppCompileResourcesEstimation: Boolean
        get() = cppOptions.getExperimentalCppCompileResourcesEstimation()

    override fun macosSetInstallName(): Boolean {
        return true
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun forcePicStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        checkInExpandedApiAllowlist(thread, "force_pic")
        return forcePic()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun generateLlvmLcovStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        checkInExpandedApiAllowlist(thread, "generate_llvm_lcov")
        return generateLlvmLCov()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun fdoInstrumentStarlark(thread: net.starlark.java.eval.StarlarkThread?): String? {
        checkInExpandedApiAllowlist(thread, "fdo_instrument")
        return this.fdoInstrument
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun processHeadersInDependenciesStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return processHeadersInDependencies()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun saveFeatureStateStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return saveFeatureState()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun fissionActiveForCurrentCompilationModeStarlark(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return fissionIsActiveForCurrentCompilationMode()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getExperimentalLinkStaticLibrariesOnce(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return experimentalLinkStaticLibrariesOnce()
    }

    override fun objcShouldStripBinary(): Boolean {
        return objcEnableBinaryStripping() && getCompilationMode() === CompilationMode.OPT
    }

    @get:StarlarkConfigurationField(name = "proto_profile_path")
    val protoProfilePath: Label?
        get() = cppOptions.getProtoProfilePath()

    @net.starlark.java.annot.StarlarkMethod(name = "proto_profile", useStarlarkThread = true, documented = false)
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getProtoProfile(thread: net.starlark.java.eval.StarlarkThread?): Boolean {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return cppOptions.getProtoProfile()
    }

    companion object {
        private const val BAZEL_TOOLS_REPO = "@bazel_tools"

        /** String constant for CC_FLAGS make variable name  */
        const val CC_FLAGS_MAKE_VARIABLE_NAME: String = "CC_FLAGS"

        /**
         * This macro will be passed as a command-line parameter (eg. -DBUILD_FDO_TYPE="AUTOFDO"). For
         * possible values see `CppModel.getFdoBuildStamp()`.
         */
        const val FDO_STAMP_MACRO: String = "BUILD_FDO_TYPE"

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkInExpandedApiAllowlist(thread: net.starlark.java.eval.StarlarkThread?, feature: String?) {
            try {
                BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
            } catch (e: net.starlark.java.eval.EvalException) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "%s (feature '%s' in CppConfiguration)",
                    e.getMessage(),
                    feature
                )
            }
        }
    }
}
