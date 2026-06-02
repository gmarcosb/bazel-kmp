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
package com.google.devtools.build.lib.authandtls.credentialhelper

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.vfs.DigestHashFunction
import org.junit.Test
import java.net.URI

/** Tests for [CredentialHelperProvider].  */
@RunWith(JUnit4::class)
class CredentialHelperProviderTest {
    private val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    @Before
    @Throws(Exception::class)
    fun setUp() {
        setUpHelper(fileSystem.getPath(DEFAULT_HELPER_PATH))
        setUpHelper(fileSystem.getPath(EXAMPLE_COM_HELPER_PATH))
        setUpHelper(fileSystem.getPath(EXAMPLE_COM_WILDCARD_HELPER_PATH))
        setUpHelper(fileSystem.getPath(SUB_EXAMPLE_COM_WILDCARD_HELPER_PATH))
    }

    @Throws(Exception::class)
    private fun setUpHelper(path: Path?) {
        Preconditions.checkNotNull<Any?>(path)

        path.getParentDirectory().createDirectoryAndParents()
        path.getOutputStream().use { stream -> }
        path.setExecutable(true)
    }

    @Test
    fun noHelpersConfigured() {
        val provider: CredentialHelperProvider = CredentialHelperProvider.builder().build()

        assertThat(provider.findCredentialHelper(URI.create("http://example.com/foo"))).isEmpty()
        assertThat(provider.findCredentialHelper(URI.create("https://example.com/foo"))).isEmpty()
        assertThat(provider.findCredentialHelper(URI.create("grpc://example.com/foo"))).isEmpty()
        assertThat(provider.findCredentialHelper(URI.create("grpcs://example.com/foo"))).isEmpty()
        assertThat(provider.findCredentialHelper(URI.create("custom://example.com/foo"))).isEmpty()

        assertThat(provider.findCredentialHelper(URI.create("https://subdomain.example.com/bar")))
            .isEmpty()
        assertThat(provider.findCredentialHelper(URI.create("https://other-domain.com"))).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun uriWithoutHostComponent() {
        val helper: Path? = fileSystem.getPath(EXAMPLE_COM_HELPER_PATH)
        val provider: CredentialHelperProvider =
            CredentialHelperProvider.builder().add("example.com", helper).build()

        assertThat(provider.findCredentialHelper(URI.create("unix:///path/to/socket"))).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun onlyDefaultHelper() {
        val helper: Path? = fileSystem.getPath(DEFAULT_HELPER_PATH)
        val provider: CredentialHelperProvider = CredentialHelperProvider.builder().add(helper).build()

        assertThat(provider.findCredentialHelper(URI.create("http://example.com/foo")).get().getPath())
            .isEqualTo(helper)
        assertThat(provider.findCredentialHelper(URI.create("https://example.com/foo")).get().getPath())
            .isEqualTo(helper)
        assertThat(provider.findCredentialHelper(URI.create("grpc://example.com/foo")).get().getPath())
            .isEqualTo(helper)
        assertThat(provider.findCredentialHelper(URI.create("grpcs://example.com/foo")).get().getPath())
            .isEqualTo(helper)
        assertThat(provider.findCredentialHelper(URI.create("unix:///tmp/grpc.sock")).get().getPath())
            .isEqualTo(helper)
        assertThat(
            provider.findCredentialHelper(URI.create("custom://example.com/foo")).get().getPath()
        )
            .isEqualTo(helper)

        assertThat(
            provider
                .findCredentialHelper(URI.create("https://subdomain.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(helper)
        assertThat(
            provider.findCredentialHelper(URI.create("https://other-domain.com")).get().getPath()
        )
            .isEqualTo(helper)
    }

    @Test
    @Throws(Exception::class)
    fun withHostHelpersAndDefaultFallback() {
        val defaultHelper: Path? = fileSystem.getPath(DEFAULT_HELPER_PATH)
        val exampleComHelper: Path? = fileSystem.getPath(EXAMPLE_COM_HELPER_PATH)
        val provider: CredentialHelperProvider =
            CredentialHelperProvider.builder()
                .add(defaultHelper)
                .add("example.com", exampleComHelper)
                .build()

        assertThat(provider.findCredentialHelper(URI.create("http://example.com/foo")).get().getPath())
            .isEqualTo(exampleComHelper)
        assertThat(provider.findCredentialHelper(URI.create("https://example.com/foo")).get().getPath())
            .isEqualTo(exampleComHelper)
        assertThat(provider.findCredentialHelper(URI.create("grpc://example.com/foo")).get().getPath())
            .isEqualTo(exampleComHelper)
        assertThat(provider.findCredentialHelper(URI.create("grpcs://example.com/foo")).get().getPath())
            .isEqualTo(exampleComHelper)
        assertThat(
            provider.findCredentialHelper(URI.create("custom://example.com/foo")).get().getPath()
        )
            .isEqualTo(exampleComHelper)

        assertThat(
            provider
                .findCredentialHelper(URI.create("https://subdomain.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(defaultHelper)
        assertThat(
            provider.findCredentialHelper(URI.create("https://other-domain.com")).get().getPath()
        )
            .isEqualTo(defaultHelper)
        assertThat(provider.findCredentialHelper(URI.create("unix:///tmp/grpc.sock")).get().getPath())
            .isEqualTo(defaultHelper)
    }

    @Test
    @Throws(Exception::class)
    fun wildcardMatching() {
        val defaultHelper: Path? = fileSystem.getPath(DEFAULT_HELPER_PATH)
        val exampleComWildcardHelper: Path? = fileSystem.getPath(EXAMPLE_COM_WILDCARD_HELPER_PATH)
        val provider: CredentialHelperProvider =
            CredentialHelperProvider.builder()
                .add(defaultHelper)
                .add("*.example.com", exampleComWildcardHelper)
                .build()

        assertThat(provider.findCredentialHelper(URI.create("http://example.com/foo")).get().getPath())
            .isEqualTo(exampleComWildcardHelper)
        assertThat(provider.findCredentialHelper(URI.create("https://example.com/foo")).get().getPath())
            .isEqualTo(exampleComWildcardHelper)
        assertThat(provider.findCredentialHelper(URI.create("grpc://example.com/foo")).get().getPath())
            .isEqualTo(exampleComWildcardHelper)
        assertThat(provider.findCredentialHelper(URI.create("grpcs://example.com/foo")).get().getPath())
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            provider.findCredentialHelper(URI.create("custom://example.com/foo")).get().getPath()
        )
            .isEqualTo(exampleComWildcardHelper)

        assertThat(
            provider
                .findCredentialHelper(URI.create("https://subdomain.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            provider
                .findCredentialHelper(URI.create("https://subdomain2.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            provider
                .findCredentialHelper(URI.create("https://sub.subdomain.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            provider
                .findCredentialHelper(URI.create("https://subdomain.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)

        assertThat(
            provider.findCredentialHelper(URI.create("https://other-domain.com")).get().getPath()
        )
            .isEqualTo(defaultHelper)

        assertThat(provider.findCredentialHelper(URI.create("unix:///tmp/grpc.sock")).get().getPath())
            .isEqualTo(defaultHelper)
    }

    @Test
    @Throws(Exception::class)
    fun preferExactMatchOverWildcardMatching() {
        val defaultHelper: Path? = fileSystem.getPath(DEFAULT_HELPER_PATH)
        val exampleComHelper: Path? = fileSystem.getPath(EXAMPLE_COM_HELPER_PATH)
        val exampleComWildcardHelper: Path? = fileSystem.getPath(EXAMPLE_COM_WILDCARD_HELPER_PATH)
        val provider: CredentialHelperProvider =
            CredentialHelperProvider.builder()
                .add(defaultHelper)
                .add("example.com", exampleComHelper)
                .add("*.example.com", exampleComWildcardHelper)
                .build()

        assertThat(provider.findCredentialHelper(URI.create("http://example.com/foo")).get().getPath())
            .isEqualTo(exampleComHelper)
        assertThat(provider.findCredentialHelper(URI.create("https://example.com/foo")).get().getPath())
            .isEqualTo(exampleComHelper)
        assertThat(provider.findCredentialHelper(URI.create("grpc://example.com/foo")).get().getPath())
            .isEqualTo(exampleComHelper)
        assertThat(provider.findCredentialHelper(URI.create("grpcs://example.com/foo")).get().getPath())
            .isEqualTo(exampleComHelper)
        assertThat(
            provider.findCredentialHelper(URI.create("custom://example.com/foo")).get().getPath()
        )
            .isEqualTo(exampleComHelper)

        assertThat(
            provider
                .findCredentialHelper(URI.create("https://subdomain.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            provider
                .findCredentialHelper(URI.create("https://subdomain2.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            provider
                .findCredentialHelper(URI.create("https://sub.subdomain.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            provider
                .findCredentialHelper(URI.create("https://subdomain.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)

        assertThat(
            provider.findCredentialHelper(URI.create("https://other-domain.com")).get().getPath()
        )
            .isEqualTo(defaultHelper)
    }

    @Test
    @Throws(Exception::class)
    fun preferMostSpecificWildcardMatch() {
        val exampleComWildcardHelper: Path? = fileSystem.getPath(EXAMPLE_COM_WILDCARD_HELPER_PATH)
        val subExampleComWildcardHelper: Path? = fileSystem.getPath(SUB_EXAMPLE_COM_WILDCARD_HELPER_PATH)
        val provider: CredentialHelperProvider =
            CredentialHelperProvider.builder()
                .add("*.example.com", exampleComWildcardHelper)
                .add("*.sub.example.com", subExampleComWildcardHelper)
                .build()

        assertThat(provider.findCredentialHelper(URI.create("https://example.com/bar")).get().getPath())
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            provider
                .findCredentialHelper(URI.create("https://foo.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            provider
                .findCredentialHelper(URI.create("https://sub.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(subExampleComWildcardHelper)
        assertThat(
            provider
                .findCredentialHelper(URI.create("https://foo.sub.example.com/bar"))
                .get()
                .getPath()
        )
            .isEqualTo(subExampleComWildcardHelper)
    }

    @Test
    fun parentDomain() {
        assertThat(CredentialHelperProvider.parentDomain("com")).isEmpty()

        assertThat(CredentialHelperProvider.parentDomain("foo.example.com")).hasValue("example.com")
        assertThat(CredentialHelperProvider.parentDomain("example.com")).hasValue("com")

        // Punycode URIs (münchen.de).
        assertThat(CredentialHelperProvider.parentDomain("foo.xn--mnchen-3ya.de"))
            .hasValue("xn--mnchen-3ya.de")
        assertThat(CredentialHelperProvider.parentDomain("bar.foo.xn--mnchen-3ya.de"))
            .hasValue("foo.xn--mnchen-3ya.de")
        assertThat(CredentialHelperProvider.parentDomain("xn--mnchen-3ya.de")).hasValue("de")
    }

    companion object {
        private val DEFAULT_HELPER_PATH: PathFragment? = PathFragment.create("/path/to/default/helper")
        private val EXAMPLE_COM_HELPER_PATH: PathFragment? = PathFragment.create("/path/to/example/com/helper")
        private val EXAMPLE_COM_WILDCARD_HELPER_PATH: PathFragment? =
            PathFragment.create("/path/to/example/com/wildcard/helper")
        private val SUB_EXAMPLE_COM_WILDCARD_HELPER_PATH: PathFragment? =
            PathFragment.create("/path/to/sub/example/com/wildcard/helper")
    }
}
