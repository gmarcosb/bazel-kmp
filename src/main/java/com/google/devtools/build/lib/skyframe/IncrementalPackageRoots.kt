// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.PackageRoots

/**
 * An implementation of PackageRoots that allows incremental updating of the packageRootsMap.
 * 
 * 
 * This class is also in charge of planting the necessary symlinks.
 */
class IncrementalPackageRoots private constructor(
    execroot: com.google.devtools.build.lib.vfs.Path,
    singleSourceRoot: Root,
    eventBus: com.google.common.eventbus.EventBus?,
    prefix: String?,
    ignoredPaths: IgnoredSubdirectories?,
    useSiblingRepositoryLayout: Boolean,
    allowExternalRepositories: Boolean
) : PackageRoots {
    // We only keep track of PackageIdentifier from external repos here as a memory optimization:
    // packages belong to the main repository all share the same root, which is singleSourceRoot.
    private val threadSafeExternalRepoPackageRootsMap: MutableMap<PackageIdentifier?, Root?>

    @javax.annotation.concurrent.GuardedBy("stateLock")
    private var donePackages: MutableSet<NestedSet.Node?>? =
        com.google.common.collect.Sets.newConcurrentHashSet<NestedSet.Node?>()

    // Only tracks the symlinks lazily planted after the first eager planting wave.
    @javax.annotation.concurrent.GuardedBy("stateLock")
    private var lazilyPlantedSymlinks: MutableSet<com.google.devtools.build.lib.vfs.Path?>? =
        com.google.common.collect.Sets.newConcurrentHashSet<com.google.devtools.build.lib.vfs.Path?>()

    private val symlinkPlantingPool: com.google.common.util.concurrent.ListeningExecutorService
    private val stateLock = Any()
    private val execroot: com.google.devtools.build.lib.vfs.Path
    private val singleSourceRoot: Root
    private val prefix: String?

    private val ignoredPaths: IgnoredSubdirectories?
    private val useSiblingRepositoryLayout: Boolean

    private val allowExternalRepositories: Boolean
    private var eventBus: com.google.common.eventbus.EventBus?

    // "maybe" because some conflicts in a case-insensitive FS may not be in a case-sensitive one.
    private var maybeConflictingBaseNamesLowercase: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>()

    init {
        this.threadSafeExternalRepoPackageRootsMap =
            com.google.common.collect.Maps.newConcurrentMap<PackageIdentifier?, Root?>()
        this.execroot = execroot
        this.singleSourceRoot = singleSourceRoot
        this.prefix = prefix
        this.ignoredPaths = ignoredPaths
        this.eventBus = eventBus
        this.useSiblingRepositoryLayout = useSiblingRepositoryLayout
        this.allowExternalRepositories = allowExternalRepositories
        this.symlinkPlantingPool =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
                Executors.newFixedThreadPool(
                    SYMLINK_PLANTING_PARALLELISM,
                    com.google.common.util.concurrent.ThreadFactoryBuilder()
                        .setNameFormat("Non-eager Symlink planter %d").build()
                )
            )
    }

    /**
     * Eagerly plant the symlinks to the directories under the single source root. It's possible that
     * there's a conflict when we plant symlinks eagerly. In that case, we skip planting the
     * conflicting symlinks eagerly and wait until later in the build to see which of the conflicting
     * dir we actually need.
     * 
     * 
     * Eagerly planting the symlinks is much cheaper, hence we'd like to do it as much as possible
     * and only resort to the other route when really necessary.
     * 
     * 
     * Example: when we plant symlinks in a case-insensitive FS, "foo" and "Foo" would conflict:
     * 
     * <pre>
     * /sourceroot
     * ├── noclash
     * ├── foo
     * └── Foo
     * 
     * /execroot
     * ├── noclash -> /sourceroot/noclash
     * ├── foo -> /sourceroot/foo
     * └── Foo (clashing with foo in a case-insensitive FS)
    </pre> * 
     * 
     * We'd plant the symlink to "noclash" first, then wait to see whether we need "foo" or "Foo". If
     * we end up needing both, throw an error. See [.recursiveRegisterAndPlantMissingSymlinks].
     */
    @Throws(AbruptExitException::class)
    fun eagerlyPlantSymlinksToSingleSourceRoot() {
        try {
            maybeConflictingBaseNamesLowercase =
                SymlinkForest.eagerlyPlantSymlinkForestSinglePackagePath(
                    execroot,
                    singleSourceRoot.asPath(),
                    prefix,
                    ignoredPaths,
                    useSiblingRepositoryLayout
                )
        } catch (e: IOException) {
            throwAbruptExitException(e)
        }
    }

    val packageRootsMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?>?
        /** There is currently no use case for this method, and it should not be called.  */
        get() {
            throw java.lang.UnsupportedOperationException(
                "IncrementalPackageRoots does not provide the package roots map directly."
            )
        }

    val packageRootLookup: PackageRootLookup?
        get() = PackageRootLookup { packageId ->
            if (packageId.getRepository().isMain())
                singleSourceRoot
            else
                threadSafeExternalRepoPackageRootsMap.get(packageId)
        }

    // Intentionally don't allow concurrent events here to prevent a race condition between planting
    // a symlink and starting an action that requires that symlink. This race condition is possible
    // because of the various memoizations we use to avoid repeated work.
    @com.google.common.eventbus.Subscribe
    @Throws(AbruptExitException::class)
    fun lazilyPlantSymlinks(event: TopLevelTargetReadyForSymlinkPlanting) {
        if (allowExternalRepositories || !maybeConflictingBaseNamesLowercase.isEmpty()) {
            val donePackagesLocalRef: MutableSet<NestedSet.Node?>?
            val lazilyPlantedSymlinksLocalRef: MutableSet<com.google.devtools.build.lib.vfs.Path?>?
            // May still race with analysisFinished, hence the synchronization.
            synchronized(stateLock) {
                if (donePackages == null || lazilyPlantedSymlinks == null) {
                    return
                }
                donePackagesLocalRef = donePackages
                lazilyPlantedSymlinksLocalRef = lazilyPlantedSymlinks
            }

            // Initial capacity: arbitrarily chosen.
            // This list doesn't need to be thread-safe, as items are added sequentially.
            val futures: MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
                java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>(128)
            recursiveRegisterAndPlantMissingSymlinks(
                event.transitivePackagesForSymlinkPlanting,
                donePackagesLocalRef,
                lazilyPlantedSymlinksLocalRef,
                futures
            )

            // Now wait on the futures. After that, we can be sure that the symlinks have been planted.
            try {
                com.google.common.util.concurrent.Futures.whenAllSucceed<java.lang.Void?>(futures).call<Any?>(
                    java.util.concurrent.Callable { null },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                ).get()
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                // Bail
            } catch (e: ExecutionException) {
                if (e.cause is AbruptExitException) {
                    throw e.cause as AbruptExitException?
                }
                throw java.lang.IllegalStateException("Unexpected exception", e)
            }
        }
    }

    @com.google.common.eventbus.Subscribe
    fun analysisFinished(unused: AnalysisPhaseCompleteEvent?) {
        shutdown(false)
    }

    /**
     * Lazily plant the required symlinks that couldn't be planted in the initial eager planting wave.
     * 
     * 
     * There are 2 possibilities: either we're planting symlinks to the external repos, or there's
     * potentially conflicting symlinks detected.
     */
    private fun recursiveRegisterAndPlantMissingSymlinks(
        packages: NestedSet<Package.Metadata?>,
        donePackagesRef: MutableSet<Node?>,
        lazilyPlantedSymlinksRef: MutableSet<com.google.devtools.build.lib.vfs.Path?>,
        futures: MutableList<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>
    ) {
        // Optimization to prune subsequent traversals.
        // A false negative does not affect correctness.
        if (!donePackagesRef.add(packages.toNode())) {
            return
        }

        synchronized(symlinkPlantingPool) {
            // Some other thread shut down the executor, exit now.
            if (symlinkPlantingPool.isShutdown()) {
                return
            }
            for (pkg in packages.getLeaves()) {
                futures.add(
                    symlinkPlantingPool.submit<java.lang.Void?>(
                        java.util.concurrent.Callable { plantSingleSymlinkForPackage(pkg, lazilyPlantedSymlinksRef) })
                )
            }
        }
        for (transitive in packages.getNonLeaves()) {
            recursiveRegisterAndPlantMissingSymlinks(
                transitive, donePackagesRef, lazilyPlantedSymlinksRef, futures
            )
        }
    }

    @Throws(AbruptExitException::class)
    private fun plantSingleSymlinkForPackage(
        pkg: Package.Metadata, lazilyPlantedSymlinksRef: MutableSet<com.google.devtools.build.lib.vfs.Path?>
    ): java.lang.Void? {
        try {
            val pkgId: PackageIdentifier = pkg.packageIdentifier()
            if (isExternalRepository(pkgId)) {
                threadSafeExternalRepoPackageRootsMap.putIfAbsent(
                    pkg.packageIdentifier(), pkg.sourceRoot()
                )
                SymlinkForest.plantSingleSymlinkForExternalRepo(
                    pkgId.getRepository(),
                    pkg.sourceRoot().asPath(),
                    execroot,
                    useSiblingRepositoryLayout,
                    lazilyPlantedSymlinksRef
                )
            } else if (!maybeConflictingBaseNamesLowercase.isEmpty()) {
                val originalBaseName: String = pkgId.getTopLevelDir()
                val baseNameLowercase: String = com.google.common.base.Ascii.toLowerCase(originalBaseName)

                // As Skymeld only supports single package path at the moment, we only seek to symlink to
                // the top-level dir i.e. what's directly under the source root.
                val link: com.google.devtools.build.lib.vfs.Path = execroot.getRelative(originalBaseName)
                val target: com.google.devtools.build.lib.vfs.Path? = singleSourceRoot.getRelative(originalBaseName)

                if (originalBaseName.isEmpty()
                    || !maybeConflictingBaseNamesLowercase.contains(baseNameLowercase) || !SymlinkForest.symlinkShouldBePlanted(
                        prefix, ignoredPaths, useSiblingRepositoryLayout, originalBaseName, target
                    )
                ) {
                    // We should have already eagerly planted a symlink for this, or there's nothing to do.
                    return null
                }

                if (lazilyPlantedSymlinksRef.add(link)) {
                    try {
                        link.createSymbolicLink(target)
                    } catch (e: IOException) {
                        val errorMessage: java.lang.StringBuilder =
                            java.lang.StringBuilder(
                                String.format("Failed to plant a symlink: %s -> %s", link, target)
                            )
                        if (link.exists() && link.isSymbolicLink()) {
                            // If the link already exists, it must mean that we're planting from a
                            // case-insensitive file system and this is a legitimate conflict.
                            // TODO(b/295300378) We technically can go deeper here and try to create the subdirs
                            // to try to resolve the conflict, but the complexity isn't worth it at the moment
                            // and the non-skymeld code path isn't doing any better. Revisit if necessary.
                            val existingTarget: com.google.devtools.build.lib.vfs.Path = link.resolveSymbolicLinks()
                            if (existingTarget != target) {
                                errorMessage.append(
                                    String.format(
                                        ". Found an existing conflicting symlink: %s -> %s", link, existingTarget
                                    )
                                )
                            }
                        }

                        throw SymlinkPlantingException(errorMessage.toString(), e)
                    }
                }
            }
        } catch (e: IOException) {
            throwAbruptExitException(e)
        } catch (e: SymlinkPlantingException) {
            throwAbruptExitException(e)
        }
        return null
    }

    fun shutdown() {
        shutdown(true)
    }

    /**
     * Drops the intermediate states and stop receiving new events.
     * 
     * 
     * This essentially makes this instance read-only. Should be called when and only when all
     * analysis work is done in the build to free up some memory.
     */
    private fun shutdown(now: Boolean) {
        // This instance is retained after a build via ArtifactFactory, so it's important that we remove
        // the reference to the eventBus here for it to be GC'ed.
        if (eventBus != null) {
            eventBus.unregister(this)
            eventBus = null
        }
        synchronized(stateLock) {
            donePackages = null
            lazilyPlantedSymlinks = null
            maybeConflictingBaseNamesLowercase = com.google.common.collect.ImmutableSet.of<String?>()
        }
        synchronized(symlinkPlantingPool) {
            if (!symlinkPlantingPool.isShutdown()) {
                if (now) {
                    symlinkPlantingPool.shutdownNow()
                } else {
                    symlinkPlantingPool.shutdown()
                }
                com.google.common.util.concurrent.Uninterruptibles.awaitTerminationUninterruptibly(symlinkPlantingPool)
            }
        }
    }

    companion object {
        // This work is I/O bound: set the parallelism to something similar to the default number of
        // loading threads.
        private const val SYMLINK_PLANTING_PARALLELISM = 200

        fun createAndRegisterToEventBus(
            execroot: com.google.devtools.build.lib.vfs.Path,
            singleSourceRoot: Root,
            eventBus: com.google.common.eventbus.EventBus,
            prefix: String?,
            ignoredSubdirectories: IgnoredSubdirectories?,
            useSiblingRepositoryLayout: Boolean,
            allowExternalRepositories: Boolean
        ): IncrementalPackageRoots {
            val incrementalPackageRoots =
                IncrementalPackageRoots(
                    execroot,
                    singleSourceRoot,
                    eventBus,
                    prefix,
                    ignoredSubdirectories,
                    useSiblingRepositoryLayout,
                    allowExternalRepositories
                )
            eventBus.register(incrementalPackageRoots)
            return incrementalPackageRoots
        }

        @Throws(AbruptExitException::class)
        private fun throwAbruptExitException(e: java.lang.Exception?) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage("Failed to prepare the symlink forest: " + e)
                        .setSymlinkForest(
                            FailureDetails.SymlinkForest.newBuilder()
                                .setCode(FailureDetails.SymlinkForest.Code.CREATION_FAILED)
                        )
                        .build()
                ),
                e
            )
        }

        private fun isExternalRepository(pkgId: PackageIdentifier): Boolean {
            return !pkgId.getRepository().isMain()
        }
    }
}
