// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.analysis.ConfiguredTarget
import com.google.devtools.build.lib.bazel.commands.VendorCommand.Companion.createFailedBlazeCommandResult
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.Reporter
import com.google.devtools.build.lib.runtime.Command
import com.google.devtools.build.lib.runtime.commands.TestCommand
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.skyframe.EvaluationContext
import com.google.devtools.common.options.OptionsParser
import com.google.devtools.common.options.OptionsParsingResult
import java.lang.String
import java.net.URI
import java.util.Objects
import java.util.Optional
import java.util.Queue
import java.util.function.Predicate
import java.util.function.Supplier
import kotlin.Any
import kotlin.Boolean
import kotlin.Exception
import kotlin.arrayOf
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet
import kotlin.toString

/**
 * Fetches external repositories into a specified directory.
 * 
 * 
 * This command is used to fetch external repositories into a specified directory. It can be used
 * to fetch all external repositories, a specific list of repositories or the repositories needed to
 * build a specific list of targets.
 * 
 * 
 * The command is used to create a vendor directory that can be used to build the project
 * offline.
 */
@Command(
    name = VendorCommand.Companion.NAME,
    buildPhase = BuildPhase.ANALYZES,
    inheritsOptionsFrom = [TestCommand::class],
    options = [VendorOptions::class, PackageOptions::class, KeepGoingOption::class, LoadingPhaseThreadsOption::class
    ],
    allowResidue = true,
    usesConfigurationOptions = true,
    help = "resource:vendor.txt",
    shortDescription = "Fetches external repositories into a folder specified by the flag --vendor_dir."
)
class VendorCommand(private val nonstrictRepoEnvSupplier: Supplier<ImmutableMap<String?, String?>?>) : BlazeCommand {
    private var vendorManager: VendorManager? = null
    private var downloadManager: DownloadManager? = null

    fun setDownloadManager(downloadManager: DownloadManager?) {
        this.downloadManager = downloadManager
    }

    override fun editOptions(optionsParser: OptionsParser) {
        TargetFetcher.Companion.injectNoBuildOption(optionsParser)
    }

    override fun exec(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult {
        val invalidResult: BlazeCommandResult? = validateOptions(env, options)
        if (invalidResult != null) {
            return invalidResult
        }

        env.getEventBus()
            .post(
                NoBuildEvent(
                    env.getCommandName(),
                    env.getCommandStartTime(),  /* separateFinishedEvent= */
                    true,  /* showProgress= */
                    true,
                    env.getCommandId().toString()
                )
            )

        // IS_VENDOR_COMMAND & VENDOR_DIR is already injected in "BazelRepositoryModule", we just need
        // to update this value for the delegator function to recognize this call is from VendorCommand
        env.getSkyframeExecutor()
            .injectExtraPrecomputedValues(
                ImmutableList.of<Injected?>(
                    PrecomputedValue.injected<Boolean?>(RepositoryDirectoryValue.IS_VENDOR_COMMAND, true)
                )
            )

        val result: BlazeCommandResult
        val vendorOptions = options.getOptions<VendorOptions?>(VendorOptions::class.java)
        val threadsOption: LoadingPhaseThreadsOption? =
            options.getOptions<LoadingPhaseThreadsOption?>(LoadingPhaseThreadsOption::class.java)
        val vendorDirectory: Path? =
            env.getWorkspace()
                .getRelative(options.getOptions<RepositoryOptions?>(RepositoryOptions::class.java).getVendorDirectory())
        this.vendorManager = VendorManager(vendorDirectory)
        val targets: MutableList<String?>
        try {
            targets = TargetPatternsHelper.readFrom(env, options)
        } catch (e: TargetPatternsHelperException) {
            env.getReporter().handle(Event.error(e.getMessage()))
            return BlazeCommandResult.failureDetail(e.getFailureDetail())
        }
        try {
            if (!targets.isEmpty()) {
                if (!vendorOptions!!.getRepos().isEmpty()) {
                    return createFailedBlazeCommandResult(
                        env.getReporter(), "Target patterns and --repo cannot both be specified"
                    )
                }
                result = vendorTargets(env, options, targets)
            } else if (!vendorOptions!!.getRepos().isEmpty()) {
                result = vendorRepos(env, threadsOption, vendorOptions.getRepos())
            } else {
                result = vendorAll(env, threadsOption)
            }
        } catch (e: InterruptedException) {
            return createFailedBlazeCommandResult(
                env.getReporter(), "Vendor interrupted: " + e.getMessage()
            )
        } catch (e: IOException) {
            return createFailedBlazeCommandResult(
                env.getReporter(), "Error while vendoring repos: " + e.getMessage()
            )
        }

        env.getEventBus()
            .post(
                NoBuildRequestFinishedEvent(
                    result.getExitCode(), env.getRuntime().getClock().currentTimeMillis()
                )
            )
        return result
    }

    private fun validateOptions(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult? {
        if (options.getOptions<RepositoryOptions?>(RepositoryOptions::class.java).getVendorDirectory() == null) {
            return createFailedBlazeCommandResult(
                env.getReporter(),
                Code.OPTIONS_INVALID,
                "You cannot run the vendor command without specifying --vendor_dir"
            )
        }
        if (!options.getOptions<PackageOptions?>(PackageOptions::class.java).getFetch()) {
            return createFailedBlazeCommandResult(
                env.getReporter(),
                Code.OPTIONS_INVALID,
                "You cannot run the vendor command with --nofetch"
            )
        }
        return null
    }

    @Throws(InterruptedException::class, IOException::class)
    private fun vendorAll(
        env: CommandEnvironment, threadsOption: LoadingPhaseThreadsOption
    ): BlazeCommandResult {
        val evaluationContext =
            EvaluationContext.newBuilder()
                .setParallelism(threadsOption.getThreads())
                .setEventHandler(env.getReporter())
                .build()

        val fetchKey: SkyKey = BazelFetchAllValue.Companion.key( /* configureEnabled= */false)
        val evaluationResult: EvaluationResult<SkyValue?> =
            env.getSkyframeExecutor().prepareAndGet(ImmutableSet.of<SkyKey?>(fetchKey), evaluationContext)
        if (evaluationResult.hasError()) {
            val e: Exception? = evaluationResult.getError().getException()
            return createFailedBlazeCommandResult(
                env.getReporter(),
                if (e != null) e.getMessage() else "Unexpected error during fetching all external deps."
            )
        }

        val fetchAllValue: BazelFetchAllValue = evaluationResult.get(fetchKey) as BazelFetchAllValue
        env.getReporter().handle(Event.info("Vendoring all external repositories..."))
        vendor(env, fetchAllValue.reposToVendor)
        env.getReporter().handle(Event.info("All external dependencies vendored successfully."))
        return BlazeCommandResult.success()
    }

    @Throws(InterruptedException::class, IOException::class)
    private fun vendorRepos(
        env: CommandEnvironment, threadsOption: LoadingPhaseThreadsOption?, repos: MutableList<String?>?
    ): BlazeCommandResult {
        val repositoryNamesAndValues: ImmutableMap<RepositoryName?, RepositoryDirectoryValue?>
        try {
            repositoryNamesAndValues = RepositoryFetcher.Companion.fetchRepos(repos, env, threadsOption)
        } catch (e: RepositoryMappingResolutionException) {
            return Companion.createFailedBlazeCommandResult(
                env.getReporter(), "Invalid repo name: " + e.getMessage(), e.getDetailedExitCode()
            )
        } catch (e: RepositoryFetcherException) {
            return createFailedBlazeCommandResult(env.getReporter(), e.getMessage())
        }

        // Split repos to found and not found, vendor found ones and report others
        val reposToVendor: ImmutableList.Builder<RepositoryName?> = ImmutableList.builder<RepositoryName?>()
        val notFoundRepoErrors: MutableList<String?> = ArrayList<String?>()
        for (entry in repositoryNamesAndValues.entrySet()) {
            when (entry.getValue()) {
                -> {
                    if (!s.excludeFromVendoring) {
                        reposToVendor.add(entry.getKey())
                    }
                }

                -> notFoundRepoErrors.add(errorMsg)
            }
        }

        env.getReporter().handle(Event.info("Vendoring repositories..."))
        vendor(env, reposToVendor.build())
        if (!notFoundRepoErrors.isEmpty()) {
            return createFailedBlazeCommandResult(
                env.getReporter(), "Vendoring some repos failed with errors: " + notFoundRepoErrors
            )
        }
        env.getReporter().handle(Event.info("All requested repos vendored successfully."))
        return BlazeCommandResult.success()
    }

    @Throws(InterruptedException::class, IOException::class)
    private fun vendorTargets(
        env: CommandEnvironment, options: OptionsParsingResult?, targets: MutableList<String?>?
    ): BlazeCommandResult {
        // Call fetch which runs build to have the targets graph and configuration set
        val buildResult: BuildResult?
        try {
            buildResult = TargetFetcher.Companion.fetchTargets(env, options, targets)
        } catch (e: TargetFetcherException) {
            return createFailedBlazeCommandResult(
                env.getReporter(), Code.QUERY_EVALUATION_ERROR, e.getMessage()
            )
        }

        // Traverse the graph created from build to collect repos and vendor them
        val targetKeys: ImmutableList<SkyKey?> =
            buildResult.getActualTargets().stream()
                .map<Any?>(ConfiguredTarget::getLookupKey)
                .collect(ImmutableList.toImmutableList<Any?>())
        val inMemoryGraph: InMemoryGraph = env.getSkyframeExecutor().getEvaluator().getInMemoryGraph()
        val reposToVendor: ImmutableSet<RepositoryName?> = collectReposFromTargets(inMemoryGraph, targetKeys)

        env.getReporter().handle(Event.info("Vendoring dependencies for targets..."))
        vendor(env, reposToVendor.asList())
        env.getReporter()
            .handle(
                Event.info(
                    "All external dependencies for the requested targets vendored successfully."
                )
            )
        return BlazeCommandResult.success()
    }

    @Throws(InterruptedException::class)
    private fun collectReposFromTargets(
        inMemoryGraph: InMemoryGraph, targetKeys: ImmutableList<SkyKey?>
    ): ImmutableSet<RepositoryName?> {
        val repos: ImmutableSet.Builder<RepositoryName?> = ImmutableSet.builder<RepositoryName?>()
        val nodes: Queue<SkyKey> = ArrayDeque<SkyKey>(targetKeys)
        val visited: MutableSet<SkyKey?> = HashSet<SkyKey?>()
        while (!nodes.isEmpty()) {
            val key: SkyKey = nodes.remove()
            visited.add(key)
            val nodeEntry: NodeEntry? = inMemoryGraph.get(null, QueryableGraph.Reason.VENDOR_EXTERNAL_REPOS, key)
            if (nodeEntry.getValue() is RepositoryDirectoryValue.Success
                && !repoDirValue.excludeFromVendoring
            ) {
                repos.add(key.argument() as RepositoryName?)
            }
            for (depKey in nodeEntry.getDirectDeps()) {
                if (!visited.contains(depKey)) {
                    nodes.add(depKey)
                }
            }
        }
        return repos.build()
    }

    /**
     * Copies the fetched repos from the external cache into the vendor directory, unless the repo is
     * ignored or was already vendored and up-to-date
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun vendor(env: CommandEnvironment, reposToVendor: ImmutableList<RepositoryName?>) {
        Objects.requireNonNull<VendorManager?>(vendorManager)

        // 1. Vendor registry files
        val moduleResolutionValue: BazelModuleResolutionValue? =
            env.getSkyframeExecutor()
                .getEvaluator()
                .getExistingValue(BazelModuleResolutionValue.Companion.KEY) as BazelModuleResolutionValue?
        val registryFiles: ImmutableMap<String?, Optional<Checksum?>?> =
            Objects.requireNonNull<BazelModuleResolutionValue?>(moduleResolutionValue).getRegistryFileHashes()

        // vendorPathToURL is a map of
        //  key: a vendor path string converted to lower case
        //  value: a URL string
        // This map is for detecting potential rare vendor path conflicts, such as:
        //  http://foo.bar.com/BCR vs http://foo.bar.com/bcr => conflict vendor paths on
        // case-insensitive system
        //  http://foo.bar.com/bcr vs http://foo.bar.com:8081/bcr => conflict vendor path because port
        // number is ignored in vendor path
        // The user has to update the Bazel registries this if such conflicts occur.
        val vendorPathToUrl: MutableMap<String?, String?> = HashMap<String?, String?>()
        for (entry in registryFiles.entrySet()) {
            val url = URI.create(entry.getKey())
            if (url.getScheme() == "file") {
                continue
            }

            val outputPath = vendorManager.getVendorPathForUrl(url).getPathString()
            val outputPathLowerCase: String = outputPath.toLowerCase(Locale.ROOT)
            if (vendorPathToUrl.containsKey(outputPathLowerCase)) {
                val previousUrl = vendorPathToUrl.get(outputPathLowerCase)
                throw IOException(
                    String.format(
                        ("Vendor paths conflict detected for registry URLs:\n"
                                + "    %s => %s\n"
                                + "    %s => %s\n"
                                + "Their output paths are either the same or only differ by case, which will"
                                + " cause conflict on case insensitive file systems, please fix by changing the"
                                + " registry URLs!"),
                        previousUrl,
                        vendorManager.getVendorPathForUrl(URI.create(previousUrl)).getPathString(),
                        entry.getKey(),
                        outputPath
                    )
                )
            }

            val checksum: Optional<Checksum?> = entry.getValue()
            if (!vendorManager.isUrlVendored(url) // Only vendor a registry URL when its checksum exists, otherwise the URL should be
                // recorded as "not found" in moduleResolutionValue.getRegistryFileHashes()
                && checksum.isPresent()
            ) {
                try {
                    vendorManager.vendorRegistryUrl(
                        url,
                        downloadManager.downloadAndReadOneUrlForBzlmod(
                            url, nonstrictRepoEnvSupplier.get(), checksum
                        )
                    )
                } catch (e: IOException) {
                    throw IOException(
                        String.format(
                            "Failed to vendor registry URL %s at %s: %s", url, outputPath, e.getMessage()
                        ),
                        e.getCause()
                    )
                }
            }

            vendorPathToUrl.put(outputPathLowerCase, entry.getKey())
        }

        // 2. Vendor repos
        val externalPath: Path? =
            env.getDirectories()
                .getOutputBase()
                .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
        vendorManager.vendorRepos(externalPath, env.getDirectories().getWorkspace(), reposToVendor)

        // 3. Invalidate RepositoryDirectoryValue for vendored repos.
        env.getSkyframeExecutor()
            .getEvaluator()
            .delete(
                Predicate { k: SkyKey? ->
                    k.functionName() == SkyFunctions.REPOSITORY_DIRECTORY
                            && reposToVendor.contains(k.argument())
                })
    }

    companion object {
        const val NAME: kotlin.String = "vendor"

        private fun createFailedBlazeCommandResult(
            reporter: Reporter, fetchCommandCode: Code?, message: kotlin.String?
        ): BlazeCommandResult {
            return Companion.createFailedBlazeCommandResult(
                reporter,
                message,
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setFetchCommand(
                            FailureDetails.FetchCommand.newBuilder().setCode(fetchCommandCode).build()
                        )
                        .build()
                )
            )
        }

        private fun createFailedBlazeCommandResult(
            reporter: Reporter, errorMessage: kotlin.String?
        ): BlazeCommandResult {
            return Companion.createFailedBlazeCommandResult(
                reporter, errorMessage, InterruptedFailureDetails.detailedExitCode(errorMessage)
            )
        }

        private fun createFailedBlazeCommandResult(
            reporter: Reporter, message: kotlin.String?, exitCode: DetailedExitCode?
        ): BlazeCommandResult {
            reporter.handle(Event.error(message))
            return BlazeCommandResult.detailedExitCode(exitCode)
        }
    }
}
