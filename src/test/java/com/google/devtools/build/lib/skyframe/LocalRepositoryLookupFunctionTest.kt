// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Tests for [LocalRepositoryLookupFunction].  */
@RunWith(JUnit4::class)
class LocalRepositoryLookupFunctionTest : FoundationTestCase() {
    private var evaluator: MemoizingEvaluator? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        val analysisMock: AnalysisMock = AnalysisMock.get()
        val pkgLocator: AtomicReference<PathPackageLocator?> =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(rootDirectory, outputBase, rootDirectory),
                rootDirectory,
                analysisMock.productName
            )
        val externalFilesHelper: ExternalFilesHelper? =
            ExternalFilesHelper.createForTesting(
                pkgLocator,
                ExternalFileAction.DEPEND_ON_EXTERNAL_PKG_FOR_EXTERNAL_REPO_PATHS,
                directories
            )

        val skyFunctions: MutableMap<SkyFunctionName?, SkyFunction?> = HashMap<SkyFunctionName?, SkyFunction?>()
        skyFunctions.put(
            SkyFunctions.PACKAGE_LOOKUP,
            PackageLookupFunction(
                AtomicReference<V?>(),
                CrossRepositoryLabelViolationStrategy.ERROR,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        )
        skyFunctions.put(
            FileStateKey.FILE_STATE,
            FileStateFunction(
                com.google.common.base.Suppliers.ofInstance<T?>(TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())),
                SyscallCache.NO_CACHE,
                externalFilesHelper
            )
        )
        skyFunctions.put(SkyFunctions.FILE, FileFunction(pkgLocator, directories))
        skyFunctions.put(SkyFunctions.DIRECTORY_LISTING, DirectoryListingFunction())
        skyFunctions.put(
            SkyFunctions.DIRECTORY_LISTING_STATE,
            DirectoryListingStateFunction(externalFilesHelper, SyscallCache.NO_CACHE)
        )
        skyFunctions.put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, LocalRepositoryLookupFunction())
        skyFunctions.put(
            FileSymlinkCycleUniquenessFunction.NAME, FileSymlinkCycleUniquenessFunction()
        )
        skyFunctions.put(
            SkyFunctions.REPOSITORY_MAPPING,
            object : SkyFunction() {
                public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
                    return RepositoryMappingValue.VALUE_FOR_EMPTY_ROOT_MODULE
                }
            })
        skyFunctions.put(
            RepoDefinitionValue.REPO_DEFINITION,
            object : SkyFunction() {
                public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
                    return RepoDefinitionValue.NOT_FOUND
                }
            })

        val differencer: RecordingDifferencer = SequencedRecordingDifferencer()
        evaluator = InMemoryMemoizingEvaluator(skyFunctions, differencer)
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get())
        PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun lookupDirectory(directory: RootedPath?): LocalRepositoryLookupValue {
        val key: SkyKey = createKey(directory)
        return lookupDirectory(key).get(key)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun lookupDirectory(directoryKey: SkyKey): EvaluationResult<LocalRepositoryLookupValue?> {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        return evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(directoryKey), evaluationContext)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoPath() {
        val repositoryLookupValue: LocalRepositoryLookupValue =
            lookupDirectory(
                RootedPath.toRootedPath(Root.fromPath(rootDirectory), PathFragment.EMPTY_FRAGMENT)
            )
        assertThat(repositoryLookupValue).isNotNull()
        assertThat(repositoryLookupValue.repository).isEqualTo(RepositoryName.MAIN)
        assertThat(repositoryLookupValue.path).isEqualTo(PathFragment.EMPTY_FRAGMENT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActualPackage() {
        scratch.file("some/path/BUILD")

        val repositoryLookupValue: LocalRepositoryLookupValue =
            lookupDirectory(
                RootedPath.toRootedPath(
                    Root.fromPath(rootDirectory), PathFragment.create("some/path")
                )
            )
        assertThat(repositoryLookupValue).isNotNull()
        assertThat(repositoryLookupValue.repository).isEqualTo(RepositoryName.MAIN)
        assertThat(repositoryLookupValue.path).isEqualTo(PathFragment.EMPTY_FRAGMENT)
    }

    companion object {
        private fun createKey(directory: RootedPath?): SkyKey {
            return LocalRepositoryLookupValue.key(directory)
        }
    }
}
