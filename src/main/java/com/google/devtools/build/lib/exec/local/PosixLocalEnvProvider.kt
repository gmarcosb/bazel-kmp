// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.exec.BinTools
import com.google.devtools.build.lib.exec.local.LocalEnvProvider

/** [LocalEnvProvider] implementation for actions running on Unix-like platforms.  */
class PosixLocalEnvProvider
/**
 * Create a new [PosixLocalEnvProvider].
 * 
 * 
 * Use [LocalEnvProvider.forCurrentOs] to instantiate this unless the calling code
 * is platform-specific.
 * 
 * @param clientEnv a map of the current Bazel command's environment
 */(private val clientEnv: MutableMap<String?, String?>) : LocalEnvProvider {
    /**
     * Compute an environment map for local actions on Unix-like platforms (e.g. Linux, macOS).
     * 
     * 
     * Returns a map with the same keys and values as `env`. Overrides the value of TMPDIR
     * (or adds it if not present in `env`) by the value of `clientEnv.get("TMPDIR")`, or
     * if that's empty or null, then by "/tmp".
     */
    override fun rewriteLocalEnv(
        env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String?
    ): com.google.common.collect.ImmutableMap<String?, String?> {
        val result: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            com.google.common.collect.ImmutableMap.builder<String?, String?>()
        result.putAll(
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
        result.put("TMPDIR", p)
        return result.buildOrThrow()
    }
}
