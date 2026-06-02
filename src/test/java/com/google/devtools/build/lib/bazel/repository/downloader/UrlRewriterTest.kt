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
package com.google.devtools.build.lib.bazel.repository.downloader

import com.google.auth.Credentials
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.authandtls.BasicHttpAuthenticationEncoder
import net.starlark.java.syntax.Location
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.Reader
import java.io.StringReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.*

/** Unit tests for [UrlRewriter]  */
@RunWith(JUnit4::class)
class UrlRewriterTest {
    /** Convenience wrapper to create a [UrlRewriter] with a single path/reader.  */
    @Throws(UrlRewriterParseException::class)
    private fun testUrlRewriter(filePathForErrorReporting: String, reader: Reader): UrlRewriter {
        return UrlRewriter(ImmutableList.of<String?>(filePathForErrorReporting), ImmutableList.of<Reader?>(reader))
    }

    @Test
    @Throws(Exception::class)
    fun byDefaultTheUrlRewriterDoesNothing() {
        val munger = testUrlRewriter("/dev/null", StringReader(""))

        val urls = ImmutableList.of<URI?>(URI.create("http://example.com"))
        val amended =
            munger.amend(urls).stream().map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended).isEqualTo(urls)
    }

    @Test
    @Throws(UrlRewriterParseException::class)
    fun constructorMustHaveTheSameNumberOfFilePathsAndReaders() {
        // This has one file path and one reader - no exception is thrown.

        // Two file paths, but one reader - this will fail the precondition.

        Assert.assertThrows<IllegalArgumentException?>(
            "filePath and readers size must be equal",
            IllegalArgumentException::class.java,
            ThrowingRunnable {
                UrlRewriter(
                    ImmutableList.of<String?>("/dev/null", "/dev/null"),
                    ImmutableList.of<Reader?>(StringReader(""))
                )
            })
    }

    @Test
    @Throws(Exception::class)
    fun shouldBeAbleToBlockParticularHostsRegardlessOfScheme() {
        val config = "block example.com"
        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val urls =
            ImmutableList.of<URI?>(
                URI.create("http://example.com"),
                URI.create("https://example.com"),
                URI.create("http://localhost")
            )
        val amended =
            munger.amend(urls).stream().map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended).containsExactly(URI.create("http://localhost"))
    }

    @Test
    @Throws(Exception::class)
    fun shouldAllowAUrlToBeRewritten() {
        val config = "rewrite example.com/foo/(.*) mycorp.com/$1/foo"
        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val urls = ImmutableList.of<URI?>(URI.create("https://example.com/foo/bar"))
        val amended =
            munger.amend(urls).stream().map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended).containsExactly(URI.create("https://mycorp.com/bar/foo"))
    }

    @Test
    @Throws(Exception::class)
    fun rewritesCanExpandToMoreThanOneUrl() {
        val config =
            ("rewrite example.com/foo/(.*) mycorp.com/$1/somewhere\n"
                    + "rewrite example.com/foo/(.*) mycorp.com/$1/elsewhere")
        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val urls = ImmutableList.of<URI?>(URI.create("https://example.com/foo/bar"))
        val amended =
            munger.amend(urls).stream().map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        // There's no guarantee about the ordering of the rewrites
        Truth.assertThat(amended).contains(URI.create("https://mycorp.com/bar/somewhere"))
        Truth.assertThat(amended).contains(URI.create("https://mycorp.com/bar/elsewhere"))
    }

    /** Same as [.rewritesCanExpandToMoreThanOneUrl] but spread across two config files.  */
    @Test
    @Throws(Exception::class)
    fun rewritesCanExpandToMoreThanOneUrlWithMultipleConfigs() {
        val config = "rewrite example.com/foo/(.*) mycorp.com/$1/somewhere\n"
        val config2 = "rewrite example.com/foo/(.*) mycorp.com/$1/elsewhere\n"
        val munger =
            UrlRewriter(
                ImmutableList.of<String?>("/dev/null", "/dev/null"),
                ImmutableList.of<Reader?>(StringReader(config), StringReader(config2))
            )

        val urls = ImmutableList.of<URI?>(URI.create("https://example.com/foo/bar"))
        val amended =
            munger.amend(urls).stream().map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        // There's no guarantee about the ordering of the rewrites
        Truth.assertThat(amended).contains(URI.create("https://mycorp.com/bar/somewhere"))
        Truth.assertThat(amended).contains(URI.create("https://mycorp.com/bar/elsewhere"))
    }

    @Test
    @Throws(Exception::class)
    fun shouldBlockAllUrlsOtherThanSpecificOnes() {
        val config = "" + "block *\n" + "allow example.com"

        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val urls =
            ImmutableList.of<URI?>(
                URI.create("https://foo.com"),
                URI.create("https://example.com/foo/bar"),
                URI.create("https://subdomain.example.com/qux")
            )
        val amended =
            munger.amend(urls).stream().map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended)
            .containsExactly(
                URI.create("https://example.com/foo/bar"),
                URI.create("https://subdomain.example.com/qux")
            )
    }

    @Test
    @Throws(Exception::class)
    fun commentsArePrecededByTheHashCharacter() {
        val config =
            (""
                    + "# Block everything\n"
                    + "block *\n"
                    + "# But allow example.com\n"
                    + "allow example.com")

        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val urls =
            ImmutableList.of<URI?>(URI.create("https://foo.com"), URI.create("https://example.com"))
        val amended =
            munger.amend(urls).stream().map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended).containsExactly(URI.create("https://example.com"))
    }

    @Test
    @Throws(Exception::class)
    fun allowListAppliesToSubdomainsToo() {
        val config = "" + "block *\n" + "allow example.com"

        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val amended =
            munger.amend(ImmutableList.of<URI?>(URI.create("https://subdomain.example.com"))).stream()
                .map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended).containsExactly(URI.create("https://subdomain.example.com"))
    }

    @Test
    @Throws(Exception::class)
    fun blockListAppliesToSubdomainsToo() {
        val config = "block example.com"

        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val amended =
            munger.amend(ImmutableList.of<URI?>(URI.create("https://subdomain.example.com"))).stream()
                .map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun emptyLinesAreFine() {
        val config = "" + "\n" + "   \n" + "block *\n" + "\t  \n" + "allow example.com"

        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val amended =
            munger.amend(ImmutableList.of<URI?>(URI.create("https://subdomain.example.com"))).stream()
                .map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended).containsExactly(URI.create("https://subdomain.example.com"))
    }

    @Test
    @Throws(Exception::class)
    fun rewritingUrlsIsAppliedBeforeBlocking() {
        val config = "" + "block bad.com\n" + "rewrite bad.com/foo/(.*) mycorp.com/$1"

        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val amended =
            munger
                .amend(
                    ImmutableList.of<URI?>(
                        URI.create("https://www.bad.com"), URI.create("https://bad.com/foo/bar")
                    )
                )
                .stream()
                .map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended).containsExactly(URI.create("https://mycorp.com/bar"))
    }

    @Test
    @Throws(Exception::class)
    fun rewritingUrlsIsAppliedBeforeAllowing() {
        val config =
            "" + "block *\n" + "allow mycorp.com\n" + "rewrite bad.com/foo/(.*) mycorp.com/$1"

        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val amended =
            munger
                .amend(
                    ImmutableList.of<URI?>(
                        URI.create("https://www.bad.com"), URI.create("https://bad.com/foo/bar")
                    )
                )
                .stream()
                .map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended).containsExactly(URI.create("https://mycorp.com/bar"))
    }

    @Test
    @Throws(Exception::class)
    fun parseError() {
        val config = "#comment\nhello"
        Assert.assertThrows<UrlRewriterParseException?>(
            UrlRewriterParseException::class.java,
            ThrowingRunnable { testUrlRewriter("/some/file", StringReader(config)) })
        try {
            UrlRewriterConfig("/some/file", StringReader(config))
            Assert.fail()
        } catch (e: UrlRewriterParseException) {
            Truth.assertThat<Location?>(e.location).isEqualTo(Location.fromFileLineColumn("/some/file", 2, 0))
            Truth.assertThat(e.message).contains("Unable to parse: hello")
        }
    }

    @Test
    @Throws(Exception::class)
    fun noAllBlockedMessage() {
        val config = ""
        val munger = UrlRewriterConfig("/some/file", StringReader(config))
        Truth.assertThat(munger.allBlockedMessage).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun singleAllBlockedMessage() {
        val config =
            "all_blocked_message I'm sorry Dave, I'm afraid I can't do that.\n" + "allow *\n"
        val munger = UrlRewriterConfig("/some/file", StringReader(config))
        Truth.assertThat(munger.allBlockedMessage)
            .isEqualTo("I'm sorry Dave, I'm afraid I can't do that.")
    }

    @Test
    @Throws(Exception::class)
    fun multipleAllBlockedMessage() {
        val config = "all_blocked_message one\n" + "block *\n" + "all_blocked_message two\n"
        try {
            UrlRewriterConfig("/some/file", StringReader(config))
            Assert.fail()
        } catch (e: UrlRewriterParseException) {
            Truth.assertThat<Location?>(e.location).isEqualTo(Location.fromFileLineColumn("/some/file", 3, 0))
        }
    }

    @Test
    @Throws(Exception::class)
    fun rewritingUrlsAllowsProtocolRewrite() {
        val config =
            (""
                    + "block *\n"
                    + "allow mycorp.com\n"
                    + "allow othercorp.com\n"
                    + "rewrite bad.com/foo/(.*) http://mycorp.com/$1\n"
                    + "rewrite bad.com/bar/(.*) https://othercorp.com/bar/$1\n")

        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val amended =
            munger
                .amend(
                    ImmutableList.of<URI?>(
                        URI.create("https://www.bad.com"),
                        URI.create("https://bad.com/foo/bar"),
                        URI.create("http://bad.com/bar/xyz")
                    )
                )
                .stream()
                .map<URI?> { url: RewrittenURL? -> url.url() }
                .collect(ImmutableList.toImmutableList<URI?>())

        Truth.assertThat(amended)
            .containsExactly(
                URI.create("http://mycorp.com/bar"), URI.create("https://othercorp.com/bar/xyz")
            )
    }

    @Test
    @Throws(Exception::class)
    fun rewritingUrlsWithAuthHeaders() {
        val creds = "user:password"
        val firstNetrcCreds = "netrc_user_0:netrc_pw_0"
        val secondNetrcCreds = "netrc_user_1:netrc_pw_1"
        val netrc: Credentials =
            parseNetrc(
                ("machine mycorp.com login netrc_user_0 password netrc_pw_0\n"
                        + "machine myothercorp.com login netrc_user_1 password netrc_pw_1\n"
                        + "machine no-override.com login netrc_user_2 password netrc_pw_2\n")
            )
        val config =
            (""
                    + "rewrite my.example.com/foo/(.*) "
                    + creds
                    + "@mycorp.com/foo/$1\n" // this cred should from download config file
                    + "rewrite my.example.com/from_netrc/(.*) mycorp.com/from_netrc/$1\n" // this cred
                    // should come
                    // from netrc
                    + "rewrite"
                    + " my.example.com/from_other_netrc_entry/(.*)"
                    + " myothercorp.com/from_netrc/$1\n" // this cred should come from netrc
                    + "rewrite my.example.com/no_creds/(.*) myopencorp.com/no_creds/$1\n") // should be

        // re-written,
        // but no auth
        // headers added
        val munger = testUrlRewriter("/dev/null", StringReader(config))

        val amended: ImmutableList<RewrittenURL?> =
            munger.amend(
                ImmutableList.of<URI?>(
                    URI.create("https://my.example.com/foo/bar"),
                    URI.create("https://my.example.com/from_netrc/bar"),
                    URI.create("https://my.example.com/from_other_netrc_entry/bar"),
                    URI.create("https://my.example.com/no_creds/bar"),
                    URI.create("https://should-not-be-overridden.com/")
                )
            )
        val updatedAuthHeaders =
            munger.updateAuthHeaders(
                amended,
                ImmutableMap.of<URI?, MutableMap<String?, MutableList<String?>?>?>(),
                netrc
            )

        val expectedToken =
            "Basic " + Base64.getEncoder().encodeToString(creds.toByteArray(StandardCharsets.ISO_8859_1))
        val expectedFirstNetrcToken =
            "Basic " + Base64.getEncoder().encodeToString(firstNetrcCreds.toByteArray(StandardCharsets.ISO_8859_1))
        val expectedSecondNetrcToken =
            "Basic " + Base64.getEncoder().encodeToString(secondNetrcCreds.toByteArray(StandardCharsets.ISO_8859_1))
        // only three URLs should have auth headers
        Truth.assertThat(updatedAuthHeaders)
            .containsExactly(
                URI("https://user:password@mycorp.com/foo/bar"),
                ImmutableMap.of<String?, ImmutableList<String?>?>(
                    "Authorization",
                    ImmutableList.of<String?>(expectedToken)
                ),
                URI("https://mycorp.com/from_netrc/bar"),
                ImmutableMap.of<String?, ImmutableList<String?>?>(
                    "Authorization",
                    ImmutableList.of<String?>(expectedFirstNetrcToken)
                ),
                URI("https://myothercorp.com/from_netrc/bar"),
                ImmutableMap.of<String?, ImmutableList<String?>?>(
                    "Authorization",
                    ImmutableList.of<String?>(expectedSecondNetrcToken)
                )
            )
        // yet all four urls should be present
        Truth.assertThat(amended)
            .containsExactly(
                UrlRewriter.RewrittenURL.create(
                    URI.create("https://user:password@mycorp.com/foo/bar"), true
                ),
                UrlRewriter.RewrittenURL.create(URI.create("https://mycorp.com/from_netrc/bar"), true),
                UrlRewriter.RewrittenURL.create(
                    URI.create("https://myothercorp.com/from_netrc/bar"), true
                ),
                UrlRewriter.RewrittenURL.create(
                    URI.create("https://myopencorp.com/no_creds/bar"), true
                ),
                UrlRewriter.RewrittenURL.create(
                    URI.create("https://should-not-be-overridden.com/"), false
                )
            )
    }

    @Test
    @Throws(Exception::class)
    fun testNetrc_emptyEnv_shouldIgnore() {
        val clientEnv = ImmutableMap.of<String?, String?>()
        val workingDir: Path? = InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/workdir")

        val credentials = UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir)

        Truth.assertThat(credentials).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testNetrc_netrcNotExist_shouldIgnore() {
        val home = "/home/foo"
        val clientEnv = ImmutableMap.of<String?, String?>("HOME", home, "USERPROFILE", home)
        val workingDir: Path? = InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/workdir")

        val credentials = UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir)

        Truth.assertThat(credentials).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testNetrc_relativeNetrc_shouldUse() {
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val workingDir: Path? = fileSystem.getPath("/workdir")
        val scratch: Scratch = Scratch(fileSystem)
        scratch.file("/workdir/foo/.netrc", "machine foo.example.org login foouser password foopass")
        val clientEnv = ImmutableMap.of<String?, String?>("NETRC", "./foo/.netrc")

        val credentials: Credentials = UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir)!!

        assertRequestMetadata(
            credentials.getRequestMetadata(URI.create("https://foo.example.org")),
            "foouser",
            "foopass"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testNetrc_relativeNetrc_shouldIgnoreWhenNotExist() {
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val workingDir: Path? = fileSystem.getPath("/workdir")
        val clientEnv = ImmutableMap.of<String?, String?>("NETRC", "./foo/.netrc")

        val credentials = UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir)

        Truth.assertThat(credentials).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testNetrc_netrcExist_shouldUse() {
        val credentials: Credentials = parseNetrc("machine foo.example.org login foouser password foopass")

        Truth.assertThat(credentials).isNotNull()
        assertRequestMetadata(
            credentials.getRequestMetadata(URI.create("https://foo.example.org")),
            "foouser",
            "foopass"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testNetrc_netrcExist_cant_parse() {
        val home = "/home/foo"
        val clientEnv = ImmutableMap.of<String?, String?>("HOME", home, "USERPROFILE", home)
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val scratch: Scratch = Scratch(fileSystem)
        scratch.file(home + "/.netrc", "mach foo.example.org log foouser password foopass")

        try {
            UrlRewriter.newCredentialsFromNetrc(clientEnv, fileSystem.getPath("/workdir"))
            Assert.fail()
        } catch (e: UrlRewriterParseException) {
            Truth.assertThat<Location?>(e.location).isEqualTo(Location.fromFileLineColumn("/home/foo/.netrc", 0, 0))
        }
    }

    companion object {
        @Throws(IOException::class, UrlRewriterParseException::class)
        private fun parseNetrc(content: String?): Credentials {
            val home = "/home/foo"
            val clientEnv = ImmutableMap.of<String?, String?>("HOME", home, "USERPROFILE", home)
            val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
            val workingDir: Path? = fileSystem.getPath("/workdir")
            val scratch: Scratch = Scratch(fileSystem)
            scratch.file(home + "/.netrc", content)

            return UrlRewriter.newCredentialsFromNetrc(clientEnv, workingDir)!!
        }

        private fun assertRequestMetadata(
            requestMetadata: MutableMap<String?, MutableList<String?>?>, username: String?, password: String?
        ) {
            Truth.assertThat(requestMetadata.keys).containsExactly("Authorization")
            Truth.assertThat(Iterables.getOnlyElement<MutableList<String?>?>(requestMetadata.values))
                .containsExactly(BasicHttpAuthenticationEncoder.encode(username, password))
        }
    }
}
