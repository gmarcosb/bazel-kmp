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

import com.google.common.io.BaseEncoding
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache.KeyType
import com.google.devtools.build.lib.clock.JavaClock
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.runners.Parameterized
import java.nio.charset.Charset
import java.util.concurrent.Future
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/** Tests for [DownloadCache].  */
@RunWith(Parameterized::class)
class DownloadCacheTest(digestHashFunction: DigestHashFunction, keyType: KeyType, hash: String?) {
    @Rule
    var thrown: ExpectedException = ExpectedException.none()

    private var scratch: Scratch? = null
    private var downloadCache: DownloadCache? = null
    private var repositoryCachePath: Path? = null
    private var downloadedFile: Path? = null

    private val digestHashFunction: DigestHashFunction
    private val keyType: KeyType
    private val hash: String?

    init {
        this.digestHashFunction = digestHashFunction
        this.keyType = keyType
        this.hash = hash
    }

    @Before
    @Throws(Exception::class)
    fun setUp() {
        scratch = Scratch("/")
        repositoryCachePath = scratch.dir("/repository_cache")
        downloadCache = DownloadCache()
        downloadCache.setPath(repositoryCachePath)

        downloadedFile = scratch.file("file.tmp", Charset.defaultCharset(), "contents")
    }

    @After
    @Throws(IOException::class)
    fun tearDown() {
        repositoryCachePath.deleteTree()
    }

    @Test
    fun testNonExistentCacheValue() {
        val fakeHash: String = "a".repeat(64)
        assertThat(downloadCache.exists(fakeHash, keyType)).isFalse()
    }

    /** Test that the put method correctly stores the downloaded file into the cache.  */
    @Test
    @Throws(Exception::class)
    fun testPutCacheValue() {
        downloadCache.put(hash, downloadedFile, keyType,  /* canonicalId= */null)

        val cacheEntry: Path = keyType.getCachePath(repositoryCachePath).getChild(hash)
        val cacheValue: Path? = cacheEntry.getChild(DownloadCache.DEFAULT_CACHE_FILENAME)

        assertThat(FileSystemUtils.readContent(downloadedFile, Charset.defaultCharset()))
            .isEqualTo(FileSystemUtils.readContent(cacheValue, Charset.defaultCharset()))
    }

    /**
     * Test that the put method without cache key correctly stores the downloaded file into the cache.
     */
    @Test
    @Throws(Exception::class)
    fun testPutCacheValueWithoutHash() {
        val cacheKey: String? = downloadCache.put(downloadedFile, keyType,  /* canonicalId= */null)
        Truth.assertThat(cacheKey).isEqualTo(hash)

        val cacheEntry: Path = keyType.getCachePath(repositoryCachePath).getChild(hash)
        val cacheValue: Path? = cacheEntry.getChild(DownloadCache.DEFAULT_CACHE_FILENAME)

        assertThat(FileSystemUtils.readContent(downloadedFile, Charset.defaultCharset()))
            .isEqualTo(FileSystemUtils.readContent(cacheValue, Charset.defaultCharset()))
    }

    /**
     * Test that the put method is idempotent, i.e. two successive put calls should not affect the
     * final state in the cache.
     */
    @Test
    @Throws(Exception::class)
    fun testPutCacheValueIdempotent() {
        downloadCache.put(hash, downloadedFile, keyType,  /* canonicalId= */null)
        downloadCache.put(hash, downloadedFile, keyType,  /* canonicalId= */null)

        val cacheEntry: Path = keyType.getCachePath(repositoryCachePath).getChild(hash)
        val cacheValue: Path? = cacheEntry.getChild(DownloadCache.DEFAULT_CACHE_FILENAME)

        assertThat(FileSystemUtils.readContent(downloadedFile, Charset.defaultCharset()))
            .isEqualTo(FileSystemUtils.readContent(cacheValue, Charset.defaultCharset()))
    }

    /** Test that the put method is safe to call concurrently.  */
    @Test
    @Throws(Exception::class)
    fun testPutCacheValueConcurrent() {
        val exceptions: ConcurrentLinkedQueue<Throwable> = ConcurrentLinkedQueue<Throwable>()
        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            for (i in 0..99) {
                val unused: Future<*>? =
                    executor.submit(
                        Runnable {
                            try {
                                downloadCache.put(hash, downloadedFile, keyType,  /* canonicalId= */null)
                            } catch (t: Throwable) {
                                exceptions.add(t)
                            }
                        })
            }
        }
        if (!exceptions.isEmpty()) {
            val combined = AssertionError("Exceptions occurred during concurrent puts")
            for (t in exceptions) {
                combined.addSuppressed(t)
            }
            throw combined
        }

        val cacheEntry: Path = keyType.getCachePath(repositoryCachePath).getChild(hash)
        val cacheValue: Path? = cacheEntry.getChild(DownloadCache.DEFAULT_CACHE_FILENAME)

        assertThat(FileSystemUtils.readContent(downloadedFile, Charset.defaultCharset()))
            .isEqualTo(FileSystemUtils.readContent(cacheValue, Charset.defaultCharset()))
    }

    /** Test that the get method correctly retrieves the cached file from the cache.  */
    @Test
    @Throws(Exception::class)
    fun testGetCacheValue() {
        // Inject file into cache
        downloadCache.put(hash, downloadedFile, keyType,  /* canonicalId= */null)

        val targetDirectory: Path = scratch.dir("/external")
        val targetPath: Path? = targetDirectory.getChild(downloadedFile.getBaseName())
        val actualTargetPath: Path? =
            downloadCache.get(
                hash, targetPath, keyType,  /* canonicalId= */null,  /* mayHardlink= */true
            )

        // Check that the contents are the same.
        assertThat(FileSystemUtils.readContent(downloadedFile, Charset.defaultCharset()))
            .isEqualTo(FileSystemUtils.readContent(actualTargetPath, Charset.defaultCharset()))

        // Check that the returned value is stored under outputBaseExternal.
        Truth.assertThat(actualTargetPath as Any?).isEqualTo(targetPath)
    }

    /** Test that the get method retrieves a null if the value is not cached.  */
    @Test
    @Throws(Exception::class)
    fun testGetNullCacheValue() {
        val targetDirectory: Path = scratch.dir("/external")
        val targetPath: Path? = targetDirectory.getChild(downloadedFile.getBaseName())
        val actualTargetPath: Path? =
            downloadCache.get(
                hash, targetPath, keyType,  /* canonicalId= */null,  /* mayHardlink= */true
            )

        assertThat(actualTargetPath).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testInvalidSha256Throws() {
        val invalidSha = "foo"
        thrown.expect(IOException::class.java)
        thrown.expectMessage("Invalid key \"foo\" of type " + keyType)
        downloadCache.put(invalidSha, downloadedFile, keyType,  /* canonicalId= */null)
    }

    @Test
    @Throws(Exception::class)
    fun testPoisonedCache() {
        val poisonedEntry: Path = keyType.getCachePath(repositoryCachePath).getChild(hash)
        val poisonedValue: Path = poisonedEntry.getChild(DownloadCache.DEFAULT_CACHE_FILENAME)
        scratch.file(poisonedValue.getPathString(), Charset.defaultCharset(), "poisoned")

        val targetDirectory: Path = scratch.dir("/external")
        val targetPath: Path? = targetDirectory.getChild(downloadedFile.getBaseName())

        thrown.expect(IOException::class.java)
        thrown.expectMessage("does not match expected")
        thrown.expectMessage("Please delete the directory")

        downloadCache.get(hash, targetPath, keyType,  /* canonicalId= */null,  /* mayHardlink= */true)
    }

    @Test
    @Throws(Exception::class)
    fun testGetChecksum() {
        val actualChecksum: String? = DownloadCache.getChecksum(keyType, downloadedFile)
        Truth.assertThat(actualChecksum).isEqualTo(hash)
    }

    @Test
    @Throws(Exception::class)
    fun testGetChecksumWithFastDigest() {
        val fastDigestChecksum = "cfe5ed57e6e323555b379c660aa8d35b70c2f8f07cf03ad6747266495ac13be0"
        val fs: InMemoryFileSystem = InMemoryFileSystem(JavaClock(), digestHashFunction)
        downloadedFile = spy(downloadedFile)
        Mockito.doReturn(BaseEncoding.base16().lowerCase().decode(fastDigestChecksum))
            .`when`<Any?>(downloadedFile)
            .getFastDigest()
        Mockito.doReturn(fs).`when`<Any?>(downloadedFile).getFileSystem()

        val actualChecksum: String? = DownloadCache.getChecksum(keyType, downloadedFile)
        Truth.assertThat(actualChecksum).isEqualTo(fastDigestChecksum)
    }

    @Test
    @Throws(Exception::class)
    fun testAssertFileChecksumPass() {
        DownloadCache.assertFileChecksum(hash, downloadedFile, keyType)
    }

    @Test
    @Throws(Exception::class)
    fun testAssertFileChecksumFail() {
        thrown.expect(IOException::class.java)
        thrown.expectMessage("does not match expected")
        DownloadCache.assertFileChecksum(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            downloadedFile,
            keyType
        )
    }

    @Test
    @Throws(Exception::class)
    fun testCanonicalId() {
        downloadCache.put(hash, downloadedFile, keyType, "fooid")
        val targetDirectory: Path = scratch.dir("/external")
        val targetPath: Path? = targetDirectory.getChild(downloadedFile.getBaseName())

        val lookupWithSameId: Path? =
            downloadCache.get(hash, targetPath, keyType, "fooid",  /* mayHardlink= */true)
        assertThat(lookupWithSameId).isEqualTo(targetPath)

        val lookupOtherId: Path? =
            downloadCache.get(hash, targetPath, keyType, "barid",  /* mayHardlink= */true)
        assertThat(lookupOtherId).isNull()

        val lookupNoId: Path? =
            downloadCache.get(
                hash, targetPath, keyType,  /* canonicalId= */null,  /* mayHardlink= */true
            )
        assertThat(lookupNoId).isEqualTo(targetPath)
    }

    companion object {
        @Parameterized.Parameters
        fun getKeyType(): MutableList<Array<Any?>?> {
            val keyTypes: MutableList<Array<Any?>?> = ArrayList<Array<Any?>?>()
            keyTypes.add(
                arrayOf<Any?>(
                    // digestHashFunction
                    DigestHashFunction.SHA256,  // keyType
                    KeyType.SHA256,  // hash
                    "bfe5ed57e6e323555b379c660aa8d35b70c2f8f07cf03ad6747266495ac13be0",  // echo 'contents' | sha256sum
                )
            )
            if (TestConstants.BLAKE3_AVAILABLE) {
                keyTypes.add(
                    arrayOf<Any?>(
                        // digestHashFunction
                        BazelHashFunctions.BLAKE3,  // keyType
                        KeyType.BLAKE3,  // hash
                        "54e00265e2516f168096da17059a6109563d9ba64a0b77cdc4b33e44600c2a39",  // echo 'contents' | b3sum
                    )
                )
            }
            return keyTypes
        }
    }
}
