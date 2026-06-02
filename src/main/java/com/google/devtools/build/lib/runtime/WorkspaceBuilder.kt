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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * Builder class to create a [BlazeWorkspace] instance. This class is part of the module API,
 * which allows modules to affect how the workspace is initialized.
 */
class WorkspaceBuilder internal constructor(directories: BlazeDirectories?, binTools: BinTools?) {
    private val directories: BlazeDirectories?
    private val binTools: BinTools?

    private var skyframeExecutorFactory: SkyframeExecutorFactory? = null
    private var workspaceStatusActionFactory: WorkspaceStatusAction.Factory? = null
    private val diffAwarenessFactories: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.skyframe.DiffAwareness.Factory?> =
        com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.skyframe.DiffAwareness.Factory?>()

    // We use an immutable map builder for the nice side effect that it throws if a duplicate key
    // is inserted.
    private val skyFunctions: com.google.common.collect.ImmutableMap.Builder<SkyFunctionName?, SkyFunction?> =
        com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
    private var allocationTracker: AllocationTracker? = null

    private var skyKeyStateReceiver: SkyKeyStateReceiver? = null
    private var syscallCache: SyscallCache? = null

    private var allowExternalRepositories = true
    private var repoContentsCachePathSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path?>? =
        java.util.function.Supplier { null }
    private var analysisCodecRegistrySupplier: java.util.function.Supplier<ObjectCodecRegistry?>? = null

    private var remoteAnalysisCachingServicesSupplier: RemoteAnalysisCachingServicesSupplier? = null

    init {
        this.directories = directories
        this.binTools = binTools
    }

    @Throws(AbruptExitException::class)
    fun build(
        runtime: BlazeRuntime,
        packageFactory: PackageFactory?,
        eventBusExceptionHandler: com.google.common.eventbus.SubscriberExceptionHandler?
    ): BlazeWorkspace {
        // Set default values if none are set.
        if (skyframeExecutorFactory == null) {
            skyframeExecutorFactory = SequencedSkyframeExecutorFactory()
        }
        if (syscallCache == null) {
            syscallCache =
                DefaultSyscallCache.newBuilder()
                    .setInitialCapacity(syscallCacheInitialCapacity)
                    .build()
        }

        val singleFsSyscallCache: SingleFileSystemSyscallCache =
            SingleFileSystemSyscallCache(syscallCache, runtime.getFileSystem())

        val skyframeExecutor: SkyframeExecutor? =
            skyframeExecutorFactory.create(
                packageFactory,
                runtime.getFileSystem(),
                directories,
                runtime.getActionKeyContext(),
                workspaceStatusActionFactory,
                diffAwarenessFactories.build(),
                skyFunctions.buildOrThrow(),
                singleFsSyscallCache,
                allowExternalRepositories,
                repoContentsCachePathSupplier,
                if (skyKeyStateReceiver == null)
                    SkyframeExecutor.SkyKeyStateReceiver.NULL_INSTANCE
                else
                    skyKeyStateReceiver,
                runtime.getBugReporter()
            )
        return BlazeWorkspace(
            runtime,
            directories,
            skyframeExecutor,
            eventBusExceptionHandler,
            workspaceStatusActionFactory,
            binTools,
            allocationTracker,
            singleFsSyscallCache,
            analysisCodecRegistrySupplier,
            remoteAnalysisCachingServicesSupplier,
            allowExternalRepositories
        )
    }

    /**
     * Sets a factory for creating [SkyframeExecutor] objects. Note that only one factory per
     * workspace is allowed.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setSkyframeExecutorFactory(
        skyframeExecutorFactory: SkyframeExecutorFactory?
    ): WorkspaceBuilder {
        com.google.common.base.Preconditions.checkState(
            this.skyframeExecutorFactory == null,
            "At most one Skyframe factory supported. But found two: %s and %s",
            this.skyframeExecutorFactory,
            skyframeExecutorFactory
        )
        this.skyframeExecutorFactory =
            com.google.common.base.Preconditions.checkNotNull<SkyframeExecutorFactory?>(skyframeExecutorFactory)
        return this
    }

    /**
     * Sets the workspace status action factory contributed by this module. Only one factory per
     * workspace is allowed.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setWorkspaceStatusActionFactory(
        workspaceStatusActionFactory: WorkspaceStatusAction.Factory?
    ): WorkspaceBuilder {
        com.google.common.base.Preconditions.checkState(
            this.workspaceStatusActionFactory == null,
            "At most one workspace status action factory supported. But found two: %s and %s",
            this.workspaceStatusActionFactory,
            workspaceStatusActionFactory
        )
        this.workspaceStatusActionFactory =
            com.google.common.base.Preconditions.checkNotNull<WorkspaceStatusAction.Factory?>(
                workspaceStatusActionFactory
            )
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setAllocationTracker(allocationTracker: AllocationTracker?): WorkspaceBuilder {
        com.google.common.base.Preconditions.checkState(
            this.allocationTracker == null, "At most one allocation tracker can be set."
        )
        this.allocationTracker =
            com.google.common.base.Preconditions.checkNotNull<AllocationTracker?>(allocationTracker)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setSyscallCache(syscallCache: SyscallCache?): WorkspaceBuilder {
        com.google.common.base.Preconditions.checkState(
            this.syscallCache == null, "Set twice: %s %s", this.syscallCache, syscallCache
        )
        this.syscallCache = com.google.common.base.Preconditions.checkNotNull<SyscallCache?>(syscallCache)
        return this
    }

    /**
     * Add a [DiffAwareness] factory. These will be used to determine which files, if any,
     * changed between Blaze commands. Note that these factories are attempted in the order in which
     * they are added to this class, so order matters - in order to guarantee a specific order, only a
     * single module should add such factories.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addDiffAwarenessFactory(factory: com.google.devtools.build.lib.skyframe.DiffAwareness.Factory?): WorkspaceBuilder {
        this.diffAwarenessFactories.add(
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.skyframe.DiffAwareness.Factory?>(
                factory
            )
        )
        return this
    }

    /** Add an "extra" SkyFunction for SkyValues.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addSkyFunction(name: SkyFunctionName?, skyFunction: SkyFunction?): WorkspaceBuilder {
        com.google.common.base.Preconditions.checkNotNull<SkyFunctionName?>(name)
        com.google.common.base.Preconditions.checkNotNull<SkyFunction?>(skyFunction)
        this.skyFunctions.put(name, skyFunction)
        return this
    }

    /** Add "extra" SkyFunctions for SkyValues.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addSkyFunctions(skyFunctions: MutableMap<SkyFunctionName?, SkyFunction?>?): WorkspaceBuilder {
        this.skyFunctions.putAll(
            com.google.common.base.Preconditions.checkNotNull<MutableMap<SkyFunctionName?, SkyFunction?>?>(
                skyFunctions
            )
        )
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun allowExternalRepositories(allowExternalRepositories: Boolean): WorkspaceBuilder {
        this.allowExternalRepositories = allowExternalRepositories
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRepoContentsCachePathSupplier(
        repoContentsCachePathSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path?>?
    ): WorkspaceBuilder {
        this.repoContentsCachePathSupplier = repoContentsCachePathSupplier
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setSkyKeyStateReceiver(
        skyKeyStateReceiver: SkyKeyStateReceiver?
    ): WorkspaceBuilder {
        com.google.common.base.Preconditions.checkState(
            this.skyKeyStateReceiver == null,
            "Multiple evaluatedSkyKeyReceiver: %s %s",
            this.skyKeyStateReceiver,
            skyKeyStateReceiver
        )
        this.skyKeyStateReceiver = skyKeyStateReceiver
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setAnalysisCodecRegistrySupplier(
        analysisCodecRegistrySupplier: java.util.function.Supplier<ObjectCodecRegistry?>?
    ): WorkspaceBuilder {
        this.analysisCodecRegistrySupplier = analysisCodecRegistrySupplier
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRemoteAnalysisCachingServicesSupplier(
        remoteAnalysisCachingServicesSupplier: RemoteAnalysisCachingServicesSupplier?
    ): WorkspaceBuilder {
        this.remoteAnalysisCachingServicesSupplier = remoteAnalysisCachingServicesSupplier
        return this
    }

    companion object {
        val syscallCacheInitialCapacity: Int
            get() {
                // The initial capacity here translates into the size of an array in ConcurrentHashMap, so
                // oversizing by N results in memory usage of 8N bytes. So the maximum wasted memory here is
                // 1/2^20 of heap, or 10K on a 10G heap (which would start with 1280-capacity caches).
                var scaledMemory: Long = java.lang.Runtime.getRuntime().maxMemory() shr 23
                if (scaledMemory > java.lang.Integer.MAX_VALUE) {
                    // Something went very wrong.
                    BugReport.sendBugReport(
                        java.lang.IllegalStateException(
                            ("Scaled memory was still too big: "
                                    + scaledMemory
                                    + ", "
                                    + java.lang.Runtime.getRuntime().maxMemory())
                        )
                    )
                    scaledMemory = 1024
                } else if (scaledMemory <= 0) {
                    // If Bazel is running in <8M of memory, very impressive.
                    scaledMemory = 32
                }
                return scaledMemory.toInt()
            }
    }
}
