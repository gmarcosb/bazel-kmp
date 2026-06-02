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

import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperException
import com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsRequest
import com.google.devtools.build.lib.authandtls.credentialhelper.GetCredentialsResponse
import com.google.devtools.build.lib.shell.Subprocess
import com.google.devtools.build.lib.shell.SubprocessBuilder
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.Locale

/** Wraps an external tool used to obtain credentials.  */
@com.google.errorprone.annotations.Immutable
class CredentialHelper internal constructor(path: com.google.devtools.build.lib.vfs.Path?) {
    // `Path` is immutable, but not annotated.
    private val path: com.google.devtools.build.lib.vfs.Path

    init {
        this.path = com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path>(path)
    }

    @com.google.common.annotations.VisibleForTesting
    fun getPath(): com.google.devtools.build.lib.vfs.Path {
        return path
    }

    /**
     * Fetches credentials for the specified [URI] by invoking the credential helper as
     * subprocess according to the [credential
     * helper protocol](https://github.com/bazelbuild/proposals/blob/main/designs/2022-06-07-bazel-credential-helpers.md).
     * 
     * @param environment The environment to run the subprocess in.
     * @param uri The [URI] to fetch credentials for.
     * @return The response from the subprocess.
     */
    @Throws(IOException::class)
    fun getCredentials(environment: CredentialHelperEnvironment?, uri: java.net.URI?): GetCredentialsResponse {
        com.google.common.base.Preconditions.checkNotNull<CredentialHelperEnvironment?>(environment)
        com.google.common.base.Preconditions.checkNotNull<java.net.URI?>(uri)

        com.google.devtools.build.lib.profiler.Profiler.instance()
            .profile(com.google.devtools.build.lib.profiler.ProfilerTask.CREDENTIAL_HELPER, "calling credential helper")
            .use { c ->
                val process: Subprocess
                try {
                    process = spawnSubprocess(environment, "get")
                } catch (e: IOException) {
                    throw CredentialHelperException(
                        java.lang.String.format(
                            Locale.US,
                            "Failed to get credentials for '%s' from helper '%s': %s",
                            uri,
                            path,
                            e.getMessage()
                        )
                    )
                }
                java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)
                    .use { stdout ->
                        java.io.InputStreamReader(process.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8)
                            .use { stderr ->
                                try {
                                    OutputStreamWriter(
                                        process.getOutputStream(),
                                        java.nio.charset.StandardCharsets.UTF_8
                                    ).use { stdin ->
                                        GSON.toJson(
                                            GetCredentialsRequest.Companion.newBuilder().setUri(uri).build(),
                                            stdin
                                        )
                                    }
                                } catch (e: IOException) {
                                    // This can happen if the helper prints a static set of credentials without reading from
                                    // stdin (e.g., with a simple shell script running `echo "{...}"`). This is fine to
                                    // ignore.
                                }
                                try {
                                    process.waitFor()
                                } catch (e: java.lang.InterruptedException) {
                                    throw CredentialHelperException(
                                        java.lang.String.format(
                                            Locale.US,
                                            "Failed to get credentials for '%s' from helper '%s': process was interrupted",
                                            uri,
                                            path
                                        )
                                    )
                                }

                                if (process.timedout()) {
                                    throw CredentialHelperException(
                                        java.lang.String.format(
                                            Locale.US,
                                            "Failed to get credentials for '%s' from helper '%s': process timed out",
                                            uri,
                                            path
                                        )
                                    )
                                }
                                if (process.exitValue() != 0) {
                                    throw CredentialHelperException(
                                        java.lang.String.format(
                                            Locale.US,
                                            "Failed to get credentials for '%s' from helper '%s': process exited with code"
                                                    + " %d. stderr: %s",
                                            uri,
                                            path,
                                            process.exitValue(),
                                            com.google.common.io.CharStreams.toString(stderr)
                                        )
                                    )
                                }
                                try {
                                    val response: GetCredentialsResponse = GSON.fromJson<GetCredentialsResponse>(
                                        stdout,
                                        GetCredentialsResponse::class.java
                                    )
                                    if (response == null) {
                                        throw CredentialHelperException(
                                            java.lang.String.format(
                                                Locale.US,
                                                "Failed to get credentials for '%s' from helper '%s': process exited without"
                                                        + " output. stderr: %s",
                                                uri,
                                                path,
                                                com.google.common.io.CharStreams.toString(stderr)
                                            )
                                        )
                                    }
                                    return response
                                } catch (e: JsonSyntaxException) {
                                    throw CredentialHelperException(
                                        java.lang.String.format(
                                            Locale.US,
                                            "Failed to get credentials for '%s' from helper '%s': error parsing output."
                                                    + " stderr: %s",
                                            uri,
                                            path,
                                            com.google.common.io.CharStreams.toString(stderr)
                                        ),
                                        e
                                    )
                                }
                            }
                    }
            }
    }

    @Throws(IOException::class)
    private fun spawnSubprocess(environment: CredentialHelperEnvironment?, vararg args: String?): Subprocess {
        com.google.common.base.Preconditions.checkNotNull<CredentialHelperEnvironment?>(environment)
        com.google.common.base.Preconditions.checkNotNull<Array<String?>?>(args)

        return SubprocessBuilder(environment.clientEnvironment)
            .setArgv(
                com.google.common.collect.ImmutableList.builder<String?>().add(path.getPathString()).add(*args).build()
            )
            .setWorkingDirectory(
                if (environment.workspacePath != null) environment.workspacePath.getPathFile() else null
            )
            .setEnv(environment.clientEnvironment)
            .setTimeoutMillis(environment.helperExecutionTimeout.toMillis())
            .start()
    }

    override fun equals(o: Any?): Boolean {
        if (o is CredentialHelper) {
            return this.getPath() == o.getPath()
        }

        return false
    }

    override fun hashCode(): Int {
        return java.util.Objects.hashCode(getPath())
    }

    companion object {
        private val GSON: Gson = Gson()
    }
}
