// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.actions.ThreadStateReceiver

/**
 * Caches the results of glob evaluations for a single package. Has lifetime of evaluation of that
 * package.
 */
@ThreadCompatible
class GlobCache(
    packageDirectory: com.google.devtools.build.lib.vfs.Path?,
    packageId: PackageIdentifier?,
    ignoredSubdirectories: IgnoredSubdirectories,
    locator: CachingPackageLocator,
    syscallCache: SyscallCache,
    globExecutor: java.util.concurrent.Executor?,
    maxDirectoriesToEagerlyVisit: Int,
    threadStateReceiverForMetrics: ThreadStateReceiver
) {
    /**
     * A mapping from glob expressions (e.g. "*.java") to the list of files it matched (in the order
     * returned by VFS) at the time the package was constructed. Required for sound dependency
     * analysis.
     * 
     * 
     * We don't use a Multimap because it provides no way to distinguish "key not present" from
     * (key -> {}).
     */
    private val globCache: MutableMap<com.google.devtools.build.lib.util.Pair<String?, com.google.devtools.build.lib.packages.Globber.Operation?>?, java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>>?> =
        HashMap<com.google.devtools.build.lib.util.Pair<String?, com.google.devtools.build.lib.packages.Globber.Operation?>?, java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>>?>()

    /** The directory in which our package's BUILD file resides.  */
    private val packageDirectory: com.google.devtools.build.lib.vfs.Path

    /** The name of the package we belong to.  */
    private val packageId: PackageIdentifier

    private val filesystemOps: CountingFilesystemOps

    private val maxDirectoriesToEagerlyVisit: Int

    /** The thread pool for glob evaluation.  */
    private val globExecutor: java.util.concurrent.Executor

    private val globalStarted: AtomicBoolean = AtomicBoolean(false)

    private val packageLocator: CachingPackageLocator

    private val ignoredSubdirectories: IgnoredSubdirectories

    /**
     * Create a glob expansion cache.
     * 
     * @param packageDirectory globs will be expanded relatively to this directory.
     * @param packageId the name of the package this cache belongs to.
     * @param locator the package locator.
     * @param globExecutor thread pool for glob evaluation.
     * @param maxDirectoriesToEagerlyVisit the number of directories to eagerly traverse on the first
     * glob for a given package, in order to warm the filesystem. -1 means do no eager traversal.
     * See [     ][com.google.devtools.build.lib.pkgcache.PackageOptions.maxDirectoriesToEagerlyVisitInGlobbing].
     */
    init {
        this.packageDirectory =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(packageDirectory)
        this.packageId = com.google.common.base.Preconditions.checkNotNull<PackageIdentifier>(packageId)
        com.google.common.base.Preconditions.checkNotNull<java.util.concurrent.Executor?>(globExecutor)
        this.globExecutor =
            java.util.concurrent.Executor { command: java.lang.Runnable? ->
                globExecutor.execute(
                    java.lang.Runnable {
                        threadStateReceiverForMetrics.started().use { ignored ->
                            command.run()
                        }
                    })
            }
        this.filesystemOps = CountingFilesystemOps(syscallCache)
        this.maxDirectoriesToEagerlyVisit = maxDirectoriesToEagerlyVisit

        com.google.common.base.Preconditions.checkNotNull<CachingPackageLocator?>(locator)
        this.packageLocator = locator
        this.ignoredSubdirectories = ignoredSubdirectories
    }

    private fun globCacheShouldTraverseDirectory(directory: com.google.devtools.build.lib.vfs.Path): Boolean {
        if (directory == packageDirectory) {
            return true
        }

        val subPackagePath: PathFragment? =
            packageId.getPackageFragment().getRelative(directory.relativeTo(packageDirectory))

        if (ignoredSubdirectories.matchingEntry(subPackagePath) != null) {
            return false
        }

        return !isSubPackage(PackageIdentifier.create(packageId.getRepository(), subPackagePath))
    }

    private fun isSubPackage(directory: com.google.devtools.build.lib.vfs.Path): Boolean {
        return isSubPackage(
            PackageIdentifier.create(
                packageId.getRepository(),
                packageId.getPackageFragment().getRelative(directory.relativeTo(packageDirectory))
            )
        )
    }

    private fun isSubPackage(subPackageId: PackageIdentifier?): Boolean {
        return packageLocator.getBuildFileForPackage(subPackageId) != null
    }

    /**
     * Returns the future result of evaluating glob "pattern" against this package's directory, using
     * the package's cache of previously-started globs if possible.
     * 
     * @return the list of paths matching the pattern, relative to the package's directory.
     * @throws BadGlobException if the glob was syntactically invalid, or contained uplevel
     * references.
     */
    @Throws(BadGlobException::class)
    fun getGlobUnsortedAsync(
        pattern: String,
        globberOperation: com.google.devtools.build.lib.packages.Globber.Operation?
    ): java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>> {
        var cached: java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>>? = globCache.get(
            com.google.devtools.build.lib.util.Pair.of<String?, com.google.devtools.build.lib.packages.Globber.Operation?>(
                pattern,
                globberOperation
            )
        )
        if (cached == null) {
            if (maxDirectoriesToEagerlyVisit > -1 && !globalStarted.getAndSet(true)) {
                packageDirectory.prefetchPackageAsync(maxDirectoriesToEagerlyVisit)
            }
            cached = safeGlobUnsorted(pattern, globberOperation)
            setGlobPaths(pattern, globberOperation, cached)
        }
        return cached
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class, BadGlobException::class, java.lang.InterruptedException::class)
    fun getGlobUnsorted(pattern: String): MutableList<String?> {
        return getGlobUnsorted(pattern, com.google.devtools.build.lib.packages.Globber.Operation.FILES_AND_DIRS)
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class, BadGlobException::class, java.lang.InterruptedException::class)
    fun getGlobUnsorted(
        pattern: String,
        globberOperation: com.google.devtools.build.lib.packages.Globber.Operation?
    ): MutableList<String?> {
        val futureResult: java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>> =
            getGlobUnsortedAsync(pattern, globberOperation)
        val globPaths: MutableList<com.google.devtools.build.lib.vfs.Path> = fromFuture(futureResult)
        // Replace the UnixGlob.GlobFuture with a completed future object, to allow
        // garbage collection of the GlobFuture and GlobVisitor objects.
        if (futureResult !is com.google.common.util.concurrent.SettableFuture<*>) {
            val completedFuture: com.google.common.util.concurrent.SettableFuture<MutableList<com.google.devtools.build.lib.vfs.Path?>?> =
                com.google.common.util.concurrent.SettableFuture.create<MutableList<com.google.devtools.build.lib.vfs.Path?>?>()
            completedFuture.set(globPaths)
            globCache.put(
                com.google.devtools.build.lib.util.Pair.of<String?, com.google.devtools.build.lib.packages.Globber.Operation?>(
                    pattern,
                    globberOperation
                ), completedFuture
            )
        }

        val result: MutableList<String?> =
            com.google.common.collect.Lists.newArrayListWithCapacity<String?>(globPaths.size())
        for (path in globPaths) {
            val relative: String = path.relativeTo(packageDirectory).getPathString()
            // Don't permit "" (meaning ".") in the glob expansion, since it's
            // invalid as a label, plus users should say explicitly if they
            // really want to name the package directory.
            if (!relative.isEmpty()) {
                result.add(relative)
            }
        }
        return result
    }

    /** Adds glob entries to the cache.  */
    private fun setGlobPaths(
        pattern: String?,
        globberOperation: com.google.devtools.build.lib.packages.Globber.Operation?,
        result: java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>>?
    ) {
        globCache.put(
            com.google.devtools.build.lib.util.Pair.of<String?, com.google.devtools.build.lib.packages.Globber.Operation?>(
                pattern,
                globberOperation
            ), result
        )
    }

    /** Actually execute a glob against the filesystem. Otherwise similar to getGlob().  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(BadGlobException::class)
    fun safeGlobUnsorted(
        pattern: String,
        globberOperation: com.google.devtools.build.lib.packages.Globber.Operation?
    ): java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>>? {
        // Forbidden patterns:
        if (pattern.indexOf('?'.code) != -1) {
            throw BadGlobException("glob pattern '" + pattern + "' contains forbidden '?' wildcard")
        }
        // Patterns forbidden by UnixGlob library:
        val error: String? = UnixGlob.checkPatternForError(pattern)
        if (error != null) {
            throw BadGlobException(error + " (in glob pattern '" + pattern + "')")
        }
        try {
            return UnixGlob.Builder(packageDirectory, filesystemOps)
                .addPattern(pattern)
                .setPathDiscriminator(GlobUnixPathDiscriminator(globberOperation))
                .setExecutor(globExecutor)
                .globAsync()
        } catch (ex: BadPattern) {
            throw BadGlobException(ex.getMessage())
        }
    }

    /**
     * Helper for evaluating the build language expression "glob(includes, excludes)" in the context
     * of this package.
     * 
     * 
     * Called by PackageFactory via Package.
     */
    @Throws(IOException::class, BadGlobException::class, java.lang.InterruptedException::class)
    fun globUnsorted(
        includes: MutableList<String>,
        excludes: MutableList<String?>,
        globberOperation: com.google.devtools.build.lib.packages.Globber.Operation,
        allowEmpty: Boolean
    ): MutableList<String?> {
        // Start globbing all patterns in parallel. The getGlob() calls below will
        // block on an individual pattern's results, but the other globs can
        // continue in the background.
        for (pattern in includes) {
            @Suppress("unused") val possiblyIgnoredError: java.util.concurrent.Future<*> =
                getGlobUnsortedAsync(pattern, globberOperation)
        }

        val results: HashSet<String?> = HashSet<String?>()
        for (pattern in includes) {
            val items = getGlobUnsorted(pattern, globberOperation)
            if (!allowEmpty && items.isEmpty()) {
                GlobberUtils.throwBadGlobExceptionEmptyResult(pattern, globberOperation)
            }
            results.addAll(items)
        }
        try {
            UnixGlob.removeExcludes(results, excludes)
        } catch (ex: BadPattern) {
            throw BadGlobException(ex.getMessage())
        }
        if (!allowEmpty && results.isEmpty()) {
            GlobberUtils.throwBadGlobExceptionAllExcluded(globberOperation)
        }
        return java.util.ArrayList<String?>(results)
    }

    fun getGlobFilesystemOperationCost(): Long {
        return filesystemOps.filesystemOpCost.get()
    }

    fun getKeySet(): MutableSet<com.google.devtools.build.lib.util.Pair<String?, com.google.devtools.build.lib.packages.Globber.Operation?>?> {
        return globCache.keySet()
    }

    /** Block on the completion of all potentially-abandoned background tasks.  */
    fun finishBackgroundTasks() {
        finishBackgroundTasks(globCache.values())
    }

    fun cancelBackgroundTasks() {
        cancelBackgroundTasks(globCache.values())
    }

    override fun toString(): String {
        return "GlobCache for " + packageId + " in " + packageDirectory
    }

    /**
     * Used by 'glob()' and 'subpackages()' with UnixGlob to determine if a directory should be
     * traversed when recursing through a filesystem directory structure or include a Path in the
     * result. This essentially filters out a set of ignored prefixes and then checks to see if a
     * given sub-dir actually represents a sub-package or not when traversing.
     * 
     * 
     * The logic of including inspects the Globber.Operation to determine if it will include all
     * files, include directories or subpackages in the output.
     */
    private inner class GlobUnixPathDiscriminator(globberOperation: com.google.devtools.build.lib.packages.Globber.Operation?) :
        UnixGlobPathDiscriminator {
        private val globberOperation: com.google.devtools.build.lib.packages.Globber.Operation?

        init {
            this.globberOperation = globberOperation
        }

        override fun shouldTraverseDirectory(directory: com.google.devtools.build.lib.vfs.Path): Boolean {
            return globCacheShouldTraverseDirectory(directory)
        }

        override fun shouldIncludePathInResult(
            path: com.google.devtools.build.lib.vfs.Path,
            isDirectory: Boolean
        ): Boolean {
            return when (globberOperation) {
                com.google.devtools.build.lib.packages.Globber.Operation.FILES_AND_DIRS -> !isDirectory || !isSubPackage(
                    path
                )

                com.google.devtools.build.lib.packages.Globber.Operation.SUBPACKAGES -> {
                    // no files, or root pkg
                    if (!isDirectory || path == packageDirectory) {
                        false
                    }
                    isSubPackage(path)
                }

                com.google.devtools.build.lib.packages.Globber.Operation.FILES -> !isDirectory
            }
        }
    }

    /**
     * A [FilesystemOps] implementation that delegates to a [SyscallCache] but also
     * computes a total cost of the unique filesystem operations (regardless or not if there were
     * actually performed or cached; this way the cost is deterministic for a set of glob operations).
     * 
     * 
     * [.statIfFound] costs `1` and [.readdir] costs `1 + D`,
     * where `D` is the number of dirents.
     */
    private class CountingFilesystemOps(syscallCache: SyscallCache) : FilesystemOps {
        private val syscallCache: SyscallCache
        private val filesystemOpCost: AtomicLong = AtomicLong(0L)

        private val pathsForStatIfFound: MutableSet<com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.Sets.newConcurrentHashSet<com.google.devtools.build.lib.vfs.Path?>()
        private val pathsForReaddir: MutableSet<com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.Sets.newConcurrentHashSet<com.google.devtools.build.lib.vfs.Path?>()

        init {
            this.syscallCache = syscallCache
        }

        @Throws(IOException::class)
        override fun statIfFound(path: com.google.devtools.build.lib.vfs.Path?): FileStatus? {
            if (pathsForStatIfFound.add(path)) {
                filesystemOpCost.incrementAndGet()
            }
            return syscallCache.statIfFound(path)
        }

        @Throws(IOException::class)
        override fun readdir(path: com.google.devtools.build.lib.vfs.Path?): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?> {
            val uniqueOp = pathsForReaddir.add(path)
            if (uniqueOp) {
                filesystemOpCost.incrementAndGet()
            }
            val result: MutableCollection<com.google.devtools.build.lib.vfs.Dirent?> = syscallCache.readdir(path)
            if (uniqueOp) {
                filesystemOpCost.addAndGet(result.size().toLong())
            }
            return result
        }
    }

    companion object {
        /** Sanitize the future exceptions - the only expected checked exception is IOException.  */
        @Throws(IOException::class, java.lang.InterruptedException::class)
        private fun fromFuture(future: java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>>): MutableList<com.google.devtools.build.lib.vfs.Path> {
            try {
                return future.get()
            } catch (e: ExecutionException) {
                val cause: Throwable = e.getCause()
                com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(cause, IOException::class.java)
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    cause,
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(cause)
                throw java.lang.RuntimeException(e)
            }
        }

        private fun finishBackgroundTasks(tasks: MutableCollection<java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>?>>) {
            for (task in tasks) {
                try {
                    fromFuture(task)
                } catch (e: CancellationException) {
                    // Ignore: If this was still going on in the background, some other
                    // failure already occurred.
                } catch (e: IOException) {
                } catch (e: java.lang.InterruptedException) {
                }
            }
        }

        private fun cancelBackgroundTasks(tasks: MutableCollection<java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>?>>) {
            for (task in tasks) {
                task.cancel(true)
            }

            for (task in tasks) {
                try {
                    task.get()
                } catch (e: CancellationException) {
                    // We don't care. Point is, the task does not bother us anymore.
                } catch (e: ExecutionException) {
                } catch (e: java.lang.InterruptedException) {
                }
            }
        }
    }
}
