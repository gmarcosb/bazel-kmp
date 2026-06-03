// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.collect.nestedset.DigestDeduper.DigestReference

@RunWith(JUnit4::class)
class DigestDeduperTest {
    @org.junit.Test
    fun testAdd_newDigests() {
        val deduper: DigestDeduper = DigestDeduper(10, 32)
        assertThat(deduper.add(createDigest("digest1"))).isTrue()
        assertThat(deduper.add(createDigest("digest2"))).isTrue()
        assertThat(deduper.add(createDigest("digest3"))).isTrue()
    }

    @org.junit.Test
    fun testAdd_duplicateDigests() {
        val deduper: DigestDeduper = DigestDeduper(10, 32)
        assertThat(deduper.add(createDigest("digest1"))).isTrue()
        assertThat(deduper.add(createDigest("digest1"))).isFalse()
    }

    @org.junit.Test
    fun testAdd_collision() {
        val deduper: DigestDeduper = DigestDeduper(2, 4)

        val bytes1: ByteArray = "aaaa".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val bytes2: ByteArray = "bbba".toByteArray(java.nio.charset.StandardCharsets.UTF_8)

        val digest1: DigestReference = DigestReference()
        digest1.acceptBytes(bytes1, 0, bytes1.size)

        val digest2: DigestReference = DigestReference()
        digest2.acceptBytes(bytes2, 0, bytes2.size)

        assertThat(deduper.add(digest1)).isTrue()
        assertThat(deduper.add(digest2)).isTrue()
    }

    @org.junit.Test
    fun testAdd_nearCapacity() {
        val maxSize = 10
        val deduper: DigestDeduper = DigestDeduper(maxSize, 32)

        for (i in 0..<maxSize) {
            assertThat(deduper.add(createDigest("digest" + i))).isTrue()
        }

        // Adding a duplicate should fail.
        assertThat(deduper.add(createDigest("digest5"))).isFalse()

        // Adding one more new item should still succeed.
        assertThat(deduper.add(createDigest("digest" + maxSize))).isTrue()
    }

    @org.junit.Test
    fun testAdd_nearCapacity_withProbing() {
        val maxSize = 10
        val deduper: DigestDeduper = DigestDeduper(maxSize, 32)

        for (i in 0..<maxSize) {
            // These strings are chosen to have the same last 4 bytes, forcing hash collisions
            // and stressing the linear probing mechanism.
            assertThat(deduper.add(createDigest(i.toString() + "----------colliding_string"))).isTrue()
        }

        // Adding a duplicate should fail.
        assertThat(deduper.add(createDigest("5----------colliding_string"))).isFalse()

        // Adding one more new item should still succeed.
        assertThat(deduper.add(createDigest(maxSize.toString() + "----------colliding_string"))).isTrue()
    }

    @org.junit.Test
    fun testSizeBitsFor_throwsExceptionForInvalidMaxSize() {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { DigestDeduper.sizeBitsFor(0) })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { DigestDeduper.sizeBitsFor(-1) })
    }

    @org.junit.Test
    fun testSizeBitsFor_calculatesCorrectSize() {
        // A deduper with maxSize will have a capacity that is the smallest power of 2
        // greater than or equal to ceil(maxSize / 0.75).

        // maxSize = 10, minCapacity = ceil(10 / 0.75) = 14, size = 16 (4 bits)

        assertThat(DigestDeduper.sizeBitsFor(10)).isEqualTo(4)

        // maxSize = 12, minCapacity = ceil(12 / 0.75) = 16, size = 16 (4 bits)
        assertThat(DigestDeduper.sizeBitsFor(12)).isEqualTo(4)

        // maxSize = 13, minCapacity = ceil(13 / 0.75) = 18, size = 32 (5 bits)
        assertThat(DigestDeduper.sizeBitsFor(13)).isEqualTo(5)
    }

    companion object {
        private fun createDigest(content: String): DigestReference {
            val digest: DigestReference = DigestReference()
            val bytes: ByteArray = content.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            digest.acceptBytes(bytes, 0, bytes.size)
            return digest
        }
    }
}
