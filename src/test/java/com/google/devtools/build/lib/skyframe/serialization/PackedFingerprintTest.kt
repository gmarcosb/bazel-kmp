// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

@RunWith(JUnit4::class)
class PackedFingerprintTest {
    private val rng: Random = Random()

    @org.junit.Test
    fun plainConversion_preservesBytes() {
        for (i in 0..10000 - 1) {
            val bytes = randomFingerprintBytes()

            val fingerprint: PackedFingerprint = PackedFingerprint.fromBytes(bytes)
            assertThat(fingerprint.toBytes()).isEqualTo(bytes)
        }
    }

    @org.junit.Test
    fun concat_appendsBytes() {
        val fingerprint: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            PackedFingerprint.fromBytes(parseHex("deadbeef" + "facefeed" + "8badf00d" + "f005ba11"))
        assertThat(fingerprint.concat(parseHex("0ff1ce")))
            .isEqualTo(parseHex("deadbeeffacefeed8badf00df005ba110ff1ce"))
    }

    @org.junit.Test
    fun copyTo_honorsOffset() {
        val fingerprint: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            PackedFingerprint.fromBytes(randomFingerprintBytes())

        val target = ByteArray(4 + PackedFingerprint.BYTES)
        fingerprint.copyTo(target, 4)

        for (i in 0..3) {
            Truth.assertThat<Byte?>(target[i]).isEqualTo(0)
        }

        val fingerprintBytes: ByteArray = fingerprint.toBytes()
        for (i in 0..<PackedFingerprint.BYTES) {
            Truth.assertThat<Byte?>(target[i + 4]).isEqualTo(fingerprintBytes[i])
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun codec_roundTrips() {
        val subjects: com.google.common.collect.ImmutableList.Builder<PackedFingerprint?> =
            com.google.common.collect.ImmutableList.builder<PackedFingerprint?>()
        for (i in 0..9) {
            val bytes = randomFingerprintBytes()

            subjects.add(PackedFingerprint.fromBytes(bytes))
        }
        SerializationTester(subjects.build()).runTests()
    }

    private fun randomFingerprintBytes(): ByteArray {
        val bytes = ByteArray(PackedFingerprint.BYTES)
        rng.nextBytes(bytes)
        return bytes
    }

    companion object {
        private fun parseHex(hex: String): ByteArray? {
            return HexFormat.of().parseHex(hex)
        }
    }
}
