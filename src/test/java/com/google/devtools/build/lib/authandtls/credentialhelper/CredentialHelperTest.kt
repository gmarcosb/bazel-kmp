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
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.events.EventBusEventHandler
import com.google.devtools.build.lib.events.Reporter
import com.google.devtools.build.lib.util.OS
import com.google.devtools.build.lib.vfs.util.FileSystems
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.net.URI
import java.time.Duration

@RunWith(JUnit4::class)
class CredentialHelperTest {
    @Throws(Exception::class)
    private fun getCredentialsFromHelper(
        credHelperPath: String?, uri: String?, env: ImmutableMap<String?, String?>?
    ): GetCredentialsResponse {
        Preconditions.checkNotNull<String?>(credHelperPath)
        Preconditions.checkNotNull<String?>(uri)
        Preconditions.checkNotNull<ImmutableMap<String?, String?>?>(env)

        val fs: FileSystem = FileSystems.getNativeFileSystem()

        val credentialHelper: CredentialHelper = CredentialHelper(fs.getPath(credHelperPath))
        val clientEnv: SequencedMap<String?, String?> = LinkedHashMap<String?, String?>(System.getenv())
        // Don't cd to the Python credential helper's temporary directory on Windows, which would throw
        // off test assertions. This variable is set to "1" by the surrounding Java test.
        clientEnv.remove("RUN_UNDER_RUNFILES")
        clientEnv.putAll(env)
        return credentialHelper.getCredentials(
            CredentialHelperEnvironment.newBuilder()
                .setEventReporter(reporter)
                .setWorkspacePath(fs.getPath(TEST_WORKSPACE_PATH))
                .setClientEnvironment(ImmutableMap.< K, V > copyOf<K?, V?>(clientEnv))
                .setHelperExecutionTimeout(Duration.ofSeconds(5))
                .build(),
            URI.create(uri)
        )
    }

    @Throws(Exception::class)
    private fun getCredentialsFromHelper(
        uri: String?, env: ImmutableMap<String?, String?>?
    ): GetCredentialsResponse {
        val credHelperPath: String? =
            Runfiles.preload()
                .withSourceRepository("")
                .rlocation(TEST_CREDENTIAL_HELPER_PATH.getPathString())

        return getCredentialsFromHelper(credHelperPath, uri, env)
    }

    @Throws(Exception::class)
    private fun getCredentialsFromHelper(uri: String?): GetCredentialsResponse {
        Preconditions.checkNotNull<String?>(uri)

        return getCredentialsFromHelper(uri, ImmutableMap.of<String?, String?>())
    }

    @Test
    @Throws(Exception::class)
    fun knownUriWithSingleHeader() {
        val response: GetCredentialsResponse = getCredentialsFromHelper("https://singleheader.example.com")
        assertThat(response.headers()).containsExactly("header1", ImmutableList.of<E?>("value1"))
    }

    @Test
    @Throws(Exception::class)
    fun knownUriWithMultipleHeaders() {
        val response: GetCredentialsResponse =
            getCredentialsFromHelper("https://multipleheaders.example.com")
        assertThat(response.headers())
            .containsExactly(
                "header1",
                ImmutableList.of<E?>("value1"),
                "header2",
                ImmutableList.of<E?>("value1", "value2"),
                "header3",
                ImmutableList.of<E?>("value1", "value2", "value3")
            )
    }

    @Test
    fun unknownUri() {
        val e: CredentialHelperException? =
            Assert.assertThrows<T?>(
                CredentialHelperException::class.java,
                ThrowingRunnable { getCredentialsFromHelper("https://unknown.example.com") })
        assertThat(e).hasMessageThat().contains("Failed to get credentials")
        assertThat(e).hasMessageThat().contains("Unknown uri 'https://unknown.example.com'")
    }

    @Test
    @Throws(Exception::class)
    fun credentialHelperOutputsNothing() {
        val e: CredentialHelperException? =
            Assert.assertThrows<T?>(
                CredentialHelperException::class.java,
                ThrowingRunnable { getCredentialsFromHelper("https://printnothing.example.com") })
        assertThat(e).hasMessageThat().contains("Failed to get credentials")
        assertThat(e).hasMessageThat().contains("exited without output")
    }

    @Test
    @Throws(Exception::class)
    fun credentialHelperOutputsExtraFields() {
        val response: GetCredentialsResponse = getCredentialsFromHelper("https://extrafields.example.com")
        assertThat(response.headers()).containsExactly("header1", ImmutableList.of<E?>("value1"))
    }

    @Test
    @Throws(Exception::class)
    fun helperRunsInWorkspace() {
        val response: GetCredentialsResponse = getCredentialsFromHelper("https://cwd.example.com")
        val headers: ImmutableMap<String?, ImmutableList<String?>?> = response.headers()
        assertThat(PathFragment.create(headers.get("cwd")!!.get(0))).isEqualTo(TEST_WORKSPACE_PATH)
    }

    @Test
    @Throws(Exception::class)
    fun helperGetEnvironment() {
        val response: GetCredentialsResponse =
            getCredentialsFromHelper(
                "https://env.example.com", ImmutableMap.of<String?, String?>("FOO", "BAR!", "BAR", "123")
            )
        assertThat(response.headers())
            .containsExactly(
                "foo", ImmutableList.of<E?>("BAR!"),
                "bar", ImmutableList.of<E?>("123")
            )
    }

    @Test
    @Throws(Exception::class)
    fun helperTimeout() {
        val e: CredentialHelperException? =
            Assert.assertThrows<T?>(
                CredentialHelperException::class.java,
                ThrowingRunnable { getCredentialsFromHelper("https://timeout.example.com") })
        assertThat(e).hasMessageThat().contains("Failed to get credentials")
        assertThat(e).hasMessageThat().contains("process timed out")
    }

    @Test
    @Throws(Exception::class)
    fun nonExistentHelper() {
        val e: CredentialHelperException? =
            Assert.assertThrows<T?>(
                CredentialHelperException::class.java,
                ThrowingRunnable {
                    getCredentialsFromHelper(
                        if (OS.getCurrent() == OS.WINDOWS) "C:/no/such/file" else "/no/such/file",
                        "https://timeout.example.com",
                        ImmutableMap.of<String?, String?>()
                    )
                })
        assertThat(e).hasMessageThat().contains("Failed to get credentials")
        assertThat(e)
            .hasMessageThat()
            .contains(
                if (OS.getCurrent() == OS.WINDOWS)
                    "cannot find the file specified"
                else
                    "Cannot run program"
            )
    }

    @Test
    @Throws(Exception::class)
    fun hugePayload() {
        // Bazel reads the credential helper stdout/stderr from a pipe, and doesn't start reading
        // until the process terminates. Therefore, a response larger than the pipe buffer causes
        // a deadlock and timeout. This verifies that the pipe is sufficiently large.
        // See https://github.com/bazelbuild/bazel/issues/21287.
        val response: GetCredentialsResponse = getCredentialsFromHelper("https://hugepayload.example.com")
        assertThat(response.headers()).containsExactly("huge", ImmutableList.of<E?>("x".repeat(63 * 1024)))
    }

    companion object {
        init {
            WindowsSubprocessFactory.maybeInstallWindowsSubprocessFactory()
        }

        private val TEST_WORKSPACE_PATH: PathFragment? = PathFragment.create(System.getenv("TEST_TMPDIR"))
        private val TEST_CREDENTIAL_HELPER_PATH: PathFragment = PathFragment.create(
            "io_bazel/src/test/java/com/google/devtools/build/lib/authandtls/credentialhelper/test_credential_helper"
                    + (if (OS.getCurrent() == OS.WINDOWS) ".exe" else "")
        )

        private val reporter = Reporter(EventBusEventHandler.createWithNewEventBus())
    }
}
