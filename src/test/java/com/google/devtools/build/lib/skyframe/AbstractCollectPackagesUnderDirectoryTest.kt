// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionKeyContext

/**
 * Abstract base class for testing an implementation of a [SkyFunction] for [ ][SkyFunctions.COLLECT_PACKAGES_UNDER_DIRECTORY].
 */
abstract class AbstractCollectPackagesUnderDirectoryTest {
    protected var fileSystem: FileSystem? = null
    protected var root: Root? = null
    protected var workingDir: Path? = null
    private var scratch: Scratch? = null
    protected var directories: BlazeDirectories? = null
    private var eventCollector: EventCollector? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    protected var ruleClassProvider: ConfiguredRuleClassProvider? = null
    private var evaluator: MemoizingEvaluator? = null

    @Before
    @Throws(IOException::class)
    fun setUp() {
        fileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        workingDir = fileSystem.getPath(this.workspacePathString)
        workingDir.createDirectoryAndParents()
        root = Root.fromPath(workingDir)
        scratch = Scratch(workingDir)
        directories =
            BlazeDirectories(
                ServerDirectories(
                    fileSystem.getPath("/install"),
                    fileSystem.getPath("/output"),
                    fileSystem.getPath("/user_root"),
                    fileSystem.getPath("/execroot"),
                    if (useVirtualSourceRoot()) root else null,
                    FAKE_INSTALL_MD5_STRING
                ),
                workingDir,  /* productName= */
                "DummyProductNameForUnitTests"
            )
        eventCollector = EventCollector()
        reporter = com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
        reporter.addHandler(eventCollector)
    }

    protected abstract val workspacePathString: String?

    protected abstract val buildFileNamesByPriority: MutableList<BuildFileName>?

    protected abstract val extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?

    protected abstract fun makeSkyframeExecutorFactory(): SkyframeExecutorFactory?

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun useVirtualSourceRoot(): Boolean

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noPackageErrors() {
        initEvaluator()

        scratch.file("BUILD")
        scratch.dir("a1/b1/c1")
        scratch.file("a1/b1/c1/BUILD")
        scratch.dir("a1/b1/c2")
        scratch.dir("a1/b2/c1")
        scratch.dir("a1/b2/c2")
        scratch.dir("a2/b1/c1")
        scratch.file("a2/b1/c1/BUILD")
        scratch.dir("a2/b1/c2")
        scratch.dir("a2/b2/c1")
        scratch.dir("a2/b2/c2")
        scratch.file("a2/b2/c2/BUILD")

        run {
            val collectPackagesUnderDirectoryValue: CollectPackagesUnderDirectoryValue =
                getCollectPackagesUnderDirectoryValue("")
            assertThat(collectPackagesUnderDirectoryValue.isDirectoryPackage).isTrue()
            assertThat(
                collectPackagesUnderDirectoryValue
                    .getSubdirectoryTransitivelyContainsPackagesOrErrors()
            )
                .containsExactly(rootedPath("tools"), rootedPath("a1"), rootedPath("a2"))
        }

        run {
            val collectPackagesUnderDirectoryValue: CollectPackagesUnderDirectoryValue =
                getCollectPackagesUnderDirectoryValue("a1")
            assertThat(collectPackagesUnderDirectoryValue.isDirectoryPackage).isFalse()
            assertThat(
                collectPackagesUnderDirectoryValue
                    .getSubdirectoryTransitivelyContainsPackagesOrErrors()
            )
                .containsExactly(rootedPath("a1/b1"))
        }

        run {
            val collectPackagesUnderDirectoryValue: CollectPackagesUnderDirectoryValue =
                getCollectPackagesUnderDirectoryValue("a2/b1")
            assertThat(collectPackagesUnderDirectoryValue.isDirectoryPackage).isFalse()
            assertThat(
                collectPackagesUnderDirectoryValue
                    .getSubdirectoryTransitivelyContainsPackagesOrErrors()
            )
                .containsExactly(rootedPath("a2/b1/c1"))
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packageErrors() {
        initEvaluator()

        scratch.dir("a1/b1")
        scratch.file("a1/b1/BUILD", "xxx")
        scratch.dir("a1/b2")
        scratch.dir("a2/b1")
        scratch.dir("a2/b2")
        scratch.file("a2/b2/BUILD", "yyy")

        val collectPackagesUnderDirectoryValue: CollectPackagesUnderDirectoryValue =
            getCollectPackagesUnderDirectoryValue("")
        assertThat(collectPackagesUnderDirectoryValue.isDirectoryPackage).isFalse()
        assertThat(
            collectPackagesUnderDirectoryValue
                .getSubdirectoryTransitivelyContainsPackagesOrErrors()
        )
            .containsExactly(rootedPath("tools"), rootedPath("a1"), rootedPath("a2"))
        MoreAsserts.assertContainsEvent(eventCollector, "Loading package: a1/b1")
        MoreAsserts.assertContainsEvent(eventCollector, "a1/b1/BUILD:1:1: name 'xxx' is not defined")
        MoreAsserts.assertContainsEvent(eventCollector, "Loading package: a2/b2")
        MoreAsserts.assertContainsEvent(eventCollector, "a2/b2/BUILD:1:1: name 'yyy' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinks() {
        initEvaluator()

        val a1DirPath: Path = scratch.dir("a1")
        scratch.dir("a1/b1/c1")
        val a1CircularPath: Path? = scratch.resolve("a1/circular")
        FileSystemUtils.ensureSymbolicLink(a1CircularPath, a1CircularPath)
        scratch.file("a1/b1/c1/BUILD")
        FileSystemUtils.ensureSymbolicLink(scratch.resolve("a2"), a1DirPath)
        scratch.dir("a3")
        scratch.file(
            "a3/DONT_FOLLOW_SYMLINKS_WHEN_TRAVERSING_THIS_DIRECTORY_VIA_A_RECURSIVE_TARGET_PATTERN"
        )
        FileSystemUtils.ensureSymbolicLink(scratch.resolve("a3/dirlink"), a1DirPath)

        val collectPackagesUnderDirectoryValue: CollectPackagesUnderDirectoryValue =
            getCollectPackagesUnderDirectoryValue("")
        assertThat(collectPackagesUnderDirectoryValue.isDirectoryPackage).isFalse()
        assertThat(
            collectPackagesUnderDirectoryValue
                .getSubdirectoryTransitivelyContainsPackagesOrErrors()
        )
            .containsExactly(rootedPath("tools"), rootedPath("a1"), rootedPath("a2"))
        MoreAsserts.assertContainsEvent(eventCollector, "Loading package: a1/b1/c1")
        MoreAsserts.assertContainsEvent(eventCollector, "Loading package: a2/b1/c1")
        MoreAsserts.assertDoesNotContainEvent(eventCollector, "Loading package: a3/b1/c1")
        MoreAsserts.assertContainsEvent(
            eventCollector,
            "Failed to get information about path, for a1/circular, skipping: Symlink cycle"
        )
        MoreAsserts.assertContainsEvent(
            eventCollector,
            "Failed to get information about path, for a2/circular, skipping: Symlink cycle"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun excludedPaths() {
        initEvaluator()

        scratch.dir("a1/b1/c1")
        scratch.file("a1/b1/c1/BUILD")
        scratch.dir("a1/b1/c2")
        scratch.file("a1/b1/c2/BUILD")
        scratch.dir("a1/b2/c1")
        scratch.file("a1/b2/c1/BUILD")
        scratch.dir("a1/b2/c2")
        scratch.file("a1/b2/c2/BUILD")
        scratch.dir("a2/b1/c1")
        scratch.file("a2/b1/c1/BUILD")
        scratch.dir("a2/b1/c2")
        scratch.file("a2/b1/c2/BUILD")
        scratch.dir("a2/b2/c1")
        scratch.file("a2/b2/c1/BUILD")
        scratch.dir("a2/b2/c2")
        scratch.file("a2/b2/c2/BUILD")

        val collectPackagesUnderDirectoryValue: CollectPackagesUnderDirectoryValue =
            getCollectPackagesUnderDirectoryValue(
                "",  /* excludedPaths= */
                com.google.common.collect.ImmutableSet.of<E?>(
                    PathFragment.create("a1"),
                    PathFragment.create("a2/b1"),
                    PathFragment.create("a2/b2/c2")
                )
            )
        assertThat(collectPackagesUnderDirectoryValue.isDirectoryPackage).isFalse()
        // There is not supposed to be a map entry for excluded subdirectories.
        assertThat(
            collectPackagesUnderDirectoryValue
                .getSubdirectoryTransitivelyContainsPackagesOrErrors()
        )
            .containsExactly(rootedPath("tools"), rootedPath("a2"))
        MoreAsserts.assertDoesNotContainEvents(
            eventCollector,
            "Loading package: a1/b1/c1",
            "Loading package: a1/b1/c2",
            "Loading package: a1/b2/c1",
            "Loading package: a1/b1/c2",
            "Loading package: a2/b1/c1",
            "Loading package: a2/b1/c2",
            "Loading package: a2/b2/c2"
        )
        MoreAsserts.assertContainsEvent(eventCollector, "Loading package: a2/b2/c1")
    }

    @Throws(
        AbruptExitException::class,
        java.lang.InterruptedException::class,
        IOException::class,
        OptionsParsingException::class
    )
    private fun initEvaluator() {
        val pathPackageLocator: PathPackageLocator? =
            PathPackageLocator.createWithoutExistenceCheck(
                directories.getOutputBase(),
                com.google.common.collect.ImmutableList.of<E?>(root),
                this.buildFileNamesByPriority
            )
        val packageOptions: PackageOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(PackageOptions::class.java)
        packageOptions.setPackagePath(com.google.common.collect.ImmutableList.of<E?>(this.workspacePathString))
        scratch.file("tools/BUILD")
        scratch.file("tools/empty_prelude.bzl")
        ruleClassProvider =
            Builder()
                .setRunfilesPrefix("workspace")
                .setPrelude("//tools:empty_prelude.bzl")
                .useDummyBuiltinsBzl()
                .setPrerequisiteValidator(MinimalPrerequisiteValidator())
                .build()
        val skyframeExecutor: SkyframeExecutor =
            makeSkyframeExecutorFactory()
                .create(
                    (TestPackageFactoryBuilderFactory.getInstance()
                        .builder(directories) as PackageFactoryBuilderWithSkyframeForTesting)
                        .setExtraSkyFunctions(this.extraSkyFunctions)
                        .build(ruleClassProvider, fileSystem),
                    fileSystem,
                    directories,
                    ActionKeyContext(),  /* workspaceStatusActionFactory= */
                    null,  /* diffAwarenessFactories= */
                    com.google.common.collect.ImmutableList.of<E?>(),
                    this.extraSkyFunctions,
                    SyscallCache.NO_CACHE,  /* allowExternalRepositories= */
                    false,  /* repoContentsCachePathSupplier= */
                    { null },
                    SkyframeExecutor.SkyKeyStateReceiver.NULL_INSTANCE,
                    BugReporter.defaultInstance()
                )
        skyframeExecutor.injectExtraPrecomputedValues(
            com.google.common.collect.ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    RepoDefinitionFunction.REPOSITORY_OVERRIDES, com.google.common.collect.ImmutableMap.of<K?, V?>()
                ),
                PrecomputedValue.injected(RepositoryDirectoryValue.FETCH_DISABLED, false),
                PrecomputedValue.injected(
                    RepositoryDirectoryValue.FORCE_FETCH,
                    RepositoryDirectoryValue.FORCE_FETCH_DISABLED
                ),
                PrecomputedValue.injected(
                    RepositoryDirectoryValue.VENDOR_DIRECTORY, java.util.Optional.empty<T?>()
                )
            )
        )
        val parser: OptionsParser =
            OptionsParser.builder().optionsClasses(BuildLanguageOptions::class.java).build()
        parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
        val options: BuildLanguageOptions? = parser.getOptions<O?>(BuildLanguageOptions::class.java)
        skyframeExecutor.sync(
            reporter,
            pathPackageLocator,
            UUID.randomUUID(),  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance()),
            QuiescingExecutorsImpl.forTesting(),
            FakeOptions.builder().put(packageOptions).put(options).build(),  /* commandName= */
            "build",  /* commandExecutes= */
            true
        )
        evaluator = skyframeExecutor.getEvaluator()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getCollectPackagesUnderDirectoryValue(directory: String?): CollectPackagesUnderDirectoryValue {
        return getCollectPackagesUnderDirectoryValue(
            directory,  /* excludedPaths= */
            com.google.common.collect.ImmutableSet.of<PathFragment?>()
        )
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getCollectPackagesUnderDirectoryValue(
        directory: String?, excludedPaths: com.google.common.collect.ImmutableSet<PathFragment?>?
    ): CollectPackagesUnderDirectoryValue {
        val key: SkyKey =
            CollectPackagesUnderDirectoryValue.key(
                RepositoryName.MAIN, rootedPath(directory), IgnoredSubdirectories.of(excludedPaths)
            )
        return evaluate(key).get(key)
    }

    private fun rootedPath(relativePath: String?): RootedPath {
        return RootedPath.toRootedPath(root, PathFragment.create(relativePath))
    }

    @Throws(java.lang.InterruptedException::class)
    private fun evaluate(key: SkyKey): EvaluationResult<CollectPackagesUnderDirectoryValue?> {
        val evaluationContext: EvaluationContext? =
            EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(1)
                .setEventHandler(
                    com.google.devtools.build.lib.events.Reporter(
                        EventBusEventHandler.createWithNewEventBus(),
                        reporter
                    )
                )
                .build()
        return evaluator.evaluate(com.google.common.collect.ImmutableList.of<E?>(key), evaluationContext)
    }

    companion object {
        private const val FAKE_INSTALL_MD5_STRING = "abcedf1234567890abcedf1234567890"
    }
}
