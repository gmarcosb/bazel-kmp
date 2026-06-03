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

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [PosixLocalEnvProvider].  */
@RunWith(JUnit4::class)
class PosixLocalEnvProviderTest {
    /** Should use the client environment's TMPDIR envvar if specified.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRewriteEnvWithClientTmpdir() {
        val p: PosixLocalEnvProvider =
            PosixLocalEnvProvider(com.google.common.collect.ImmutableMap.of<K?, V?>("TMPDIR", "client-env/tmp"))
        Truth.assertThat(rewriteEnv(p, com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1")))
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMPDIR",
                    "client-env/tmp"
                )
            )
        Truth.assertThat(
            rewriteEnv(
                p,
                com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1", "TMPDIR", "ignored")
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMPDIR",
                    "client-env/tmp"
                )
            )
    }

    /** Should use the default temp dir when the client env doesn't define TMPDIR.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRewriteEnvWithDefaultTmpdir() {
        val p: PosixLocalEnvProvider =
            PosixLocalEnvProvider(com.google.common.collect.ImmutableMap.of<String?, String?>())
        Truth.assertThat(rewriteEnv(p, com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1")))
            .isEqualTo(com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1", "TMPDIR", "/tmp"))
        Truth.assertThat(
            rewriteEnv(
                p,
                com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1", "TMPDIR", "ignored")
            )
        )
            .isEqualTo(com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1", "TMPDIR", "/tmp"))
    }

    companion object {
        private fun rewriteEnv(
            p: PosixLocalEnvProvider, env: com.google.common.collect.ImmutableMap<String?, String?>?
        ): MutableMap<String?, String?> {
            return p.rewriteLocalEnv(env, null, null)
        }
    }
}
