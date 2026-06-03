// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.util.Fingerprint

/** Tests for [DigestMap].  */
@RunWith(org.junit.runners.Parameterized::class)
class DigestMapTest {
    @org.junit.runners.Parameterized.Parameter
    var digestHashFunction: DigestHashFunction? = null

    private fun fingerprint(): Fingerprint {
        return Fingerprint(digestHashFunction)
    }

    @org.junit.Test
    fun simpleTest() {
        val count = 128 // Must be smaller than byte for this test or we'll overflow
        val keys = arrayOfNulls<Any>(count)
        for (i in 0..<count) {
            keys[i] = Any()
        }

        val digestMap: DigestMap = DigestMap(digestHashFunction, 4)
        for (i in 0..<count) {
            val digest: Fingerprint? = fingerprint().addInt(i)
            val fingerprint: Fingerprint = fingerprint()
            digestMap.insertAndReadDigest(keys[i], digest, fingerprint)
            val reference: Fingerprint = fingerprint().addBytes(fingerprint().addInt(i).digestAndReset())
            assertThat(fingerprint.hexDigestAndReset()).isEqualTo(reference.hexDigestAndReset())
        }
        for (i in 0..<count) {
            val fingerprint: Fingerprint = fingerprint()
            assertThat(digestMap.readDigest(keys[i], fingerprint)).isTrue()
            val reference: Fingerprint = fingerprint().addBytes(fingerprint().addInt(i).digestAndReset())
            assertThat(fingerprint.hexDigestAndReset()).isEqualTo(reference.hexDigestAndReset())
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concurrencyTest() {
        val count = 128 // Must be smaller than byte for this test or we'll overflow
        val keys = arrayOfNulls<Any>(count)
        for (i in 0..<count) {
            keys[i] = Any()
        }
        val digestMap: DigestMap = DigestMap(digestHashFunction, 4)

        val done: AtomicBoolean = AtomicBoolean()
        val exception: AtomicReference<java.lang.Exception?> = AtomicReference<java.lang.Exception?>()
        val threads: MutableList<java.lang.Thread?> = java.util.ArrayList<java.lang.Thread?>()
        val threadCount = 16
        for (i in 0..<threadCount) {
            val thread: java.lang.Thread =
                java.lang.Thread(
                    java.lang.Runnable {
                        val random: Random = Random()
                        while (!done.get()) {
                            val index: Int = random.nextInt(count)
                            val key = keys[index]
                            val fingerprint: Fingerprint = fingerprint()
                            if (!digestMap.readDigest(key, fingerprint)) {
                                val digest: Fingerprint? = fingerprint().addInt(index)
                                digestMap.insertAndReadDigest(key, digest, fingerprint)
                            }
                            val reference: Fingerprint =
                                fingerprint().addBytes(fingerprint().addInt(index).digestAndReset())
                            val hexDigest: String = fingerprint.hexDigestAndReset()
                            val referenceDigest: String? = reference.hexDigestAndReset()
                            if (hexDigest != referenceDigest) {
                                exception.set(
                                    java.lang.IllegalStateException(
                                        String.format(
                                            "Digests are not equal: %s != %s, index %d",
                                            hexDigest, referenceDigest, index
                                        )
                                    )
                                )
                                done.set(true)
                            }
                        }
                    })
            thread.start()
            threads.add(thread)
        }
        java.lang.Thread.sleep(1000)
        done.set(true)
        for (i in 0..<threadCount) {
            threads.get(i).join(1000)
        }
        if (exception.get() != null) {
            throw exception.get()
        }
    }

    companion object {
        @org.junit.runners.Parameterized.Parameters(name = "Hash: {0}")
        fun hashFunction(): Iterable<Array<Any?>?> {
            return com.google.common.collect.ImmutableList.of<Array<Any?>?>(
                arrayOf<Any?>(DigestHashFunction.SHA1), arrayOf<Any?>(DigestHashFunction.SHA256)
            )
        }
    }
}
