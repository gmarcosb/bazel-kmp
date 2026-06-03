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
package com.google.devtools.build.lib.packages.util

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Sets
import com.google.devtools.build.lib.actions.ActionKeyContext
import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.common.options.Options
import org.junit.After
import java.util.Optional

/**
 * This is a specialization of [FoundationTestCase] that's useful for implementing tests of
 * the "packages" library.
 */
abstract class PackageLoadingTestCase : FoundationTestCase() {
    protected var loadingMock: LoadingMock? = null
    private var packageOptions: PackageOptions? = null
    private var buildLanguageOptions: BuildLanguageOptions? = null
    @kotlin.jvm.JvmField
    protected var ruleClassProvider: ConfiguredRuleClassProvider? = null
    protected var packageFactory: PackageFactory? = null
    @kotlin.jvm.JvmField
    protected var skyframeExecutor: SkyframeExecutor? = null
    protected var directories: BlazeDirectories? = null
    protected var validator: PackageValidator? = null
    @kotlin.jvm.JvmField
    protected val delegatingSyscallCache: DelegatingSyscallCache = DelegatingSyscallCache()

    protected val actionKeyContext: ActionKeyContext = ActionKeyContext()

    @Before
    @Throws(Exception::class)
    fun initializeSkyframeExecutor() {
        loadingMock = LoadingMock.Companion.get()
        packageOptions = parsePackageOptions()
        buildLanguageOptions = parseBuildLanguageOptions()
        val extraRules: MutableList<RuleDefinition?> = this.extraRules
        if (!extraRules.isEmpty()) {
            val builder: ConfiguredRuleClassProvider.Builder = Builder()
            TestRuleClassProvider.addStandardRules(builder)
            for (def in extraRules) {
                builder.addRuleDefinition(def)
            }
            ruleClassProvider = builder.build()
        } else {
            ruleClassProvider = loadingMock!!.createRuleClassProvider()
        }
        directories =
            BlazeDirectories(
                ServerDirectories(outputBase, outputBase, outputBase),
                rootDirectory,
                loadingMock!!.getProductName()
            )
        packageFactory =
            loadingMock!!
                .getPackageFactoryBuilderForTesting(directories)
                .setExtraSkyFunctions(
                    ImmutableMap.of<K?, V?>(
                        SkyFunctions.MODULE_FILE,
                        ModuleFileFunction(
                            ruleClassProvider.getBazelStarlarkEnvironment(),
                            directories.getWorkspace(),
                            ImmutableMap.of<K?, V?>()
                        )
                    )
                )
                .setPackageValidator(
                    object : PackageValidator() {
                        @Throws(InvalidPackageException::class)
                        public override fun validate(
                            pkg: Package?, metrics: Metrics?, eventHandler: ExtendedEventHandler?
                        ) {
                            if (validator != null) {
                                validator.validate(pkg, metrics, eventHandler)
                            }
                        }
                    })
                .build(ruleClassProvider, fileSystem)
        delegatingSyscallCache.setDelegate(SyscallCache.NO_CACHE)
        skyframeExecutor = createSkyframeExecutor()
        setUpSkyframe()
    }

    @Before
    @Throws(IOException::class)
    fun initializeMockTestingRules() {
        val mockToolsConfig = MockToolsConfig(rootDirectory)
        mockToolsConfig.create("test_defs/BUILD")
        mockToolsConfig.create(
            "test_defs/foo_library.bzl",
            """
        def _impl(ctx):
          pass
        foo_library = rule(
          implementation = _impl,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
          },
        )
        
        """.trimIndent()
        )
        mockToolsConfig.create(
            "test_defs/foo_binary.bzl",
            """
        def _impl(ctx):
          symlink = ctx.actions.declare_file(ctx.label.name)
          ctx.actions.symlink(output = symlink, target_file = ctx.files.srcs[0],
            is_executable = True)
          files = depset(ctx.files.srcs)
          return [DefaultInfo(files = files, executable = symlink,
             runfiles = ctx.runfiles(transitive_files = files, collect_default = True))]
        foo_binary = rule(
          implementation = _impl,
          executable = True,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
            "data": attr.label_list(allow_files=True),
          },
        )
        
        """.trimIndent()
        )
        mockToolsConfig.create(
            "test_defs/foo_test.bzl",
            """
        def _impl(ctx):
          symlink = ctx.actions.declare_file(ctx.label.name)
          ctx.actions.symlink(output = symlink, target_file = ctx.files.srcs[0],
            is_executable = True)
          files = depset(ctx.files.srcs)
          return [DefaultInfo(files = files, executable = symlink,
             runfiles = ctx.runfiles(transitive_files = files, collect_default = True))]
        foo_test = rule(
          implementation = _impl,
          test = True,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
            "deps": attr.label_list(),
            "data": attr.label_list(allow_files=True),
          },
        )
        
        """.trimIndent()
        )
    }

    @After
    fun cleanUpInterningPools() {
        skyframeExecutor.getEvaluator().cleanupInterningPools()
    }

    protected open val extraRules: MutableList<RuleDefinition>
        /** Allows subclasses to augment the [RuleDefinition]s available in this test.  */
        get() = ImmutableList.of<RuleDefinition?>()

    private fun createSkyframeExecutor(): SkyframeExecutor {
        val skyframeExecutor: SkyframeExecutor =
            BazelSkyframeExecutorConstants.newBazelSkyframeExecutorBuilder()
                .setPkgFactory(packageFactory)
                .setFileSystem(fileSystem)
                .setDirectories(directories)
                .setActionKeyContext(actionKeyContext)
                .setSyscallCache(delegatingSyscallCache)
                .build()
        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    RepositoryDirectoryValue.VENDOR_DIRECTORY, Optional.empty<T?>()
                )
            )
        )
        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    RepoDefinitionFunction.REPOSITORY_OVERRIDES, ImmutableMap.of<K?, V?>()
                )
            )
        )
        skyframeExecutor.injectExtraPrecomputedValues(
            ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    ModuleFileFunction.INJECTED_REPOSITORIES, ImmutableMap.of<K?, V?>()
                )
            )
        )
        SkyframeExecutorTestHelper.process(skyframeExecutor)
        return skyframeExecutor
    }

    protected fun setUpSkyframe(defaultVisibility: RuleVisibility?) {
        val packageOptions: PackageOptions = Options.getDefaults<O>(PackageOptions::class.java)
        packageOptions.setDefaultVisibility(defaultVisibility)
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(GLOBBING_THREADS)
        skyframeExecutor.preparePackageLoading(
            PathPackageLocator(
                outputBase,
                ImmutableList.of<E?>(Root.fromPath(rootDirectory)),
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            ),
            packageOptions,
            buildLanguageOptions,
            UUID.randomUUID(),
            ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            TimestampGranularityMonitor(BlazeClock.instance())
        )
        skyframeExecutor.setActionEnv(ImmutableMap.of<K?, V?>())
    }

    private fun setUpSkyframe() {
        val pkgLocator: PathPackageLocator? =
            PathPackageLocator.create(
                outputBase,
                packageOptions.getPackagePath(),
                reporter,
                rootDirectory.asFragment(),
                rootDirectory,
                BazelSkyframeExecutorConstants.BUILD_FILES_BY_PRIORITY
            )
        packageOptions.setShowLoadingProgress(true)
        packageOptions.setGlobbingThreads(GLOBBING_THREADS)
        skyframeExecutor.preparePackageLoading(
            pkgLocator,
            packageOptions,
            buildLanguageOptions,
            UUID.randomUUID(),
            ImmutableMap.of<K?, V?>(),
            QuiescingExecutorsImpl.forTesting(),
            TimestampGranularityMonitor(BlazeClock.instance())
        )
        skyframeExecutor.setActionEnv(ImmutableMap.of<K?, V?>())
        skyframeExecutor.setDeletedPackages(
            ImmutableSet.copyOf(packageOptions.getDeletedPackagesOrEmptySet())
        )
    }

    @Throws(Exception::class)
    protected fun setPackageOptions(vararg options: String?) {
        packageOptions = parsePackageOptions(*options)
        setUpSkyframe()
    }

    @Throws(Exception::class)
    protected fun setBuildLanguageOptions(vararg options: String?) {
        buildLanguageOptions = parseBuildLanguageOptions(*options)
        setUpSkyframe()
    }

    @Throws(
        NoSuchPackageException::class,
        NoSuchTargetException::class,
        LabelSyntaxException::class,
        InterruptedException::class
    )
    protected fun getTarget(label: String?): Target? {
        return getTarget(Label.parseCanonical(label))
    }

    @Throws(NoSuchPackageException::class, NoSuchTargetException::class, InterruptedException::class)
    protected fun getTarget(label: Label?): Target {
        return this.packageManager.getTarget(reporter, label)
    }

    /**
     * Loads a package with the given name in the main repo.
     * 
     * @throws NoSuchPackageException if the package does not exist
     * @throws LabelSyntaxException if the package name is not syntactically valid
     * @throws InterruptedException if loading is interrupted
     */
    @Throws(NoSuchPackageException::class, LabelSyntaxException::class, InterruptedException::class)
    protected fun getPackage(packageName: String?): Package {
        return this.packageManager.getPackage(reporter, PackageIdentifier.parse(packageName))
    }

    @Throws(NoSuchPackageException::class, InterruptedException::class)
    protected fun getPackage(packageIdentifier: PackageIdentifier?): Package {
        return this.packageManager.getPackage(reporter, packageIdentifier)
    }

    /**
     * Create and return a scratch rule.
     * 
     * @param packageName the package name of the rule.
     * @param ruleName the name of the rule.
     * @param lines the text of the rule.
     * @return the rule instance for the created rule.
     */
    @Throws(Exception::class)
    protected fun scratchRule(packageName: String?, ruleName: String?, vararg lines: String?): Rule? {
        scratch.file(packageName + "/BUILD", *lines)
        return getTarget("//" + packageName + ":" + ruleName) as Rule?
    }

    protected val packageManager: PackageManager
        get() = skyframeExecutor.getPackageManager()

    protected fun getSkyframeExecutor(): SkyframeExecutor {
        return skyframeExecutor
    }

    /**
     * Called after files are modified to invalidate all file-system nodes below rootDirectory. It
     * does not unconditionally invalidate PackageValue nodes; if no file-system nodes have changed,
     * packages may not be reloaded.
     */
    @Throws(InterruptedException::class, AbruptExitException::class)
    protected fun invalidatePackages() {
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter, ModifiedFileSet.EVERYTHING_MODIFIED, Root.fromPath(rootDirectory)
        )
    }

    companion object {
        private const val GLOBBING_THREADS = 7

        @Throws(Exception::class)
        private fun parsePackageOptions(vararg options: String?): PackageOptions {
            val parser: OptionsParser = OptionsParser.builder().optionsClasses(PackageOptions::class.java).build()
            parser.parse("--default_visibility=public")
            parser.parse(*options)
            return parser.getOptions<O>(PackageOptions::class.java)
        }

        @Throws(Exception::class)
        private fun parseBuildLanguageOptions(vararg options: String?): BuildLanguageOptions? {
            val parser: OptionsParser =
                OptionsParser.builder().optionsClasses(BuildLanguageOptions::class.java).build()
            parser.parse(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
            parser.parse(*options)
            return parser.getOptions<O?>(BuildLanguageOptions::class.java)
        }

        /**
         * A Utility method that generates build file rules for tests.
         * 
         * @param rule the name of the rule class.
         * @param name the name of the rule instance.
         * @param body an array of strings containing the contents of the rule.
         * @return a string containing the build file rule.
         */
        protected fun genRule(rule: String?, name: String?, vararg body: String?): String {
            val buf = StringBuilder()
            buf.append(rule)
            buf.append("(name='")
            buf.append(name)
            buf.append("',\n")
            for (line in body) {
                buf.append(line)
            }
            buf.append(")\n")
            return buf.toString()
        }

        /**
         * A utility function which generates the "deps" clause for a build file rule from a list of
         * targets.
         * 
         * @param depTargets the list of targets.
         * @return a string containing the deps clause
         */
        protected fun deps(vararg depTargets: String?): String {
            val buf = StringBuilder()
            buf.append("    deps=[")
            var sep = "'"
            for (dep in depTargets) {
                buf.append(sep)
                buf.append(dep)
                buf.append("'")
                sep = ", '"
            }
            buf.append("]")
            return buf.toString()
        }

        /**
         * Utility method for tests. Converts an array of strings into a set of labels.
         * 
         * @param strings the set of strings to be converted to labels.
         * @throws LabelSyntaxException if there are any syntax errors in the strings.
         */
        @Throws(LabelSyntaxException::class)
        fun asLabelSet(vararg strings: String?): MutableSet<Label?> {
            return asLabelSet(ImmutableList.copyOf<String?>(strings))
        }

        /**
         * Utility method for tests. Converts an array of strings into a set of labels.
         * 
         * @param strings the set of strings to be converted to labels.
         * @throws LabelSyntaxException if there are any syntax errors in the strings.
         */
        @Throws(LabelSyntaxException::class)
        fun asLabelSet(strings: Iterable<String?>): MutableSet<Label?> {
            val result: MutableSet<Label?> = Sets.newTreeSet<Label?>()
            for (s in strings) {
                result.add(Label.parseCanonical(s))
            }
            return result
        }
    }
}
