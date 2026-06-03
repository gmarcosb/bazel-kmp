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

import com.google.devtools.build.lib.actions.Action

/** Tests for [JavaCompileActionBuilder].  */
@RunWith(JUnit4::class)
class JavaCompileActionBuilderTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClassdirIsInBlazeOut() {
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = "a",
            srcs = ["a.java"],
        )
        
        """.trimIndent()
        )
        val action: JavaCompileAction =
            getGeneratingActionForLabel("//java/com/google/test:a.jar") as JavaCompileAction
        val command: MutableList<String?> = java.util.ArrayList<String?>()
        command.addAll(JavaCompileActionTestHelper.getJavacArguments(action))
        MoreAsserts.assertContainsSublist<T?>(
            command,
            "--output",
            targetConfig
                .getBinFragment(RepositoryName.MAIN)
                .getRelative("java/com/google/test/a.jar")
                .getPathString()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun progressMessage() {
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = [
                "a.java",
                "b.java",
            ],
        )
        
        """.trimIndent()
        )
        val action: JavaCompileAction =
            getGeneratingActionForLabel("//java/com/google/test:liba.jar") as JavaCompileAction
        assertThat(action.getProgressMessage())
            .isEqualTo("Building java/com/google/test/liba.jar (2 source files)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun progressMessageWithSourceJars() {
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = [
                "a.java",
                "archive.srcjar",
                "b.java",
            ],
        )
        
        """.trimIndent()
        )
        val action: JavaCompileAction =
            getGeneratingActionForLabel("//java/com/google/test:liba.jar") as JavaCompileAction
        assertThat(action.getProgressMessage())
            .isEqualTo("Building java/com/google/test/liba.jar (2 source files, 1 source jar)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun progressMessageAnnotationProcessors() {
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        java_plugin(
            name = "foo",
            srcs = ["Foo.java"],
            processor_class = "Foo",
        )

        java_plugin(
            name = "bar",
            srcs = ["Bar.java"],
            processor_class = "com.google.Bar",
        )

        java_library(
            name = "a",
            srcs = [
                "a.java",
                "archive.srcjar",
                "b.java",
            ],
            plugins = [
                ":foo",
                ":bar",
            ],
        )
        
        """.trimIndent()
        )
        val action: JavaCompileAction =
            getGeneratingActionForLabel("//java/com/google/test:liba.jar") as JavaCompileAction
        assertThat(action.getProgressMessage())
            .isEqualTo(
                "Building java/com/google/test/liba.jar (2 source files, 1 source jar)"
                        + " and running annotation processors (Foo, Bar)"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocale() {
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["A.java"],
        )
        
        """.trimIndent()
        )
        val action: JavaCompileAction =
            getGeneratingActionForLabel("//java/com/google/test:liba.jar") as JavaCompileAction
        Truth.assertThat(action.incompleteEnvironmentForTesting)
            .containsEntry("LC_CTYPE", if (analysisMock.isThisBazel) "C.UTF-8" else "en_US.UTF-8")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testClasspathReduction() {
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["A.java"],
            deps = [":b"],
        )

        java_library(
            name = "b",
            srcs = ["B.java"],
            deps = [
                ":c",
                ":d",
            ],
        )

        java_library(
            name = "c",
            srcs = ["C.java"],
        )

        java_library(
            name = "d",
            srcs = ["D.java"],
        )
        
        """.trimIndent()
        )
        val bJdeps: Artifact =
            getBinArtifact("libb-hjar.jdeps", getConfiguredTarget("//java/com/google/test:b"))
        val cHjar: Artifact =
            getBinArtifact("libc-hjar.jar", getConfiguredTarget("//java/com/google/test:libc.jar"))
        val action: JavaCompileAction =
            getGeneratingActionForLabel("//java/com/google/test:liba.jar") as JavaCompileAction
        val context: JavaCompileActionContext = JavaCompileActionContext()
        val dep: Deps.Dependency? =
            Deps.Dependency.newBuilder()
                .setKind(Kind.EXPLICIT)
                .setPath(cHjar.getExecPathString())
                .build()
        context.insertDependencies(bJdeps, Deps.Dependencies.newBuilder().addDependency(dep).build())
        Truth.assertThat(
            artifactsToStrings(
                action.getReducedClasspath(ActionExecutionContextBuilder().build(), context)
            )
        )
            .containsExactly(
                "bin java/com/google/test/libb-hjar.jar", "bin java/com/google/test/libc-hjar.jar"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTurbineCpuReservation() {
        useConfiguration("--java_header_compilation=true", "--experimental_turbine_cpu_reservation=2")
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["A.java"],
            deps = [":b"],
        )
        java_library(
            name = "b",
            srcs = ["b.java"],
        )
        
        """.trimIndent()
        )
        val compileAction: JavaCompileAction =
            getGeneratingActionForLabel("//java/com/google/test:liba.jar") as JavaCompileAction
        val action: Action = getTurbineAction(compileAction)

        if (TestConstants.PRODUCT_NAME == "bazel") {
            Truth.assertThat(paramFileArgsForAction(action)).contains("-XDnoParallel")
        } else {
            Truth.assertThat(paramFileArgsForAction(action)).doesNotContain("-XDnoParallel")
        }
        assertThat(action.getExecutionInfo().keySet().stream().filter({ k -> k.startsWith("cpu:") }))
            .containsExactly("cpu:2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoTurbineCpuReservation() {
        useConfiguration("--java_header_compilation=true")
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "a",
            srcs = ["A.java"],
            deps = [":b"],
        )
        java_library(
            name = "b",
            srcs = ["b.java"],
        )
        
        """.trimIndent()
        )
        val compileAction: JavaCompileAction =
            getGeneratingActionForLabel("//java/com/google/test:liba.jar") as JavaCompileAction
        val action: Action = getTurbineAction(compileAction)

        if (TestConstants.PRODUCT_NAME == "bazel") {
            Truth.assertThat(paramFileArgsForAction(action)).contains("-XDnoParallel")
        } else {
            Truth.assertThat(paramFileArgsForAction(action)).doesNotContain("-XDnoParallel")
        }
        assertThat(action.getExecutionInfo().keySet().stream().filter({ k -> k.startsWith("cpu:") }))
            .isEmpty()
    }

    @Throws(java.lang.Exception::class)
    private fun getTurbineAction(compileAction: JavaCompileAction?): CommandAction {
        return getGeneratingAction(getBinArtifacts(compileAction).collect(com.google.common.collect.MoreCollectors.onlyElement<Artifact?>())) as CommandAction
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun getBinArtifacts(compileAction: JavaCompileAction?): java.util.stream.Stream<Artifact?> {
            return getInputs(compileAction, JavaCompileActionTestHelper.getDirectJars(compileAction)).stream()
                .filter({ a -> a.getFilename().endsWith("-hjar.jar") })
        }
    }
}
