// Copyright 2021 The Bazel Authors. All rights reserved.
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

/** Unit test for [JavaPluginsFlagAliasRule]  */
@RunWith(JUnit4::class)
class JavaPluginsFlagAliasTest : BuildViewTestCase() {
    /** Tests that java_plugins_flag_alias target cannot be created in arbitrary location.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaPluginFlagAlias_wrongLocationThrowsError() {
        checkError( /* packageName = */
            "mytools",  /* ruleName = */
            "custom_plugins_alias",  /* expectedErrorMessage = */
            "Rule //mytools:custom_plugins_alias cannot use private rule",
            "java_plugins_flag_alias(name = 'custom_plugins_alias')"
        )
    }

    /**
     * Tests that when no --plugins flag is set java_plugins_flag_alias returns empty JavaPluginInfo.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaPluginFlagAlias_noFlagSet() {
        useConfiguration()

        val target: ConfiguredTarget =
            getConfiguredTarget(TestConstants.TOOLS_REPOSITORY.toString() + "//tools/jdk:java_plugins_flag_alias")

        assertThat(JavaPluginInfo.get(target)).isEqualTo(JavaPluginInfo.empty(JavaPluginInfo.PROVIDER))
    }

    /** Tests that a single plugin passed by a flag is returned by java_plugins_flag_alias.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaPluginFlagAlias_flagWithSinglePlugin() {
        scratch.file(
            "java/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_plugin')",
            "java_plugin(name = 'plugin', srcs = ['A.java'])"
        )
        useConfiguration("--plugin=//java:plugin")

        val target: ConfiguredTarget =
            getConfiguredTarget(TestConstants.TOOLS_REPOSITORY.toString() + "//tools/jdk:java_plugins_flag_alias")

        assertThat(JavaPluginInfo.get(target).plugins().processorClasspath().toList()).hasSize(1)
    }

    /** Tests that two plugins passed by flag are returned by java_plugins_flag_alias.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaPluginFlagAlias_flagWithTwoPlugins() {
        scratch.file(
            "java/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_plugin")
        java_plugin(
            name = "plugin1",
            srcs = ["A.java"],
        )

        java_plugin(
            name = "plugin2",
            srcs = ["B.java"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--plugin=//java:plugin1", "--plugin=//java:plugin2")

        val target: ConfiguredTarget =
            getConfiguredTarget(TestConstants.TOOLS_REPOSITORY.toString() + "//tools/jdk:java_plugins_flag_alias")

        assertThat(JavaPluginInfo.get(target).plugins().processorClasspath().toList()).hasSize(2)
    }

    /** Tests that passing a java_library to --plugin flag fails.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaPluginFlagAlias_flagWithJavaLibraryFails() {
        scratch.file(
            "java/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'lib', srcs = ['A.java'])"
        )
        useConfiguration("--plugin=//java:lib")

        checkError( /* label = */
            TestConstants.TOOLS_REPOSITORY.toString() + "//tools/jdk:java_plugins_flag_alias",
            getErrorMsgMandatoryProviderMissing("//java:lib", "JavaPluginInfo")
        )
    }
}
