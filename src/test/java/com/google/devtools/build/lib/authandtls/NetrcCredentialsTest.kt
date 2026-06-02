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

import com.google.devtools.build.lib.authandtls.Netrc.Credential

/** Tests for [NetrcCredentials].  */
@RunWith(JUnit4::class)
class NetrcCredentialsTest {
    @get:Throws(IOException::class)
    @get:org.junit.Test
    val requestMetadata_emptyNetrc_returnEmpty: Unit
        get() {
            val netrc: Netrc? = Netrc.create(null, com.google.common.collect.ImmutableMap.of<K?, V?>())
            val netrcCredentials: NetrcCredentials = NetrcCredentials(netrc)

            val requestMetadata: MutableMap<String?, MutableList<String?>?>? =
                netrcCredentials.getRequestMetadata(java.net.URI.create("https://example.org"))

            Truth.assertThat(requestMetadata).isEmpty()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val requestMetadata_matchedMachine_returnMatchedOne: Unit
        get() {
            val netrc: Netrc? = Netrc.create(
                null,
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    FOO_MACHINE,
                    FOO_CREDENTIAL
                )
            )
            val netrcCredentials: NetrcCredentials = NetrcCredentials(netrc)

            val fooRequestMetadata: MutableMap<String?, MutableList<String?>?> =
                netrcCredentials.getRequestMetadata(java.net.URI.create("https://" + FOO_MACHINE))

            assertRequestMetadata(
                fooRequestMetadata,
                FOO_CREDENTIAL.login(),
                FOO_CREDENTIAL.password()
            )
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val requestMetadata_notMatchedMachine_returnEmpty: Unit
        get() {
            val netrc: Netrc? = Netrc.create(
                null,
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    FOO_MACHINE,
                    FOO_CREDENTIAL
                )
            )
            val netrcCredentials: NetrcCredentials = NetrcCredentials(netrc)

            val barRequestMetadata: MutableMap<String?, MutableList<String?>?>? =
                netrcCredentials.getRequestMetadata(java.net.URI.create("https://" + BAR_MACHINE))

            Truth.assertThat(barRequestMetadata).isEmpty()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val requestMetadata_notMatchedMachine_returnDefault: Unit
        get() {
            val netrc: Netrc? = Netrc.create(
                DEFAULT_CREDENTIAL,
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    FOO_MACHINE,
                    FOO_CREDENTIAL
                )
            )
            val netrcCredentials: NetrcCredentials = NetrcCredentials(netrc)

            val barRequestMetadata: MutableMap<String?, MutableList<String?>?> =
                netrcCredentials.getRequestMetadata(java.net.URI.create("https://" + BAR_MACHINE))

            assertRequestMetadata(
                barRequestMetadata,
                DEFAULT_CREDENTIAL.login(),
                DEFAULT_CREDENTIAL.password()
            )
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val requestMetadata_emptyLogin: Unit
        get() {
            val netrc: Netrc? =
                Netrc.create(
                    null,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        FOO_MACHINE,
                        Credential.builder(FOO_MACHINE)
                            .setPassword(FOO_CREDENTIAL.password()).build()
                    )
                )
            val netrcCredentials: NetrcCredentials = NetrcCredentials(netrc)

            val fooRequestMetadata: MutableMap<String?, MutableList<String?>?> =
                netrcCredentials.getRequestMetadata(java.net.URI.create("https://" + FOO_MACHINE))

            assertRequestMetadata(
                fooRequestMetadata,
                "",
                FOO_CREDENTIAL.password()
            )
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val requestMetadata_emptyPassword: Unit
        get() {
            val netrc: Netrc? =
                Netrc.create(
                    null,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        FOO_MACHINE,
                        Credential.builder(FOO_MACHINE)
                            .setLogin(FOO_CREDENTIAL.login()).build()
                    )
                )
            val netrcCredentials: NetrcCredentials = NetrcCredentials(netrc)

            val fooRequestMetadata: MutableMap<String?, MutableList<String?>?> =
                netrcCredentials.getRequestMetadata(java.net.URI.create("https://" + FOO_MACHINE))

            assertRequestMetadata(
                fooRequestMetadata,
                FOO_CREDENTIAL.login(),
                ""
            )
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val requestMetadata_emptyLoginAndPassword: Unit
        get() {
            val netrc: Netrc? =
                Netrc.create(
                    null,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        FOO_MACHINE,
                        Credential.builder(FOO_MACHINE).build()
                    )
                )
            val netrcCredentials: NetrcCredentials = NetrcCredentials(netrc)

            val fooRequestMetadata: MutableMap<String?, MutableList<String?>?> =
                netrcCredentials.getRequestMetadata(java.net.URI.create("https://" + FOO_MACHINE))

            assertRequestMetadata(fooRequestMetadata, "", "")
        }

    companion object {
        private const val FOO_MACHINE = "foo.example.org"
        private val FOO_CREDENTIAL: Credential =
            Credential.builder(FOO_MACHINE).setLogin("foouser").setPassword("foopass").build()
        private const val BAR_MACHINE = "bar.example.org"
        private val DEFAULT_CREDENTIAL: Credential =
            Credential.builder("default").setLogin("defaultuser").setPassword("defaultpass").build()

        private fun assertRequestMetadata(
            requestMetadata: MutableMap<String?, MutableList<String?>?>, username: String?, password: String?
        ) {
            Truth.assertThat(requestMetadata.keys).containsExactly("Authorization")
            Truth.assertThat(com.google.common.collect.Iterables.getOnlyElement<MutableList<String?>?>(requestMetadata.values))
                .containsExactly(BasicHttpAuthenticationEncoder.encode(username, password))
        }
    }
}
