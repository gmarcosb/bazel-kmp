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

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Tests for [ContainingPackageLookupFunction].  */
@RunWith(JUnit4::class)
class ContainingPackageLookupFunctionTest : FoundationTestCase() {
    private var deletedPackages: AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>? = null
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
        deletedPackages =
            AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>(com.google.common.collect.ImmutableSet.of<PackageIdentifier?>())
        val directories: BlazeDirectories =
            BlazeDirectories(
                ServerDirectories(rootDirectory, outputBase, outputBase),
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
        skyFunctions.put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, ContainingPackageLookupFunction())

        skyFunctions.put(
            SkyFunctions.PACKAGE_LOOKUP,
            PackageLookupFunction(
                deletedPackages,
                CrossRepositoryLabelViolationStrategy.ERROR,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        )
        skyFunctions.put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
        skyFunctions.put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.NOOP)
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
            SkyFunctions.REPOSITORY_DIRECTORY,
            RepositoryFetchFunction(
                com.google.common.collect.ImmutableMap::of,
                com.google.common.collect.ImmutableMap::of,
                directories,
                LocalRepoContentsCache()
            )
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
        PrecomputedValue.BUILD_ID.set(differencer, UUID.randomUUID())
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(differencer, pkgLocator.get())
        PrecomputedValue.STARLARK_SEMANTICS.set(differencer, StarlarkSemantics.DEFAULT)
        RepoDefinitionFunction.REPOSITORY_OVERRIDES.set(
            differencer,
            com.google.common.collect.ImmutableMap.of<K?, V?>()
        )
        RepositoryDirectoryValue.FETCH_DISABLED.set(differencer, false)
        RepositoryDirectoryValue.FORCE_FETCH.set(
            differencer, RepositoryDirectoryValue.FORCE_FETCH_DISABLED
        )
        RepositoryDirectoryValue.VENDOR_DIRECTORY.set(differencer, java.util.Optional.empty<T?>())
    }

    @Throws(java.lang.InterruptedException::class)
    private fun lookupContainingPackage(packageName: String?): ContainingPackageLookupValue {
        return lookupContainingPackage(PackageIdentifier.createInMainRepo(packageName))
    }

    @Throws(java.lang.InterruptedException::class)
    private fun lookupContainingPackage(packageIdentifier: PackageIdentifier?): ContainingPackageLookupValue {
        val key: SkyKey = ContainingPackageLookupValue.key(packageIdentifier)
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        return evaluator
            .< ContainingPackageLookupValue > evaluate < ContainingPackageLookupValue ? > (com.google.common.collect.ImmutableList.of<E?>(
            key
        ), evaluationContext)
        .get(key)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun lookupPackage(packageIdentifier: PackageIdentifier?): PackageLookupValue {
        val key: SkyKey = PackageLookupValue.key(packageIdentifier)
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        return evaluator
            .< PackageLookupValue > evaluate < PackageLookupValue ? > (com.google.common.collect.ImmutableList.of<E?>(
            key
        ), evaluationContext)
        .get(key)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoContainingPackage() {
        val value: ContainingPackageLookupValue = lookupContainingPackage("a/b")
        assertThat(value.hasContainingPackage()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testContainingPackageIsParent() {
        scratch.file("a/BUILD")
        val value: ContainingPackageLookupValue = lookupContainingPackage("a/b")
        assertThat(value.hasContainingPackage()).isTrue()
        assertThat(value.containingPackageName).isEqualTo(PackageIdentifier.createInMainRepo("a"))
        assertThat(value.containingPackageRoot).isEqualTo(Root.fromPath(rootDirectory))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testContainingPackageIsSelf() {
        scratch.file("a/b/BUILD")
        val value: ContainingPackageLookupValue = lookupContainingPackage("a/b")
        assertThat(value.hasContainingPackage()).isTrue()
        assertThat(value.containingPackageName)
            .isEqualTo(PackageIdentifier.createInMainRepo("a/b"))
        assertThat(value.containingPackageRoot).isEqualTo(Root.fromPath(rootDirectory))
    }

    @org.junit.Test
    fun testEqualsAndHashCodeContract() {
        val valueA1: ContainingPackageLookupValue? = ContainingPackageLookupValue.NONE
        val valueA2: ContainingPackageLookupValue? = ContainingPackageLookupValue.NONE
        val valueB1: ContainingPackageLookupValue? =
            ContainingPackageLookupValue.withContainingPackage(
                PackageIdentifier.createInMainRepo("b"), Root.fromPath(rootDirectory)
            )
        val valueB2: ContainingPackageLookupValue? =
            ContainingPackageLookupValue.withContainingPackage(
                PackageIdentifier.createInMainRepo("b"), Root.fromPath(rootDirectory)
            )
        val cFrag: PackageIdentifier? = PackageIdentifier.createInMainRepo("c")
        val valueC1: ContainingPackageLookupValue? =
            ContainingPackageLookupValue.withContainingPackage(cFrag, Root.fromPath(rootDirectory))
        val valueC2: ContainingPackageLookupValue? =
            ContainingPackageLookupValue.withContainingPackage(cFrag, Root.fromPath(rootDirectory))
        val valueCOther: ContainingPackageLookupValue? =
            ContainingPackageLookupValue.withContainingPackage(
                cFrag, Root.fromPath(rootDirectory.getRelative("other_root"))
            )
        EqualsTester()
            .addEqualityGroup(valueA1, valueA2)
            .addEqualityGroup(valueB1, valueB2)
            .addEqualityGroup(valueC1, valueC2)
            .addEqualityGroup(valueCOther)
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistentExternalRepositoryErrorReason() {
        val identifier: PackageIdentifier? =
            PackageIdentifier.create("some_repo", PathFragment.create(":atarget"))
        val value: ContainingPackageLookupValue = lookupContainingPackage(identifier)
        assertThat(value.hasContainingPackage()).isFalse()
        assertThat(value.getClass()).isEqualTo(NoContainingPackage::class.java)
        assertThat(value.getReasonForNoContainingPackage())
            .isEqualTo(
                "The repository '@@some_repo' could not be resolved: Repository '@@some_repo' is not"
                        + " defined"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidPackageLabelErrorReason() {
        val value: ContainingPackageLookupValue = lookupContainingPackage("invalidpackagename:42/BUILD")
        assertThat(value.hasContainingPackage()).isFalse()
        assertThat(value.getClass()).isEqualTo(NoContainingPackage::class.java)
        // As for invalid package name we continue to climb up the parent packages,
        // we will find the top-level package with the path "" - empty string.
        assertThat(value.getReasonForNoContainingPackage()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeletedPackageErrorReason() {
        val identifier: PackageIdentifier = PackageIdentifier.createInMainRepo("deletedpackage")
        deletedPackages.set(com.google.common.collect.ImmutableSet.of<PackageIdentifier?>(identifier))
        scratch.file("BUILD")

        val packageLookupValue: PackageLookupValue = lookupPackage(identifier)
        assertThat(packageLookupValue.packageExists()).isFalse()
        assertThat(packageLookupValue.errorReason).isEqualTo(ErrorReason.DELETED_PACKAGE)
        assertThat(packageLookupValue.errorMsg)
            .isEqualTo("Package is considered deleted due to --deleted_packages")

        val value: ContainingPackageLookupValue = lookupContainingPackage(identifier)
        assertThat(value.hasContainingPackage()).isTrue()
        assertThat(value.containingPackageName.toString()).isEmpty()
        assertThat(value.getClass()).isEqualTo(ContainingPackage::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoBuildFileErrorReason() {
        val value: ContainingPackageLookupValue = lookupContainingPackage("abc")
        assertThat(value.hasContainingPackage()).isFalse()
        assertThat(value.getClass()).isEqualTo(NoContainingPackage::class.java)
        assertThat(value.getReasonForNoContainingPackage()).isNull()
    }
}
