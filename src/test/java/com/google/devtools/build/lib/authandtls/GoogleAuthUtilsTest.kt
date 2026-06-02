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

import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment

@RunWith(JUnit4::class)
class GoogleAuthUtilsTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNetrc_emptyEnv_shouldIgnore() {
        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>()
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

        assertThat(GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNetrc_netrcNotExist_shouldIgnore() {
        val home = "/home/foo"
        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("HOME", home)
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

        assertThat(GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNetrc_netrcExist_shouldUse() {
        val home = "/home/foo"
        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("HOME", home)
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val scratch: Scratch = Scratch(fileSystem)
        scratch.file(home + "/.netrc", "machine foo.example.org login foouser password foopass")

        val credentials: java.util.Optional<com.google.auth.Credentials?>? =
            GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem)

        Truth.assertThat(credentials).isPresent()
        assertRequestMetadata(
            credentials.get().getRequestMetadata(java.net.URI.create("https://foo.example.org")),
            "foouser",
            "foopass"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNetrc_netrcFromNetrcEnvExist_shouldUse() {
        val home = "/home/foo"
        val netrc = "/.netrc"
        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("HOME", home, "NETRC", netrc)
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val scratch: Scratch = Scratch(fileSystem)
        scratch.file(home + "/.netrc", "machine foo.example.org login foouser password foopass")
        scratch.file(netrc, "machine foo.example.org login baruser password barpass")

        val credentials: java.util.Optional<com.google.auth.Credentials?>? =
            GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem)

        Truth.assertThat(credentials).isPresent()
        assertRequestMetadata(
            credentials.get().getRequestMetadata(java.net.URI.create("https://foo.example.org")),
            "baruser",
            "barpass"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNetrc_netrcFromNetrcEnvNotExist_shouldIgnore() {
        val home = "/home/foo"
        val netrc = "/.netrc"
        val clientEnv: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("HOME", home, "NETRC", netrc)
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
        val scratch: Scratch = Scratch(fileSystem)
        scratch.file(home + "/.netrc", "machine foo.example.org login foouser password foopass")

        assertThat(GoogleAuthUtils.newCredentialsFromNetrc(clientEnv, fileSystem)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCredentialHelperProvider() {
        val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

        val workspace: Path = fileSystem.getPath("/workspace")
        val pathValue: Path = fileSystem.getPath("/usr/local/bin")
        pathValue.createDirectoryAndParents()

        val credentialHelperEnvironment: CredentialHelperEnvironment? =
            CredentialHelperEnvironment.newBuilder()
                .setEventReporter(com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus()))
                .setWorkspacePath(workspace)
                .setClientEnvironment(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "PATH",
                        pathValue.getPathString()
                    )
                )
                .setHelperExecutionTimeout(java.time.Duration.ZERO)
                .build()
        val commandLinePathFactory: CommandLinePathFactory =
            CommandLinePathFactory(
                fileSystem,
                com.google.common.collect.ImmutableMap.of<K?, V?>("workspace", workspace)
            )

        val unusedHelper: Path = createExecutable(fileSystem, "/unused/helper")

        val defaultHelper: Path = createExecutable(fileSystem, "/default/helper")
        val exampleComHelper: Path = createExecutable(fileSystem, "/example/com/helper")
        val fooExampleComHelper: Path = createExecutable(fileSystem, "/foo/example/com/helper")
        val exampleComWildcardHelper: Path = createExecutable(fileSystem, "/example/com/wildcard/helper")

        val exampleOrgHelper: Path = createExecutable(workspace.getRelative("helpers/example-org"))

        // No helpers.
        val credentialHelperProvider1: CredentialHelperProvider =
            newCredentialHelperProvider(
                credentialHelperEnvironment,
                commandLinePathFactory,
                com.google.common.collect.ImmutableList.of<String?>()
            )
        assertThat(credentialHelperProvider1.findCredentialHelper(java.net.URI.create("https://example.com")))
            .isEmpty()
        assertThat(
            credentialHelperProvider1.findCredentialHelper(java.net.URI.create("https://foo.example.com"))
        )
            .isEmpty()

        // Default helper only.
        val credentialHelperProvider2: CredentialHelperProvider =
            newCredentialHelperProvider(
                credentialHelperEnvironment,
                commandLinePathFactory,
                com.google.common.collect.ImmutableList.of<E?>(defaultHelper.getPathString())
            )
        assertThat(
            credentialHelperProvider2
                .findCredentialHelper(java.net.URI.create("https://example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(defaultHelper)
        assertThat(
            credentialHelperProvider2
                .findCredentialHelper(java.net.URI.create("https://foo.example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(defaultHelper)

        // Default and exact match.
        val credentialHelperProvider3: CredentialHelperProvider =
            newCredentialHelperProvider(
                credentialHelperEnvironment,
                commandLinePathFactory,
                com.google.common.collect.ImmutableList.of<E?>(
                    defaultHelper.getPathString(), "example.com=" + exampleComHelper.getPathString()
                )
            )
        assertThat(
            credentialHelperProvider3
                .findCredentialHelper(java.net.URI.create("https://example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComHelper)
        assertThat(
            credentialHelperProvider3
                .findCredentialHelper(java.net.URI.create("https://foo.example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(defaultHelper)

        // Exact match without default.
        val credentialHelperProvider4: CredentialHelperProvider =
            newCredentialHelperProvider(
                credentialHelperEnvironment,
                commandLinePathFactory,
                com.google.common.collect.ImmutableList.of<String?>("example.com=" + exampleComHelper.getPathString())
            )
        assertThat(
            credentialHelperProvider4
                .findCredentialHelper(java.net.URI.create("https://example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComHelper)
        assertThat(
            credentialHelperProvider4.findCredentialHelper(java.net.URI.create("https://foo.example.com"))
        )
            .isEmpty()

        // Multiple scoped helpers with default.
        val credentialHelperProvider5: CredentialHelperProvider =
            newCredentialHelperProvider(
                credentialHelperEnvironment,
                commandLinePathFactory,
                com.google.common.collect.ImmutableList.of<E?>(
                    defaultHelper.getPathString(),
                    "example.com=" + exampleComHelper.getPathString(),
                    "*.foo.example.com=" + fooExampleComHelper.getPathString(),
                    "*.example.com=" + exampleComWildcardHelper.getPathString(),
                    "example.org=%workspace%/helpers/example-org"
                )
            )
        assertThat(
            credentialHelperProvider5
                .findCredentialHelper(java.net.URI.create("https://anotherdomain.com"))
                .get()
                .getPath()
        )
            .isEqualTo(defaultHelper)
        assertThat(
            credentialHelperProvider5
                .findCredentialHelper(java.net.URI.create("https://example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComHelper)
        assertThat(
            credentialHelperProvider5
                .findCredentialHelper(java.net.URI.create("https://foo.example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(fooExampleComHelper)
        assertThat(
            credentialHelperProvider5
                .findCredentialHelper(java.net.URI.create("https://abc.foo.example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(fooExampleComHelper)
        assertThat(
            credentialHelperProvider5
                .findCredentialHelper(java.net.URI.create("https://bar.example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            credentialHelperProvider5
                .findCredentialHelper(java.net.URI.create("https://abc.bar.example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            credentialHelperProvider5
                .findCredentialHelper(java.net.URI.create("https://example.org"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleOrgHelper)

        // Helpers override.
        val credentialHelperProvider6: CredentialHelperProvider =
            newCredentialHelperProvider(
                credentialHelperEnvironment,
                commandLinePathFactory,
                com.google.common.collect.ImmutableList.of<E?>( // <system .bazelrc>
                    unusedHelper.getPathString(),  // <user .bazelrc>

                    defaultHelper.getPathString(),
                    "example.com=" + unusedHelper.getPathString(),
                    "*.example.com=" + unusedHelper.getPathString(),
                    "example.org=" + unusedHelper.getPathString(),
                    "*.example.org=" + exampleOrgHelper.getPathString(),  // <workspace .bazelrc>

                    "*.example.com=" + exampleComWildcardHelper.getPathString(),
                    "example.org=" + exampleOrgHelper.getPathString(),
                    "*.foo.example.com=" + unusedHelper.getPathString(),  // <command-line>

                    "example.com=" + exampleComHelper.getPathString(),
                    "*.foo.example.com=" + fooExampleComHelper.getPathString()
                )
            )
        assertThat(
            credentialHelperProvider6
                .findCredentialHelper(java.net.URI.create("https://anotherdomain.com"))
                .get()
                .getPath()
        )
            .isEqualTo(defaultHelper)
        assertThat(
            credentialHelperProvider6
                .findCredentialHelper(java.net.URI.create("https://example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComHelper)
        assertThat(
            credentialHelperProvider6
                .findCredentialHelper(java.net.URI.create("https://foo.example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(fooExampleComHelper)
        assertThat(
            credentialHelperProvider6
                .findCredentialHelper(java.net.URI.create("https://bar.example.com"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleComWildcardHelper)
        assertThat(
            credentialHelperProvider6
                .findCredentialHelper(java.net.URI.create("https://example.org"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleOrgHelper)
        assertThat(
            credentialHelperProvider6
                .findCredentialHelper(java.net.URI.create("https://foo.example.org"))
                .get()
                .getPath()
        )
            .isEqualTo(exampleOrgHelper)
    }

    companion object {
        @Throws(IOException::class)
        private fun createExecutable(fileSystem: FileSystem?, path: String?): Path {
            com.google.common.base.Preconditions.checkNotNull<Any?>(fileSystem)
            com.google.common.base.Preconditions.checkNotNull<String?>(path)

            return createExecutable(fileSystem.getPath(path))
        }

        @Throws(IOException::class)
        private fun createExecutable(path: Path): Path {
            com.google.common.base.Preconditions.checkNotNull<Any?>(path)

            path.getParentDirectory().createDirectoryAndParents()
            path.getOutputStream().use { unused -> }
            path.setExecutable(true)

            return path
        }

        private fun assertRequestMetadata(
            requestMetadata: MutableMap<String?, MutableList<String?>?>, username: String?, password: String?
        ) {
            Truth.assertThat(requestMetadata.keys).containsExactly("Authorization")
            Truth.assertThat(com.google.common.collect.Iterables.getOnlyElement<MutableList<String?>?>(requestMetadata.values))
                .containsExactly(BasicHttpAuthenticationEncoder.encode(username, password))
        }

        @Throws(java.lang.Exception::class)
        private fun newCredentialHelperProvider(
            credentialHelperEnvironment: CredentialHelperEnvironment?,
            commandLinePathFactory: CommandLinePathFactory?,
            inputs: com.google.common.collect.ImmutableList<String?>?
        ): CredentialHelperProvider {
            com.google.common.base.Preconditions.checkNotNull<Any?>(credentialHelperEnvironment)
            com.google.common.base.Preconditions.checkNotNull<Any?>(commandLinePathFactory)
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>?>(inputs)

            return GoogleAuthUtils.newCredentialHelperProvider(
                credentialHelperEnvironment,
                commandLinePathFactory,
                com.google.common.collect.ImmutableList.< E > copyOf < E ? > (com.google.common.collect.Iterables.transform<F?, T?>(
                    inputs,
                    com.google.common.base.Function { s: F? -> createCredentialHelperOption(s) }))
            )
        }

        private fun createCredentialHelperOption(
            input: String?
        ): AuthAndTLSOptions.CredentialHelperOption {
            com.google.common.base.Preconditions.checkNotNull<String?>(input)

            try {
                return AuthAndTLSOptions.CredentialHelperOptionConverter.INSTANCE.convert(input)
            } catch (e: OptionsParsingException) {
                throw java.lang.IllegalStateException(e)
            }
        }
    }
}
