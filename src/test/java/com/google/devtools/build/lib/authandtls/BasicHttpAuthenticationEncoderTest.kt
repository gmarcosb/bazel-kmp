// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.authandtls

import com.google.devtools.build.lib.util.StringEncoding

/** Tests for [BasicHttpAuthenticationEncoder].  */
@RunWith(JUnit4::class)
class BasicHttpAuthenticationEncoderTest {
    @org.junit.Test
    fun encode_normalUsernamePassword_outputExpected() {
        val message: String? = BasicHttpAuthenticationEncoder.encode("Aladdin", "open sesame")
        Truth.assertThat(message).isEqualTo("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==")
    }

    @org.junit.Test
    fun encode_normalUsernamePassword_canBeDecoded() {
        val message: String = BasicHttpAuthenticationEncoder.encode("Aladdin", "open sesame")

        val usernameAndPassword = decode(message)
        Truth.assertThat(usernameAndPassword[0]).isEqualTo("Aladdin")
        Truth.assertThat(usernameAndPassword[1]).isEqualTo("open sesame")
    }

    @org.junit.Test
    fun encode_usernameContainsColon_canBeDecoded() {
        val message: String = BasicHttpAuthenticationEncoder.encode("foo:user", "foopass")

        val usernameAndPassword = decode(message)
        Truth.assertThat(usernameAndPassword[0]).isEqualTo("foo")
        Truth.assertThat(usernameAndPassword[1]).isEqualTo("user:foopass")
    }

    @org.junit.Test
    fun encode_emptyUsername_outputExpected() {
        val message: String? = BasicHttpAuthenticationEncoder.encode("", "foopass")
        Truth.assertThat(message).isEqualTo("Basic OmZvb3Bhc3M=")
    }

    @org.junit.Test
    fun encode_emptyPassword_outputExpected() {
        val message: String? = BasicHttpAuthenticationEncoder.encode("foouser", "")
        Truth.assertThat(message).isEqualTo("Basic Zm9vdXNlcjo=")
    }

    @org.junit.Test
    fun encode_emptyUsernamePassword_outputExpected() {
        val message: String? = BasicHttpAuthenticationEncoder.encode("", "")
        Truth.assertThat(message).isEqualTo("Basic Og==")
    }

    @org.junit.Test
    fun encode_specialCharacterUtf8_outputExpected() {
        val message: String? =
            BasicHttpAuthenticationEncoder.encode(
                "test", StringEncoding.unicodeToInternal("123\u00A3")
            )
        Truth.assertThat(message).isEqualTo("Basic dGVzdDoxMjPCow==")
    }

    companion object {
        private fun decode(message: String): Array<String?> {
            val base64EncodedMessage: String = message.substring(6)
            val usernameAndPassword = String(
                java.util.Base64.getDecoder().decode(base64EncodedMessage),
                java.nio.charset.StandardCharsets.UTF_8
            )
            return usernameAndPassword.split(":".toRegex(), limit = 2).toTypedArray()
        }
    }
}
