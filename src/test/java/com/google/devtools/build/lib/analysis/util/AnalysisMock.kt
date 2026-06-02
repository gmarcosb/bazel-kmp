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

import com.google.common.base.Predicate
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.common.collect.Maps
import com.google.devtools.build.lib.analysis.BlazeDirectories
import java.util.*

/** Create a mock client for the analysis phase, as well as a configuration factory.  */
abstract class AnalysisMock : LoadingMock() {
    val productName: String
        get() = TestConstants.PRODUCT_NAME

    val embeddedTools: ImmutableList<String?>
        get() = TestConstants.EMBEDDED_TOOLS

    override fun getPackageFactoryBuilderForTesting(
        directories: BlazeDirectories
    ): PackageFactoryBuilderWithSkyframeForTesting {
        return super.getPackageFactoryBuilderForTesting(directories)
            .setExtraSkyFunctions(getSkyFunctions(directories))
            .setExtraPrecomputeValues(this.precomputedValues)
    }

    /**
     * This is called from test setup to create the mock directory layout needed to create the
     * configuration.
     */
    @Throws(IOException::class)
    fun setupMockClient(mockToolsConfig: MockToolsConfig) {
        setupMockClientInternal(mockToolsConfig)
        setupMockTestingRules(mockToolsConfig)
    }

    @Throws(IOException::class)
    abstract fun setupMockClientInternal(mockToolsConfig: MockToolsConfig?)

    @Throws(IOException::class)
    fun setupMockTestingRules(mockToolsConfig: MockToolsConfig) {
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

    /** Creates a mock tools repository.  */
    @Throws(IOException::class)
    open fun setupMockToolsRepository(config: MockToolsConfig?) {
        // Do nothing by default.
    }

    abstract val isThisBazel: Boolean

    abstract fun ccSupport(): MockCcSupport?

    abstract fun javaSupport(): AbstractMockJavaSupport?

    abstract fun pySupport(): MockPythonSupport?

    open fun getSkyFunctions(directories: BlazeDirectories): ImmutableMap<SkyFunctionName?, SkyFunction?>? {
        return ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
            .put(
                SkyFunctions.REPOSITORY_DIRECTORY,
                RepositoryFetchFunction(
                    ImmutableMap::of, ImmutableMap::of, directories, LocalRepoContentsCache()
                )
            )
            .put(RepoDefinitionValue.REPO_DEFINITION, RepoDefinitionFunction(directories))
            .put(
                SkyFunctions.MODULE_FILE,
                ModuleFileFunction(
                    createRuleClassProvider().getBazelStarlarkEnvironment(),
                    directories.getWorkspace(),
                    getBuiltinModules(directories)
                )
            )
            .put(SkyFunctions.BAZEL_DEP_GRAPH, BazelDepGraphFunction())
            .put(
                SkyFunctions.BAZEL_LOCK_FILE,
                BazelLockFileFunction(directories.getWorkspace(), directories.getOutputBase())
            )
            .put(SkyFunctions.BAZEL_MODULE_RESOLUTION, BazelModuleResolutionFunction())
            .put(SkyFunctions.SINGLE_EXTENSION, SingleExtensionFunction())
            .put(
                SkyFunctions.SINGLE_EXTENSION_EVAL,
                SingleExtensionEvalFunction(directories, ImmutableMap::of, ImmutableMap::of)
            )
            .put(SkyFunctions.SINGLE_EXTENSION_USAGES, SingleExtensionUsagesFunction())
            .put(
                SkyFunctions.REGISTRY,
                RegistryFunction(FakeRegistry.Companion.DEFAULT_FACTORY, directories.getWorkspace())
            )
            .put(SkyFunctions.REPO_SPEC, RepoSpecFunction())
            .put(SkyFunctions.YANKED_VERSIONS, YankedVersionsFunction())
            .put(
                SkyFunctions.MODULE_EXTENSION_REPO_MAPPING_ENTRIES,
                ModuleExtensionRepoMappingEntriesFunction()
            )
            .put(
                SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE,
                ClientEnvironmentFunction(AtomicReference<V?>(ImmutableMap.of<Any?, Any?>()))
            )
            .buildOrThrow()
    }

    val precomputedValues: ImmutableList<PrecomputedValue.Injected>
        get() =// PrecomputedValues required by SkyFunctions in getSkyFunctions()
            ImmutableList.of<E?>(
                PrecomputedValue.injected(
                    PrecomputedValue.REPO_ENV,
                    ImmutableMap.of<K?, V?>()
                ),
                PrecomputedValue.injected(
                    ModuleFileFunction.MODULE_OVERRIDES,
                    ImmutableMap.of<K?, V?>()
                ),
                PrecomputedValue.injected(
                    RepoDefinitionFunction.REPOSITORY_OVERRIDES,
                    ImmutableMap.of<K?, V?>()
                ),
                PrecomputedValue.injected(RepositoryDirectoryValue.FETCH_DISABLED, false),
                PrecomputedValue.injected(
                    RepositoryDirectoryValue.FORCE_FETCH, RepositoryDirectoryValue.FORCE_FETCH_DISABLED
                ),
                PrecomputedValue.injected(RepositoryDirectoryValue.VENDOR_DIRECTORY, Optional.empty<T?>()),
                PrecomputedValue.injected(
                    ModuleFileFunction.REGISTRIES,
                    ImmutableSet.of<E?>()
                ),
                PrecomputedValue.injected(
                    RegistryFunction.MODULE_MIRRORS,
                    ImmutableMap.of<K?, V?>()
                ),
                PrecomputedValue.injected(ModuleFileFunction.IGNORE_DEV_DEPS, false),
                PrecomputedValue.injected(
                    ModuleFileFunction.INJECTED_REPOSITORIES,
                    ImmutableMap.of<K?, V?>()
                ),
                PrecomputedValue.injected(
                    YankedVersionsUtil.ALLOWED_YANKED_VERSIONS,
                    ImmutableList.of<E?>()
                ),
                PrecomputedValue.injected(
                    BazelModuleResolutionFunction.CHECK_DIRECT_DEPENDENCIES, CheckDirectDepsMode.WARNING
                ),
                PrecomputedValue.injected(
                    BazelModuleResolutionFunction.BAZEL_COMPATIBILITY_MODE, BazelCompatibilityMode.ERROR
                ),
                PrecomputedValue.injected(BazelLockFileFunction.LOCKFILE_MODE, LockfileMode.UPDATE)
            )

    /** Returns the built-in modules.  */
    abstract fun getBuiltinModules(
        directories: BlazeDirectories?
    ): ImmutableMap<String?, NonRegistryOverride?>?

    @Throws(IOException::class)
    abstract fun setupPrelude(mockToolsConfig: MockToolsConfig?)

    abstract fun getBazelRepositoryModule(directories: BlazeDirectories?): BlazeModule?

    /**
     * Stub class for tests to extend in order to update a small amount of [AnalysisMock]
     * functionality.
     */
    open class Delegate(private val delegate: AnalysisMock) : AnalysisMock() {
        @Throws(IOException::class)
        override fun setupMockClientInternal(mockToolsConfig: MockToolsConfig?) {
            delegate.setupMockClientInternal(mockToolsConfig)
        }

        @Throws(IOException::class)
        override fun setupMockToolsRepository(config: MockToolsConfig?) {
            delegate.setupMockToolsRepository(config)
        }

        override fun createRuleClassProvider(): ConfiguredRuleClassProvider? {
            return delegate.createRuleClassProvider()
        }

        override fun isThisBazel(): Boolean {
            return delegate.isThisBazel
        }

        override fun ccSupport(): MockCcSupport? {
            return delegate.ccSupport()
        }

        override fun javaSupport(): AbstractMockJavaSupport? {
            return delegate.javaSupport()
        }

        override fun pySupport(): MockPythonSupport? {
            return delegate.pySupport()
        }

        override fun getSkyFunctions(
            directories: BlazeDirectories
        ): ImmutableMap<SkyFunctionName?, SkyFunction?>? {
            return ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
                .putAll(
                    Maps.filterKeys<SkyFunctionName?, SkyFunction?>(
                        super.getSkyFunctions(directories),
                        Predicate { fnName: SkyFunctionName? -> !fnName.equals(SkyFunctions.MODULE_FILE) })
                )
                .put(
                    SkyFunctions.MODULE_FILE,
                    ModuleFileFunction(
                        createRuleClassProvider().getBazelStarlarkEnvironment(),
                        directories.getWorkspace(),
                        getBuiltinModules(directories)
                    )
                )
                .buildOrThrow()
        }

        override fun getBuiltinModules(
            directories: BlazeDirectories?
        ): ImmutableMap<String?, NonRegistryOverride?>? {
            return delegate.getBuiltinModules(directories)
        }

        @Throws(IOException::class)
        override fun setupPrelude(mockToolsConfig: MockToolsConfig?) {
            delegate.setupPrelude(mockToolsConfig)
        }

        override fun getBazelRepositoryModule(directories: BlazeDirectories?): BlazeModule? {
            return delegate.getBazelRepositoryModule(directories)
        }
    }

    companion object {
        fun get(): AnalysisMock? {
            try {
                val providerClass = Class.forName(TestConstants.TEST_ANALYSIS_MOCK)
                val instanceField = providerClass.getField("INSTANCE")
                return instanceField.get(null) as AnalysisMock?
            } catch (e: Exception) {
                throw IllegalStateException(e)
            }
        }

        val analysisMockWithoutBuiltinModules: AnalysisMock
            get() = object :
                Delegate(get()!!) {
                override fun getBuiltinModules(
                    directories: BlazeDirectories?
                ): ImmutableMap<String?, NonRegistryOverride?> {
                    return ImmutableMap.of<String?, NonRegistryOverride?>()
                }
            }
    }
}
