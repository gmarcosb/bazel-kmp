// Copyright 2006 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.filegroup

import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.analysis.ConfiguredTarget
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.lang.String
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Exception

/** Tests for [Filegroup].  */
@RunWith(TestParameterInjector::class)
class FilegroupConfiguredTargetTest : BuildViewTestCase() {
    @Test
    @Throws(Exception::class)
    fun testGroup() {
        scratch.file(
            "nevermore/BUILD",
            """
        filegroup(name  = 'staticdata',
                  srcs = ['staticdata/spam.txt', 'staticdata/good.txt'])
        
        """.trimIndent()
        )
        val groupTarget: ConfiguredTarget = getConfiguredTarget("//nevermore:staticdata")
        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(groupTarget)))
            .containsExactly("nevermore/staticdata/spam.txt", "nevermore/staticdata/good.txt")
    }

    @Test
    @Throws(Exception::class)
    fun testDependencyGraph() {
        scratch.file(
            "java/com/google/test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(name  = 'test_app',
            resources = [':data'],
            create_executable = 0,
            srcs  = ['InputFile.java', 'InputFile2.java'])
        filegroup(name  = 'data',
                  srcs = ['b.txt', 'a.txt'])
        
        """.trimIndent()
        )
        val appOutput: FileConfiguredTarget =
            getFileConfiguredTarget("//java/com/google/test:test_app.jar")
        assertThat(actionsTestUtil().predecessorClosureOf(appOutput.getArtifact(), FileType.of(".txt")))
            .isEqualTo("b.txt a.txt")
    }

    @Test
    @Throws(Exception::class)
    fun testEmptyGroupIsAnOk() {
        scratchConfiguredTarget(
            "empty", "empty",
            "filegroup(name='empty', srcs=[])"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testEmptyGroupInGenruleIsOk() {
        scratchConfiguredTarget(
            "empty", "genempty",
            "filegroup(name='empty', srcs=[])",
            "genrule(name='genempty', tools=[':empty'], outs=['nothing'], cmd='touch $@')"
        )
    }

    @Throws(IOException::class)
    private fun writeTest() {
        scratch.file(
            "another/BUILD",
            """
        exports_files(['another.txt'])
        filegroup(name  = 'another',
                  srcs = ['another.txt'])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        filegroup(name  = 'a',
                  srcs = ['a.txt'])
        filegroup(name  = 'b',
                  srcs = ['a.txt'])
        filegroup(name  = 'c',
                  srcs = ['a', 'b.txt'])
        filegroup(name  = 'd',
                  srcs = ['//another:another.txt'])
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun testFileCanBeSrcsOfMultipleRules() {
        writeTest()
        assertThat(
            ActionsTestUtil.prettyArtifactNames(getFilesToBuild(getConfiguredTarget("//test:a")))
        )
            .containsExactly("test/a.txt")
        assertThat(
            ActionsTestUtil.prettyArtifactNames(getFilesToBuild(getConfiguredTarget("//test:b")))
        )
            .containsExactly("test/a.txt")
    }

    @Test
    @Throws(Exception::class)
    fun testRuleCanBeSrcsOfOtherRule() {
        writeTest()
        assertThat(
            ActionsTestUtil.prettyArtifactNames(getFilesToBuild(getConfiguredTarget("//test:c")))
        )
            .containsExactly("test/a.txt", "test/b.txt")
    }

    @Test
    @Throws(Exception::class)
    fun testOtherPackageCanBeSrcsOfRule() {
        writeTest()
        assertThat(
            ActionsTestUtil.prettyArtifactNames(getFilesToBuild(getConfiguredTarget("//test:d")))
        )
            .containsExactly("another/another.txt")
    }

    @Test
    @Throws(Exception::class)
    fun testIsNotExecutable() {
        scratch.file(
            "x/BUILD",
            "filegroup(name = 'not_exec_two_files', srcs = ['bin', 'bin.sh'])"
        )
        assertThat(getExecutable("//x:not_exec_two_files")).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testIsExecutable() {
        scratch.file(
            "x/BUILD",
            "filegroup(name = 'exec', srcs = ['bin'])"
        )
        assertThat(getExecutable("//x:exec").getExecPath().getPathString()).isEqualTo("x/bin")
    }

    @Test
    @Throws(Exception::class)
    fun testNoDuplicate() {
        scratch.file(
            "x/BUILD",
            """
        filegroup(name = 'a', srcs = ['file'])
        filegroup(name = 'b', srcs = ['file'])
        filegroup(name = 'c', srcs = [':a', ':b'])
        
        """.trimIndent()
        )
        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(getConfiguredTarget("//x:c"))))
            .containsExactly("x/file")
    }

    @Test
    @Throws(Exception::class)
    fun testGlobMatchesRuleOutputsInsteadOfFileWithTheSameName() {
        scratch.file("pkg/file_or_rule")
        scratch.file("pkg/a.txt")
        val target: ConfiguredTarget = scratchConfiguredTarget(
            "pkg", "my_rule",
            "filegroup(name = 'file_or_rule', srcs = ['a.txt'])",
            "filegroup(name = 'my_rule', srcs = glob(['file_or_rule']))"
        )
        assertThat(ActionsTestUtil.baseArtifactNames(getFilesToBuild(target))).containsExactly("a.txt")
    }

    @Test
    @Throws(Exception::class)
    fun outputGroupSourceJars_extractsTransitiveSources() {
        scratch.file("pkg/a.java")
        scratch.file("pkg/b.java")
        scratch.file("pkg/c.java")
        scratch.file(
            "pkg/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name='lib_a', srcs=['a.java'])",
            "java_library(name='lib_b', srcs=['b.java'], deps = [':lib_c'])",
            "java_library(name='lib_c', srcs=['c.java'])",
            "filegroup(name='group', srcs=[':lib_a', ':lib_b'],"
                    + String.format("output_group='%s')", JavaSemantics.SOURCE_JARS_OUTPUT_GROUP)
        )

        val group: ConfiguredTarget = getConfiguredTarget("//pkg:group")

        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(group)))
            .containsExactly("pkg/liblib_a-src.jar", "pkg/liblib_b-src.jar", "pkg/liblib_c-src.jar")
    }

    @Test
    @Throws(Exception::class)
    fun outputGroupDirectSourceJars_extractsDirectSources() {
        scratch.file("pkg/a.java")
        scratch.file("pkg/b.java")
        scratch.file("pkg/c.java")
        scratch.file(
            "pkg/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name='lib_a', srcs=['a.java'])",
            "java_library(name='lib_b', srcs=['b.java'], deps = [':lib_c'])",
            "java_library(name='lib_c', srcs=['c.java'])",
            "filegroup(name='group', srcs=[':lib_a', ':lib_b'],"
                    + String.format("output_group='%s')", JavaSemantics.DIRECT_SOURCE_JARS_OUTPUT_GROUP)
        )

        val group: ConfiguredTarget = getConfiguredTarget("//pkg:group")

        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(group)))
            .containsExactly("pkg/liblib_a-src.jar", "pkg/liblib_b-src.jar")
    }

    @Test
    @Throws(Exception::class)
    fun testErrorForIllegalOutputGroup() {
        scratch.file("pkg/a.cc")
        scratch.file(
            "pkg/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='lib_a', srcs=['a.cc'])",
            String.format(
                "filegroup(name='group', srcs=[':lib_a'], output_group='%s')",
                OutputGroupInfo.HIDDEN_TOP_LEVEL
            )
        )
        val e = Assert.assertThrows<AssertionError?>(
            AssertionError::class.java,
            ThrowingRunnable { getConfiguredTarget("//pkg:group") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                String.format(Filegroup.ILLEGAL_OUTPUT_GROUP_ERROR, OutputGroupInfo.HIDDEN_TOP_LEVEL)
            )
    }

    @Test
    @Throws(Exception::class)
    fun testDefaultInfo(@TestParameter filegroupRunfilesForData: Boolean) {
        scratch.file(
            "x/defs.bzl",
            """
        def _default_info_impl(ctx):
            files = depset(transitive = [t[DefaultInfo].files for t in ctx.attr.files])
            default_runfiles = ctx.runfiles(transitive_files = depset(transitive = [t[DefaultInfo].files for t in ctx.attr.default_runfiles]))
            data_runfiles = ctx.runfiles(transitive_files = depset(transitive = [t[DefaultInfo].files for t in ctx.attr.data_runfiles]))
            return [
                DefaultInfo(
                    files = files,
                    default_runfiles = default_runfiles,
                    data_runfiles = data_runfiles,
                )
            ]
        default_info = rule(
            implementation = _default_info_impl,
            attrs = {
                "files": attr.label_list(allow_files=True),
                "default_runfiles": attr.label_list(allow_files=True),
                "data_runfiles": attr.label_list(allow_files=True),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "x/BUILD",
            """
        load(":defs.bzl", "default_info")

        default_info(
            name = "default_info_srcs",
            files = ["srcs_files_file"],
            default_runfiles = ["srcs_default_runfiles_file"],
            data_runfiles = ["srcs_data_runfiles_file"],
        )

        default_info(
            name = "default_info_data",
            files = ["data_files"],
            default_runfiles = ["data_default_runfiles_file"],
            data_runfiles = ["data_data_runfiles_file"],
        )

        filegroup(
            name = "filegroup",
            srcs = [
                ":default_info_srcs",
                "srcs_file",
            ],
            data = [
                ":default_info_data",
                "data_file",
            ],
        )
        
        """.trimIndent()
        )

        useConfiguration("--incompatible_filegroup_runfiles_for_data=" + filegroupRunfilesForData)
        val filegroup: @NotNull ConfiguredTarget = getConfiguredTarget("//x:filegroup")

        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(filegroup)))
            .containsExactly("x/srcs_file", "x/srcs_files_file")
        assertThat(ActionsTestUtil.prettyArtifactNames(getDefaultRunfiles(filegroup).getArtifacts()))
            .containsExactly(
                "x/srcs_default_runfiles_file",
                "x/data_file",
                "x/data_files",
                "x/data_data_runfiles_file"
            )
        val expectedDataRunfiles =
            ImmutableSet.builder<kotlin.String?>()
                .add(
                    "x/srcs_file",
                    "x/srcs_files_file",
                    "x/data_file",
                    "x/data_files",
                    "x/data_data_runfiles_file"
                )
        if (filegroupRunfilesForData) {
            expectedDataRunfiles.add("x/srcs_data_runfiles_file")
        }
        assertThat(ActionsTestUtil.prettyArtifactNames(getDataRunfiles(filegroup).getArtifacts()))
            .containsExactlyElementsIn(expectedDataRunfiles.build())
    }
}
