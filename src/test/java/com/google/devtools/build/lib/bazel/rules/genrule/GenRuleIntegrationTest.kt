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
package com.google.devtools.build.lib.bazel.rules.genrule

import com.google.devtools.build.lib.analysis.CommandHelper
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/**
 * Some integration tests of genrule, including several specifically addressing the treatment of
 * very long command lines whose total length in bytes exceeds
 * CommandBasedConfiguredTarget.maxCommandLength.
 */
@RunWith(JUnit4::class)
class GenRuleIntegrationTest : BuildIntegrationTestCase() {
    @Before
    fun setMaxCommandLength() {
        CommandHelper.setMaxCommandLengthForTesting(OptionalInt.of(40))
    }

    @After
    fun unsetMaxCommandLength() {
        CommandHelper.setMaxCommandLengthForTesting(OptionalInt.empty())
    }

    @Throws(IOException::class)
    private fun writeFiles() {
        for (i in 0..9) {
            write("test/input" + i + ".txt", "The number " + i)
        }
        write(
            "test/BUILD",
            """
        # Directly executed with "/bin/bash -c <command>".
        genrule(name = 'gen_small',
                  srcs = [],
                  outs = ['small'],
                  cmd = 'echo Smaller than 40 characters > ${'$'}@')
        # Executed indirectly via a script file "gen_large.genrule_script.sh",
        # because command length exceeds maxCommandLength.
        genrule(name = 'gen_large',
                  srcs = [],
                  outs = ['large'],
                  cmd = 'echo Larger than 40 characters............................ > ${'$'}@')
        # Also executed indirectly via a script file,
        # because command length exceeds maxCommandLength,
        # after expansion of ${'$'}(SRCS).
        genrule(name = 'gen_many_inputs',
                  srcs = glob(['input*.txt']),
                  outs = ['all.txt'],
                  cmd = 'cat ${'$'}(SRCS) > ${'$'}@')
        # A more realistic example of indirect execution via a script file.
        # This one is carefully written to avoid overflowing fixed limits,
        # even if ${'$'}(SRCS) expands to a very long string.
        genrule(name = 'gen_many_inputs2',
                  srcs = glob(['input*.txt']),
                  outs = ['all2.txt'],
                  cmd = '''
        set -x
        > ${'$'}@
        {
        cat <<EOF
        ${'$'}(SRCS)
        EOF
        } |
        tr ' ' '\
        ' |
        while read file; do cat ${'$'}${'$'}file >> ${'$'}@; done
        ''')
        
        """.trimIndent()
        )
    }

    @Throws(IOException::class)
    private fun getContents(outputFile: Path?): String {
        return String(FileSystemUtils.readContentAsLatin1(outputFile))
    }

    @Test
    @Throws(Exception::class)
    fun testDirectExecution() {
        writeFiles()

        buildTarget("//test:gen_small")
        val output: OutputFileConfiguredTarget =
            getConfiguredTarget("//test:small") as OutputFileConfiguredTarget
        Truth.assertThat(readContentAsLatin1String(output.getArtifact()))
            .isEqualTo("Smaller than 40 characters\n")
    }

    @Test
    @Throws(Exception::class)
    fun testSimpleIndirectExecution() {
        writeFiles()

        buildTarget("//test:gen_large")
        val output: OutputFileConfiguredTarget =
            getConfiguredTarget("//test:large") as OutputFileConfiguredTarget
        Truth.assertThat(readContentAsLatin1String(output.getArtifact()))
            .isEqualTo("Larger than 40 characters............................\n")

        val script: Path? =
            output
                .getArtifact()
                .getPath()
                .getParentDirectory()
                .getRelative("gen_large.genrule_script.sh")
        val scriptContents = getContents(script)
        Truth.assertThat(scriptContents).contains("#!/bin/bash\n")
        Truth.assertThat(scriptContents).contains("echo Larger than 40 characters")
    }

    @Test
    @Throws(Exception::class)
    fun testComplicatedIndirectExecution() {
        writeFiles()

        buildTarget("//test:gen_many_inputs")

        val output: OutputFileConfiguredTarget =
            getConfiguredTarget("//test:all.txt") as OutputFileConfiguredTarget
        Truth.assertThat(cleanNewlines(readContentAsLatin1String(output.getArtifact())))
            .isEqualTo(
                "The number 0\nThe number 1\nThe number 2\nThe number 3\nThe number 4\n"
                        + "The number 5\nThe number 6\nThe number 7\nThe number 8\nThe number 9\n"
            )

        val script: Path? =
            output
                .getArtifact()
                .getPath()
                .getParentDirectory()
                .getRelative("gen_many_inputs.genrule_script.sh")
        val scriptContents = getContents(script)
        Truth.assertThat(scriptContents).contains("#!/bin/bash\n")
        Truth.assertThat(scriptContents)
            .containsMatch("cat .*/input0.txt .*/input1.txt .* .*/input9.txt > .*/all.txt")
    }

    @Test
    @Throws(Exception::class)
    fun testRealisticIndirectExecution() {
        writeFiles()

        val recordingOutErr: RecordingOutErr = RecordingOutErr()
        this.outErr = recordingOutErr

        buildTarget("//test:gen_many_inputs2")

        val output: OutputFileConfiguredTarget =
            getConfiguredTarget("//test:all2.txt") as OutputFileConfiguredTarget
        Truth.assertThat(cleanNewlines(readContentAsLatin1String(output.getArtifact())))
            .isEqualTo(
                "The number 0\nThe number 1\nThe number 2\nThe number 3\nThe number 4\n"
                        + "The number 5\nThe number 6\nThe number 7\nThe number 8\nThe number 9\n"
            )

        val script: Path? =
            output
                .getArtifact()
                .getPath()
                .getParentDirectory()
                .getRelative("gen_many_inputs2.genrule_script.sh")
        val scriptContents = getContents(script)
        Truth.assertThat(scriptContents).contains("#!/bin/bash\n")
        Truth.assertThat(scriptContents).containsMatch("cat <<EOF")
        Truth.assertThat(scriptContents).containsMatch(".*/input0.txt .*/input1.txt .* .*/input9.txt")

        // Check that we didn't exceed the (supposed) maximum command-line length.
        for (line in recordingOutErr.errAsLatin1().split("\n")) {
            if (line.startsWith("+")) {
                Truth.assertThat(line.length).isLessThan(40)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun testToolchains_fromTemplateVariableInfo() {
        // Write a rule that generates templated data.
        write(
            "test/template_rule.bzl",
            """
        def _impl(ctx):
            vars = ctx.attr.vars
            return [platform_common.TemplateVariableInfo(vars)]

        template_rule = rule(
            _impl,
            attrs = {
                "vars": attr.string_dict(),
            },
        )
        
        """.trimIndent()
        )

        // Write a BUILD file that uses the data.
        write(
            "test/BUILD",
            """
        load(":template_rule.bzl", "template_rule")

        template_rule(
            name = "data",
            vars = {
                "foo": "bar",
            },
        )

        genrule(
            name = "g",
            srcs = [],
            outs = ["g.out"],
            cmd = "echo foo: ${'$'}(foo) > ${'$'}@",
            toolchains = [":data"],
        )
        
        """.trimIndent()
        )

        buildTarget("//test:g")
        val output: OutputFileConfiguredTarget =
            getConfiguredTarget("//test:g.out") as OutputFileConfiguredTarget
        Truth.assertThat(readContentAsLatin1String(output.getArtifact())).isEqualTo("foo: bar\n")
    }

    @Test
    @Throws(Exception::class)
    fun testToolchains_fromToolchain() {
        // Write a toolchain rule that generates templated data.
        write(
            "test/toolchain/template_toolchain.bzl",
            """
        def _impl(ctx):
            vars = ctx.attr.vars
            return [
                platform_common.TemplateVariableInfo(vars),
                platform_common.ToolchainInfo(data = "from " + ctx.label.name),
            ]

        template_toolchain = rule(
            _impl,
            attrs = {
                "vars": attr.string_dict(),
            },
        )
        
        """.trimIndent()
        )
        write(
            "test/toolchain/BUILD",
            """
        load(":template_toolchain.bzl", "template_toolchain")

        toolchain_type(name = "toolchain_type")

        template_toolchain(
            name = "data",
            vars = {
                "foo": "bar",
            },
        )

        toolchain(
            name = "data_impl",
            toolchain_type = ":toolchain_type",
            toolchain = ":data",
        )
        
        """.trimIndent()
        )

        // Write a BUILD file that uses the toolchain type.
        write(
            "test/BUILD",
            """

genrule(
    name = "g",
    srcs = [],
    outs = ["g.out"],
    cmd = "echo foo: ${'$'}(foo) > ${'$'}@",
    toolchains = ["//test/toolchain:toolchain_type"],
)

""".trimIndent()
        )

        // Make sure the toolchain is available.
        addOptions("--extra_toolchains=//test/toolchain:data_impl")
        buildTarget("//test:g")
        val output: OutputFileConfiguredTarget =
            getConfiguredTarget("//test:g.out") as OutputFileConfiguredTarget
        Truth.assertThat(readContentAsLatin1String(output.getArtifact())).isEqualTo("foo: bar\n")
    }

    @Test
    @Throws(Exception::class)
    fun testToolchains_fromToolchain_noToolchainFound() {
        // Define a toolchain type.
        write(
            "test/toolchain/BUILD",
            """
        toolchain_type(name = "toolchain_type")
        
        """.trimIndent()
        )

        // Write a BUILD file that uses the toolchain type.
        write(
            "test/BUILD",
            """

genrule(
    name = "g",
    srcs = [],
    outs = ["g.out"],
    cmd = "echo foo: ${'$'}(foo) > ${'$'}@",
    toolchains = ["//test/toolchain:toolchain_type"],
)

""".trimIndent()
        )

        Assert.assertThrows<T?>(ViewCreationFailedException::class.java, ThrowingRunnable { buildTarget("//test:g") })
        assertContainsError("$(foo) not defined")
    }

    @Test
    @Throws(Exception::class)
    fun testToolchains_fromToolchain_noToolchainFound_unused() {
        // Define a toolchain type.
        write(
            "test/toolchain/BUILD",
            """
        toolchain_type(name = "toolchain_type")
        
        """.trimIndent()
        )

        // Write a BUILD file that uses the toolchain type.
        write(
            "test/BUILD",
            """

genrule(
    name = "g",
    srcs = [],
    outs = ["g.out"],
    cmd = "echo no template variables used > ${'$'}@",
    toolchains = ["//test/toolchain:toolchain_type"],
)

""".trimIndent()
        )

        // Invoke the target, even though the toolchain isn't resolved.
        buildTarget("//test:g")
        val output: OutputFileConfiguredTarget =
            getConfiguredTarget("//test:g.out") as OutputFileConfiguredTarget
        Truth.assertThat(readContentAsLatin1String(output.getArtifact()))
            .isEqualTo("no template variables used\n")
    }

    companion object {
        private fun cleanNewlines(input: String): String? {
            return input.replace("\r\n".toRegex(), "\n")
        }
    }
}
