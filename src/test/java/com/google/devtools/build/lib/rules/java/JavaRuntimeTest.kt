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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Tests for the java_runtime rule.  */
@RunWith(JUnit4::class)
class JavaRuntimeTest : BuildViewTestCase() {
    @Throws(net.starlark.java.eval.EvalException::class, RuleErrorException::class)
    private fun getJavaRuntimeInfo(collection: ProviderCollection): JavaRuntimeInfo {
        val toolchainInfo: ToolchainInfo = collection.get(ToolchainInfo.PROVIDER)
        return JavaRuntimeInfo.wrap(toolchainInfo.getValue("java_runtime", Info::class.java), "java_runtime")
    }

    // This test is here to ensure that the java_runtime version is accessible by native code.
    // It needs to stay in native as long as the native class
    // bazel/src/main/java/com/google/devtools/build/lib/rules/java/JavaRuntimeInfo.java exists.
    // copybara:strip see cl/613242078
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaRuntimeVersion_isAccessibleByNativeCode() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_runtime")
        genrule(
            name = "gen",
            outs = ["generated_java_home/bin/java"],
            cmd = "",
        )

        java_runtime(
            name = "jvm",
            java = "generated_java_home/bin/java",
            java_home = "generated_java_home",
            version = 234,
        )
        
        """.trimIndent()
        )
        val jvm: ConfiguredTarget = getConfiguredTarget("//a:jvm")
        Truth.assertThat(getJavaRuntimeInfo(jvm).version()).isEqualTo(234)
    }

    // This test is here to ensure that trying to wrap a missing JavaRuntimeInfo in native does
    // not crash blaze. Can be deleted when we no longer have a native JavaRuntimeInfo
    // Regression test for b/486198263
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nullJavaRuntimeInfoWrapping_doesNotCrashBlaze() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_java//java/toolchains:java_toolchain.bzl", "java_toolchain")
        java_toolchain(
            name = "jt",
            singlejar = ["sj"],
            java_runtime = None,
        )
        
        """.trimIndent()
        )
        val jt: ConfiguredTarget = getConfiguredTarget("//a:jt")
        val assertionError: RuleErrorException? =
            org.junit.Assert.assertThrows<T?>(
                RuleErrorException::class.java,
                org.junit.function.ThrowingRunnable {
                    JavaToolchainProvider.from(jt).getJavaRuntime()
                })
        assertThat(assertionError)
            .hasMessageThat()
            .contains("expected a JavaRuntimeInfo, but java_runtime was unset")
    }
}
