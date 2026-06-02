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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.function.ThrowingRunnable
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Tests for [ProxyHelper].
 */
@RunWith(JUnit4::class)
class ProxyHelperTest {
    @Before
    fun setUp() {
        ProxyHelper.resetAuthenticatorForTesting()
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededHttpLowerCase() {
        val helper = ProxyHelper(ImmutableMap.of<String?, String?>("http_proxy", "http://my.example.com"))
        val proxyInfo = helper.createProxyIfNeeded(URI.create("http://www.something.com"))
        Truth.assertThat(proxyInfo.proxy().toString())
            .containsMatch("my\\.example\\.com(/<unresolved>)?:80$")
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededHttpUpperCase() {
        val helper = ProxyHelper(ImmutableMap.of<String?, String?>("HTTP_PROXY", "http://my.example.com"))
        val proxyInfo = helper.createProxyIfNeeded(URI.create("http://www.something.com"))
        Truth.assertThat(proxyInfo.proxy().toString())
            .containsMatch("my\\.example\\.com(/<unresolved>)?:80$")
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededHttpsLowerCase() {
        val helper = ProxyHelper(ImmutableMap.of<String?, String?>("https_proxy", "https://my.example.com"))
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.something.com"))
        Truth.assertThat(proxyInfo.proxy().toString())
            .containsMatch("my\\.example\\.com(/<unresolved>)?:443$")
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededHttpsUpperCase() {
        val helper = ProxyHelper(ImmutableMap.of<String?, String?>("HTTPS_PROXY", "https://my.example.com"))
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.something.com"))
        Truth.assertThat(proxyInfo.proxy().toString())
            .containsMatch("my\\.example\\.com(/<unresolved>)?:443$")
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededNoProxyLowerCase() {
        val helper =
            ProxyHelper(
                ImmutableMap.of<String?, String?>(
                    "no_proxy",
                    "something.com,example.com,localhost",
                    "HTTPS_PROXY",
                    "https://my.example.com"
                )
            )
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.example.com"))
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededNoProxyUpperCase() {
        val helper =
            ProxyHelper(
                ImmutableMap.of<String?, String?>(
                    "NO_PROXY",
                    "something.com,example.com,localhost",
                    "HTTPS_PROXY",
                    "https://my.example.com"
                )
            )
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.example.com"))
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededMultipleNoProxyLowerCase() {
        val helper =
            ProxyHelper(
                ImmutableMap.of<String?, String?>(
                    "no_proxy",
                    "something.com,example.com,localhost",
                    "HTTPS_PROXY",
                    "https://my.example.com"
                )
            )
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.example.com"))
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededMultipleNoProxyUpperCase() {
        val helper =
            ProxyHelper(
                ImmutableMap.of<String?, String?>(
                    "NO_PROXY",
                    "something.com,example.com,localhost",
                    "HTTPS_PROXY",
                    "https://my.example.com"
                )
            )
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.example.com"))
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededMultipleNoProxySpaces() {
        val helper =
            ProxyHelper(
                ImmutableMap.of<String?, String?>(
                    "no_proxy",
                    "something.com ,   example.com, localhost",
                    "HTTPS_PROXY",
                    "https://my.example.com"
                )
            )
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.something.com"))
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)

        val proxyInfo2 = helper.createProxyIfNeeded(URI.create("https://www.example.com"))
        Truth.assertThat(proxyInfo2.proxy()).isEqualTo(Proxy.NO_PROXY)

        val proxyInfo3 = helper.createProxyIfNeeded(URI.create("https://localhost"))
        Truth.assertThat(proxyInfo3.proxy()).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededNoProxyNoMatchSubstring() {
        val helper =
            ProxyHelper(
                ImmutableMap.of<String?, String?>(
                    "NO_PROXY",
                    "something.com,example.com,localhost",
                    "HTTPS_PROXY",
                    "https://my.example.com"
                )
            )
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.not-example.com"))
        Truth.assertThat(proxyInfo.proxy().toString())
            .containsMatch("my\\.example\\.com(/<unresolved>)?:443$")
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededNoProxyMatchSubdomainInNoProxy() {
        val helper =
            ProxyHelper(
                ImmutableMap.of<String?, String?>(
                    "NO_PROXY",
                    ".something.com,example.com,localhost",
                    "HTTPS_PROXY",
                    "https://my.example.com"
                )
            )
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.my.something.com"))
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededNoProxyMatchSubdomainInURL() {
        val helper =
            ProxyHelper(
                ImmutableMap.of<String?, String?>(
                    "NO_PROXY",
                    "something.com,example.com,localhost",
                    "HTTPS_PROXY",
                    "https://my.example.com"
                )
            )
        val proxyInfo =
            helper.createProxyIfNeeded(URI.create("https://www.my.subdomain.something.com"))
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    @Throws(Exception::class)
    fun testNoProxy() {
        // Empty address.
        var proxyInfo = ProxyHelper.createProxy(null)
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)
        proxyInfo = ProxyHelper.createProxy("")
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)
        val env: MutableMap<String?, String?> = ImmutableMap.of<String?, String?>()
        val helper = ProxyHelper(env)
        proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.something.com"))
        Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    @Throws(Exception::class)
    fun testProxyDefaultPort() {
        var proxyInfo = ProxyHelper.createProxy("http://my.example.com")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.HTTP)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":80")

        proxyInfo = ProxyHelper.createProxy("https://my.example.com")
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":443")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyExplicitPort() {
        var proxyInfo = ProxyHelper.createProxy("http://my.example.com:12345")
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":12345")

        proxyInfo = ProxyHelper.createProxy("https://my.example.com:12345")
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":12345")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyNoProtocol() {
        val proxyInfo = ProxyHelper.createProxy("my.example.com")
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":80")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyNoProtocolWithPort() {
        val proxyInfo = ProxyHelper.createProxy("my.example.com:12345")
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":12345")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyPortParsingError() {
        val e =
            Assert.assertThrows<IOException?>(
                IOException::class.java,
                ThrowingRunnable { ProxyHelper.createProxy("http://my.example.com:foo") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Proxy address http://my.example.com:foo is not a valid URL")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyAuth() {
        var proxyInfo = ProxyHelper.createProxy("http://foo:barbaz@my.example.com")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.HTTP)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":80")

        proxyInfo = ProxyHelper.createProxy("https://biz:bat@my.example.com")
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":443")
    }

    @Test
    @Throws(Exception::class)
    fun testEncodedProxyAuth() {
        val proxyInfo = ProxyHelper.createProxy("http://foo:b%40rb%40z@my.example.com")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.HTTP)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":80")
    }

    @Test
    @Throws(Exception::class)
    fun testInvalidAuth() {
        val e =
            Assert.assertThrows<IOException?>(
                IOException::class.java,
                ThrowingRunnable { ProxyHelper.createProxy("http://foo@my.example.com") })
        Truth.assertThat(e).hasMessageThat().contains("No password given for proxy")
    }

    @Test
    @Throws(Exception::class)
    fun testNoProxyAuth() {
        val proxyInfo = ProxyHelper.createProxy("http://localhost:3128/")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.HTTP)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":3128")
    }

    @Test
    @Throws(Exception::class)
    fun testTrailingSlash() {
        val proxyInfo = ProxyHelper.createProxy("http://foo:bar@example.com:8000/")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.HTTP)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":8000")
    }

    // Tests for ProxyInfo credentials
    @Test
    @Throws(Exception::class)
    fun testProxyInfoWithCredentials() {
        val proxyInfo = ProxyHelper.createProxy("http://myuser:mypass@proxy.example.com:8080")
        Truth.assertThat(proxyInfo.hasCredentials()).isTrue()
        Truth.assertThat(proxyInfo.proxyAuthorizationHeader).isNotNull()
        // Verify it's a valid Basic auth header
        Truth.assertThat(proxyInfo.proxyAuthorizationHeader).startsWith("Basic ")
        // Decode and verify the credentials
        val encoded: String = proxyInfo.proxyAuthorizationHeader.substring("Basic ".length)
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        Truth.assertThat(decoded).isEqualTo("myuser:mypass")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyInfoWithUrlEncodedCredentials() {
        // Password contains @ and : which are URL-encoded
        val proxyInfo =
            ProxyHelper.createProxy("http://user:p%40ss%3Aword@proxy.example.com:8080")
        Truth.assertThat(proxyInfo.hasCredentials()).isTrue()
        val encoded: String = proxyInfo.proxyAuthorizationHeader.substring("Basic ".length)
        val decoded = String(Base64.getDecoder().decode(encoded), Charset.defaultCharset())
        // URL-encoded characters should be decoded
        Truth.assertThat(decoded).isEqualTo("user:p@ss:word")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyInfoWithUrlEncodedUsername() {
        // Username contains @ which is URL-encoded
        val proxyInfo =
            ProxyHelper.createProxy("http://user%40domain:password@proxy.example.com:8080")
        Truth.assertThat(proxyInfo.hasCredentials()).isTrue()
        val encoded: String = proxyInfo.proxyAuthorizationHeader.substring("Basic ".length)
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        // URL-encoded characters in username should be decoded
        Truth.assertThat(decoded).isEqualTo("user@domain:password")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyInfoWithUnicodeCredentials() {
        // Test Unicode characters in username and password
        // Username: "用户" (Chinese for "user") = %E7%94%A8%E6%88%B7
        // Password: "contraseña" (Spanish with ñ) = contrase%C3%B1a
        val proxyInfo =
            ProxyHelper.createProxy("http://%E7%94%A8%E6%88%B7:contrase%C3%B1a@proxy.example.com:8080")
        Truth.assertThat(proxyInfo.hasCredentials()).isTrue()
        val encoded: String = proxyInfo.proxyAuthorizationHeader.substring("Basic ".length)
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        Truth.assertThat(decoded).isEqualTo("用户:contraseña")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyInfoWithoutCredentials() {
        val proxyInfo = ProxyHelper.createProxy("http://proxy.example.com:8080")
        Truth.assertThat(proxyInfo.hasCredentials()).isFalse()
        Truth.assertThat(proxyInfo.proxyAuthorizationHeader).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testProxyInfoNoProxyHasNoCredentials() {
        Truth.assertThat(ProxyInfo.NO_PROXY.hasCredentials()).isFalse()
        Truth.assertThat(ProxyInfo.NO_PROXY.proxyAuthorizationHeader).isNull()
        Truth.assertThat(ProxyInfo.NO_PROXY.proxy()).isEqualTo(Proxy.NO_PROXY)
    }

    @Test
    @Throws(Exception::class)
    fun testProxyInfoFromSystemProperties() {
        // Test that credentials can be provided via the systemPropertyUser/Password parameters
        val proxyInfo =
            ProxyHelper.createProxyInfo("http://proxy.example.com:8080", "sysuser", "syspass")
        Truth.assertThat(proxyInfo.hasCredentials()).isTrue()
        val encoded: String = proxyInfo.proxyAuthorizationHeader.substring("Basic ".length)
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        Truth.assertThat(decoded).isEqualTo("sysuser:syspass")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyInfoUrlCredentialsTakePrecedence() {
        // URL credentials should take precedence over system property credentials
        val proxyInfo =
            ProxyHelper.createProxyInfo(
                "http://urluser:urlpass@proxy.example.com:8080", "sysuser", "syspass"
            )
        Truth.assertThat(proxyInfo.hasCredentials()).isTrue()
        val encoded: String = proxyInfo.proxyAuthorizationHeader.substring("Basic ".length)
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        Truth.assertThat(decoded).isEqualTo("urluser:urlpass")
    }

    @Test
    @Throws(Exception::class)
    fun testProxyInfoSystemPropertiesOnlyUserIgnored() {
        // If only username is provided via system properties (no password), credentials should not be
        // set
        val proxyInfo =
            ProxyHelper.createProxyInfo("http://proxy.example.com:8080", "sysuser", null)
        Truth.assertThat(proxyInfo.hasCredentials()).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun testProxyInfoSystemPropertiesOnlyPasswordIgnored() {
        // If only password is provided via system properties (no username), credentials should not be
        // set
        val proxyInfo =
            ProxyHelper.createProxyInfo("http://proxy.example.com:8080", null, "syspass")
        Truth.assertThat(proxyInfo.hasCredentials()).isFalse()
    }

    // Tests for http.nonProxyHosts system property
    @Test
    @Throws(Exception::class)
    fun testNonProxyHostsExactMatch() {
        val oldValue = System.getProperty("http.nonProxyHosts")
        try {
            System.setProperty("http.nonProxyHosts", "localhost|example.com")
            val helper = ProxyHelper(ImmutableMap.of<String?, String?>("http_proxy", "http://proxy:8080"))

            // Exact match should bypass proxy
            val proxyInfo = helper.createProxyIfNeeded(URI.create("http://example.com/foo"))
            Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)

            // Non-match should use proxy
            val proxyInfo2 = helper.createProxyIfNeeded(URI.create("http://other.com/foo"))
            Truth.assertThat(proxyInfo2.proxy()).isNotEqualTo(Proxy.NO_PROXY)
        } finally {
            if (oldValue != null) {
                System.setProperty("http.nonProxyHosts", oldValue)
            } else {
                System.clearProperty("http.nonProxyHosts")
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun testNonProxyHostsWildcardPrefix() {
        val oldValue = System.getProperty("http.nonProxyHosts")
        try {
            System.setProperty("http.nonProxyHosts", "*.example.com")
            val helper = ProxyHelper(ImmutableMap.of<String?, String?>("http_proxy", "http://proxy:8080"))

            // Wildcard match should bypass proxy
            val proxyInfo = helper.createProxyIfNeeded(URI.create("http://foo.example.com/bar"))
            Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)

            // Non-match should use proxy
            val proxyInfo2 = helper.createProxyIfNeeded(URI.create("http://example.com/bar"))
            Truth.assertThat(proxyInfo2.proxy()).isNotEqualTo(Proxy.NO_PROXY)
        } finally {
            if (oldValue != null) {
                System.setProperty("http.nonProxyHosts", oldValue)
            } else {
                System.clearProperty("http.nonProxyHosts")
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun testNonProxyHostsWildcardSuffix() {
        val oldValue = System.getProperty("http.nonProxyHosts")
        try {
            System.setProperty("http.nonProxyHosts", "local*")
            val helper = ProxyHelper(ImmutableMap.of<String?, String?>("http_proxy", "http://proxy:8080"))

            // Wildcard match should bypass proxy
            val proxyInfo = helper.createProxyIfNeeded(URI.create("http://localhost/bar"))
            Truth.assertThat(proxyInfo.proxy()).isEqualTo(Proxy.NO_PROXY)

            val proxyInfo2 = helper.createProxyIfNeeded(URI.create("http://localserver/bar"))
            Truth.assertThat(proxyInfo2.proxy()).isEqualTo(Proxy.NO_PROXY)
        } finally {
            if (oldValue != null) {
                System.setProperty("http.nonProxyHosts", oldValue)
            } else {
                System.clearProperty("http.nonProxyHosts")
            }
        }
    }

    // Tests for SOCKS proxy support
    @Test
    @Throws(Exception::class)
    fun testSocks5ProxyDefaultPort() {
        val proxyInfo = ProxyHelper.createProxy("socks5://my.example.com")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.SOCKS)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":1080")
    }

    @Test
    @Throws(Exception::class)
    fun testSocks5ProxyExplicitPort() {
        val proxyInfo = ProxyHelper.createProxy("socks5://my.example.com:5000")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.SOCKS)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":5000")
    }

    @Test
    @Throws(Exception::class)
    fun testSocks4ProxyDefaultPort() {
        val proxyInfo = ProxyHelper.createProxy("socks4://my.example.com")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.SOCKS)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":1080")
    }

    @Test
    @Throws(Exception::class)
    fun testSocksProxyDefaultPort() {
        val proxyInfo = ProxyHelper.createProxy("socks://my.example.com")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.SOCKS)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":1080")
    }

    @Test
    @Throws(Exception::class)
    fun testSocks5ProxyWithAuth() {
        val proxyInfo = ProxyHelper.createProxy("socks5://user:pass@my.example.com:1080")
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.SOCKS)
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":1080")
        Truth.assertThat(proxyInfo.hasCredentials()).isTrue()
        val encoded: String = proxyInfo.proxyAuthorizationHeader.substring("Basic ".length)
        val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        Truth.assertThat(decoded).isEqualTo("user:pass")
    }

    @Test
    @Throws(Exception::class)
    fun testCreateIfNeededSocks5Proxy() {
        val helper = ProxyHelper(ImmutableMap.of<String?, String?>("HTTPS_PROXY", "socks5://localhost:5000"))
        val proxyInfo = helper.createProxyIfNeeded(URI.create("https://www.something.com"))
        Truth.assertThat<Proxy.Type?>(proxyInfo.proxy()!!.type()).isEqualTo(Proxy.Type.SOCKS)
        Truth.assertThat(proxyInfo.proxy().toString()).contains("localhost")
        Truth.assertThat(proxyInfo.proxy().toString()).endsWith(":5000")
    }
}
