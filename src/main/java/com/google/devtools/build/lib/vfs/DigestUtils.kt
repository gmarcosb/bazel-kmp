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
package com.google.devtools.build.lib.vfs

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.vfs.FileStatus
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.lib.vfs.XattrProvider
import java.io.IOException

/**
 * Utility class for getting digests of files.
 * 
 * 
 * This class implements an optional cache of file digests when the computation of the digests is
 * costly (i.e. when [Path.getFastDigest] is not available). The cache can be enabled via
 * the [.configureCache] function, but note that enabling this cache might have an
 * impact on correctness because not all changes to files can be purely detected from their
 * metadata.
 */
object DigestUtils {
    // Typical size for a digest byte array.
    const val ESTIMATED_SIZE: Int = 32

    /**
     * Global cache of files to their digests.
     * 
     * 
     * This is null when the cache is disabled.
     * 
     * 
     * Note that we do not use a [com.github.benmanes.caffeine.cache.LoadingCache] because
     * our keys represent the paths as strings, not as [Path] instances. As a result, the
     * loading function cannot actually compute the digests of the files so we have to handle this
     * externally.
     */
    private var globalCache: com.github.benmanes.caffeine.cache.Cache<CacheKey?, ByteArray?>? = null

    /**
     * Enables the caching of file digests based on file status data.
     * 
     * 
     * If the cache was already enabled, this causes the cache to be reinitialized thus losing all
     * contents. If the given size is zero, the cache is disabled altogether.
     * 
     * @param maximumSize maximumSize of the cache in number of entries
     */
    @kotlin.jvm.JvmStatic
    fun configureCache(maximumSize: Long) {
        if (maximumSize == 0L) {
            com.google.devtools.build.lib.vfs.DigestUtils.globalCache = null
        } else {
            com.google.devtools.build.lib.vfs.DigestUtils.globalCache =
                Caffeine.newBuilder().maximumSize(maximumSize).recordStats().build<CacheKey?, ByteArray?>()
        }
    }

    /**
     * Clears the cache contents without changing its size. No-op if the cache hasn't yet been
     * initialized.
     */
    @kotlin.jvm.JvmStatic
    fun clearCache() {
        if (com.google.devtools.build.lib.vfs.DigestUtils.globalCache != null) {
            com.google.devtools.build.lib.vfs.DigestUtils.globalCache.invalidateAll()
        }
    }

    @kotlin.jvm.JvmStatic
    val cacheStats: com.github.benmanes.caffeine.cache.stats.CacheStats?
        /**
         * Obtains cache statistics.
         * 
         * 
         * The cache must have previously been enabled by a call to [.configureCache].
         * 
         * @return an immutable snapshot of the cache statistics
         */
        get() {
            val cache: com.github.benmanes.caffeine.cache.Cache<CacheKey?, ByteArray?>? =
                com.google.devtools.build.lib.vfs.DigestUtils.globalCache
            com.google.common.base.Preconditions.checkNotNull<com.github.benmanes.caffeine.cache.Cache<CacheKey?, ByteArray?>?>(
                cache,
                "configureCache() must have been called with a size >= 0"
            )
            return cache.stats()
        }

    /**
     * Gets the digest of `path`, using a constant-time xattr call if the filesystem supports
     * it, and calculating the digest manually otherwise.
     * 
     * 
     * If [Path.getFastDigest] has already been attempted and was not available, call [ ][.manuallyComputeDigest] to skip an additional attempt to obtain the fast digest.
     * 
     * 
     * Prefer calling [.manuallyComputeDigest] when a recently obtained
     * [FileStatus] is available.
     * 
     * @param path the file path
     */
    @Throws(IOException::class)
    fun getDigestWithManualFallback(
        path: com.google.devtools.build.lib.vfs.Path,
        xattrProvider: XattrProvider
    ): ByteArray? {
        return com.google.devtools.build.lib.vfs.DigestUtils.getDigestWithManualFallback(path, xattrProvider, null)
    }

    /**
     * Same as [.getDigestWithManualFallback], but providing the ability to
     * reuse a recently obtained [FileStatus].
     * 
     * @param path the file path
     * @param status a recently obtained file status, if available
     */
    @Throws(IOException::class)
    fun getDigestWithManualFallback(
        path: com.google.devtools.build.lib.vfs.Path, xattrProvider: XattrProvider, status: FileStatus?
    ): ByteArray? {
        val digest: ByteArray? = xattrProvider.getFastDigest(path)
        return if (digest != null) digest else com.google.devtools.build.lib.vfs.DigestUtils.manuallyComputeDigest(
            path,
            status
        )
    }

    /**
     * Same as [.manuallyComputeDigest], but providing the ability to reuse a recently
     * obtained [FileStatus].
     * 
     * @param path the file path
     * @param status a recently obtained file status, if available
     */
    /**
     * Calculates a digest manually (i.e., assuming that a fast digest can't obtained).
     * 
     * 
     * Prefer calling [.manuallyComputeDigest] when a recently obtained
     * [FileStatus] is available.
     * 
     * @param path the file path
     */
    @kotlin.jvm.JvmOverloads
    @Throws(IOException::class)
    fun manuallyComputeDigest(path: com.google.devtools.build.lib.vfs.Path, status: FileStatus? = null): ByteArray? {
        var digest: ByteArray?

        // Attempt a cache lookup if the cache is enabled.
        val cache: com.github.benmanes.caffeine.cache.Cache<CacheKey?, ByteArray?>? =
            com.google.devtools.build.lib.vfs.DigestUtils.globalCache
        var key: CacheKey? = null
        if (cache != null) {
            key = com.google.devtools.build.lib.vfs.DigestUtils.CacheKey(
                path,
                if (status != null) status else path.stat()
            )
            digest = cache.getIfPresent(key)
            if (digest != null) {
                return digest
            }
        }

        digest = path.getDigest()

        com.google.common.base.Preconditions.checkNotNull<ByteArray?>(digest, "Missing digest for %s", path)
        if (cache != null) {
            cache.put(key, digest)
        }
        return digest
    }

    /**
     * Combines two digests into one such that swapping the arguments results in the same result. May
     * clobber either argument.
     */
    @kotlin.jvm.JvmStatic
    fun combineUnordered(lhs: ByteArray, rhs: ByteArray): ByteArray {
        val n = rhs.size
        if (lhs.size >= n) {
            for (i in 0..<n) {
                // Use + as in Guava's Hashing.combineUnordered.
                // This has a number of advantages over XOR, which was used in the past:
                // * Identical inputs will not cancel each other out.
                // * Due to the carry, addition isn't a linear operation on the level of bit vectors.
                //   This prevents adversaries from producing linear combinations (i.e., subsets of input
                //   sets) that collide with other inputs.
                lhs[i] = (lhs[i] + rhs[i]).toByte()
            }
            return lhs
        }
        return com.google.devtools.build.lib.vfs.DigestUtils.combineUnordered(rhs, lhs)
    }

    /**
     * Keys used to cache the values of the digests for files where we don't have fast digests.
     * 
     * 
     * The cache keys are derived from many properties of the file metadata in an attempt to be
     * able to detect most file changes.
     */
    private class CacheKey(path: PathFragment?, nodeId: Long, changeTime: Long, lastModifiedTime: Long, size: Long) {
        /**
         * Constructs a new cache key.
         * 
         * @param path path to the file
         * @param status file status data from which to obtain the cache key properties
         * @throws IOException if reading the file status data fails
         */
        private constructor(path: com.google.devtools.build.lib.vfs.Path, status: FileStatus) : this(
            path.asFragment(),
            status.getNodeId(),
            status.getLastChangeTime(),
            status.getLastModifiedTime(),
            status.getSize()
        )

        val path: PathFragment?
        val nodeId: Long
        val changeTime: Long
        val lastModifiedTime: Long
        val size: Long

        init {
            this.path = path
            this.nodeId = nodeId
            this.changeTime = changeTime
            this.lastModifiedTime = lastModifiedTime
            this.size = size
        }
    }
}
