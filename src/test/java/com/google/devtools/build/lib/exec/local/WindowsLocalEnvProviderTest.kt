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

/** Unit tests for [WindowsLocalEnvProvider].  */
@RunWith(JUnit4::class)
class WindowsLocalEnvProviderTest {
    /** Should use the client environment's TMP envvar if specified.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRewriteEnvWithClientTmp() {
        val p: WindowsLocalEnvProvider =
            WindowsLocalEnvProvider(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "TMP",
                    "client-env/tmp",
                    "TEMP",
                    "ignore/when/tmp/is/present"
                )
            )

        Truth.assertThat(
            rewriteEnv(
                p,
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMP",
                    "ignore",
                    "TEMP",
                    "ignore"
                )
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMP",
                    "client-env\\tmp",
                    "TEMP",
                    "client-env\\tmp"
                )
            )

        Truth.assertThat(
            rewriteEnv(
                p,
                com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1", "TMP", "ignore")
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMP",
                    "client-env\\tmp",
                    "TEMP",
                    "client-env\\tmp"
                )
            )

        Truth.assertThat(rewriteEnv(p, com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1")))
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMP",
                    "client-env\\tmp",
                    "TEMP",
                    "client-env\\tmp"
                )
            )
    }

    /** Should use the client environment's TEMP envvar if TMP is unspecified.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRewriteEnvWithoutClientTmpWithClientTemp() {
        val p: WindowsLocalEnvProvider =
            WindowsLocalEnvProvider(com.google.common.collect.ImmutableMap.of<K?, V?>("TEMP", "client-env/temp"))

        Truth.assertThat(
            rewriteEnv(
                p,
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMP",
                    "ignore",
                    "TEMP",
                    "ignore"
                )
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1", "value1", "TMP", "client-env\\temp", "TEMP", "client-env\\temp"
                )
            )

        Truth.assertThat(
            rewriteEnv(
                p,
                com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1", "TMP", "ignore")
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1", "value1", "TMP", "client-env\\temp", "TEMP", "client-env\\temp"
                )
            )

        Truth.assertThat(rewriteEnv(p, com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1")))
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1", "value1", "TMP", "client-env\\temp", "TEMP", "client-env\\temp"
                )
            )
    }

    /** Should use the fallback temp dir when the client env defines neither TMP nor TEMP.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRewriteEnvWithFallbackTmp() {
        val p: WindowsLocalEnvProvider =
            WindowsLocalEnvProvider(com.google.common.collect.ImmutableMap.of<String?, String?>())

        Truth.assertThat(
            rewriteEnv(
                p,
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMP",
                    "ignore",
                    "TEMP",
                    "ignore"
                ),
                "fallback/tmp"
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMP",
                    "fallback\\tmp",
                    "TEMP",
                    "fallback\\tmp"
                )
            )

        Truth.assertThat(
            rewriteEnv(
                p,
                com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1", "TMP", "ignore"),
                "fallback/tmp"
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMP",
                    "fallback\\tmp",
                    "TEMP",
                    "fallback\\tmp"
                )
            )

        Truth.assertThat(
            rewriteEnv(
                p,
                com.google.common.collect.ImmutableMap.of<String?, String?>("key1", "value1"),
                "fallback/tmp"
            )
        )
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "key1",
                    "value1",
                    "TMP",
                    "fallback\\tmp",
                    "TEMP",
                    "fallback\\tmp"
                )
            )
    }

    companion object {
        private fun rewriteEnv(
            p: WindowsLocalEnvProvider, env: com.google.common.collect.ImmutableMap<String?, String?>?
        ): MutableMap<String?, String?> {
            return p.rewriteLocalEnv(env, null, null)
        }

        private fun rewriteEnv(
            p: WindowsLocalEnvProvider,
            env: com.google.common.collect.ImmutableMap<String?, String?>?,
            fallback: String?
        ): MutableMap<String?, String?> {
            return p.rewriteLocalEnv(env, null, fallback)
        }
    }
}
