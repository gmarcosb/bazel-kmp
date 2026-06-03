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

import com.google.devtools.build.lib.actions.FileStateValue

/** Tests for [PackageLookupFunction].  */
abstract class PackageLookupFunctionTest : FoundationTestCase() {
    private var deletedPackages: AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>? = null
    private var evaluator: MemoizingEvaluator? = null
    private var differencer: RecordingDifferencer? = null
    private var emptyPackagePath: Path? = null

    protected abstract fun crossRepositoryLabelViolationStrategy(): CrossRepositoryLabelViolationStrategy?

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        emptyPackagePath = rootDirectory.getRelative("somewhere/else")
        scratch.file("parentpackage/BUILD")

        val analysisMock: AnalysisMock = AnalysisMock.get()
        val pkgLocator: AtomicReference<PathPackageLocator?> =
            AtomicReference<PathPackageLocator?>(
                PathPackageLocator(
                    outputBase,
                    com.google.common.collect.ImmutableList.of<E?>(
                        Root.fromPath(emptyPackagePath),
                        Root.fromPath(rootDirectory)
                    ),
                    BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
                )
            )
        deletedPackages =
            AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>(com.google.common.collect.ImmutableSet.of<PackageIdentifier?>())
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

        val ruleClassProvider: RuleClassProvider = analysisMock.createRuleClassProvider()
        val skyFunctions: MutableMap<SkyFunctionName?, SkyFunction?> = HashMap<SkyFunctionName?, SkyFunction?>()
        skyFunctions.put(
            SkyFunctions.PACKAGE_LOOKUP,
            PackageLookupFunction(
                deletedPackages,
                crossRepositoryLabelViolationStrategy(),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        )
        skyFunctions.put(SkyFunctions.PACKAGE, PackageFunction.newBuilder().build())
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
        skyFunctions.put(
            SkyFunctions.REPO_FILE,
            RepoFileFunction(
                ruleClassProvider.getBazelStarlarkEnvironment(),
                Root.fromPath(directories.getWorkspace())
            )
        )
        skyFunctions.put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.INSTANCE)
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

        differencer = SequencedRecordingDifferencer()
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
    protected fun lookupPackage(packageName: String?): PackageLookupValue {
        return lookupPackage(PackageIdentifier.createInMainRepo(packageName))
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun lookupPackage(packageId: PackageIdentifier?): PackageLookupValue {
        val key: SkyKey = PackageLookupValue.key(packageId)
        return lookupPackage(key).get(key)
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun lookupPackage(packageIdentifierSkyKey: SkyKey): EvaluationResult<PackageLookupValue?> {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(false)
                .setParallelism(SkyframeExecutor.DEFAULT_THREAD_COUNT)
                .setEventHandler(NullEventHandler.INSTANCE)
                .build()
        return evaluator.evaluate(
            com.google.common.collect.ImmutableList.of<E?>(packageIdentifierSkyKey),
            evaluationContext
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoBuildFile() {
        scratch.file("parentpackage/nobuildfile/foo.txt")
        val packageLookupValue: PackageLookupValue = lookupPackage("parentpackage/nobuildfile")
        assertThat(packageLookupValue.packageExists()).isFalse()
        assertThat(packageLookupValue.errorReason).isEqualTo(ErrorReason.NO_BUILD_FILE)
        assertThat(packageLookupValue.errorMsg).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoBuildFileAndNoParentPackage() {
        scratch.file("noparentpackage/foo.txt")
        val packageLookupValue: PackageLookupValue = lookupPackage("noparentpackage")
        assertThat(packageLookupValue.packageExists()).isFalse()
        assertThat(packageLookupValue.errorReason).isEqualTo(ErrorReason.NO_BUILD_FILE)
        assertThat(packageLookupValue.errorMsg).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeletedPackage() {
        scratch.file("parentpackage/deletedpackage/BUILD")
        deletedPackages.set(
            com.google.common.collect.ImmutableSet.of<E?>(PackageIdentifier.createInMainRepo("parentpackage/deletedpackage"))
        )
        val packageLookupValue: PackageLookupValue = lookupPackage("parentpackage/deletedpackage")
        assertThat(packageLookupValue.packageExists()).isFalse()
        assertThat(packageLookupValue.errorReason).isEqualTo(ErrorReason.DELETED_PACKAGE)
        assertThat(packageLookupValue.errorMsg).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIgnoredPackage() {
        scratch.file("ignored/subdir/BUILD")
        scratch.file("ignored/BUILD")
        val ignored: Path? =
            scratch.overwriteFile(
                IgnoredSubdirectoriesFunction.BAZELIGNORE_REPOSITORY_RELATIVE_PATH.getPathString(),
                "ignored"
            )

        val pkgs: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("ignored/subdir", "ignored")
        for (pkg in pkgs) {
            val packageLookupValue: PackageLookupValue = lookupPackage(pkg)
            assertThat(packageLookupValue.packageExists()).isFalse()
            assertThat(packageLookupValue.errorReason).isEqualTo(ErrorReason.DELETED_PACKAGE)
            assertThat(packageLookupValue.errorMsg).isNotNull()
        }

        scratch.overwriteFile(
            IgnoredSubdirectoriesFunction.BAZELIGNORE_REPOSITORY_RELATIVE_PATH.getPathString(),
            "not_ignored"
        )
        val rootedIgnoreFile: RootedPath? =
            RootedPath.toRootedPath(
                root, IgnoredSubdirectoriesFunction.BAZELIGNORE_REPOSITORY_RELATIVE_PATH
            )
        differencer.invalidate(com.google.common.collect.ImmutableSet.of<E?>(FileStateValue.key(rootedIgnoreFile)))
        for (pkg in pkgs) {
            val packageLookupValue: PackageLookupValue = lookupPackage(pkg)
            assertThat(packageLookupValue.packageExists()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidPackageName() {
        scratch.file("parentpackage/invalidpackagename:42/BUILD")
        val packageLookupValue: PackageLookupValue = lookupPackage("parentpackage/invalidpackagename:42")
        assertThat(packageLookupValue.packageExists()).isFalse()
        assertThat(packageLookupValue.errorReason).isEqualTo(ErrorReason.INVALID_PACKAGE_NAME)
        assertThat(packageLookupValue.errorMsg).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryNamedBuild() {
        scratch.dir("parentpackage/isdirectory/BUILD")
        val packageLookupValue: PackageLookupValue = lookupPackage("parentpackage/isdirectory")
        assertThat(packageLookupValue.packageExists()).isFalse()
        assertThat(packageLookupValue.errorReason).isEqualTo(ErrorReason.NO_BUILD_FILE)
        assertThat(packageLookupValue.errorMsg).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEverythingIsGood_BUILD() {
        scratch.file("parentpackage/everythinggood/BUILD")
        val packageLookupValue: PackageLookupValue = lookupPackage("parentpackage/everythinggood")
        assertThat(packageLookupValue.packageExists()).isTrue()
        assertThat(packageLookupValue.root).isEqualTo(Root.fromPath(rootDirectory))
        assertThat(packageLookupValue.buildFileName).isEqualTo(BuildFileName.BUILD)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEverythingIsGood_BUILD_bazel() {
        scratch.file("parentpackage/everythinggood/BUILD.bazel")
        val packageLookupValue: PackageLookupValue = lookupPackage("parentpackage/everythinggood")
        assertThat(packageLookupValue.packageExists()).isTrue()
        assertThat(packageLookupValue.root).isEqualTo(Root.fromPath(rootDirectory))
        assertThat(packageLookupValue.buildFileName).isEqualTo(BuildFileName.BUILD_DOT_BAZEL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEverythingIsGood_both() {
        scratch.file("parentpackage/everythinggood/BUILD")
        scratch.file("parentpackage/everythinggood/BUILD.bazel")
        val packageLookupValue: PackageLookupValue = lookupPackage("parentpackage/everythinggood")
        assertThat(packageLookupValue.packageExists()).isTrue()
        assertThat(packageLookupValue.root).isEqualTo(Root.fromPath(rootDirectory))
        assertThat(packageLookupValue.buildFileName).isEqualTo(BuildFileName.BUILD_DOT_BAZEL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildFilesInMultiplePackagePaths() {
        scratch.file(emptyPackagePath.getPathString() + "/foo/BUILD")
        scratch.file("foo/BUILD.bazel")

        // BUILD file in the first package path should be preferred to BUILD.bazel in the second.
        val packageLookupValue: PackageLookupValue = lookupPackage("foo")
        assertThat(packageLookupValue.packageExists()).isTrue()
        assertThat(packageLookupValue.root).isEqualTo(Root.fromPath(emptyPackagePath))
        assertThat(packageLookupValue.buildFileName).isEqualTo(BuildFileName.BUILD)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyPackageName() {
        scratch.file("BUILD")
        val packageLookupValue: PackageLookupValue = lookupPackage("")
        assertThat(packageLookupValue.packageExists()).isTrue()
        assertThat(packageLookupValue.root).isEqualTo(Root.fromPath(rootDirectory))
        assertThat(packageLookupValue.buildFileName).isEqualTo(BuildFileName.BUILD)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invisibleRepo_main() {
        scratch.file("BUILD")
        val packageLookupValue: PackageLookupValue =
            lookupPackage(
                PackageIdentifier.create(
                    RepositoryName.MAIN.toNonVisible(RepositoryName.BAZEL_TOOLS),
                    PathFragment.EMPTY_FRAGMENT
                )
            )
        assertThat(packageLookupValue.packageExists()).isFalse()
        assertThat(packageLookupValue.errorReason).isEqualTo(ErrorReason.REPOSITORY_NOT_FOUND)
        com.google.common.truth.Subject.contains("No repository visible as")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invisibleRepo_nonMain() {
        val packageLookupValue: PackageLookupValue =
            lookupPackage(
                PackageIdentifier.create(
                    RepositoryName.createUnvalidated("local").toNonVisible(RepositoryName.BAZEL_TOOLS),
                    PathFragment.EMPTY_FRAGMENT
                )
            )
        assertThat(packageLookupValue.packageExists()).isFalse()
        assertThat(packageLookupValue.errorReason).isEqualTo(ErrorReason.REPOSITORY_NOT_FOUND)
        com.google.common.truth.Subject.contains("No repository visible as")
    }

    @org.junit.Test
    fun testPackageLookupValueHashCodeAndEqualsContract() {
        val root1: Root? = Root.fromPath(rootDirectory.getRelative("root1"))
        val root2: Root? = Root.fromPath(rootDirectory.getRelative("root2"))
        // Our (seeming) duplication of parameters here is intentional. Some of the subclasses of
        // PackageLookupValue are supposed to have reference equality semantics, and some are supposed
        // to have logical equality semantics.
        EqualsTester()
            .addEqualityGroup(
                PackageLookupValue.success(root1, BuildFileName.BUILD),
                PackageLookupValue.success(root1, BuildFileName.BUILD)
            )
            .addEqualityGroup(
                PackageLookupValue.success(root2, BuildFileName.BUILD),
                PackageLookupValue.success(root2, BuildFileName.BUILD)
            )
            .addEqualityGroup(
                PackageLookupValue.NO_BUILD_FILE_VALUE, PackageLookupValue.NO_BUILD_FILE_VALUE
            )
            .addEqualityGroup(
                PackageLookupValue.DELETED_PACKAGE_VALUE, PackageLookupValue.DELETED_PACKAGE_VALUE
            )
            .addEqualityGroup(
                PackageLookupValue.invalidPackageName("nope1"),
                PackageLookupValue.invalidPackageName("nope1")
            )
            .addEqualityGroup(
                PackageLookupValue.invalidPackageName("nope2"),
                PackageLookupValue.invalidPackageName("nope2")
            )
            .testEquals()
    }

    /**
     * Runs all tests in the base [PackageLookupFunctionTest] class with the [ ][CrossRepositoryLabelViolationStrategy.IGNORE] enum set, and also additional tests specific to
     * that setting.
     */
    @RunWith(JUnit4::class)
    class IgnoreLabelViolationsTest : PackageLookupFunctionTest() {
        override fun crossRepositoryLabelViolationStrategy(): CrossRepositoryLabelViolationStrategy {
            return CrossRepositoryLabelViolationStrategy.IGNORE
        }
    }

    /**
     * Runs all tests in the base [PackageLookupFunctionTest] class with the [ ][CrossRepositoryLabelViolationStrategy.ERROR] enum set, and also additional tests specific to
     * that setting.
     */
    @RunWith(JUnit4::class)
    class ErrorLabelViolationsTest : PackageLookupFunctionTest() {
        override fun crossRepositoryLabelViolationStrategy(): CrossRepositoryLabelViolationStrategy {
            return CrossRepositoryLabelViolationStrategy.ERROR
        }
    }
}
