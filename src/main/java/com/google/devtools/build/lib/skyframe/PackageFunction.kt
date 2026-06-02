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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileValue

/**
 * A SkyFunction which computes a [PackageValue] if given a [PackageIdentifier], or a
 * [PackagePieceValue.ForBuildFile] if given a [PackagePieceIdentifier.ForBuildFile].
 */
abstract class PackageFunction protected constructor(
    packageFactory: PackageFactory?,
    pkgLocator: CachingPackageLocator?,
    showLoadingProgress: AtomicBoolean,
    numPackagesSuccessfullyLoaded: AtomicInteger,
    bzlLoadFunctionForInlining: BzlLoadFunction?,
    packageProgress: PackageProgressReceiver?,
    actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile,
    actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile,
    shouldUseRepoDotBazel: Boolean,
    threadStateReceiverFactoryForMetrics: java.util.function.Function<SkyKey?, ThreadStateReceiver?>,
    cpuBoundSemaphore: AtomicReference<Semaphore?>
) : SkyFunction {
    protected val packageFactory: PackageFactory?
    protected val packageLocator: CachingPackageLocator?
    private val showLoadingProgress: AtomicBoolean
    private val numPackagesSuccessfullyLoaded: AtomicInteger
    private val packageProgress: PackageProgressReceiver?

    // Not final only for testing.
    private var bzlLoadFunctionForInlining: BzlLoadFunction?

    private val actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile

    private val actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile

    private val shouldUseRepoDotBazel: Boolean

    protected val threadStateReceiverFactoryForMetrics: java.util.function.Function<SkyKey?, ThreadStateReceiver?>

    private val cpuBoundSemaphore: AtomicReference<Semaphore?>

    /**
     * CompiledBuildFile holds information extracted from the BUILD syntax tree before it was
     * discarded, such as the compiled program, its glob literals, and its mapping from each function
     * call site to its `generator_name` attribute value.
     */
    // TODO(adonovan): when we split PackageCompileFunction out, move this there, and make it
    // non-public. (Since CompiledBuildFile contains a Module (the prelude), when we split it out,
    // the code path that requests it will have to support inlining a la BzlLoadFunction.)
    class CompiledBuildFile {
        // Either errors is null, or all the other fields are.
        private val errors: com.google.common.collect.ImmutableList<net.starlark.java.syntax.SyntaxError>?
        private val prog: net.starlark.java.syntax.Program?
        private val globs: com.google.common.collect.ImmutableList<String?>?
        private val globsWithDirs: com.google.common.collect.ImmutableList<String?>?
        private val subpackages: com.google.common.collect.ImmutableList<String?>?
        private val generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?
        private val predeclared: com.google.common.collect.ImmutableMap<String?, Any?>?

        fun ok(): Boolean {
            return prog != null
        }

        // success
        internal constructor(
            prog: net.starlark.java.syntax.Program?,
            globs: com.google.common.collect.ImmutableList<String?>?,
            globsWithDirs: com.google.common.collect.ImmutableList<String?>?,
            subpackages: com.google.common.collect.ImmutableList<String?>?,
            generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?,
            predeclared: com.google.common.collect.ImmutableMap<String?, Any?>?
        ) {
            this.errors = null
            this.prog = prog
            this.globs = globs
            this.subpackages = subpackages
            this.globsWithDirs = globsWithDirs
            this.generatorMap = generatorMap
            this.predeclared = predeclared
        }

        // failure
        internal constructor(errors: MutableList<net.starlark.java.syntax.SyntaxError?>) {
            this.errors = com.google.common.collect.ImmutableList.copyOf<net.starlark.java.syntax.SyntaxError?>(errors)
            this.prog = null
            this.globs = null
            this.globsWithDirs = null
            this.subpackages = null
            this.generatorMap = null
            this.predeclared = null
        }
    }

    init {
        this.bzlLoadFunctionForInlining = bzlLoadFunctionForInlining
        this.packageFactory = packageFactory
        this.packageLocator = pkgLocator
        this.showLoadingProgress = showLoadingProgress
        this.numPackagesSuccessfullyLoaded = numPackagesSuccessfullyLoaded
        this.packageProgress = packageProgress
        this.actionOnIOExceptionReadingBuildFile = actionOnIOExceptionReadingBuildFile
        this.actionOnFilesystemErrorCodeLoadingBzlFile = actionOnFilesystemErrorCodeLoadingBzlFile
        this.shouldUseRepoDotBazel = shouldUseRepoDotBazel
        this.threadStateReceiverFactoryForMetrics = threadStateReceiverFactoryForMetrics
        this.cpuBoundSemaphore = cpuBoundSemaphore
    }

    fun setBzlLoadFunctionForInliningForTesting(bzlLoadFunctionForInlining: BzlLoadFunction?) {
        this.bzlLoadFunctionForInlining = bzlLoadFunctionForInlining
    }

    /**
     * What to do when encountering an [IOException] trying to read the contents of a BUILD
     * file.
     * 
     * 
     * Any choice besides [ ][ActionOnIOExceptionReadingBuildFile.UseOriginalIOException.INSTANCE] is potentially
     * incrementally unsound: if the initial [IOException] is transient, then Blaze will
     * "incorrectly" not attempt to redo package loading for this BUILD file on incremental builds.
     * 
     * 
     * The fact that this behavior is configurable and potentially unsound is a concession to
     * certain desired use cases with fancy filesystems.
     */
    interface ActionOnIOExceptionReadingBuildFile {
        /**
         * Given the [IOException] encountered when reading the contents of the given BUILD file,
         * returns the contents that should be used, or `null` if the original [IOException]
         * should be respected (that is, we should error-out with a package loading error).
         */
        fun maybeGetBuildFileContentsToUse(
            buildFilePathFragment: PathFragment?, originalExn: IOException?
        ): ByteArray?

        /**
         * A [ActionOnIOExceptionReadingBuildFile] whose [.maybeGetBuildFileContentsToUse]
         * has the sensible behavior of always respecting the initial [IOException].
         */
        class UseOriginalIOException private constructor() : ActionOnIOExceptionReadingBuildFile {
            override fun maybeGetBuildFileContentsToUse(
                buildFilePathFragment: PathFragment?, originalExn: IOException?
            ): ByteArray? {
                return null
            }

            companion object {
                val INSTANCE: UseOriginalIOException = UseOriginalIOException()
            }
        }
    }

    /**
     * What to do when encountering a [Filesystem] error code while trying to load a bzl file.
     * 
     * 
     * This class should decide whether the Filesystem error code takes precedence over the
     * PackageLoading error code.
     */
    interface ActionOnFilesystemErrorCodeLoadingBzlFile {
        fun shouldTakePrecedenceOverPackageLoadingCode(filesystemCode: Filesystem.Code?): Boolean

        companion object {
            /** By default, always use the PackageLoading error code.  */
            val ALWAYS_USE_PACKAGE_LOADING_CODE: ActionOnFilesystemErrorCodeLoadingBzlFile =
                ActionOnFilesystemErrorCodeLoadingBzlFile { filesystemCode: Filesystem.Code? -> false }
        }
    }

    /** Ways that [PackageFunction] can perform globbing.  */
    enum class GlobbingStrategy {
        /**
         * Globs are resolved using `PackageFunctionWithMultipleGlobDeps#SkyframeHybridGlobber`,
         * which declares proper Skyframe dependencies.
         * 
         * 
         * This strategy is formerly named `SKYFRAME_HYBRID`.
         * 
         * 
         * Use when [PackageFunction] will be used to load packages incrementally (e.g. on both
         * clean builds and incremental builds, perhaps with cached globs). This used to be Bazel's
         * normal use-case and still is the preferred strategy if incremental evaluation performance
         * requirement is strict.
         */
        MULTIPLE_GLOB_HYBRID,

        /**
         * Globs are resolved using `PackageFunctionWithSingleGlobsDep#GlobsGlobber`. This
         * strategy is similar to [.MULTIPLE_GLOB_HYBRID] except that there is a single GLOBS
         * Skyframe dependency including all globs defined in the package's BUILD file.
         * 
         * 
         * The `GLOBS` strategy is designed to replace `SKYFRAME_HYBRID` as Bazel's
         * normal use case in that it coarsens the Glob-land subgraph and saving memory without
         * meaningfully sacrificing performance.
         * 
         * 
         * However, incremental evaluation performance might regress when switching from [ ][.MULTIPLE_GLOB_HYBRID] to [.SINGLE_GLOBS_HYBRID]. See [GlobFunction] for more
         * details.
         */
        SINGLE_GLOBS_HYBRID,

        /**
         * Globs are resolved using [NonSkyframeGlobber], which does not declare Skyframe
         * dependencies.
         * 
         * 
         * This is a performance optimization only for use when [PackageFunction] will never be
         * used to load packages incrementally. Do not use this unless you know what you are doing;
         * Bazel will be intentionally incrementally incorrect!
         */
        NON_SKYFRAME
    }

    /**
     * Queries GLOB deps in Skyframe if necessary, and handles package's glob deps symlink issues
     * discovered by Skyframe globbing.
     */
    @com.google.errorprone.annotations.ForOverride
    @Throws(
        InternalInconsistentFilesystemException::class,
        FileSymlinkException::class,
        java.lang.InterruptedException::class
    )
    protected abstract fun handleGlobDepsAndPropagateFilesystemExceptions(
        packageIdentifier: PackageIdentifier?,
        packageRoot: Root?,
        loadedPackage: LoadedPackage?,
        env: SkyFunction.Environment?,
        packageWasInError: Boolean
    )

    /**
     * Stores information needed to load the package. Subclasses are expected to provide different
     * types of containers which store glob deps information.
     */
    protected abstract class LoadedPackage internal constructor(builder: Package.AbstractBuilder, metrics: Metrics?) {
        val builder: Package.AbstractBuilder
        val metrics: Metrics?

        init {
            this.builder = builder
            this.metrics = metrics
        }
    }

    private class State : SkyKeyComputeState {
        private var compiledBuildFile: CompiledBuildFile? = null
        private var loadedPackage: LoadedPackage? = null
    }

    /**
     * @param key either a [PackageIdentifier] or a [PackagePieceIdentifier.ForBuildFile];
     * cannot be a [PackagePieceIdentifier.ForMacro].
     * @return a [PackageValue] if given a [PackageIdentifier], or a [     ] if given a [PackagePieceIdentifier.ForBuildFile].
     */
    @Throws(PackageFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(key: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val packageId: PackageIdentifier
        val packagePieceId: PackagePieceIdentifier.ForBuildFile?
        if (key.argument() is PackageIdentifier) {
            packagePieceId = null
            packageId = id
        } else {
            packagePieceId = key.argument() as PackagePieceIdentifier.ForBuildFile?
            packageId = packagePieceId.getPackageIdentifier()
        }
        if (packageId.equals(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)) {
            throw PackageFunctionException.Companion.builder()
                .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.NO_SUCH_PACKAGE)
                .setTransience(Transience.PERSISTENT)
                .setPackageIdentifier(LabelConstants.EXTERNAL_PACKAGE_IDENTIFIER)
                .setMessage(
                    "//external package is not available since the WORKSPACE file is deprecated, please"
                            + " migrate to Bzlmod. See https://bazel.build/external/migration#bind-targets."
                )
                .setPackageLoadingCode(PackageLoading.Code.WORKSPACE_FILE_ERROR)
                .build()
        }

        if (packagePieceId == null
            && PrecomputedValue.LAZY_MACRO_EXPANSION_PACKAGES.get(env).contains(packageId)
        ) {
            try {
                return computePackageFromPackagePieces(packageId, env)
            } catch (e: NoSuchPackageException) {
                throw PackageFunctionException(e, Transience.PERSISTENT)
            } catch (e: NoSuchPackagePieceException) {
                throw PackageFunctionException(
                    NoSuchPackageException(
                        packageId,
                        java.lang.String.format(
                            "cannot compute package %s: %s", packageId.getCanonicalForm(), e.getMessage()
                        ),
                        e,
                        e.getDetailedExitCode()
                    ),
                    Transience.PERSISTENT
                )
            } catch (e: NoSuchMacroInstanceException) {
                throw PackageFunctionException(
                    NoSuchPackageException(
                        packageId,
                        java.lang.String.format(
                            "cannot compute package %s: %s", packageId.getCanonicalForm(), e.getMessage()
                        ),
                        e,
                        e.getDetailedExitCode()
                    ),
                    Transience.PERSISTENT
                )
            }
        }

        val packageLookupKey: SkyKey? = PackageLookupValue.key(packageId)
        val packageLookupValue: PackageLookupValue?
        try {
            packageLookupValue =
                env.getValueOrThrow<E1?, E2?, E3?, E4?>(
                    packageLookupKey,
                    BadRepoFileException::class.java,
                    InvalidIgnorePathException::class.java,
                    BuildFileNotFoundException::class.java,
                    InconsistentFilesystemException::class.java
                ) as PackageLookupValue?
        } catch (e: InvalidIgnorePathException) {
            throw PackageFunctionException.Companion.builder()
                .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.NO_SUCH_PACKAGE)
                .setTransience(Transience.PERSISTENT)
                .setPackageIdentifier(packageId)
                .setMessage(e.getMessage())
                .setException(e)
                .setPackageLoadingCode(PackageLoading.Code.BAD_IGNORED_DIRECTORIES)
                .build()
        } catch (e: BadRepoFileException) {
            throw badRepoFileException(e, packageId)
        } catch (e: BuildFileNotFoundException) {
            throw PackageFunctionException(e, Transience.PERSISTENT)
        } catch (e: InconsistentFilesystemException) {
            // This error is not transient from the perspective of the PackageFunction.
            throw PackageFunctionException.Companion.builder()
                .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.NO_SUCH_PACKAGE)
                .setTransience(Transience.PERSISTENT)
                .setPackageIdentifier(packageId)
                .setMessage(e.getMessage())
                .setException(e)
                .setPackageLoadingCode(PackageLoading.Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR)
                .build()
        }
        if (packageLookupValue == null) {
            return null
        }

        if (!packageLookupValue.packageExists()) {
            val exceptionBuilder =
                PackageFunctionException.Companion.builder()
                    .setPackageIdentifier(packageId)
                    .setTransience(Transience.PERSISTENT)
            when (packageLookupValue.errorReason) {
                NO_BUILD_FILE -> {
                    val message: String? = PackageLookupFunction.explainNoBuildFileValue(packageId, env)
                    throw exceptionBuilder
                        .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.BUILD_FILE_NOT_FOUND)
                        .setMessage(message)
                        .setPackageLoadingCode(PackageLoading.Code.BUILD_FILE_MISSING)
                        .build()
                }

                DELETED_PACKAGE, REPOSITORY_NOT_FOUND -> throw exceptionBuilder
                    .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.BUILD_FILE_NOT_FOUND)
                    .setMessage(packageLookupValue.errorMsg)
                    .setPackageLoadingCode(PackageLoading.Code.REPOSITORY_MISSING)
                    .build()

                INVALID_PACKAGE_NAME -> throw exceptionBuilder
                    .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.INVALID_PACKAGE_NAME)
                    .setMessage(packageLookupValue.errorMsg)
                    .setPackageLoadingCode(PackageLoading.Code.INVALID_NAME)
                    .build()
            }
            // We should never get here.
            throw java.lang.IllegalStateException()
        }

        val starlarkBuiltinsValue: StarlarkBuiltinsValue?
        try {
            if (bzlLoadFunctionForInlining == null) {
                starlarkBuiltinsValue =
                    env.getValueOrThrow<BuiltinsFailedException?>(
                        StarlarkBuiltinsValue.Companion.key(),
                        BuiltinsFailedException::class.java
                    ) as StarlarkBuiltinsValue?
            } else {
                starlarkBuiltinsValue =
                    StarlarkBuiltinsFunction.Companion.computeInline(
                        StarlarkBuiltinsValue.Companion.key(),
                        InliningState.Companion.create(env),
                        packageFactory.getRuleClassProvider().getBazelStarlarkEnvironment(),
                        bzlLoadFunctionForInlining
                    )
            }
        } catch (e: BuiltinsFailedException) {
            throw PackageFunctionException.Companion.builder()
                .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
                .setPackageIdentifier(packageId)
                .setTransience(Transience.PERSISTENT)
                .setMessage(
                    java.lang.String.format("Internal error while loading Starlark builtins: %s", e.getMessage())
                )
                .setPackageLoadingCode(PackageLoading.Code.BUILTINS_INJECTION_FAILURE)
                .build()
        }

        if (env.valuesMissing()) {
            return null
        }

        // TODO(adonovan): put BUILD compilation from BUILD execution in separate Skyframe functions
        // like we do for .bzl files, so that we don't need to recompile BUILD files each time their
        // .bzl dependencies change.
        val state: State =
            env.getState<State>(java.util.function.Supplier { com.google.devtools.build.lib.skyframe.PackageFunction.State() })
        if (state.loadedPackage == null) {
            state.loadedPackage =
                loadPackage(
                    packageLookupValue,
                    packagePieceId,
                    packageId,
                    starlarkBuiltinsValue,
                    packageLookupValue.root,
                    env,
                    key,
                    state
                )
            if (state.loadedPackage == null) {
                return null
            }
        }
        var pfeFromNonSkyframeGlobbing: PackageFunctionException? = null
        val pkgBuilder: Package.AbstractBuilder = state.loadedPackage!!.builder
        try {
            pkgBuilder.buildPartial()
            // Since the Skyframe dependencies we request below in
            // handleGlobDepsAndPropagateFilesystemExceptions are requested independently of the ones
            // requested here in
            // handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions, we don't
            // bother checking for missing values and instead piggyback on the env.missingValues() call
            // for the former. This avoids a Skyframe restart.
            // Note that handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions
            // expects to mutate pkgBuilder.getTargets(), and thus can only be safely called if
            // pkgBuilder.buildPartial() didn't throw.
            handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions(
                packageLookupValue.root, packageId, pkgBuilder, env
            )
        } catch (e: NoSuchPackageException) {
            // If non-Skyframe globbing encounters an IOException, #buildPartial will throw a
            // NoSuchPackageException. If that happens, we prefer throwing an exception derived from
            // Skyframe globbing. See the comments in #handleGlobDepsAndPropagateFilesystemExceptions.
            // Therefore we store the exception encountered here and maybe use it later.
            val transience: Transience =
                when (e.getCause()) {
                    -> detailed.getTransience()
                    -> Transience.PERSISTENT
                    null -> Transience.TRANSIENT
                }
            pfeFromNonSkyframeGlobbing = PackageFunctionException(e, transience)
        } catch (e: InternalInconsistentFilesystemException) {
            throw e.throwPackageFunctionException()
        }

        try {
            handleGlobDepsAndPropagateFilesystemExceptions(
                packageId,
                packageLookupValue.root,
                state.loadedPackage,
                env,
                pkgBuilder.containsErrors()
            )
        } catch (e: InternalInconsistentFilesystemException) {
            throw e.throwPackageFunctionException()
        } catch (e: FileSymlinkException) {
            val message = "Symlink issue while evaluating globs: " + e.getUserFriendlyMessage()
            throw PackageFunctionException.Companion.builder()
                .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.NO_SUCH_PACKAGE)
                .setTransience(Transience.PERSISTENT)
                .setPackageIdentifier(packageId)
                .setMessage(message)
                .setPackageLoadingCode(PackageLoading.Code.EVAL_GLOBS_SYMLINK_ERROR)
                .build()
        }

        if (pfeFromNonSkyframeGlobbing != null) {
            // Throw before checking for missing values, since this may be our last chance to throw if in
            // nokeep-going error bubbling.
            throw pfeFromNonSkyframeGlobbing
        }

        if (env.valuesMissing()) {
            return null
        }

        val packageoid: Packageoid = pkgBuilder.finishBuild()

        pkgBuilder.getLocalEventHandler().replayOn(env.getListener())

        if (packageoid is Package) {
            try {
                packageFactory.afterDoneLoadingPackage(
                    packageoid,
                    starlarkBuiltinsValue.starlarkSemantics,
                    PrecomputedValue.LAZY_MACRO_EXPANSION_PACKAGES.get(env),
                    state.loadedPackage!!.metrics,
                    env.getListener()
                )
            } catch (e: InvalidPackageException) {
                throw PackageFunctionException(e, Transience.PERSISTENT)
            }
        } else {
            try {
                packageFactory.afterDoneLoadingPackagePiece(
                    packageoid as PackagePiece.ForBuildFile?,
                    starlarkBuiltinsValue.starlarkSemantics,
                    state.loadedPackage!!.metrics,
                    env.getListener()
                )
            } catch (e: InvalidPackagePieceException) {
                throw PackageFunctionException(e, Transience.PERSISTENT)
            }
        }

        if (!packageoid.containsErrors()) {
            // TODO(https://github.com/bazelbuild/bazel/issues/23852): here we are counting a successfully
            // loaded PackagePiece.ForBuildFile as a successfully loaded package for metric purposes. And
            // we *don't* count packages from computePackageFromPackagePieces() since that would result
            // in double-counting - a Package from pieces necessarily requires a PackagePiece.ForBuildFile
            // to have been loaded.
            // We could also avoid double-counting by tracking 3 different successful loading metrics:
            // * monolithic packages
            // * full packages from pieces
            // * PackagePiece.ForBuildFile-s
            // but it's not clear if the complexity would be worthwhile.
            numPackagesSuccessfullyLoaded.incrementAndGet()
        }
        if (packageoid is Package) {
            return PackageValue(packageoid)
        } else if (packageoid is PackagePiece.ForBuildFile) {
            return ForBuildFile(
                packageoid, starlarkBuiltinsValue.starlarkSemantics, pkgBuilder.getMainRepoMapping()
            )
        } else {
            throw java.lang.IllegalStateException("Unexpected packageoid type: " + packageoid.getClass())
        }
    }

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun makeGlobber(
        nonSkyframeGlobber: NonSkyframeGlobber?,
        packageId: PackageIdentifier?,
        packageRoot: Root?,
        env: SkyFunction.Environment?
    ): Globber?

    /**
     * Constructs a [Package] or `PackagePiece.ForBuildFile` object for the given package.
     * Note that the returned package or piece may be in error.
     * 
     * 
     * May return null if the computation has to be restarted.
     * 
     * @param packagePieceId the identifier of the `PackagePiece.ForBuildFile` if we are loading
     * a package piece, or null if we are loading a full [Package].
     */
    @Throws(java.lang.InterruptedException::class, PackageFunctionException::class)
    private fun loadPackage(
        packageLookupValue: PackageLookupValue,
        packagePieceId: PackagePieceIdentifier.ForBuildFile?,
        packageId: PackageIdentifier,
        starlarkBuiltinsValue: StarlarkBuiltinsValue,
        packageRoot: Root?,
        env: SkyFunction.Environment,
        keyForMetrics: SkyKey?,
        state: State
    ): LoadedPackage? {
        val repositoryMappingValue: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.key(packageId.getRepository())) as RepositoryMappingValue?
        val mainRepositoryMappingValue: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.key(RepositoryName.MAIN)) as RepositoryMappingValue?
        val buildFileRootedPath: RootedPath = packageLookupValue.getRootedPath(packageId)
        val buildFileValue: FileValue? = getBuildFileValue(env, buildFileRootedPath)
        val defaultVisibility: RuleVisibility? = PrecomputedValue.DEFAULT_VISIBILITY.get(env)
        val configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy? =
            PrecomputedValue.CONFIG_SETTING_VISIBILITY_POLICY.get(env)
        val repositoryIgnoredSubdirectories: IgnoredSubdirectoriesValue? =
            env.getValue(IgnoredSubdirectoriesValue.key(packageId.getRepository())) as IgnoredSubdirectoriesValue?
        val repoPackageArgsValue: RepoPackageArgsValue?
        if (shouldUseRepoDotBazel) {
            try {
                repoPackageArgsValue =
                    env.getValueOrThrow<E1?, E2?>(
                        RepoPackageArgsFunction.key(packageId.getRepository()),
                        IOException::class.java,
                        BadRepoFileException::class.java
                    ) as RepoPackageArgsValue?
            } catch (e: IOException) {
                throw badRepoFileException(e, packageId)
            } catch (e: BadRepoFileException) {
                throw badRepoFileException(e, packageId)
            }
        } else {
            repoPackageArgsValue = RepoPackageArgsValue.EMPTY
        }

        if (env.valuesMissing()) {
            return null
        }

        val repositoryMapping: RepositoryMapping? = repositoryMappingValue.repositoryMapping()
        val mainRepositoryMapping: RepositoryMapping? = mainRepositoryMappingValue.repositoryMapping()
        var preludeLabel: Label? = null

        // Load (optional) prelude, which determines environment.
        var preludeBindings: com.google.common.collect.ImmutableMap<String?, Any?>? = null
        // Can be null in tests.
        if (packageFactory != null) {
            // Load the prelude from the same repository as the package being loaded.
            val rawPreludeLabel: Label? = packageFactory.getRuleClassProvider().getPreludeLabel()
            if (rawPreludeLabel != null) {
                val preludePackage: PackageIdentifier? =
                    PackageIdentifier.create(
                        packageId.getRepository(), rawPreludeLabel.getPackageFragment()
                    )
                preludeLabel = Label.createUnvalidated(preludePackage, rawPreludeLabel.name)
                val prelude: net.starlark.java.eval.Module?
                try {
                    prelude = loadPrelude(env, packageId, preludeLabel, bzlLoadFunctionForInlining)
                } catch (e: NoSuchPackageException) {
                    throw PackageFunctionException(e, Transience.PERSISTENT)
                }
                if (prelude == null) {
                    return null // skyframe restart
                }
                preludeBindings = prelude.getGlobals()
            }
        }

        // TODO(adonovan): opt: evaluate splitting this part out as a separate Skyframe
        // function (PackageCompileFunction, by analogy with BzlCompileFunction).
        // There's a tradeoff between the memory costs of unconditionally storing
        // the PackageCompileValue and the time savings of not having to recompute
        // it situationally, so it's not an obvious strict win.

        // vv ---- begin PackageCompileFunction ---- vv
        if (packageProgress != null) {
            packageProgress.startReadPackage(packageId)
        }
        var committed = false
        try {
            Profiler.instance().profile(ProfilerTask.CREATE_PACKAGE, packageId.toString()).use { c ->
                var compiled = state.compiledBuildFile
                if (compiled == null) {
                    if (showLoadingProgress.get()) {
                        env.getListener()
                            .handle(com.google.devtools.build.lib.events.Event.progress("Loading package: " + packageId))
                    }
                    compiled =
                        compileBuildFile(
                            packageId,
                            buildFileRootedPath,
                            buildFileValue,
                            starlarkBuiltinsValue,
                            preludeBindings,
                            env.getListener()
                        )
                    state.compiledBuildFile = compiled
                }

                // ^^ ---- end PackageCompileFunction ---- ^^
                var loadedModules: com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.Module?>? =
                    null
                if (compiled.ok()) {
                    // Parse the labels in the file's load statements.
                    val programLoads: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>?> =
                        BzlLoadFunction.Companion.getLoadsFromProgram(compiled.prog)
                    val loadLabels: com.google.common.collect.ImmutableList<Label?>? =
                        BzlLoadFunction.Companion.getLoadLabels(
                            env.getListener(),
                            programLoads,
                            packageId,
                            packageFactory.getRuleClassProvider()::isPackageUnderExperimental,
                            packageFactory.getRuleClassProvider()::isPackageUnderPrototypes,
                            packageFactory.getRuleClassProvider()::mayPackageDependOnPrototypes,
                            repositoryMapping,
                            starlarkBuiltinsValue.starlarkSemantics
                        )
                    if (loadLabels == null) {
                        throw PackageFunctionException.Companion.builder()
                            .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
                            .setPackageIdentifier(packageId)
                            .setTransience(Transience.PERSISTENT)
                            .setMessage("malformed load statements")
                            .setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
                            .build()
                    }

                    // Compute key for each label in loads.
                    val keys: com.google.common.collect.ImmutableList.Builder<BzlLoadValue.Key?> =
                        com.google.common.collect.ImmutableList.builderWithExpectedSize<BzlLoadValue.Key?>(loadLabels.size())
                    for (loadLabel in loadLabels) {
                        keys.add(BzlLoadValue.keyForBuild(loadLabel))
                    }

                    // Load .bzl modules in parallel.
                    val buildFileLabel: Label
                    try {
                        buildFileLabel =
                            Label.create(
                                packageId,
                                packageLookupValue.buildFileName.getFilenameFragment().getPathString()
                            )
                    } catch (e: LabelSyntaxException) {
                        throw java.lang.IllegalStateException("Failed to construct label representing BUILD file", e)
                    }
                    try {
                        loadedModules =
                            loadBzlModules(
                                env,
                                packageId,
                                "file " + buildFileLabel.getCanonicalForm(),
                                programLoads,
                                keys.build(),
                                starlarkBuiltinsValue.starlarkSemantics,
                                bzlLoadFunctionForInlining,
                                packageFactory.getRuleClassProvider()::isPackageUnderExperimental,
                                packageFactory.getRuleClassProvider()::isPackageUnderPrototypes,
                                actionOnFilesystemErrorCodeLoadingBzlFile
                            )
                    } catch (e: NoSuchPackageException) {
                        throw PackageFunctionException(e, Transience.PERSISTENT)
                    }
                    if (loadedModules == null) {
                        return null // skyframe restart
                    }
                }

                // From this point on, no matter whether the function returns
                // successfully or throws an exception, there will be no more
                // Skyframe restarts.
                committed = true

                val startTimeNanos: Long = com.google.devtools.build.lib.clock.BlazeClock.nanoTime()

                val nonSkyframeGlobber: NonSkyframeGlobber =
                    packageFactory.createNonSkyframeGlobber(
                        buildFileRootedPath.asPath().getParentDirectory(),
                        packageId,
                        repositoryIgnoredSubdirectories.asIgnoredSubdirectories(),
                        packageLocator,
                        threadStateReceiverFactoryForMetrics.apply(keyForMetrics)
                    )
                val globber: Globber? = makeGlobber(nonSkyframeGlobber, packageId, packageRoot, env)

                // Create the package,
                // even if it will be empty because we cannot attempt execution.
                val pkgBuilder: Package.AbstractBuilder =
                    if (packagePieceId == null)
                        packageFactory.newPackageBuilder(
                            packageId,
                            buildFileRootedPath,
                            repositoryMappingValue.associatedModuleName(),
                            repositoryMappingValue.associatedModuleVersion(),
                            starlarkBuiltinsValue.starlarkSemantics,
                            repositoryMapping,
                            mainRepositoryMapping,
                            cpuBoundSemaphore.get(),  /* (Nullable) */
                            compiled.generatorMap,
                            configSettingVisibilityPolicy,
                            globber
                        )
                    else
                        packageFactory.newPackagePieceForBuildFileBuilder(
                            packagePieceId,
                            buildFileRootedPath,
                            repositoryMappingValue.associatedModuleName(),
                            repositoryMappingValue.associatedModuleVersion(),
                            starlarkBuiltinsValue.starlarkSemantics,
                            repositoryMapping,
                            mainRepositoryMapping,
                            cpuBoundSemaphore.get(),  /* (Nullable) */
                            compiled.generatorMap,
                            configSettingVisibilityPolicy,
                            globber
                        )

                pkgBuilder.mergePackageArgsFrom(
                    PackageArgs.builder().setDefaultVisibility(defaultVisibility)
                )
                pkgBuilder.mergePackageArgsFrom(repoPackageArgsValue.getPackageArgs())

                if (compiled.ok()) {
                    packageFactory.executeBuildFile(
                        pkgBuilder,
                        compiled.prog,
                        compiled.globs,
                        compiled.globsWithDirs,
                        compiled.subpackages,
                        compiled.predeclared,
                        loadedModules,
                        starlarkBuiltinsValue.starlarkSemantics
                    )
                    // TODO: b/155396641 - Validate that transitive visibility groups are correctly declared and
                    // that this package is a member of all transitive visibility groups it declares, but
                    // probably not in this part of the code.
                    if (packagePieceId == null) {
                        try {
                            (pkgBuilder as Package.Builder)
                                .expandAllRemainingMacros(starlarkBuiltinsValue.starlarkSemantics)
                        } catch (ex: net.starlark.java.eval.EvalException) {
                            pkgBuilder
                                .getLocalEventHandler()
                                .handle(Package.error(null, ex.getMessageWithStack(), Code.STARLARK_EVAL_ERROR))
                            pkgBuilder.setContainsErrors()
                        }
                    }
                } else {
                    // Execution not attempted due to static errors.
                    for (err in compiled.errors) {
                        pkgBuilder
                            .getLocalEventHandler()
                            .handle(
                                Package.error(err.location(), err.message(), PackageLoading.Code.SYNTAX_ERROR)
                            )
                    }
                    pkgBuilder.setContainsErrors()
                }

                val loadTimeNanos: Long =
                    java.lang.Math.max(com.google.devtools.build.lib.clock.BlazeClock.nanoTime() - startTimeNanos, 0L)
                return newLoadedPackage(
                    pkgBuilder,
                    globber,
                    Metrics(loadTimeNanos, nonSkyframeGlobber.getGlobFilesystemOperationCost())
                )
            }
        } finally {
            if (committed) {
                // We're done executing the BUILD file. Therefore, we can discard the compiled BUILD file...
                state.compiledBuildFile = null
                if (packageProgress != null) {
                    // ... and also note that we're done.
                    packageProgress.doneReadPackage(packageId)
                }
            }
        }
    }

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun newLoadedPackage(
        packageBuilder: Package.AbstractBuilder?,
        globber: Globber?,
        metrics: PackageLoadingListener.Metrics?
    ): LoadedPackage?

    // Reads, parses, resolves, and compiles a BUILD file.
    // A read error is reported as PackageFunctionException.
    // A syntax error is reported by returning a CompiledBuildFile with errors.
    @Throws(PackageFunctionException::class)
    private fun compileBuildFile(
        packageId: PackageIdentifier?,
        buildFilePath: RootedPath,
        buildFileValue: FileValue,
        starlarkBuiltinsValue: StarlarkBuiltinsValue,
        preludeBindings: MutableMap<String?, Any?>?,
        reporter: ExtendedEventHandler?
    ): CompiledBuildFile {
        // Though it could be in principle, `cpuBoundSemaphore` is not held here as this method does
        // not show up in profiles as being significantly impacted by thrashing. It could be worth doing
        // so, in which case it should be released when reading the file below.
        val semantics: net.starlark.java.eval.StarlarkSemantics = starlarkBuiltinsValue.starlarkSemantics

        // read BUILD file
        val inputFile: com.google.devtools.build.lib.vfs.Path = buildFilePath.asPath()
        var buildFileBytes: ByteArray?
        try {
            buildFileBytes =
                if (buildFileValue.isSpecialFile())
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(inputFile)
                else
                    com.google.devtools.build.lib.vfs.FileSystemUtils.readWithKnownFileSize(
                        inputFile,
                        buildFileValue.getSize()
                    )
        } catch (e: IOException) {
            buildFileBytes =
                actionOnIOExceptionReadingBuildFile.maybeGetBuildFileContentsToUse(
                    inputFile.asFragment(), e
                )
            if (buildFileBytes == null) {
                // Note that we did the work that led to this IOException, so we should
                // conservatively report this error as transient.
                val builder =
                    PackageFunctionException.Companion.builder()
                        .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
                        .setTransience(Transience.TRANSIENT)
                        .setPackageIdentifier(packageId)
                        .setMessage(e.getMessage())
                        .setException(e)
                if (e is DetailedIOException
                    && e.getDetailedExitCode().getFailureDetail().hasFilesystem()
                    && actionOnFilesystemErrorCodeLoadingBzlFile.shouldTakePrecedenceOverPackageLoadingCode(
                        e
                            .getDetailedExitCode()
                            .getFailureDetail()
                            .getFilesystem()
                            .getCode()
                    )
                ) {
                    builder.setFilesystemCode(
                        e
                            .getDetailedExitCode()
                            .getFailureDetail()
                            .getFilesystem()
                            .getCode()
                    )
                } else {
                    builder.setPackageLoadingCode(PackageLoading.Code.BUILD_FILE_MISSING)
                }
                throw builder.build()
            }
            // If control flow reaches here, we're in territory that is deliberately unsound.
            // See the javadoc for ActionOnIOExceptionReadingBuildFile.
        }
        val handler: StoredEventHandler = StoredEventHandler()
        val input: net.starlark.java.syntax.ParserInput?
        try {
            input =
                com.google.devtools.build.lib.skyframe.StarlarkUtil.createParserInput(
                    buildFileBytes,
                    inputFile.toString(),
                    semantics.get<Utf8EnforcementMode?>(BuildLanguageOptions.INCOMPATIBLE_ENFORCE_STARLARK_UTF8),
                    handler
                )
        } catch (e: InvalidUtf8Exception) {
            handler.replayOn(reporter)
            throw PackageFunctionException.Companion.builder()
                .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
                .setPackageIdentifier(packageId)
                .setTransience(Transience.PERSISTENT)
                .setException(e)
                .setMessage("error reading " + inputFile.toString())
                .setPackageLoadingCode(PackageLoading.Code.STARLARK_EVAL_ERROR)
                .build()
        }
        handler.replayOn(reporter)

        // Options for processing BUILD files.
        val options: net.starlark.java.syntax.FileOptions? =
            net.starlark.java.syntax.FileOptions.builder()
                .requireLoadStatementsFirst(false) // For historical reasons, BUILD files are allowed to load a symbol
                // and then reassign it later. (It is unclear why this is necessary).
                // TODO(adonovan): remove this flag and make loads bind file-locally,
                // as in .bzl files. One can always use a renaming load statement.
                .loadBindsGlobally(true)
                .allowToplevelRebinding(true)
                .build()

        // parse
        val file: net.starlark.java.syntax.StarlarkFile = net.starlark.java.syntax.StarlarkFile.parse(input, options)
        if (!file.ok()) {
            return CompiledBuildFile(file.errors())
        }

        // Check syntax. Make a pass over the syntax tree to:
        // - reject forbidden BUILD syntax
        // - extract literal glob patterns for prefetching
        // - record the generator_name of each top-level macro call
        val globs: MutableSet<String?> = HashSet<String?>()
        val globsWithDirs: MutableSet<String?> = HashSet<String?>()
        val subpackages: MutableSet<String?> = HashSet<String?>()
        val generatorMap: MutableMap<net.starlark.java.syntax.Location?, String?> =
            HashMap<net.starlark.java.syntax.Location?, String?>()
        try {
            PackageFactory.checkBuildSyntax(file, globs, globsWithDirs, subpackages, generatorMap)
        } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
            return CompiledBuildFile(ex.errors())
        }

        // Construct static environment for resolution/compilation.
        // The Resolver.Module defines the set of accessible names
        // (plus special errors for flag-disabled ones), but it is
        // materialized as an ephemeral eval.Module such as will be
        // used later during execution; the two environments must match.
        // TODO(#11437): Remove conditional once disabling injection is no longer allowed.
        var predeclared: MutableMap<String?, Any?> =
            if (semantics.get<String?>(BuildLanguageOptions.EXPERIMENTAL_BUILTINS_BZL_PATH).isEmpty())
                packageFactory
                    .getRuleClassProvider()
                    .getBazelStarlarkEnvironment()
                    .getUninjectedBuildEnv()
            else
                starlarkBuiltinsValue.predeclaredForBuild
        if (preludeBindings != null) {
            predeclared = LinkedHashMap<String?, Any?>(predeclared)
            predeclared.putAll(preludeBindings)
        }
        val module: net.starlark.java.eval.Module =
            net.starlark.java.eval.Module.withPredeclared(semantics, predeclared)

        // Compile BUILD file.
        val prog: net.starlark.java.syntax.Program?
        try {
            prog = net.starlark.java.syntax.Program.compileFile(file, module)
        } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
            return CompiledBuildFile(ex.errors())
        }

        // success
        return CompiledBuildFile(
            prog,
            com.google.common.collect.ImmutableList.copyOf<String?>(globs),
            com.google.common.collect.ImmutableList.copyOf<String?>(globsWithDirs),
            com.google.common.collect.ImmutableList.copyOf<String?>(subpackages),
            com.google.common.collect.ImmutableMap.copyOf<net.starlark.java.syntax.Location?, String?>(generatorMap),
            com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(predeclared)
        )
    }

    @Throws(
        NoSuchPackageException::class,
        NoSuchPackagePieceException::class,
        NoSuchMacroInstanceException::class,
        java.lang.InterruptedException::class
    )
    private fun computePackageFromPackagePieces(
        packageId: PackageIdentifier?,
        env: SkyFunction.Environment
    ): PackageValue? {
        val nonFinalizerPackagePiecesValue: NonFinalizerPackagePiecesValue? =
            env.getValueOrThrow<E1?, E2?, E3?>(
                Key(packageId),
                NoSuchPackageException::class.java,
                NoSuchPackagePieceException::class.java,
                NoSuchMacroInstanceException::class.java
            ) as NonFinalizerPackagePiecesValue?
        if (nonFinalizerPackagePiecesValue == null) {
            return null
        }
        val buildFilePiece: PackagePiece.ForBuildFile =
            nonFinalizerPackagePiecesValue.getPackagePieceForBuildFile()
        val allPackagePieces: PackagePieces? =
            RecursiveExpander.Companion.expandFinalizers(nonFinalizerPackagePiecesValue, env)
        if (allPackagePieces == null) {
            return null
        }
        val configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy? =
            PrecomputedValue.CONFIG_SETTING_VISIBILITY_POLICY.get(env)

        val pkgBuilder: Package.Builder =
            packageFactory.newPackageFromPackagePiecesBuilder(
                buildFilePiece.getMetadata(),
                buildFilePiece.getDeclarations(),
                nonFinalizerPackagePiecesValue.starlarkSemantics(),
                nonFinalizerPackagePiecesValue.mainRepositoryMapping(),
                cpuBoundSemaphore.get(),  /* generatorMap= */
                null,
                configSettingVisibilityPolicy,  /* globber= */
                null,
                buildFilePiece.getBuildFile()
            )

        if (!allPackagePieces.errorKeys.isEmpty()) {
            // Error within one package piece. It was already reported as an event with stack trace by the
            // computation of the PackagePieceValue, so we don't need to repeat the stack trace - just a
            // brief summary.
            val errorKey: PackagePieceIdentifier? = allPackagePieces.errorKeys.getFirst()
            val errorPiece: PackagePiece = allPackagePieces.packagePieces.get(errorKey)
            handlePackagePieceDependencyError(pkgBuilder, "error in " + errorPiece.getShortDescription())
        } else if (nonFinalizerPackagePiecesValue.nameConflictBetweenPackagePiecesException() != null) {
            // Name conflict between non-finalizer package pieces. It was already reported as an event
            // with stack trace by the computation of the NonFinalizerPackagePiecesValue, so we don't need
            // to repeat the stack trace - just a brief summary.
            handlePackagePieceDependencyError(
                pkgBuilder,
                nonFinalizerPackagePiecesValue.nameConflictBetweenPackagePiecesException().getMessage()
            )
        } else {
            // TODO(https://github.com/bazelbuild/bazel/issues/23852): in the common case where there are
            // no errors and no finalizers, we should directly use the target and macro maps from the
            // NonFinalizerPackagePiecesValue rather than re-recording them.
            try {
                allPackagePieces.recordTargetsAndMacros(pkgBuilder)
            } catch (e: net.starlark.java.eval.EvalException) {
                // Previously unreported name conflict between a finalizer package piece and another package
                // piece.
                handlePackagePieceDependencyError(pkgBuilder, e.getMessageWithStack())
            }
        }

        val pkg: Package? = pkgBuilder.finishBuild()

        pkgBuilder.getLocalEventHandler().replayOn(env.getListener())

        packageFactory.afterDoneLoadingPackage(
            pkg,
            nonFinalizerPackagePiecesValue.starlarkSemantics(),
            PrecomputedValue.LAZY_MACRO_EXPANSION_PACKAGES.get(env),  // TODO(https://github.com/bazelbuild/bazel/issues/23852): compute sum of metrics from
            // package piece values.
            Metrics( /* loadTimeNanos= */0,  /* globFilesystemOperationCost= */0),
            env.getListener()
        )
        return PackageValue(pkg)
    }

    /** Builder class for [PackageFunction].  */
    class Builder {
        private var packageFactory: PackageFactory? = null
        private var pkgLocator: CachingPackageLocator? = null
        private var showLoadingProgress: AtomicBoolean? = AtomicBoolean(false)
        private var numPackagesSuccessfullyLoaded: AtomicInteger? = AtomicInteger(0)
        private var bzlLoadFunctionForInlining: BzlLoadFunction? = null
        private var packageProgress: PackageProgressReceiver? = null
        private var actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile? =
            UseOriginalIOException.Companion.INSTANCE
        private var actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile? =
            ActionOnFilesystemErrorCodeLoadingBzlFile.Companion.ALWAYS_USE_PACKAGE_LOADING_CODE
        private var shouldUseRepoDotBazel = true
        private var globbingStrategy = GlobbingStrategy.SINGLE_GLOBS_HYBRID
        private var threadStateReceiverFactoryForMetrics: java.util.function.Function<SkyKey?, ThreadStateReceiver?>? =
            java.util.function.Function { k: SkyKey? -> ThreadStateReceiver.NULL_INSTANCE }
        private var cpuBoundSemaphore: AtomicReference<Semaphore?>? = AtomicReference<Semaphore?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPackageFactory(packageFactory: PackageFactory?): Builder {
            this.packageFactory = packageFactory
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPackageLocator(pkgLocator: CachingPackageLocator?): Builder {
            this.pkgLocator = pkgLocator
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setShowLoadingProgress(showLoadingProgress: AtomicBoolean?): Builder {
            this.showLoadingProgress = showLoadingProgress
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNumPackagesSuccessfullyLoaded(numPackagesSuccessfullyLoaded: AtomicInteger?): Builder {
            this.numPackagesSuccessfullyLoaded = numPackagesSuccessfullyLoaded
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setBzlLoadFunctionForInlining(bzlLoadFunctionForInlining: BzlLoadFunction?): Builder {
            this.bzlLoadFunctionForInlining = bzlLoadFunctionForInlining
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPackageProgress(packageProgress: PackageProgressReceiver?): Builder {
            this.packageProgress = packageProgress
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionOnIOExceptionReadingBuildFile(
            actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile?
        ): Builder {
            this.actionOnIOExceptionReadingBuildFile = actionOnIOExceptionReadingBuildFile
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setActionOnFilesystemErrorCodeLoadingBzlFile(
            actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile?
        ): Builder {
            this.actionOnFilesystemErrorCodeLoadingBzlFile = actionOnFilesystemErrorCodeLoadingBzlFile
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setShouldUseRepoDotBazel(shouldUseRepoDotBazel: Boolean): Builder {
            this.shouldUseRepoDotBazel = shouldUseRepoDotBazel
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setGlobbingStrategy(globbingStrategy: GlobbingStrategy): Builder {
            this.globbingStrategy = globbingStrategy
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setThreadStateReceiverFactoryForMetrics(
            threadStateReceiverFactoryForMetrics: java.util.function.Function<SkyKey?, ThreadStateReceiver?>?
        ): Builder {
            this.threadStateReceiverFactoryForMetrics = threadStateReceiverFactoryForMetrics
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCpuBoundSemaphore(cpuBoundSemaphore: AtomicReference<Semaphore?>?): Builder {
            this.cpuBoundSemaphore = cpuBoundSemaphore
            return this
        }

        fun build(): PackageFunction {
            when (globbingStrategy) {
                GlobbingStrategy.MULTIPLE_GLOB_HYBRID -> {
                    return PackageFunctionWithMultipleGlobDeps(
                        packageFactory,
                        pkgLocator,
                        showLoadingProgress,
                        numPackagesSuccessfullyLoaded,
                        bzlLoadFunctionForInlining,
                        packageProgress,
                        actionOnIOExceptionReadingBuildFile,
                        actionOnFilesystemErrorCodeLoadingBzlFile,
                        shouldUseRepoDotBazel,
                        threadStateReceiverFactoryForMetrics,
                        cpuBoundSemaphore
                    )
                }

                GlobbingStrategy.SINGLE_GLOBS_HYBRID -> {
                    return PackageFunctionWithSingleGlobsDep(
                        packageFactory,
                        pkgLocator,
                        showLoadingProgress,
                        numPackagesSuccessfullyLoaded,
                        bzlLoadFunctionForInlining,
                        packageProgress,
                        actionOnIOExceptionReadingBuildFile,
                        actionOnFilesystemErrorCodeLoadingBzlFile,
                        shouldUseRepoDotBazel,
                        threadStateReceiverFactoryForMetrics,
                        cpuBoundSemaphore
                    )
                }

                GlobbingStrategy.NON_SKYFRAME -> {
                    return PackageFunctionWithoutGlobDeps(
                        packageFactory,
                        pkgLocator,
                        showLoadingProgress,
                        numPackagesSuccessfullyLoaded,
                        bzlLoadFunctionForInlining,
                        packageProgress,
                        actionOnIOExceptionReadingBuildFile,
                        actionOnFilesystemErrorCodeLoadingBzlFile,
                        shouldUseRepoDotBazel,
                        threadStateReceiverFactoryForMetrics,
                        cpuBoundSemaphore
                    )
                }
            }
            throw java.lang.IllegalStateException()
        }
    }

    /**
     * Wraps [InconsistentFilesystemException]. This is only internally used by [ ].
     */
    protected class InternalInconsistentFilesystemException(
        packageIdentifier: PackageIdentifier?,
        e: InconsistentFilesystemException
    ) : java.lang.Exception(e.getMessage(), e) {
        var isTransient: Boolean
            private set
        private val packageIdentifier: PackageIdentifier?

        /**
         * Used to represent a filesystem inconsistency discovered outside the [PackageFunction].
         */
        init {
            this.packageIdentifier = packageIdentifier
            // This is not a transient error from the perspective of the PackageFunction.
            this.isTransient = false
        }

        /** Used to represent a filesystem inconsistency discovered by the [PackageFunction].  */
        constructor(packageIdentifier: PackageIdentifier?, inconsistencyMessage: String?) : this(
            packageIdentifier,
            InconsistentFilesystemException(inconsistencyMessage)
        ) {
            this.isTransient = true
        }

        @Throws(PackageFunctionException::class)
        private fun throwPackageFunctionException(): PackageFunctionException? {
            throw PackageFunctionException.Companion.builder()
                .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.NO_SUCH_PACKAGE)
                .setPackageIdentifier(packageIdentifier)
                .setMessage(this.getMessage())
                .setException(this.getCause() as java.lang.Exception?)
                .setPackageLoadingCode(
                    if (this.isTransient)
                        Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR
                    else
                        Code.PERSISTENT_INCONSISTENT_FILESYSTEM_ERROR
                )
                .setTransience(if (this.isTransient) Transience.TRANSIENT else Transience.PERSISTENT)
                .build()
        }
    }

    /**
     * Used to declare all the exception types that can be wrapped in the exception thrown by [ ][PackageFunction.compute].
     */
    internal class PackageFunctionException : SkyFunctionException {
        constructor(e: NoSuchPackageException?, transience: Transience?) : super(e, transience)

        constructor(e: NoSuchPackagePieceException?, transience: Transience?) : super(e, transience)

        constructor(e: BadRepoFileException?, transience: Transience?) : super(e, transience)

        /**
         * An enum to help create the different types of [NoSuchPackageException]. PackageFunction
         * contains a myriad of different types of exceptions that extend NoSuchPackageException for
         * different scenarios.
         */
        internal enum class Type {
            BUILD_FILE_CONTAINS_ERRORS {
                override fun create(
                    packId: PackageIdentifier?,
                    msg: String?,
                    detailedExitCode: DetailedExitCode?,
                    e: java.lang.Exception?
                ): BuildFileContainsErrorsException? {
                    return if (e is IOException)
                        BuildFileContainsErrorsException(packId, msg, e, detailedExitCode)
                    else
                        BuildFileContainsErrorsException(packId, msg, detailedExitCode)
                }
            },
            BUILD_FILE_NOT_FOUND {
                override fun create(
                    packId: PackageIdentifier?,
                    msg: String?,
                    detailedExitCode: DetailedExitCode?,
                    e: java.lang.Exception?
                ): BuildFileNotFoundException? {
                    return BuildFileNotFoundException(packId, msg, detailedExitCode)
                }
            },
            INVALID_PACKAGE_NAME {
                override fun create(
                    packId: PackageIdentifier?,
                    msg: String?,
                    detailedExitCode: DetailedExitCode?,
                    e: java.lang.Exception?
                ): InvalidPackageNameException? {
                    return InvalidPackageNameException(packId, msg, detailedExitCode)
                }
            },
            NO_SUCH_PACKAGE {
                override fun create(
                    packId: PackageIdentifier?,
                    msg: String?,
                    detailedExitCode: DetailedExitCode?,
                    e: java.lang.Exception?
                ): NoSuchPackageException? {
                    return if (e != null)
                        NoSuchPackageException(packId, msg, e, detailedExitCode)
                    else
                        NoSuchPackageException(packId, msg, detailedExitCode)
                }
            };

            abstract fun create(
                packId: PackageIdentifier?, msg: String?, detailedExitCode: DetailedExitCode?, e: java.lang.Exception?
            ): NoSuchPackageException?
        }

        /**
         * The builder class for [PackageFunctionException] and its [NoSuchPackageException]
         * cause.
         */
        internal class Builder {
            private var exceptionType: Type? = null
            private var packageIdentifier: PackageIdentifier? = null
            private var transience: Transience? = null
            private var exception: java.lang.Exception? = null
            private var message: String? = null
            private var packageLoadingCode: PackageLoading.Code? = null
            private var filesystemCode: Filesystem.Code? = null

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setType(exceptionType: Type): Builder {
                this.exceptionType = exceptionType
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setPackageIdentifier(packageIdentifier: PackageIdentifier?): Builder {
                this.packageIdentifier = packageIdentifier
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            private fun setTransience(transience: Transience?): Builder {
                this.transience = transience
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            private fun setException(exception: java.lang.Exception?): Builder {
                this.exception = exception
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setMessage(message: String?): Builder {
                this.message = message
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setPackageLoadingCode(packageLoadingCode: PackageLoading.Code?): Builder {
                this.packageLoadingCode = packageLoadingCode
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setFilesystemCode(filesystemCode: Filesystem.Code?): Builder {
                this.filesystemCode = filesystemCode
                return this
            }

            override fun hashCode(): Int {
                return java.util.Objects.hash(
                    exceptionType, packageIdentifier, transience, exception, message, packageLoadingCode
                )
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) {
                    return true
                }
                if (other !is Builder) {
                    return false
                }
                return exceptionType == other.exceptionType
                        && packageIdentifier == other.packageIdentifier
                        && transience == other.transience
                        && exception == other.exception
                        && message == other.message
                        && packageLoadingCode == other.packageLoadingCode
            }

            fun buildCause(): NoSuchPackageException? {
                com.google.common.base.Preconditions.checkNotNull<Type?>(
                    exceptionType,
                    "The NoSuchPackageException type must be set."
                )

                val detailedExitCode: DetailedExitCode?
                if (filesystemCode != null) {
                    detailedExitCode =
                        com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Builder.Companion.createDetailedExitCodeWithFilesystemCode(
                            message,
                            filesystemCode
                        )
                } else {
                    com.google.common.base.Preconditions.checkNotNull<Any?>(
                        packageLoadingCode,
                        "Either the Filesystem code or the PackageLoading code must be set."
                    )
                    detailedExitCode =
                        com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Builder.Companion.createDetailedExitCodeWithPackageLoadingCode(
                            message,
                            packageLoadingCode
                        )
                }

                return exceptionType!!.create(packageIdentifier, message, detailedExitCode, exception)
            }

            fun build(): PackageFunctionException {
                return PackageFunctionException(
                    buildCause(),
                    com.google.common.base.Preconditions.checkNotNull<Transience?>(transience, "Transience must be set")
                )
            }

            companion object {
                private fun createDetailedExitCodeWithPackageLoadingCode(
                    message: String?, packageLoadingCode: PackageLoading.Code?
                ): DetailedExitCode {
                    return DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setPackageLoading(PackageLoading.newBuilder().setCode(packageLoadingCode).build())
                            .build()
                    )
                }

                private fun createDetailedExitCodeWithFilesystemCode(
                    message: String?, filesystemCode: Filesystem.Code?
                ): DetailedExitCode {
                    return DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setFilesystem(Filesystem.newBuilder().setCode(filesystemCode))
                            .build()
                    )
                }
            }
        }

        companion object {
            fun builder(): Builder {
                return com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Builder()
            }
        }
    }

    companion object {
        @Throws(InternalInconsistentFilesystemException::class)
        protected fun maybeThrowFilesystemInconsistency(
            pkgId: PackageIdentifier?, skyframeException: java.lang.Exception, packageWasInError: Boolean
        ) {
            if (!packageWasInError) {
                throw InternalInconsistentFilesystemException(
                    pkgId,
                    ("Encountered error '"
                            + skyframeException.getMessage()
                            + "' but didn't encounter it when doing the same thing earlier in the build")
                )
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getBuildFileValue(env: SkyFunction.Environment, buildFileRootedPath: RootedPath?): FileValue? {
            val buildFileValue: FileValue?
            try {
                buildFileValue =
                    env.getValueOrThrow<E?>(FileValue.key(buildFileRootedPath), IOException::class.java) as FileValue?
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(
                    "Package lookup succeeded but encountered error when "
                            + "getting FileValue for BUILD file directly.",
                    e
                )
            }
            if (buildFileValue == null) {
                return null
            }
            checkState(buildFileValue.exists(), "Package lookup succeeded but BUILD file doesn't exist")
            return buildFileValue
        }

        /**
         * Loads the .bzl modules whose names and load-locations are `programLoads`, and whose
         * corresponding Skyframe keys are `keys`.
         * 
         * 
         * Validates load visibility for loaded modules.
         * 
         * 
         * Returns a map from module name to module, or null for a Skyframe restart.
         * 
         * 
         * The `packageId` is used only for error reporting.
         * 
         * 
         * This function is called for load statements in BUILD files. For loads in .bzl files, see
         * [BzlLoadFunction].
         */
        /*
   * TODO(b/237658764): This logic has several problems:
   *
   * - It is partly duplicated by loadPrelude() below.
   * - The meaty computeBzlLoads* helpers are almost copies of BzlLoadFunction#computeBzlLoads*.
   * - This function should morally probably be called by {Innate,Regular}RunnableExtension rather
   *   than requesting a BzlLoadKey directly. But the API is awkward for these callers.
   * - InliningState is not shared across all callers within a BUILD file; see the comment in
   *   computeBzlLoadsWithInlining.
   *
   * To address these issues, we can instead make public the two BzlLoadFunction#computeBzlLoads*
   * methods. Their programLoads parameter is only used for wrapping exceptions in
   * BzlLoadFailedException#whileLoadingDep, but we can probably push that wrapping to the caller.
   * If we fix PackageFunction to use a shared InliningState, then our computeBzlLoadsWithInlining
   * method will take it as a param and its signature will then basically match the one in
   * BzlLoadFunction.
   *
   * At that point we can eliminate our own computeBzlLoads* methods in favor of the BzlLoadFunction
   * ones. We could factor out the piece of loadBzlModules that dispatches to computeBzlLoads* and
   * translates the possible exception, and push the visibility checking and loadedModules map
   * construction to the caller, so that loadPrelude can become just a call to the factored-out
   * code.
   */
        // TODO(18422): Cleanup/refactor this method's signature.
        @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
        fun loadBzlModules(
            env: SkyFunction.Environment,
            packageId: PackageIdentifier?,
            requestingFileDescription: String?,
            programLoads: MutableList<com.google.devtools.build.lib.util.Pair<String?, net.starlark.java.syntax.Location?>?>,
            keys: MutableList<BzlLoadValue.Key?>,
            semantics: net.starlark.java.eval.StarlarkSemantics,
            bzlLoadFunctionForInlining: BzlLoadFunction?,
            isUnderExperimental: java.util.function.Predicate<PackageIdentifier?>,
            isUnderPrototype: java.util.function.Predicate<PackageIdentifier?>?,
            actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile
        ): com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.Module?>? {
            val bzlLoads: MutableList<BzlLoadValue?>?
            try {
                bzlLoads =
                    if (bzlLoadFunctionForInlining == null)
                        computeBzlLoadsNoInlining(env, keys)
                    else
                        computeBzlLoadsWithInlining(env, keys, bzlLoadFunctionForInlining)
                if (bzlLoads == null) {
                    return null // Skyframe deps unavailable
                }
                // Validate that the current BUILD file satisfies each loaded dependency's load visibility.
                BzlLoadFunction.Companion.checkLoadVisibilities(
                    packageId,
                    requestingFileDescription,
                    bzlLoads,
                    keys,
                    programLoads,  /* demoteErrorsToWarnings= */
                    !semantics.getBool(
                        BuildLanguageOptions.CHECK_BZL_VISIBILITY
                    ),
                    isUnderExperimental,
                    isUnderPrototype,
                    env.getListener()
                )
            } catch (e: BzlLoadFailedException) {
                val rootCause: Throwable = com.google.common.base.Throwables.getRootCause(e)
                val exceptionBuilder =
                    PackageFunctionException.Companion.builder()
                        .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
                        .setPackageIdentifier(packageId)
                        .setException(if (rootCause is IOException) rootCause as IOException else null)
                        .setMessage(e.getMessage())

                val filesystemCode: Filesystem.Code? =
                    e.getDetailedExitCode().getFailureDetail().getFilesystem().getCode()
                if (actionOnFilesystemErrorCodeLoadingBzlFile.shouldTakePrecedenceOverPackageLoadingCode(
                        filesystemCode
                    )
                ) {
                    exceptionBuilder.setFilesystemCode(filesystemCode)
                } else {
                    exceptionBuilder.setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
                }
                exceptionBuilder.setTransience(e.getTransience())
                throw exceptionBuilder.buildCause()
            }

            // Build map of loaded modules.
            val loadedModules: MutableMap<String?, net.starlark.java.eval.Module?> =
                com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize<String?, net.starlark.java.eval.Module?>(
                    bzlLoads.size()
                )
            for (i in bzlLoads.indices) {
                loadedModules.put(programLoads.get(i).first, bzlLoads.get(i).getModule()) // dups ok
            }
            return com.google.common.collect.ImmutableMap.copyOf<String?, net.starlark.java.eval.Module?>(loadedModules)
        }

        // Loads the prelude identified by the label. Returns null for a skyframe restart.
        @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
        private fun loadPrelude(
            env: SkyFunction.Environment,
            packageId: PackageIdentifier?,
            label: Label?,
            bzlLoadFunctionForInlining: BzlLoadFunction?
        ): net.starlark.java.eval.Module? {
            val keys: MutableList<BzlLoadValue.Key?> =
                com.google.common.collect.ImmutableList.of<E?>(BzlLoadValue.keyForBuildPrelude(label))
            try {
                val loads: MutableList<BzlLoadValue?>? =
                    if (bzlLoadFunctionForInlining == null)
                        computeBzlLoadsNoInlining(env, keys)
                    else
                        computeBzlLoadsWithInlining(env, keys, bzlLoadFunctionForInlining)
                if (loads == null) {
                    return null // skyframe restart
                }
                // No need to validate visibility since we're processing an internal load on behalf of Bazel.
                return loads.get(0).getModule()
            } catch (e: BzlLoadFailedException) {
                val rootCause: Throwable = com.google.common.base.Throwables.getRootCause(e)
                throw PackageFunctionException.Companion.builder()
                    .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
                    .setPackageIdentifier(packageId)
                    .setException(if (rootCause is IOException) rootCause as IOException else null)
                    .setMessage(e.getMessage())
                    .setPackageLoadingCode(PackageLoading.Code.IMPORT_STARLARK_FILE_ERROR)
                    .buildCause()
            }
        }

        /**
         * Compute the BzlLoadValue for all given keys using vanilla Skyframe evaluation, returning `null` if Skyframe deps were missing and have been requested.
         */
        @Throws(java.lang.InterruptedException::class, BzlLoadFailedException::class)
        private fun computeBzlLoadsNoInlining(
            env: SkyFunction.Environment, keys: MutableList<BzlLoadValue.Key?>
        ): MutableList<BzlLoadValue?>? {
            val bzlLoads: MutableList<BzlLoadValue?> =
                com.google.common.collect.Lists.newArrayListWithExpectedSize<BzlLoadValue?>(keys.size())
            val starlarkLookupResults: SkyframeLookupResult = env.getValuesAndExceptions(keys)
            for (key in keys) {
                // TODO(adonovan): if get fails, report the source location
                // in the corresponding programLoads[i] (see caller).
                bzlLoads.add(
                    starlarkLookupResults.getOrThrow<E?>(key, BzlLoadFailedException::class.java) as BzlLoadValue?
                )
            }
            return if (env.valuesMissing()) null else bzlLoads
        }

        /**
         * Compute the BzlLoadValue for all given keys by "inlining" the BzlLoadFunction and bypassing
         * traditional Skyframe evaluation, returning `null` if Skyframe deps were missing and have
         * been requested.
         */
        @Throws(java.lang.InterruptedException::class, BzlLoadFailedException::class)
        private fun computeBzlLoadsWithInlining(
            env: SkyFunction.Environment,
            keys: MutableList<BzlLoadValue.Key?>,
            bzlLoadFunctionForInlining: BzlLoadFunction
        ): MutableList<BzlLoadValue?>? {
            val bzlLoads: MutableList<BzlLoadValue?> =
                com.google.common.collect.Lists.newArrayListWithExpectedSize<BzlLoadValue?>(keys.size())
            // See the comment about the desire for deterministic graph structure in BzlLoadFunction for the
            // motivation of this approach to exception handling.
            var deferredException: BzlLoadFailedException? = null
            // Compute BzlLoadValue for each key, sharing the same inlining state, i.e. cache of loaded
            // modules. This ensures that each .bzl is loaded only once, regardless of diamond dependencies
            // or cache eviction. (Multiple loads of the same .bzl would screw up identity equality of some
            // Starlark symbols -- see comments in BzlLoadFunction#computeInline.)
            // TODO(brandjon): Note that using a fresh InliningState in each call to this function means
            // that we don't get sharing between the top-level callers -- namely, the callers that retrieve
            // the BUILD file's loads, the prelude file, and the @_builtins. Since there's still a global
            // cache of bzls, this is only really a problem if the same bzl can appear in more than one of
            // those contexts. This *can* happen if a dependency of the prelude file is also reachable
            // through regular loads, but *only* in OSS Bazel, where inlining is not really used. The fix
            // would be to thread a single InliningState through all call sites within the same call to
            // compute().
            val inliningState: InliningState = InliningState.Companion.create(env)
            for (key in keys) {
                val skyValue: SkyValue?
                try {
                    // Will complete right away if this key has been seen before in inliningState -- regardless
                    // of whether it was evaluated successfully, had missing deps, or was found to be in error.
                    skyValue = bzlLoadFunctionForInlining.computeInline(key, inliningState)
                } catch (e: BzlLoadFailedException) {
                    if (deferredException == null) {
                        deferredException = e
                    }
                    continue
                }
                if (skyValue != null) {
                    bzlLoads.add(skyValue as BzlLoadValue)
                }
                // A null value for `skyValue` can occur when it (or its transitive loads) has a Skyframe dep
                // that is missing or in error. It can also occur if there's a transitive load on a bzl that
                // was already seen by inliningState and which returned null. In both these cases, we want to
                // continue making our inline calls, so as to maximize the number of dependent (non-inlined)
                // SkyFunctions that are requested and avoid a quadratic number of restarts.
            }
            if (deferredException != null) {
                throw deferredException
            }
            return if (env.valuesMissing()) null else bzlLoads
        }

        /**
         * For each of a [Package.Builder]'s targets, propagate the target's corresponding [ ] (if any) and verify that the target's label does not cross
         * subpackage boundaries.
         * 
         * @param pkgBuilder a [Package.AbstractBuilder] whose `getTargets()` set is mutable
         * (i.e. `pkgBuilder.buildPartial()` must have been successfully called).
         */
        @Throws(InternalInconsistentFilesystemException::class, java.lang.InterruptedException::class)
        private fun handleLabelsCrossingSubpackagesAndPropagateInconsistentFilesystemExceptions(
            pkgRoot: Root?, pkgId: PackageIdentifier, pkgBuilder: Package.AbstractBuilder, env: SkyFunction.Environment
        ) {
            val pkgDir: PathFragment? = pkgId.getPackageFragment()
            // Contains a key for each package whose label that might have a presence of a subpackage.
            // Values are all potential subpackages of the label.
            val targetsAndSubpackagePackageLookupKeys: MutableList<com.google.devtools.build.lib.util.Pair<Target?, MutableList<PackageLookupValue.Key?>?>> =
                java.util.ArrayList<com.google.devtools.build.lib.util.Pair<Target?, MutableList<PackageLookupValue.Key?>?>>()
            val allPackageLookupKeys: MutableSet<PackageLookupValue.Key?> = HashSet<PackageLookupValue.Key?>()
            for (target in pkgBuilder.getTargets()) {
                val label: Label = target.getLabel()
                val dir: PathFragment = Label.getContainingDirectory(label)
                if (dir == pkgDir) {
                    continue
                }
                val subpackagePackageLookupKeys: MutableList<PackageLookupValue.Key?> =
                    java.util.ArrayList<PackageLookupValue.Key?>()
                val labelName: String? = label.name
                val labelAsRelativePath: PathFragment? = PathFragment.create(labelName).getParentDirectory()
                var subpackagePath: PathFragment? = pkgDir
                for (segment in labelAsRelativePath.segments()) {
                    // Please note that the order from the shallowest path to the deepest is preserved.
                    subpackagePath = subpackagePath.getRelative(segment)
                    val currentPackageLookupKey: PackageLookupValue.Key? =
                        PackageLookupValue.key(PackageIdentifier.create(pkgId.getRepository(), subpackagePath))
                    subpackagePackageLookupKeys.add(currentPackageLookupKey)
                    allPackageLookupKeys.add(currentPackageLookupKey)
                }
                targetsAndSubpackagePackageLookupKeys.add(
                    com.google.devtools.build.lib.util.Pair.of<Target?, MutableList<PackageLookupValue.Key?>?>(
                        target,
                        subpackagePackageLookupKeys
                    )
                )
            }

            if (targetsAndSubpackagePackageLookupKeys.isEmpty()) {
                return
            }

            val packageLookupResults: SkyframeLookupResult = env.getValuesAndExceptions(allPackageLookupKeys)
            if (env.valuesMissing()) {
                return
            }

            for (targetAndSubpackagePackageLookupKeys in targetsAndSubpackagePackageLookupKeys) {
                val target: Target? = targetAndSubpackagePackageLookupKeys.getFirst()
                val targetPackageLookupKeys: MutableList<PackageLookupValue.Key?>? =
                    targetAndSubpackagePackageLookupKeys.getSecond()
                // Iterate from the deepest potential subpackage to the shallowest in that we only want to
                // display the deepest subpackage in the error message for each target.
                for (packageLookupKey in com.google.common.collect.Lists.reverse<PackageLookupValue.Key>(
                    targetPackageLookupKeys
                )) {
                    var packageLookupValue: PackageLookupValue?
                    try {
                        packageLookupValue =
                            packageLookupResults.getOrThrow<E1?, E2?>(
                                packageLookupKey,
                                BuildFileNotFoundException::class.java,
                                InconsistentFilesystemException::class.java
                            ) as PackageLookupValue?
                    } catch (e: BuildFileNotFoundException) {
                        env.getListener().handle(com.google.devtools.build.lib.events.Event.error(null, e.getMessage()))
                        packageLookupValue = null
                    } catch (e: InconsistentFilesystemException) {
                        throw InternalInconsistentFilesystemException(pkgId, e)
                    }

                    if (Companion.maybeAddEventAboutLabelCrossingSubpackage(
                            pkgBuilder, pkgRoot, target!!, packageLookupKey.argument(), packageLookupValue
                        )
                    ) {
                        pkgBuilder.getTargets().remove(target)
                        pkgBuilder.setContainsErrors()
                        break
                    }
                }
            }
        }

        private fun maybeAddEventAboutLabelCrossingSubpackage(
            pkgBuilder: Package.AbstractBuilder,
            pkgRoot: Root?,
            target: Target,
            subpackageIdentifier: PackageIdentifier?,
            packageLookupValue: PackageLookupValue?
        ): Boolean {
            if (packageLookupValue == null) {
                return true
            }
            val errMsg: String? =
                PackageLookupValue.getErrorMessageForLabelCrossingPackageBoundary(
                    pkgRoot, target.getLabel(), subpackageIdentifier, packageLookupValue
                )
            if (errMsg != null) {
                val error: com.google.devtools.build.lib.events.Event? =
                    Package.error(target.getLocation(), errMsg, Code.LABEL_CROSSES_PACKAGE_BOUNDARY)
                pkgBuilder.getLocalEventHandler().handle(error)
                return true
            } else {
                return false
            }
        }

        private fun badRepoFileException(
            cause: java.lang.Exception?, packageId: PackageIdentifier?
        ): PackageFunctionException {
            return PackageFunctionException.Companion.builder()
                .setType(com.google.devtools.build.lib.skyframe.PackageFunction.PackageFunctionException.Type.BUILD_FILE_CONTAINS_ERRORS)
                .setPackageIdentifier(packageId)
                .setTransience(Transience.PERSISTENT)
                .setException(cause)
                .setMessage("bad REPO.bazel file")
                .setPackageLoadingCode(PackageLoading.Code.BAD_REPO_FILE)
                .build()
        }

        private fun handlePackagePieceDependencyError(
            pkgBuilder: Package.Builder, message: String?
        ) {
            pkgBuilder.setContainsErrors()
            pkgBuilder
                .getLocalEventHandler()
                .handle(
                    Package.error(
                        pkgBuilder.getMetadata().getBuildFileLocation(),
                        java.lang.String.format(
                            "cannot compute package %s: %s",
                            pkgBuilder.getMetadata().packageIdentifier().getCanonicalForm(), message
                        ),
                        Code.STARLARK_EVAL_ERROR
                    )
                )
        }

        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return com.google.devtools.build.lib.skyframe.PackageFunction.Builder()
        }
    }
}
