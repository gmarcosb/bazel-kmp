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

import com.google.devtools.build.lib.cmdline.BatchCallback

/** A [TargetPatternResolver] backed by a [RecursivePackageProvider].  */
@ThreadCompatible
class RecursivePackageProviderBackedTargetPatternResolver
    (
    recursivePackageProvider: RecursivePackageProvider,
    eventHandler: ExtendedEventHandler,
    policy: FilteringPolicy,
    packageSemaphore: MultisetSemaphore<PackageIdentifier?>,
    maxConcurrentGetTargetsTasks: java.util.Optional<Int?>,
    packageIdentifierBatchingCallbackFactory: com.google.devtools.build.lib.skyframe.PackageIdentifierBatchingCallback.Factory
) : TargetPatternResolver<Target?>() {
    protected val policy: FilteringPolicy
    private val recursivePackageProvider: RecursivePackageProvider
    private val eventHandler: ExtendedEventHandler
    private val packageSemaphore: MultisetSemaphore<PackageIdentifier?>

    private val getTargetsTaskSemaphore: Semaphore?
    private val packageIdentifierBatchingCallbackFactory: com.google.devtools.build.lib.skyframe.PackageIdentifierBatchingCallback.Factory

    init {
        this.recursivePackageProvider = recursivePackageProvider
        this.eventHandler = eventHandler
        this.policy = policy
        this.packageSemaphore = packageSemaphore
        this.getTargetsTaskSemaphore =
            if (maxConcurrentGetTargetsTasks.isPresent())
                Semaphore(maxConcurrentGetTargetsTasks.get())
            else
                null
        this.packageIdentifierBatchingCallbackFactory = packageIdentifierBatchingCallbackFactory
    }

    public override fun warn(msg: String?) {
        eventHandler.handle(Event.warn(msg))
    }

    /**
     * Gets a [Package] from the [RecursivePackageProvider]. May return a [Package]
     * that has errors.
     */
    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    private fun getPackage(pkgIdentifier: PackageIdentifier?): Package {
        return recursivePackageProvider.getPackage(eventHandler, pkgIdentifier)
    }

    @Throws(java.lang.InterruptedException::class, InconsistentFilesystemException::class)
    public override fun getTargetOrNull(label: Label): Target? {
        try {
            if (!isPackage(label.getPackageIdentifier())) {
                return null
            }
            return recursivePackageProvider.getTarget(eventHandler, label)
        } catch (e: NoSuchThingException) {
            return null
        }
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    public override fun getExplicitTarget(label: Label?): ResolvedTargets<Target?> {
        try {
            val target: Target? = recursivePackageProvider.getTarget(eventHandler, label)
            return if (policy.shouldRetain(target, true))
                ResolvedTargets.of(target)
            else
                ResolvedTargets.empty()
        } catch (e: NoSuchThingException) {
            throw TargetParsingException(e.getMessage(), e, e.getDetailedExitCode())
        }
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    public override fun getTargetsInPackage(
        originalPattern: String?, packageIdentifier: PackageIdentifier?, rulesOnly: Boolean
    ): MutableCollection<Target?> {
        val actualPolicy: FilteringPolicy? =
            if (rulesOnly) FilteringPolicies.and(FilteringPolicies.RULES_ONLY, policy) else policy
        try {
            val pkg: Package = getPackage(packageIdentifier)
            Package.maybeAddPackageContainsErrorsEventToHandler(pkg, eventHandler)
            return TargetPatternResolverUtil.resolvePackageTargets(pkg, actualPolicy)
        } catch (e: NoSuchThingException) {
            val message: String? =
                TargetPatternResolverUtil.getParsingErrorMessage(e.getMessage(), originalPattern)
            throw TargetParsingException(message, e, e.getDetailedExitCode())
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun bulkGetTargetsInPackage(
        originalPattern: String?, pkgIds: Iterable<PackageIdentifier?>, policy: FilteringPolicy?
    ): MutableMap<PackageIdentifier?, MutableCollection<Target?>?> {
        try {
            val pkgs: MutableMap<PackageIdentifier?, Package?> =
                recursivePackageProvider.bulkGetPackages(eventHandler, pkgIds)
            check(pkgs.size() == com.google.common.collect.Iterables.size(pkgIds)) {
                ("Bulk package retrieval missing results: "
                        + com.google.common.collect.Sets.difference<PackageIdentifier?>(
                    com.google.common.collect.ImmutableSet.copyOf<PackageIdentifier?>(
                        pkgIds
                    ), pkgs.keySet()
                ))
            }
            val result: com.google.common.collect.ImmutableMap.Builder<PackageIdentifier?, MutableCollection<Target?>?> =
                com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, MutableCollection<Target?>?>()
            for (pkgId in pkgIds) {
                val pkg: Package? = pkgs.get(pkgId)
                Package.maybeAddPackageContainsErrorsEventToHandler(pkg, eventHandler)
                result.put(pkgId, TargetPatternResolverUtil.resolvePackageTargets(pkg, policy))
            }
            return result.buildOrThrow()
        } catch (e: NoSuchThingException) {
            val message: String? =
                TargetPatternResolverUtil.getParsingErrorMessage(e.getMessage(), originalPattern)
            throw java.lang.IllegalStateException(
                "Mismatch: Expected given pkgIds to correspond to valid Packages. " + message, e
            )
        }
    }

    @Throws(java.lang.InterruptedException::class, InconsistentFilesystemException::class)
    public override fun isPackage(packageIdentifier: PackageIdentifier?): Boolean {
        return recursivePackageProvider.isPackage(eventHandler, packageIdentifier)
    }

    public override fun getTargetKind(target: Target): String {
        return target.getTargetKind()
    }

    @Throws(
        TargetParsingException::class,
        E::class,
        java.lang.InterruptedException::class,
        ProcessPackageDirectoryException::class
    )
    public override fun <E> findTargetsBeneathDirectory(
        repository: RepositoryName?,
        originalPattern: String?,
        directory: String?,
        rulesOnly: Boolean,
        forbiddenSubdirectories: IgnoredSubdirectories?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
        callback: BatchCallback<Target?, E?>,
        exceptionClass: java.lang.Class<E?>
    ) where E : java.lang.Exception?, E : QueryExceptionMarkerInterface? {
        val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?
        try {
            future =
                findTargetsBeneathDirectoryAsyncImpl(
                    repository,
                    originalPattern,
                    directory,
                    rulesOnly,
                    forbiddenSubdirectories,
                    excludedSubdirectories,
                    callback,
                    com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService()
                )
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            com.google.common.base.Throwables.throwIfInstanceOf<E?>(e, exceptionClass)
            throw java.lang.IllegalStateException(e)
        } catch (e: NoSuchPackageException) {
            // Can happen during a Skyframe no-keep-going evaluation.
            throw TargetParsingException(
                "error loading package under directory '" + directory + "': " + e.getMessage(),
                e,
                e.getDetailedExitCode()
            )
        }
        if (!isSuccessful(future)) {
            // Don't get the future if it finished successfully: all that will do is throw an
            // interrupted exception if this thread was interrupted, but that's not helpful for a done
            // future.
            try {
                future.get()
            } catch (e: ExecutionException) {
                com.google.common.base.Throwables.propagateIfPossible<java.lang.InterruptedException?, E?>(
                    e.getCause(),
                    java.lang.InterruptedException::class.java,
                    exceptionClass
                )
                throw java.lang.IllegalStateException(e.getCause())
            }
        }
    }

    public override fun <E>
            findTargetsBeneathDirectoryAsync(
        repository: RepositoryName?,
        originalPattern: String?,
        directory: String?,
        rulesOnly: Boolean,
        forbiddenSubdirectories: IgnoredSubdirectories?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
        callback: BatchCallback<Target?, E?>,
        exceptionClass: java.lang.Class<E?>,
        executor: com.google.common.util.concurrent.ListeningExecutorService
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> where E : java.lang.Exception?, E : QueryExceptionMarkerInterface? {
        try {
            return findTargetsBeneathDirectoryAsyncImpl(
                repository,
                originalPattern,
                directory,
                rulesOnly,
                forbiddenSubdirectories,
                excludedSubdirectories,
                callback,
                executor
            )
        } catch (e: TargetParsingException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(e)
        } catch (e: java.lang.InterruptedException) {
            return com.google.common.util.concurrent.Futures.immediateCancelledFuture<java.lang.Void?>()
        } catch (e: ProcessPackageDirectoryException) {
            throw java.lang.IllegalStateException(
                ("Async find targets beneath directory isn't called from within Skyframe: traversing "
                        + directory
                        + " for "
                        + originalPattern),
                e
            )
        } catch (e: NoSuchPackageException) {
            throw java.lang.IllegalStateException(
                ("Async find targets beneath directory isn't called from within Skyframe: traversing "
                        + directory
                        + " for "
                        + originalPattern),
                e
            )
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            if (exceptionClass.isInstance(e)) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
            }
            throw java.lang.IllegalStateException(e)
        }
    }

    /**
     * The returned future may throw [QueryException] (if `E` is [QueryException])
     * or [InterruptedException] on retrieval, but no other exceptions.
     */
    @Throws(
        TargetParsingException::class,
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        java.lang.InterruptedException::class,
        ProcessPackageDirectoryException::class,
        NoSuchPackageException::class
    )
    private fun <E>
            findTargetsBeneathDirectoryAsyncImpl(
        repository: RepositoryName?,
        pattern: String?,
        directory: String?,
        rulesOnly: Boolean,
        forbiddenSubdirectories: IgnoredSubdirectories?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
        callback: BatchCallback<Target?, E?>,
        executor: com.google.common.util.concurrent.ListeningExecutorService
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> where E : java.lang.Exception?, E : QueryExceptionMarkerInterface? {
        val actualPolicy: FilteringPolicy? =
            if (rulesOnly) FilteringPolicies.and(FilteringPolicies.RULES_ONLY, policy) else policy

        val futures: java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()
        val getPackageTargetsCallback: SafeBatchCallback<PackageIdentifier?> =
            SafeBatchCallback { pkgIdBatch ->
                futures.add(
                    executor.submit(
                        GetTargetsInPackagesTask<E?>(pkgIdBatch, pattern, actualPolicy, callback)
                    )
                )
            }

        val pathFragment: PathFragment? = TargetPatternResolverUtil.getPathFragment(directory)
        packageIdentifierBatchingCallbackFactory.create(
            getPackageTargetsCallback, MAX_PACKAGES_BULK_GET
        ).use { batchingCallback ->
            recursivePackageProvider.streamPackagesUnderDirectory(
                batchingCallback,
                eventHandler,
                repository,
                pathFragment,
                forbiddenSubdirectories,
                excludedSubdirectories
            )
        }
        if (futures.isEmpty()) {
            throw TargetParsingException(
                "no targets found beneath '" + pathFragment + "'", TargetPatterns.Code.TARGETS_MISSING
            )
        }
        return com.google.common.util.concurrent.Futures.whenAllSucceed<java.lang.Void?>(futures).call<java.lang.Void?>(
            java.util.concurrent.Callable { null },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    /**
     * Task to get all matching targets in the given packages, filter them, and pass them to the
     * target batch callback.
     */
    private inner class GetTargetsInPackagesTask<E>
        (
        packageIdentifiers: Iterable<PackageIdentifier?>,
        originalPattern: String?,
        actualPolicy: FilteringPolicy?,
        callback: BatchCallback<Target?, E?>
    ) : java.util.concurrent.Callable<java.lang.Void?> where E : java.lang.Exception?, E : QueryExceptionMarkerInterface? {
        private val packageIdentifiers: Iterable<PackageIdentifier?>
        private val originalPattern: String?
        private val actualPolicy: FilteringPolicy?
        private val callback: BatchCallback<Target?, E?>

        init {
            this.packageIdentifiers = packageIdentifiers
            this.originalPattern = originalPattern
            this.actualPolicy = actualPolicy
            this.callback = callback
        }

        @Throws(java.lang.InterruptedException::class)
        fun acquireTaskLock() {
            if (getTargetsTaskSemaphore != null) {
                getTargetsTaskSemaphore.acquire()
            }
        }

        fun releaseTaskLock() {
            if (getTargetsTaskSemaphore != null) {
                getTargetsTaskSemaphore.release()
            }
        }

        @Throws(E::class, java.lang.InterruptedException::class)
        override fun call(): java.lang.Void? {
            val pkgIdBatchSet: com.google.common.collect.ImmutableSet<PackageIdentifier?> =
                com.google.common.collect.ImmutableSet.copyOf<PackageIdentifier?>(packageIdentifiers)
            acquireTaskLock()
            packageSemaphore.acquireAll(pkgIdBatchSet)
            try {
                val resolvedTargets: Iterable<MutableCollection<Target?>> =
                    this@RecursivePackageProviderBackedTargetPatternResolver
                        .bulkGetTargetsInPackage(originalPattern, packageIdentifiers, actualPolicy)
                        .values()
                val filteredTargets: MutableList<Target?> =
                    java.util.ArrayList<Target?>(calculateSize<Target?>(resolvedTargets))
                for (targets in resolvedTargets) {
                    filteredTargets.addAll(targets)
                }
                // TODO(b/121277360): Invoking the callback while holding onto the package
                // semaphore can lead to deadlocks.
                //
                // Also, if the semaphore has a small count, acquireAll can also lead to problems if we
                // don't batch appropriately. Note: We default to an unbounded semaphore for SkyQuery.
                //
                // TODO(b/168142585): Make this code strictly correct in the situation where the semaphore
                // is bounded.
                callback.process(filteredTargets)
            } finally {
                packageSemaphore.releaseAll(pkgIdBatchSet)
                releaseTaskLock()
            }
            return null
        }
    }

    companion object {
        // TODO(janakr): Move this to a more generic place and unify with SkyQueryEnvironment's value?
        const val MAX_PACKAGES_BULK_GET: Int = 1000

        private fun <T> calculateSize(resolvedTargets: Iterable<MutableCollection<T?>>): Int {
            var size = 0
            for (targets in resolvedTargets) {
                size += targets.size()
            }
            return size
        }

        /** Inspired by not-yet-open-source futures code.  */
        private fun isSuccessful(future: java.util.concurrent.Future<*>): Boolean {
            if (future.isDone() && !future.isCancelled()) {
                try {
                    com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly(future)
                    return true
                } catch (e: ExecutionException) {
                    // Fall through.
                } catch (e: java.lang.RuntimeException) {
                }
            }
            return false
        }
    }
}
