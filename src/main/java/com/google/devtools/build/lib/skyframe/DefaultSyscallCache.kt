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
package com.google.devtools.build.lib.skyframe

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.skyframe.CompactSortedDirents
import com.google.devtools.build.lib.skyframe.DefaultSyscallCache
import com.google.devtools.build.lib.skyframe.Dirents
import com.google.devtools.build.lib.unsafe.StringUnsafe
import com.google.devtools.build.lib.util.LatestObjectMetricExporter
import com.google.devtools.build.lib.vfs.FileStatus
import com.google.devtools.build.lib.vfs.Symlinks
import com.google.devtools.build.lib.vfs.SyscallCache
import com.google.devtools.build.lib.vfs.SyscallCache.DirentTypeWithSkip
import java.io.IOException

/**
 * A basic implementation of [SyscallCache] that caches stat and readdir operations, used if
 * no custom cache is set in [ ][com.google.devtools.build.lib.runtime.WorkspaceBuilder.setSyscallCache].
 * 
 * 
 * Allows non-Skyframe operations (like non-Skyframe globbing) to share a filesystem cache with
 * Skyframe operations, and may be able to answer questions (like the type of a file) based on
 * existing data (like the directory listing of a parent) without filesystem access.
 */
class DefaultSyscallCache private constructor(
    statCacheSupplier: java.util.function.Supplier<com.github.benmanes.caffeine.cache.LoadingCache<com.google.devtools.build.lib.util.Pair<com.google.devtools.build.lib.vfs.Path?, Symlinks?>?, Any?>>,
    readdirCacheSupplier: java.util.function.Supplier<com.github.benmanes.caffeine.cache.LoadingCache<com.google.devtools.build.lib.vfs.Path?, Any?>>,
    statCacheMetricExporter: LatestObjectMetricExporter<com.github.benmanes.caffeine.cache.Cache<*, *>?>?,
    readdirCacheMetricExporter: LatestObjectMetricExporter<com.github.benmanes.caffeine.cache.Cache<*, *>?>?
) : SyscallCache {
    private val statCacheSupplier: java.util.function.Supplier<com.github.benmanes.caffeine.cache.LoadingCache<com.google.devtools.build.lib.util.Pair<com.google.devtools.build.lib.vfs.Path?, Symlinks?>?, Any?>>
    private val readdirCacheSupplier: java.util.function.Supplier<com.github.benmanes.caffeine.cache.LoadingCache<com.google.devtools.build.lib.vfs.Path?, Any?>>

    private val statCacheMetricExporter: LatestObjectMetricExporter<com.github.benmanes.caffeine.cache.Cache<*, *>?>?

    private val readdirCacheMetricExporter: LatestObjectMetricExporter<com.github.benmanes.caffeine.cache.Cache<*, *>?>?

    private var statCache: com.github.benmanes.caffeine.cache.LoadingCache<com.google.devtools.build.lib.util.Pair<com.google.devtools.build.lib.vfs.Path?, Symlinks?>?, Any?>? =
        null

    /* Caches the result of readdir(<path>, Symlinks.NOFOLLOW) calls. */
    private var readdirCache: com.github.benmanes.caffeine.cache.LoadingCache<com.google.devtools.build.lib.vfs.Path?, Any?>? =
        null

    init {
        this.statCacheSupplier = statCacheSupplier
        this.readdirCacheSupplier = readdirCacheSupplier
        this.statCacheMetricExporter = statCacheMetricExporter
        this.readdirCacheMetricExporter = readdirCacheMetricExporter
        clear()
    }

    /** Builder for a per-build filesystem cache.  */
    class Builder private constructor() {
        private var maxStats: Int = com.google.devtools.build.lib.skyframe.DefaultSyscallCache.Builder.Companion.UNSET
        private var maxReaddirs: Int =
            com.google.devtools.build.lib.skyframe.DefaultSyscallCache.Builder.Companion.UNSET
        private var initialCapacity: Int =
            com.google.devtools.build.lib.skyframe.DefaultSyscallCache.Builder.Companion.UNSET
        private var statCacheMetricExporter: LatestObjectMetricExporter<com.github.benmanes.caffeine.cache.Cache<*, *>?>? =
            null
        private var readdirCacheMetricExporter: LatestObjectMetricExporter<com.github.benmanes.caffeine.cache.Cache<*, *>?>? =
            null

        /** Sets the upper bound of the 'stat' cache. This cache is unbounded by default.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMaxStats(maxStats: Int): Builder {
            this.maxStats = maxStats
            return this
        }

        /** Sets the upper bound of the 'readdir' cache. This cache is unbounded by default.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMaxReaddirs(maxReaddirs: Int): Builder {
            this.maxReaddirs = maxReaddirs
            return this
        }

        /** Sets the concurrency level of the caches.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInitialCapacity(initialCapacity: Int): Builder {
            this.initialCapacity = initialCapacity
            return this
        }

        /**
         * Sets the metric exporter for the 'stat' cache.
         * 
         * 
         * No metrics are exported by default. If a non-null value is set, the 'stat' cache will
         * record access statistics with some overhead.
         * 
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Deprecated(
            """If you need this, please file an issue on the Bazel GitHub tracker. It ultimately
          did not work as intended internally at Google, but it can be retained if others are using
          it."""
        )
        fun setStatCacheMetricExporter(
            statCacheMetricExporter: LatestObjectMetricExporter<com.github.benmanes.caffeine.cache.Cache<*, *>?>?
        ): Builder {
            this.statCacheMetricExporter = statCacheMetricExporter
            return this
        }

        /**
         * Sets the metric exporter for the 'readdir' cache.
         * 
         * 
         * No metrics are exported by default. If a non-null value is set, the 'readdir' cache will
         * record access statistics with some overhead.
         * 
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Deprecated(
            """If you need this, please file an issue on the Bazel GitHub tracker. It ultimately
          did not work as intended internally at Google, but it can be retained if others are using
          it."""
        )
        fun setReaddirCacheMetricExporter(
            readdirCacheMetricExporter: LatestObjectMetricExporter<com.github.benmanes.caffeine.cache.Cache<*, *>?>?
        ): Builder {
            this.readdirCacheMetricExporter = readdirCacheMetricExporter
            return this
        }

        fun build(): DefaultSyscallCache {
            val statCacheBuilder: Caffeine<Any?, Any?> = Caffeine.newBuilder()
            if (maxStats != com.google.devtools.build.lib.skyframe.DefaultSyscallCache.Builder.Companion.UNSET) {
                statCacheBuilder.maximumSize(maxStats.toLong())
            }
            if (statCacheMetricExporter != null) {
                statCacheBuilder.recordStats()
            }
            val readdirCacheBuilder: Caffeine<Any?, Any?> = Caffeine.newBuilder()
            if (maxReaddirs != com.google.devtools.build.lib.skyframe.DefaultSyscallCache.Builder.Companion.UNSET) {
                readdirCacheBuilder.maximumSize(maxReaddirs.toLong())
            }
            if (readdirCacheMetricExporter != null) {
                readdirCacheBuilder.recordStats()
            }
            if (initialCapacity != com.google.devtools.build.lib.skyframe.DefaultSyscallCache.Builder.Companion.UNSET) {
                statCacheBuilder.initialCapacity(initialCapacity)
                readdirCacheBuilder.initialCapacity(initialCapacity)
            }
            return DefaultSyscallCache(
                java.util.function.Supplier {
                    statCacheBuilder.build<com.google.devtools.build.lib.util.Pair<com.google.devtools.build.lib.vfs.Path?, Symlinks?>?, Any?>(
                        com.github.benmanes.caffeine.cache.CacheLoader { p: com.google.devtools.build.lib.util.Pair<com.google.devtools.build.lib.vfs.Path?, Symlinks?>? ->
                            statImpl(p)
                        })
                },
                java.util.function.Supplier {
                    readdirCacheBuilder.build<com.google.devtools.build.lib.vfs.Path?, Any?>(
                        com.github.benmanes.caffeine.cache.CacheLoader { p: com.google.devtools.build.lib.vfs.Path? ->
                            readdirImpl(p)
                        })
                },
                statCacheMetricExporter,
                readdirCacheMetricExporter
            )
        }

        companion object {
            private val UNSET = -1
        }
    }

    @Throws(IOException::class)
    override fun readdir(path: com.google.devtools.build.lib.vfs.Path?): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>? {
        val result: Any? = readdirCache.get(path)
        if (result is IOException) {
            throw result
        }
        return result as MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>? // unchecked cast
    }

    @Throws(IOException::class)
    override fun statIfFound(path: com.google.devtools.build.lib.vfs.Path?, symlinks: Symlinks?): FileStatus? {
        // Try to load a Symlinks.NOFOLLOW result first. Symlinks are rare and this enables sharing the
        // cache for all non-symlink paths.
        var result: Any? = statCache.get(
            com.google.devtools.build.lib.util.Pair.of<com.google.devtools.build.lib.vfs.Path?, Symlinks?>(
                path,
                Symlinks.NOFOLLOW
            )
        )
        if (result is IOException) {
            throw result
        }
        var status: FileStatus = result as FileStatus
        if (status !== NO_STATUS && symlinks == Symlinks.FOLLOW && status.isSymbolicLink()) {
            result = statCache.get(
                com.google.devtools.build.lib.util.Pair.of<com.google.devtools.build.lib.vfs.Path?, Symlinks?>(
                    path,
                    Symlinks.FOLLOW
                )
            )
            if (result is IOException) {
                throw result
            }
            status = result as FileStatus
        }
        return if (status === NO_STATUS) null else status
    }

    @Throws(IOException::class)
    override fun getType(path: com.google.devtools.build.lib.vfs.Path, symlinks: Symlinks?): DirentTypeWithSkip? {
        // Use a cached stat call if we have one. This is done first so that we don't need to iterate
        // over a list of directory entries as we do for cached readdir() entries. We don't ever expect
        // to get a cache hit if symlinks == Symlinks.NOFOLLOW and so we don't bother to check.
        if (symlinks == Symlinks.FOLLOW) {
            val key: com.google.devtools.build.lib.util.Pair<com.google.devtools.build.lib.vfs.Path?, Symlinks?> =
                com.google.devtools.build.lib.util.Pair.of<com.google.devtools.build.lib.vfs.Path?, Symlinks?>(
                    path,
                    symlinks
                )
            val result: Any? = statCache.getIfPresent(key)
            if (result != null && result !is IOException) {
                if (result === NO_STATUS) {
                    return null
                }
                return ofStat(result as FileStatus)
            }
        }

        // If this is a root directory, we must stat, there is no parent.
        val parent: com.google.devtools.build.lib.vfs.Path? = path.getParentDirectory()
        if (parent == null) {
            return ofStat(statIfFound(path, symlinks))
        }

        // Answer based on a cached readdir() call if possible. The cache might already be populated
        // from Skyframe directory lising (DirectoryListingFunction) or by globbing via
        // {@link UnixGlob}. We generally try to avoid following symlinks in readdir() calls as in a
        // directory with many symlinks, these would be resolved basically using a stat anyway and they
        // would be resolved sequentially which can be slow on high-latency file systems. If we request
        // the type of a file with FOLLOW, and find a symlink in the directory, we fall back to doing a
        // stat.
        if (readdirCache.getIfPresent(parent) is Dirents) {
            val baseName: String = path.getBaseName()
            val dirent: com.google.devtools.build.lib.vfs.Dirent? = dirents.maybeGetDirent(baseName)
            if (dirent != null) {
                if (dirent.getType() == com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK && symlinks == Symlinks.FOLLOW) {
                    // See above: We don't want to follow symlinks with readdir(). Do a stat() instead.
                    return ofStat(statIfFound(path, Symlinks.FOLLOW))
                }
                return DirentTypeWithSkip.of(dirent.getType())
            }
            if (!path.getFileSystem().mayBeCaseOrNormalizationInsensitive()) {
                return null
            }
            // The filesystem may be case-insensitive or normalization-insensitive, but it doesn't have
            // to be and even if it is, we don't know which normalization algorithm it uses. We assume
            // that every reasonable filesystem doesn't normalize pure ASCII path components in any
            // way other than ASCII case insensitivity.
            if (StringUnsafe.isAscii(baseName)) {
                var mayHaveFoundMatch = false
                for (d in dirents) {
                    if (!StringUnsafe.isAscii(d.getName()) || com.google.common.base.Ascii.equalsIgnoreCase(
                            baseName,
                            d.getName()
                        )
                    ) {
                        mayHaveFoundMatch = true
                        break
                    }
                }
                if (!mayHaveFoundMatch) {
                    return null
                }
            }
            // Fall back to stat() if we might have found a match.
        }

        return ofStat(statIfFound(path, symlinks))
    }

    override fun clear() {
        // Drop not just the memory of the FileStatus objects but the maps themselves.
        statCache = statCacheSupplier.get()
        readdirCache = readdirCacheSupplier.get()
        if (statCacheMetricExporter != null) {
            statCacheMetricExporter.setLatestInstance(statCache)
        }
        if (readdirCacheMetricExporter != null) {
            readdirCacheMetricExporter.setLatestInstance(readdirCache)
        }
    }

    // This is used because the cache implementations don't allow null.
    private class FakeFileStatus : FileStatus {
        val lastChangeTime: Long
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        val nodeId: Long
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        val lastModifiedTime: Long
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        val size: Long
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        val isDirectory: Boolean
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        val isFile: Boolean
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        val isSpecialFile: Boolean
            get() {
                throw java.lang.UnsupportedOperationException()
            }

        val isSymbolicLink: Boolean
            get() {
                throw java.lang.UnsupportedOperationException()
            }
    }

    companion object {
        private val NO_STATUS: FileStatus = FakeFileStatus()

        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return com.google.devtools.build.lib.skyframe.DefaultSyscallCache.Builder()
        }

        private fun ofStat(status: FileStatus?): DirentTypeWithSkip? {
            return DirentTypeWithSkip.of(SyscallCache.statusToDirentType(status))
        }

        /** Returns [FileStatus] or [IOException].  */
        private fun statImpl(p: com.google.devtools.build.lib.util.Pair<com.google.devtools.build.lib.vfs.Path?, Symlinks?>): Any {
            try {
                val stat: FileStatus? = p.first.statIfFound(p.second)
                return com.google.common.base.MoreObjects.firstNonNull<FileStatus>(stat, NO_STATUS)
            } catch (e: IOException) {
                return e
            }
        }

        /** Returns a collection of [Dirent] or [IOException].  */
        private fun readdirImpl(p: com.google.devtools.build.lib.vfs.Path): Any {
            try {
                return CompactSortedDirents.create(p.readdir(Symlinks.NOFOLLOW))
            } catch (e: IOException) {
                return e
            }
        }
    }
}
