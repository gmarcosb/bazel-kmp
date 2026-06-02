// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.coverage

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.primitives.Booleans
import com.google.devtools.build.lib.actions.AbstractAction
import com.google.devtools.build.lib.collect.nestedset.Order
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.EventHandler
import com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils
import com.google.devtools.build.lib.profiler.ProfilerTask
import com.google.devtools.build.lib.vfs.Path
import kotlin.Any
import kotlin.Boolean
import kotlin.Comparator
import kotlin.String
import kotlin.Unit

/**
 * A class to create the coverage report generator action.
 * 
 * 
 * The coverage report action is created after every test shard action is created, at the very
 * end of the analysis phase. There is only one coverage report action per coverage command
 * invocation. It can also be viewed as a single sink node of the action graph.
 * 
 * 
 * Its inputs are the individual coverage.dat files from the test outputs (each shard produces
 * one) and the baseline coverage artifacts. Note that each ConfiguredTarget among the transitive
 * dependencies of the top level test targets may provide baseline coverage artifacts.
 * 
 * 
 * The coverage report generation can have two phases, though they both run in the same action.
 * The source code of the coverage report tool `lcov_merger` is in the `testing/coverage/lcov_merger` directory. The deployed binaries used by Blaze are under `tools/coverage`.
 * 
 * 
 * The first phase is merging the individual coverage files into a single report file. The
 * location of this file is reported by Blaze. This phase always happens if the `--combined_report=lcov` or `--combined_report=html`.
 * 
 * 
 * The second phase is generating an html report. It only happens if `--combined_report=html`. The action generates an html output file potentially for every tested
 * source file into the report. Since this set of files is unknown in the analysis phase (the tool
 * figures it out from the contents of the merged coverage report file) the action always runs
 * locally when `--combined_report=html`.
 */
class CoverageReportActionBuilder {
    // SpawnActions can't be used because they need the AnalysisEnvironment and this action is
    // created specially at the very end of the analysis phase when we don't have it anymore.
    @ThreadSafety.Immutable
    private class CoverageReportAction(
        owner: ActionOwner?,
        inputs: NestedSet<Artifact?>?,
        outputs: ImmutableSet<Artifact?>?,
        private val command: ImmutableList<String?>,
        private val locationMessage: String?
    ) : AbstractAction(owner, inputs, outputs), NotifyOnActionCacheHit {
        @Throws(ActionExecutionException::class, InterruptedException::class)
        public override fun execute(ctx: ActionExecutionContext): ActionResult {
            val spawn: Spawn =
                BaseSpawn(command, ImmutableMap.of<K?, V?>(), ImmutableMap.of<K?, V?>(), this, LOCAL_RESOURCES)
            try {
                val spawnResults: ImmutableList<SpawnResult?>? =
                    ctx.getContext(SpawnStrategyResolver::class.java).exec(spawn, ctx)
                informImportantOutputHandler(ctx)
                ctx.getEventHandler().handle(Event.info(locationMessage))
                return ActionResult.create(spawnResults)
            } catch (e: ExecException) {
                throw ActionExecutionException.fromExecException(e, this)
            }
        }

        @Throws(EnvironmentalExecException::class, InterruptedException::class)
        fun informImportantOutputHandler(ctx: ActionExecutionContext) {
            val importantOutputHandler: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                ctx.getContext(ImportantOutputHandler::class.java)
            if (importantOutputHandler == null) {
                return
            }

            val coverageReportOutputs: ImmutableList<Path?>? =
                getOutputs().stream()
                    .map({ o -> ctx.getPathResolver().toPath(o) })
                    .collect(ImmutableList.toImmutableList<E?>())
            try {
                GoogleAutoProfilerUtils.profiledAndLogged(
                    "Informing important output handler of coverage report",
                    ProfilerTask.INFO,
                    ImportantOutputHandler.LOG_THRESHOLD
                ).use { ignored ->
                    importantOutputHandler.processTestOutputs(coverageReportOutputs)
                }
            } catch (e: ImportantOutputException) {
                throw EnvironmentalExecException(e, e.getFailureDetail())
            }
        }

        public override fun getMnemonic(): String {
            return "CoverageReport"
        }

        protected override fun getRawProgressMessage(): String {
            return "Coverage report generation"
        }

        protected override fun computeKey(
            actionKeyContext: ActionKeyContext?,
            inputMetadataProvider: InputMetadataProvider?,
            fp: Fingerprint
        ) {
            fp.addStrings(command)
        }

        public override fun actionCacheHit(context: ActionCachedContext): Boolean {
            context.getEventHandler().handle(Event.info(locationMessage))
            return true
        }
    }

    /** Returns the coverage report action. May return null in case of an error.  */
    @Throws(InterruptedException::class)
    fun createCoverageActionsWrapper(
        reporter: EventHandler,
        directories: BlazeDirectories,
        configuredTargets: MutableCollection<ConfiguredTarget>,
        targetsToTest: MutableCollection<ConfiguredTarget>?,
        factory: ArtifactFactory,
        actionKeyContext: ActionKeyContext?,
        artifactOwner: ArtifactOwner?,
        workspaceName: String?,
        coverageHelper: CoverageHelper,
        htmlReport: Artifact?
    ): CoverageReportActionsWrapper? {
        if (targetsToTest == null || targetsToTest.isEmpty()) {
            return null
        }
        val builder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder<Artifact?>()
        var reportGenerator: FilesToRunProvider? = null
        var actionOwner: ActionOwner? = null
        for (target in targetsToTest) {
            // Skip incompatible tests.
            if (target.get(IncompatiblePlatformProvider.PROVIDER) != null) {
                continue
            }
            val testParams: TestParams = target.getProvider(TestProvider::class.java).getTestParams()
            builder.addAll(testParams.getCoverageArtifacts())
            // targetsToTest has non-deterministic order, so we ensure that we pick the same action owner
            // and matching report generator each time by picking the owner that's lexicographically
            // largest. We prefer an owner with exec properties set in case the action is run remotely.
            if (reportGenerator == null
                || (ACTION_OWNER_COMPARATOR.compare(testParams.getActionOwnerForCoverage(), actionOwner)
                        > 0)
            ) {
                reportGenerator = testParams.getCoverageReportGenerator()
                actionOwner = testParams.getActionOwnerForCoverage()
            }
        }
        // If all tests are incompatible, there's nothing to do.
        if (reportGenerator == null) {
            return null
        }
        Preconditions.checkNotNull<Any?>(actionOwner)
        val baselineCoverageArtifacts: NestedSet<Artifact?> = getBaselineCoverageArtifacts(configuredTargets)
        val coverageArtifacts: NestedSet<Artifact?> =
            builder.addTransitive(baselineCoverageArtifacts).build()
        if (!coverageArtifacts.isEmpty()) {
            val baselineLcovArtifact: Artifact? =
                factory.getDerivedArtifact(
                    TestRunnerAction.COVERAGE_TMP_ROOT.getRelative("baseline_lcov_files.tmp"),
                    directories.getBuildDataDirectory(workspaceName),
                    artifactOwner
                )
            val baselineLcovFileAction: Action =
                generateLcovFileWriteAction(baselineLcovArtifact, baselineCoverageArtifacts, actionOwner)
            val baselineReportAction: Action =
                generateCoverageReportAction(
                    CoverageArgs(
                        directories,
                        baselineCoverageArtifacts,
                        baselineLcovArtifact,
                        factory,
                        artifactOwner,
                        reportGenerator,
                        workspaceName,  /* htmlReport= */
                        null,
                        actionOwner
                    ),
                    coverageHelper,
                    configuredTargets,
                    "_baseline_report.dat"
                )
            val coverageLcovArtifact: Artifact? =
                factory.getDerivedArtifact(
                    TestRunnerAction.COVERAGE_TMP_ROOT.getRelative("coverage_lcov_files.tmp"),
                    directories.getBuildDataDirectory(workspaceName),
                    artifactOwner
                )
            val coverageLcovFileAction: Action =
                generateLcovFileWriteAction(coverageLcovArtifact, coverageArtifacts, actionOwner)
            val coverageReportAction: Action =
                generateCoverageReportAction(
                    CoverageArgs(
                        directories,
                        coverageArtifacts,
                        coverageLcovArtifact,
                        factory,
                        artifactOwner,
                        reportGenerator,
                        workspaceName,
                        htmlReport,
                        actionOwner
                    ),
                    coverageHelper,
                    configuredTargets,
                    "_coverage_report.dat"
                )
            return CoverageReportActionsWrapper(
                baselineReportAction,
                coverageReportAction,
                ImmutableList.of<ActionAnalysisMetadata?>(baselineLcovFileAction, coverageLcovFileAction),
                actionKeyContext
            )
        } else {
            reporter.handle(
                Event.error("Cannot generate coverage report - no coverage information was collected")
            )
            return null
        }
    }

    /** A helper interface for product-specific coverage support.  */
    interface CoverageHelper {
        /** Returns the arguments for the coverage report generator action.  */
        fun getArgs(args: CoverageArgs?, lcovOutput: Artifact?): ImmutableList<String?>

        /** Returns a message describing the location of the coverage report.  */
        fun getLocationMessage(args: CoverageArgs?, lcovOutput: Artifact?): String?

        /** Returns additional inputs for the coverage report action.  */
        fun getInstrumentedFiles(
            args: CoverageArgs?, configuredTargets: MutableCollection<ConfiguredTarget>?
        ): NestedSet<Artifact?>? {
            return NestedSetBuilder.emptySet<Artifact?>(Order.STABLE_ORDER)
        }
    }

    companion object {
        private val LOCAL_RESOURCES: ResourceSet? = ResourceSet.createWithRamCpu( /* memoryMb= */750,  /* cpu= */1)

        private val ACTION_OWNER_COMPARATOR: Comparator<ActionOwner?> = Comparator.comparing<T?, U?>(
            { actionOwner: ActionOwner? -> actionOwner.getExecProperties().isEmpty() }, Booleans.falseFirst()
        )
            .thenComparing(ActionOwner::getLabel)
            .thenComparing(ActionOwner::getConfigurationChecksum)

        private fun getBaselineCoverageArtifacts(
            configuredTargets: MutableCollection<ConfiguredTarget>
        ): NestedSet<Artifact?> {
            val baselineCoverageArtifacts: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder<Artifact?>()
            for (target in configuredTargets) {
                val provider: InstrumentedFilesInfo? = target.get(InstrumentedFilesInfo.Companion.STARLARK_CONSTRUCTOR)
                if (provider != null) {
                    baselineCoverageArtifacts.addTransitive(provider.getBaselineCoverageArtifacts())
                }
            }
            return baselineCoverageArtifacts.build()
        }

        private fun generateLcovFileWriteAction(
            lcovArtifact: Artifact?, coverageArtifacts: NestedSet<Artifact?>?, actionOwner: ActionOwner?
        ): LazyWritePathsFileAction {
            return LazyWritePathsFileAction(
                actionOwner,
                lcovArtifact,
                coverageArtifacts,  /* filesToIgnore= */
                ImmutableSet.of<E?>(),  /* includeDerivedArtifacts= */
                true
            )
        }

        private fun generateCoverageReportAction(
            args: CoverageArgs,
            coverageHelper: CoverageHelper,
            configuredTargets: MutableCollection<ConfiguredTarget>?,
            basename: String?
        ): CoverageReportAction {
            val root: ArtifactRoot? = args.directories.getBuildDataDirectory(args.workspaceName)
            val lcovOutput: Artifact? =
                args.factory
                    .getDerivedArtifact(
                        TestRunnerAction.COVERAGE_TMP_ROOT.getRelative(basename),
                        root,
                        args.artifactOwner
                    )
            val reportGeneratorExec: Artifact? = args.reportGenerator.getExecutable()
            val runfilesSupport: RunfilesSupport? = args.reportGenerator.getRunfilesSupport()
            val runfilesTree: Artifact? =
                if (runfilesSupport != null) runfilesSupport.getRunfilesTreeArtifact() else null
            val actionArgs = coverageHelper.getArgs(args, lcovOutput)

            val inputsBuilder: NestedSetBuilder<Artifact?> =
                NestedSetBuilder.stableOrder<Artifact?>()
                    .addTransitive(args.coverageArtifacts)
                    .add(reportGeneratorExec)
                    .add(args.lcovArtifact)
            if (runfilesTree != null) {
                inputsBuilder.add(runfilesTree)
            }

            val outputsBuilder: ImmutableSet.Builder<Artifact?> =
                ImmutableSet.builder<Artifact?>().add(lcovOutput)
            if (args.htmlReport != null) {
                outputsBuilder.add(args.htmlReport)
                inputsBuilder.addTransitive(coverageHelper.getInstrumentedFiles(args, configuredTargets))
            }
            return CoverageReportAction(
                args.actionOwner,
                inputsBuilder.build(),
                outputsBuilder.build(),
                actionArgs,
                coverageHelper.getLocationMessage(args, lcovOutput)
            )
        }
    }
}
