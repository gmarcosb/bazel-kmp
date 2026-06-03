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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

internal abstract class ArtifactFunctionTestCase {
    protected var actions: LinkedHashSet<ActionAnalysisMetadata?>? = null
    protected var fastDigest: Boolean = false
    protected var differencer: RecordingDifferencer = SequencedRecordingDifferencer()
    protected var evaluator: MemoizingEvaluator? = null
    protected var root: Path? = null
    protected val actionKeyContext: ActionKeyContext = ActionKeyContext()

    /**
     * The test action execution function. The Skyframe evaluator's action execution function
     * delegates to this one.
     */
    protected var delegateActionExecutionFunction: SkyFunction? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun baseSetUp() {
        val fs: CustomInMemoryFs = com.google.devtools.build.lib.skyframe.ArtifactFunctionTestCase.CustomInMemoryFs()
        setupRoot(fs)
        val pkgLocator: AtomicReference<PathPackageLocator?> =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    root.getFileSystem().getPath("/outputbase"),
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(root)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(root, root, root), root, TestConstants.PRODUCT_NAME
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(
                pkgLocator,
                ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
                directories
            )
        differencer = SequencedRecordingDifferencer()
        evaluator =
            InMemoryMemoizingEvaluator(
                com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                    .put(
                        FileStateKey.FILE_STATE,
                        FileStateFunction(
                            com.google.common.base.Suppliers.ofInstance<T?>(
                                TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
                            ),
                            SyscallCache.NO_CACHE,
                            externalFilesHelper
                        )
                    )
                    .put(SkyFunctions.FILE, FileFunction(pkgLocator, directories))
                    .put(
                        Artifact.ARTIFACT,
                        ArtifactFunction(
                            { true },
                            MetadataConsumerForMetrics.NO_OP,
                            SyscallCache.NO_CACHE,  /* actionExecutor= */
                            null,  // only used by remote analysis caching
                            { RemoteAnalysisCacheDeps.createDisabled() })
                    )
                    .put(
                        SkyFunctions.ACTION_EXECUTION,
                        com.google.devtools.build.lib.skyframe.ArtifactFunctionTestCase.SimpleActionExecutionFunction()
                    )
                    .put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
                    .put(
                        SkyFunctions.PACKAGE_LOOKUP,
                        PackageLookupFunction(
                            null,
                            CrossRepositoryLabelViolationStrategy.ERROR,
                            BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                        )
                    )
                    .put(
                        SkyFunctions.ACTION_TEMPLATE_EXPANSION,
                        ActionTemplateExpansionFunction(actionKeyContext)
                    )
                    .build(),
                differencer
            )
        PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID())
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get())
        actions = LinkedHashSet<ActionAnalysisMetadata?>()
    }

    @Throws(IOException::class)
    protected fun setupRoot(fs: CustomInMemoryFs) {
        val tmpDir: Path = fs.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir())
        root = tmpDir.getChild("root")
        root.createDirectoryAndParents()
        FileSystemUtils.createEmptyFile(root.getRelative("WORKSPACE"))
    }

    /** ActionExecutionFunction that delegates to our delegate.  */
    private inner class SimpleActionExecutionFunction : SkyFunction {
        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
            return delegateActionExecutionFunction.compute(skyKey, env)
        }

        public override fun extractTag(skyKey: SkyKey?): String {
            return delegateActionExecutionFunction.extractTag(skyKey)
        }
    }

    /** InMemoryFileSystem that can pretend to do a fast digest.  */
    protected open inner class CustomInMemoryFs internal constructor() : InMemoryFileSystem(DigestHashFunction.SHA256) {
        @Throws(IOException::class)
        public override fun getFastDigest(path: PathFragment?): ByteArray? {
            return if (fastDigest) getDigest(path) else null
        }
    }

    companion object {
        val ALL_OWNER: ActionLookupKey = InjectedActionLookupKey("all_owner")

        @Throws(IOException::class)
        protected fun writeFile(path: Path, contents: String?) {
            path.getParentDirectory().createDirectoryAndParents()
            FileSystemUtils.writeContentAsLatin1(path, contents)
        }
    }
}
