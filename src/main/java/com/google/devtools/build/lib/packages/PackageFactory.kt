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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.actions.ThreadStateReceiver

/**
 * The package factory is responsible for constructing Package instances from a BUILD file's
 * abstract syntax tree (AST).
 * 
 * 
 * A PackageFactory is a heavy-weight object; create them sparingly. Typically only one is needed
 * per client application.
 */
class PackageFactory(
    ruleClassProvider: RuleClassProvider,
    executorForGlobbing: ForkJoinPool?,
    packageSettings: PackageSettings,
    packageValidator: PackageValidator,
    packageOverheadEstimator: PackageOverheadEstimator?,
    packageLoadingListener: PackageLoadingListener
) {
    private val ruleClassProvider: RuleClassProvider

    private var syscallCache: SyscallCache? = null

    private var executor: ForkJoinPool?

    private var maxDirectoriesToEagerlyVisitInGlobbing = 0

    private val packageSettings: PackageSettings
    private val packageValidator: PackageValidator
    private val packageOverheadEstimator: PackageOverheadEstimator?
    private val packageLoadingListener: PackageLoadingListener

    /** Builder for [PackageFactory] instances. Intended to only be used by unit tests.  */
    @com.google.common.annotations.VisibleForTesting
    abstract class BuilderForTesting {
        @kotlin.jvm.JvmField
        protected var packageValidator: PackageValidator? = PackageValidator.Companion.NOOP_VALIDATOR
        @kotlin.jvm.JvmField
        protected var packageOverheadEstimator: PackageOverheadEstimator? =
            PackageOverheadEstimator.Companion.NOOP_ESTIMATOR

        @kotlin.jvm.JvmField
        protected var doChecksForTesting: Boolean = true

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun disableChecks(): BuilderForTesting {
            this.doChecksForTesting = false
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPackageValidator(packageValidator: PackageValidator?): BuilderForTesting {
            this.packageValidator = packageValidator
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPackageOverheadEstimator(
            packageOverheadEstimator: PackageOverheadEstimator?
        ): BuilderForTesting {
            this.packageOverheadEstimator = packageOverheadEstimator
            return this
        }

        abstract fun build(
            ruleClassProvider: RuleClassProvider?,
            fs: com.google.devtools.build.lib.vfs.FileSystem?
        ): PackageFactory?
    }

    @com.google.common.annotations.VisibleForTesting
    fun getPackageSettingsForTesting(): PackageSettings {
        return packageSettings
    }

    /** Sets the syscalls cache used in filesystem access.  */
    fun setSyscallCache(syscallCache: SyscallCache?) {
        this.syscallCache = com.google.common.base.Preconditions.checkNotNull<SyscallCache?>(syscallCache)
    }

    /**
     * Sets the max number of threads to use for globbing.
     * 
     * 
     * Internally there is a [ForkJoinPool] used for globbing. If the specified `globbingThreads` does not match the previous value (initial value is 100), then we [ ][ForkJoinPool.shutdown] the old [ForkJoinPool] instance and make a new one.
     */
    fun setGlobbingThreads(globbingThreads: Int) {
        if (executor == null) {
            executor = makeForkJoinPool(globbingThreads)
            return
        }
        if (executor.getParallelism() == globbingThreads) {
            return
        }
        // We don't use ForkJoinPool#shutdownNow since it has a performance bug. See
        // http://b/33482341#comment13.
        executor.shutdown()
        executor = makeForkJoinPool(globbingThreads)
    }

    /**
     * Sets the number of directories to eagerly traverse on the first glob for a given package, in
     * order to warm the filesystem. -1 means do no eager traversal. See [ ][com.google.devtools.build.lib.pkgcache.PackageOptions.maxDirectoriesToEagerlyVisitInGlobbing].
     * -2 means do the eager traversal using the regular globbing infrastructure, i.e. sharing the
     * globbing threads and caching the actual glob results.
     */
    fun setMaxDirectoriesToEagerlyVisitInGlobbing(
        maxDirectoriesToEagerlyVisitInGlobbing: Int
    ) {
        this.maxDirectoriesToEagerlyVisitInGlobbing = maxDirectoriesToEagerlyVisitInGlobbing
    }

    /** Returns the [RuleClassProvider] of this [PackageFactory].  */
    fun getRuleClassProvider(): RuleClassProvider {
        return ruleClassProvider
    }

    fun getPackageLoadingListener(): PackageLoadingListener {
        return packageLoadingListener
    }

    // This function is public only for the benefit of skyframe.PackageFunction,
    // which is morally part of lib.packages, so that it can create empty packages
    // in case of error before BUILD execution. Do not call it from anywhere else.
    // TODO(adonovan): refactor Rule{Class,Factory}Test not to need this.
    fun newPackageBuilder(
        packageId: PackageIdentifier?,
        filename: RootedPath?,
        associatedModuleName: java.util.Optional<String?>?,
        associatedModuleVersion: java.util.Optional<String?>?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
        repositoryMapping: RepositoryMapping?,
        mainRepositoryMapping: RepositoryMapping?,
        cpuBoundSemaphore: Semaphore?,
        generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?,
        configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy?,
        globber: Globber?
    ): com.google.devtools.build.lib.packages.Package.Builder {
        return com.google.devtools.build.lib.packages.Package.Companion.newPackageBuilder(
            packageSettings,
            packageId,
            filename,
            ruleClassProvider.getRunfilesPrefix(),
            associatedModuleName,
            associatedModuleVersion,
            starlarkSemantics.getBool(BuildLanguageOptions.Companion.INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT),
            starlarkSemantics.getBool(
                BuildLanguageOptions.Companion.INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS
            ),
            repositoryMapping,
            mainRepositoryMapping,
            cpuBoundSemaphore,
            packageOverheadEstimator,
            generatorMap,
            configSettingVisibilityPolicy,
            globber,  /* enableNameConflictChecking= */
            true,  /* trackFullMacroInformation= */
            true,
            packageValidator.getPackageLimits()
        )
    }

    // This function is public only for the benefit of skyframe.PackageFunction,
    // which is morally part of lib.packages, so that it can create empty packages
    // in case of error before BUILD execution. Do not call it from anywhere else.
    fun newPackageFromPackagePiecesBuilder(
        metadata: com.google.devtools.build.lib.packages.Package.Metadata?,
        declarations: Declarations,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
        mainRepositoryMapping: RepositoryMapping?,
        cpuBoundSemaphore: Semaphore?,
        generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?,
        configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy?,
        globber: Globber?,
        buildFile: InputFile
    ): com.google.devtools.build.lib.packages.Package.Builder {
        return com.google.devtools.build.lib.packages.Package.Companion.newPackageFromPackagePiecesBuilder(
            packageSettings,
            metadata,
            declarations,
            starlarkSemantics.getBool(BuildLanguageOptions.Companion.INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT),
            starlarkSemantics.getBool(
                BuildLanguageOptions.Companion.INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS
            ),
            mainRepositoryMapping,
            cpuBoundSemaphore,
            packageOverheadEstimator,
            generatorMap,
            globber,  /* enableNameConflictChecking= */
            true,  /* trackFullMacroInformation= */
            true,
            packageValidator.getPackageLimits(),
            buildFile
        )
    }

    // This function is public only for the benefit of skyframe.PackageFunction, which is morally part
    // of lib.packages, so that it can create empty package pieces in case of error before BUILD
    // execution. Do not call it from anywhere else.
    fun newPackagePieceForBuildFileBuilder(
        packagePieceId: com.google.devtools.build.lib.packages.PackagePieceIdentifier.ForBuildFile,
        filename: RootedPath?,
        associatedModuleName: java.util.Optional<String?>?,
        associatedModuleVersion: java.util.Optional<String?>?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
        repositoryMapping: RepositoryMapping?,
        mainRepositoryMapping: RepositoryMapping?,
        cpuBoundSemaphore: Semaphore?,
        generatorMap: com.google.common.collect.ImmutableMap<net.starlark.java.syntax.Location?, String?>?,
        configSettingVisibilityPolicy: ConfigSettingVisibilityPolicy?,
        globber: Globber?
    ): com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile.Builder {
        return com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile.Companion.newBuilder(
            packageSettings,
            packagePieceId,
            filename,
            ruleClassProvider.getRunfilesPrefix(),
            associatedModuleName,
            associatedModuleVersion,
            starlarkSemantics.getBool(BuildLanguageOptions.Companion.INCOMPATIBLE_NO_IMPLICIT_FILE_EXPORT),
            starlarkSemantics.getBool(
                BuildLanguageOptions.Companion.INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS
            ),
            repositoryMapping,
            mainRepositoryMapping,
            cpuBoundSemaphore,
            packageOverheadEstimator,
            generatorMap,
            configSettingVisibilityPolicy,
            globber,  /* enableNameConflictChecking= */
            true,  /* trackFullMacroInformation= */
            true,
            packageValidator.getPackageLimits()
        )
    }

    // This function is public only for the benefit of skyframe.EvalMacroFunction, which is morally
    // part of lib.packages, so that it can create empty package pieces in case of error before macro
    // execution. Do not call it from anywhere else.
    fun newPackagePieceForMacroBuilder(
        metadata: com.google.devtools.build.lib.packages.Package.Metadata?,
        declarations: Declarations?,
        macro: MacroInstance?,
        parentIdentifier: PackagePieceIdentifier?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
        mainRepositoryMapping: RepositoryMapping?,
        cpuBoundSemaphore: Semaphore?,
        existingRulesMapForFinalizer: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.packages.Rule?>?
    ): com.google.devtools.build.lib.packages.PackagePiece.ForMacro.Builder {
        return com.google.devtools.build.lib.packages.PackagePiece.ForMacro.Companion.newBuilder(
            metadata,
            declarations,
            macro,
            parentIdentifier,
            starlarkSemantics.getBool(
                BuildLanguageOptions.Companion.INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS
            ),
            mainRepositoryMapping,
            cpuBoundSemaphore,
            packageOverheadEstimator,  /* enableNameConflictChecking= */
            true,  /* trackFullMacroInformation= */
            true,
            packageValidator.getPackageLimits(),
            existingRulesMapForFinalizer
        )
    }

    /** Returns a new [NonSkyframeGlobber].  */ // Exposed to skyframe.PackageFunction.
    fun createNonSkyframeGlobber(
        packageDirectory: com.google.devtools.build.lib.vfs.Path?,
        packageId: PackageIdentifier?,
        ignoredSubdirectories: IgnoredSubdirectories?,
        locator: CachingPackageLocator?,
        threadStateReceiverForMetrics: ThreadStateReceiver?
    ): NonSkyframeGlobber {
        return NonSkyframeGlobber(
            GlobCache(
                packageDirectory,
                packageId,
                ignoredSubdirectories,
                locator,
                syscallCache,
                executor,
                maxDirectoriesToEagerlyVisitInGlobbing,
                threadStateReceiverForMetrics
            )
        )
    }

    /**
     * Runs final validation and administrative tasks on newly loaded package. Called by a caller of
     * [.executeBuildFile] after this caller has fully loaded the package.
     * 
     * @throws InvalidPackageException if the package is determined to be invalid
     */
    @Throws(InvalidPackageException::class)
    fun afterDoneLoadingPackage(
        pkg: com.google.devtools.build.lib.packages.Package,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
        lazyMacroExpansionPackages: LazyMacroExpansionPackages?,
        metrics: com.google.devtools.build.lib.packages.PackageLoadingListener.Metrics?,
        eventHandler: ExtendedEventHandler?
    ) {
        packageValidator.validate(pkg, metrics, eventHandler)

        // Enforce limit on number of compute steps in BUILD file (b/151622307).
        val maxSteps: Long = starlarkSemantics.get<Long?>(BuildLanguageOptions.Companion.MAX_COMPUTATION_STEPS)
        val steps: Long = pkg.getComputationSteps()
        if (maxSteps > 0 && steps > maxSteps) {
            val message: String? =
                java.lang.String.format(
                    "BUILD file computation took %d steps, but --max_computation_steps=%d",
                    steps, maxSteps
                )
            throw InvalidPackageException(
                pkg.getPackageIdentifier(),
                message,
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setPackageLoading(
                            PackageLoading.newBuilder()
                                .setCode(PackageLoading.Code.MAX_COMPUTATION_STEPS_EXCEEDED)
                                .build()
                        )
                        .build()
                )
            )
        }

        packageLoadingListener.onLoadingCompleteAndSuccessful(
            pkg, starlarkSemantics, lazyMacroExpansionPackages, metrics
        )
    }

    /**
     * Runs final validation and administrative tasks on newly loaded package piece. Called by a
     * caller of [.executeBuildFile] after this caller has fully loaded the package piece.
     * 
     * @throws InvalidPackagePieceException if the package is determined to be invalid
     */
    // TODO(https://github.com/bazelbuild/bazel/issues/23852): merge with afterDoneLoadingPackagePiece
    // and perhaps move it all to PackageFunction (combining with existing PackageFunction.compute()
    // boilerplate such as finishBuild() and event replay). Requires package piece validation.
    @Throws(InvalidPackagePieceException::class)
    fun afterDoneLoadingPackagePiece(
        pkgPiece: PackagePiece,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
        metrics: com.google.devtools.build.lib.packages.PackageLoadingListener.Metrics?,
        eventHandler: ExtendedEventHandler?
    ) {
        // TODO(https://github.com/bazelbuild/bazel/issues/23852): add package piece validation.

        // Enforce limit on number of compute steps in BUILD file (b/151622307).

        val maxSteps: Long = starlarkSemantics.get<Long?>(BuildLanguageOptions.Companion.MAX_COMPUTATION_STEPS)
        val steps: Long = pkgPiece.getComputationSteps()
        if (maxSteps > 0 && steps > maxSteps) {
            val message: String? =
                java.lang.String.format(
                    "%s took %d computation steps, but --max_computation_steps=%d",
                    if (pkgPiece is com.google.devtools.build.lib.packages.PackagePiece.ForBuildFile)
                        "BUILD file computation without expanding symbolic macros"
                    else
                        "symbolic macro evaluation",
                    steps,
                    maxSteps
                )
            throw InvalidPackagePieceException(
                pkgPiece.getIdentifier(),
                message,
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setPackageLoading(
                            PackageLoading.newBuilder()
                                .setCode(PackageLoading.Code.MAX_COMPUTATION_STEPS_EXCEEDED)
                                .build()
                        )
                        .build()
                )
            )
        }

        // TODO(https://github.com/bazelbuild/bazel/issues/23852): inform packageLoadingListener
    }

    /**
     * Populates the Package.Builder by executing the specified BUILD file.
     * 
     * 
     * The package exists---we have parsed its BUILD file---but it may contain errors, either
     * arising from Starlark evaluation (such as an array index error, or a call to a built-in
     * function that fails), or reported as a side effect of a built-in function, such as rule
     * instantiation, that returns normally. A partial package is nonetheless returned in both cases,
     * although it may have fewer rules than expected.
     * 
     * 
     * TODO(adonovan): do not return a partial package in case of BUILD evaluation errors. Errors
     * during .bzl execution are already fatal.
     * 
     * 
     * **Do not call it from elsewhere! It is not in any meaningful sense a public API.**<br></br>
     * In tests, use BuildViewTestCase or PackageLoadingTestCase instead.
     * 
     * 
     * TODO(adonovan): move PackageFunction into this package and develop a rational API.
     */
    // This function is the sole entry point for package creation in production and tests. Do not add
    // others! It changes often, and is exposed only for the benefit of skyframe.PackageFunction,
    // which is logically part of the loading phase and should in due course be moved to lib.packages,
    // but that cannot happen until Skyframe's core interfaces have been separated.
    @Throws(java.lang.InterruptedException::class)
    fun executeBuildFile(
        pkgBuilder: com.google.devtools.build.lib.packages.Package.AbstractBuilder,
        buildFileProgram: net.starlark.java.syntax.Program,
        globs: com.google.common.collect.ImmutableList<String?>?,
        globsWithDirs: com.google.common.collect.ImmutableList<String?>?,
        subpackages: com.google.common.collect.ImmutableList<String?>?,
        predeclared: com.google.common.collect.ImmutableMap<String?, Any?>?,
        loadedModules: com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.Module?>,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
    ) {
        val globber: Globber? = pkgBuilder.getGlobber()

        // Prefetch glob patterns asynchronously.
        if (maxDirectoriesToEagerlyVisitInGlobbing == -2) {
            try {
                val allowEmpty = true
                globber.runAsync(
                    globs,
                    com.google.common.collect.ImmutableList.of<String?>(),
                    com.google.devtools.build.lib.packages.Globber.Operation.FILES,
                    allowEmpty
                )
                globber.runAsync(
                    globsWithDirs,
                    com.google.common.collect.ImmutableList.of<String?>(),
                    com.google.devtools.build.lib.packages.Globber.Operation.FILES_AND_DIRS,
                    allowEmpty
                )
                globber.runAsync(
                    subpackages,
                    com.google.common.collect.ImmutableList.of<String?>(),
                    com.google.devtools.build.lib.packages.Globber.Operation.SUBPACKAGES,
                    allowEmpty
                )
            } catch (ex: BadGlobException) {
                logger.atWarning().withCause(ex).log(
                    "Suppressing exception for globs=%s, globsWithDirs=%s", globs, globsWithDirs
                )
                // Ignore exceptions. Errors will be properly reported when the actual globbing is done.
            }
        }

        val cpuSemaphore: Semaphore? = pkgBuilder.getCpuBoundSemaphore()
        var semaphoreAcquired = false
        try {
            if (cpuSemaphore != null) {
                cpuSemaphore.acquire()
                semaphoreAcquired = true
            }
            executeBuildFileImpl(
                pkgBuilder, buildFileProgram, predeclared, loadedModules, starlarkSemantics
            )
        } catch (e: java.lang.InterruptedException) {
            if (semaphoreAcquired) {
                cpuSemaphore.release()
                semaphoreAcquired = false // Mark as released
            }
            globber.onInterrupt()
            throw e
        } finally {
            if (semaphoreAcquired) {
                cpuSemaphore.release()
            }
            globber.onCompletion()
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun executeBuildFileImpl(
        pkgBuilder: com.google.devtools.build.lib.packages.Package.AbstractBuilder,
        buildFileProgram: net.starlark.java.syntax.Program,
        predeclared: com.google.common.collect.ImmutableMap<String?, Any?>?,
        loadedModules: com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.Module?>,
        semantics: net.starlark.java.eval.StarlarkSemantics?
    ) {
        pkgBuilder.setLoads(loadedModules.values())

        net.starlark.java.eval.Mutability.create("package", pkgBuilder.getFilename()).use { mu ->
            val module: net.starlark.java.eval.Module =
                net.starlark.java.eval.Module.withPredeclared(semantics, predeclared)
            val thread: net.starlark.java.eval.StarlarkThread =
                net.starlark.java.eval.StarlarkThread.create(
                    mu, semantics,  /* contextDescription= */"", pkgBuilder.getSymbolGenerator()
                )
            thread.setLoader(net.starlark.java.eval.StarlarkThread.Loader { key: String? -> loadedModules.get(key) })
            thread.setPrintHandler(Event.makeDebugPrintHandler(pkgBuilder.getLocalEventHandler()))
            pkgBuilder.storeInThread(thread)

            // TODO(b/291752414): The rule definition environment shouldn't be needed at BUILD evaluation
            // time EXCEPT for analysis_test, which needs the tools repository for use in
            // StarlarkRuleClassFunctions#createRule. So we set it here as a thread-local to be retrieved
            // by StarlarkTestingModule#analysisTest.
            thread.setThreadLocal<T?>(RuleDefinitionEnvironment::class.java, ruleClassProvider)
            try {
                pkgBuilder.updateStartedThreadComputationSteps(thread).use { updater ->
                    net.starlark.java.eval.Starlark.execFileProgram(buildFileProgram, module, thread)
                }
            } catch (ex: net.starlark.java.eval.EvalException) {
                pkgBuilder
                    .getLocalEventHandler()
                    .handle(
                        com.google.devtools.build.lib.packages.Package.Companion.error(
                            null,
                            ex.getMessageWithStack(),
                            Code.STARLARK_EVAL_ERROR
                        )
                    )
                pkgBuilder.setContainsErrors()
            } catch (ex: java.lang.InterruptedException) {
                if (pkgBuilder.containsErrors()) {
                    // Suppress the interrupted exception: we have an error of our own to return.
                    java.lang.Thread.currentThread().interrupt()
                    logger.atInfo().withCause(ex).log(
                        "Suppressing InterruptedException for %s because an error was also found",
                        pkgBuilder.getShortDescription()
                    )
                } else {
                    throw ex
                }
            }
        }
    }

    /**
     * Constructs a `PackageFactory` instance with a specific glob path translator and rule
     * factory.
     * 
     * 
     * Only intended to be called by BlazeRuntime or [BuilderForTesting.build].
     * 
     * 
     * Do not call this constructor directly in tests; please use
     * TestConstants#PACKAGE_FACTORY_BUILDER_FACTORY_FOR_TESTING instead.
     */
    init {
        this.ruleClassProvider = ruleClassProvider
        this.executor = executorForGlobbing
        this.packageSettings = packageSettings
        this.packageValidator = packageValidator
        this.packageOverheadEstimator = packageOverheadEstimator
        this.packageLoadingListener = packageLoadingListener
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @kotlin.jvm.JvmStatic
        fun makeDefaultSizedForkJoinPoolForGlobbing(): ForkJoinPool {
            return makeForkJoinPool( /* globbingThreads= */100)
        }

        private fun makeForkJoinPool(globbingThreads: Int): ForkJoinPool {
            return NamedForkJoinPool.newNamedPool("globbing pool", globbingThreads)
        }

        /**
         * checkBuildSyntax is a static pass over the syntax tree of a BUILD (not .bzl) file.
         * 
         * 
         * It throws a [SyntaxError.Exception] if it discovers disallowed elements (see [ ]).
         * 
         * 
         * It extracts literal `glob(include="pattern")` patterns and adds them to `globs`,
         * or to `globsWithDirs` if the call had a `exclude_directories=0` argument.
         * 
         * 
         * It records in `generatorNameByLocation` all calls of the form `f(name="foo", ...)` so that any rules instantiated during the call to `f` can be ascribed a "generator
         * name" of `"foo"`.
         */
        // TODO(adonovan): restructure so that this is called from the sole place that executes BUILD
        // files. Also, make private; there's no reason for tests to call this directly.
        @Throws(net.starlark.java.syntax.SyntaxError.Exception::class)
        fun checkBuildSyntax(
            file: net.starlark.java.syntax.StarlarkFile?,
            globs: MutableCollection<String?>?,
            globsWithDirs: MutableCollection<String?>?,
            subpackages: MutableCollection<String?>,
            generatorNameByLocation: MutableMap<net.starlark.java.syntax.Location?, String?>
        ) {
            object : DotBazelFileSyntaxChecker("BUILD files",  /* canLoadBzl= */true) {
                // Extract literal glob patterns from calls of the form:
                //   glob(include = ["pattern"])
                //   glob(["pattern"])
                //   subpackages(include = ["pattern"])
                // This may spuriously match user-defined functions named glob or
                // subpackages; that's ok, it's only a heuristic.
                fun extractGlobPatterns(call: net.starlark.java.syntax.CallExpression) {
                    if (call.getFunction() is net.starlark.java.syntax.Identifier) {
                        val functionName: String = (call.getFunction() as net.starlark.java.syntax.Identifier).getName()
                        if (functionName != "glob" && functionName != "subpackages") {
                            return
                        }

                        var excludeDirectories: net.starlark.java.syntax.Expression? = null
                        var include: net.starlark.java.syntax.Expression? = null
                        val arguments: com.google.common.collect.ImmutableList<net.starlark.java.syntax.Argument> =
                            call.getArguments()
                        for (i in arguments.indices) {
                            val arg: net.starlark.java.syntax.Argument = arguments.get(i)
                            val name: String? = arg.getName()
                            if (name == null) {
                                if (i == 0) { // first positional argument
                                    include = arg.getValue()
                                }
                            } else if (name == "include") {
                                include = arg.getValue()
                            } else if (name == "exclude_directories") {
                                excludeDirectories = arg.getValue()
                            }
                        }
                        if (include is net.starlark.java.syntax.ListExpression) {
                            for (elem in (include as net.starlark.java.syntax.ListExpression).getElements()) {
                                if (elem is net.starlark.java.syntax.StringLiteral) {
                                    val pattern: String? = (elem as net.starlark.java.syntax.StringLiteral).getValue()
                                    // exclude_directories is (oddly) an int with default 1.
                                    var exclude = true
                                    if (excludeDirectories is net.starlark.java.syntax.IntLiteral) {
                                        val v: Number? =
                                            (excludeDirectories as net.starlark.java.syntax.IntLiteral).getValue()
                                        if (v is Int && v == 0) {
                                            exclude = false
                                        }
                                    }
                                    if (functionName == "glob") {
                                        (if (exclude) globs else globsWithDirs)!!.add(pattern)
                                    } else {
                                        subpackages.add(pattern)
                                    }
                                }
                            }
                        }
                    }
                }

                // Record calls of the form f(name="foo", ...)
                // so that we can later ascribe "foo" as the "generator name"
                // of any rules instantiated during the call of f.
                fun recordGeneratorName(call: net.starlark.java.syntax.CallExpression) {
                    for (arg in call.getArguments()) {
                        if (arg is net.starlark.java.syntax.Argument.Keyword
                            && arg.getName() == "name"
                            && arg.getValue() is net.starlark.java.syntax.StringLiteral
                        ) {
                            generatorNameByLocation.put(
                                call.getLparenLocation(),
                                (arg.getValue() as net.starlark.java.syntax.StringLiteral).getValue()
                            )
                        }
                    }
                }

                override fun visit(node: net.starlark.java.syntax.CallExpression) {
                    extractGlobPatterns(node)
                    recordGeneratorName(node)
                    // Continue traversal so as not to miss nested calls
                    // like cc_binary(..., f(**kwargs), srcs=glob(...), ...).
                    super.visit(node)
                }
            }.check(file)
        }

        // Install profiler hooks into Starlark interpreter.
        init {
            // parser profiler
            net.starlark.java.syntax.StarlarkFile.setParseProfiler(
                object : net.starlark.java.syntax.StarlarkFile.ParseProfiler {
                    override fun start(): Long {
                        return com.google.devtools.build.lib.profiler.Profiler.Companion.instance().nanoTimeMaybe()
                    }

                    override fun end(startTimeNanos: Long, filename: String?) {
                        com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                            .completeTask(
                                startTimeNanos,
                                com.google.devtools.build.lib.profiler.ProfilerTask.STARLARK_PARSER,
                                filename
                            )
                    }
                })

            // call profiler
            net.starlark.java.eval.StarlarkThread.setCallProfiler(
                object : net.starlark.java.eval.StarlarkThread.CallProfiler {
                    override fun start(): Long {
                        return com.google.devtools.build.lib.profiler.Profiler.Companion.instance().nanoTimeMaybe()
                    }

                    override fun end(
                        startTimeNanos: Long, fn: net.starlark.java.eval.StarlarkCallable, threadContext: String?
                    ) {
                        com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                            .completeTask(
                                startTimeNanos,
                                if (fn is net.starlark.java.eval.StarlarkFunction)
                                    com.google.devtools.build.lib.profiler.ProfilerTask.STARLARK_USER_FN
                                else
                                    com.google.devtools.build.lib.profiler.ProfilerTask.STARLARK_BUILTIN_FN,
                                fn.getName()
                            )
                        // Keep this last so that it wraps the span above.
                        if (!com.google.common.base.Strings.isNullOrEmpty(threadContext)) {
                            com.google.devtools.build.lib.profiler.Profiler.Companion.instance()
                                .completeTask(
                                    startTimeNanos,
                                    com.google.devtools.build.lib.profiler.ProfilerTask.STARLARK_THREAD_CONTEXT,
                                    threadContext
                                )
                        }
                    }
                })
        }
    }
}
