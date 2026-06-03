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
package com.google.devtools.build.lib.vfs

import com.google.common.truth.Truth
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Tests for [DigestUtils].  */
@RunWith(JUnit4::class)
class DigestUtilsTest {
    @org.junit.After
    fun tearDown() {
        DigestUtils.configureCache( /*maximumSize=*/0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCache() {
        val getFastDigestCounter: AtomicInteger = AtomicInteger(0)
        val getDigestCounter: AtomicInteger = AtomicInteger(0)

        val tracingFileSystem: FileSystem =
            object : InMemoryFileSystem(DigestHashFunction.SHA256) {
                public override fun getFastDigest(path: PathFragment?): ByteArray? {
                    getFastDigestCounter.incrementAndGet()
                    return null
                }

                @Throws(IOException::class)
                public override fun getDigest(path: PathFragment?): ByteArray {
                    getDigestCounter.incrementAndGet()
                    return super.getDigest(path)
                }
            }

        DigestUtils.configureCache( /*maximumSize=*/100)

        val file: Path? = tracingFileSystem.getPath("/file.txt")
        FileSystemUtils.writeContentAsLatin1(file, "some contents")

        val digest: ByteArray? = DigestUtils.getDigestWithManualFallback(file, SyscallCache.NO_CACHE)
        Truth.assertThat(getFastDigestCounter.get()).isEqualTo(1)
        Truth.assertThat(getDigestCounter.get()).isEqualTo(1)

        assertThat(DigestUtils.getDigestWithManualFallback(file, SyscallCache.NO_CACHE))
            .isEqualTo(digest)
        Truth.assertThat(getFastDigestCounter.get()).isEqualTo(2)
        Truth.assertThat(getDigestCounter.get()).isEqualTo(1) // Cached.

        DigestUtils.clearCache()

        assertThat(DigestUtils.getDigestWithManualFallback(file, SyscallCache.NO_CACHE))
            .isEqualTo(digest)
        Truth.assertThat(getFastDigestCounter.get()).isEqualTo(3)
        Truth.assertThat(getDigestCounter.get()).isEqualTo(2) // Not cached.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun manuallyComputeDigest() {
        val digest = byteArrayOf(1, 2, 3)
        val noDigestFileSystem: FileSystem =
            object : InMemoryFileSystem(DigestHashFunction.SHA256) {
                public override fun getFastDigest(path: PathFragment?): ByteArray? {
                    throw java.lang.AssertionError("Unexpected call to getFastDigest")
                }

                public override fun getDigest(path: PathFragment?): ByteArray {
                    return digest
                }
            }
        val file: Path? = noDigestFileSystem.getPath("/f.txt")
        FileSystemUtils.writeContentAsLatin1(file, "contents")

        assertThat(DigestUtils.manuallyComputeDigest(file)).isEqualTo(digest)
    }

    @org.junit.Test
    fun combineUnordered_commutative() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        assertThat(DigestUtils.combineUnordered(a.clone(), b.clone()))
            .isEqualTo(DigestUtils.combineUnordered(b.clone(), a.clone()))
    }

    @org.junit.Test
    fun combineUnordered_noCancellation() {
        val a = byteArrayOf(1, 2, 3)
        assertThat(DigestUtils.combineUnordered(a.clone(), a.clone()))
            .isNotEqualTo(byteArrayOf(0, 0, 0))
    }
}
