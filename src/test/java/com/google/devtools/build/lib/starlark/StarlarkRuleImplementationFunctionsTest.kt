// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey

/** Tests for Starlark functions relating to rule implementation.  */
@RunWith(TestParameterInjector::class)
class StarlarkRuleImplementationFunctionsTest : BuildViewTestCase() {
    private val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()

    @Throws(java.lang.Exception::class)
    private fun createRuleContext(label: String?): StarlarkRuleContext {
        return StarlarkRuleContext(getRuleContextForStarlark(getConfiguredTarget(label)), null)
    }

    @org.junit.Rule
    var thrown: org.junit.rules.ExpectedException = org.junit.rules.ExpectedException.none()

    // def mock(mandatory, optional=None, *, mandatory_key, optional_key='x')
    @StarlarkMethod(
        name = "mock",
        documented = false,
        parameters = [net.starlark.java.annot.Param(
            name = "mandatory",
            doc = "",
            named = true
        ), net.starlark.java.annot.Param(
            name = "optional",
            doc = "",
            defaultValue = "None",
            named = true
        ), net.starlark.java.annot.Param(
            name = "mandatory_key",
            doc = "",
            positional = false,
            named = true
        ), net.starlark.java.annot.Param(
            name = "optional_key",
            doc = "",
            defaultValue = "'x'",
            positional = false,
            named = true
        )],
        useStarlarkThread = true
    )
    fun mock(
        mandatory: Any?,
        optional: Any?,
        mandatoryKey: Any?,
        optionalKey: Any?,
        thread: StarlarkThread?
    ): Any {
        val m: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        m.put("mandatory", mandatory)
        m.put("optional", optional)
        m.put("mandatory_key", mandatoryKey)
        m.put("optional_key", optionalKey)
        return m
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createBuildFilesAndHostPlatform() {
        scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()")

        scratch.file("myinfo/BUILD")

        scratch.file(
            "foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        genrule(name = 'foo',
          cmd = 'dummy_cmd',
          srcs = ['a.txt', 'b.img'],
          tools = ['t.exe'],
          outs = ['c.txt'])
        genrule(name = 'bar',
          cmd = 'dummy_cmd',
          srcs = [':jl', ':gl'],
          outs = ['d.txt'])
        genrule(name = 'baz',
          cmd = 'dummy_cmd',
          outs = ['e.txt'])
        java_library(name = 'jl',
          srcs = ['a.java'])
        genrule(name = 'gl',
          cmd = 'touch ${'$'}(OUTS)',
          srcs = ['a.go'],
          outs = [ 'gl.a', 'gl.gcgox', ],
          output_to_bindir = 1,
        )
        # The target below is used by testResolveCommand and testResolveTools
        foo_binary(name = 'mytool',
          srcs = ['mytool.sh'],
          data = ['file1.dat', 'file2.dat'],
        )
        # The target below is used by testResolveCommand and testResolveTools
        genrule(name = 'resolve_me',
          cmd = 'aa',
          tools = [':mytool', 't.exe'],
          srcs = ['file3.dat', 'file4.dat'],
          outs = ['r1.txt', 'r2.txt'],
        )
        genrule(name = 'mixed_cfgs',
          cmd = 'some_cmd',
          srcs = ['a.txt', ':baz'],
          tools = ['r1.txt'],
          outs = ['out.txt'],
        )
        
        """.trimIndent()
        )

        // Tests below assume that the actual host OS is reflected in the host platform, but Bazel's
        // test setup forces the host platform to be "linux-x86_64".
        scratch.file(
            "platforms/BUILD",
            """
        platform(
            name = "host_platform",
             constraint_values = [
                 "%sos:%s",
                 "%scpu:x86_64",
             ],
        )
        
        """
                .trimIndent()
                .formatted(
                    TestConstants.CONSTRAINTS_PACKAGE_ROOT,
                    when (com.google.devtools.build.lib.util.OS.getCurrent()) {
                        com.google.devtools.build.lib.util.OS.LINUX -> "linux"
                        com.google.devtools.build.lib.util.OS.DARWIN -> "macos"
                        com.google.devtools.build.lib.util.OS.FREEBSD -> "freebsd"
                        com.google.devtools.build.lib.util.OS.OPENBSD -> "openbsd"
                        com.google.devtools.build.lib.util.OS.WINDOWS -> "windows"
                        com.google.devtools.build.lib.util.OS.UNKNOWN -> "none"
                    },
                    TestConstants.CONSTRAINTS_PACKAGE_ROOT
                )
        )
        useConfiguration("--host_platform=//platforms:host_platform")
    }

    @Throws(java.lang.Exception::class)
    private fun setRuleContext(ctx: StarlarkRuleContext?) {
        ev.update("ruleContext", ctx)
    }

    @Throws(java.lang.Exception::class)
    private fun getMyInfoFromTarget(configuredTarget: ConfiguredTarget): StructImpl {
        val key: Provider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo"
            )
        return configuredTarget.get(key) as StructImpl
    }

    // Defines all @StarlarkCallable-annotated methods (mock, throw, ...) in the environment.
    @Throws(java.lang.Exception::class)
    private fun defineTestMethods() {
        val env: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        Starlark.addMethods(env, this)
        for (entry in env.buildOrThrow().entries) {
            ev.update(entry.key, entry.value)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun checkStarlarkFunctionError(errorSubstring: String?, line: String?) {
        defineTestMethods()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.exec(line) })
        Truth.assertThat(e).hasMessageThat().contains(errorSubstring)
    }

    // TODO(adonovan): move these tests of Starlark interpreter core into net/starlark/java.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkFunctionPosArgs() {
        defineTestMethods()
        ev.exec("a = mock('a', 'b', mandatory_key='c')")
        val params = ev.lookup("a") as MutableMap<*, *>
        Truth.assertThat(params.get("mandatory")).isEqualTo("a")
        Truth.assertThat(params.get("optional")).isEqualTo("b")
        Truth.assertThat(params.get("mandatory_key")).isEqualTo("c")
        Truth.assertThat(params.get("optional_key")).isEqualTo("x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkFunctionKwArgs() {
        defineTestMethods()
        ev.exec("a = mock(optional='b', mandatory='a', mandatory_key='c')")
        val params = ev.lookup("a") as MutableMap<*, *>
        Truth.assertThat(params.get("mandatory")).isEqualTo("a")
        Truth.assertThat(params.get("optional")).isEqualTo("b")
        Truth.assertThat(params.get("mandatory_key")).isEqualTo("c")
        Truth.assertThat(params.get("optional_key")).isEqualTo("x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkFunctionTooFewArguments() {
        checkStarlarkFunctionError(
            "missing 1 required positional argument: mandatory", "mock(mandatory_key='y')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkFunctionTooManyArguments() {
        checkStarlarkFunctionError(
            "mock() accepts no more than 2 positional arguments but got 3",
            "mock('a', 'b', 'c', mandatory_key='y')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkFunctionAmbiguousArguments() {
        checkStarlarkFunctionError(
            "mock() got multiple values for argument 'mandatory'",
            "mock('by position', mandatory='by_key', mandatory_key='c')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionCreatesSpawnAction() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        createTestSpawnAction(ruleContext)
        val action: ActionAnalysisMetadata? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            )
        assertThat(action).isInstanceOf(SpawnAction::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArtifactPath() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result = ev.eval("ruleContext.files.tools[0].path") as String
        Truth.assertThat(result).isEqualTo("foo/t.exe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArtifactShortPath() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result = ev.eval("ruleContext.files.tools[0].short_path") as String
        Truth.assertThat(result).isEqualTo("foo/t.exe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionArgumentsWithCommand() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        createTestSpawnAction(ruleContext)
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertArtifactFilenames(action.getInputs().toList(), "a.txt", "b.img")
        assertArtifactFilenames(action.getOutputs(), "a.txt", "b.img")
        MoreAsserts.assertContainsSublist<T?>(
            action.getArguments(), "-c", "dummy_command", "", "--a", "--b"
        )
        assertThat(action.getMnemonic()).isEqualTo("DummyMnemonic")
        assertThat(action.getProgressMessage()).isEqualTo("dummy_message")
        assertThat(action.getIncompleteEnvironmentForTesting())
            .isEqualTo(targetConfig.getLocalShellEnvironment())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionArgumentsWithExecutable() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = ['--a','--b'],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertArtifactFilenames(action.getInputs().toList(), "a.txt", "b.img", "t.exe")
        assertArtifactFilenames(action.getOutputs(), "a.txt", "b.img")
        MoreAsserts.assertContainsSublist<T?>(action.getArguments(), "foo/t.exe", "--a", "--b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createSpawnAction_progressMessageWithSubstitutions() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs[1:],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None,",
            "  mnemonic = 'DummyMnemonic',",
            "  progress_message = 'message %{label} %{input} %{output}')"
        )

        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?

        assertThat(action.getProgressMessage()).isEqualTo("message //foo:foo foo/a.txt foo/b.img")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateActionWithDepsetInput() {
        // Same test as above, with depset as inputs.
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = ['--a','--b'],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertArtifactFilenames(action.getInputs().toList(), "a.txt", "b.img", "t.exe")
        assertArtifactFilenames(action.getOutputs(), "a.txt", "b.img")
        MoreAsserts.assertContainsSublist<T?>(action.getArguments(), "foo/t.exe", "--a", "--b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionArgumentsBadExecutable() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "got value of type 'int', want 'File, string, or FilesToRunProvider'",
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = ['--a','--b'],",
            "  executable = 123)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionShellCommandList() {
        setBuildLanguageOptions("--incompatible_run_shell_command_string=false")
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.run_shell(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  mnemonic = 'DummyMnemonic',",
            "  command = ['dummy_command', '--arg1', '--arg2'],",
            "  progress_message = 'dummy_message')"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly("dummy_command", "--arg1", "--arg2")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionEnvAndExecInfo() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.run_shell(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  env = {'a' : 'b'},",
            "  execution_requirements = {'timeout' : '10', 'block-network' : 'foo'},",
            "  mnemonic = 'DummyMnemonic',",
            "  command = 'dummy_command',",
            "  progress_message = 'dummy_message')"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getIncompleteEnvironmentForTesting()).containsExactly("a", "b")
        // We expect "timeout" to be filtered by TargetUtils.
        assertThat(action.getExecutionInfo()).containsExactly("block-network", "foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionEnvAndExecInfo_withWorkerKeyMnemonic() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.run_shell(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  env = {'a' : 'b'},",
            "  execution_requirements = {",
            "    'supports-workers': '1',",
            "    'worker-key-mnemonic': 'MyMnemonic',",
            "  },",
            "  mnemonic = 'DummyMnemonic',",
            "  command = 'dummy_command',",
            "  progress_message = 'dummy_message')"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getExecutionInfo())
            .containsExactly("supports-workers", "1", "worker-key-mnemonic", "MyMnemonic")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionUnknownParam() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "run() got unexpected keyword argument 'bad_param'",
            "f = ruleContext.actions.declare_file('foo.sh')",
            "ruleContext.actions.run(outputs=[], bad_param = 'some text', executable = f)"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun createTestSpawnAction(ruleContext: StarlarkRuleContext?): Any {
        setRuleContext(ruleContext)
        return ev.eval(
            "ruleContext.actions.run_shell(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = ['--a','--b'],",
            "  mnemonic = 'DummyMnemonic',",
            "  command = 'dummy_command',",
            "  progress_message = 'dummy_message',",
            "  use_default_shell_env = True)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionBadGenericArg() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "at index 0 of outputs, got element of type string, want File",
            "l = ['a', 'b']",
            "ruleContext.actions.run_shell(",
            "  outputs = l,",
            "  command = 'dummy_command')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunShellArgumentsWithCommandSequence() {
        setBuildLanguageOptions("--incompatible_run_shell_command_string=false")
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "'arguments' must be empty if 'command' is a sequence of strings",
            "ruleContext.actions.run_shell(outputs = ruleContext.files.srcs,",
            "  command = [\"echo\", \"'hello world'\", \"&&\", \"touch\"],",
            "  arguments = [ruleContext.files.srcs[0].path])"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun setupToolInInputsTest(vararg ruleImpl: String?) {
        val lines: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        lines.add("def _main_rule_impl(ctx):")
        for (line in ruleImpl) {
            lines.add("  " + line)
        }
        lines.add(
            "my_rule = rule(",
            "  _main_rule_impl,",
            "  attrs = { ",
            "    'exe' : attr.label(executable = True, allow_files = True, cfg='exec'),",
            "  },",
            ")"
        )
        scratch.file("bar/bar.bzl", lines.build().< T > toArray < T ? > (arrayOf<String?>()))
        scratch.file(
            "bar/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        load('//bar:bar.bzl', 'my_rule')
        foo_binary(
          name = 'mytool',
          srcs = ['mytool.sh'],
          data = ['file1.dat', 'file2.dat'],
        )
        my_rule(
          name = 'my_rule',
          exe = ':mytool',
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionWithToolAttribute() {
        setupToolInInputsTest(
            "output = ctx.actions.declare_file('bar.out')",
            "ctx.actions.run_shell(",
            "  inputs = [],",
            "  tools = ctx.attr.exe.files,",
            "  outputs = [output],",
            "  command = 'boo bar baz',",
            "  toolchain = None",
            ")"
        )
        val target: RuleConfiguredTarget = getConfiguredTarget("//bar:my_rule") as RuleConfiguredTarget
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(target.getActions()) as SpawnAction?
        assertThat(action.getTools().toList()).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionWithToolAttributeIgnoresToolsInInputs() {
        setupToolInInputsTest(
            "output = ctx.actions.declare_file('bar.out')",
            "ctx.actions.run_shell(",
            "  inputs = ctx.attr.exe.files,",
            "  tools = ctx.attr.exe.files,",
            "  outputs = [output],",
            "  command = 'boo bar baz',",
            "  toolchain = None",
            ")"
        )
        val target: RuleConfiguredTarget = getConfiguredTarget("//bar:my_rule") as RuleConfiguredTarget
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(target.getActions()) as SpawnAction?
        assertThat(action.getTools().toList()).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateFileAction() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.write(",
            "  output = ruleContext.files.srcs[0],",
            "  content = 'hello world',",
            "  is_executable = False)"
        )
        val action: FileWriteAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as FileWriteAction?
        assertThat(com.google.common.collect.Iterables.getOnlyElement<T?>(action.getOutputs()).getExecPathString())
            .isEqualTo("foo/a.txt")
        assertThat(action.getFileContents()).isEqualTo("hello world")
        assertThat(action.makeExecutable()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyAction() {
        setRuleContext(createRuleContext("//foo:foo"))
        checkEmptyAction("mnemonic = 'test'")
        checkEmptyAction("mnemonic = 'test', inputs = ruleContext.files.srcs")
        checkEmptyAction("mnemonic = 'test', inputs = depset(ruleContext.files.srcs)")

        ev.checkEvalErrorContains(
            "do_nothing() missing 1 required named argument: mnemonic",
            "ruleContext.actions.do_nothing(inputs = ruleContext.files.srcs)"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun checkEmptyAction(namedArgs: String?) {
        Truth.assertThat(ev.eval(String.format("ruleContext.actions.do_nothing(%s)", namedArgs)))
            .isEqualTo(Starlark.NONE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyActionWithExtraAction() {
        scratch.file(
            "test/empty.bzl",
            """
        def _impl(ctx):
          ctx.actions.do_nothing(
              inputs = ctx.files.srcs,
              mnemonic = 'EA',
          )
        empty_action_rule = rule(
            implementation = _impl,
            attrs = {
               "srcs": attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:empty.bzl', 'empty_action_rule')
        empty_action_rule(name = 'my_empty_action',
                        srcs = ['foo.in', 'other_foo.in'])
        action_listener(name = 'listener',
                        mnemonics = ['EA'],
                        extra_actions = [':extra'])
        extra_action(name = 'extra',
                     cmd='')
        
        """.trimIndent()
        )

        getPseudoActionViaExtraAction("//test:my_empty_action", "//test:listener")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandLocation() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:bar")
        setRuleContext(ruleContext)

        // If there is only a single target, both "location" and "locations" should work
        runExpansion("location :jl", "[blaze]*-out/.*/bin/foo/libjl.jar")
        runExpansion("locations :jl", "[blaze]*-out/.*/bin/foo/libjl.jar")

        runExpansion("location //foo:jl", "[blaze]*-out/.*/bin/foo/libjl.jar")

        // Multiple targets and "location" should result in an error
        checkReportedErrorStartsWith(
            ("in genrule rule //foo:bar: label '//foo:gl' "
                    + "in $(location) expression expands to more than one file, please use $(locations "
                    + "//foo:gl) instead."),
            "ruleContext.expand_location('$(location :gl)')"
        )

        // We have to use "locations" for multiple targets
        runExpansion("locations :gl", "[blaze]*-out/.*/bin/foo/gl.a [blaze]*-out/.*/bin/foo/gl.gcgox")

        // LocationExpander just returns the input string if there is no label
        runExpansion("location", "\\$\\(location\\)")

        checkReportedErrorStartsWith(
            "in genrule rule //foo:bar: label '//foo:abc' in $(locations) expression "
                    + "is not a declared prerequisite of this rule",
            "ruleContext.expand_location('$(locations :abc)')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandLocationWithShortPathsIsPrivateAPI() {
        scratch.file(
            "abc/rule.bzl",
            """
        def _impl(ctx):
         ctx.expand_location('', short_paths = True)
         return []

        r = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "abc/BUILD",
            """
        load(':rule.bzl', 'r')

        r(name = 'foo')
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//abc:foo") })

        Truth.assertThat(error).hasMessageThat().contains("file '//abc:rule.bzl' cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandLocationWithShortPaths() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:bar")
        setRuleContext(ruleContext)

        val loc: Any = ev.eval("ruleContext.expand_location('$(location :jl)', short_paths = True)")

        Truth.assertThat(loc).isEqualTo("foo/libjl.jar")
    }

    /** Regression test to check that expand_location allows ${var} and $$.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandLocationWithDollarSignsAndCurlys() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:bar")
        setRuleContext(ruleContext)
        Truth.assertThat(ev.eval("ruleContext.expand_location('\${abc} $(echo) $$ $')") as String?)
            .isEqualTo("\${abc} $(echo) $$ $")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedLocationWithSingleFileDifferentFromExecutable(
        @TestParameter locationsPrefersExecutable: Boolean
    ) {
        setBuildLanguageOptions(
            "--incompatible_locations_prefers_executable=" + locationsPrefersExecutable
        )

        scratch.file(
            "test/defs.bzl",
            "def _my_binary_impl(ctx):",
            "  executable = ctx.actions.declare_file(ctx.attr.name + '_executable')",
            "  ctx.actions.write(executable, '', is_executable = True)",
            "  file = ctx.actions.declare_file(ctx.attr.name + '_file')",
            "  ctx.actions.write(file, '')",
            "  return [DefaultInfo(executable = executable, files = depset([file]))]",
            "my_binary = rule(",
            "    implementation = _my_binary_impl,",
            "    executable = True,",
            ")",
            "def _expand_location_rule_impl(ctx):",
            "  expansions = []",
            "  for data in ctx.attr.data:",
            "    expansions.append(",
            "        ctx.expand_location('$(location ' + str(data.label) + ')', ctx.attr.data),",
            "    )",
            "    expansions.append(",
            "        ctx.expand_location('$(locations ' + str(data.label) + ')', ctx.attr.data)",
            "    )",
            "  file = ctx.actions.declare_file(ctx.attr.name)",
            "  ctx.actions.write(file, '\\n'.join(expansions))",
            "  return [DefaultInfo(files = depset([file]))]",
            "expand_location_rule = rule(",
            "    implementation = _expand_location_rule_impl,",
            "    attrs = {",
            "       'data': attr.label_list(),",
            "    },",
            ")"
        )

        scratch.file(
            "test/BUILD",
            "load('//test:defs.bzl', 'expand_location_rule', 'my_binary')",
            "my_binary(name = 'main')",
            "expand_location_rule(",
            "  name = 'expand',",
            "  data = [':main'],",
            ")"
        )

        val expandTarget: TransitiveInfoCollection = getConfiguredTarget("//test:expand")
        val artifact: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                expandTarget.getProvider(FileProvider::class.java).getFilesToBuild().toList()
            )
        val action: FileWriteAction = getGeneratingAction(artifact) as FileWriteAction
        assertThat(action.getFileContents())
            .matches(
                """
            ^\S*/bin/test/main_file
            \S*/bin/test/main_file${'$'}
            """.trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedLocationsWithMultipleFilesAndExecutable(
        @TestParameter locationsPrefersExecutable: Boolean
    ) {
        setBuildLanguageOptions(
            "--incompatible_locations_prefers_executable=" + locationsPrefersExecutable
        )

        scratch.file(
            "test/defs.bzl",
            "def _my_binary_impl(ctx):",
            "  executable = ctx.actions.declare_file(ctx.attr.name + '_executable')",
            "  ctx.actions.write(executable, '', is_executable = True)",
            "  file1 = ctx.actions.declare_file(ctx.attr.name + '_file1')",
            "  file2 = ctx.actions.declare_file(ctx.attr.name + '_file2')",
            "  ctx.actions.write(file1, '')",
            "  ctx.actions.write(file2, '')",
            "  return [DefaultInfo(executable = executable, files = depset([file1, file2]))]",
            "my_binary = rule(",
            "    implementation = _my_binary_impl,",
            "    executable = True,",
            ")",
            "def _expand_location_rule_impl(ctx):",
            "  expansions = []",
            "  for data in ctx.attr.data:",
            "    expansions.append(",
            "        ctx.expand_location('$(location ' + str(data.label) + ')', ctx.attr.data),",
            "    )",
            "    expansions.append(",
            "        ctx.expand_location('$(locations ' + str(data.label) + ')', ctx.attr.data)",
            "    )",
            "  file = ctx.actions.declare_file(ctx.attr.name)",
            "  ctx.actions.write(file, '\\n'.join(expansions))",
            "  return [DefaultInfo(files = depset([file]))]",
            "expand_location_rule = rule(",
            "    implementation = _expand_location_rule_impl,",
            "    attrs = {",
            "       'data': attr.label_list(),",
            "    },",
            ")"
        )

        scratch.file(
            "test/BUILD",
            "load('//test:defs.bzl', 'expand_location_rule', 'my_binary')",
            "my_binary(name = 'main')",
            "expand_location_rule(",
            "  name = 'expand',",
            "  data = [':main'],",
            ")"
        )

        reporter.removeHandler(failFastHandler)
        val expandTarget: TransitiveInfoCollection = getConfiguredTarget("//test:expand")
        if (locationsPrefersExecutable) {
            val artifact: Artifact? =
                com.google.common.collect.Iterables.getOnlyElement<T?>(
                    expandTarget.getProvider(FileProvider::class.java).getFilesToBuild().toList()
                )
            val action: FileWriteAction = getGeneratingAction(artifact) as FileWriteAction
            assertThat(action.getFileContents())
                .matches(
                    """
              ^\S*/bin/test/main_executable
              \S*/bin/test/main_executable${'$'}
              """.trimIndent()
                )
        } else {
            assertContainsEvent(
                "label '//test:main' in $(location) expression expands to more than one file"
            )
            assertContainsEvent("/bin/test/main_file1,")
            assertContainsEvent("/bin/test/main_file2]")
        }
    }

    /**
     * Invokes ctx.expand_location() with the given parameters and checks whether this led to the
     * expected result
     * 
     * @param command Either "location" or "locations". This only matters when the label has multiple
     * targets
     * @param expectedPattern Regex pattern that matches the expected result
     */
    @Throws(java.lang.Exception::class)
    private fun runExpansion(command: String?, expectedPattern: String?) {
        assertMatches(
            "Expanded string",
            expectedPattern,
            ev.eval(String.format("ruleContext.expand_location('$(%s)')", command)) as String?
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveCommandMakeVariables() {
        setRuleContext(createRuleContext("//foo:resolve_me"))
        ev.exec(
            "inputs, argv, manifests = ruleContext.resolve_command(",
            "  command='I got the $(HELLO) on a $(DAVE)', ",
            "  make_variables={'HELLO': 'World', 'DAVE': type('')})"
        )
        val argv = ev.lookup("argv") as StarlarkList<*>? as MutableList<*>? as MutableList<String?>?
        Truth.assertThat(argv).hasSize(3)
        assertMatches("argv[0]", "^.*/bash" + OsUtils.executableExtension() + "$", argv!!.get(0))
        Truth.assertThat(argv.get(1)).isEqualTo("-c")
        Truth.assertThat(argv.get(2)).isEqualTo("I got the World on a string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveCommandInputs() {
        setRuleContext(createRuleContext("//foo:resolve_me"))
        ev.exec(
            "inputs, argv, input_manifests = ruleContext.resolve_command(",
            "   tools=ruleContext.attr.tools)"
        )
        val inputs: MutableList<Artifact> =
            ev.lookup("inputs") as StarlarkList<*>? as MutableList<*>? as MutableList<Artifact>
        assertArtifactFilenames(inputs, "mytool.sh", "mytool", "mytool.runfiles", "t.exe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveCommandExpandLocations() {
        setRuleContext(createRuleContext("//foo:resolve_me"))
        ev.exec(
            "def foo():",  // no for loops at top-level
            "  label_dict = {}",
            "  all = []",
            "  for dep in ruleContext.attr.srcs + ruleContext.attr.tools:",
            "    all.extend(dep[DefaultInfo].files.to_list())",
            "    label_dict[dep.label] = dep[DefaultInfo].files.to_list()",
            "  return ruleContext.resolve_command(",
            "    command='A$(locations //foo:mytool) B$(location //foo:file3.dat)',",
            "    attribute='cmd', expand_locations=True, label_dict=label_dict)",
            "inputs, argv, manifests = foo()"
        )
        val argv = ev.lookup("argv") as StarlarkList<*>? as MutableList<*>? as MutableList<String?>?
        Truth.assertThat(argv).hasSize(3)
        assertMatches("argv[0]", "^.*/bash" + OsUtils.executableExtension() + "$", argv!!.get(0))
        Truth.assertThat(argv.get(1)).isEqualTo("-c")
        assertMatches("argv[2]", "A.*/mytool .*/mytool.sh B.*file3.dat", argv.get(2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveCommandExecutionRequirements() {
        // Tests that requires-darwin execution requirements result in the usage of /bin/bash.
        setRuleContext(createRuleContext("//foo:resolve_me"))
        ev.exec(
            "inputs, argv, manifests = ruleContext.resolve_command(",
            "  execution_requirements={'requires-darwin': ''})"
        )
        val argv = ev.lookup("argv") as StarlarkList<*>? as MutableList<*>? as MutableList<String?>
        assertMatches("argv[0]", "^/bin/bash$", argv.get(0))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolveCommandScript() {
        setRuleContext(createRuleContext("//foo:resolve_me"))
        ev.exec(
            "s = 'a' * " + CommandHelper.maxCommandLength(com.google.devtools.build.lib.util.OS.getCurrent()) + 1,
            "inputs, argv, _ = ruleContext.resolve_command(command = s)"
        )

        val inputs: MutableList<Artifact?>? = ev.lookup("inputs") as MutableList<Artifact?>?
        val argv = ev.lookup("argv") as MutableList<String?>?

        Truth.assertThat(inputs).hasSize(1)
        Truth.assertThat(argv).hasSize(2)
        Truth.assertThat(argv!!.get(0)).endsWith("/bash" + OsUtils.executableExtension())
        Truth.assertThat(argv.get(1)).isEqualTo(inputs!!.get(0).getExecPathString())
        assertThat(inputs.get(0).getExecPathString()).endsWith(".script.sh")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleResolveCommandScripts_noConflict() {
        setRuleContext(createRuleContext("//foo:resolve_me"))
        ev.exec(
            "s1 = '1' * " + CommandHelper.maxCommandLength(com.google.devtools.build.lib.util.OS.getCurrent()) + 1,
            "s2 = '2' * " + CommandHelper.maxCommandLength(com.google.devtools.build.lib.util.OS.getCurrent()) + 1,
            "inputs1, argv1, _ = ruleContext.resolve_command(command = s1)",
            "inputs2, argv2, __ = ruleContext.resolve_command(command = s2)"
        )

        val inputs1: MutableList<Artifact?>? = ev.lookup("inputs1") as MutableList<Artifact?>?
        val argv1 = ev.lookup("argv1") as MutableList<String?>?
        val inputs2: MutableList<Artifact?>? = ev.lookup("inputs2") as MutableList<Artifact?>?
        val argv2 = ev.lookup("argv2") as MutableList<String?>?

        Truth.assertThat(inputs1).hasSize(1)
        Truth.assertThat(inputs2).hasSize(1)
        assertThat(inputs1!!.get(0).getExecPathString()).isNotEqualTo(inputs2!!.get(0).getExecPathString())
        Truth.assertThat(argv1).hasSize(2)
        Truth.assertThat(argv2).hasSize(2)
        Truth.assertThat(argv1!!.get(0)).endsWith("/bash" + OsUtils.executableExtension())
        Truth.assertThat(argv2!!.get(0)).endsWith("/bash" + OsUtils.executableExtension())
        Truth.assertThat(argv1.get(1)).isEqualTo(inputs1.get(0).getExecPathString())
        Truth.assertThat(argv2.get(1)).isEqualTo(inputs2.get(0).getExecPathString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolveCommandScript_namingNotDependantOnCommand() {
        setRuleContext(createRuleContext("//foo:resolve_me"))
        ev.exec(
            "s = '1' * " + CommandHelper.maxCommandLength(com.google.devtools.build.lib.util.OS.getCurrent()) + 1,
            "result1 = ruleContext.resolve_command(command = s)"
        )
        val result1: Any? = ev.lookup("result1")

        // Reset the rule context to simulate a build in a different configuration that results in a
        // different command.
        setRuleContext(createRuleContext("//foo:resolve_me"))
        ev.exec(
            "s = '2' * " + CommandHelper.maxCommandLength(com.google.devtools.build.lib.util.OS.getCurrent()) + 1,
            "result2 = ruleContext.resolve_command(command = s)"
        )
        val result2: Any? = ev.lookup("result2")

        Truth.assertThat(result1).isEqualTo(result2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolveTools() {
        setBuildLanguageOptions("--incompatible_disallow_ctx_resolve_tools=false")
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:resolve_me")
        setRuleContext(ruleContext)
        ev.exec(
            "inputs, input_manifests = ruleContext.resolve_tools(tools=ruleContext.attr.tools)",
            "ruleContext.actions.run(",
            "    outputs = [ruleContext.actions.declare_file('x.out')],",
            "    inputs = inputs,",
            "    executable = 'dummy',",
            ")"
        )
        assertArtifactFilenames(
            (ev.lookup("inputs") as Depset).getSet(Artifact::class.java).toList(),
            "mytool.sh",
            "mytool",
            "mytool.runfiles",
            "t.exe"
        )

        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(ActionsTestUtil.baseArtifactNames(action.getInputs()))
            .containsAtLeast("mytool.sh", "mytool", "mytool.runfiles", "t.exe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadParamTypeErrorMessage() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "got value of type 'int', want 'string or Args'",
            "ruleContext.actions.write(",
            "  output = ruleContext.files.srcs[0],",
            "  content = 1,",
            "  is_executable = False)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateTemplateAction() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.expand_template(",
            "  template = ruleContext.files.srcs[0],",
            "  output = ruleContext.files.srcs[1],",
            "  substitutions = {'a': 'b'},",
            "  is_executable = False)"
        )

        val action: TemplateExpansionAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as TemplateExpansionAction?
        assertThat(action.getInputs().getSingleton().getExecPathString()).isEqualTo("foo/a.txt")
        assertThat(com.google.common.collect.Iterables.getOnlyElement<T?>(action.getOutputs()).getExecPathString())
            .isEqualTo("foo/b.img")
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<T?>(action.getSubstitutions()).getKey()
        ).isEqualTo("a")
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<T?>(action.getSubstitutions()).getValue()
        ).isEqualTo("b")
        assertThat(action.makeExecutable()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateTemplateActionUnicode() {
        // The following array contains bytes that represent a string of length two when treated as
        // UTF-8 and a string of length four when treated as ISO-8859-1 (a.k.a. Latin 1).
        val internalString = String(
            byteArrayOf(0xC2.toByte(), 0xA2.toByte(), 0xC2.toByte(), 0xA2.toByte()),
            java.nio.charset.StandardCharsets.ISO_8859_1
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        // In production, Bazel parses Starlark as raw bytes encoded as Latin-1.
        ev.exec(
            "ruleContext.actions.expand_template(",
            "  template = ruleContext.files.srcs[0],",
            "  output = ruleContext.files.srcs[1],",
            "  substitutions = {'a" + internalString + "': '" + internalString + "'},",
            "  is_executable = False)"
        )
        val action: TemplateExpansionAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as TemplateExpansionAction?
        val substitutions: MutableList<Substitution?>? = action.getSubstitutions()
        Truth.assertThat(substitutions).hasSize(1)
        assertThat(substitutions!!.get(0).getKey()).isEqualTo("a" + internalString)
        assertThat(substitutions.get(0).getValue()).isEqualTo(internalString)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesAddFromDependencies() {
        setRuleContext(createRuleContext("//foo:bar"))
        val result: Any = ev.eval("ruleContext.runfiles(collect_default = True)")
        com.google.common.truth.Subject.contains("libjl.jar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesBadListGenericType() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "at index 0 of files, got element of type string, want File",
            "ruleContext.runfiles(files = ['some string'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesBadSetGenericType() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "got a depset of 'int', expected a depset of 'File'",
            "ruleContext.runfiles(transitive_files=depset([1, 2, 3]))"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesBadMapGenericType() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "got dict<int, File> for 'symlinks', want dict<string, File>",
            "ruleContext.runfiles(symlinks = {123: ruleContext.files.srcs[0]})"
        )
        ev.checkEvalErrorContains(
            "got dict<string, int> for 'symlinks', want dict<string, File>",
            "ruleContext.runfiles(symlinks = {'some string': 123})"
        )
        ev.checkEvalErrorContains(
            "got dict<int, File> for 'root_symlinks', want dict<string, File>",
            "ruleContext.runfiles(root_symlinks = {123: ruleContext.files.srcs[0]})"
        )
        ev.checkEvalErrorContains(
            "got dict<string, int> for 'root_symlinks', want dict<string, File>",
            "ruleContext.runfiles(root_symlinks = {'some string': 123})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesArtifactsFromArtifact() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.runfiles(files = ruleContext.files.tools)")
        com.google.common.truth.Subject.contains("t.exe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesArtifactsFromIterableArtifacts() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.runfiles(files = ruleContext.files.srcs)")
        Truth.assertThat(com.google.common.collect.ImmutableList.of<String?>("a.txt", "b.img"))
            .isEqualTo(ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesArtifactsFromNestedSetArtifacts() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any =
            ev.eval("ruleContext.runfiles(transitive_files = depset(ruleContext.files.srcs))")
        Truth.assertThat(com.google.common.collect.ImmutableList.of<String?>("a.txt", "b.img"))
            .isEqualTo(ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesArtifactsFromDefaultAndFiles() {
        setRuleContext(createRuleContext("//foo:bar"))
        // It would be nice to write [DEFAULT] + ruleContext.files.srcs, but artifacts
        // is an ImmutableList and Starlark interprets it as a tuple.
        val result: Any =
            ev.eval("ruleContext.runfiles(collect_default = True, files = ruleContext.files.srcs)")
        // From DEFAULT only libjl.jar comes, see testRunfilesAddFromDependencies().
        Truth.assertThat(com.google.common.collect.ImmutableList.of<String?>("libjl.jar", "gl.a", "gl.gcgox"))
            .isEqualTo(ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesArtifactsFromSymlink() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.runfiles(symlinks = {'sym1': ruleContext.files.srcs[0]})")
        Truth.assertThat(com.google.common.collect.ImmutableList.of<String?>("a.txt"))
            .isEqualTo(ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesArtifactsFromRootSymlink() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any =
            ev.eval("ruleContext.runfiles(root_symlinks = {'sym1': ruleContext.files.srcs[0]})")
        Truth.assertThat(com.google.common.collect.ImmutableList.of<String?>("a.txt"))
            .isEqualTo(ActionsTestUtil.baseArtifactNames(getRunfileArtifacts(result)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesBadKeywordArguments() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "runfiles() got unexpected keyword argument 'bad_keyword'",
            "ruleContext.runfiles(bad_keyword = '')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNsetContainsList() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "depset elements must not be mutable values", "depset([[ruleContext.files.srcs]])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructPlusArtifactErrorMessage() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "unsupported binary operation: File + struct",
            "ruleContext.files.tools[0] + struct(a = 1)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoSuchProviderErrorMessage() {
        setRuleContext(createRuleContext("//foo:bar"))
        ev.update(
            "MyInfo",
            StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN)
                .buildExported(
                    Key(
                        keyForBuild(Label.parseCanonicalUnchecked("//myinfo:myinfo.bzl")), "MyInfo"
                    )
                )
        )
        ev.checkEvalErrorContains(
            "<target //foo:jl> (rule 'java_library') doesn't contain declared provider 'MyInfo'",
            "ruleContext.attr.srcs[0][MyInfo]"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesForRuleConfiguredTarget() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.attr.srcs[0].files")
        assertThat(ActionsTestUtil.baseNamesOf((result as Depset).getSet(Artifact::class.java)))
            .isEqualTo("a.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultProvider() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        def _impl(ctx):
            default = DefaultInfo(
                runfiles=ctx.runfiles(ctx.files.runs),
            )
            foo = foo_provider()
            return [foo, default]
        foo_rule = rule(
            implementation = _impl,
            attrs = {
               'runs': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/bar.bzl",
            """
        load(':foo.bzl', 'foo_provider')
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
            provider = ctx.attr.deps[0][DefaultInfo]
            return [MyInfo(
                is_provided = DefaultInfo in ctx.attr.deps[0],
                provider = provider,
                dir = str(sorted(dir(provider))),
                rule_data_runfiles = provider.data_runfiles,
                rule_default_runfiles = provider.default_runfiles,
                rule_files = provider.files,
                rule_files_to_run = provider.files_to_run,
                rule_file_executable = provider.files_to_run.executable
            )]
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        load(':bar.bzl', 'bar_rule')
        foo_rule(name = 'dep_rule', runs = ['run.file', 'run2.file'])
        bar_rule(name = 'my_rule', deps = [':dep_rule', 'file.txt'])
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//test:my_rule")
        val myInfo: StructImpl = getMyInfoFromTarget(configuredTarget)
        Truth.assertThat(myInfo.getValue("is_provided") as Boolean?).isTrue()

        val provider: Any = myInfo.getValue("provider")
        Truth.assertThat(provider).isInstanceOf(DefaultInfo::class.java)
        assertThat((provider as DefaultInfo).getProvider().getKey())
            .isEqualTo(DefaultInfo.PROVIDER.getKey())

        assertThat(myInfo.getValue("dir"))
            .isEqualTo("[\"data_runfiles\", \"default_runfiles\", \"files\", \"files_to_run\"]")

        assertThat(myInfo.getValue("rule_data_runfiles")).isInstanceOf(Runfiles::class.java)
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                (myInfo.getValue("rule_data_runfiles") as Runfiles).getAllArtifacts().toList(),
                com.google.common.base.Function { obj: F? -> java.lang.String.valueOf(obj) })
        )
            .containsExactly(
                "File:[/workspace[source]]test/run.file", "File:[/workspace[source]]test/run2.file"
            )

        assertThat(myInfo.getValue("rule_default_runfiles")).isInstanceOf(Runfiles::class.java)
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                (myInfo.getValue("rule_default_runfiles") as Runfiles).getAllArtifacts().toList(),
                com.google.common.base.Function { obj: F? -> java.lang.String.valueOf(obj) })
        )
            .containsExactly(
                "File:[/workspace[source]]test/run.file", "File:[/workspace[source]]test/run2.file"
            )

        assertThat(myInfo.getValue("rule_files")).isInstanceOf(Depset::class.java)
        assertThat(myInfo.getValue("rule_files_to_run")).isInstanceOf(FilesToRunProvider::class.java)
        assertThat(myInfo.getValue("rule_file_executable")).isEqualTo(Starlark.NONE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultProviderInStruct() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        def _impl(ctx):
            default = DefaultInfo(
                runfiles=ctx.runfiles(ctx.files.runs),
            )
            foo = foo_provider()
            return [foo, default]
        foo_rule = rule(
            implementation = _impl,
            attrs = {
               'runs': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/bar.bzl",
            """
        load(':foo.bzl', 'foo_provider')
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
            provider = ctx.attr.deps[0][DefaultInfo]
            return [MyInfo(
                is_provided = DefaultInfo in ctx.attr.deps[0],
                provider = provider,
                dir = str(sorted(dir(provider))),
                rule_data_runfiles = provider.data_runfiles,
                rule_default_runfiles = provider.default_runfiles,
                rule_files = provider.files,
                rule_files_to_run = provider.files_to_run,
            )]
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        load(':bar.bzl', 'bar_rule')
        foo_rule(name = 'dep_rule', runs = ['run.file', 'run2.file'])
        bar_rule(name = 'my_rule', deps = [':dep_rule', 'file.txt'])
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//test:my_rule")
        val myInfo: StructImpl = getMyInfoFromTarget(configuredTarget)

        Truth.assertThat(myInfo.getValue("is_provided") as Boolean?).isTrue()

        val provider: Any = myInfo.getValue("provider")
        Truth.assertThat(provider).isInstanceOf(DefaultInfo::class.java)
        assertThat((provider as DefaultInfo).getProvider().getKey())
            .isEqualTo(DefaultInfo.PROVIDER.getKey())

        assertThat(myInfo.getValue("dir"))
            .isEqualTo("[\"data_runfiles\", \"default_runfiles\", \"files\", \"files_to_run\"]")

        assertThat(myInfo.getValue("rule_data_runfiles")).isInstanceOf(Runfiles::class.java)
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                (myInfo.getValue("rule_data_runfiles") as Runfiles).getAllArtifacts().toList(),
                com.google.common.base.Function { obj: F? -> java.lang.String.valueOf(obj) })
        )
            .containsExactly(
                "File:[/workspace[source]]test/run.file", "File:[/workspace[source]]test/run2.file"
            )

        assertThat(myInfo.getValue("rule_default_runfiles")).isInstanceOf(Runfiles::class.java)
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                (myInfo.getValue("rule_default_runfiles") as Runfiles).getAllArtifacts().toList(),
                com.google.common.base.Function { obj: F? -> java.lang.String.valueOf(obj) })
        )
            .containsExactly(
                "File:[/workspace[source]]test/run.file", "File:[/workspace[source]]test/run2.file"
            )

        assertThat(myInfo.getValue("rule_files")).isInstanceOf(Depset::class.java)
        assertThat(myInfo.getValue("rule_files_to_run")).isInstanceOf(FilesToRunProvider::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultProviderOnFileTarget() {
        scratch.file(
            "test/bar.bzl",
            """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
            provider = ctx.attr.deps[0][DefaultInfo]
            return [MyInfo(
                is_provided = DefaultInfo in ctx.attr.deps[0],
                provider = provider,
                dir = str(sorted(dir(provider))),
                file_data_runfiles = provider.data_runfiles,
                file_default_runfiles = provider.default_runfiles,
                file_files = provider.files,
                file_files_to_run = provider.files_to_run,
            )]
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':bar.bzl', 'bar_rule')
        bar_rule(name = 'my_rule', deps = ['file.txt'])
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//test:my_rule")
        val myInfo: StructImpl = getMyInfoFromTarget(configuredTarget)

        Truth.assertThat(myInfo.getValue("is_provided") as Boolean?).isTrue()

        val provider: Any = myInfo.getValue("provider")
        Truth.assertThat(provider).isInstanceOf(DefaultInfo::class.java)
        assertThat((provider as DefaultInfo).getProvider().getKey())
            .isEqualTo(DefaultInfo.PROVIDER.getKey())

        assertThat(myInfo.getValue("dir"))
            .isEqualTo("[\"data_runfiles\", \"default_runfiles\", \"files\", \"files_to_run\"]")

        assertThat(myInfo.getValue("file_data_runfiles")).isInstanceOf(Runfiles::class.java)
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                (myInfo.getValue("file_data_runfiles") as Runfiles).getAllArtifacts().toList(),
                com.google.common.base.Function { obj: F? -> java.lang.String.valueOf(obj) })
        )
            .isEmpty()

        assertThat(myInfo.getValue("file_default_runfiles")).isInstanceOf(Runfiles::class.java)
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                (myInfo.getValue("file_default_runfiles") as Runfiles).getAllArtifacts().toList(),
                com.google.common.base.Function { obj: F? -> java.lang.String.valueOf(obj) })
        )
            .isEmpty()

        assertThat(myInfo.getValue("file_files")).isInstanceOf(Depset::class.java)
        assertThat(myInfo.getValue("file_files_to_run")).isInstanceOf(FilesToRunProvider::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultProviderProvidedImplicitly() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        def _impl(ctx):
            foo = foo_provider()
            return [foo]
        foo_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/bar.bzl",
            """
        load(':foo.bzl', 'foo_provider')
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
            dep = ctx.attr.deps[0]
            provider = dep[DefaultInfo]  # The goal is to test this object
            return [MyInfo(  # so we return it here
                default = provider,
            )]
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        load(':bar.bzl', 'bar_rule')
        foo_rule(name = 'dep_rule')
        bar_rule(name = 'my_rule', deps = [':dep_rule'])
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//test:my_rule")
        val provider: Any = getMyInfoFromTarget(configuredTarget).getValue("default")
        Truth.assertThat(provider).isInstanceOf(DefaultInfo::class.java)
        assertThat((provider as DefaultInfo).getProvider().getKey())
            .isEqualTo(DefaultInfo.PROVIDER.getKey())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultProviderUnknownFields() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        def _impl(ctx):
            default = DefaultInfo(
                foo=ctx.runfiles(),
            )
            return [default]
        foo_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        foo_rule(name = 'my_rule')
        
        """.trimIndent()
        )
        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_rule") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("DefaultInfo() got unexpected keyword argument 'foo'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclaredProviders() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        foobar_provider = provider()
        def _impl(ctx):
            foo = foo_provider()
            foobar = foobar_provider()
            return [foo, foobar]
        foo_rule = rule(
            implementation = _impl,
            attrs = {
               "srcs": attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/bar.bzl",
            """
        load(':foo.bzl', 'foo_provider')
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
            dep = ctx.attr.deps[0]
            provider = dep[foo_provider]  # The goal is to test this object
            return [MyInfo(proxy = provider)]  # so we return it here
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'srcs': attr.label_list(allow_files=True),
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        load(':bar.bzl', 'bar_rule')
        foo_rule(name = 'dep_rule')
        bar_rule(name = 'my_rule', deps = [':dep_rule'])
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//test:my_rule")
        val provider: Any = getMyInfoFromTarget(configuredTarget).getValue("proxy")
        Truth.assertThat(provider).isInstanceOf(StructImpl::class.java)
        assertThat((provider as StructImpl).getProvider().getKey())
            .isEqualTo(
                Key(
                    keyForBuild(Label.parseCanonical("//test:foo.bzl")), "foo_provider"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAdvertisedProviders() {
        scratch.file(
            "test/foo.bzl",
            """
        FooInfo = provider()
        BarInfo = provider()
        def _impl(ctx):
            foo = FooInfo()
            bar = BarInfo()
            return [foo, bar]
        foo_rule = rule(
            implementation = _impl,
            provides = [FooInfo, BarInfo]
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/bar.bzl",
            """
        load(':foo.bzl', 'FooInfo')
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
            dep = ctx.attr.deps[0]
            proxy = dep[FooInfo]  # The goal is to test this object
            return [MyInfo(proxy = proxy)]  # so we return it here
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        load(':bar.bzl', 'bar_rule')
        foo_rule(name = 'dep_rule')
        bar_rule(name = 'my_rule', deps = [':dep_rule'])
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//test:my_rule")
        val provider: Any = getMyInfoFromTarget(configuredTarget).getValue("proxy")
        Truth.assertThat(provider).isInstanceOf(StructImpl::class.java)
        assertThat((provider as StructImpl).getProvider().getKey())
            .isEqualTo(
                Key(
                    keyForBuild(Label.parseCanonical("//test:foo.bzl")), "FooInfo"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLacksAdvertisedDeclaredProvider() {
        scratch.file(
            "test/foo.bzl",
            """
        FooInfo = provider()
        def _impl(ctx):
            default = DefaultInfo(
                runfiles=ctx.runfiles(ctx.files.runs),
            )
            return [default]
        foo_rule = rule(
            implementation = _impl,
            attrs = {
               'runs': attr.label_list(allow_files=True),
            },
            provides = [FooInfo, DefaultInfo]
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        foo_rule(name = 'my_rule', runs = ['run.file', 'run2.file'])
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_rule") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "rule advertised the 'FooInfo' provider, "
                        + "but this provider was not among those returned"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLacksAdvertisedBuiltinProvider() {
        scratch.file(
            "test/foo.bzl",
            """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")
        FooInfo = provider()
        def _impl(ctx):
            MyFooInfo = FooInfo()
            return [MyFooInfo]
        foo_rule = rule(
            implementation = _impl,
            provides = [FooInfo, JavaInfo]
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        foo_rule(name = 'my_rule')
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_rule") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "rule advertised the 'JavaInfo' provider, "
                        + "but this provider was not among those returned"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadlySpecifiedProvides() {
        scratch.file(
            "test/foo.bzl",
            """
        def _impl(ctx):
            return []
        foo_rule = rule(
            implementation = _impl,
            provides = [1]
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        foo_rule(name = 'my_rule')
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_rule") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Error in rule: at index 0 of provides, got element of type int, want Provider")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleDeclaredProvider() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        def _impl(ctx):
            return foo_provider(a=123)
        foo_rule = rule(
            implementation = _impl,
            attrs = {
               "srcs": attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/bar.bzl",
            """
        load(':foo.bzl', 'foo_provider')
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
            dep = ctx.attr.deps[0]
            provider = dep[foo_provider]  # The goal is to test this object
            return [MyInfo(proxy = provider)]  # so we return it here
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'srcs': attr.label_list(allow_files=True),
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        load(':bar.bzl', 'bar_rule')
        foo_rule(name = 'dep_rule')
        bar_rule(name = 'my_rule', deps = [':dep_rule'])
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//test:my_rule")
        val provider: Any = getMyInfoFromTarget(configuredTarget).getValue("proxy")
        Truth.assertThat(provider).isInstanceOf(StructImpl::class.java)
        assertThat((provider as StructImpl).getProvider().getKey())
            .isEqualTo(
                Key(
                    keyForBuild(Label.parseCanonical("//test:foo.bzl")), "foo_provider"
                )
            )
        assertThat((provider as StructImpl).getValue("a")).isEqualTo(StarlarkInt.of(123))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclaredProvidersAliasTarget() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        foobar_provider = provider()
        def _impl(ctx):
            foo = foo_provider()
            foobar = foobar_provider()
            return [foo, foobar]
        foo_rule = rule(
            implementation = _impl,
            attrs = {
               "srcs": attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/bar.bzl",
            """
        load(':foo.bzl', 'foo_provider')
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
            dep = ctx.attr.deps[0]
            provider = dep[foo_provider]  # The goal is to test this object
            return [MyInfo(proxy = provider)]  # so we return it here
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'srcs': attr.label_list(allow_files=True),
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        load(':bar.bzl', 'bar_rule')
        foo_rule(name = 'foo_rule')
        alias(name = 'dep_rule', actual=':foo_rule')
        bar_rule(name = 'my_rule', deps = [':dep_rule'])
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//test:my_rule")
        val provider: Any = getMyInfoFromTarget(configuredTarget).getValue("proxy")
        Truth.assertThat(provider).isInstanceOf(StructImpl::class.java)
        assertThat((provider as StructImpl).getProvider().getKey())
            .isEqualTo(
                Key(
                    keyForBuild(Label.parseCanonical("//test:foo.bzl")), "foo_provider"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclaredProvidersWrongKey() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        unused_provider = provider()
        def _impl(ctx):
            foo = foo_provider()
            return [foo]
        foo_rule = rule(
            implementation = _impl,
            attrs = {
               "srcs": attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/bar.bzl",
            """
        load(':foo.bzl', 'unused_provider')
        def _impl(ctx):
            dep = ctx.attr.deps[0]
            provider = dep[unused_provider]  # Should throw an error here
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'srcs': attr.label_list(allow_files=True),
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        load(':bar.bzl', 'bar_rule')
        foo_rule(name = 'dep_rule')
        bar_rule(name = 'my_rule', deps = [':dep_rule'])
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_rule") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "<target //test:dep_rule> (rule 'foo_rule') doesn't contain "
                        + "declared provider 'unused_provider'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclaredProvidersInvalidKey() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        def _impl(ctx):
            foo = foo_provider()
            return [foo]
        foo_rule = rule(
            implementation = _impl,
            attrs = {
               "srcs": attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/bar.bzl",
            """
        def _impl(ctx):
            dep = ctx.attr.deps[0]
            provider = dep['foo_provider']  # Should throw an error here
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'srcs': attr.label_list(allow_files=True),
               'deps': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'foo_rule')
        load(':bar.bzl', 'bar_rule')
        foo_rule(name = 'dep_rule')
        bar_rule(name = 'my_rule', deps = [':dep_rule'])
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_rule") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Type Target only supports indexing by object constructors, got string instead")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclaredProvidersFileTarget() {
        scratch.file(
            "test/bar.bzl",
            """
        unused_provider = provider()
        def _impl(ctx):
            src = ctx.attr.srcs[0]
            provider = src[unused_provider]  # Should throw an error here
        bar_rule = rule(
            implementation = _impl,
            attrs = {
               'srcs': attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':bar.bzl', 'bar_rule')
        bar_rule(name = 'my_rule', srcs = ['input.txt'])
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_rule") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "<input file target //test:input.txt> doesn't contain "
                        + "declared provider 'unused_provider'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclaredProvidersInOperator() {
        scratch.file(
            "test/foo.bzl",
            """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        foo_provider = provider()
        bar_provider = provider()

        def _inner_impl(ctx):
            foo = foo_provider()
            return [foo]
        inner_rule = rule(
            implementation = _inner_impl,
        )

        def _outer_impl(ctx):
            dep = ctx.attr.deps[0]
            return [MyInfo(
                foo = (foo_provider in dep),  # Should be true
                bar = (bar_provider in dep),  # Should be false
            )]
        outer_rule = rule(
            implementation = _outer_impl,
            attrs = {
               'deps': attr.label_list(),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'inner_rule', 'outer_rule')
        inner_rule(name = 'dep_rule')
        outer_rule(name = 'my_rule', deps = [':dep_rule'])
        
        """.trimIndent()
        )

        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//test:my_rule")
        val myInfo: StructImpl = getMyInfoFromTarget(configuredTarget)

        val foo: Any? = myInfo.getValue("foo")
        Truth.assertThat(foo).isInstanceOf(Boolean::class.java)
        Truth.assertThat(foo as Boolean?).isTrue()
        val bar: Any? = myInfo.getValue("bar")
        Truth.assertThat(bar).isInstanceOf(Boolean::class.java)
        Truth.assertThat(bar as Boolean?).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclaredProvidersInOperatorInvalidKey() {
        scratch.file(
            "test/foo.bzl",
            """
        foo_provider = provider()
        bar_provider = provider()

        def _inner_impl(ctx):
            foo = foo_provider()
            return [foo]
        inner_rule = rule(
            implementation = _inner_impl,
        )

        def _outer_impl(ctx):
            dep = ctx.attr.deps[0]
            'foo_provider' in dep  # Should throw an error here
        outer_rule = rule(
            implementation = _outer_impl,
            attrs = {
               'deps': attr.label_list(),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':foo.bzl', 'inner_rule', 'outer_rule')
        inner_rule(name = 'dep_rule')
        outer_rule(name = 'my_rule', deps = [':dep_rule'])
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_rule") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("Type Target only supports querying by object constructors, got string instead")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReturnNonExportedProvider() {
        scratch.file(
            "test/my_rule.bzl",
            """
        def _rule_impl(ctx):
            foo_provider = provider()
            foo = foo_provider()
            return [foo]

        my_rule = rule(
            implementation = _rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':my_rule.bzl', 'my_rule')
        my_rule(name = 'my_rule')
        
        """.trimIndent()
        )

        val ex: java.lang.AssertionError =
            org.junit.Assert.assertThrows<java.lang.AssertionError>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_rule") })
        val msg: String? = ex.message
        Truth.assertThat(msg)
            .contains("rule implementation function returned an instance of an unnamed provider")
        Truth.assertThat(msg).contains("Provider defined at /workspace/test/my_rule.bzl:2:28")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesForFileConfiguredTarget() {
        setRuleContext(createRuleContext("//foo:bar"))
        val result: Any = ev.eval("ruleContext.attr.srcs[0].files")
        assertThat(ActionsTestUtil.baseNamesOf((result as Depset).getSet(Artifact::class.java)))
            .isEqualTo("libjl.jar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCtxStructFieldsCustomErrorMessages() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains("No attribute 'foo' in attr.", "ruleContext.attr.foo")
        ev.checkEvalErrorContains("No attribute 'foo' in outputs.", "ruleContext.outputs.foo")
        ev.checkEvalErrorContains("No attribute 'foo' in files.", "ruleContext.files.foo")
        ev.checkEvalErrorContains("No attribute 'foo' in file.", "ruleContext.file.foo")
        ev.checkEvalErrorContains("No attribute 'foo' in executable.", "ruleContext.executable.foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBinDirPath() {
        val ctx: StarlarkRuleContext = createRuleContext("//foo:bar")
        setRuleContext(ctx)
        val result: Any = ev.eval("ruleContext.bin_dir.path")
        Truth.assertThat(result)
            .isEqualTo(ctx.getConfiguration().getBinFragment(RepositoryName.MAIN).getPathString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyLabelListTypeAttrInCtx() {
        setRuleContext(createRuleContext("//foo:baz"))
        val result: Any = ev.eval("ruleContext.attr.srcs")
        Truth.assertThat(result).isEqualTo(StarlarkList.empty<Any?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefinedMakeVariable() {
        useConfiguration("--define=FOO=bar")
        setRuleContext(createRuleContext("//foo:baz"))
        val foo = ev.eval("ruleContext.var['FOO']") as String
        Truth.assertThat(foo).isEqualTo("bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodeCoverageConfigurationAccess() {
        val ctx: StarlarkRuleContext = createRuleContext("//foo:baz")
        setRuleContext(ctx)
        val coverage = ev.eval("ruleContext.configuration.coverage_enabled") as Boolean
        assertThat(ctx.getRuleContext().getConfiguration().isCodeCoverageEnabled()).isEqualTo(coverage)
    }

    /** Checks whether the given (invalid) statement leads to the expected error  */
    @Throws(java.lang.Exception::class)
    private fun checkReportedErrorStartsWith(errorMsg: String?, vararg statements: String?) {
        // If the component under test relies on Reporter and EventCollector for error handling, any
        // error would lead to an asynchronous AssertionFailedError thanks to failFastHandler in
        // FoundationTestCase.
        //
        // Consequently, we disable failFastHandler and check all events for the expected error message
        reporter.removeHandler(failFastHandler)

        val result: Any = ev.eval(*statements)

        var first: String? = null
        var count = 0

        try {
            for (evt in eventCollector) {
                if (evt.getMessage().startsWith(errorMsg)) {
                    return
                }

                ++count
                first = evt.getMessage()
            }

            if (count == 0) {
                org.junit.Assert.fail(
                    String.format(
                        "checkReportedErrorStartsWith(): There was no error; the result is '%s'", result
                    )
                )
            } else {
                org.junit.Assert.fail(
                    String.format(
                        "Found %d error(s), but none with the expected message '%s'. First error: '%s'",
                        count, errorMsg, first
                    )
                )
            }
        } finally {
            eventCollector.clear()
        }
    }

    @StarlarkMethod(name = "throw2", documented = false)
    @Throws(java.lang.Exception::class)
    fun throw2(): Any? {
        throw java.lang.InterruptedException()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoStackTraceOnInterrupt() {
        defineTestMethods()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { ev.eval("throw2()") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobInImplicitOutputs() {
        scratch.file(
            "test/glob.bzl",
            """
        def _impl(ctx):
          ctx.actions.do_nothing(
            inputs = [],
          )
        def _foo():
          return native.glob(['*'])
        glob_rule = rule(
          implementation = _impl,
          outputs = _foo,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:glob.bzl', 'glob_rule')
        glob_rule(name = 'my_glob',
          srcs = ['foo.bar', 'other_foo.bar'])
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:my_glob")
        assertContainsEvent("glob() can only be used while evaluating a BUILD file or a legacy macro")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleFromBzlFile() {
        scratch.file(
            "test/rule.bzl",
            """
        def _impl(ctx): return
        foo = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/ext.bzl",
            """
        load('//test:rule.bzl', 'foo')
        a = 1
        foo(name = 'x')
        
        """.trimIndent()
        )
        scratch.file("test/BUILD", "load('//test:ext.bzl', 'a')")
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:x")
        assertContainsEvent(
            "a rule can only be instantiated while evaluating a BUILD file or a legacy or symbolic"
                    + " macro"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitOutputsFromGlob() {
        scratch.file(
            "test/glob.bzl",
            """
        def _impl(ctx):
          outs = ctx.outputs
          for i in ctx.attr.srcs:
            o = getattr(outs, 'foo_' + i.label.name)
            ctx.actions.write(
              output = o,
              content = 'hoho')

        def _foo(srcs):
          outs = {}
          for i in srcs:
            outs['foo_' + i.name] = i.name + '.out'
          return outs

        glob_rule = rule(
            attrs = {
                'srcs': attr.label_list(allow_files = True),
            },
            outputs = _foo,
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file("test/a.bar", "a")
        scratch.file("test/b.bar", "b")
        scratch.file(
            "test/BUILD",
            """
        load('//test:glob.bzl', 'glob_rule')
        glob_rule(name = 'my_glob', srcs = glob(['*.bar']))
        
        """.trimIndent()
        )
        val ct: ConfiguredTarget = getConfiguredTarget("//test:my_glob")
        assertThat(ct).isNotNull()
        assertThat(getGeneratingAction(getBinArtifact("a.bar.out", ct))).isNotNull()
        assertThat(getGeneratingAction(getBinArtifact("b.bar.out", ct))).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuiltInFunctionAsRuleImplementation() {
        // Using built-in functions as rule implementations shouldn't cause runtime errors
        scratch.file(
            "test/rule.bzl",
            """
        silly_rule = rule(
            implementation = int,
            attrs = {
               "srcs": attr.label_list(allow_files=True),
            }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'silly_rule')
        silly_rule(name = 'silly')
        
        """.trimIndent()
        )
        thrown.handleAssertionErrors() // Compatibility with JUnit 4.11
        thrown.expect(java.lang.AssertionError::class.java)
        thrown.expectMessage(
            "in call to rule(), parameter 'implementation' got value of type"
                    + " 'builtin_function_or_method', want 'function'"
        )
        getConfiguredTarget("//test:silly")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsScalarAdd() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "args = ruleContext.actions.args()",
            "args.add('--foo')",
            "args.add('-')",
            "args.add('foo', format='format%s')",
            "args.add('-')",
            "args.add('--foo', 'val')",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly("foo/t.exe", "--foo", "-", "formatfoo", "-", "--foo", "val")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsScalarAddThrowsWithVectorArg() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "Args.add() doesn't accept vectorized arguments",
            "args = ruleContext.actions.args()",
            "args.add([1, 2])",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsAddAll() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "args = ruleContext.actions.args()",
            "args.add_all([1, 2])",
            "args.add('-')",
            "args.add_all('--foo', [1, 2])",
            "args.add('-')",
            "args.add_all([1, 2], before_each='-before')",
            "args.add('-')",
            "args.add_all([1, 2], format_each='format/%s')",
            "args.add('-')",
            "args.add_all(ruleContext.files.srcs)",
            "args.add('-')",
            "args.add_all(ruleContext.files.srcs, format_each='format/%s')",
            "args.add('-')",
            "args.add_all([1, 2], terminate_with='--terminator')",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly(
                "foo/t.exe",
                "1",
                "2",
                "-",
                "--foo",
                "1",
                "2",
                "-",
                "-before",
                "1",
                "-before",
                "2",
                "-",
                "format/1",
                "format/2",
                "-",
                "foo/a.txt",
                "foo/b.img",
                "-",
                "format/foo/a.txt",
                "format/foo/b.img",
                "-",
                "1",
                "2",
                "--terminator"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsAddAllWithMapEach() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "def add_one(val): return str(val + 1)",
            "def expand_to_many(val): return ['hey', 'hey']",
            "args = ruleContext.actions.args()",
            "args.add_all([1, 2], map_each=add_one)",
            "args.add('-')",
            "args.add_all([1, 2], map_each=expand_to_many)",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly("foo/t.exe", "2", "3", "-", "hey", "hey", "hey", "hey")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOmitIfEmpty() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "def add_one(val): return str(val + 1)",
            "def filter(val): return None",
            "args = ruleContext.actions.args()",
            "args.add_joined([], join_with=',')",
            "args.add('-')",
            "args.add_joined([], join_with=',', omit_if_empty=False)",
            "args.add('-')",
            "args.add_all('--foo', [])",
            "args.add('-')",
            "args.add_all('--foo', [], omit_if_empty=False)",
            "args.add('-')",
            "args.add_all('--foo', [1], map_each=filter, terminate_with='hello')",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly(
                "foo/t.exe",  // Nothing
                "-",
                "",  // Empty string was joined and added
                "-",  // Nothing
                "-",
                "--foo",  // Arg added regardless
                "-" // Nothing, all values were filtered
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUniquify() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "def add_one(val): return str(val + 1)",
            "args = ruleContext.actions.args()",
            "args.add_all(['a', 'b', 'a'])",
            "args.add('-')",
            "args.add_all(['a', 'b', 'a', 'c', 'b'], uniquify=True)",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly("foo/t.exe", "a", "b", "a", "-", "a", "b", "c")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsAddJoined() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "def add_one(val): return str(val + 1)",
            "args = ruleContext.actions.args()",
            "args.add_joined([1, 2], join_with=':')",
            "args.add('-')",
            "args.add_joined([1, 2], join_with=':', format_each='format/%s')",
            "args.add('-')",
            "args.add_joined([1, 2], join_with=':', format_each='format/%s', format_joined='--foo=%s')",
            "args.add('-')",
            "args.add_joined([1, 2], join_with=':', map_each=add_one)",
            "args.add('-')",
            "args.add_joined(ruleContext.files.srcs, join_with=':')",
            "args.add('-')",
            "args.add_joined(ruleContext.files.srcs, join_with=':', format_each='format/%s')",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly(
                "foo/t.exe",
                "1:2",
                "-",
                "format/1:format/2",
                "-",
                "--foo=format/1:format/2",
                "-",
                "2:3",
                "-",
                "foo/a.txt:foo/b.img",
                "-",
                "format/foo/a.txt:format/foo/b.img"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleLazyArgsMixedWithStrings() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "foo_args = ruleContext.actions.args()",
            "foo_args.add('--foo')",
            "bar_args = ruleContext.actions.args()",
            "bar_args.add('--bar')",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = ['hello', foo_args, 'world', bar_args, 'works'],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly("foo/t.exe", "hello", "--foo", "world", "--bar", "works")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLazyArgsWithParamFile() {
        scratch.file(
            "test/main_rule.bzl",
            """
        def _impl(ctx):
          args = ctx.actions.args()
          args.add('--foo')
          args.use_param_file('--file=%s', use_always=True)
          output=ctx.actions.declare_file('out')
          ctx.actions.run_shell(
            inputs = [output],
            outputs = [output],
            arguments = [args],
            command = 'touch out',
          )
        main_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:main_rule.bzl', 'main_rule')
        main_rule(name='main')
        
        """.trimIndent()
        )
        val ct: ConfiguredTarget = getConfiguredTarget("//test:main")
        val output: Artifact = getBinArtifact("out", ct)
        val action: SpawnAction = getGeneratingAction(output) as SpawnAction
        Truth.assertThat(paramFileArgsForAction(action)).containsExactly("--foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWriteArgsToParamFile() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "args = ruleContext.actions.args()",
            "args.add('--foo')",
            "output=ruleContext.actions.declare_file('out')",
            "ruleContext.actions.write(",
            "  output=output,",
            "  content=args,",
            ")"
        )
        val actions: MutableList<ActionAnalysisMetadata?> =
            ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
        val action: java.util.Optional<ActionAnalysisMetadata?> =
            actions.stream().filter { a: ActionAnalysisMetadata? -> a is ParameterFileWriteAction }.findFirst()
        Truth.assertThat(action.isPresent()).isTrue()
        val paramAction: ParameterFileWriteAction = action.get() as ParameterFileWriteAction
        assertThat(paramAction.getArguments()).containsExactly("--foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLazyArgsWithParamFileInvalidFormatString() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "Invalid value for parameter \"param_file_arg\": "
                    + "Expected string with a single \"%s\", got \"--file=\"",
            "args = ruleContext.actions.args()\n" + "args.use_param_file('--file=')"
        )
        ev.checkEvalErrorContains(
            "Invalid value for parameter \"param_file_arg\": "
                    + "Expected string with a single \"%s\", got \"--file=%s%s\"",
            "args = ruleContext.actions.args()\n" + "args.use_param_file('--file=%s%s')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLazyArgsWithParamFileInvalidFormat() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "Invalid value for parameter \"format\": Expected one of \"shell\", \"multiline\"",
            "args = ruleContext.actions.args()\n" + "args.set_param_file_format('illegal')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsAddInvalidTypesForArgAndValues() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "expected value of type 'string' for arg name, got 'int'",
            "args = ruleContext.actions.args()",
            "args.add(1, 'value')"
        )
        ev.checkEvalErrorContains(
            "expected value of type 'string' for arg name, got 'int'",
            "args = ruleContext.actions.args()",
            "args.add_all(1, [1, 2])"
        )
        ev.checkEvalErrorContains(
            "expected value of type 'sequence or depset' for values, got 'int'",
            "args = ruleContext.actions.args()",
            "args.add_all(1)"
        )
        ev.checkEvalErrorContains(
            "in call to add_all(), parameter 'values' got value of type 'int', want 'sequence or"
                    + " depset'",
            "args = ruleContext.actions.args()",
            "args.add_all('--foo', 1)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLazyArgIllegalFormatString() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "Invalid value for parameter \"format\": Expected string with a single \"%s\"",
            "args = ruleContext.actions.args()",
            "args.add('foo', format='illegal_format')",  // Expects two args, will only be given one
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMapEachAcceptsBuiltinFunction() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        // map_each accepts a non-Starlark built-in function such as str.
        ev.exec("ruleContext.actions.args().add_all(['foo'], map_each = str)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLazyArgMapEachThrowsError() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "args = ruleContext.actions.args()",
            "def bad_fn(val): 'hello'.nosuchmethod()",
            "args.add_all([1, 2], map_each=bad_fn)",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        val e: CommandLineExpansionException? =
            org.junit.Assert.assertThrows<T?>(
                CommandLineExpansionException::class.java,
                org.junit.function.ThrowingRunnable { action.getArguments() })
        assertThat(e).hasMessageThat().contains("'string' value has no field or method 'nosuchmethod'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLazyArgMapEachReturnsNone() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "args = ruleContext.actions.args()",
            "def none_fn(val): return None if val == 'nokeep' else val",
            "args.add_all(['keep', 'nokeep'], map_each=none_fn)",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments()).containsExactly("foo/t.exe", "keep").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLazyArgMapEachReturnsWrongType() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "args = ruleContext.actions.args()",
            "def bad_fn(val): return 1",
            "args.add_all([1, 2], map_each=bad_fn)",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        val e: CommandLineExpansionException =
            org.junit.Assert.assertThrows<T>(
                CommandLineExpansionException::class.java,
                org.junit.function.ThrowingRunnable { action.getArguments() })
        com.google.common.truth.Subject.contains("Expected map_each to return string, None, or list of strings, found int")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createShellWithLazyArgs() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "args = ruleContext.actions.args()",
            "args.add('--foo')",
            "ruleContext.actions.run_shell(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  mnemonic = 'DummyMnemonic',",
            "  command = 'dummy_command',",
            "  progress_message = 'dummy_message',",
            "  use_default_shell_env = True)"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        val args: MutableList<String?> = action.getArguments()
        // We don't need to assert the entire arg list, just check that
        // the dummy empty string is inserted followed by '--foo'
        Truth.assertThat(args.get(args.size - 2)).isEmpty()
        Truth.assertThat(com.google.common.collect.Iterables.getLast<String?>(args)).isEqualTo("--foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLazyArgsObjectImmutability() {
        scratch.file(
            "test/BUILD",
            """
        load('//test:rules.bzl', 'main_rule', 'dep_rule')
        dep_rule(name = 'dep')
        main_rule(name = 'main', deps = [':dep'])
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _main_impl(ctx):
          dep = ctx.attr.deps[0]
          args = dep[MyInfo].dep_arg
          args.add('hello')
        main_rule = rule(
          implementation = _main_impl,
          attrs = {
            'deps': attr.label_list()
          },
          outputs = {'file': 'output.txt'},
        )
        def _dep_impl(ctx):
          args = ctx.actions.args()
          return [MyInfo(dep_arg = args)]
        dep_rule = rule(implementation = _dep_impl)
        
        """.trimIndent()
        )
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:main") })
        Truth.assertThat(e).hasMessageThat().contains("trying to mutate a frozen Args value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsMainRepoLabel() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "actions = ruleContext.actions",
            "a = []",
            "a.append(actions.args().add(Label('//bar')))",
            "a.append(actions.args().add('-flag', Label('//bar')))",
            "a.append(actions.args().add('-flag', Label('//bar'), format = '_%s_'))",
            "a.append(actions.args().add_all(['foo', Label('//bar')]))",
            "a.append(actions.args().add_all(depset([Label('//foo'), Label('//bar')])))",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = a,",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly(
                "foo/t.exe",
                "//bar:bar",
                "-flag",
                "//bar:bar",
                "-flag",
                "_//bar:bar_",
                "foo",
                "//bar:bar",
                "//foo:foo",
                "//bar:bar"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsCanonicalRepoLabel() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "actions = ruleContext.actions",
            "a = []",
            "a.append(actions.args().add(Label('@@repo+//:foo')))",
            "a.append(actions.args().add('-flag', Label('@@repo+//:foo')))",
            "a.append(actions.args().add('-flag', Label('@@repo+//:foo'), format = '_%s_'))",
            "a.append(actions.args().add_all(['foo', Label('@@repo+//:foo')]))",
            "a.append(actions.args().add_all(depset([Label('@@other_repo+//:foo'),"
                    + " Label('@@repo+//:foo')])))",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = a,",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly(
                "foo/t.exe",
                "@@repo+//:foo",
                "-flag",
                "@@repo+//:foo",
                "-flag",
                "_@@repo+//:foo_",
                "foo",
                "@@repo+//:foo",
                "@@other_repo+//:foo",
                "@@repo+//:foo"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsApparentRepoLabel() {
        scratch.overwriteFile("MODULE.bazel", "bazel_dep(name = 'foo', version = '1.0')")
        registry.addModule(createModuleKey("foo", "1.0"), "module(name='foo', version='1.0')")
        invalidatePackages()

        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "actions = ruleContext.actions",
            "a = []",
            "a.append(actions.args().add(Label('@@foo+//:foo')))",
            "a.append(actions.args().add('-flag', Label('@@foo+//:foo')))",
            "a.append(actions.args().add('-flag', Label('@@foo+//:foo'), format = '_%s_'))",
            "a.append(actions.args().add_all(['foo', Label('@@foo+//:foo')]))",
            "a.append(actions.args().add_all(depset([Label('@@repo+//:foo'), Label('@@foo+//:foo')])))",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = a,",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val action: SpawnAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as SpawnAction?
        assertThat(action.getArguments())
            .containsExactly(
                "foo/t.exe",
                "@foo//:foo",
                "-flag",
                "@foo//:foo",
                "-flag",
                "_@foo//:foo_",
                "foo",
                "@foo//:foo",
                "@@repo+//:foo",
                "@foo//:foo"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsBuiltTwiceWithExternalLabel() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "args = ruleContext.actions.args()",
            "args.add(Label('@@foo'))",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")",
            "ruleContext.actions.run(",
            "  inputs = depset(ruleContext.files.srcs),",
            "  outputs = ruleContext.files.srcs,",
            "  arguments = [args],",
            "  executable = ruleContext.files.tools[0],",
            "  toolchain = None",
            ")"
        )
        val actions: MutableList<SpawnAction?>? =
            ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions().stream()
                .map({ obj: Any? -> SpawnAction::class.java.cast(obj) })
                .toList()
        Truth.assertThat(actions).hasSize(2)
        assertThat(actions.getFirst().getArguments())
            .containsExactlyElementsIn(actions.getLast().getArguments())
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationField_starlarkSplitTransitionProhibited() {
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = 'function_transition_allowlist',
            packages = [
                '//...',
            ],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/rule.bzl",
            """
        def _foo_impl(ctx):
          return []

        def _foo_transition_impl(settings):
          return {'t1': {}, 't2': {}}
        foo_transition = transition(implementation=_foo_transition_impl, inputs=[], outputs=[])

        foo = rule(
          implementation = _foo_impl,
          attrs = {
            '_attr': attr.label(
                cfg = foo_transition,
                default = configuration_field(fragment = "coverage", name = "output_generator"))})
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'foo')
        foo(name='foo')
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:foo")
        assertContainsEvent("late-bound attributes must not have a split configuration transition")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationField_invalidFragment() {
        scratch.file(
            "test/main_rule.bzl",
            """
        def _impl(ctx):
          return []
        main_rule = rule(implementation = _impl,
            attrs = { '_myattr': attr.label(
                default = configuration_field(
                fragment = 'notarealfragment', name = 'method_name')),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:main_rule.bzl', 'main_rule')
        main_rule(name='main')
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:main") })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("invalid configuration fragment name 'notarealfragment'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationField_doesNotChangeFragmentAccess() {
        scratch.file(
            "test/main_rule.bzl",
            """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
          return [MyInfo(platform = ctx.fragments.apple.single_arch_platform)]
        main_rule = rule(implementation = _impl,
            attrs = { '_myattr': attr.label(
                default = configuration_field(
                fragment = 'apple', name = 'xcode_config_label')),
            },
            fragments = [],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:main_rule.bzl', 'main_rule')
        main_rule(name='main')
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:main") })

        Truth.assertThat(expected).hasMessageThat().contains("has to declare 'apple' as a required fragment")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationField_invalidFieldName() {
        scratch.file(
            "test/main_rule.bzl",
            """
        def _impl(ctx):
          return []
        main_rule = rule(implementation = _impl,
            attrs = { '_myattr': attr.label(
                default = configuration_field(
                fragment = 'apple', name = 'notarealfield')),
            },
            fragments = ['apple'],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:main_rule.bzl', 'main_rule')
        main_rule(name='main')
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:main") })

        Truth.assertThat(expected)
            .hasMessageThat()
            .contains("invalid configuration field name 'notarealfield' on fragment 'apple'")
    }

    // Verifies that configuration_field can only be used on 'private' attributes.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationField_invalidVisibility() {
        scratch.file(
            "test/main_rule.bzl",
            """
        def _impl(ctx):
          return []
        main_rule = rule(implementation = _impl,
            attrs = { 'myattr': attr.label(
                default = configuration_field(
                fragment = 'apple', name = 'xcode_config_label')),
            },
            fragments = ['apple'],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:main_rule.bzl', 'main_rule')
        main_rule(name='main')
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:main") })

        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "When an attribute value is a function, "
                        + "the attribute must be private (i.e. start with '_')"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesToRunInActionsRun() {
        scratch.file(
            "a/a.bzl",
            """
        def _impl(ctx):
            f = ctx.actions.declare_file('output')
            ctx.actions.run(
                inputs = [],
                outputs = [f],
                executable = ctx.attr._tool[DefaultInfo].files_to_run,
                toolchain = None
            )
            return [DefaultInfo(files=depset([f]))]
        r = rule(implementation=_impl, attrs = {'_tool': attr.label(default='//a:tool')})
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(':a.bzl', 'r')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        r(name='r')
        foo_binary(name='tool', srcs=['tool.sh'], data=['data'])
        
        """.trimIndent()
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val action: Action =
            getGeneratingAction(r.getProvider(FileProvider::class.java).getFilesToBuild().getSingleton())
        com.google.common.truth.Subject.contains("tool.runfiles")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesToRunInActionsTools() {
        scratch.file(
            "a/a.bzl",
            """
        def _impl(ctx):
            f = ctx.actions.declare_file('output')
            ctx.actions.run(
                inputs = [],
                outputs = [f],
                tools = [ctx.attr._tool[DefaultInfo].files_to_run],
                executable = 'a/tool',
                toolchain = None
            )
            return [DefaultInfo(files=depset([f]))]
        r = rule(implementation=_impl, attrs = {'_tool': attr.label(default='//a:tool')})
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(':a.bzl', 'r')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        r(name='r')
        foo_binary(name='tool', srcs=['tool.sh'], data=['data'])
        
        """.trimIndent()
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val action: Action =
            getGeneratingAction(r.getProvider(FileProvider::class.java).getFilesToBuild().getSingleton())
        com.google.common.truth.Subject.contains("tool.runfiles")
    }

    // Verifies that configuration_field can only be used on 'label' attributes.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationField_invalidAttributeType() {
        scratch.file(
            "test/main_rule.bzl",
            """
        def _impl(ctx):
          return []
        main_rule = rule(implementation = _impl,
            attrs = { '_myattr': attr.int(
                default = configuration_field(
                fragment = 'apple', name = 'xcode_config_label')),
            },
            fragments = ['apple'],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:main_rule.bzl', 'main_rule')
        main_rule(name='main')
        
        """.trimIndent()
        )

        val expected: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:main") })

        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(
                "in call to int(), parameter 'default' got value of type 'LateBoundDefault', want"
                        + " 'int'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkCustomCommandLineKeyComputation() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLines: com.google.common.collect.ImmutableList.Builder<CommandLine?> =
            com.google.common.collect.ImmutableList.builder<CommandLine?>()

        commandLines.add(getCommandLine("args = ruleContext.actions.args()"))
        commandLines.add(getCommandLine("args = ruleContext.actions.args()", "args.add('foo')"))
        commandLines.add(
            getCommandLine("args = ruleContext.actions.args()", "args.add('--foo', 'foo')")
        )
        commandLines.add(
            getCommandLine("args = ruleContext.actions.args()", "args.add('foo', format='--foo=%s')")
        )
        commandLines.add(
            getCommandLine("args = ruleContext.actions.args()", "args.add_all(['foo', 'bar'])")
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()", "args.add_all('-foo', ['foo', 'bar'])"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()",
                "args.add_all(['foo', 'bar'], format_each='format%s')"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()", "args.add_all(['foo', 'bar'], before_each='-I')"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()", "args.add_all(['boing', 'boing', 'boing'])"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()",
                "args.add_all(['boing', 'boing', 'boing'], uniquify=True)"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()",
                "args.add_all(['foo', 'bar'], terminate_with='baz')"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()", "args.add_joined(['foo', 'bar'], join_with=',')"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()",
                "args.add_joined(['foo', 'bar'], join_with=',', format_joined='--foo=%s')"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()",
                "def _map_each(s): return s + '_mapped'",
                "args.add_all(['foo', 'bar'], map_each=_map_each)"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()",
                "values = depset(['a', 'b'])",
                "args.add_all(values)"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()",
                "def _map_each(s): return s + '_mapped'",
                "values = depset(['a', 'b'])",
                "args.add_all(values, map_each=_map_each)"
            )
        )
        commandLines.add(
            getCommandLine(
                "args = ruleContext.actions.args()",
                "def _map_each(s): return s + '_mapped_again'",
                "values = depset(['a', 'b'])",
                "args.add_all(values, map_each=_map_each)"
            )
        )

        // Ensure all these command lines have distinct keys
        val digests: MutableMap<String?, CommandLine?> = HashMap<String?, CommandLine?>()
        for (commandLine in commandLines.build()) {
            val digest = getDigest(commandLine)
            val previous: CommandLine? = digests.putIfAbsent(digest, commandLine)
            if (previous != null) {
                org.junit.Assert.fail(
                    String.format(
                        "Found two command lines with identical digest %s: '%s' and '%s'",
                        digest,
                        com.google.common.base.Joiner.on(' ').join(previous.arguments()),
                        com.google.common.base.Joiner.on(' ').join(commandLine.arguments())
                    )
                )
            }
        }

        // Ensure errors are handled
        val commandLine: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "def _bad_fn(s): return s.doesnotexist()",
                "values = depset(['a', 'b'])",
                "args.add_all(values, map_each=_bad_fn)"
            )
        org.junit.Assert.assertThrows<T?>(
            CommandLineExpansionException::class.java,
            org.junit.function.ThrowingRunnable {
                commandLine.addToFingerprint(
                    actionKeyContext,  /* inputMetadataProvider= */
                    null,
                    OutputPathsMode.OFF,
                    Fingerprint()
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_differentMapEach() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "def _fun1(arg): return 'val1'",
                "def _fun2(arg): return 'val2'",
                "args.add_all(['a'], map_each=_fun1)"
            )
        val commandLine2: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "def _fun1(arg): return 'val1'",
                "def _fun2(arg): return 'val2'",
                "args.add_all(['a'], map_each=_fun2)"
            )

        Truth.assertThat(getDigest(commandLine1)).isNotEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_differentArg() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "def _fun(arg): return arg",
                "args.add_all(['a'], map_each=_fun)"
            )
        val commandLine2: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "def _fun(arg): return arg",
                "args.add_all(['b'], map_each=_fun)"
            )

        Truth.assertThat(getDigest(commandLine1)).isNotEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputationWithExpander_equivalentMapEach_sameKey() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "args.add_joined([directory], join_with=',', map_each=str, expand_directories=True)"
            )
        val commandLine2: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "def mystr(file): return str(file)",
                "args.add_joined([directory], join_with=',', map_each=mystr, expand_directories=True)"
            )

        val inputMetadataProvider: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file")
        Truth.assertThat(getDigest(commandLine1, inputMetadataProvider))
            .isEqualTo(getDigest(commandLine2, inputMetadataProvider))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputationWithExpander_mapEachConstantForDir() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "def _constant_for_dir(f): return 'constant' if f.path.endswith('dir') else 'value1'",
                "args.add_all([directory], map_each=_constant_for_dir, expand_directories=True)"
            )
        val commandLine2: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "def _constant_for_dir(f): return 'constant' if f.path.endswith('dir') else 'value2'",
                "args.add_all([directory], map_each=_constant_for_dir, expand_directories=True)"
            )

        val inputMetadataProvider: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file")
        Truth.assertThat(getDigest(commandLine1, inputMetadataProvider))
            .isNotEqualTo(getDigest(commandLine2, inputMetadataProvider))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputationWithExpander_constantForDirWithNestedSet() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "dir = ruleContext.actions.declare_directory('dir')",
                "def _constant_for_dir(f): return 'constant' if f.path.endswith('dir') else 'value1'",
                "args.add_all(depset([dir]), map_each=_constant_for_dir, expand_directories=True)"
            )
        val commandLine2: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "dir = ruleContext.actions.declare_directory('dir')",
                "def _constant_for_dir(f): return 'constant' if f.path.endswith('dir') else 'value2'",
                "args.add_all(depset([dir]), map_each=_constant_for_dir, expand_directories=True)"
            )

        val inputMetadataProvider: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file")
        Truth.assertThat(getDigest(commandLine1, inputMetadataProvider))
            .isNotEqualTo(getDigest(commandLine2, inputMetadataProvider))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputationWithExpander_mapEachFailsForDir() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "ruleContext.actions.run_shell(outputs=[directory], command='')",
                "def _fail_for_dir(file):",
                "   if file.path.endswith('dir'): fail('hello')",
                "   return 'value1'",
                "args.add_all([directory], map_each=_fail_for_dir, expand_directories=True)"
            )
        val commandLine2: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "ruleContext.actions.run_shell(outputs=[directory], command='')",
                "directory = ruleContext.actions.declare_directory('dir')",
                "def _fail_for_dir(file):",
                "   if file.path.endswith('dir'): fail('hello')",
                "   return 'value2'",
                "args.add_all([directory], map_each=_fail_for_dir, expand_directories=True)"
            )

        val inputMetadataProvider: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file")
        Truth.assertThat(getDigest(commandLine1, inputMetadataProvider))
            .isNotEqualTo(getDigest(commandLine2, inputMetadataProvider))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputationWithExpander_differentExpansion() {
        setRuleContext(createRuleContext("//foo:foo"))
        val commandLine: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "ruleContext.actions.run_shell(outputs=[directory], command='')",
                "def _get_path(file): return file.path",
                "args.add_all([directory], map_each=_get_path, expand_directories=True)"
            )

        val inputMetadataProvider1: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file1")
        val inputMetadataProvider2: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file2")
        Truth.assertThat(getDigest(commandLine, inputMetadataProvider1))
            .isNotEqualTo(getDigest(commandLine, inputMetadataProvider2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputationWithExpander_differentExpansionNoMapEach() {
        setRuleContext(createRuleContext("//foo:foo"))
        val commandLine: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "args.add_all([directory])"
            )

        val inputMetadataProvider1: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file1")
        val inputMetadataProvider2: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file2")
        Truth.assertThat(getDigest(commandLine, inputMetadataProvider1))
            .isNotEqualTo(getDigest(commandLine, inputMetadataProvider2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputationWithExpander_extraFileInExpansionNoMapEach() {
        setRuleContext(createRuleContext("//foo:foo"))
        val commandLine: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "args.add_all([directory])"
            )

        val expander1: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file1")
        val expander2: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file1", "file2")
        Truth.assertThat(getDigest(commandLine, expander1)).isNotEqualTo(getDigest(commandLine, expander2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputationWithExpander_constantForDirAddJoined() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "def _constant_for_dir(f): return 'constant' if f.path.endswith('dir') else 'value1'",
                "args.add_joined([directory], join_with=',', map_each=_constant_for_dir,"
                        + " expand_directories=True)"
            )
        val commandLine2: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "def _constant_for_dir(f): return 'constant' if f.path.endswith('dir') else 'value2'",
                "args.add_joined([directory], join_with=',', map_each=_constant_for_dir,"
                        + " expand_directories=True)"
            )

        val inputMetadataProvider: InputMetadataProvider = createInputMetadataProvider("foo/dir", "file")
        Truth.assertThat(getDigest(commandLine1, inputMetadataProvider))
            .isNotEqualTo(getDigest(commandLine2, inputMetadataProvider))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_inconsequentialChangeToStarlarkSemantics() {
        setRuleContext(createRuleContext("//foo:foo"))
        val commandLine1: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "def _path(f): return f.path",
                "args.add_all([directory], map_each=_path)"
            )

        ev.setSemantics("--incompatible_run_shell_command_string=false")
        // setBuildLanguageOptions reinitializes the thread -- set the ruleContext on the new one.
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine2: CommandLine =
            getCommandLine(
                "args = ruleContext.actions.args()",
                "directory = ruleContext.actions.declare_directory('dir')",
                "def _path(f): return f.path",
                "args.add_all([directory], map_each=_path)"
            )

        Truth.assertThat(getDigest(commandLine1)).isEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_singleLabel_repoMappingChanges_relevant() {
        setRuleContext(createRuleContext("//foo:foo"))

        val mainRepoMapping1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent1",
                    RepositoryName.createUnvalidated("canonical+")
                ),
                RepositoryName.MAIN
            )
        val mainRepoMapping2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent2",
                    RepositoryName.createUnvalidated("canonical+")
                ),
                RepositoryName.MAIN
            )
        val args: String =
            """
        args = ruleContext.actions.args()
        args.add(Label("@@canonical+//foo:bar"))
        
        """.trimIndent()
        val commandLine1: CommandLine = getCommandLine(mainRepoMapping1, args)
        val commandLine2: CommandLine = getCommandLine(mainRepoMapping2, args)

        Truth.assertThat(com.google.common.collect.ImmutableList.copyOf(commandLine1.arguments()))
            .isNotEqualTo(com.google.common.collect.ImmutableList.copyOf(commandLine2.arguments()))
        Truth.assertThat(getDigest(commandLine1)).isNotEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_singleLabel_repoMappingChanges_notRelevant() {
        setRuleContext(createRuleContext("//foo:foo"))

        val mainRepoMapping1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent", RepositoryName.createUnvalidated("canonical+"),
                    "other_repo1", RepositoryName.createUnvalidated("other_repo+")
                ),
                RepositoryName.MAIN
            )
        val mainRepoMapping2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent", RepositoryName.createUnvalidated("canonical+"),
                    "other_repo2", RepositoryName.createUnvalidated("other_repo+")
                ),
                RepositoryName.MAIN
            )
        val args: String =
            """
        args = ruleContext.actions.args()
        args.add(Label("@@canonical+//foo:bar"))
        
        """.trimIndent()
        val commandLine1: CommandLine = getCommandLine(mainRepoMapping1, args)
        val commandLine2: CommandLine = getCommandLine(mainRepoMapping2, args)

        assertThat(commandLine1.arguments())
            .containsExactlyElementsIn(commandLine2.arguments())
            .inOrder()
        Truth.assertThat(getDigest(commandLine1)).isEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_listOfLabels_repoMappingChanges_relevant() {
        setRuleContext(createRuleContext("//foo:foo"))

        val mainRepoMapping1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent1",
                    RepositoryName.createUnvalidated("canonical+")
                ),
                RepositoryName.MAIN
            )
        val mainRepoMapping2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent2",
                    RepositoryName.createUnvalidated("canonical+")
                ),
                RepositoryName.MAIN
            )
        val args: String =
            """
        args = ruleContext.actions.args()
        args.add_all([Label("@@canonical+//foo:bar"), Label("@@canonical+//foo:baz")])
        
        """.trimIndent()
        val commandLine1: CommandLine = getCommandLine(mainRepoMapping1, args)
        val commandLine2: CommandLine = getCommandLine(mainRepoMapping2, args)

        Truth.assertThat(com.google.common.collect.ImmutableList.copyOf(commandLine1.arguments()))
            .isNotEqualTo(com.google.common.collect.ImmutableList.copyOf(commandLine2.arguments()))
        Truth.assertThat(getDigest(commandLine1)).isNotEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_listOfLabels_repoMappingChanges_notRelevant() {
        setRuleContext(createRuleContext("//foo:foo"))

        val mainRepoMapping1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent",
                    RepositoryName.createUnvalidated("canonical+"),
                    "other_repo1",
                    RepositoryName.createUnvalidated("other_repo+")
                ),
                RepositoryName.MAIN
            )
        val mainRepoMapping2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent",
                    RepositoryName.createUnvalidated("canonical+"),
                    "other_repo2",
                    RepositoryName.createUnvalidated("other_repo+")
                ),
                RepositoryName.MAIN
            )
        val args: String =
            """
        args = ruleContext.actions.args()
        args.add_all([Label("@@canonical+//foo:bar"), Label("@@canonical+//foo:baz")])
        
        """.trimIndent()
        val commandLine1: CommandLine = getCommandLine(mainRepoMapping1, args)
        val commandLine2: CommandLine = getCommandLine(mainRepoMapping2, args)

        assertThat(commandLine1.arguments())
            .containsExactlyElementsIn(commandLine2.arguments())
            .inOrder()
        Truth.assertThat(getDigest(commandLine1)).isEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_nestedSetOfLabels_repoMappingChanges_relevant() {
        setRuleContext(createRuleContext("//foo:foo"))

        val mainRepoMapping1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent1",
                    RepositoryName.createUnvalidated("canonical+")
                ),
                RepositoryName.MAIN
            )
        val mainRepoMapping2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent2",
                    RepositoryName.createUnvalidated("canonical+")
                ),
                RepositoryName.MAIN
            )
        val args: String =
            """
        args = ruleContext.actions.args()
        args.add_all(depset([Label("@@canonical+//foo:bar"), Label("@@canonical+//foo:baz")]))
        
        """.trimIndent()
        val commandLine1: CommandLine = getCommandLine(mainRepoMapping1, args)
        val commandLine2: CommandLine = getCommandLine(mainRepoMapping2, args)

        Truth.assertThat(com.google.common.collect.ImmutableList.copyOf(commandLine1.arguments()))
            .isNotEqualTo(com.google.common.collect.ImmutableList.copyOf(commandLine2.arguments()))
        Truth.assertThat(getDigest(commandLine1)).isNotEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_labelVsString() {
        setRuleContext(createRuleContext("//foo:foo"))

        val mainRepoMapping: RepositoryMapping? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent",
                    RepositoryName.createUnvalidated("canonical+")
                ),
                RepositoryName.MAIN
            )
        val commandLine1: CommandLine =
            getCommandLine(
                mainRepoMapping,
                """
            args = ruleContext.actions.args()
            args.add(Label("@@canonical+//foo:bar"))
            args.add(str(Label("@@canonical+//foo:bar")))
            
            """.trimIndent()
            )
        val commandLine2: CommandLine =
            getCommandLine(
                mainRepoMapping,
                """
            args = ruleContext.actions.args()
            args.add(Label("@@canonical+//foo:bar"))
            args.add(Label("@@canonical+//foo:bar"))
            
            """.trimIndent()
            )

        Truth.assertThat(getArguments(commandLine1, PathMapper.NOOP))
            .isNotEqualTo(getArguments(commandLine2, PathMapper.NOOP))
        Truth.assertThat(getDigest(commandLine1)).isNotEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_labelDepsetVsMixedList() {
        setRuleContext(createRuleContext("//foo:foo"))

        // Verify that a depset with elements of type Label and a list that starts with a Label and has
        // elements with identical string representation to those of the depset have different keys.
        val mainRepoMapping: RepositoryMapping? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "apparent1",
                    RepositoryName.createUnvalidated("canonical1+"),
                    "apparent2",
                    RepositoryName.createUnvalidated("canonical2+")
                ),
                RepositoryName.MAIN
            )
        val commandLine1: CommandLine =
            getCommandLine(
                mainRepoMapping,
                """
args = ruleContext.actions.args()
args.add_all(depset([Label("@@canonical1+//foo:bar"), Label("@@canonical2+//foo:bar")]))

""".trimIndent()
            )
        val commandLine2: CommandLine =
            getCommandLine(
                mainRepoMapping,
                """
            args = ruleContext.actions.args()
            args.add_all([Label("@@canonical1+//foo:bar"), str(Label("@@canonical2+//foo:bar"))])
            
            """.trimIndent()
            )

        Truth.assertThat(getArguments(commandLine1, PathMapper.NOOP))
            .isNotEqualTo(getArguments(commandLine2, PathMapper.NOOP))
        Truth.assertThat(getDigest(commandLine1)).isNotEqualTo(getDigest(commandLine2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_artifactVsPathStringInAdd() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                """
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add(file)
            
            """.trimIndent()
            )
        val commandLine2: CommandLine =
            getCommandLine(
                """
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add(file.path)
            
            """.trimIndent()
            )

        Truth.assertThat(getArguments(commandLine1, PathMapper.NOOP))
            .isEqualTo(getArguments(commandLine2, PathMapper.NOOP))
        Truth.assertThat(getArguments(commandLine1, NON_TRIVIAL_PATH_MAPPER))
            .isNotEqualTo(getArguments(commandLine2, NON_TRIVIAL_PATH_MAPPER))
        Truth.assertThat(getDigest(commandLine1, OutputPathsMode.STRIP))
            .isNotEqualTo(getDigest(commandLine2, OutputPathsMode.STRIP))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_artifactVsPathStringInAddFormatted() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                """
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add(file, format = '--%s')
            
            """.trimIndent()
            )
        val commandLine2: CommandLine =
            getCommandLine(
                """
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add(file.path, format = '--%s')
            
            """.trimIndent()
            )

        Truth.assertThat(getArguments(commandLine1, PathMapper.NOOP))
            .isEqualTo(getArguments(commandLine2, PathMapper.NOOP))
        Truth.assertThat(getArguments(commandLine1, NON_TRIVIAL_PATH_MAPPER))
            .isNotEqualTo(getArguments(commandLine2, NON_TRIVIAL_PATH_MAPPER))
        Truth.assertThat(getDigest(commandLine1, OutputPathsMode.STRIP))
            .isNotEqualTo(getDigest(commandLine2, OutputPathsMode.STRIP))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_artifactVsPathStringInAddAllList() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                """
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add_all([file])
            
            """.trimIndent()
            )
        val commandLine2: CommandLine =
            getCommandLine(
                """
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add_all([file.path])
            
            """.trimIndent()
            )

        Truth.assertThat(getArguments(commandLine1, PathMapper.NOOP))
            .isEqualTo(getArguments(commandLine2, PathMapper.NOOP))
        Truth.assertThat(getArguments(commandLine1, NON_TRIVIAL_PATH_MAPPER))
            .isNotEqualTo(getArguments(commandLine2, NON_TRIVIAL_PATH_MAPPER))
        Truth.assertThat(getDigest(commandLine1, OutputPathsMode.STRIP))
            .isNotEqualTo(getDigest(commandLine2, OutputPathsMode.STRIP))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_artifactVsPathStringInAddAllDepset() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                """
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add_all(depset([file]))
            
            """.trimIndent()
            )
        val commandLine2: CommandLine =
            getCommandLine(
                """
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add_all(depset([file.path]))
            
            """.trimIndent()
            )

        Truth.assertThat(getArguments(commandLine1, PathMapper.NOOP))
            .isEqualTo(getArguments(commandLine2, PathMapper.NOOP))
        Truth.assertThat(getArguments(commandLine1, NON_TRIVIAL_PATH_MAPPER))
            .isNotEqualTo(getArguments(commandLine2, NON_TRIVIAL_PATH_MAPPER))
        Truth.assertThat(getDigest(commandLine1, OutputPathsMode.STRIP))
            .isNotEqualTo(getDigest(commandLine2, OutputPathsMode.STRIP))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_artifactVsPathStringInAddAllDepsetMapEach() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                """
            def _map_each(x):
              if type(x.obj) == "File":
                return x.obj.path
              return str(x.obj)
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add_all(depset([struct(obj = file.path)]), map_each=_map_each)
            
            """.trimIndent()
            )
        val commandLine2: CommandLine =
            getCommandLine(
                """
            def _map_each(x):
              if type(x.obj) == "File":
                return x.obj.path
              return str(x.obj)
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add_all(depset([struct(obj = file)]), map_each=_map_each)
            
            """.trimIndent()
            )

        Truth.assertThat(getArguments(commandLine1, PathMapper.NOOP))
            .isEqualTo(getArguments(commandLine2, PathMapper.NOOP))
        Truth.assertThat(getDigest(commandLine1)).isEqualTo(getDigest(commandLine2))

        Truth.assertThat(getArguments(commandLine1, NON_TRIVIAL_PATH_MAPPER))
            .isNotEqualTo(getArguments(commandLine2, NON_TRIVIAL_PATH_MAPPER))
        Truth.assertThat(getDigest(commandLine1, OutputPathsMode.STRIP))
            .isNotEqualTo(getDigest(commandLine2, OutputPathsMode.STRIP))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_artifactVsPathStringInAddAllListMapEach() {
        setRuleContext(createRuleContext("//foo:foo"))

        val commandLine1: CommandLine =
            getCommandLine(
                """
            def _map_each(x):
              if type(x.obj) == "File":
                return x.obj.path
              return str(x.obj)
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add_all(depset([struct(obj = file.path)]), map_each=_map_each)
            
            """.trimIndent()
            )
        val commandLine2: CommandLine =
            getCommandLine(
                """
            def _map_each(x):
              if type(x.obj) == "File":
                return x.obj.path
              return str(x.obj)
            file = ruleContext.actions.declare_file('file')
            args = ruleContext.actions.args()
            args.add_all(depset([struct(obj = file)]), map_each=_map_each)
            
            """.trimIndent()
            )

        Truth.assertThat(getArguments(commandLine1, PathMapper.NOOP))
            .isEqualTo(getArguments(commandLine2, PathMapper.NOOP))
        Truth.assertThat(getDigest(commandLine1)).isEqualTo(getDigest(commandLine2))

        Truth.assertThat(getArguments(commandLine1, NON_TRIVIAL_PATH_MAPPER))
            .isNotEqualTo(getArguments(commandLine2, NON_TRIVIAL_PATH_MAPPER))
        Truth.assertThat(getDigest(commandLine1, OutputPathsMode.STRIP))
            .isNotEqualTo(getDigest(commandLine2, OutputPathsMode.STRIP))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkCustomCommandLineKeyComputation_artifactVsPathStringMapEachWithUniquify() {
        setRuleContext(createRuleContext("//foo:mixed_cfgs"))

        val commandLine1: CommandLine =
            getCommandLine(
                """
def _map_each(x):
  return x.field.root.path
args = ruleContext.actions.args()
d = depset([struct(field = f) for f in ruleContext.files.srcs + ruleContext.files.tools])
args.add_all(d, map_each = _map_each, uniquify = True)

""".trimIndent()
            )
        val commandLine2: CommandLine =
            getCommandLine(
                """
def _map_each(x):
  return x.field
args = ruleContext.actions.args()
d = depset([struct(field = f.root.path) for f in ruleContext.files.srcs + ruleContext.files.tools])
args.add_all(d, map_each = _map_each, uniquify = True)

""".trimIndent()
            )

        Truth.assertThat(getArguments(commandLine1, PathMapper.NOOP))
            .isEqualTo(getArguments(commandLine2, PathMapper.NOOP))
        Truth.assertThat(getDigest(commandLine1)).isEqualTo(getDigest(commandLine2))

        val arguments1 = getArguments(commandLine1, NON_TRIVIAL_PATH_MAPPER)
        val arguments2 = getArguments(commandLine2, NON_TRIVIAL_PATH_MAPPER)
        Truth.assertThat(arguments1).isNotEqualTo(arguments2)
        Truth.assertThat(arguments1.size).isNotEqualTo(arguments2.size)
        Truth.assertThat(getDigest(commandLine1, OutputPathsMode.STRIP))
            .isNotEqualTo(getDigest(commandLine2, OutputPathsMode.STRIP))
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    private fun getDigest(commandLine: CommandLine): String {
        return getDigest(commandLine,  /* inputMetadataProvider= */null, OutputPathsMode.OFF)
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    private fun getDigest(commandLine: CommandLine, inputMetadataProvider: InputMetadataProvider?): String {
        return getDigest(commandLine, inputMetadataProvider, OutputPathsMode.OFF)
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    private fun getDigest(commandLine: CommandLine, outputPathsMode: OutputPathsMode?): String {
        return getDigest(commandLine,  /* inputMetadataProvider= */null, outputPathsMode)
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    private fun getDigest(
        commandLine: CommandLine,
        inputMetadataProvider: InputMetadataProvider?,
        outputPathsMode: OutputPathsMode?
    ): String {
        val fingerprint: Fingerprint = Fingerprint()
        commandLine.addToFingerprint(
            actionKeyContext, inputMetadataProvider, outputPathsMode, fingerprint
        )
        return fingerprint.hexDigestAndReset()
    }

    @Throws(java.lang.Exception::class)
    private fun getCommandLine(vararg lines: String?): CommandLine {
        return getCommandLine(RepositoryMapping.EMPTY, lines)
    }

    @Throws(java.lang.Exception::class)
    private fun getCommandLine(mainRepoMapping: RepositoryMapping?, vararg lines: String?): CommandLine {
        ev.exec(*lines)
        return (ev.eval("args") as Args).build({ mainRepoMapping })
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    private fun getArguments(commandLine: CommandLine, pathMapper: PathMapper?): MutableList<String?> {
        return com.google.common.collect.ImmutableList.copyOf(
            commandLine.arguments( /* inputMetadataProvider= */null, pathMapper)
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrintArgs() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.exec("args = ruleContext.actions.args()", "args.add_all(['--foo', '--bar'])")
        val args: Args = ev.eval("args") as Args
        Truth.assertThat(
            net.starlark.java.eval.Printer()
                .debugPrint(
                    args,
                    StarlarkThread.createTransient(
                        Mutability.create("test"), starlarkSemantics
                    )
                )
                .toString()
        )
            .isEqualTo("--foo --bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryInArgs() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.exec(
            "args = ruleContext.actions.args()",
            "directory = ruleContext.actions.declare_directory('dir')",
            "def _short_path(f): return f.short_path",  // For easier assertions
            "args.add_all([directory], map_each=_short_path)"
        )
        val result: net.starlark.java.eval.Sequence<*> =
            ev.eval("args, directory") as net.starlark.java.eval.Sequence<*>
        val args: Args = result.get(0) as Args
        val directory: Artifact = result.get(1) as Artifact
        val commandLine: CommandLine = args.build({ RepositoryMapping.EMPTY })

        // When asking for arguments without an artifact expander we just return the directory
        assertThat(commandLine.arguments()).containsExactly("foo/dir")

        // Now ask for one with an expanded directory
        val inputMetadataProvider: InputMetadataProvider =
            createInputMetadataProvider(directory.getRootRelativePathString(), "file1", "file2")
        assertThat(commandLine.arguments(inputMetadataProvider, PathMapper.NOOP))
            .containsExactly("foo/dir/file1", "foo/dir/file2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryInArgsExpandDirectories() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.exec(
            "args = ruleContext.actions.args()",
            "directory = ruleContext.actions.declare_directory('dir')",
            "def _short_path(f): return f.short_path",  // For easier assertions
            "args.add_all([directory], map_each=_short_path, expand_directories=True)",
            "args.add_all([directory], map_each=_short_path, expand_directories=False)"
        )
        val result: net.starlark.java.eval.Sequence<*> =
            ev.eval("args, directory") as net.starlark.java.eval.Sequence<*>
        val args: Args = result.get(0) as Args
        val directory: Artifact = result.get(1) as Artifact
        val commandLine: CommandLine = args.build({ RepositoryMapping.EMPTY })

        val inputMetadataProvider: InputMetadataProvider =
            createInputMetadataProvider(directory.getRootRelativePathString(), "file1", "file2")
        // First expanded, then not expanded (two separate calls)
        assertThat(commandLine.arguments(inputMetadataProvider, PathMapper.NOOP))
            .containsExactly("foo/dir/file1", "foo/dir/file2", "foo/dir")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryInScalarArgsFails() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains(
            "Cannot add directories to Args#add",
            "args = ruleContext.actions.args()",
            "directory = ruleContext.actions.declare_directory('dir')",
            "args.add(directory)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParamFileHasDirectoryAsInput() {
        val ctx: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ctx)
        ev.exec(
            "args = ruleContext.actions.args()",
            "directory = ruleContext.actions.declare_directory('dir')",
            "args.add_all([directory])",
            "params = ruleContext.actions.declare_file('params')",
            "ruleContext.actions.write(params, args)"
        )
        val result: net.starlark.java.eval.Sequence<*> =
            ev.eval("params, directory") as net.starlark.java.eval.Sequence<*>
        val params: Artifact? = result.get(0) as Artifact?
        val directory: Artifact? = result.get(1) as Artifact?
        val action: ActionAnalysisMetadata =
            ctx.getRuleContext().getAnalysisEnvironment().getLocalGeneratingAction(params)
        com.google.common.truth.Subject.contains(directory)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectoryExpansionInArgs() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.exec(
            "args = ruleContext.actions.args()",
            "directory = ruleContext.actions.declare_directory('dir')",
            "file3 = ruleContext.actions.declare_file('file3')",
            "def _expand_dirs(artifact, dir_expander):",
            "  return [f.short_path for f in dir_expander.expand(artifact)]",
            "args.add_all([directory, file3], map_each=_expand_dirs)"
        )
        val args: Args = ev.eval("args") as Args
        val directory: Artifact = ev.eval("directory") as Artifact
        val commandLine: CommandLine = args.build({ RepositoryMapping.EMPTY })

        val inputMetadataProvider: InputMetadataProvider =
            createInputMetadataProvider(directory.getRootRelativePathString(), "file1", "file2")
        assertThat(commandLine.arguments(inputMetadataProvider, PathMapper.NOOP))
            .containsExactly("foo/dir/file1", "foo/dir/file2", "foo/file3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCallDirectoryExpanderWithWrongType() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.exec(
            "args = ruleContext.actions.args()",
            "f = ruleContext.actions.declare_file('file')",
            "def _expand_dirs(artifact, dir_expander):",
            "  return dir_expander.expand('oh no a string')",
            "args.add_all([f], map_each=_expand_dirs)"
        )
        val args: Args = ev.eval("args") as Args
        val commandLine: CommandLine = args.build({ RepositoryMapping.EMPTY })
        org.junit.Assert.assertThrows<T?>(CommandLineExpansionException::class.java, commandLine::arguments)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclareSharedArtifactIsPrivateAPI() {
        scratch.file(
            "abc/rule.bzl",
            """
        def _impl(ctx):
         ctx.actions.declare_shareable_artifact('foo')
         return []

        r = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "abc/BUILD",
            """
        load(':rule.bzl', 'r')

        r(name = 'foo')
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//abc:foo") })

        Truth.assertThat(error).hasMessageThat().contains("file '//abc:rule.bzl' cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisablingRunfilesSymlinkChecksIsPrivateAPI() {
        scratch.file(
            "abc/rule.bzl",
            """
        def _impl(ctx):
         ctx.runfiles(skip_conflict_checking = True)
         return []

        r = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "abc/BUILD",
            """
        load(':rule.bzl', 'r')

        r(name = 'foo')
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//abc:foo") })

        Truth.assertThat(error).hasMessageThat().contains("file '//abc:rule.bzl' cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclareSharedArtifact_differentFileRoot() {
        scratch.file(
            "test/rule.bzl",
            """
        RootProvider = provider(fields = ['root'])
        def _impl(ctx):
          if not ctx.attr.dep:
              return [RootProvider(root = ctx.configuration.bin_dir)]  # This is the child.
          exec_config_root = ctx.attr.dep[RootProvider].root
          a1 = ctx.actions.declare_shareable_artifact(ctx.label.name + '1.so')
          ctx.actions.write(a1, '')
          a2 = ctx.actions.declare_shareable_artifact(
                   ctx.label.name + '2.so',
                   exec_config_root
               )
          ctx.actions.write(a2, '')
          return [DefaultInfo(files = depset([a1, a2]))]

        r = rule(
            implementation = _impl,
            attrs = {'dep': attr.label(cfg = 'exec')},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':rule.bzl', 'r')
        r(name = 'foo', dep = ':exec_configured_child')
        r(name = 'exec_configured_child')
        
        """.trimIndent()
        )

        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )

        val target: ConfiguredTarget = getConfiguredTarget("//test:foo")

        assertThat(target).isNotNull()
        val a1: Artifact =
            getFilesToBuild(target).toSet().stream()
                .filter(artifactNamed("foo1.so"))
                .findFirst()
                .orElse(null)
        assertThat(a1).isNotNull()
        assertThat(a1.getRoot().getExecPathString())
            .isEqualTo(relativeOutputPath + "/k8-fastbuild/bin")
        val a2: Artifact =
            getFilesToBuild(target).toSet().stream()
                .filter(artifactNamed("foo2.so"))
                .findFirst()
                .orElse(null)
        assertThat(a2).isNotNull()
        assertThat(a2.getRoot().getExecPathString())
            .matches(relativeOutputPath + "/[\\w\\-]+\\-exec/bin")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHashableProviders() {
        ev.execAndExport("p = provider()")
        val dict: Dict<*, *> = ev.eval("{k: None for k in [DefaultInfo, p, DefaultInfo, p]}") as Dict<*, *>
        Truth.assertThat(dict.size).isEqualTo(2)
    }

    companion object {
        private fun assertArtifactFilenames(artifacts: Iterable<Artifact>, vararg expected: String?) {
            val filenames: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (a in artifacts) {
                filenames.add(a.getFilename())
            }
            Truth.assertThat(filenames.build())
                .containsAtLeastElementsIn(com.google.common.collect.Lists.newArrayList<String?>(*expected))
        }

        private fun assertMatches(
            description: String, expectedPattern: String?, computedValue: String?
        ) {
            Truth.assertWithMessage(
                "%s '%s' did not match pattern '%s'", description, computedValue, expectedPattern
            )
                .that(java.util.regex.Pattern.matches(expectedPattern, computedValue))
                .isTrue()
        }

        private fun getRunfileArtifacts(runfiles: Any): Iterable<Artifact?> {
            return (runfiles as Runfiles).getAllArtifacts().toList()
        }

        private fun createInputMetadataProvider(
            dirRelativePath: String?, vararg files: String?
        ): InputMetadataProvider {
            val result: InputMetadataProvider = Mockito.mock<InputMetadataProvider>(InputMetadataProvider::class.java)
            Mockito.`when`<T?>(result.getTreeMetadata(ArgumentMatchers.any<T?>()))
                .thenAnswer(
                    Answer { invocation: InvocationOnMock? ->
                        val arg: SpecialArtifact = invocation.getArgument<SpecialArtifact>(0)
                        check(arg.getRootRelativePathString().equals(dirRelativePath))

                        if (!arg.hasGeneratingActionKey()) {
                            arg.setGeneratingActionKey(ActionLookupData.create(arg.getArtifactOwner(), 0))
                        }

                        val builder: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(arg)
                        for (file in files) {
                            builder.putChild(
                                TreeFileArtifact.createTreeOutput(arg, PathFragment.create(file)),
                                FileArtifactValue.MISSING_FILE_MARKER
                            )
                        }
                        builder.build()
                    })

            return result
        }

        private val NON_TRIVIAL_PATH_MAPPER: PathMapper =
            PathMapper { path -> path.subFragment(0, 1).getChild("cfg").getRelative(path.subFragment(2)) }
    }
}
