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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.vfs.DigestHashFunction

/**
 * Tests for Fingerprint.
 */
@RunWith(JUnit4::class)
class FingerprintTest {
    @org.junit.Test
    fun equivalentBytesAndStringsFingerprintsMatch() {
        val helloWorld = "Hello World!"
        // $ echo -n 'Hello World!' | sha256sum
        val helloWorldHash = "7f83b1657ff1fc53b92dc18148a1d65dfc2d4b1fa3d677284addd200126d9069"

        assertThat(
            Fingerprint().addBytes(helloWorld.toByteArray(java.nio.charset.StandardCharsets.UTF_8)).hexDigestAndReset()
        )
            .isEqualTo(helloWorldHash)

        assertThat(Fingerprint.getHexDigest(helloWorld)).isEqualTo(helloWorldHash)

        assertThat(Fingerprint().addBytes(ByteString.copyFromUtf8(helloWorld)).hexDigestAndReset())
            .isEqualTo(helloWorldHash)
    }

    @org.junit.Test
    fun otherStringFingerprint() {
        assertFingerprintsDiffer(
            com.google.common.collect.ImmutableList.of<String?>("Hello World!"),
            com.google.common.collect.ImmutableList.of<String?>("Goodbye World.")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleUpdatesDiffer() {
        assertFingerprintsDiffer(
            com.google.common.collect.ImmutableList.of<String?>("Hello ", "World!"),
            com.google.common.collect.ImmutableList.of<String?>("Hello World!")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleUpdatesShiftedDiffer() {
        assertFingerprintsDiffer(
            com.google.common.collect.ImmutableList.of<String?>("Hello ", "World!"),
            com.google.common.collect.ImmutableList.of<String?>("Hello", " World!")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun listFingerprintNotSameAsIndividualElements() {
        val f1: Fingerprint = Fingerprint()
        f1.addString("Hello ")
        f1.addString("World!")
        val f2: Fingerprint = Fingerprint()
        f2.addStrings(com.google.common.collect.ImmutableList.of<E?>("Hello ", "World!"))
        assertThat(f1.hexDigestAndReset()).isNotEqualTo(f2.hexDigestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mapFingerprintNotSameAsIndividualElements() {
        val f1: Fingerprint = Fingerprint()
        val map: MutableMap<String?, String?> = HashMap<String?, String?>()
        map.put("Hello ", "World!")
        f1.addStringMap(map)
        val f2: Fingerprint = Fingerprint()
        f2.addStrings(com.google.common.collect.ImmutableList.of<E?>("Hello ", "World!"))
        assertThat(f1.hexDigestAndReset()).isNotEqualTo(f2.hexDigestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addBoolean() {
        val f1: String? = Fingerprint().addBoolean(true).hexDigestAndReset()
        val f2: String? = Fingerprint().addBoolean(false).hexDigestAndReset()
        val f3: String? = Fingerprint().addBoolean(true).hexDigestAndReset()

        Truth.assertThat(f1).isEqualTo(f3)
        Truth.assertThat(f1).isNotEqualTo(f2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addPath() {
        val pf: PathFragment? = PathFragment.create("/etc/pwd")
        assertThat(Fingerprint().addPath(pf).hexDigestAndReset())
            .isEqualTo("0b229115c2da46773ff38528420b922488dd564ddb3c0c861fb1c77ae8525f9b")
        val p: Path? = InMemoryFileSystem(
            com.google.devtools.build.lib.clock.BlazeClock.instance(),
            DigestHashFunction.SHA256
        ).getPath(pf)
        assertThat(Fingerprint().addPath(p).hexDigestAndReset())
            .isEqualTo("0b229115c2da46773ff38528420b922488dd564ddb3c0c861fb1c77ae8525f9b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addNullableBoolean() {
        val f1: String? = Fingerprint().addNullableBoolean(null).hexDigestAndReset()
        Truth.assertThat(f1).isEqualTo(Fingerprint().addNullableBoolean(null).hexDigestAndReset())
        Truth.assertThat(f1).isNotEqualTo(Fingerprint().addNullableBoolean(false).hexDigestAndReset())
        Truth.assertThat(f1).isNotEqualTo(Fingerprint().addNullableBoolean(true).hexDigestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addNullableInteger() {
        val f1: String? = Fingerprint().addNullableInt(null).hexDigestAndReset()
        Truth.assertThat(f1).isEqualTo(Fingerprint().addNullableInt(null).hexDigestAndReset())
        Truth.assertThat(f1).isNotEqualTo(Fingerprint().addNullableInt(0).hexDigestAndReset())
        Truth.assertThat(f1).isNotEqualTo(Fingerprint().addNullableInt(1).hexDigestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addNullableString() {
        val f1: String? = Fingerprint().addNullableString(null).hexDigestAndReset()
        Truth.assertThat(f1).isEqualTo(Fingerprint().addNullableString(null).hexDigestAndReset())
        Truth.assertThat(f1).isNotEqualTo(Fingerprint().addNullableString("").hexDigestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReusableAfterReset() {
        val fp: Fingerprint = Fingerprint()
        val f1 = convolutedFingerprintAndReset(fp)
        val f2 = convolutedFingerprintAndReset(fp)
        Truth.assertThat(f1).isEqualTo(f2)
    }

    companion object {
        private fun assertFingerprintsDiffer(list1: MutableList<String?>, list2: MutableList<String?>) {
            val f1: Fingerprint = Fingerprint()
            val f1Latin1: Fingerprint = Fingerprint()
            for (s in list1) {
                f1.addString(s)
                f1Latin1.addString(s)
            }
            val f2: Fingerprint = Fingerprint()
            val f2Latin1: Fingerprint = Fingerprint()
            for (s in list2) {
                f2.addString(s)
                f2Latin1.addString(s)
            }
            assertThat(f1.hexDigestAndReset()).isNotEqualTo(f2.hexDigestAndReset())
            assertThat(f1Latin1.hexDigestAndReset()).isNotEqualTo(f2Latin1.hexDigestAndReset())
        }

        private fun convolutedFingerprintAndReset(fingerprint: Fingerprint): String {
            return fingerprint
                .addBoolean(false)
                .addBytes(ByteArray(10))
                .addBytes(ByteArray(10), 0, 5)
                .addInt(20)
                .addLong(30)
                .addNullableBoolean(null)
                .addNullableInt(null)
                .addNullableString(null)
                .addPath(PathFragment.create("/foo/bar"))
                .addPaths(com.google.common.collect.ImmutableList.of<E?>(PathFragment.create("/foo/bar")))
                .addString("baz")
                .addUUID(UUID.fromString("12345678-1234-1234-1234-1234567890ab"))
                .hexDigestAndReset()
        }
    }
}
