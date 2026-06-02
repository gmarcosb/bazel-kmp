// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.commands

import com.google.common.base.Preconditions
import com.google.common.collect.*
import com.google.common.io.CharSource
import com.google.devtools.build.lib.analysis.NoBuildEvent
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions
import com.google.devtools.build.lib.cmdline.RepositoryMapping
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.build.lib.profiler.ProfilerTask
import com.google.devtools.build.lib.runtime.Command
import com.google.devtools.build.lib.shell.CommandException
import com.google.devtools.build.skyframe.EvaluationContext
import com.google.devtools.common.options.OptionsParsingException
import com.google.devtools.common.options.OptionsParsingResult
import java.io.Writer
import java.lang.String
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Function
import java.util.function.IntFunction
import kotlin.Boolean
import kotlin.Exception
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.arrayOf
import kotlin.toString

/** Queries the Bzlmod external dependency graph.  */
@Command(
    name = ModCommand.Companion.NAME,
    buildPhase = BuildPhase.LOADS,
    options = [CoreOptions::class // for --action_env, which affects the repo env
        , ModOptions::class, PackageOptions::class, LoadingPhaseThreadsOption::class
    ],
    help = "resource:mod.txt",
    shortDescription = "Queries the Bzlmod external dependency graph",
    allowResidue = true
)
class ModCommand : BlazeCommand {
    override fun exec(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult {
        env.getEventBus()
            .post(
                NoBuildEvent(
                    env.getCommandName(),
                    env.getCommandStartTime(),  /* separateFinishedEvent= */
                    true,  /* showProgress= */
                    true,  /* id= */
                    null
                )
            )
        val result: BlazeCommandResult = execInternal(env, options)
        env.getEventBus()
            .post(
                NoBuildRequestFinishedEvent(
                    result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()
                )
            )
        return result
    }

    @Throws(InvalidArgumentException::class)
    private fun validateArgs(subcommand: ModSubcommand, modOptions: ModOptions, args: MutableList<String>) {
        // Validate output format.

        when (subcommand) {
            ModSubcommand.SHOW_REPO -> {
                when (modOptions.getOutputFormat()) {
                    ModOptions.OutputFormat.TEXT, ModOptions.OutputFormat.STREAMED_JSONPROTO, ModOptions.OutputFormat.STREAMED_PROTO -> {}
                    else -> throw InvalidArgumentException(
                        String.format(
                            "Invalid --output '%s' for the 'show_repo' subcommand. Only 'text',"
                                    + " 'streamed_jsonproto', and 'streamed_proto' are supported.",
                            modOptions.getOutputFormat()
                        ),
                        Code.INVALID_ARGUMENTS
                    )
                }
            }

            ModSubcommand.SHOW_EXTENSION -> {
                if (modOptions.getOutputFormat() != ModOptions.OutputFormat.TEXT) {
                    throw InvalidArgumentException(
                        String.format(
                            "Invalid --output '%s' for the 'show_extension' subcommand. Only 'text' is"
                                    + " supported.",
                            modOptions.getOutputFormat()
                        ),
                        Code.INVALID_ARGUMENTS
                    )
                }
            }

            -> {
                when (modOptions.getOutputFormat()) {
                    ModOptions.OutputFormat.TEXT, ModOptions.OutputFormat.JSON, ModOptions.OutputFormat.GRAPH -> {}
                    else -> throw InvalidArgumentException(
                        String.format(
                            "Invalid --output '%s' for the '%s' subcommand. "
                                    + "Only 'text', 'json', and 'graph' are supported.",
                            modOptions.getOutputFormat(), sub
                        ),
                        Code.INVALID_ARGUMENTS
                    )
                }
            }

            else -> {}
        }

        if (subcommand == ModSubcommand.SHOW_REPO) {
            var selectedModes = 0
            if (modOptions.getAllRepos()) {
                selectedModes++
            }
            if (modOptions.getAllVisibleRepos()) {
                selectedModes++
            }
            if (!args.isEmpty()) {
                selectedModes++
            }
            if (selectedModes > 1) {
                throw InvalidArgumentException(
                    "the 'show_repo' command requires exactly one of --all_repos, --all_visible_repos, or a"
                            + " list of repo arguments",
                    Code.TOO_MANY_ARGUMENTS
                )
            }
        } else {
            if (modOptions.getAllRepos()) {
                throw InvalidArgumentException(
                    String.format("the '%s' command doesn't take the --all_repos option", subcommand),
                    Code.INVALID_ARGUMENTS
                )
            }
            if (modOptions.getAllVisibleRepos()) {
                throw InvalidArgumentException(
                    String.format(
                        "the '%s' command doesn't take the --all_visible_repos option", subcommand
                    ),
                    Code.INVALID_ARGUMENTS
                )
            }
        }
    }

    private fun execInternal(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult {
        val modOptions: ModOptions? = options.getOptions<ModOptions?>(ModOptions::class.java)
        Preconditions.checkArgument(modOptions != null)

        if (options.getResidue().isEmpty()) {
            val errorMessage =
                String.format(
                    "No subcommand specified, choose one of : %s.", ModSubcommand.Companion.printValues()
                )
            return reportAndCreateFailureResult(env, errorMessage, Code.MOD_COMMAND_UNKNOWN)
        }

        // The first element in the residue must be the subcommand, and then comes a list of arguments.
        val subcommandStr = options.getResidue().get(0)
        val subcommand: ModSubcommand
        try {
            subcommand = ModSubcommandConverter().convert(subcommandStr)
        } catch (e: OptionsParsingException) {
            val errorMessage =
                String.format("Invalid subcommand, choose one from : %s.", ModSubcommand.Companion.printValues())
            return reportAndCreateFailureResult(env, errorMessage, Code.MOD_COMMAND_UNKNOWN)
        }
        val args = options.getResidue().subList(1, options.getResidue().size())

        // Validate and parse args as early as possible, so we don't have to
        // wait for Skyframe evaluations to happen before failing due to a simple error.
        try {
            validateArgs(subcommand, modOptions, args)
        } catch (e: InvalidArgumentException) {
            return reportAndCreateFailureResult(env, e.getMessage(), e.getCode())
        }

        val repositoryMappingKeysBuilder: ImmutableList.Builder<RepositoryMappingValue.Key?> =
            ImmutableList.builder<RepositoryMappingValue.Key?>()
        if (subcommand == ModSubcommand.DUMP_REPO_MAPPING) {
            if (args.isEmpty()) {
                // Make this case an error so that we are free to add a mode that emits all mappings in a
                // single JSON object later.
                return reportAndCreateFailureResult(
                    env, "No repository name(s) specified", Code.INVALID_ARGUMENTS
                )
            }
            for (arg in args) {
                try {
                    repositoryMappingKeysBuilder.add(RepositoryMappingValue.key(RepositoryName.create(arg)))
                } catch (e: LabelSyntaxException) {
                    return reportAndCreateFailureResult(env, e.getMessage(), Code.INVALID_ARGUMENTS)
                }
            }
        }
        val repoMappingKeys: ImmutableList<RepositoryMappingValue.Key?> =
            repositoryMappingKeysBuilder.build()

        val depGraphValue: BazelDepGraphValue
        val moduleInspector: BazelModuleInspectorValue?
        val modTidyValue: BazelModTidyValue?
        val repoMappingValues: ImmutableList<RepositoryMappingValue>?

        val skyframeExecutor: SkyframeExecutor = env.getSkyframeExecutor()
        val threadsOption: LoadingPhaseThreadsOption? =
            options.getOptions<LoadingPhaseThreadsOption?>(LoadingPhaseThreadsOption::class.java)

        val evaluationContext =
            EvaluationContext.newBuilder()
                .setParallelism(threadsOption.getThreads())
                .setEventHandler(env.getReporter())
                .build()

        try {
            env.syncPackageLoading(options)

            val keys: ImmutableSet.Builder<SkyKey?> = ImmutableSet.builder<SkyKey?>()
            if (subcommand == ModSubcommand.DUMP_REPO_MAPPING) {
                keys.addAll(repoMappingKeys)
            } else if (subcommand == ModSubcommand.TIDY) {
                keys.add(BazelModTidyValue.Companion.KEY)
            } else {
                keys.add(BazelDepGraphValue.Companion.KEY, BazelModuleInspectorValue.Companion.KEY)
            }
            val evaluationResult: EvaluationResult<SkyValue?> =
                skyframeExecutor.prepareAndGet(keys.build(), evaluationContext)

            if (evaluationResult.hasError()) {
                val cycleInfo: ImmutableList<CycleInfo?> = evaluationResult.getError().getCycleInfo()
                if (!cycleInfo.isEmpty()) {
                    // We don't expect target-level cycles here, so restrict to the subset of reporters that
                    // are relevant for the (conceptual) loading phase.
                    CyclesReporter(BzlmodRepoCycleReporter(), BzlLoadCycleReporter())
                        .reportCycles(cycleInfo, cycleInfo.getFirst().getTopKey(), env.getReporter())
                }
                val e: Exception? = evaluationResult.getError().getException()
                var message = "Unexpected error during module graph evaluation."
                if (e != null) {
                    message = e.getMessage()
                }
                return reportAndCreateFailureResult(env, message, Code.MOD_COMMAND_UNKNOWN)
            }

            depGraphValue = evaluationResult.get(BazelDepGraphValue.Companion.KEY) as BazelDepGraphValue

            moduleInspector =
                evaluationResult.get(BazelModuleInspectorValue.Companion.KEY) as BazelModuleInspectorValue?

            modTidyValue = evaluationResult.get(BazelModTidyValue.Companion.KEY) as BazelModTidyValue?

            repoMappingValues =
                repoMappingKeys.stream()
                    .map<SkyValue?>(Function { key: RepositoryMappingValue.Key? -> evaluationResult.get(key) })
                    .map<RepositoryMappingValue?>(Function { obj: SkyValue? ->
                        RepositoryMappingValue::class.java.cast(
                            obj
                        )
                    })
                    .collect(ImmutableList.toImmutableList<RepositoryMappingValue?>())
        } catch (e: InterruptedException) {
            val errorMessage = "mod command interrupted: " + e.getMessage()
            env.getReporter().handle(Event.error(errorMessage))
            return BlazeCommandResult.detailedExitCode(
                InterruptedFailureDetails.detailedExitCode(errorMessage)
            )
        } catch (e: AbruptExitException) {
            env.getReporter().handle(Event.error(null, "Unknown error: " + e.getMessage()))
            return BlazeCommandResult.detailedExitCode(e.getDetailedExitCode())
        }

        // Handle commands that do not require BazelModuleInspectorValue.
        if (subcommand == ModSubcommand.DUMP_REPO_MAPPING) {
            val missingRepos: kotlin.String =
                IntStream.range(0, repoMappingKeys.size())
                    .filter(IntPredicate { i: Int -> repoMappingValues.get(i) === RepositoryMappingValue.NOT_FOUND_VALUE })
                    .mapToObj<RepositoryMappingValue.Key?>(IntFunction { index: Int -> repoMappingKeys.get(index) })
                    .map<RepositoryName?>(Function { RepositoryMappingValue.Key.repoName() })
                    .map<kotlin.String?>(Function { obj: RepositoryName? -> obj.getName() })
                    .collect(Collectors.joining(", "))
            if (!missingRepos.isEmpty()) {
                return reportAndCreateFailureResult(
                    env, "Repositories not found: " + missingRepos, Code.INVALID_ARGUMENTS
                )
            }
            try {
                Profiler.instance().profile(ProfilerTask.BZLMOD, "execute mod " + subcommand).use { c ->
                    dumpRepoMappings(
                        repoMappingValues,
                        OutputStreamWriter(
                            env.getReporter().getOutErr().getOutputStream(),
                            if (modOptions.getCharset() == ModOptions.Charset.UTF8) StandardCharsets.UTF_8 else StandardCharsets.US_ASCII
                        )
                    )
                }
            } catch (e: IOException) {
                throw IllegalStateException(e)
            }
            return BlazeCommandResult.success()
        } else if (subcommand == ModSubcommand.TIDY) {
            // tidy doesn't take extra arguments.
            if (!args.isEmpty()) {
                return reportAndCreateFailureResult(
                    env, "the 'tidy' command doesn't take extra arguments", Code.TOO_MANY_ARGUMENTS
                )
            }
            Profiler.instance().profile(ProfilerTask.BZLMOD, "execute mod " + subcommand).use { c ->
                return runTidy(env, modTidyValue)
            }
        }

        // Extract and check the --base_module argument first to use it when parsing the other args.
        // Can only be a TargetModule or a repoName relative to the ROOT.
        val baseModuleKey: ModuleKey?
        val rootModule: AugmentedModule? = moduleInspector.depGraph.get(ModuleKey.Companion.ROOT)
        try {
            val keys: ImmutableSet<ModuleKey?> =
                modOptions
                    .getBaseModule()
                    .resolveToModuleKeys(
                        moduleInspector.modulesIndex,
                        moduleInspector.depGraph,
                        moduleInspector.moduleKeyToCanonicalNames,
                        rootModule.deps,
                        rootModule.unusedDeps,
                        false,
                        false
                    )
            if (keys.size() > 1) {
                throw InvalidArgumentException(
                    String.format(
                        "The --base_module option can only specify exactly one module version, choose one"
                                + " of: %s.",
                        keys.stream().map<kotlin.String?>(Function { obj: ModuleKey? -> obj.toString() })
                            .collect(Collectors.joining(", "))
                    ),
                    Code.INVALID_ARGUMENTS
                )
            }
            baseModuleKey = Iterables.getOnlyElement<ModuleKey?>(keys)
        } catch (e: InvalidArgumentException) {
            return reportAndCreateFailureResult(
                env,
                String.format(
                    "In --base_module %s option: %s (Note that unused modules cannot be used here)",
                    modOptions.getBaseModule(), e.getMessage()
                ),
                Code.INVALID_ARGUMENTS
            )
        }

        // The args can have different types depending on the subcommand, so create multiple containers
        // which can be filled accordingly.
        var argsAsModules: ImmutableSet<ModuleKey?>? = null
        var argsAsExtensions: ImmutableSortedSet<ModuleExtensionId?>? = null
        var argsAsRepos: ImmutableMap<kotlin.String?, RepositoryName?>? = null

        val baseModule: AugmentedModule =
            Objects.requireNonNull<AugmentedModule>(moduleInspector.depGraph.get(baseModuleKey))
        val baseModuleMapping: RepositoryMapping = depGraphValue.getFullRepoMapping(baseModuleKey)
        try {
            when (subcommand) {
                ModSubcommand.GRAPH -> {
                    // GRAPH doesn't take extra arguments.
                    if (!args.isEmpty()) {
                        throw InvalidArgumentException(
                            "the 'graph' command doesn't take extra arguments", Code.TOO_MANY_ARGUMENTS
                        )
                    }
                }

                ModSubcommand.SHOW_REPO -> {
                    argsAsRepos =
                        getReposToShow(modOptions, moduleInspector, depGraphValue, baseModuleMapping, args)
                }

                ModSubcommand.SHOW_EXTENSION -> {
                    val extensionsBuilder: ImmutableSortedSet.Builder<ModuleExtensionId?> =
                        ImmutableSortedSet.Builder<ModuleExtensionId?>(ModuleExtensionId.Companion.LEXICOGRAPHIC_COMPARATOR)
                    for (arg in args) {
                        try {
                            extensionsBuilder.add(
                                ExtensionArgConverter.Companion.INSTANCE
                                    .convert(arg)
                                    .resolveToExtensionId(
                                        moduleInspector.modulesIndex,
                                        moduleInspector.depGraph,
                                        moduleInspector.moduleKeyToCanonicalNames,
                                        baseModule.deps,
                                        baseModule.unusedDeps
                                    )
                            )
                        } catch (e: InvalidArgumentException) {
                            throw InvalidArgumentException(
                                String.format("In extension argument %s: %s", arg, e.getMessage()),
                                Code.INVALID_ARGUMENTS,
                                e
                            )
                        } catch (e: OptionsParsingException) {
                            throw InvalidArgumentException(
                                String.format("In extension argument %s: %s", arg, e.getMessage()),
                                Code.INVALID_ARGUMENTS,
                                e
                            )
                        }
                    }
                    argsAsExtensions = extensionsBuilder.build()
                }

                else -> {
                    val keysBuilder: ImmutableSet.Builder<ModuleKey?> = ImmutableSet.Builder<ModuleKey?>()
                    for (arg in args) {
                        try {
                            keysBuilder.addAll(
                                ModuleArgConverter.Companion.INSTANCE
                                    .convert(arg)
                                    .resolveToModuleKeys(
                                        moduleInspector.modulesIndex,
                                        moduleInspector.depGraph,
                                        moduleInspector.moduleKeyToCanonicalNames,
                                        baseModule.deps,
                                        baseModule.unusedDeps,
                                        modOptions.getIncludeUnused(),  /* warnUnused= */
                                        true
                                    )
                            )
                        } catch (e: InvalidArgumentException) {
                            throw InvalidArgumentException(
                                String.format("In module argument %s: %s", arg, e.getMessage()),
                                Code.INVALID_ARGUMENTS
                            )
                        } catch (e: OptionsParsingException) {
                            throw InvalidArgumentException(
                                String.format("In module argument %s: %s", arg, e.getMessage()),
                                Code.INVALID_ARGUMENTS
                            )
                        }
                    }
                    argsAsModules = keysBuilder.build()
                }
            }
        } catch (e: InvalidArgumentException) {
            return reportAndCreateFailureResult(env, e.getMessage(), e.getCode())
        }
        /* Extract and check the --from and --extension_usages argument */
        val fromKeys: ImmutableSet<ModuleKey?>?
        val usageKeys: ImmutableSet<ModuleKey?>?
        try {
            fromKeys =
                moduleArgListToKeys(
                    modOptions.getModulesFrom(),
                    moduleInspector.modulesIndex,
                    moduleInspector.depGraph,
                    moduleInspector.moduleKeyToCanonicalNames,
                    baseModule.deps,
                    baseModule.unusedDeps,
                    modOptions.getIncludeUnused()
                )
        } catch (e: InvalidArgumentException) {
            return reportAndCreateFailureResult(
                env,
                String.format("In --from %s option: %s", modOptions.getModulesFrom(), e.getMessage()),
                Code.INVALID_ARGUMENTS
            )
        }

        try {
            usageKeys =
                moduleArgListToKeys(
                    modOptions.getExtensionUsages(),
                    moduleInspector.modulesIndex,
                    moduleInspector.depGraph,
                    moduleInspector.moduleKeyToCanonicalNames,
                    baseModule.deps,
                    baseModule.unusedDeps,
                    modOptions.getIncludeUnused()
                )
        } catch (e: InvalidArgumentException) {
            return reportAndCreateFailureResult(
                env,
                String.format(
                    "In --extension_usages %s option: %s (Note that unused modules cannot be used"
                            + " here)",
                    modOptions.getExtensionUsages(), e.getMessage()
                ),
                Code.INVALID_ARGUMENTS
            )
        }

        /* Extract and check the --extension_filter argument */
        var filterExtensions: Optional<MaybeCompleteSet<ModuleExtensionId?>?> =
            Optional.empty<MaybeCompleteSet<ModuleExtensionId?>?>()
        if (subcommand.isGraph() && modOptions.getExtensionFilter() != null) {
            if (modOptions.getExtensionFilter().isEmpty()) {
                filterExtensions =
                    Optional.of<MaybeCompleteSet<ModuleExtensionId?>?>(MaybeCompleteSet.completeSet<ModuleExtensionId?>())
            } else {
                try {
                    filterExtensions =
                        Optional.of<MaybeCompleteSet<ModuleExtensionId?>?>(
                            MaybeCompleteSet.copyOf<ModuleExtensionId?>(
                                extensionArgListToIds(
                                    modOptions.getExtensionFilter(),
                                    moduleInspector.modulesIndex,
                                    moduleInspector.depGraph,
                                    moduleInspector.moduleKeyToCanonicalNames,
                                    baseModule.deps,
                                    baseModule.unusedDeps
                                )
                            )
                        )
                } catch (e: InvalidArgumentException) {
                    return reportAndCreateFailureResult(
                        env,
                        String.format(
                            "In --extension_filter %s option: %s",
                            modOptions.getExtensionFilter(), e.getMessage()
                        ),
                        Code.INVALID_ARGUMENTS
                    )
                }
            }
        }

        var targetRepoDefinitions: ImmutableMap<kotlin.String?, RepoDefinitionValue?>? = null
        try {
            if (subcommand == ModSubcommand.SHOW_REPO) {
                val skyKeys: ImmutableSet<SkyKey?> =
                    argsAsRepos.values().stream()
                        .map<RepoDefinitionValue.Key?>(Function { repositoryName: RepositoryName? ->
                            RepoDefinitionValue.key(repositoryName)
                        }).collect(
                            ImmutableSet.toImmutableSet<SkyKey?>()
                        )
                val result: EvaluationResult<SkyValue?> =
                    env.getSkyframeExecutor().prepareAndGet(skyKeys, evaluationContext)
                if (result.hasError()) {
                    val e: Exception? = result.getError().getException()
                    var message = "Unexpected error during repository rule evaluation."
                    if (e != null) {
                        message = e.getMessage()
                    }
                    return reportAndCreateFailureResult(env, message, Code.INVALID_ARGUMENTS)
                }
                val resultBuilder: ImmutableMap.Builder<kotlin.String?, RepoDefinitionValue?> =
                    ImmutableMap.builderWithExpectedSize<kotlin.String?, RepoDefinitionValue?>(argsAsRepos.size())
                for (e in argsAsRepos.entrySet()) {
                    val value: SkyValue? = result.get(RepoDefinitionValue.key(e.getValue()))
                    if (value === RepoDefinitionValue.NOT_FOUND) {
                        return reportAndCreateFailureResult(
                            env,
                            String.format("In repo argument %s: no such repo", e.getKey()),
                            Code.INVALID_ARGUMENTS
                        )
                    }
                    resultBuilder.put(e.getKey(), value as RepoDefinitionValue?)
                }
                targetRepoDefinitions = resultBuilder.buildOrThrow()
            }
        } catch (e: InterruptedException) {
            val errorMessage = "mod command interrupted: " + e.getMessage()
            env.getReporter().handle(Event.error(errorMessage))
            return BlazeCommandResult.detailedExitCode(
                InterruptedFailureDetails.detailedExitCode(errorMessage)
            )
        }

        // Workaround to allow different default value for DEPS and EXPLAIN, and also use
        // Integer.MAX_VALUE instead of the exact number string.
        if (modOptions.getDepth() < 1) {
            modOptions.setDepth(
                when (subcommand) {
                    ModSubcommand.EXPLAIN -> 1
                    ModSubcommand.DEPS -> 2
                    else -> Integer.MAX_VALUE
                }
            )
        }

        val modExecutor: ModExecutor =
            ModExecutor(
                moduleInspector.depGraph,
                depGraphValue.getExtensionUsagesTable(),
                moduleInspector.extensionToRepoInternalNames,
                filterExtensions,
                modOptions,
                env.getReporter().getOutErr().getOutputStream()
            )

        try {
            Profiler.instance().profile(ProfilerTask.BZLMOD, "execute mod " + subcommand).use { c ->
                when (subcommand) {
                    ModSubcommand.GRAPH -> modExecutor.graph(fromKeys)
                    ModSubcommand.DEPS -> modExecutor.graph(argsAsModules)
                    ModSubcommand.PATH -> modExecutor.path(fromKeys, argsAsModules)
                    ModSubcommand.ALL_PATHS, ModSubcommand.EXPLAIN -> modExecutor.allPaths(fromKeys, argsAsModules)
                    ModSubcommand.SHOW_REPO -> modExecutor.showRepo(targetRepoDefinitions)
                    ModSubcommand.SHOW_EXTENSION -> modExecutor.showExtension(argsAsExtensions, usageKeys)
                    else -> throw IllegalStateException("Unexpected subcommand: " + subcommand)
                }
            }
        } catch (e: InvalidArgumentException) {
            return reportAndCreateFailureResult(env, e.getMessage(), Code.INVALID_ARGUMENTS)
        }

        if (moduleInspector.errors.isEmpty()) {
            return BlazeCommandResult.success()
        } else {
            return reportAndCreateFailureResult(
                env,
                String.format(
                    "Results may be incomplete as %d extension%s failed.",
                    moduleInspector.errors.size(), if (moduleInspector.errors.size() == 1) "" else "s"
                ),
                Code.ERROR_DURING_GRAPH_INSPECTION
            )
        }
    }

    @Throws(InvalidArgumentException::class)
    private fun getReposToShow(
        modOptions: ModOptions,
        moduleInspector: BazelModuleInspectorValue,
        depGraphValue: BazelDepGraphValue,
        baseModuleMapping: RepositoryMapping,
        args: MutableList<kotlin.String>
    ): ImmutableMap<kotlin.String?, RepositoryName?> {
        val targetToRepoName: ImmutableMap.Builder<kotlin.String?, RepositoryName?> =
            ImmutableMap.Builder<kotlin.String?, RepositoryName?>()

        if (modOptions.getAllRepos()) {
            // Module repos.
            for (repoName in moduleInspector.moduleKeyToCanonicalNames.values()) {
                if (repoName.isMain()) {
                    // The main repo can't be inspected.
                    continue
                }
                targetToRepoName.put(repoName.getNameWithAt(), repoName)
            }

            // Extension repos.
            for (extensionRepos in moduleInspector.extensionToRepoInternalNames.asMap().entrySet()) {
                val extensionUniqueName: kotlin.String? =
                    depGraphValue.getExtensionUniqueNames().get(extensionRepos.getKey())

                for (internalName in extensionRepos.getValue()) {
                    val repoName: RepositoryName =
                        SingleExtensionValue.Companion.repositoryName(extensionUniqueName, internalName)
                    targetToRepoName.put(repoName.getNameWithAt(), repoName)
                }
            }
        } else if (modOptions.getAllVisibleRepos()) {
            for (entry in baseModuleMapping.entries().entrySet()) {
                if (entry.getValue().isMain()) {
                    // The main repo can't be inspected.
                    continue
                }
                targetToRepoName.put("@" + entry.getKey(), entry.getValue())
            }
        } else {
            // Resolve explicitly specified repos.
            for (arg in args) {
                try {
                    targetToRepoName.putAll(
                        ModuleArgConverter.Companion.INSTANCE
                            .convert(arg)
                            .resolveToRepoNames(
                                moduleInspector.modulesIndex,
                                moduleInspector.depGraph,
                                moduleInspector.moduleKeyToCanonicalNames,
                                baseModuleMapping
                            )
                    )
                } catch (e: InvalidArgumentException) {
                    throw InvalidArgumentException(
                        String.format(
                            "In repo argument %s: %s (Note that unused modules cannot be used here)",
                            arg, e.getMessage()
                        ),
                        Code.INVALID_ARGUMENTS,
                        e
                    )
                } catch (e: OptionsParsingException) {
                    throw InvalidArgumentException(
                        String.format(
                            "In repo argument %s: %s (Note that unused modules cannot be used here)",
                            arg, e.getMessage()
                        ),
                        Code.INVALID_ARGUMENTS,
                        e
                    )
                }
            }
        }
        return targetToRepoName.buildKeepingLast()
    }

    private fun runTidy(env: CommandEnvironment, modTidyValue: BazelModTidyValue): BlazeCommandResult {
        val allCommandsPerFile: ImmutableListMultimap<PathFragment?, kotlin.String?>? =
            modTidyValue.fixups.stream()
                .flatMap<MutableMap.MutableEntry<PathFragment?, kotlin.String?>?>(Function { fixup: RootModuleFileFixup? ->
                    fixup.moduleFilePathToBuildozerCommands.entries().stream()
                })
        TODO(
            """
            |Cannot convert element
            |With text:
            |collect(<Entry<PathFragment, String>, PathFragment, String>toImmutableListMultimap(Entry::getKey, Entry::getValue)
            """.trimMargin()
        )

        val buildozerInput = StringBuilder()
        for (moduleFilePath in modTidyValue.moduleFilePaths) {
            buildozerInput.append("//").append(moduleFilePath).append(":all|")
            for (command in allCommandsPerFile!!.get(moduleFilePath)) {
                buildozerInput.append(command).append('|')
            }
            buildozerInput.append("format\n")
        }

        try {
            CharSource.wrap(buildozerInput).asByteSource(StandardCharsets.ISO_8859_1).openStream().use { stdin ->
                CommandBuilder(env.getClientEnv())
                    .setWorkingDir(env.getWorkspace())
                    .addArg(modTidyValue.buildozer.getPathString())
                    .addArg("-f")
                    .addArg("-")
                    .build()
                    .executeAsync(stdin,  /* killSubprocessOnInterrupt= */true)
                    .get()
            }
        } catch (e: InterruptedException) {
            var suffix = ""
            if (e is AbnormalTerminationException) {
                if (e.getResult().terminationStatus.getRawExitCode() == 3) {
                    // Buildozer exits with exit code 3 if it didn't make any changes.
                    return reportAndCreateTidyResult(env, modTidyValue)
                }
                suffix =
                    (":\n"
                            + kotlin.String(
                        (e as AbnormalTerminationException).getResult().getStderr(), StandardCharsets.ISO_8859_1
                    ))
            }
            return reportAndCreateFailureResult(
                env,
                "Unexpected error while running buildozer: " + e.getMessage() + suffix,
                Code.BUILDOZER_FAILED
            )
        } catch (e: CommandException) {
            var suffix = ""
            if (e is AbnormalTerminationException) {
                if (e.getResult().terminationStatus.getRawExitCode() == 3) {
                    return reportAndCreateTidyResult(env, modTidyValue)
                }
                suffix =
                    (":\n"
                            + kotlin.String(
                        (e as AbnormalTerminationException).getResult().getStderr(), StandardCharsets.ISO_8859_1
                    ))
            }
            return reportAndCreateFailureResult(
                env,
                "Unexpected error while running buildozer: " + e.getMessage() + suffix,
                Code.BUILDOZER_FAILED
            )
        } catch (e: IOException) {
            var suffix = ""
            if (e is AbnormalTerminationException) {
                if (e.getResult().terminationStatus.getRawExitCode() == 3) {
                    return reportAndCreateTidyResult(env, modTidyValue)
                }
                suffix =
                    (":\n"
                            + kotlin.String(
                        (e as AbnormalTerminationException).getResult().getStderr(), StandardCharsets.ISO_8859_1
                    ))
            }
            return reportAndCreateFailureResult(
                env,
                "Unexpected error while running buildozer: " + e.getMessage() + suffix,
                Code.BUILDOZER_FAILED
            )
        }

        for (fixupEvent in modTidyValue.fixups) {
            env.getReporter().handle(Event.info(fixupEvent.getSuccessMessage()))
        }

        return reportAndCreateTidyResult(env, modTidyValue)
    }

    companion object {
        const val NAME: kotlin.String = "mod"

        private fun reportAndCreateTidyResult(
            env: CommandEnvironment, modTidyValue: BazelModTidyValue
        ): BlazeCommandResult {
            if (modTidyValue.errors.isEmpty()) {
                return BlazeCommandResult.success()
            } else {
                return reportAndCreateFailureResult(
                    env,
                    String.format(
                        "Failed to process %d extension%s due to errors.",
                        modTidyValue.errors.size(), if (modTidyValue.errors.size() == 1) "" else "s"
                    ),
                    Code.ERROR_DURING_GRAPH_INSPECTION
                )
            }
        }

        /** Collects a list of [ModuleArg] into a set of [ModuleKey]s.  */
        @Throws(InvalidArgumentException::class)
        private fun moduleArgListToKeys(
            argList: ImmutableList<ModuleArg>,
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>?,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>?,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>?,
            baseModuleDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?,
            baseModuleUnusedDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?,
            includeUnused: Boolean
        ): ImmutableSet<ModuleKey?> {
            val allTargetKeys: ImmutableSet.Builder<ModuleKey?> = ImmutableSet.Builder<ModuleKey?>()
            for (moduleArg in argList) {
                allTargetKeys.addAll(
                    moduleArg.resolveToModuleKeys(
                        modulesIndex,
                        depGraph,
                        moduleKeyToCanonicalNames,
                        baseModuleDeps,
                        baseModuleUnusedDeps,
                        includeUnused,
                        true
                    )
                )
            }
            return allTargetKeys.build()
        }

        @Throws(InvalidArgumentException::class)
        private fun extensionArgListToIds(
            args: ImmutableList<ExtensionArg>,
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>?,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>?,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>?,
            baseModuleDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?,
            baseModuleUnusedDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?
        ): ImmutableSortedSet<ModuleExtensionId?> {
            val extensionsBuilder: ImmutableSortedSet.Builder<ModuleExtensionId?> =
                ImmutableSortedSet.Builder<ModuleExtensionId?>(ModuleExtensionId.Companion.LEXICOGRAPHIC_COMPARATOR)
            for (arg in args) {
                extensionsBuilder.add(
                    arg.resolveToExtensionId(
                        modulesIndex,
                        depGraph,
                        moduleKeyToCanonicalNames,
                        baseModuleDeps,
                        baseModuleUnusedDeps
                    )
                )
            }
            return extensionsBuilder.build()
        }

        private fun reportAndCreateFailureResult(
            env: CommandEnvironment, message: kotlin.String, detailedCode: Code
        ): BlazeCommandResult {
            val fullMessage =
                when (detailedCode) {
                    MISSING_ARGUMENTS, TOO_MANY_ARGUMENTS, INVALID_ARGUMENTS -> String.format(
                        "%s%s Type '%s help mod' for syntax and help.",
                        message, if (message.endsWith(".")) "" else ".", env.getRuntime().getProductName()
                    )

                    else -> message
                }
            env.getReporter().handle(Event.error(fullMessage))
            return createFailureResult(fullMessage, detailedCode)
        }

        private fun createFailureResult(message: kotlin.String?, detailedCode: Code?): BlazeCommandResult {
            return BlazeCommandResult.detailedExitCode(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setModCommand(FailureDetails.ModCommand.newBuilder().setCode(detailedCode).build())
                        .setMessage(message)
                        .build()
                )
            )
        }

        @Throws(IOException::class)
        fun dumpRepoMappings(repoMappings: MutableList<RepositoryMappingValue>, writer: Writer) {
            val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
            for (repoMapping in repoMappings) {
                val jsonWriter: JsonWriter = gson.newJsonWriter(writer)
                jsonWriter.beginObject()
                for (entry in repoMapping.repositoryMapping.entries().entrySet()) {
                    jsonWriter.name(entry.getKey())
                    jsonWriter.value(entry.getValue().getName())
                }
                jsonWriter.endObject()
                writer.write('\n'.code)
            }
            writer.flush()
        }
    }
}
