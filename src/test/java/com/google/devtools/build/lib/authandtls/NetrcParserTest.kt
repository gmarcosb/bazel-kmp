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

/** Tests for [NetrcParser].  */
@RunWith(JUnit4::class)
class NetrcParserTest {
    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_emptyContent_returnEmpty() {
        val inputStream: java.io.InputStream = newInputStreamWithContent("")

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isNull()
        assertThat(netrc.credentials()).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_emptyContentWithWhitespaces_returnEmpty() {
        val content = "\t \n   \r\n  \n"
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isNull()
        assertThat(netrc.credentials()).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_multipleMachines_returnMatched() {
        val content =
            ("machine "
                    + FOO_MACHINE
                    + " login "
                    + FOO_CREDENTIAL.login()
                    + " password "
                    + FOO_CREDENTIAL.password()
                    + "\n"
                    + "machine "
                    + BAR_MACHINE
                    + " login "
                    + BAR_CREDENTIAL.login()
                    + " password "
                    + BAR_CREDENTIAL.password())
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isNull()
        assertThat(netrc.credentials())
            .containsExactly(FOO_MACHINE, FOO_CREDENTIAL, BAR_MACHINE, BAR_CREDENTIAL)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_correctButBadlyFormattedContent_returnMatched() {
        val content =
            ("machine "
                    + FOO_MACHINE
                    + "\r\n   login "
                    + FOO_CREDENTIAL.login()
                    + "\n\t\t\t password \t\t\n"
                    + FOO_CREDENTIAL.password()
                    + "\n")
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isNull()
        assertThat(netrc.credentials()).containsExactly(FOO_MACHINE, FOO_CREDENTIAL)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_macdefOnly_returnEmpty() {
        val content = "macdef init\n" + "\tcd /pub\n" + "\tmget *\n" + "\tquit"
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isNull()
        assertThat(netrc.credentials()).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_mixOfMachinesAndMacdef_skipMacdefAndReturnMatched() {
        val content =
            ("machine "
                    + FOO_MACHINE
                    + " login "
                    + FOO_CREDENTIAL.login()
                    + " password "
                    + FOO_CREDENTIAL.password()
                    + "\n"
                    + "macdef init\n"
                    + "\tcd /pub\n"
                    + "\tmget *\n"
                    + "\tquit\n"
                    + "\n"
                    + "machine "
                    + BAR_MACHINE
                    + " login "
                    + BAR_CREDENTIAL.login()
                    + " password "
                    + BAR_CREDENTIAL.password())
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isNull()
        assertThat(netrc.credentials())
            .containsExactly(FOO_MACHINE, FOO_CREDENTIAL, BAR_MACHINE, BAR_CREDENTIAL)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_macdefWithCommentsInBetween_skipMacdefAndReturnMatched() {
        val content =
            ("machine "
                    + FOO_MACHINE
                    + " login "
                    + FOO_CREDENTIAL.login()
                    + " password "
                    + FOO_CREDENTIAL.password()
                    + "\n"
                    + "macdef init\n"
                    + "# this is comment\n"
                    + "\tcd /pub\n"
                    + "\tmget *\n"
                    + "\tquit\n"
                    + "# this is comment\n"
                    + "\n"
                    + "machine "
                    + BAR_MACHINE
                    + " login "
                    + BAR_CREDENTIAL.login()
                    + " password "
                    + BAR_CREDENTIAL.password())
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isNull()
        assertThat(netrc.credentials())
            .containsExactly(FOO_MACHINE, FOO_CREDENTIAL, BAR_MACHINE, BAR_CREDENTIAL)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_duplicatedMachineFields_override() {
        val content =
            ("machine "
                    + FOO_MACHINE
                    + " login overridden_user login "
                    + FOO_CREDENTIAL.login()
                    + " password overridden_pass password "
                    + FOO_CREDENTIAL.password())
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isNull()
        assertThat(netrc.credentials()).containsExactly(FOO_MACHINE, FOO_CREDENTIAL)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_machinesAfterDefault_ignore() {
        val content =
            ("machine "
                    + FOO_MACHINE
                    + " login "
                    + FOO_CREDENTIAL.login()
                    + " password "
                    + FOO_CREDENTIAL.password()
                    + "\n"
                    + "default login "
                    + DEFAULT_CREDENTIAL.login()
                    + " password "
                    + DEFAULT_CREDENTIAL.password()
                    + "\n"
                    + "machine "
                    + BAR_MACHINE
                    + " login "
                    + BAR_CREDENTIAL.login()
                    + " password "
                    + BAR_CREDENTIAL.password())
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isEqualTo(DEFAULT_CREDENTIAL)
        assertThat(netrc.credentials()).containsExactly(FOO_MACHINE, FOO_CREDENTIAL)
    }

    @org.junit.Test
    fun parseAndClose_badStartingContent_fail() {
        val content = "this is not netrc syntax"
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable {
                NetrcParser.parseAndClose(inputStream)
            })
    }

    @org.junit.Test
    fun parseAndClose_badMachine_fail() {
        val content = "machine this is not netrc syntax"
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable {
                NetrcParser.parseAndClose(inputStream)
            })
    }

    @org.junit.Test
    fun parseAndClose_badDefault_fail() {
        val content = "default this is not netrc syntax"
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable {
                NetrcParser.parseAndClose(inputStream)
            })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun parseAndClose_commentOnly_returnEmpty() {
        val content = "# this is comment"
        val inputStream: java.io.InputStream = newInputStreamWithContent(content)

        val netrc: Netrc = NetrcParser.parseAndClose(inputStream)

        assertThat(netrc.defaultCredential()).isNull()
        assertThat(netrc.credentials()).isEmpty()
    }

    @org.junit.Test
    fun credential_shouldNotLeakPassword() {
        assertThat(FOO_CREDENTIAL.toString()).doesNotContain(FOO_CREDENTIAL.password())
    }

    companion object {
        private const val FOO_MACHINE = "foo.example.org"
        private val FOO_CREDENTIAL: Credential =
            Credential.builder(FOO_MACHINE).setLogin("foouser").setPassword("foopass").build()
        private const val BAR_MACHINE = "bar.example.org"
        private val BAR_CREDENTIAL: Credential =
            Credential.builder(BAR_MACHINE).setLogin("baruser").setPassword("barpass").build()
        private val DEFAULT_CREDENTIAL: Credential =
            Credential.builder("default").setLogin("defaultuser").setPassword("defaultpass").build()

        fun newInputStreamWithContent(content: String): java.io.InputStream {
            return ByteArrayInputStream(content.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        }
    }
}
