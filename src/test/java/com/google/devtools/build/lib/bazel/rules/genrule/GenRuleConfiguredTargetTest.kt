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

import com.google.common.truth.Subject
import com.google.devtools.build.lib.actions.Action
import org.junit.Assert
import org.junit.Test
import java.util.regex.Matcher
import java.util.regex.Pattern

/** Tests of [BazelGenRule].  */
@RunWith(JUnit4::class)
class GenRuleConfiguredTargetTest : BuildViewTestCase() {
    @Throws(Exception::class)
    fun createFiles() {
        scratch.file(
            "hello/BUILD",
            """
        genrule(
            name = "z",
            outs = ["x/y"],
            cmd = "echo hi > ${'$'}(@D)/y",
        )

        genrule(
            name = "w",
            outs = [
                "a/b",
                "c/d",
            ],
            cmd = "echo hi | tee ${'$'}(@D)/a/b ${'$'}(@D)/c/d",
        )
        
        """.trimIndent()
        )
    }

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        return builder.addRuleDefinition(MakeVariableTesterRule()).build()
    }

    @Test
    @Throws(Exception::class)
    fun testToolchainOverridesJavabase() {
        scratch.file(
            "a/BUILD",
            """
        genrule(
            name = "gr",
            srcs = [],
            outs = ["out"],
            cmd = "JAVABASE=${'$'}(JAVABASE)",
            toolchains = [":v"],
        )

        make_variable_tester(
            name = "v",
            variables = {"JAVABASE": "REPLACED"},
        )
        
        """.trimIndent()
        )

        val cmd = getCommand("//a:gr")
        Truth.assertThat(cmd).endsWith("JAVABASE=REPLACED")
    }

    @Test
    @Throws(Exception::class)
    fun testD() {
        createFiles()
        val z: ConfiguredTarget? = getConfiguredTarget("//hello:z")
        val y: Artifact = BuildViewTestCase.Companion.getFilesToBuild(z).getSingleton()
        assertThat(y.getRootRelativePath()).isEqualTo(PathFragment.create("hello/x/y"))
    }

    @Test
    @Throws(Exception::class)
    fun testDMultiOutput() {
        createFiles()
        val z: ConfiguredTarget? = getConfiguredTarget("//hello:w")
        val files: MutableList<Artifact?>? = BuildViewTestCase.Companion.getFilesToBuild(z).toList()
        Truth.assertThat(files).hasSize(2)
        assertThat(files!!.get(0).getRootRelativePath()).isEqualTo(PathFragment.create("hello/a/b"))
        assertThat(files.get(1).getRootRelativePath()).isEqualTo(PathFragment.create("hello/c/d"))
    }

    @Test
    @Throws(Exception::class)
    fun testOutsWithSameNameAsRule() {
        // The error was demoted to a warning.
        // Re-enable after June 1 2008 when we make it an error again.
        checkWarning(
            "genrule2",
            "hello_world",
            "target 'hello_world' is both a rule and a file;",
            "genrule(name = 'hello_world',",
            "srcs = ['ignore_me.txt'],",
            "outs = ['message.txt', 'hello_world'],",
            "cmd  = 'echo \"Hello, world.\" >$(location message.txt)')"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testFilesToBuildIsOuts() {
        scratch.file(
            "genrule1/BUILD",
            """
        genrule(
            name = "hello_world",
            srcs = ["ignore_me.txt"],
            outs = ["message.txt"],
            cmd = 'echo "Hello, world." >${'$'}(location message.txt)',
        )
        
        """.trimIndent()
        )
        val messageArtifact: Artifact? = getFileConfiguredTarget("//genrule1:message.txt").getArtifact()
        assertThat(BuildViewTestCase.Companion.getFilesToBuild(getConfiguredTarget("//genrule1:hello_world")).toList())
            .containsExactly(messageArtifact)
    }

    @Test
    @Throws(Exception::class)
    fun testActionIsShellCommand() {
        scratch.file(
            "genrule1/BUILD",
            """
        genrule(
            name = "hello_world",
            srcs = ["ignore_me.txt"],
            outs = ["message.txt"],
            cmd = 'echo "Hello, world." >${'$'}(location message.txt)',
        )
        
        """.trimIndent()
        )

        val messageArtifact: Artifact = getFileConfiguredTarget("//genrule1:message.txt").getArtifact()
        val shellAction: SpawnAction? = getGeneratingAction(messageArtifact) as SpawnAction?

        val ignoreMeArtifact: Artifact? = getFileConfiguredTarget("//genrule1:ignore_me.txt").getArtifact()
        val genruleSetupArtifact: Artifact? = getFileConfiguredTarget(TestConstants.GENRULE_SETUP).getArtifact()

        assertThat(shellAction).isNotNull()
        assertThat(shellAction.getInputs().toList())
            .containsExactly(ignoreMeArtifact, genruleSetupArtifact)
        assertThat(shellAction.getOutputs()).containsExactly(messageArtifact)

        val expected = "echo \"Hello, world.\" >" + messageArtifact.getExecPathString()
        assertThat(shellAction.getArguments().get(0))
            .isEqualTo(ShToolchain.getPathForHost(targetConfig).getPathString())
        assertThat(shellAction.getArguments().get(1)).isEqualTo("-c")
        assertCommandEquals(expected, shellAction.getArguments().get(2))
    }

    @Test
    @Throws(Exception::class)
    fun testDependentGenrule() {
        scratch.file(
            "genrule1/BUILD",
            """
        genrule(
            name = "hello_world",
            srcs = ["ignore_me.txt"],
            outs = ["message.txt"],
            cmd = 'echo "Hello, world." >${'$'}(location message.txt)',
        )
        
        """.trimIndent()
        )
        scratch.file(
            "genrule2/BUILD",
            """
        genrule(
            name = "goodbye_world",
            srcs = [
                "goodbye.txt",
                "//genrule1:hello_world",
            ],
            outs = ["farewell.txt"],
            cmd = "echo ${'$'}(SRCS) >${'$'}(location farewell.txt)",
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//genrule2:goodbye_world")

        val farewellArtifact: Artifact = getFileConfiguredTarget("//genrule2:farewell.txt").getArtifact()
        val goodbyeArtifact: Artifact = getFileConfiguredTarget("//genrule2:goodbye.txt").getArtifact()
        val messageArtifact: Artifact = getFileConfiguredTarget("//genrule1:message.txt").getArtifact()
        val genruleSetupArtifact: Artifact? = getFileConfiguredTarget(TestConstants.GENRULE_SETUP).getArtifact()

        val shellAction: SpawnAction? = getGeneratingAction(farewellArtifact) as SpawnAction?

        // inputs = { "goodbye.txt", "//genrule1:message.txt" }
        assertThat(shellAction.getInputs().toList())
            .containsExactly(goodbyeArtifact, messageArtifact, genruleSetupArtifact)

        // outputs = { "farewell.txt" }
        assertThat(shellAction.getOutputs()).containsExactly(farewellArtifact)

        val expected =
            ("echo "
                    + goodbyeArtifact.getExecPathString()
                    + " "
                    + messageArtifact.getExecPathString()
                    + " >"
                    + farewellArtifact.getExecPathString())
        assertCommandEquals(expected, shellAction.getArguments().get(2))
    }

    /**
     * Ensure that the actions / artifacts created by genrule dependencies allow us to follow the
     * chain of generated files backward.
     */
    @Test
    @Throws(Exception::class)
    fun testDependenciesViaFiles() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "bar",
            srcs = ["bar_in.txt"],
            outs = ["bar_out.txt"],
            cmd = "touch ${'$'}(OUTS)",
        )

        genrule(
            name = "baz",
            srcs = ["bar_out.txt"],
            outs = ["baz_out.txt"],
            cmd = "touch ${'$'}(OUTS)",
        )
        
        """.trimIndent()
        )

        val bazOutTarget: FileConfiguredTarget = getFileConfiguredTarget("//foo:baz_out.txt")
        val bazAction: Action? = getGeneratingAction(bazOutTarget.getArtifact())
        val barOut: Artifact = bazAction.getInputs().toList().get(0)
        assertThat(barOut.getExecPath().endsWith(PathFragment.create("foo/bar_out.txt"))).isTrue()
        val barAction: Action? = getGeneratingAction(barOut)
        val barIn: Artifact = barAction.getInputs().toList().get(0)
        assertThat(barIn.getExecPath().endsWith(PathFragment.create("foo/bar_in.txt"))).isTrue()
    }

    /** Ensure that variable $(@D) gets expanded correctly in the genrule cmd.  */
    @Test
    @Throws(Exception::class)
    fun testOutputDirExpansion() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "bar",
            srcs = ["bar_in.txt"],
            outs = ["bar/bar_out.txt"],
            cmd = "touch ${'$'}(@D)",
        )

        genrule(
            name = "baz",
            srcs = ["bar/bar_out.txt"],
            outs = [
                "logs/baz_out.txt",
                "logs/baz.log",
            ],
            cmd = "touch ${'$'}(@D)",
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//foo:bar")

        val bazOutTarget: FileConfiguredTarget = getFileConfiguredTarget("//foo:logs/baz_out.txt")

        val bazAction: SpawnAction? = getGeneratingAction(bazOutTarget.getArtifact()) as SpawnAction?

        // Make sure the expansion for $(@D) results in the
        // directory of the BUILD file ("foo"), not the common parent
        // directory of the output files ("logs")
        val bazExpected =
            ("touch "
                    + bazOutTarget
                .getArtifact()
                .getExecPath()
                .getParentDirectory()
                .getParentDirectory()
                .getPathString())
        assertCommandEquals(bazExpected, bazAction.getArguments().get(2))
        assertThat(bazAction.getArguments().get(2)).endsWith("/foo")

        getConfiguredTarget("//foo:bar")

        val barOut: Artifact = bazAction.getInputs().toList().get(0)
        assertThat(barOut.getExecPath().endsWith(PathFragment.create("foo/bar/bar_out.txt"))).isTrue()
        val barAction: SpawnAction? = getGeneratingAction(barOut) as SpawnAction?
        val barExpected = "touch " + barOut.getExecPath().getParentDirectory().getPathString()
        assertCommandEquals(barExpected, barAction.getArguments().get(2))
        Truth.assertThat(bazExpected == barExpected).isFalse()
    }

    /** Ensure that variable $(RULE_DIR) gets expanded correctly in the genrule cmd.  */
    @Test
    @Throws(Exception::class)
    fun testRuleDirExpansion() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "bar",
            srcs = ["bar_in.txt"],
            outs = ["bar/bar_out.txt"],
            cmd = "touch ${'$'}(RULEDIR)",
        )

        genrule(
            name = "baz",
            srcs = ["bar/bar_out.txt"],
            outs = [
                "baz/baz_out.txt",
                "logs/baz.log",
            ],
            cmd = "touch ${'$'}(RULEDIR)",
        )
        
        """.trimIndent()
        )

        // Make sure the expansion for $(RULE_DIR) results in the directory of the BUILD file ("foo")
        val expectedRegex = "touch b.{4}-out.*foo"
        Truth.assertThat(getCommand("//foo:bar")).containsMatch(expectedRegex)
        Truth.assertThat(getCommand("//foo:baz")).containsMatch(expectedRegex)
    }

    // Returns the expansion of 'cmd' for the specified genrule.
    @Throws(Exception::class)
    private fun getCommand(label: String?): String {
        return getSpawnAction(label).getArguments().get(2)
    }

    // Returns the SpawnAction for the specified genrule.
    @Throws(Exception::class)
    private fun getSpawnAction(label: String?): SpawnAction {
        return getGeneratingAction(
            BuildViewTestCase.Companion.getFilesToBuild(getConfiguredTarget(label)).toList().get(0)
        ) as SpawnAction
    }

    @Test
    @Throws(Exception::class)
    fun testMessage() {
        scratch.file(
            "genrule3/BUILD",
            """
        genrule(
            name = "hello_world",
            srcs = ["ignore_me.txt"],
            outs = ["hello.txt"],
            cmd = 'echo "Hello, world." >hello.txt',
        )

        genrule(
            name = "goodbye_world",
            srcs = ["ignore_me.txt"],
            outs = ["goodbye.txt"],
            cmd = 'echo "Goodbye, world." >goodbye.txt',
            message = "Generating message",
        )
        
        """.trimIndent()
        )
        assertThat(getSpawnAction("//genrule3:hello_world").getProgressMessage())
            .isEqualTo("Executing genrule //genrule3:hello_world")
        assertThat(getSpawnAction("//genrule3:goodbye_world").getProgressMessage())
            .isEqualTo("Generating message //genrule3:goodbye_world")
    }

    /** Ensure that labels from binary targets expand to the executable  */
    @Test
    @Throws(Exception::class)
    fun testBinaryTargetsExpandToExecutable() {
        scratch.file(
            "genrule3/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")

        genrule(
            name = "hello_world",
            srcs = ["ignore_me.txt"],
            outs = ["message.txt"],
            cmd = '${'$'}(location :echo) "Hello, world." >message.txt',
            tools = ["echo"],
        )

        cc_binary(
            name = "echo",
            srcs = ["echo.cc"],
        )
        
        """.trimIndent()
        )
        val regex = "b.{4}-out/.*/bin/genrule3/echo(\\.exe)? \"Hello, world.\" >message.txt"
        Truth.assertThat(getCommand("//genrule3:hello_world")).containsMatch(regex)
    }

    @Test
    @Throws(Exception::class)
    fun testOutputToBindir() {
        scratch.file(
            "x/BUILD",
            """
        genrule(
            name = "bin",
            outs = ["bin.out"],
            cmd = ":",
            output_to_bindir = 1,
        )

        genrule(
            name = "genfiles",
            outs = ["genfiles.out"],
            cmd = ":",
            output_to_bindir = 0,
        )
        
        """.trimIndent()
        )

        assertThat(getFileConfiguredTarget("//x:bin.out").getArtifact())
            .isEqualTo(getBinArtifact("bin.out", getConfiguredTarget("//x:bin")))
        assertThat(getFileConfiguredTarget("//x:genfiles.out").getArtifact())
            .isEqualTo(getGenfilesArtifact("genfiles.out", "//x:genfiles"))
    }

    @Test
    @Throws(Exception::class)
    fun testMultipleOutputsToBindir() {
        scratch.file(
            "x/BUILD",
            """
        genrule(
            name = "bin",
            outs = [
                "bin_a.out",
                "bin_b.out",
            ],
            cmd = ":",
            output_to_bindir = 1,
        )

        genrule(
            name = "genfiles",
            outs = [
                "genfiles_a.out",
                "genfiles_b.out",
            ],
            cmd = ":",
            output_to_bindir = 0,
        )
        
        """.trimIndent()
        )

        val binCt: ConfiguredTarget? = getConfiguredTarget("//x:bin")
        val genCt: ConfiguredTarget? = getConfiguredTarget("//x:genfiles")
        assertThat(getFileConfiguredTarget("//x:bin_a.out").getArtifact())
            .isEqualTo(getBinArtifact("bin_a.out", binCt))
        assertThat(getFileConfiguredTarget("//x:bin_b.out").getArtifact())
            .isEqualTo(getBinArtifact("bin_b.out", binCt))
        assertThat(getFileConfiguredTarget("//x:genfiles_a.out").getArtifact())
            .isEqualTo(getGenfilesArtifact("genfiles_a.out", genCt))
        assertThat(getFileConfiguredTarget("//x:genfiles_b.out").getArtifact())
            .isEqualTo(getGenfilesArtifact("genfiles_b.out", genCt))
    }

    @Test
    @Throws(Exception::class)
    fun testMultipleOutsPreservesOrdering() {
        scratch.file(
            "multiple/outs/BUILD",
            """
        genrule(
            name = "test",
            outs = [
                "file1.out",
                "file2.out",
            ],
            cmd = "touch ${'$'}(OUTS)",
        )
        
        """.trimIndent()
        )
        val regex =
            ("touch b.{4}-out/.*/multiple/outs/file1.out "
                    + "b.{4}-out/.*/multiple/outs/file2.out")
        Truth.assertThat(getCommand("//multiple/outs:test")).containsMatch(regex)
    }

    @Test
    @Throws(Exception::class)
    fun testToolsAreExecConfiguration() {
        scratch.file(
            "config/BUILD",
            """
        genrule(
            name = "src",
            outs = ["src.out"],
            cmd = ":",
        )

        genrule(
            name = "tool",
            outs = ["tool.out"],
            cmd = ":",
        )

        genrule(
            name = "config",
            srcs = [":src"],
            outs = ["out"],
            cmd = "${'$'}(location :tool)",
            tools = [":tool"],
        )
        
        """.trimIndent()
        )

        val parentTarget: ConfiguredTarget? = getConfiguredTarget("//config")

        val prereqs: Iterable<ConfiguredTarget> = getDirectPrerequisites(parentTarget)

        var foundSrc = false
        var foundTool = false
        var foundSetup = false
        for (prereq in prereqs) {
            val name: String = prereq.getLabel().getName()
            when (name) {
                "src" -> {
                    BuildViewTestCase.Companion.assertConfigurationsEqual(
                        getConfiguration(parentTarget),
                        getConfiguration(prereq)
                    )
                    foundSrc = true
                }

                "tool" -> {
                    assertThat(getConfiguration(prereq).isToolConfiguration()).isTrue()
                    foundTool = true
                }

                TestConstants.GENRULE_SETUP_PATH -> {
                    assertThat(getConfiguration(prereq)).isNull()
                    foundSetup = true
                }

                "host" -> {}
                else -> Assert.fail("unexpected prerequisite " + prereq + " (name: " + name + ")")
            }
        }

        Truth.assertThat(foundSrc).isTrue()
        Truth.assertThat(foundTool).isTrue()
        Truth.assertThat(foundSetup).isTrue()
    }

    @Test
    @Throws(Exception::class)
    fun testLabelsContainingAtDAreExpanded() {
        scratch.file(
            "puck/BUILD",
            """
        genrule(
            name = "gen",
            outs = ["out"],
            cmd = "echo ${'$'}(@D)",
            tools = ["puck"],
        )
        
        """.trimIndent()
        )
        val regex = "echo b.{4}-out/.*/puck"
        Truth.assertThat(getCommand("//puck:gen")).containsMatch(regex)
    }

    @Test
    @Throws(Exception::class)
    fun testGetExecutable() {
        val turtle: ConfiguredTarget? =
            scratchConfiguredTarget(
                "java/com/google/turtle",
                "turtle_bootstrap",
                "genrule(name = 'turtle_bootstrap',",
                "    srcs = ['Turtle.java'],",
                "    outs = ['turtle'],",
                "    executable = 1,",
                "    cmd = 'touch $(OUTS)')"
            )
        assertThat(getExecutable(turtle).getExecPath().getBaseName()).isEqualTo("turtle")
    }

    @Test
    @Throws(Exception::class)
    fun testGetExecutableForNonExecutableOut() {
        val turtle: ConfiguredTarget? =
            scratchConfiguredTarget(
                "java/com/google/turtle",
                "turtle_bootstrap",
                "genrule(name = 'turtle_bootstrap',",
                "    srcs = ['Turtle.java'],",
                "    outs = ['debugdata.txt'],",
                "    cmd = 'touch $(OUTS)')"
            )
        Truth.assertThat(getExecutable(turtle)).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testGetExecutableForMultipleOuts() {
        val turtle: ConfiguredTarget? =
            scratchConfiguredTarget(
                "java/com/google/turtle",
                "turtle_bootstrap",
                "genrule(name = 'turtle_bootstrap',",
                "    srcs = ['Turtle.java'],",
                "    outs = ['turtle', 'debugdata.txt'],",
                "    cmd = 'touch $(OUTS)')"
            )
        Truth.assertThat(getExecutable(turtle)).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun testGetExecutableFailsForMultipleOutputs() {
        // Multiple output files are invalid when executable=1.
        checkError(
            "bad",
            "bad",
            ("in executable attribute of genrule rule //bad:bad: "
                    + "if genrules produce executables, they are allowed only one output. "
                    + "If you need the executable=1 argument, then you should split this genrule into "
                    + "genrules producing single outputs"),
            "genrule(name = 'bad',",
            "        outs = [ 'bad_out1', 'bad_out2' ],",
            "        executable = 1,",
            "        cmd = 'touch $(OUTS)')"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testEmptyOutsError() {
        checkError(
            "x",
            "x",
            "Genrules without outputs don't make sense",
            "genrule(name = 'x', outs = [], cmd='echo')"
        )
    }

    @Test
    @Throws(Exception::class)
    fun testGenruleSetup() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = "foo_sh",
            outs = ["foo.sh"],  # Shell script files are known to be executable.
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )

        Truth.assertThat(getCommand("//foo:foo_sh")).contains(TestConstants.GENRULE_SETUP_PATH)
    }

    @Throws(Exception::class)
    private fun createStampingTargets() {
        scratch.file(
            "u/BUILD",
            """
        genrule(
            name = "foo_stamp",
            srcs = [],
            outs = ["uu"],
            cmd = "",
            stamp = 1,
        )

        genrule(
            name = "foo_nostamp",
            srcs = [],
            outs = ["vv"],
            cmd = "",
            stamp = 0,
        )

        genrule(
            name = "foo_autostamp",
            srcs = [],
            outs = ["aa"],
            cmd = "",
            stamp = -1,
        )

        genrule(
            name = "foo_default",
            srcs = [],
            outs = ["xx"],
            cmd = "",
        )
        
        """.trimIndent()
        )
    }

    @Throws(Exception::class)
    private fun assertStamped(target: String?) {
        assertStamped(getConfiguredTarget(target))
    }

    private fun assertStamped(target: ConfiguredTarget) {
        val out: Artifact? = BuildViewTestCase.Companion.getFilesToBuild(target).toList().get(0)
        val inputs: MutableList<String?>? = ActionsTestUtil.baseArtifactNames(getGeneratingAction(out).getInputs())
        Truth.assertThat(inputs).containsAtLeast("build-info.txt", "build-changelist.txt")
    }

    @Throws(Exception::class)
    private fun assertNotStamped(target: String?) {
        assertNotStamped(getConfiguredTarget(target))
    }

    private fun assertNotStamped(target: ConfiguredTarget) {
        val out: Artifact? = BuildViewTestCase.Companion.getFilesToBuild(target).toList().get(0)
        val inputs: MutableList<String?>? = ActionsTestUtil.baseArtifactNames(getGeneratingAction(out).getInputs())
        Truth.assertThat(inputs).doesNotContain("build-info.txt")
        Truth.assertThat(inputs).doesNotContain("build-changelist.txt")
    }

    @Test
    @Throws(Exception::class)
    fun testStampingWithNoStamp() {
        useConfiguration("--nostamp")
        createStampingTargets()
        assertStamped("//u:foo_stamp")
        assertStamped(getExecConfiguredTarget("//u:foo_stamp"))
        assertNotStamped("//u:foo_nostamp")
        assertNotStamped(getExecConfiguredTarget("//u:foo_nostamp"))
        assertNotStamped("//u:foo_autostamp")
        assertNotStamped(getExecConfiguredTarget("//u:foo_autostamp"))
        assertNotStamped("//u:foo_default")
    }

    @Test
    @Throws(Exception::class)
    fun testStampingWithStamp() {
        useConfiguration("--stamp")
        createStampingTargets()
        assertStamped("//u:foo_stamp")
        assertStamped(getExecConfiguredTarget("//u:foo_stamp"))
        assertNotStamped("//u:foo_nostamp")
        assertNotStamped(getExecConfiguredTarget("//u:foo_nostamp"))
        assertStamped("//u:foo_autostamp")
        assertNotStamped(getExecConfiguredTarget("//u:foo_autostamp"))
        assertNotStamped("//u:foo_default")
    }

    @Test
    @Throws(Exception::class)
    fun testRequiresDarwin() {
        scratch.file(
            "foo/BUILD",
            "genrule(name='darwin', srcs=[], outs=['macout'], cmd='', tags=['requires-darwin'])"
        )

        val action: SpawnAction = getSpawnAction("//foo:darwin")
        Subject.contains("requires-darwin")
        // requires-darwin causes /bin/bash to be hard-coded, see CommandHelper.shellPath().
        assertThat(action.getCommandFilename())
            .isEqualTo("/bin/bash")
    }

    @Test
    @Throws(Exception::class)
    fun testJarError() {
        checkError(
            "foo",
            "grj",
            "in cmd attribute of genrule rule //foo:grj: $(JAR) not defined",
            ("genrule(name='grj',"
                    + "      srcs = [],"
                    + "      outs=['grj'],"
                    + "      cmd='$(JAR) foo bar')")
        )
    }

    /** Regression test for b/15589451.  */
    @Test
    @Throws(Exception::class)
    fun testDuplicateLocalFlags() {
        scratch.file(
            "foo/BUILD",
            ("genrule(name='g',"
                    + "      srcs = [],"
                    + "      outs = ['grj'],"
                    + "      cmd ='echo g',"
                    + "      local = 1,"
                    + "      tags = ['local'])")
        )
        getConfiguredTarget("//foo:g")
        assertNoEvents()
    }

    @Test
    @Throws(Exception::class)
    fun testToolsHaveExecOutputDir() {
        scratch.file(
            "config/BUILD",
            """
        genrule(
            name = "src",
            outs = ["src.out"],
            cmd = ":",
        )

        genrule(
            name = "tool",
            outs = ["tool.out"],
            cmd = ":",
        )

        genrule(
            name = "config",
            srcs = [":src"],
            outs = ["out"],
            cmd = "${'$'}(location :tool)",
            tools = [":tool"],
        )
        
        """.trimIndent()
        )

        val parentTarget: ConfiguredTarget? = getConfiguredTarget("//config")

        // Cannot use getDirectPrerequisites, as this re-configures that target incorrectly.
        val out: Artifact? = BuildViewTestCase.Companion.getFilesToBuild(parentTarget).toList().get(0)
        assertThat(getGeneratingAction(out).getTools().toList()).hasSize(1)
        val tool: Artifact = getGeneratingAction(out).getTools().getSingleton()
        // This is the output dir fragment for the execution transition.
        Subject.contains("-exec")
    }

    companion object {
        private val SETUP_COMMAND_PATTERN: Pattern = Pattern.compile(".*/genrule-setup.sh;\\s+(?<command>.*)")

        private fun assertCommandEquals(expected: String?, command: String?) {
            // Ensure the command after the genrule setup is correct.
            var command = command
            val m: Matcher = SETUP_COMMAND_PATTERN.matcher(command)
            if (m.matches()) {
                command = m.group("command")
            }

            Truth.assertThat(command).isEqualTo(expected)
        }
    }
}
