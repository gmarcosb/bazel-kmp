// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.StringEncoding.internalToPlatform

@RunWith(TestParameterInjector::class)
class StringEncodingTest {
    @org.junit.Test
    fun testUnicodeToInternal() {
        assertThat(unicodeToInternal("")).isSameInstanceAs("")
        assertThat(unicodeToInternal("hello")).isSameInstanceAs("hello")
        assertThat(unicodeToInternal("hällo"))
            .isEqualTo(
                String(
                    "hällo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            )
        assertThat(unicodeToInternal("hållo"))
            .isEqualTo(
                String(
                    "hållo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            )
        assertThat(unicodeToInternal("h👋llo"))
            .isEqualTo(
                String(
                    "h👋llo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            )
    }

    @org.junit.Test
    fun testInternalToUnicode() {
        assertThat(internalToUnicode("")).isSameInstanceAs("")
        assertThat(internalToUnicode("hello")).isSameInstanceAs("hello")
        assertThat(
            internalToUnicode(
                String(
                    "hällo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            )
        )
            .isEqualTo("hällo")
        assertThat(
            internalToUnicode(
                String(
                    "hållo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            )
        )
            .isEqualTo("hållo")
        assertThat(
            internalToUnicode(
                String(
                    "h👋llo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
            )
        )
            .isEqualTo("h👋llo")
    }

    @org.junit.Test
    fun testPlatformToInternal() {
        if (SUN_JNU_ENCODING == java.nio.charset.StandardCharsets.ISO_8859_1 && OS.getCurrent() === OS.LINUX) {
            assertThat(platformToInternal("")).isSameInstanceAs("")
            assertThat(platformToInternal("hello")).isSameInstanceAs("hello")
            run {
                val s = String(
                    "hällo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
                assertThat(platformToInternal(s)).isSameInstanceAs(s)
            }
            run {
                val s = String(
                    "hållo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
                assertThat(platformToInternal(s)).isSameInstanceAs(s)
            }
            run {
                val s = String(
                    "h👋llo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
                assertThat(platformToInternal(s)).isSameInstanceAs(s)
            }
            run {
                // Not valid Unicode.
                val s = String(
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0X01),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
                assertThat(platformToInternal(s)).isSameInstanceAs(s)
            }
        } else {
            assertThat(platformToInternal("")).isSameInstanceAs("")
            assertThat(platformToInternal("hello")).isSameInstanceAs("hello")
            assertThat(platformToInternal("hällo"))
                .isEqualTo(
                    String(
                        "hällo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    )
                )
            assertThat(platformToInternal("hållo"))
                .isEqualTo(
                    String(
                        "hållo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    )
                )
            assertThat(platformToInternal("h👋llo"))
                .isEqualTo(
                    String(
                        "h👋llo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    )
                )
        }
    }

    @org.junit.Test
    fun testInternalToPlatform() {
        if (SUN_JNU_ENCODING == java.nio.charset.StandardCharsets.ISO_8859_1 && OS.getCurrent() === OS.LINUX) {
            assertThat(internalToPlatform("")).isSameInstanceAs("")
            assertThat(internalToPlatform("hello")).isSameInstanceAs("hello")
            run {
                val s = String(
                    "hällo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
                assertThat(internalToPlatform(s)).isSameInstanceAs(s)
            }
            run {
                val s = String(
                    "hållo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
                assertThat(internalToPlatform(s)).isSameInstanceAs(s)
            }
            run {
                val s = String(
                    "h👋llo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
                assertThat(internalToPlatform(s)).isSameInstanceAs(s)
            }
            run {
                // Not valid Unicode.
                val s = String(
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0X01),
                    java.nio.charset.StandardCharsets.ISO_8859_1
                )
                assertThat(internalToPlatform(s)).isSameInstanceAs(s)
            }
        } else {
            assertThat(internalToPlatform("")).isSameInstanceAs("")
            assertThat(internalToPlatform("hello")).isSameInstanceAs("hello")
            assertThat(
                internalToPlatform(
                    String(
                        "hällo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    )
                )
            )
                .isEqualTo("hällo")
            assertThat(
                internalToPlatform(
                    String(
                        "hållo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    )
                )
            )
                .isEqualTo("hållo")
            assertThat(
                internalToPlatform(
                    String(
                        "h👋llo".toByteArray(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.ISO_8859_1
                    )
                )
            )
                .isEqualTo("h👋llo")
        }
    }

    @org.junit.Test
    fun testPlatformToInternal_roundtrip(
        @TestParameter("ascii", "äöüÄÖÜß", "🌱", "羅勒罗勒学名") s: String
    ) {
        TruthJUnit.assume().that(canEncode(s, SUN_JNU_ENCODING)).isTrue()

        val internal: String? = platformToInternal(s)
        // In the internal encoding, raw bytes are encoded as Latin-1.
        assertThat(StringUnsafe.getCoder(internal)).isEqualTo(StringUnsafe.LATIN1)
        val roundtripped: String? = internalToPlatform(internal)
        if (StringUnsafe.isAscii(s)) {
            Truth.assertThat(roundtripped).isSameInstanceAs(s)
        } else {
            Truth.assertThat(roundtripped).isEqualTo(s)
        }
    }

    @org.junit.Test
    fun testPlatformToInternal_rawBytesRoundtrip() {
        // Not valid UTF-8
        val rawBytes = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFE.toByte(), 0xFF.toByte())
        Truth.assertThat(canDecode(rawBytes, java.nio.charset.StandardCharsets.UTF_8)).isFalse()

        // Roundtripping raw bytes through the internal encoding requires Linux and a Latin-1 locale.
        TruthJUnit.assume().that(OS.getCurrent()).isEqualTo(OS.LINUX)
        TruthJUnit.assume().that<java.nio.charset.Charset?>(SUN_JNU_ENCODING)
            .isEqualTo(java.nio.charset.StandardCharsets.ISO_8859_1)

        val platform = String(rawBytes, java.nio.charset.StandardCharsets.ISO_8859_1)
        val internal: String? = platformToInternal(platform)
        Truth.assertThat(internal).isSameInstanceAs(platform)
        val roundtripped: String? = internalToPlatform(internal)
        Truth.assertThat(roundtripped).isSameInstanceAs(internal)
    }

    @org.junit.Test
    fun testUnicodeToInternal_roundtrip(
        @TestParameter("ascii", "äöüÄÖÜß", "🌱", "羅勒罗勒学名") s: String?
    ) {
        val internal: String? = unicodeToInternal(s)
        // In the internal encoding, raw bytes are encoded as Latin-1.
        assertThat(StringUnsafe.getCoder(internal)).isEqualTo(StringUnsafe.LATIN1)
        val roundtripped: String? = internalToUnicode(internal)
        if (StringUnsafe.isAscii(s)) {
            Truth.assertThat(roundtripped).isSameInstanceAs(s)
        } else {
            Truth.assertThat(roundtripped).isEqualTo(s)
        }
    }

    companion object {
        val SUN_JNU_ENCODING: java.nio.charset.Charset =
            java.nio.charset.Charset.forName(java.lang.System.getProperty("sun.jnu.encoding"))

        private fun canEncode(s: String, charset: java.nio.charset.Charset): Boolean {
            try {
                charset.newEncoder().encode(CharBuffer.wrap(s))
                return true
            } catch (e: CharacterCodingException) {
                return false
            }
        }

        private fun canDecode(bytes: ByteArray, charset: java.nio.charset.Charset): Boolean {
            try {
                charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
                return true
            } catch (e: CharacterCodingException) {
                return false
            }
        }
    }
}
