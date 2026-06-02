// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Joiner
import com.google.devtools.build.lib.analysis.actions.SpawnAction
import org.junit.Test
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * A unit test of the various kinds of label and "Make"-variable substitutions that are applied to
 * the genrule "cmd" attribute.
 * 
 * 
 * Some of these tests are similar to tests in LabelExpanderTest and MakeVariableExpanderTest,
 * but this test case exercises the composition of these various transformations.
 */
@RunWith(JUnit4::class)
class GenRuleCommandSubstitutionTest : BuildViewTestCase() {
    @Throws(Exception::class)
    private fun getGenruleCommand(genrule: String?): String {
        return (getGeneratingAction(
            BuildViewTestCase.Companion.getFilesToBuild(getConfiguredTarget(genrule)).toList().get(0)
        ) as SpawnAction)
            .getArguments()
            .get(2)
    }

    @Throws(Exception::class)
    private fun assertExpansionEquals(expected: String, genrule: String?) {
        val command = getGenruleCommand(genrule)
        assertCommandEquals(expected, command)
    }

    @Throws(Exception::class)
    private fun assertExpansionFails(expectedErrorSuffix: String?, genrule: String?) {
        reporter.removeHandler(FoundationTestCase.failFastHandler) // we expect errors
        eventCollector.clear()
        getConfiguredTarget(genrule)
        assertContainsEvent(expectedErrorSuffix)
    }

    // Creates a BUILD file defining a genrule called "//test" with no srcs or
    // deps, one output and the specified command.
    @Throws(Exception::class)
    private fun genrule(command: String?) {
        scratch.overwriteFile(
            "test/BUILD",  // This is a horrible workaround for b/147306893:
            // somehow, duplicate events (same location, same message)
            // are being suppressed, so we must vary the location of the
            // genrule by inserting a unique number of newlines.
            String(CharArray(seq++)).replace('\u0000', '\n'),
            "genrule(name = 'test',",
            "        outs = ['out'],",
            "        cmd = '" + command + "')"
        )

        // Since we're probably re-defining "//test":
        invalidatePackages()
    }

    private var seq = 0

    @Test
    @Throws(Exception::class)
    fun testLocationSyntaxErrors() {
        genrule("$(location )")
        assertExpansionFails(
            "invalid label in $(location) expression: invalid target name '': empty target name",
            "//test"
        )

        genrule("$(location foo bar")
        assertExpansionFails("unterminated variable reference", "//test")

        genrule("$(location")
        assertExpansionFails("unterminated variable reference", "//test")

        genrule("$(locationz")
        assertExpansionFails("unterminated variable reference", "//test")

        genrule("$(locationz)")
        assertExpansionFails("$(locationz) not defined", "//test")

        genrule("$(locationz )")
        assertExpansionFails("$(locationz) not defined", "//test")

        genrule("$(locationz foo )")
        assertExpansionFails("$(locationz) not defined", "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testLocationOfLabelThatIsNotAPrerequsite() {
        scratch.file(
            "test/BUILD",
            """
        exports_files(["exists"])

        genrule(
            name = "test1",
            outs = ["test1.out"],
            cmd = "${'$'}(location :exists)",
        )

        genrule(
            name = "test2",
            outs = ["test2.out"],
            cmd = "${'$'}(location :doesnt_exist)",
        )
        
        """.trimIndent()
        )

        // $(location) of a non-prerequisite fails, even if the target exists:
        assertExpansionFails(
            "label '//test:exists' in $(location) expression is "
                    + "not a declared prerequisite of this rule",
            "//test:test1"
        )

        assertExpansionFails(
            "label '//test:doesnt_exist' in $(location) expression is "
                    + "not a declared prerequisite of this rule",
            "//test:test2"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testLocationOfMultiFileLabel() {
        scratch.file(
            "deuce/BUILD",
            """
        genrule(
            name = "deuce",
            outs = [
                "out.1",
                "out.2",
            ],
            cmd = ":",
        )
        
        """.trimIndent()
        )
        checkError(
            "test",
            "test1",
            "label '//deuce:deuce' in $(location) expression expands to more than one "
                    + "file, please use $(locations //deuce:deuce) instead",
            "genrule(name = 'test1',",
            "        tools = ['//deuce'],",
            "        outs = ['test1.out'],",
            "        cmd = '$(location //deuce)')"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testUnknownVariable() {
        genrule("$(UNKNOWN)")
        assertExpansionFails("$(UNKNOWN) not defined", "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testLocationOfSourceLabel() {
        scratch.file(
            "test1/BUILD",
            """
        genrule(
            name = "test1",
            srcs = ["src"],
            outs = ["out"],
            cmd = "${'$'}(location //test1:src)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals("test1/src", "//test1")

        scratch.file(
            "test2/BUILD",
            """
        genrule(
            name = "test2",
            srcs = ["src"],
            outs = ["out"],
            cmd = "${'$'}(location src)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals("test2/src", "//test2")

        scratch.file(
            "test3/BUILD",
            """
        genrule(
            name = "test3",
            srcs = ["src"],
            outs = ["out"],
            cmd = "${'$'}(location :src)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals("test3/src", "//test3")
    }

    @Test
    @Throws(Exception::class)
    fun testLocationOfOutputLabel() {
        val gendir: String? = targetConfig.getMakeVariableDefault("GENDIR")
        scratch.file(
            "test1/BUILD",
            """
        genrule(
            name = "test1",
            outs = ["out"],
            cmd = "${'$'}(location //test1:out)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals(gendir + "/test1/out", "//test1")

        scratch.file(
            "test2/BUILD",
            """
        genrule(
            name = "test2",
            outs = ["out"],
            cmd = "${'$'}(location out)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals(gendir + "/test2/out", "//test2")

        scratch.file(
            "test3/BUILD",
            """
        genrule(
            name = "test3",
            outs = ["out"],
            cmd = "${'$'}(location out)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals(gendir + "/test3/out", "//test3")
    }

    @Test
    @Throws(Exception::class)
    fun testLocationsSyntaxErrors() {
        genrule("$(locations )")
        assertExpansionFails(
            "invalid label in $(locations) expression: invalid target name '': empty target name",
            "//test"
        )

        genrule("$(locations foo bar")
        assertExpansionFails("unterminated variable reference", "//test")

        genrule("$(locations")
        assertExpansionFails("unterminated variable reference", "//test")

        genrule("$(locationsz")
        assertExpansionFails("unterminated variable reference", "//test")

        genrule("$(locationsz)")
        assertExpansionFails("$(locationsz) not defined", "//test")

        genrule("$(locationsz )")
        assertExpansionFails("$(locationsz) not defined", "//test")

        genrule("$(locationsz foo )")
        assertExpansionFails("$(locationsz) not defined", "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testLocationsOfLabelThatIsNotAPrerequsite() {
        scratch.file(
            "test/BUILD",
            """
        exports_files(["exists"])

        genrule(
            name = "test1",
            outs = ["test1.out"],
            cmd = "${'$'}(locations :exists)",
        )

        genrule(
            name = "test2",
            outs = ["test2.out"],
            cmd = "${'$'}(locations :doesnt_exist)",
        )
        
        """.trimIndent()
        )

        // $(locations) of a non-prerequisite fails, even if the target exists:
        assertExpansionFails(
            "label '//test:exists' in $(locations) expression is "
                    + "not a declared prerequisite of this rule",
            "//test:test1"
        )

        assertExpansionFails(
            "label '//test:doesnt_exist' in $(locations) expression is "
                    + "not a declared prerequisite of this rule",
            "//test:test2"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testLocationsOfMultiFileLabel() {
        val gendir: String? = targetConfig.getMakeVariableDefault("GENDIR")
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "x",
            srcs = ["src"],
            outs = [
                "out1",
                "out2",
            ],
            cmd = ":",
        )

        genrule(
            name = "y",
            srcs = ["x"],
            outs = ["out"],
            cmd = "${'$'}(locations x)",
        )
        
        """.trimIndent()
        )

        assertExpansionEquals(gendir + "/test/out1 " + gendir + "/test/out2", "//test:y")
    }

    @Test
    @Throws(Exception::class)
    fun testLocationLocationsAndLabel() {
        val gendir: String = targetConfig.getMakeVariableDefault("GENDIR")
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "x",
            srcs = ["src"],
            outs = ["out"],
            cmd = ":",
        )

        genrule(
            name = "y",
            srcs = ["src"],
            outs = [
                "out1",
                "out2",
            ],
            cmd = ":",
        )

        genrule(
            name = "r",
            srcs = [
                "x",
                "y",
                "z",
            ],
            outs = ["res"],
            cmd = " _ ${'$'}(location x) _ ${'$'}(locations y) _ ",
        )
        
        """.trimIndent()
        )

        val expected =
            "_ " + gendir + "/test/out _ " + gendir + "/test/out1 " + gendir + "/test/out2 _ "
        assertExpansionEquals(expected, "//test:r")
    }

    @Test
    @Throws(Exception::class)
    fun testLocationsOfSourceLabel() {
        scratch.file(
            "test1/BUILD",
            """
        genrule(
            name = "test1",
            srcs = ["src"],
            outs = ["out"],
            cmd = "${'$'}(locations //test1:src)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals("test1/src", "//test1")

        scratch.file(
            "test2/BUILD",
            """
        genrule(
            name = "test2",
            srcs = ["src"],
            outs = ["out"],
            cmd = "${'$'}(locations src)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals("test2/src", "//test2")

        scratch.file(
            "test3/BUILD",
            """
        genrule(
            name = "test3",
            srcs = ["src"],
            outs = ["out"],
            cmd = "${'$'}(location :src)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals("test3/src", "//test3")
    }

    @Test
    @Throws(Exception::class)
    fun testLocationsOfOutputLabel() {
        val gendir: String? = targetConfig.getMakeVariableDefault("GENDIR")
        scratch.file(
            "test1/BUILD",
            """
        genrule(
            name = "test1",
            outs = ["out"],
            cmd = "${'$'}(locations //test1:out)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals(gendir + "/test1/out", "//test1")

        scratch.file(
            "test2/BUILD",
            """
        genrule(
            name = "test2",
            outs = ["out"],
            cmd = "${'$'}(locations out)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals(gendir + "/test2/out", "//test2")

        scratch.file(
            "test3/BUILD",
            """
        genrule(
            name = "test3",
            outs = ["out"],
            cmd = "${'$'}(locations out)",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals(gendir + "/test3/out", "//test3")
    }

    @Test
    @Throws(Exception::class)
    fun testOuts() {
        val expected = targetConfig.getMakeVariableDefault("GENDIR") + "/test/out"
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "test",
            outs = ["out"],
            cmd = "${'$'}(OUTS) # ${'$'}@",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals(expected + " # " + expected, "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testSrcs() {
        val expected = "test/src"

        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "test",
            srcs = ["src"],
            outs = ["out"],
            cmd = "${'$'}(SRCS) # ${'$'}<",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals(expected + " # " + expected, "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testDollarDollar() {
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "test",
            outs = ["out"],
            cmd = "${'$'}${'$'}DOLLAR",
        )
        
        """.trimIndent()
        )
        assertExpansionEquals("\$DOLLAR", "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testDollarLessThanWithZeroInputs() {
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "test",
            outs = ["out"],
            cmd = "${'$'}<",
        )
        
        """.trimIndent()
        )
        assertExpansionFails("variable '$<' : no input file", "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testDollarLessThanWithMultipleInputs() {
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "test",
            srcs = [
                "src1",
                "src2",
            ],
            outs = ["out"],
            cmd = "${'$'}<",
        )
        
        """.trimIndent()
        )
        assertExpansionFails("variable '$<' : more than one input file", "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testDollarAtWithMultipleOutputs() {
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "test",
            outs = [
                "out.1",
                "out.2",
            ],
            cmd = "${'$'}@",
        )
        
        """.trimIndent()
        )
        assertExpansionFails("variable '$@' : more than one output file", "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testDollarAtWithZeroOutputs() {
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = "test",
            srcs = [
                "src1",
                "src2",
            ],
            outs = [],
            cmd = "${'$'}@",
        )
        
        """.trimIndent()
        )
        assertExpansionFails("Genrules without outputs don't make sense", "//test")
    }

    @Test
    @Throws(Exception::class)
    fun testShellVariables() {
        genrule("for file in a b c;do echo $\$file;done")
        assertExpansionEquals("for file in a b c;do echo \$file;done", "//test")
        assertNoEvents()

        genrule("$\${file%:.*8}")
        assertExpansionEquals("\${file%:.*8}", "//test")
        assertNoEvents()

        genrule("$$(basename file)")
        assertExpansionEquals("$(basename file)", "//test")
        assertNoEvents()

        genrule("$(basename file)")
        assertExpansionFails("$(basename) not defined", "//test")
        assertContainsEvent("$(basename) not defined")
    }

    @Test
    @Throws(Exception::class)
    fun heuristicLabelExpansion_singletonFilegroupInTools_expandsToFile() {
        scratch.file(
            "foo/BUILD",
            """
        filegroup(
            name = "fg",
            srcs = ["fg1.txt"],
        )

        genrule(
            name = "gen",
            outs = ["gen.out"],
            cmd = "cp :fg ${'$'}@",
            heuristic_label_expansion = True,
            tools = [":fg"],
        )
        
        """.trimIndent()
        )

        Truth.assertThat(getGenruleCommand("//foo:gen")).contains("foo/fg1.txt")
    }

    @Test
    @Throws(Exception::class)
    fun heuristicLabelExpansion_emptyFilegroupInTools_fails() {
        scratch.file(
            "foo/BUILD",
            """
        filegroup(
            name = "fg",
            srcs = [],
        )

        genrule(
            name = "gen",
            outs = ["gen.out"],
            cmd = "cp :fg ${'$'}@",
            heuristic_label_expansion = True,
            tools = [":fg"],
        )
        
        """.trimIndent()
        )

        assertExpansionFails("expands to 0 files", "//foo:gen")
    }

    @Test
    @Throws(Exception::class)
    fun heuristicLabelExpansion_multiFilegroupInTools_fails() {
        scratch.file(
            "foo/BUILD",
            """
        filegroup(
            name = "fg",
            srcs = [
                "fg1.txt",
                "fg2.txt",
            ],
        )

        genrule(
            name = "gen",
            outs = ["gen.out"],
            cmd = "cp :fg ${'$'}@",
            heuristic_label_expansion = True,
            tools = [":fg"],
        )
        
        """.trimIndent()
        )

        assertExpansionFails("expands to 2 files", "//foo:gen")
    }

    @Test
    @Throws(Exception::class)
    fun testDollarFileFails() {
        checkError(
            "test",
            "test",
            "'\$file' syntax is not supported; use '$(file)' ",
            getBuildFileWithCommand("for file in a b c;do echo \$file;done")
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDollarFile2Fails() {
        checkError(
            "test",
            "test",
            "'\${file%:.*8}' syntax is not supported; use '$(file%:.*8)' ",
            getBuildFileWithCommand("\${file%:.*8}")
        )
    }

    companion object {
        private val SETUP_COMMAND_PATTERN: Pattern = Pattern.compile(".*/genrule-setup.sh;\\s+(?<command>.*)")

        private fun assertCommandEquals(expected: String, command: String?) {
            // Ensure the command after the genrule setup is correct.
            var command = command
            val m: Matcher = SETUP_COMMAND_PATTERN.matcher(command)
            if (m.matches()) {
                command = m.group("command")
            }

            Truth.assertWithMessage("Expected command to be \"%s\", but found \"%s\"", expected, command)
                .that(command)
                .isEqualTo(expected)
        }

        private fun getBuildFileWithCommand(command: String?): String {
            return Joiner.on("\n")
                .join(
                    "genrule(name = 'test',",
                    "        outs = ['out'],",
                    "        cmd = '" + command + "')"
                )
        }
    }
}
