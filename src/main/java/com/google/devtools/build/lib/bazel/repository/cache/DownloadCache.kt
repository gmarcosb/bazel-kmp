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
package com.google.devtools.build.lib.bazel.repository.cache

import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache
import com.google.devtools.build.lib.vfs.DigestHashFunction
import com.google.devtools.build.zip.ZipReader.getInputStream
import java.io.IOException
import java.util.UUID

/**
 * The cache implementation to store download artifacts from external repositories.
 * 
 * 
 * Operations performed by this class are atomic on the file system level under the assumption
 * that the cache directory is not subject to concurrent file deletion.
 */
class DownloadCache {
    /** The types of cache keys used.  */
    enum class KeyType(stringRepr: String, regexp: String, hashName: String) {
        SHA1("SHA-1", "\\p{XDigit}{40}", "sha1"),
        SHA256("SHA-256", "\\p{XDigit}{64}", "sha256"),
        SHA384("SHA-384", "\\p{XDigit}{96}", "sha384"),
        SHA512("SHA-512", "\\p{XDigit}{128}", "sha512"),
        BLAKE3("BLAKE3", "\\p{XDigit}{64}", "blake3");

        private val stringRepr: String?
        private val regexp: String?
        val hashName: String?

        init {
            this.stringRepr = stringRepr
            this.regexp = regexp
            this.hashName = hashName
        }

        fun isValid(checksum: String?): Boolean {
            return !com.google.common.base.Strings.isNullOrEmpty(checksum) && checksum.matches(regexp)
        }

        fun getCachePath(parentDirectory: com.google.devtools.build.lib.vfs.Path): com.google.devtools.build.lib.vfs.Path {
            return parentDirectory.getChild(hashName)
        }

        fun newHasher(): com.google.common.hash.Hasher {
            return this.hashFunction.newHasher()
        }

        val hashFunction: com.google.common.hash.HashFunction?
            get() =// stringRepr is the canonical name for the hash function. See {@link DigestHashFunction}.
                DigestHashFunction.getHashFunctionFromName(stringRepr)

        override fun toString(): String {
            return stringRepr!!
        }
    }

    private var path: com.google.devtools.build.lib.vfs.Path? = null
    private var useHardlinks = false

    fun setPath(path: com.google.devtools.build.lib.vfs.Path?) {
        this.path = path
    }

    fun setHardlink(useHardlinks: Boolean) {
        this.useHardlinks = useHardlinks
    }

    val isEnabled: Boolean
        /** Returns true iff the cache path is set.  */
        get() = path != null

    /**
     * Determine if a cache entry exist, given a cache key.
     * 
     * @param cacheKey The string key to cache the value by.
     * @param keyType The type of key used. See: KeyType
     * @return true if the cache entry exist, false otherwise.
     */
    fun exists(cacheKey: String?, keyType: KeyType): Boolean {
        com.google.common.base.Preconditions.checkState(this.isEnabled)
        return keyType.getCachePath(path).getChild(cacheKey).getChild(DEFAULT_CACHE_FILENAME).exists()
    }

    fun hasCanonicalId(cacheKey: String?, keyType: KeyType, canonicalId: String): Boolean {
        com.google.common.base.Preconditions.checkState(this.isEnabled)
        val idHash =
            keyType.newHasher().putString(canonicalId, java.nio.charset.StandardCharsets.UTF_8).hash().toString()
        return keyType.getCachePath(path).getChild(cacheKey).getChild(ID_PREFIX + idHash).exists()
    }

    /**
     * Copy or hardlink cached value to a specified directory, if it exists.
     * 
     * 
     * We're using hardlinking instead of symlinking because symlinking require weird checks to
     * verify that the symlink still points to an existing artifact. e.g. cleaning up the central
     * cache but not the workspace cache.
     * 
     * @param cacheKey The string key to cache the value by.
     * @param targetPath The path where the cache value should be copied to.
     * @param keyType The type of key used. See: KeyType
     * @param canonicalId If set to a non-empty string, restrict cache hits to those cases, where the
     * entry with the given cacheKey was added with this String given.
     * @return The Path value where the cache value has been copied to. If cache value does not exist,
     * return null.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun get(
        cacheKey: String,
        targetPath: com.google.devtools.build.lib.vfs.Path,
        keyType: KeyType,
        canonicalId: String?,
        mayHardlink: Boolean
    ): com.google.devtools.build.lib.vfs.Path? {
        val cacheValue: com.google.devtools.build.lib.vfs.Path? = findCacheValue(cacheKey, keyType, canonicalId)
        if (cacheValue == null) {
            return null
        }

        targetPath.getParentDirectory().createDirectoryAndParents()
        if (useHardlinks && mayHardlink) {
            com.google.devtools.build.lib.vfs.FileSystemUtils.createHardLink(targetPath, cacheValue)
        } else {
            com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(cacheValue, targetPath)
        }

        return targetPath
    }

    /**
     * Get the content of a cached value, if it exists.
     * 
     * @param cacheKey The string key to cache the value by.
     * @param keyType The type of key used. See: KeyType
     * @return The bytes of the cache value. If cache value does not exist, returns null.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun getBytes(cacheKey: String, keyType: KeyType): ByteArray? {
        val cacheValue: com.google.devtools.build.lib.vfs.Path? =
            findCacheValue(cacheKey, keyType,  /* canonicalId= */null)
        if (cacheValue == null) {
            return null
        }

        return com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(cacheValue)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun findCacheValue(
        cacheKey: String,
        keyType: KeyType,
        canonicalId: String?
    ): com.google.devtools.build.lib.vfs.Path? {
        com.google.common.base.Preconditions.checkState(this.isEnabled)

        assertKeyIsValid(cacheKey, keyType)
        if (!exists(cacheKey, keyType)) {
            return null
        }

        val cacheEntry: com.google.devtools.build.lib.vfs.Path = keyType.getCachePath(path).getRelative(cacheKey)
        val cacheValue: com.google.devtools.build.lib.vfs.Path = cacheEntry.getRelative(DEFAULT_CACHE_FILENAME)

        try {
            assertFileChecksum(cacheKey, cacheValue, keyType)
        } catch (e: IOException) {
            // New lines because this error message gets large printing multiple absolute filepaths.
            throw IOException(
                (e.getMessage()
                        + "\n\n"
                        + "Please delete the directory "
                        + cacheEntry
                        + " and try again.")
            )
        }

        if (!com.google.common.base.Strings.isNullOrEmpty(canonicalId)) {
            if (!hasCanonicalId(cacheKey, keyType, canonicalId!!)) {
                return null
            }
        }

        try {
            com.google.devtools.build.lib.vfs.FileSystemUtils.touchFile(cacheValue)
        } catch (e: IOException) {
            // Ignore, because the cache might be on a read-only volume.
        }

        return cacheValue
    }

    internal interface FileWriter {
        @Throws(IOException::class)
        fun writeTo(name: com.google.devtools.build.lib.vfs.Path?)
    }

    /**
     * Copies a value from a specified path into the cache.
     * 
     * @param cacheKey The string key to cache the value by.
     * @param fileWriter A function that writes the value to a given file.
     * @param keyType The type of key used. See: KeyType
     * @param canonicalId If set to a non-empty String associate the file with this name, allowing
     * restricted cache lookups later.
     */
    @Throws(IOException::class)
    private fun storeCacheValue(
        cacheKey: String?, fileWriter: FileWriter, keyType: KeyType, canonicalId: String?
    ) {
        com.google.common.base.Preconditions.checkState(this.isEnabled)

        assertKeyIsValid(cacheKey, keyType)
        ensureCacheDirectoryExists(keyType)

        val cacheEntry: com.google.devtools.build.lib.vfs.Path = keyType.getCachePath(path).getRelative(cacheKey)
        val cacheValue: com.google.devtools.build.lib.vfs.Path = cacheEntry.getRelative(DEFAULT_CACHE_FILENAME)
        val tmpName: com.google.devtools.build.lib.vfs.Path = cacheEntry.getRelative(TMP_PREFIX + UUID.randomUUID())
        cacheEntry.createDirectoryAndParents()
        fileWriter.writeTo(tmpName)
        com.google.devtools.build.lib.vfs.FileSystemUtils.renameToleratingConcurrentCreation(tmpName, cacheValue)

        if (!com.google.common.base.Strings.isNullOrEmpty(canonicalId)) {
            val idHash =
                keyType.newHasher().putBytes(canonicalId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hash()
                    .toString()
            com.google.devtools.build.lib.vfs.FileSystemUtils.touchFile(cacheEntry.getRelative(ID_PREFIX + idHash))
        }
    }

    /**
     * Copies a value from a specified path into the cache.
     * 
     * @param cacheKey The string key to cache the value by.
     * @param sourcePath The path of the value to be cached.
     * @param keyType The type of key used. See: KeyType
     * @param canonicalId If set to a non-empty String associate the file with this name, allowing
     * restricted cache lookups later.
     */
    @Throws(IOException::class)
    fun put(
        cacheKey: String?,
        sourcePath: com.google.devtools.build.lib.vfs.Path?,
        keyType: KeyType,
        canonicalId: String?
    ) {
        storeCacheValue(
            cacheKey,
            com.google.devtools.build.lib.bazel.repository.cache.DownloadCache.FileWriter { tmpName: com.google.devtools.build.lib.vfs.Path? ->
                com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(
                    sourcePath,
                    tmpName
                )
            },
            keyType,
            canonicalId
        )
    }

    /**
     * Adds an in-memory value to the cache.
     * 
     * @param content The byte content of the value to be cached.
     * @param keyType The type of key used. See: KeyType
     */
    @Throws(IOException::class)
    fun put(cacheKey: String?, content: ByteArray?, keyType: KeyType) {
        storeCacheValue(
            cacheKey,
            com.google.devtools.build.lib.bazel.repository.cache.DownloadCache.FileWriter { tmpName: com.google.devtools.build.lib.vfs.Path? ->
                com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(
                    tmpName,
                    content
                )
            },
            keyType,  /* canonicalId= */
            null
        )
    }

    /**
     * Adds an in-memory value to the cache.
     * 
     * @param content The byte content of the value to be cached.
     * @param keyType The type of key used. See: KeyType
     */
    @Throws(IOException::class)
    fun put(content: ByteArray, keyType: KeyType) {
        val cacheKey = keyType.newHasher().putBytes(content).hash().toString()
        put(cacheKey, content, keyType)
    }

    /**
     * Copies a value from a specified path into the cache, computing the cache key itself.
     * 
     * @param sourcePath The path of the value to be cached.
     * @param keyType The type of key to be used.
     * @param canonicalId If set to a non-empty String associate the file with this name, allowing
     * restricted cache lookups later.
     * @return The key for the cached entry.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun put(sourcePath: com.google.devtools.build.lib.vfs.Path, keyType: KeyType, canonicalId: String?): String {
        val cacheKey = getChecksum(keyType, sourcePath)
        put(cacheKey, sourcePath, keyType, canonicalId)
        return cacheKey
    }

    @Throws(IOException::class)
    private fun ensureCacheDirectoryExists(keyType: KeyType) {
        val directoryPath: com.google.devtools.build.lib.vfs.Path = keyType.getCachePath(path)
        if (!directoryPath.exists()) {
            directoryPath.createDirectoryAndParents()
        }
    }

    @Throws(IOException::class)
    private fun assertKeyIsValid(key: String?, keyType: KeyType) {
        if (!keyType.isValid(key)) {
            throw IOException("Invalid key \"" + key + "\" of type " + keyType + ". ")
        }
    }

    companion object {
        private val BUFFER_SIZE = 32 * 1024

        // Rename cached files to this value to simplify lookup.
        const val DEFAULT_CACHE_FILENAME: String = "file"
        const val TMP_PREFIX: String = "tmp-"
        const val ID_PREFIX: String = "id-"

        /**
         * Assert that a file has an expected checksum.
         * 
         * @param expectedChecksum The expected checksum of the file.
         * @param filePath The path to the file.
         * @param keyType The type of hash function. e.g. SHA-1, SHA-256
         * @throws IOException If the checksum does not match or the file cannot be hashed, an exception
         * is thrown.
         */
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun assertFileChecksum(
            expectedChecksum: String,
            filePath: com.google.devtools.build.lib.vfs.Path,
            keyType: KeyType
        ) {
            com.google.common.base.Preconditions.checkArgument(!expectedChecksum.isEmpty())

            val actualChecksum: String
            try {
                actualChecksum = getChecksum(keyType, filePath)
            } catch (e: IOException) {
                throw IOException(
                    ("Could not hash file "
                            + filePath
                            + ": "
                            + e.getMessage()
                            + ", expected "
                            + keyType
                            + " of "
                            + expectedChecksum
                            + ". ")
                )
            }
            if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
                throw IOException(
                    ("Downloaded file at "
                            + filePath
                            + " has "
                            + keyType
                            + " of "
                            + actualChecksum
                            + ", does not match expected "
                            + keyType
                            + " ("
                            + expectedChecksum
                            + ")")
                )
            }
        }

        /**
         * Obtain the checksum of a file.
         * 
         * @param keyType The type of hash function. e.g. SHA-1, SHA-256.
         * @param path The path to the file.
         */
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun getChecksum(keyType: KeyType, path: com.google.devtools.build.lib.vfs.Path): String {
            // Attempt to use the fast digest if the hash function of the filesystem
            // matches `keyType` and it's available.
            if (path.getFileSystem()
                    .getDigestFunction()
                    .getHashFunction()
                == keyType.hashFunction
            ) {
                val digest: ByteArray? = path.getFastDigest()
                if (digest != null) {
                    return com.google.common.io.BaseEncoding.base16().lowerCase().encode(digest)
                }
            }

            val hasher: com.google.common.hash.Hasher = keyType.newHasher()
            val byteBuffer = ByteArray(BUFFER_SIZE)
            path.getInputStream().use { stream ->
                var numBytesRead: Int = stream.read(byteBuffer)
                while (numBytesRead != -1) {
                    if (numBytesRead != 0) {
                        // If more than 0 bytes were read, add them to the hash.
                        hasher.putBytes(byteBuffer, 0, numBytesRead)
                    }
                    if (java.lang.Thread.interrupted()) {
                        throw java.lang.InterruptedException()
                    }
                    numBytesRead = stream.read(byteBuffer)
                }
            }
            return hasher.hash().toString()
        }
    }
}
