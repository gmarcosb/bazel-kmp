// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.actions.FileValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction.Companion.errorf

/**
 * Takes a [ModuleKey] and its override (if any), retrieves the module file from a registry or
 * as directed by the override, and evaluates the module file.
 */
class ModuleFileFunction(
    starlarkEnv: BazelStarlarkEnvironment?,
    workspaceRoot: com.google.devtools.build.lib.vfs.Path?,
    builtinModules: com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>?
) : SkyFunction {
    private val starlarkEnv: BazelStarlarkEnvironment?
    private val workspaceRoot: com.google.devtools.build.lib.vfs.Path?
    private val builtinModules: com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>?
    private var downloadManager: DownloadManager? = null

    /**
     * @param builtinModules A list of "built-in" modules that are treated as implicit dependencies of
     * every other module (including other built-in modules). These modules are defined as
     * non-registry overrides.
     */
    init {
        this.starlarkEnv = starlarkEnv
        this.workspaceRoot = workspaceRoot
        this.builtinModules = builtinModules
    }

    private class ModuleFileMetadata(
        registry: com.google.devtools.build.lib.bazel.bzlmod.Registry?,
        registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?
    ) {
        val registry: com.google.devtools.build.lib.bazel.bzlmod.Registry?
        val registryFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?

        init {
            this.registry = registry
            this.registryFileHashes = registryFileHashes
        }
    }

    private class State : SkyKeyComputeState {
        var compiledModuleFile: CompiledModuleFile? = null
        var moduleFileMetadata: ModuleFileMetadata? = null

        // The following fields are used while evaluating the root module file or the module file of a
        // module subject to an override. We try to compile the root module file itself first, and then
        // read, parse, and compile any included module files layer by layer, in a BFS fashion (hence
        // the `horizon` field). Finally, everything is collected into the
        // `includeLabelToCompiledModuleFile` map for use during actual Starlark execution.
        var horizon: com.google.common.collect.ImmutableList<IncludeStatement>? = null
        var includeLabelToCompiledModuleFile: SequencedMap<String?, CompiledModuleFile?> =
            LinkedHashMap<String?, CompiledModuleFile?>()
    }

    @Throws(ModuleFileFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? = PrecomputedValue.STARLARK_SEMANTICS.get(env)
        if (starlarkSemantics == null) {
            return null
        }

        if (skyKey == ModuleFileValue.Companion.KEY_FOR_ROOT_MODULE) {
            return computeForRootModule(
                starlarkSemantics,
                env,
                net.starlark.java.eval.SymbolGenerator.create<SkyKey?>(skyKey)
            )
        }

        val allowedYankedVersionsFromEnv: EnvironmentVariableValue? =
            env.getValue(
                ClientEnvironmentFunction.key(
                    YankedVersionsUtil.BZLMOD_ALLOWED_YANKED_VERSIONS_ENV
                )
            ) as EnvironmentVariableValue?
        if (allowedYankedVersionsFromEnv == null) {
            return null
        }

        val rootModuleFileValue: RootModuleFileValue? =
            env.getValue(ModuleFileValue.Companion.KEY_FOR_ROOT_MODULE) as RootModuleFileValue?
        if (rootModuleFileValue == null) {
            return null
        }

        val moduleKey: ModuleKey = (skyKey as com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.Key).moduleKey
        val state: State =
            env.getState<State>(java.util.function.Supplier { com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction.State() })
        if (state.compiledModuleFile == null) {
            val getModuleFileResult: GetModuleFileResult?
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(
                    com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD,
                    java.util.function.Supplier { "fetch module file: " + moduleKey }).use { c ->
                    getModuleFileResult =
                        getModuleFile(moduleKey, rootModuleFileValue.overrides.get(moduleKey.name), env)
                }
            if (getModuleFileResult == null) {
                return null
            }

            state.moduleFileMetadata =
                ModuleFileMetadata(
                    getModuleFileResult.registry,
                    RegistryFileDownloadEvent.Companion.collectToMap(
                        getModuleFileResult.downloadEventHandler.getPosts()
                    )
                )
            try {
                state.compiledModuleFile =
                    CompiledModuleFile.Companion.parseAndCompile(
                        getModuleFileResult.moduleFile,
                        moduleKey,
                        starlarkSemantics,
                        starlarkEnv,
                        env.getListener()
                    )
            } catch (e: ExternalDepsException) {
                throw ModuleFileFunctionException(e, Transience.PERSISTENT)
            }
        }
        val moduleThreadContext: ModuleThreadContext?
        if (state.moduleFileMetadata!!.registry != null) {
            if (!state.compiledModuleFile.includeStatements.isEmpty()) {
                throw errorf(
                    Code.BAD_MODULE,
                    "include() directive found at %s, but it can only be used in the root module or in "
                            + "modules with non-registry overrides",
                    state.compiledModuleFile.includeStatements.getFirst().location
                )
            }
            moduleThreadContext =
                execModuleFile(
                    state.compiledModuleFile,  /* includeLabelToParsedModuleFile= */
                    null,
                    moduleKey,  // Dev dependencies should always be ignored if the current module isn't the root
                    // module.
                    /* ignoreDevDeps= */
                    true,
                    builtinModules,  /* injectedRepositories= */
                    com.google.common.collect.ImmutableMap.of<String?, PathFragment?>(),  // Disable printing for modules from registries. We don't want them to be able to spam
                    // the console during resolution.
                    /* printIsNoop= */
                    true,
                    starlarkSemantics,
                    env.getListener(),
                    net.starlark.java.eval.SymbolGenerator.create<SkyKey?>(skyKey)
                )
        } else {
            moduleThreadContext =
                execNonRegistryModuleFile(
                    moduleKey, starlarkSemantics, env, net.starlark.java.eval.SymbolGenerator.create<SkyKey?>(skyKey)
                )
            if (moduleThreadContext == null) {
                return null
            }
        }

        // Perform some sanity checks.
        val module: InterimModule
        try {
            module = moduleThreadContext.buildModule(state.moduleFileMetadata!!.registry)
        } catch (e: net.starlark.java.eval.EvalException) {
            env.getListener().handle(
                com.google.devtools.build.lib.events.Event.error(
                    e.getInnermostLocation(),
                    e.getMessageWithStack()
                )
            )
            throw errorf(Code.BAD_MODULE, "error executing MODULE.bazel file for %s", moduleKey)
        }
        if (module.getName() != moduleKey.name) {
            throw errorf(
                Code.BAD_MODULE,
                "the MODULE.bazel file of %s declares a different name (%s)",
                moduleKey,
                module.getName()
            )
        }
        if (!moduleKey.version.isEmpty() && module.getVersion() != moduleKey.version) {
            throw errorf(
                Code.BAD_MODULE,
                "the MODULE.bazel file of %s declares a different version (%s)",
                moduleKey,
                module.getVersion()
            )
        }

        return NonRootModuleFileValue(module, state.moduleFileMetadata!!.registryFileHashes)
    }

    fun setDownloadManager(downloadManager: DownloadManager?) {
        this.downloadManager = downloadManager
    }

    @Throws(ModuleFileFunctionException::class, java.lang.InterruptedException::class)
    private fun computeForRootModule(
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
        env: SkyFunction.Environment,
        symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>?
    ): SkyValue? {
        val state: State =
            env.getState<State>(java.util.function.Supplier { com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction.State() })
        if (state.compiledModuleFile == null) {
            val moduleFilePath: RootedPath = getModuleFilePath(workspaceRoot)
            if (env.getValue(FileValue.key(moduleFilePath)) == null) {
                return null
            }
            val moduleFileContents: ByteArray?
            if (moduleFilePath.asPath().exists()) {
                moduleFileContents = readModuleFile(moduleFilePath.asPath())
            } else {
                moduleFileContents = BZLMOD_REMINDER.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                createModuleFile(moduleFilePath.asPath(), moduleFileContents)
                env.getListener()
                    .handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            ("--enable_bzlmod is set, but no MODULE.bazel file was found at the workspace"
                                    + " root. Bazel will create an empty MODULE.bazel file. Please consider"
                                    + " migrating your external dependencies from WORKSPACE to MODULE.bazel."
                                    + " For more details, please refer to"
                                    + " https://github.com/bazelbuild/bazel/issues/18958.")
                        )
                    )
            }
            try {
                state.compiledModuleFile =
                    CompiledModuleFile.Companion.parseAndCompile(
                        ModuleFile.Companion.create(moduleFileContents, moduleFilePath.asPath().toString()),
                        ModuleKey.Companion.ROOT,
                        starlarkSemantics,
                        starlarkEnv,
                        env.getListener()
                    )
            } catch (e: ExternalDepsException) {
                throw ModuleFileFunctionException(e, Transience.PERSISTENT)
            }
        }
        val moduleThreadContext: ModuleThreadContext? =
            execNonRegistryModuleFile(ModuleKey.Companion.ROOT, starlarkSemantics, env, symbolGenerator)
        if (moduleThreadContext == null) {
            return null
        }
        return buildRootModuleFileValue(
            moduleThreadContext,
            com.google.common.collect.ImmutableMap.copyOf<String?, CompiledModuleFile?>(state.includeLabelToCompiledModuleFile),
            MODULE_OVERRIDES.get(env),
            env.getListener()
        )
    }

    /** env.getState(State::new).compiledModuleFile must be set before calling this method.  */
    @Throws(ModuleFileFunctionException::class, java.lang.InterruptedException::class)
    private fun execNonRegistryModuleFile(
        moduleKey: ModuleKey,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
        env: SkyFunction.Environment,
        symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>?
    ): ModuleThreadContext? {
        val state: State =
            env.getState<State>(java.util.function.Supplier { com.google.devtools.build.lib.bazel.bzlmod.ModuleFileFunction.State() })
        com.google.common.base.Preconditions.checkNotNull<CompiledModuleFile?>(state.compiledModuleFile)
        if (state.horizon == null) {
            state.horizon = state.compiledModuleFile.includeStatements
        }
        while (!state.horizon.isEmpty()) {
            val newHorizon: com.google.common.collect.ImmutableList<IncludeStatement>? =
                advanceHorizon(
                    moduleKey,
                    state.includeLabelToCompiledModuleFile,
                    state.horizon,
                    env,
                    starlarkSemantics,
                    starlarkEnv
                )
            if (newHorizon == null) {
                return null
            }
            state.horizon = newHorizon
        }
        val isRoot = moduleKey == ModuleKey.Companion.ROOT
        return execModuleFile(
            state.compiledModuleFile,
            com.google.common.collect.ImmutableMap.copyOf<String?, CompiledModuleFile?>(state.includeLabelToCompiledModuleFile),
            moduleKey,
            if (isRoot) IGNORE_DEV_DEPS.get(env) else true,
            builtinModules,
            if (isRoot) INJECTED_REPOSITORIES.get(env) else com.google.common.collect.ImmutableMap.of<String?, PathFragment?>(),  // Allow printing to aid in debugging non-registry overrides, which are often edited by the
            // user.
            /* printIsNoop= */
            false,
            starlarkSemantics,
            env.getListener(),
            symbolGenerator
        )
    }

    /**
     * Result of a [.getModuleFile] call.
     * 
     * @param registry can be null if this module has a non-registry override.
     */
    private class GetModuleFileResult(
        moduleFile: ModuleFile?,
        registry: com.google.devtools.build.lib.bazel.bzlmod.Registry?,
        downloadEventHandler: com.google.devtools.build.lib.events.StoredEventHandler?
    ) {
        val moduleFile: ModuleFile?
        val registry: com.google.devtools.build.lib.bazel.bzlmod.Registry?
        val downloadEventHandler: com.google.devtools.build.lib.events.StoredEventHandler?

        init {
            this.moduleFile = moduleFile
            this.registry = registry
            this.downloadEventHandler = downloadEventHandler
        }
    }

    @Throws(ModuleFileFunctionException::class, java.lang.InterruptedException::class)
    private fun getModuleFile(
        key: ModuleKey,
        override: com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?,
        env: SkyFunction.Environment
    ): GetModuleFileResult? {
        // If there is a non-registry override for this module, we need to fetch the corresponding repo
        // first and read the module file from there.
        if (override is NonRegistryOverride) {
            // A module with a non-registry override always has a unique version across the entire dep
            // graph.
            val canonicalRepoName: RepositoryName? = key.getCanonicalRepoNameWithoutVersion()
            val repoDir: RepositoryDirectoryValue? =
                env.getValue(RepositoryDirectoryValue.key(canonicalRepoName)) as RepositoryDirectoryValue?
            if (repoDir == null) {
                return null
            }
            // This repo _definitely_ exists, since it has a non-registry override, which directly gets
            // "translated" into a repo spec. So we can cast `repoDir` to `Success`.
            val moduleFilePath: RootedPath =
                RootedPath.toRootedPath(
                    (repoDir as RepositoryDirectoryValue.Success).root, LabelConstants.MODULE_DOT_BAZEL_FILE_NAME
                )
            if (env.getValue(FileValue.key(moduleFilePath)) == null) {
                return null
            }
            val moduleFileLabel: com.google.devtools.build.lib.cmdline.Label =
                com.google.devtools.build.lib.cmdline.Label.createUnvalidated(
                    PackageIdentifier.create(canonicalRepoName, PathFragment.EMPTY_FRAGMENT),
                    LabelConstants.MODULE_DOT_BAZEL_FILE_NAME.getBaseName()
                )
            return GetModuleFileResult(
                ModuleFile.Companion.create(
                    readModuleFile(moduleFilePath.asPath()),
                    moduleFileLabel.getUnambiguousCanonicalForm()
                ),  /* registry= */
                null,
                com.google.devtools.build.lib.events.StoredEventHandler()
            )
        }

        // Otherwise, we should get the module file from a registry.
        if (key.version.isEmpty()) {
            // Print a friendlier error message if the user forgets to specify a version *and* doesn't
            // have a non-registry override.
            throw errorf(
                Code.MODULE_NOT_FOUND,
                "bad bazel_dep on module '%s' with no version. Did you forget to specify a version, or a"
                        + " non-registry override?",
                key.name
            )
        }
        var registries: com.google.common.collect.ImmutableSet<String?> =
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<String?>>(
                REGISTRIES.get(env)
            )
        if (override is RegistryOverride) {
            val overrideRegistry: String = override.getRegistry()
            if (!overrideRegistry.isEmpty()) {
                registries = com.google.common.collect.ImmutableSet.of<String?>(overrideRegistry)
            }
        } else check(override == null) {
            java.lang.String.format(
                "unrecognized override type %s for module %s",
                override.getClass().getSimpleName(), key
            )
        }

        val registryKeys: MutableList<com.google.devtools.build.lib.bazel.bzlmod.RegistryKey?> =
            registries.stream()
                .map<com.google.devtools.build.lib.bazel.bzlmod.RegistryKey?>(java.util.function.Function { url: String? ->
                    com.google.devtools.build.lib.bazel.bzlmod.RegistryKey.Companion.create(url)
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.bazel.bzlmod.RegistryKey?>())
        val registryResult: SkyframeLookupResult = env.getValuesAndExceptions(registryKeys)
        if (env.valuesMissing()) {
            return null
        }
        val registryObjects: MutableList<com.google.devtools.build.lib.bazel.bzlmod.Registry> =
            java.util.ArrayList<com.google.devtools.build.lib.bazel.bzlmod.Registry>(registryKeys.size())
        for (registryKey in registryKeys) {
            val registry: com.google.devtools.build.lib.bazel.bzlmod.Registry? =
                registryResult.get(registryKey) as com.google.devtools.build.lib.bazel.bzlmod.Registry?
            if (registry == null) {
                return null
            }
            registryObjects.add(registry)
        }

        // Now go through the list of registries and use the first one that contains the requested
        // module.
        val downloadEventHandler: com.google.devtools.build.lib.events.StoredEventHandler =
            com.google.devtools.build.lib.events.StoredEventHandler()
        var notFoundTrace: MutableList<String?>? = null
        for (registry in registryObjects) {
            try {
                val originalModuleFile: ModuleFile
                try {
                    originalModuleFile =
                        registry.getModuleFile(key, downloadEventHandler, this.downloadManager)
                } catch (e: NotFoundException) {
                    if (notFoundTrace == null) {
                        notFoundTrace = java.util.ArrayList<String?>()
                    }
                    notFoundTrace!!.add(e.getMessage())
                    continue
                }
                val moduleFile: ModuleFile? = maybePatchModuleFile(originalModuleFile, override, env)
                if (moduleFile == null) {
                    return null
                }
                return GetModuleFileResult(moduleFile, registry, downloadEventHandler)
            } catch (e: MissingChecksumException) {
                throw ModuleFileFunctionException(
                    ExternalDepsException.Companion.withCause(Code.BAD_LOCKFILE, e)
                )
            } catch (e: IOException) {
                throw errorf(
                    Code.ERROR_ACCESSING_REGISTRY, e, "Error accessing registry %s", registry.getUrl()
                )
            }
        }

        throw errorf(
            Code.MODULE_NOT_FOUND,
            "module %s not found in registries:\n* %s",
            key,
            java.lang.String.join("\n* ", notFoundTrace)
        )
    }

    /**
     * Applies any patches specified in registry overrides.
     * 
     * 
     * This allows users to modify MODULE.bazel files and thus influence resolution and visibility
     * for modules via patches without having to replace the entire module via a non-registry
     * override.
     * 
     * 
     * Note: Only patch files from the main repo are applied, all other patches are ignored. This
     * is necessary as we can't load other repos during resolution (unless they are subject to a
     * non-registry override). Patch commands are also not supported as they cannot be selectively
     * applied to the module file only.
     */
    @Throws(java.lang.InterruptedException::class, ModuleFileFunctionException::class)
    private fun maybePatchModuleFile(
        moduleFile: ModuleFile,
        override: com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?,
        env: SkyFunction.Environment
    ): ModuleFile? {
        if (override !is SingleVersionOverride) {
            return moduleFile
        }
        val patchesInMainRepo: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label> =
            override.patches.stream()
                .filter(java.util.function.Predicate { label: com.google.devtools.build.lib.cmdline.Label? ->
                    label.getRepository().isMain()
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.cmdline.Label>())
        if (patchesInMainRepo.isEmpty()) {
            return moduleFile
        }

        // Get the patch paths.
        val patchPackageLookupKeys: com.google.common.collect.ImmutableList<SkyKey?> =
            patchesInMainRepo.stream()
                .map<PackageIdentifier?>(java.util.function.Function { obj: com.google.devtools.build.lib.cmdline.Label -> obj.getPackageIdentifier() })
                .map<SkyKey?>(java.util.function.Function { pkg: PackageIdentifier? -> PackageLookupValue.key(pkg) as SkyKey? })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<SkyKey?>())
        val patchPackageLookupResult: SkyframeLookupResult = env.getValuesAndExceptions(patchPackageLookupKeys)
        if (env.valuesMissing()) {
            return null
        }
        val patchPaths: MutableList<RootedPath> = java.util.ArrayList<RootedPath>()
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */

        // Register dependencies on the patch files and ensure that they exist.
        val fileKeys: Iterable<Any?> =
            com.google.common.collect.Iterables.transform<RootedPath?, Any?>(patchPaths, FileValue::key)
        val fileValueResult: SkyframeLookupResult = env.getValuesAndExceptions(fileKeys)
        if (env.valuesMissing()) {
            return null
        }
        for (key in fileKeys) {
            val fileValue: FileValue? = fileValueResult.get(key) as FileValue?
            if (fileValue == null) {
                return null
            }
            if (!fileValue.isFile()) {
                throw errorf(
                    Code.BAD_MODULE,
                    "error reading single_version_override patch %s: is a directory or doesn't exist",
                    key.argument()
                )
            }
        }

        // Apply the patches to the module file only.
        val patchFs: InMemoryFileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        try {
            val moduleRoot: com.google.devtools.build.lib.vfs.Path = patchFs.getPath("/module")
            moduleRoot.createDirectoryAndParents()
            val moduleFilePath: com.google.devtools.build.lib.vfs.Path? = moduleRoot.getChild("MODULE.bazel")
            com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(moduleFilePath, moduleFile.getContent())
            for (patchPath in patchPaths) {
                try {
                    PatchUtil.applyToSingleFile(
                        patchPath.asPath(), override.patchStrip, moduleRoot, moduleFilePath
                    )
                } catch (e: PatchFailedException) {
                    throw errorf(
                        Code.BAD_MODULE,
                        "error applying single_version_override patch %s to module file: %s",
                        patchPath.asPath(),
                        e.getMessage()
                    )
                }
            }
            return ModuleFile.Companion.create(
                com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(moduleFilePath), moduleFile.getLocation()
            )
        } catch (e: IOException) {
            throw errorf(
                Code.BAD_MODULE,
                "error applying single_version_override patches to module file: %s",
                e.getMessage()
            )
        }
    }

    internal class ModuleFileFunctionException : SkyFunctionException {
        constructor(cause: ExternalDepsException?) : super(cause, Transience.TRANSIENT)

        constructor(cause: ExternalDepsException?, transience: Transience?) : super(cause, transience)
    }

    companion object {
        // Never empty.
        @kotlin.jvm.JvmField
        val REGISTRIES: Precomputed<com.google.common.collect.ImmutableSet<String?>?> =
            Precomputed<com.google.common.collect.ImmutableSet<String?>?>("registries")
        @kotlin.jvm.JvmField
        val IGNORE_DEV_DEPS: Precomputed<Boolean?> = Precomputed<Boolean?>("ignore_dev_dependency")

        @kotlin.jvm.JvmField
        val MODULE_OVERRIDES: Precomputed<MutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>?> =
            Precomputed<MutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>?>("module_overrides")
        @kotlin.jvm.JvmField
        val INJECTED_REPOSITORIES: Precomputed<com.google.common.collect.ImmutableMap<String?, PathFragment?>?> =
            Precomputed<com.google.common.collect.ImmutableMap<String?, PathFragment?>?>("repository_injections")

        private val BZLMOD_REMINDER: String = """
      ###############################################################################
      # Bazel now uses Bzlmod by default to manage external dependencies.
      # Please consider migrating your external dependencies from WORKSPACE to MODULE.bazel.
      #
      # For more details, please check https://github.com/bazelbuild/bazel/issues/18958
      ###############################################################################
      
      """.trimIndent()
        private const val INCLUDE_FILENAME_SUFFIX = ".MODULE.bazel"

        /**
         * Reads, parses, and compiles all included module files named by `horizon`, stores the
         * result in `includeLabelToCompiledModuleFile`, and finally returns the include statements
         * of these newly compiled module files as a new "horizon".
         */
        @Throws(ModuleFileFunctionException::class, java.lang.InterruptedException::class)
        private fun advanceHorizon(
            moduleKey: ModuleKey,
            includeLabelToCompiledModuleFile: SequencedMap<String?, CompiledModuleFile?>,
            horizon: com.google.common.collect.ImmutableList<IncludeStatement>,
            env: SkyFunction.Environment,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics,
            starlarkEnv: BazelStarlarkEnvironment?
        ): com.google.common.collect.ImmutableList<IncludeStatement>? {
            // Includes are only allowed in the root module as well as those with non-registry overrides, so
            // their repo name never contains a version.
            val repoContext: RepoContext =
                com.google.devtools.build.lib.cmdline.Label.RepoContext.of(
                    moduleKey.getCanonicalRepoNameWithoutVersion(),
                    com.google.devtools.build.lib.cmdline.RepositoryMapping.EMPTY
                )
            val includeLabels: java.util.ArrayList<com.google.devtools.build.lib.cmdline.Label> =
                java.util.ArrayList<com.google.devtools.build.lib.cmdline.Label>(horizon.size())
            for (includeStatement in horizon) {
                if (!includeStatement.includeLabel.startsWith("//")) {
                    throw errorf(
                        Code.BAD_MODULE,
                        "bad include label '%s' at %s: include() must be called with repo-relative labels "
                                + "(starting with double slashes)",
                        includeStatement.includeLabel,
                        includeStatement.location
                    )
                }
                val includeLabel: com.google.devtools.build.lib.cmdline.Label
                try {
                    includeLabel = com.google.devtools.build.lib.cmdline.Label.parseWithRepoContext(
                        includeStatement.includeLabel,
                        repoContext
                    )
                } catch (e: LabelSyntaxException) {
                    throw errorf(
                        Code.BAD_MODULE,
                        "bad include label '%s' at %s: %s",
                        includeStatement.includeLabel,
                        includeStatement.location,
                        e.getMessage()
                    )
                }
                val basename: String =
                    includeLabel.getName().substring(includeLabel.getName().lastIndexOf('/'.code) + 1)
                if (!basename.endsWith(INCLUDE_FILENAME_SUFFIX)) {
                    throw errorf(
                        Code.BAD_MODULE,
                        "bad include label '%s' at %s: the file to be included must have a name ending in"
                                + " '%s'",
                        includeStatement.includeLabel,
                        includeStatement.location,
                        INCLUDE_FILENAME_SUFFIX
                    )
                }
                if (basename.startsWith(".")) {
                    throw errorf(
                        Code.BAD_MODULE,
                        "bad include label '%s' at %s: the name of the file to be included must not start"
                                + " with '.'",
                        includeStatement.includeLabel,
                        includeStatement.location
                    )
                }
                includeLabels.add(includeLabel)
            }
            var result: SkyframeLookupResult =
                env.getValuesAndExceptions(
                    includeLabels.stream()
                        .map<SkyKey?>(java.util.function.Function { l: com.google.devtools.build.lib.cmdline.Label? ->
                            PackageLookupValue.key(
                                l.getPackageIdentifier()
                            ) as SkyKey?
                        })
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
                )
            val rootedPaths: java.util.ArrayList<RootedPath?> = java.util.ArrayList<RootedPath?>(horizon.size())
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            result =
                env.getValuesAndExceptions(
                    rootedPaths.stream().map<Any?>(FileValue::key)
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
                )
            val newHorizon: com.google.common.collect.ImmutableList.Builder<IncludeStatement?> =
                com.google.common.collect.ImmutableList.builder<IncludeStatement?>()
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return newHorizon.build()
        }

        fun getModuleFilePath(workspaceRoot: com.google.devtools.build.lib.vfs.Path?): RootedPath {
            return RootedPath.toRootedPath(
                Root.fromPath(workspaceRoot), LabelConstants.MODULE_DOT_BAZEL_FILE_NAME
            )
        }

        @Throws(ModuleFileFunctionException::class)
        fun buildRootModuleFileValue(
            moduleThreadContext: ModuleThreadContext,
            includeLabelToCompiledModuleFile: com.google.common.collect.ImmutableMap<String?, CompiledModuleFile?>,
            commandOverrides: MutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>,
            eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler
        ): RootModuleFileValue {
            val module: InterimModule
            try {
                module = moduleThreadContext.buildModule( /* registry= */null)
            } catch (e: net.starlark.java.eval.EvalException) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        e.getInnermostLocation(),
                        e.getMessageWithStack()
                    )
                )
                throw errorf(Code.BAD_MODULE, "error executing MODULE.bazel file for the root module")
            }
            for (usage in module.getExtensionUsages()) {
                val firstProxy: com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy =
                    usage.getProxies().getFirst()
                if (usage.getIsolationKey().isPresent() && firstProxy.getImports().isEmpty()) {
                    throw errorf(
                        Code.BAD_MODULE,
                        ("the isolated usage at %s of extension %s defined in %s has no effect as no "
                                + "repositories are imported from it. Either import one or more repositories "
                                + "generated by the extension with use_repo or remove the usage."),
                        firstProxy.getLocation(),
                        usage.getExtensionName(),
                        usage.getExtensionBzlFile()
                    )
                }
            }

            val moduleOverrides: com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?> =
                moduleThreadContext.buildOverrides()
            val overrides: com.google.common.collect.ImmutableMap<String, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?> =
                com.google.common.collect.ImmutableMap.builder<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>()
                    .putAll(moduleOverrides)
                    .putAll(commandOverrides)
                    .buildKeepingLast()
            for (entry in overrides.entrySet()) {
                val moduleName: String = entry.getKey()
                if (entry.getValue() !is SingleVersionOverride || !module.getDeps().containsKey(moduleName)) {
                    continue
                }
                val depVersion: com.google.devtools.build.lib.bazel.bzlmod.Version =
                    module.getDeps().get(moduleName).version
                if (!depVersion.isEmpty() && svo.version.compareTo(depVersion) < 0) {
                    throw errorf(
                        Code.BAD_MODULE,
                        "module '%s' is overridden to use version '%s', which is lower than the version '%s' "
                                + "requested by the root module",
                        moduleName,
                        svo.version,
                        depVersion
                    )
                }
            }

            // Check that overrides don't contain the root module itself.
            val rootOverride: com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride? =
                overrides.get(module.getName())
            if (rootOverride != null) {
                throw errorf(Code.BAD_MODULE, "invalid override for the root module found: %s", rootOverride)
            }
            val nonRegistryOverrideModuleToRepoName: com.google.common.collect.ImmutableMap<String?, String?>? =
                module.getDeps().entrySet().stream()
                    .filter(java.util.function.Predicate { dep: MutableMap.MutableEntry<String?, ModuleKey?>? ->
                        overrides.get(
                            dep.getValue().name
                        ) is NonRegistryOverride
                    })
            TODO(
                """
                |Cannot convert element
                |With text:
                |collect(<Map.Entry<String, ModuleKey>, String, String>toImmutableMap(dep -> dep.getValue().name(), Map.Entry::getKey)
                """.trimMargin()
            )

            val nonRegistryOverrideCanonicalRepoToModuleName: com.google.common.collect.ImmutableMap<RepositoryName?, String?>? =
                com.google.common.collect.Maps.filterValues<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>(
                    overrides,
                    com.google.common.base.Predicate { override: com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride? -> override is NonRegistryOverride })
                    .keySet()
                    .stream()
                    .collect()
            TODO(
                """
                |Cannot convert element
                |With text:
                |String, RepositoryName, String>toImmutableMap(
                |                    // A module with a non-registry override always has a unique version across the
                |                    // entire dep graph.
                |                    name -> new ModuleKey(name, Version.EMPTY).getCanonicalRepoNameWithoutVersion(),
                |                    name -> name)
                """.trimMargin()
            )

            val moduleFilePaths: com.google.common.collect.ImmutableSet<PathFragment?> =
                java.util.stream.Stream.concat<PathFragment?>(
                    java.util.stream.Stream.of<PathFragment?>(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME),
                    includeLabelToCompiledModuleFile.keySet().stream()
                        .map<PathFragment?>(java.util.function.Function { label: String? ->
                            com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked(
                                label
                            ).toPathFragment()
                        })
                )
                    .collect(TODO("Cannot convert element"))<PathFragment> com . google . common . collect . ImmutableSet . toImmutableSet < kotlin . Any ? > ()

            return RootModuleFileValue(
                module,
                overrides,
                nonRegistryOverrideCanonicalRepoToModuleName,
                nonRegistryOverrideModuleToRepoName,
                moduleFilePaths
            )
        }

        @Throws(ModuleFileFunctionException::class, java.lang.InterruptedException::class)
        private fun execModuleFile(
            compiledRootModuleFile: CompiledModuleFile,
            includeLabelToParsedModuleFile: com.google.common.collect.ImmutableMap<String?, CompiledModuleFile?>?,
            moduleKey: ModuleKey,
            ignoreDevDeps: Boolean,
            builtinModules: com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>?,
            injectedRepositories: MutableMap<String?, PathFragment?>,
            printIsNoop: Boolean,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
            eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
            symbolGenerator: net.starlark.java.eval.SymbolGenerator<*>?
        ): ModuleThreadContext {
            val context: ModuleThreadContext =
                ModuleThreadContext(
                    builtinModules, moduleKey, ignoreDevDeps, includeLabelToParsedModuleFile
                )
            try {
                com.google.devtools.build.lib.profiler.Profiler.instance()
                    .profile(
                        com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD,
                        java.util.function.Supplier { "evaluate module file: " + moduleKey }).use { c ->
                        net.starlark.java.eval.Mutability.create("module file", moduleKey).use { mu ->
                            val thread: net.starlark.java.eval.StarlarkThread =
                                net.starlark.java.eval.StarlarkThread.create(
                                    mu,
                                    starlarkSemantics,
                                    "MODULE.bazel file of " + moduleKey.toDisplayString(),
                                    symbolGenerator
                                )
                            context.storeInThread(thread)
                            if (printIsNoop) {
                                thread.setPrintHandler(net.starlark.java.eval.StarlarkThread.PrintHandler { t: net.starlark.java.eval.StarlarkThread?, msg: String? -> })
                            } else {
                                thread.setPrintHandler(
                                    com.google.devtools.build.lib.events.Event.makeDebugPrintHandler(
                                        eventHandler
                                    )
                                )
                            }
                            thread.setPostAssignHook(
                                net.starlark.java.eval.StarlarkThread.PostAssignHook { name: String?, nameStartLocation: net.starlark.java.syntax.Location?, value: Any? ->
                                    if (value is StarlarkExportable) {
                                        if (!value.isExported()) {
                                            value.export(eventHandler, null, name, nameStartLocation)
                                        }
                                    }
                                })

                            compiledRootModuleFile.runOnThread(thread)
                            injectRepos(injectedRepositories, context, thread)
                            for (warning in context.getWarnings()) {
                                eventHandler.handle(warning)
                            }
                        }
                    }
            } catch (e: net.starlark.java.eval.EvalException) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.error(
                        e.getInnermostLocation(),
                        e.getMessageWithStack()
                    )
                )
                throw errorf(Code.BAD_MODULE, "error executing MODULE.bazel file for %s", moduleKey)
            }
            return context
        }

        // Adds a local_repository for each repository injected via --injected_repositories.
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun injectRepos(
            injectedRepositories: MutableMap<String?, PathFragment?>,
            context: ModuleThreadContext,
            thread: net.starlark.java.eval.StarlarkThread
        ) {
            if (injectedRepositories.isEmpty()) {
                return
            }
            // Use the innate extension backing use_repo_rule.
            val usageBuilder: ModuleExtensionUsageBuilder =
                context.getOrCreateExtensionUsageBuilder(
                    "//:MODULE.bazel",
                    "@bazel_tools//tools/build_defs/repo:local.bzl local_repository",  /* isolate= */
                    false
                )
            val extensionProxy: ModuleExtensionProxy =
                ModuleExtensionProxy(
                    usageBuilder,
                    com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Companion.builder()
                        .setDevDependency(true)
                        .setLocation(net.starlark.java.syntax.Location.BUILTIN)
                        .setContainingModuleFilePath(context.getCurrentModuleFilePath())
                )
            for (injectedRepository in injectedRepositories.entrySet()) {
                extensionProxy
                    .getValue("repo")
                    .call(
                        net.starlark.java.eval.Dict.copyOf<String?, Any?>(
                            thread.mutability(),
                            com.google.common.collect.ImmutableMap.of<String?, String?>(
                                "name", injectedRepository.getKey(),
                                "path", injectedRepository.getValue().getPathString()
                            )
                        ),
                        thread
                    )
                extensionProxy.addImport(
                    injectedRepository.getKey(),
                    injectedRepository.getKey(),
                    "by --inject_repository",
                    thread.getCallStack()
                )
            }
        }

        @Throws(ModuleFileFunctionException::class)
        private fun readModuleFile(path: com.google.devtools.build.lib.vfs.Path): ByteArray {
            try {
                return com.google.devtools.build.lib.vfs.FileSystemUtils.readWithKnownFileSize(path, path.getFileSize())
            } catch (e: IOException) {
                throw errorf(
                    Code.MODULE_NOT_FOUND,
                    "MODULE.bazel expected but not found at %s: %s",
                    path,
                    e.getMessage()
                )
            }
        }

        @Throws(ModuleFileFunctionException::class)
        private fun createModuleFile(path: com.google.devtools.build.lib.vfs.Path?, bytes: ByteArray?) {
            try {
                com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(path, bytes)
            } catch (e: IOException) {
                throw errorf(
                    Code.EXTERNAL_DEPS_UNKNOWN,
                    "MODULE.bazel cannot be created at %s: %s",
                    path,
                    e.getMessage()
                )
            }
        }

        @com.google.errorprone.annotations.FormatMethod
        private fun errorf(code: Code?, format: String?, vararg args: Any?): ModuleFileFunctionException {
            return ModuleFileFunctionException(ExternalDepsException.Companion.withMessage(code, format, *args))
        }

        @com.google.errorprone.annotations.FormatMethod
        private fun errorf(
            code: Code?, cause: Throwable?, format: String?, vararg args: Any?
        ): ModuleFileFunctionException {
            return ModuleFileFunctionException(
                ExternalDepsException.Companion.withCauseAndMessage(code, cause, format, *args)
            )
        }

        @kotlin.jvm.JvmStatic
        fun getBuiltinModules(): com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?> {
            return com.google.common.collect.ImmutableMap.of<String?, NonRegistryOverride?>(
                "bazel_tools",
                NonRegistryOverride.Companion.BAZEL_TOOLS_OVERRIDE
            )
        }
    }
}
