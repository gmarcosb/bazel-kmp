// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.blackbox.bazel

import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorDescriptor.Builder.build
import com.google.devtools.build.lib.blackbox.framework.BlackBoxTestContext
import com.google.devtools.build.lib.blackbox.framework.ToolsSetup
import com.google.devtools.build.lib.vfs.Path
import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Path

/** Setup for Bazel default tools  */
class DefaultToolsSetup : ToolsSetup {
    @Throws(IOException::class)
    override fun setup(context: BlackBoxTestContext) {
        val outputRoot: Path = java.nio.file.Files.createTempDirectory(context.getTmpDir(), "root").toAbsolutePath()
        val lines: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        lines.add("startup --output_user_root=" + outputRoot.toString().replace('\\', '/'))

        val sharedInstallBase: String? = java.lang.System.getenv("TEST_INSTALL_BASE")
        if (sharedInstallBase != null) {
            lines.add("startup --install_base=" + sharedInstallBase)
        }

        val sharedRepoCache: String? = java.lang.System.getenv("REPOSITORY_CACHE")
        if (sharedRepoCache != null) {
            lines.add("common --repository_cache=" + sharedRepoCache)
            // TODO: Remove this flag once all dependencies are mirrored.
            // See https://github.com/bazelbuild/bazel/pull/19549 for more context.
            lines.add("common --repo_env=BAZEL_HTTP_RULES_URLS_AS_DEFAULT_CANONICAL_ID=0")
            if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN) {
                // For reducing SSD usage on our physical Mac machines.
                lines.add("common --experimental_repository_cache_hardlinks")
            }
        }

        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.DARWIN && hasIpv6DefaultRouteOnDarwin()) {
            // Prefer IPv6 network on macOS only when an IPv6 default route exists.
            lines.add("startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true")
            lines.add("build --jvmopt=-Djava.net.preferIPv6Addresses")
        }

        context.write(".bazelrc", lines)
    }

    companion object {
        private fun hasIpv6DefaultRouteOnDarwin(): Boolean {
            if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.DARWIN) {
                return false
            }
            try {
                val p: java.lang.Process =
                    java.lang.ProcessBuilder("netstat", "-rn", "-f", "inet6").redirectErrorStream(true).start()
                BufferedReader(java.io.InputStreamReader(p.getInputStream())).use { r ->
                    var line: String?
                    while ((r.readLine().also { line = it }) != null) {
                        if (line.trim { it <= ' ' }.startsWith("default")) {
                            p.destroy()
                            return true
                        }
                    }
                }
                p.waitFor()
            } catch (e: java.lang.Exception) {
                // netstat not found or failed; assume no IPv6 default route.
            }
            return false
        }
    }
}
