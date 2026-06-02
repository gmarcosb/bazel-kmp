// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions.CredentialHelperOption

/** Test for [CredentialHelperOptionConverter].  */
@RunWith(TestParameterInjector::class)
class CredentialHelperOptionConverterTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exactScope() {
        val helper1: CredentialHelperOption =
            CredentialHelperOptionConverter.INSTANCE.convert("example.com=foo")
        assertThat(helper1.scope()).hasValue("example.com")
        assertThat(helper1.path).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun wildcardScope() {
        val helper1: CredentialHelperOption =
            CredentialHelperOptionConverter.INSTANCE.convert("*.example.com=foo")
        assertThat(helper1.scope()).hasValue("*.example.com")
        assertThat(helper1.path).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun punycodeScope() {
        val helper1: CredentialHelperOption =
            CredentialHelperOptionConverter.INSTANCE.convert("münchen.de=foo")
        assertThat(helper1.scope()).hasValue("xn--mnchen-3ya.de")
        assertThat(helper1.path).isEqualTo("foo")

        val helper2: CredentialHelperOption =
            CredentialHelperOptionConverter.INSTANCE.convert("*.köln.de=foo")
        assertThat(helper2.scope()).hasValue("*.xn--kln-sna.de")
        assertThat(helper2.path).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun absolutePath() {
        val helper1: CredentialHelperOption =
            CredentialHelperOptionConverter.INSTANCE.convert("/absolute/path")
        assertThat(helper1.scope()).isEmpty()
        assertThat(helper1.path).isEqualTo("/absolute/path")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun rootRelativePath() {
        val helper1: CredentialHelperOption =
            CredentialHelperOptionConverter.INSTANCE.convert("%workspace%/path")
        assertThat(helper1.scope()).isEmpty()
        assertThat(helper1.path).isEqualTo("%workspace%/path")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathLookup() {
        val helper1: CredentialHelperOption = CredentialHelperOptionConverter.INSTANCE.convert("foo")
        assertThat(helper1.scope()).isEmpty()
        assertThat(helper1.path).isEqualTo("foo")
    }

    @org.junit.Test
    fun emptyOption() {
        val t: Throwable? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { CredentialHelperOptionConverter.INSTANCE.convert("") })
        Truth.assertThat(t).hasMessageThat().contains("Credential helper path must not be empty")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyScope() {
        val t: Throwable? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { CredentialHelperOptionConverter.INSTANCE.convert("=/foo") })
        Truth.assertThat(t).hasMessageThat().contains("Credential helper scope must not be empty")
    }

    @org.junit.Test
    fun emptyPath() {
        val t: Throwable? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { CredentialHelperOptionConverter.INSTANCE.convert("foo=") })
        Truth.assertThat(t).hasMessageThat().contains("Credential helper path must not be empty")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyScopeAndPath() {
        val t: Throwable? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { CredentialHelperOptionConverter.INSTANCE.convert("=") })
        Truth.assertThat(t).hasMessageThat().contains("Credential helper scope must not be empty")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidScope(
        @TestParameter(
            "-example.com",
            "example-.com",
            "example!.com",
            "*.",
            "example.*",
            "*.example.*",
            "foo.*.example.com",
            "*.foo.*.example.com",
            "*-foo.example.com",
            ".*.example.com",
            "foo.*.münchen.de",
            ".*.münchen.de",
            "*-foo.münchen.de"
        ) scope: String?
    ) {
        val t: Throwable? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { CredentialHelperOptionConverter.INSTANCE.convert(scope + "=foo") })
        Truth.assertThat(t)
            .hasMessageThat()
            .contains(
                ("Credential helper scope '"
                        + scope
                        + "' must be a valid domain name with an optional leading '*.' wildcard")
            )
    }
}
