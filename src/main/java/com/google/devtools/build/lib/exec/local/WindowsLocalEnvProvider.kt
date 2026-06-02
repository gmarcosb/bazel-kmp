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

/** [LocalEnvProvider] implementation for actions running on Windows.  */
class WindowsLocalEnvProvider
/**
 * Create a new [WindowsLocalEnvProvider].
 * 
 * 
 * Use [LocalEnvProvider.forCurrentOs] to instantiate this.
 * 
 * @param clientEnv a map of the current Bazel command's environment
 */(private val clientEnv: MutableMap<String?, String>) : LocalEnvProvider {
    /**
     * Compute an environment map for local actions on Windows.
     * 
     * 
     * Returns a map with the same keys and values as `env`. Overrides the value of TMP and
     * TEMP (or adds them if not present in `env`) by the same value, which is:
     * 
     * 
     *  * the value of `clientEnv.get("TMP")`, or if that's empty or null, then
     *  * the value of `clientEnv.get("TEMP")`, or if that's empty or null, then
     *  * the value of `fallbackTmpDir`.
     * 
     * 
     * 
     * The values for TMP and TEMP will use backslashes as directory separators.
     */
    override fun rewriteLocalEnv(
        env: MutableMap<String?, String?>, binTools: BinTools?, fallbackTmpDir: String
    ): com.google.common.collect.ImmutableMap<String?, String?> {
        val result: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            com.google.common.collect.ImmutableMap.builder<String?, String?>()
        result.putAll(
            com.google.common.collect.Maps.filterKeys<String?, String?>(
                env,
                com.google.common.base.Predicate { k: String? -> k != "TMP" && k != "TEMP" })
        )
        var p: String = clientEnv.get("TMP")!!
        if (com.google.common.base.Strings.isNullOrEmpty(p)) {
            p = clientEnv.get("TEMP")!!
            if (com.google.common.base.Strings.isNullOrEmpty(p)) {
                p = fallbackTmpDir
            }
        }
        p = p.replace('/', '\\')
        result.put("TMP", p)
        result.put("TEMP", p)
        return result.buildOrThrow()
    }
}
