// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.collect.ImmutableSortedMap
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata
import com.google.devtools.build.lib.concurrent.ThreadSafety
import java.util.function.Function
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/**
 * Utilities for analysis phase tests.
 */
object AnalysisTestUtil {
    /** TopLevelArtifactContext that should be sufficient for testing.  */
    val TOP_LEVEL_ARTIFACT_CONTEXT: TopLevelArtifactContext = TopLevelArtifactContext( /* runTestsExclusively= */
        false,  /* expandFilesets= */
        false,
        OutputGroupInfo.DEFAULT_GROUPS
    )

    /** Matches the output path prefix contributed by a C++ configuration fragment.  */
    private val OUTPUT_PATH_CPP_PREFIX_PATTERN: Pattern =
        Pattern.compile("(?<=" + TestConstants.PRODUCT_NAME + "-out/)gcc[^/]*-grte-\\w+-")

    /** Matches the output path prefix contributed by an Android configuration fragment.  */
    private val OUTPUT_PATH_ANDROID_PREFIX_PATTERN: Pattern =
        Pattern.compile("(?<=" + TestConstants.PRODUCT_NAME + "-out/)android-")

    /**
     * Apply `function` to the path string of the given ArtifactRoot. If the root path matches
     * [.OUTPUT_PATH_CPP_PREFIX_PATTERN] or [.OUTPUT_PATH_ANDROID_PREFIX_PATTERN], also
     * use those to update the path and invoke `function` again.
     * 
     * @return the result of `function` from the most specific root path
     */
    private fun <U> computeRootPaths(artifactRoot: ArtifactRoot, function: Function<String?, U?>): U? {
        val rootPath = artifactRoot.getRoot().toString()
        var result = function.apply(rootPath)
        // The output paths that bin, genfiles, etc. refer to may or may not include the C++-contributed
        // pieces. e.g. they may be bazel-out/gcc-X-glibc-Y-k8-fastbuild/ or they may be
        // bazel-out/fastbuild/. This code adds support for the non-C++ case, too.
        val cppReplacedPath = OUTPUT_PATH_CPP_PREFIX_PATTERN.matcher(rootPath).replaceFirst("")
        if (rootPath != cppReplacedPath) {
            result = function.apply(cppReplacedPath)
        }
        // Also handle Android output paths in the same way.
        val androidReplacedPath =
            OUTPUT_PATH_ANDROID_PREFIX_PATTERN.matcher(rootPath).replaceFirst("")
        if (rootPath != androidReplacedPath) {
            result = function.apply(androidReplacedPath)
        }
        return result
    }

    /**
     * Given a collection of Artifacts, returns a corresponding set of strings of the form "{root}
     * {relpath}", such as "bin x/libx.a". Such strings make assertions easier to write.
     * 
     * 
     * The returned set preserves the order of the input.
     */
    fun artifactsToStrings(
        targetConfiguration: BuildConfigurationValue,
        artifacts: Iterable<out Artifact>
    ): MutableSet<String?> {
        val rootMap: MutableMap<String?, String?> = HashMap<String?, String?>()
        AnalysisTestUtil.computeRootPaths<U?>(
            targetConfiguration.getBinDirectory(RepositoryName.MAIN),
            Function { path: String? -> rootMap.put(path, "bin") })
        // In preparation for merging genfiles/ and bin/, we don't differentiate them in tests anymore
        AnalysisTestUtil.computeRootPaths<U?>(
            targetConfiguration.getGenfilesDirectory(RepositoryName.MAIN),
            Function { path: String? -> rootMap.put(path, "bin") })

        val files: MutableSet<String?> = LinkedHashSet<String?>()
        for (artifact in artifacts) {
            val root: ArtifactRoot = artifact.getRoot()
            if (root.isSourceRoot()) {
                files.add("src " + artifact.getExecPath())
            } else {
                // Find the most specific mapping.
                val name =
                    computeRootPaths<String?>(root, Function { path: String? -> rootMap.getOrDefault(path, "/") })
                files.add(name + " " + artifact.getRootRelativePath())
            }
        }
        return files
    }

    /** Creates a [RunfilesTree] for use in tests.  */
    fun createRunfilesTree(runfilesDir: PathFragment, runfiles: Runfiles?): RunfilesTree {
        return FakeRunfilesTree(
            runfilesDir,
            runfiles,  /* repoMappingManifest= */
            null,
            RunfileSymlinksMode.SKIP,  /* buildRunfileLinks= */
            false
        )
    }

    @Throws(Exception::class)
    fun execOptions(
        targetOptions: BuildOptions, skyframeExecutor: SkyframeExecutor, handler: ExtendedEventHandler?
    ): BuildOptions? {
        // Get Starlark flags' "scope = '<string>'" info, which can control whether the flags propagate
        // to the exec config.
        val starlarkFlagScopeInfo: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.evaluateSkyKeys(
                handler,
                ImmutableList.of<E?>(
                    BuildOptionsScopeValue.Key.create(
                        targetOptions, ArrayList<Any?>(targetOptions.getStarlarkOptions().keySet())
                    )
                ),  /* keepGoing= */
                false
            )

        val targetOptionsWithScopeInfo: BuildOptions? =
            (Iterables.getOnlyElement<T?>(starlarkFlagScopeInfo.values()) as BuildOptionsScopeValue)
                .getResolvedBuildOptionsWithScopeTypes()

        return Iterables.getOnlyElement<T?>(
            ExecutionTransitionFactory.createFactory()
                .create(
                    AttributeTransitionData.builder()
                        .attributes(FakeAttributeMapper.empty())
                        .executionPlatform(targetOptions.get(PlatformOptions::class.java).getHostPlatform())
                        .analysisData(
                            skyframeExecutor.getStarlarkExecTransition(
                                targetOptionsWithScopeInfo, handler
                            )
                        )
                        .build()
                )
                .apply(
                    BuildOptionsView(
                        targetOptionsWithScopeInfo, targetOptions.getFragmentClasses()
                    ),
                    handler
                )
                .values()
        )
    }

    /**
     * An [AnalysisEnvironment] implementation that collects the actions registered.
     */
    class CollectingAnalysisEnvironment(original: AnalysisEnvironment) : AnalysisEnvironment {
        private val actions: MutableList<ActionAnalysisMetadata?> = ArrayList<ActionAnalysisMetadata?>()
        private val original: AnalysisEnvironment

        init {
            this.original = original
        }

        fun clear() {
            actions.clear()
        }

        public override fun registerAction(action: ActionAnalysisMetadata?) {
            this.actions.add(action)
            original.registerAction(action)
        }

        /** Calls [MutableActionGraph.registerAction] for all collected actions.  */
        @Throws(InterruptedException::class)
        fun registerWith(actionGraph: MutableActionGraph) {
            for (action in actions) {
                try {
                    actionGraph.registerAction(action)
                } catch (e: ActionConflictException) {
                    throw UncheckedActionConflictException(e)
                }
            }
        }

        val eventHandler: ExtendedEventHandler
            get() = original.getEventHandler()

        public override fun hasErrors(): Boolean {
            return original.hasErrors()
        }

        public override fun getDerivedArtifact(
            rootRelativePath: PathFragment?, root: ArtifactRoot?
        ): Artifact.DerivedArtifact {
            return original.getDerivedArtifact(rootRelativePath, root)
        }

        public override fun getConstantMetadataArtifact(
            rootRelativePath: PathFragment?,
            root: ArtifactRoot?
        ): Artifact {
            return original.getConstantMetadataArtifact(rootRelativePath, root)
        }

        public override fun getRunfilesArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): SpecialArtifact {
            return original.getRunfilesArtifact(rootRelativePath, root)
        }

        public override fun getTreeArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): SpecialArtifact {
            return original.getTreeArtifact(rootRelativePath, root)
        }

        public override fun getSymlinkArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): SpecialArtifact {
            return original.getSymlinkArtifact(rootRelativePath, root)
        }

        public override fun getFilesetArtifact(rootRelativePath: PathFragment?, root: ArtifactRoot?): Artifact {
            return original.getFilesetArtifact(rootRelativePath, root)
        }

        public override fun getLocalGeneratingAction(artifact: Artifact?): ActionAnalysisMetadata {
            return original.getLocalGeneratingAction(artifact)
        }

        val registeredActions: ImmutableList<ActionAnalysisMetadata>
            get() = original.getRegisteredActions()

        val skyframeEnv: SkyFunction.Environment?
            get() = null

        val starlarkSemantics: StarlarkSemantics
            get() = original.getStarlarkSemantics()

        @get:Throws(InterruptedException::class)
        val starlarkDefinedBuiltins: ImmutableMap<String?, Any?>
            get() = original.getStarlarkDefinedBuiltins()

        @get:Throws(InterruptedException::class)
        val stableWorkspaceStatusArtifact: Artifact
            get() = original.getStableWorkspaceStatusArtifact()

        @get:Throws(InterruptedException::class)
        val volatileWorkspaceStatusArtifact: Artifact
            get() = original.getVolatileWorkspaceStatusArtifact()

        @Throws(InterruptedException::class)
        public override fun declareStampSettingDep() {
            original.declareStampSettingDep()
        }

        val owner: ActionLookupKey
            get() = original.getOwner()

        val orphanArtifacts: ImmutableSet<Artifact>
            get() = original.getOrphanArtifacts()

        val treeArtifactsConflictingWithFiles: ImmutableSet<Artifact>
            get() = original.getTreeArtifactsConflictingWithFiles()

        val actionKeyContext: ActionKeyContext
            get() = original.getActionKeyContext()

        @get:Throws(InterruptedException::class)
        val mainRepoMapping: RepositoryMapping
            get() = original.getMainRepoMapping()
    }

    /** A dummy WorkspaceStatusAction.  */
    @ThreadSafety.Immutable
    class DummyWorkspaceStatusAction(stableStatus: Artifact, volatileStatus: Artifact) : WorkspaceStatusAction(
        ActionOwner.SYSTEM_ACTION_OWNER,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        ImmutableSet.of<E?>(stableStatus, volatileStatus),
        "workspace status"
    ) {
        private val stableStatus: Artifact
        private val volatileStatus: Artifact

        init {
            this.stableStatus = stableStatus
            this.volatileStatus = volatileStatus
        }

        @Throws(ActionExecutionException::class)
        public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
            try {
                FileSystemUtils.writeContent(
                    actionExecutionContext.getInputPath(stableStatus), byteArrayOf()
                )
                FileSystemUtils.writeContent(
                    actionExecutionContext.getInputPath(volatileStatus), byteArrayOf()
                )
            } catch (e: IOException) {
                throw ActionExecutionException(
                    e, this, true, CrashFailureDetails.detailedExitCodeForThrowable(e)
                )
            }
            return ActionResult.EMPTY
        }

        public override fun executeUnconditionally(): Boolean {
            return false // Some test assertions rely on this action being cached.
        }

        val isVolatile: Boolean
            get() = false

        val mnemonic: String
            get() = "DummyBuildInfoAction"

        public override fun getVolatileStatus(): Artifact {
            return volatileStatus
        }

        public override fun getStableStatus(): Artifact {
            return stableStatus
        }
    }

    /** A [WorkspaceStatusAction.Context] that does not support any operations.  */
    class DummyWorkspaceStatusActionContext : WorkspaceStatusAction.Context {
        val options: Options?
            get() {
                throw UnsupportedOperationException()
            }

        val clientEnv: ImmutableMap<String?, String?>?
            get() {
                throw UnsupportedOperationException()
            }

        val command: Command?
            get() {
                throw UnsupportedOperationException()
            }
    }

    /** A workspace status action factory that does not do any interaction with the environment.  */
    class DummyWorkspaceStatusActionFactory

        : WorkspaceStatusAction.Factory {
        public override fun createWorkspaceStatusAction(
            env: WorkspaceStatusAction.Environment
        ): WorkspaceStatusAction {
            val stableStatus: Artifact = env.createStableArtifact("build-info.txt")
            val volatileStatus: Artifact = env.createVolatileArtifact("build-changelist.txt")
            return DummyWorkspaceStatusAction(stableStatus, volatileStatus)
        }

        public override fun createDummyWorkspaceStatus(
            workspaceInfoFromDiff: WorkspaceInfoFromDiff?
        ): ImmutableSortedMap<String?, String?> {
            return ImmutableSortedMap.of<String?, String?>()
        }
    }
}
