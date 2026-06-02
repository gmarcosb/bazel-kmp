// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec.local

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.exec.BinTools
import com.google.devtools.build.lib.exec.local.LocalEnvProvider
import com.google.devtools.build.lib.exec.local.XcodeLocalEnvProvider
import com.google.devtools.build.lib.rules.apple.AppleConfiguration
import com.google.devtools.build.lib.shell.AbnormalTerminationException
import com.google.devtools.build.lib.shell.CommandResult
import com.google.devtools.build.lib.shell.TerminationStatus
import java.io.IOException
import java.io.UncheckedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * Adds to the given environment all variables that are dependent on system state of the host
 * machine.
 * 
 * 
 * Admittedly, hermeticity is "best effort" in such cases; these environment values should be as
 * tied to configuration parameters as possible.
 */
class XcodeLocalEnvProvider
/**
 * Creates a new [XcodeLocalEnvProvider].
 * 
 * 
 * Use [LocalEnvProvider.forCurrentOs] to instantiate this.
 * 
 * @param clientEnv a map of the current Bazel command's environment
 */ internal constructor(private val clientEnv: MutableMap<String?, String?>) : LocalEnvProvider {
    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun rewriteLocalEnv(
        env: MutableMap<String?, String>, binTools: BinTools, fallbackTmpDir: String?
    ): com.google.common.collect.ImmutableMap<String?, String?> {
        val containsDeveloperDir = env.containsKey(AppleConfiguration.DEVELOPER_DIR_ENV_NAME)
        val containsXcodeVersion = env.containsKey(AppleConfiguration.XCODE_VERSION_ENV_NAME)
        val containsAppleSdkPlatform =
            env.containsKey(AppleConfiguration.APPLE_SDK_PLATFORM_ENV_NAME)

        val newEnvBuilder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            com.google.common.collect.ImmutableMap.builder<String?, String?>()
        newEnvBuilder.putAll(
            com.google.common.collect.Maps.filterKeys<String?, String?>(
                env,
                com.google.common.base.Predicate { k: String? -> k != "TMPDIR" })
        )
        var p = clientEnv.get("TMPDIR")
        if (com.google.common.base.Strings.isNullOrEmpty(p)) {
            // Do not use `fallbackTmpDir`, use `/tmp` instead. This way if the user didn't export TMPDIR
            // in their environment, Bazel will still set a TMPDIR that's Posixy enough and plays well
            // with heavily path-length-limited scenarios, such as the socket creation scenario that
            // motivated https://github.com/bazelbuild/bazel/issues/4376.
            p = "/tmp"
        }
        newEnvBuilder.put("TMPDIR", p)

        if (!containsXcodeVersion && !containsAppleSdkPlatform) {
            return newEnvBuilder.buildOrThrow()
        }

        // Empty developer dir indicates to use the system default.
        // TODO(bazel-team): Bazel's view of the Xcode version and developer dir should be explicitly
        // set for build hermeticity.
        var developerDir: String? = ""
        if (containsXcodeVersion && !containsDeveloperDir) {
            val version: String = env.get(AppleConfiguration.XCODE_VERSION_ENV_NAME)!!
            // Directly use version as DEVELOPER_DIR when a path is passed
            if (version.startsWith("/")) {
                developerDir = version
            } else {
                developerDir = getDeveloperDir(
                    binTools,
                    com.google.devtools.build.lib.rules.apple.DottedVersion.fromStringUnchecked(version)
                )
            }
            newEnvBuilder.put("DEVELOPER_DIR", developerDir)
        }
        if (containsAppleSdkPlatform) {
            val appleSdkPlatform: String = env.get(AppleConfiguration.APPLE_SDK_PLATFORM_ENV_NAME)!!
            newEnvBuilder.put("SDKROOT", getSdkRoot(developerDir, appleSdkPlatform))
        }

        return newEnvBuilder.buildOrThrow()
    }

    /**
     * Queries the path to the target Apple SDK on the host system for a given version of Xcode.
     * 
     * 
     * This spawns a subprocess to run the `/usr/bin/xcrun` binary to locate the target SDK.
     * As this is a costly operation, always call [.getSdkRoot] instead, which
     * does caching.
     * 
     * @param developerDir the value of `DEVELOPER_DIR` for the target version of Xcode
     * @param appleSdkPlatform the SDK platform; for example, `iPhoneOS`
     * @return an absolute path to the root of the target Apple SDK
     * @throws IOException if there is an issue with obtaining the root from the spawned process,
     * either because the SDK platform/version pair doesn't exist, or there was an unexpected
     * issue finding or running the tool
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun querySdkRoot(developerDir: String?, appleSdkPlatform: String): String {
        try {
            val sdkString: String = com.google.common.base.Ascii.toLowerCase(appleSdkPlatform)
            val env: MutableMap<String?, String?> =
                if (com.google.common.base.Strings.isNullOrEmpty(developerDir))
                    com.google.common.collect.ImmutableMap.of<String?, String?>()
                else
                    com.google.common.collect.ImmutableMap.of<String?, String?>("DEVELOPER_DIR", developerDir)
            val xcrunResult: CommandResult =
                com.google.devtools.build.lib.shell.Command(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "/usr/bin/xcrun",
                        "--sdk",
                        sdkString,
                        "--show-sdk-path"
                    ),
                    env,
                    null,
                    clientEnv
                )
                    .execute()

            return String(xcrunResult.getStdout(), java.nio.charset.StandardCharsets.UTF_8).trim()
        } catch (e: AbnormalTerminationException) {
            val terminationStatus: TerminationStatus = e.getResult().terminationStatus

            if (terminationStatus.exited()) {
                throw IOException(
                    java.lang.String.format(
                        ("xcrun failed with code %s.\n"
                                + "This most likely indicates that the SDK platform [%s] is "
                                + "unsupported for the target version of Xcode.\n"
                                + "%s\n"
                                + "stdout: %s"
                                + "stderr: %s"),
                        terminationStatus.getExitCode(),
                        appleSdkPlatform,
                        terminationStatus,
                        String(e.getResult().getStdout(), java.nio.charset.StandardCharsets.UTF_8),
                        String(e.getResult().getStderr(), java.nio.charset.StandardCharsets.UTF_8)
                    )
                )
            }
            val message: String? =
                java.lang.String.format(
                    "xcrun failed.\n" + "%s\n" + "stdout: %s\n" + "stderr: %s",
                    e.getResult().terminationStatus,
                    String(e.getResult().getStdout(), java.nio.charset.StandardCharsets.UTF_8),
                    String(e.getResult().getStderr(), java.nio.charset.StandardCharsets.UTF_8)
                )
            throw IOException(message, e)
        } catch (e: com.google.devtools.build.lib.shell.CommandException) {
            throw IOException(e)
        }
    }

    /**
     * Returns the path to the target Apple SDK on the host system for a given version of Xcode.
     * 
     * 
     * This may delegate to [.querySdkRoot] to obtain the path from external
     * sources in the system. Values are cached in-memory throughout the lifetime of the Bazel server.
     * 
     * @param developerDir the value of `DEVELOPER_DIR` for the target version of Xcode
     * @param appleSdkPlatform the SDK platform; for example, `iPhoneOS`
     * @return an absolute path to the root of the target Apple SDK
     * @throws IOException if there is an issue with obtaining the root from the spawned process,
     * either because the SDK platform/version pair doesn't exist, or there was an unexpected
     * issue finding or running the tool
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun getSdkRoot(developerDir: String?, appleSdkPlatform: String): String? {
        try {
            return sdkRootCache.computeIfAbsent(
                developerDir + ":" + com.google.common.base.Ascii.toLowerCase(appleSdkPlatform),
                java.util.function.Function { key: String? ->
                    try {
                        val sdkRoot = querySdkRoot(developerDir, appleSdkPlatform)
                        logger.atInfo().log("Queried Xcode SDK root with key %s and got %s", key, sdkRoot)
                        return@computeIfAbsent sdkRoot
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    } catch (e: java.lang.InterruptedException) {
                        throw com.google.devtools.build.lib.exec.local.XcodeLocalEnvProvider.UncheckedInterruptedException(
                            e
                        )
                    }
                })
        } catch (e: UncheckedIOException) {
            throw e.getCause()
        } catch (e: UncheckedInterruptedException) {
            throw e.cause
        }
    }

    private class UncheckedInterruptedException(e: java.lang.InterruptedException?) : java.lang.RuntimeException(e) {
        @get:kotlin.jvm.Synchronized
        val cause: java.lang.InterruptedException?
            get() = super.getCause() as java.lang.InterruptedException?
    }

    /**
     * Queries the path to the Xcode developer directory on the host system for the given Xcode
     * version.
     * 
     * 
     * This spawns a subprocess to run the `xcode-locator` binary. As this is a costly
     * operation, always call [.getDeveloperDir] instead, which does
     * caching.
     * 
     * @param binTools the [BinTools], used to locate the cache file
     * @param version the Xcode version number to look up
     * @return an absolute path to the root of the Xcode developer directory
     * @throws IOException if there is an issue with obtaining the path from the spawned process,
     * either because there is no installed Xcode with the given version, or there was an
     * unexpected issue finding or running the tool
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun queryDeveloperDir(
        binTools: BinTools,
        version: com.google.devtools.build.lib.rules.apple.DottedVersion
    ): String {
        val xcodeLocatorPath: String? = binTools.getEmbeddedPath("xcode-locator").getPathString()
        try {
            val xcodeLocatorResult: CommandResult =
                com.google.devtools.build.lib.shell.Command(
                    com.google.common.collect.ImmutableList.of<String?>(
                        xcodeLocatorPath,
                        version.toString()
                    ), clientEnv
                ).execute()

            return String(xcodeLocatorResult.getStdout(), java.nio.charset.StandardCharsets.UTF_8).trim()
        } catch (e: AbnormalTerminationException) {
            val terminationStatus: TerminationStatus = e.getResult().terminationStatus

            val message: String?
            if (e.getResult().terminationStatus.exited()) {
                message =
                    java.lang.String.format(
                        ("Running '%s %s' failed with code %s.\n"
                                + "This most likely indicates that Xcode version %s is not available on the "
                                + "host machine.\n"
                                + "%s\n"
                                + "stdout: %s\n"
                                + "stderr: %s"),
                        xcodeLocatorPath,
                        version,
                        terminationStatus.getExitCode(),
                        version,
                        terminationStatus.toString(),
                        String(e.getResult().getStdout(), java.nio.charset.StandardCharsets.UTF_8),
                        String(e.getResult().getStderr(), java.nio.charset.StandardCharsets.UTF_8)
                    )
            } else {
                message =
                    java.lang.String.format(
                        "Running '%s %s' failed.\n" + "%s\n" + "stdout: %s\n" + "stderr: %s",
                        xcodeLocatorPath,
                        version,
                        e.getResult().terminationStatus,
                        String(e.getResult().getStdout(), java.nio.charset.StandardCharsets.UTF_8),
                        String(e.getResult().getStderr(), java.nio.charset.StandardCharsets.UTF_8)
                    )
            }
            throw IOException(message, e)
        } catch (e: com.google.devtools.build.lib.shell.CommandException) {
            throw IOException(e)
        }
    }

    /**
     * Returns the absolute root path of the Xcode developer directory on the host system for the
     * given Xcode version.
     * 
     * 
     * This may delegate to [.queryDeveloperDir] to obtain the path from
     * external sources in the system. Values are cached in-memory throughout the lifetime of the
     * Bazel server.
     * 
     * @param binTools the [BinTools] path, used to locate the cache file
     * @param version the Xcode version number to look up
     * @return an absolute path to the root of the Xcode developer directory
     * @throws IOException if there is an issue with obtaining the path from the spawned process,
     * either because there is no installed Xcode with the given version, or there was an
     * unexpected issue finding or running the tool
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun getDeveloperDir(
        binTools: BinTools,
        version: com.google.devtools.build.lib.rules.apple.DottedVersion
    ): String? {
        try {
            return developerDirCache.computeIfAbsent(
                version.toString(),
                java.util.function.Function { key: String? ->
                    try {
                        val developerDir = queryDeveloperDir(binTools, version)
                        logger.atInfo().log(
                            "Queried Xcode developer dir with key %s and got %s", key, developerDir
                        )
                        return@computeIfAbsent developerDir
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    } catch (e: java.lang.InterruptedException) {
                        throw com.google.devtools.build.lib.exec.local.XcodeLocalEnvProvider.UncheckedInterruptedException(
                            e
                        )
                    }
                })
        } catch (e: UncheckedIOException) {
            throw e.getCause()
        } catch (e: UncheckedInterruptedException) {
            throw e.cause
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val sdkRootCache: ConcurrentMap<String?, String?> = ConcurrentHashMap<String?, String?>()
        private val developerDirCache: ConcurrentMap<String?, String?> = ConcurrentHashMap<String?, String?>()
    }
}
