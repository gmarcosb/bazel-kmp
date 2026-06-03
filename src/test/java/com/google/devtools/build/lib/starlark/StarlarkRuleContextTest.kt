// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for [StarlarkRuleContext].  */
@RunWith(TestParameterInjector::class)
class StarlarkRuleContextTest : BuildViewTestCase() {
    @Before
    @Throws(IOException::class)
    fun defineStarlarkProviders() {
        scratch.file(
            "test/providers.bzl",  //
            "AInfo = provider()",
            "BInfo = provider()",
            "CInfo = provider()"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun createRuleContext(label: String?): StarlarkRuleContext {
        return StarlarkRuleContext(getRuleContextForStarlark(getConfiguredTarget(label)), null)
    }

    private val ev: BazelEvaluationTestCase = BazelEvaluationTestCase()

    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder()
                .addRuleDefinition(TESTING_RULE_FOR_MANDATORY_PROVIDERS)
                .addRuleDefinition(FAKE_CC_LIBRARY)
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setupMyInfoAndGenerateBuildFile() {
        scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()")
        scratch.file("myinfo/BUILD")
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_java//java:defs.bzl", "java_library", "java_import")
        package(features = ['-f1', 'f2', 'f3'])
        genrule(name = 'foo',
          cmd = 'dummy_cmd',
          srcs = ['a.txt', 'b.img'],
          tools = ['t.exe'],
          outs = ['c.txt'])
        genrule(name = 'foo2',
          cmd = 'dummy_cmd',
          outs = ['e.txt'])
        genrule(name = 'bar',
          cmd = 'dummy_cmd',
          srcs = [':jl', ':gl'],
          outs = ['d.txt'])
        java_library(name = 'jl',
          srcs = ['a.java'])
        java_import(name = 'asr',
          jars = [ 'asr.jar' ],
          srcjar = 'asr-src.jar',
        )
        genrule(name = 'gl',
          cmd = 'touch ${'$'}(OUTS)',
          srcs = ['a.go'],
          outs = [ 'gl.a', 'gl.gcgox', ],
          output_to_bindir = 1,
        )
        cc_library(name = 'cc_with_features',
                   srcs = ['dummy.cc'],
                   features = ['f1', '-f3'],
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun setRuleContext(ctx: StarlarkRuleContext?) {
        ev.update("ruleContext", ctx)
    }

    @Throws(java.lang.Exception::class)
    private fun setUpAttributeErrorTest() {
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        load('//test:macros.bzl', 'macro_native_rule', 'macro_starlark_rule', 'starlark_rule')
        macro_native_rule(name = 'm_native',
          deps = [':jlib'])
        macro_starlark_rule(name = 'm_starlark',
          deps = [':jlib'])
        java_library(name = 'jlib',
          srcs = ['bla.java'])
        fake_cc_library(name = 'cclib',
          deps = [':jlib'])
        starlark_rule(name = 'skyrule',
          deps = [':jlib'])
        
        """.trimIndent()
        )
        scratch.file(
            "test/macros.bzl",
            """
        SomeInfo = provider()
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'deps': attr.label_list(providers = [SomeInfo], allow_files=True)
          }
        )
        def macro_native_rule(name, deps):
          native.fake_cc_library(name = name, deps = deps)
        def macro_starlark_rule(name, deps):
          starlark_rule(name = name, deps = deps)
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hasCorrectLocationForRuleAttributeError_NativeRuleWithMacro() {
        setUpAttributeErrorTest()
        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { createRuleContext("//test:m_native") })
        assertContainsEvent("misplaced here")
        // Skip the part of the error message that has details about the allowed deps since the mocks
        // for the mac tests might have different values for them.
        assertContainsEvent(
            (". Since this "
                    + "rule was created by the macro 'macro_native_rule', the error might have been caused "
                    + "by the macro implementation")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hasCorrectLocationForRuleAttributeError_StarlarkRuleWithMacro() {
        setUpAttributeErrorTest()
        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { createRuleContext("//test:m_starlark") })
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:5:20: in deps attribute of starlark_rule rule "
                    + "//test:m_starlark: '//test:jlib' does not have mandatory providers:"
                    + " 'SomeInfo'. "
                    + "Since this rule was created by the macro 'macro_starlark_rule', the error might "
                    + "have been caused by the macro implementation")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hasCorrectLocationForRuleAttributeError_NativeRule() {
        setUpAttributeErrorTest()
        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { createRuleContext("//test:cclib") })
        assertContainsEvent("misplaced here")
        // Skip the part of the error message that has details about the allowed deps since the mocks
        // for the mac tests might have different values for them.
        assertDoesNotContainEvent("Since this rule was created by the macro")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hasCorrectLocationForRuleAttributeError_StarlarkRule() {
        setUpAttributeErrorTest()
        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { createRuleContext("//test:skyrule") })
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:11:14: in deps attribute of "
                    + "starlark_rule rule //test:skyrule: '//test:jlib' does not have mandatory providers: "
                    + "'SomeInfo'")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMandatoryProvidersListWithStarlark() {
        scratch.file(
            "test/BUILD",
            """
        load('//test:rules.bzl', 'starlark_rule', 'my_rule', 'my_other_rule')
        my_rule(name = 'mylib',
          srcs = ['a.py'])
        starlark_rule(name = 'skyrule1',
          deps = [':mylib'])
        my_other_rule(name = 'my_other_lib',
          srcs = ['a.py'])
        starlark_rule(name = 'skyrule2',
          deps = [':my_other_lib'])
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:providers.bzl", "AInfo", "BInfo", "CInfo")
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'deps': attr.label_list(providers = [[AInfo], [BInfo, CInfo]],
            allow_files=True)
          }
        )
        def my_rule_impl(ctx):
          return AInfo()
        my_rule = rule(implementation = my_rule_impl,
          attrs = { 'srcs' : attr.label_list(allow_files=True)})
        def my_other_rule_impl(ctx):
          return BInfo()
        my_other_rule = rule(implementation = my_other_rule_impl,
          attrs = { 'srcs' : attr.label_list(allow_files=True)})
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        assertThat(getConfiguredTarget("//test:skyrule1")).isNotNull()

        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { createRuleContext("//test:skyrule2") })
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:8:14: in deps attribute of "
                    + "starlark_rule rule //test:skyrule2: '//test:my_other_lib' does not have "
                    + "mandatory providers: 'AInfo' or 'CInfo'")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMandatoryProvidersListWithNative() {
        scratch.file(
            "test/BUILD",
            """
        load('//test:rules.bzl', 'my_rule', 'my_other_rule')
        my_rule(name = 'mylib',
          srcs = ['a.py'])
        testing_rule_for_mandatory_providers(name = 'skyrule1',
          deps = [':mylib'])
        my_other_rule(name = 'my_other_lib',
          srcs = ['a.py'])
        testing_rule_for_mandatory_providers(name = 'skyrule2',
          deps = [':my_other_lib'])
        
        """.trimIndent()
        )
        scratch.file(
            "test/rules.bzl",
            """
        load("//test:providers.bzl", "AInfo", "BInfo")
        def my_rule_impl(ctx):
          return AInfo()
        my_rule = rule(implementation = my_rule_impl,
          attrs = { 'srcs' : attr.label_list(allow_files=True)})
        def my_other_rule_impl(ctx):
          return BInfo()
        my_other_rule = rule(implementation = my_other_rule_impl,
          attrs = { 'srcs' : attr.label_list(allow_files=True)})
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        assertThat(getConfiguredTarget("//test:skyrule1")).isNotNull()

        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { createRuleContext("//test:skyrule2") })
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:8:37: in deps attribute of "
                    + "testing_rule_for_mandatory_providers rule //test:skyrule2: '//test:my_other_lib' "
                    + "does not have mandatory providers: 'AInfo' or 'CInfo'")
        )
    }

    /* Sharing setup code between the testPackageBoundaryError*() methods is not possible since the
   * errors already happen when loading the file. Consequently, all tests would fail at the same
   * statement. */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageBoundaryError_nativeRule() {
        scratch.file(
            "test/BUILD",
            """
        fake_cc_library(name = 'cclib',
          srcs = ['sub/my_sub_lib.h'])
        
        """.trimIndent()
        )
        scratch.file("test/sub/BUILD", "fake_cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])")
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:cclib")
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:1:16: Label '//test:sub/my_sub_lib.h' is invalid because "
                    + "'test/sub' is a subpackage; perhaps you meant to put the colon here: "
                    + "'//test/sub:my_sub_lib.h'?")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageBoundaryError_starlarkRule() {
        scratch.file(
            "test/BUILD",
            """
        load('//test:macros.bzl', 'starlark_rule')
        starlark_rule(name = 'skyrule',
          srcs = ['sub/my_sub_lib.h'])
        
        """.trimIndent()
        )
        scratch.file("test/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])")
        scratch.file(
            "test/macros.bzl",
            """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'srcs': attr.label_list(allow_files=True)
          }
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:skyrule")
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:2:14: Label '//test:sub/my_sub_lib.h' is invalid because "
                    + "'test/sub' is a subpackage; perhaps you meant to put the colon here: "
                    + "'//test/sub:my_sub_lib.h'?")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageBoundaryError_starlarkMacro() {
        scratch.file(
            "test/BUILD",
            """
        load('//test:macros.bzl', 'macro_starlark_rule')
        macro_starlark_rule(name = 'm_starlark',
          srcs = ['sub/my_sub_lib.h'])
        
        """.trimIndent()
        )
        scratch.file("test/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])")
        scratch.file(
            "test/macros.bzl",
            """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'srcs': attr.label_list(allow_files=True)
          }
        )
        def macro_starlark_rule(name, srcs=[]):
          starlark_rule(name = name, srcs = srcs)
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:m_starlark")
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:2:20: Label '//test:sub/my_sub_lib.h' is invalid because"
                    + " 'test/sub' is a subpackage; perhaps you meant to put the colon here: "
                    + "'//test/sub:my_sub_lib.h'?")
        )
    }

    /* The error message for this case used to be wrong. */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageBoundaryError_externalRepository_entirelyInside() {
        scratch.file("/r/MODULE.bazel", "module(name = 'r')")
        scratch.file(
            "/r/BUILD",
            """
        fake_cc_library(name = 'cclib',
          srcs = ['sub/my_sub_lib.h'])
        
        """.trimIndent()
        )
        scratch.file("/r/sub/BUILD", "fake_cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])")
        scratch.overwriteFile(
            "MODULE.bazel", "bazel_dep(name = 'r')", "local_path_override(module_name='r', path='/r')"
        )
        invalidatePackages( /* alsoConfigs= */
            false
        ) // Repository shuffling messes with toolchain labels.
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("@@r+//:cclib")
        assertContainsEvent(
            ("/external/r+/BUILD:1:16: Label '@@r+//:sub/my_sub_lib.h' is invalid because "
                    + "'@@r+//sub' is a subpackage; perhaps you meant to put the colon here: "
                    + "'@@r+//sub:my_sub_lib.h'?")
        )
    }

    /*
   * Making the location in BUILD file the default for "crosses boundary of subpackage" errors does
   * not work in this case since the error actually happens in the bzl file. However, because of
   * the current design, we can neither show the location in the bzl file nor display both
   * locations (BUILD + bzl).
   *
   * Since this case is less common than having such an error in a BUILD file, we can live
   * with it.
   */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageBoundaryError_starlarkMacroWithErrorInBzlFile() {
        scratch.file(
            "test/BUILD",
            """
        load('//test:macros.bzl', 'macro_starlark_rule')
        macro_starlark_rule(name = 'm_starlark')
        
        """.trimIndent()
        )
        scratch.file("test/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])")
        scratch.file(
            "test/macros.bzl",
            """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'srcs': attr.label_list(allow_files=True)
          }
        )
        def macro_starlark_rule(name, srcs=[]):
          starlark_rule(name = name, srcs = srcs + ['sub/my_sub_lib.h'])
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:m_starlark")
        assertContainsEvent(
            "ERROR /workspace/test/BUILD:2:20: Label '//test:sub/my_sub_lib.h' "
                    + "is invalid because 'test/sub' is a subpackage"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageBoundaryError_nativeMacro() {
        scratch.file(
            "test/BUILD",
            """
        load('//test:macros.bzl', 'macro_native_rule')
        macro_native_rule(name = 'm_native',
          srcs = ['sub/my_sub_lib.h'])
        
        """.trimIndent()
        )
        scratch.file("test/sub/BUILD", "cc_library(name = 'my_sub_lib', srcs = ['my_sub_lib.h'])")
        scratch.file(
            "test/macros.bzl",
            """
        def macro_native_rule(name, deps=[], srcs=[]):
          native.fake_cc_library(name = name, deps = deps, srcs = srcs)
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:m_native")
        assertContainsEvent(
            "ERROR /workspace/test/BUILD:2:18: Label '//test:sub/my_sub_lib.h' "
                    + "is invalid because 'test/sub' is a subpackage"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldGetPrerequisiteArtifacts() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.files.srcs")
        assertArtifactList(result, com.google.common.collect.ImmutableList.of<String?>("a.txt", "b.img"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldGetPrerequisites() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:bar")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.attr.srcs")
        // Check for a known provider
        val tic1: TransitiveInfoCollection =
            (result as net.starlark.java.eval.Sequence<*>).get(0) as TransitiveInfoCollection
        assertThat(JavaInfo.getProvider(JavaSourceJarsProvider::class.java, tic1)).isNotNull()
        // Check an unimplemented provider too
        assertThat(tic1.get("not_implemented_provider")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun shouldGetPrerequisite() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:asr")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.attr.srcjar")
        val tic: TransitiveInfoCollection = result as TransitiveInfoCollection
        assertThat(tic).isInstanceOf(FileConfiguredTarget::class.java)
        assertThat(tic.label.getName()).isEqualTo("asr-src.jar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRuleAttributeListType() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.attr.outs")
        Truth.assertThat(result).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRuleAttributeListValue() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.attr.outs")
        Truth.assertThat((result as net.starlark.java.eval.Sequence<*>?)).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRuleAttributeListValueNoGet() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.attr.outs")
        Truth.assertThat((result as net.starlark.java.eval.Sequence<*>?)).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRuleAttributeStringTypeValue() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.attr.cmd")
        Truth.assertThat(result as String?).isEqualTo("dummy_cmd")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRuleAttributeStringTypeValueNoGet() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.attr.cmd")
        Truth.assertThat(result as String?).isEqualTo("dummy_cmd")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRuleAttributeBadAttributeName() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains("No attribute 'bad'", "ruleContext.attr.bad")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetRuleAttributeNoAspectHints() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains("No attribute 'aspect_hints'", "ruleContext.attr.aspect_hints")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetLabel() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.label")
        assertThat((result as Label).toString()).isEqualTo("//foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleError() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains("message", "fail('message')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttributeError() {
        setRuleContext(createRuleContext("//foo:foo"))
        ev.checkEvalErrorContains("attribute srcs: message", "fail(attr='srcs', msg='message')")
    }

    @Throws(java.lang.Exception::class)
    private fun writeToolUsingRule() {
        // Write a rule with an executable implicit attribute.
        scratch.file(
            "a/rule.bzl",
            """
        def _impl(ctx):
            pass
        sample = rule(
            implementation = _impl,
            attrs = {
                '_tool': attr.label(
                    cfg = 'exec',
                    executable = True,
                    default = '//a:tool'),
            }
        )
        
        """.trimIndent()
        )

        // Use the rule.
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load('rule.bzl', 'sample')
        cc_binary(name = 'tool')
        sample(name = 'sample')
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetExecutablePrerequisite() {
        writeToolUsingRule()

        setRuleContext(createRuleContext("//a:sample"))
        val result: Any = ev.eval("ruleContext.executable._tool")
        assertThat((result as Artifact).getFilename()).matches("^tool(\\.exe)?$")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateSpawnActionArgumentsWithExecutableFilesToRunProvider() {
        writeToolUsingRule()

        val ruleContext: StarlarkRuleContext = createRuleContext("//a:sample")
        setRuleContext(ruleContext)
        ev.exec(
            "output = ruleContext.actions.declare_file(ruleContext.attr.name + '_out')",
            "ruleContext.actions.run(",
            "  outputs = [output],",
            "  arguments = ['--a','--b'],",
            "  executable = ruleContext.executable._tool)"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?
        assertThat(action.getCommandFilename()).matches("^.*/tool(\\.exe)?$")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetExecutablePrerequisite_forNativeRuleWithLabelList() {
        // Starlark rules only support executable=True on LABEL attributes, but native rules support it
        // for LABEL_LIST as well. This became a problem when we started creating StarlarkRuleContexts
        // for native rules for builtins injection. We work around it by not populating the executable
        // field for these rules.
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        extra_action(
            name = 'foo',
            cmd = 'cmd',
            out_templates = ['foo.out'],
            tools = [':tool1', ':tool2']  # not allowed in Starlark-defined rules
        )
        cc_binary(
            name = 'tool1',
            srcs = ['tool1.cc'],
        )
        cc_binary(
            name = 'tool2',
            srcs = ['tool2.cc'],
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//pkg:foo")
        setRuleContext(ruleContext)
        Truth.assertThat(ev.eval("hasattr(ruleContext.executable, 'tools')") as Boolean?).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithUnusedInputsList() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  executable = 'executable',",
            "  unused_inputs_list = ruleContext.files.srcs[0])"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?
        assertThat(action.getUnusedInputsList()).isPresent()
        assertThat(action.getUnusedInputsList().get().getFilename()).isEqualTo("a.txt")
        assertThat(action.discoversInputs()).isTrue()
        assertThat(action.isShareable()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithResourceSet_success() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)

        ev.exec(
            "def get_resources(os, inputs_size):",
            "  if os == \"osx\":",
            "    return {\"cpu\": 2., \"memory\": 350. + inputs_size * 20, \"local_test\": 2.}",
            "  return {\"cpu\": 1., \"memory\": 350. + inputs_size * 10, \"local_test\": 0.}",
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  resource_set = get_resources,",
            "  executable = 'executable')"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?

        assertThat(action.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 2))
            .isEqualTo(ResourceSet.create(370, 1, 0))
        assertThat(action.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.DARWIN, 2))
            .isEqualTo(ResourceSet.create(390, 2, 2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithResourceSet_noneResourceSet() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)

        ev.exec(
            "def get_resources(os, inputs_size):",
            "  if os == \"osx\":",
            "    return {\"cpu\": 2., \"memory\": 350. + inputs_size * 20, \"local_test\": 2.}",
            "  return {\"cpu\": 1., \"memory\": 350. + inputs_size * 10, \"local_test\": 0.}",
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  resource_set = None,",
            "  executable = 'executable')"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?

        assertThat(action.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 2))
            .isEqualTo(ResourceSet.create(250, 1, 0))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithResourceSet_lambdaForbidden() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)

        val thrown: java.lang.Exception? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    ev.exec(
                        "ruleContext.actions.run(",
                        "  inputs = ruleContext.files.srcs,",
                        "  outputs = ruleContext.files.srcs,",
                        "  resource_set = lambda os, inputs_size : {\"cpu\": 1., \"memory\": 1.,"
                                + " \"local_test\": 1.} ,",
                        "  executable = 'executable')"
                    )
                })

        Truth.assertThat(thrown).hasMessageThat().contains("must be declared by a top-level def statement")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithResourceSet_illegalResource() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)

        ev.exec(
            "def get_resources(os, inputs_size):",
            "  return {\"cpu\": 2., \"memory\": 350., \"local_test\": 2., \"gpu\": 1.}",
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  resource_set = get_resources,",
            "  executable = 'executable')"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?

        val thrown: java.lang.Exception? =
            org.junit.Assert.assertThrows<T?>(
                ExecException::class.java,
                org.junit.function.ThrowingRunnable {
                    action.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 2)
                })
        Truth.assertThat(thrown).hasMessageThat().contains("Illegal resource keys: (gpu)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithResourceSet_defaultValue() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)

        ev.exec(
            "def get_resources(os, inputs_size):",
            "  return {\"cpu\": 2., \"local_test\": 2.}",
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  resource_set = get_resources,",
            "  executable = 'executable')"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?

        assertThat(action.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 2))
            .isEqualTo(ResourceSet.create(250, 2, 2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithResourceSet_intDict() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)

        ev.exec(
            "def get_resources(os, inputs_size):",
            "  return {\"cpu\": 1, \"memory\": 2, \"local_test\": 3}",
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  resource_set = get_resources,",
            "  executable = 'executable')"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?

        assertThat(action.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 0))
            .isEqualTo(ResourceSet.create(2, 1, 3))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithResourceSet_notDict() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)

        ev.exec(
            "def get_resources(os, inputs_size):",
            "  return \"keks\"",
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  resource_set = get_resources,",
            "  executable = 'executable')"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?

        val thrown: java.lang.Exception? =
            org.junit.Assert.assertThrows<T?>(
                ExecException::class.java,
                org.junit.function.ThrowingRunnable {
                    action.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 2)
                })
        Truth.assertThat(thrown).hasMessageThat().contains("got string for 'resource_set', want dict")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithResourceSet_wrongDict() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)

        ev.exec(
            "def get_resources(os, inputs_size):",
            "  return {\"cpu\": 1, \"memory\": 2, \"local_test\": \"hi\"}",
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  resource_set = get_resources,",
            "  executable = 'executable')"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?

        val thrown: java.lang.Exception? =
            org.junit.Assert.assertThrows<T?>(
                ExecException::class.java,
                org.junit.function.ThrowingRunnable {
                    action.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 2)
                })
        Truth.assertThat(thrown).hasMessageThat().contains("Illegal resource value type for key local_test")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithResourceSet_incorrectSignature() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)

        ev.exec(
            "def get_resources(os):",
            "  return {\"cpu\": 1, \"memory\": 2, \"local_test\": \"hi\"}",
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  resource_set = get_resources,",
            "  executable = 'executable')"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?

        val thrown: java.lang.Exception? =
            org.junit.Assert.assertThrows<T?>(
                ExecException::class.java,
                org.junit.function.ThrowingRunnable {
                    action.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 2)
                })
        Truth.assertThat(thrown)
            .hasMessageThat()
            .contains("get_resources() accepts no more than 1 positional argument but got 2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStarlarkActionArgumentsWithoutUnusedInputsList() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        ev.exec(
            "ruleContext.actions.run(",
            "  inputs = ruleContext.files.srcs,",
            "  outputs = ruleContext.files.srcs,",
            "  executable = 'executable',",
            "  unused_inputs_list = None)"
        )
        val action: StarlarkAction? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                ruleContext.getRuleContext().getAnalysisEnvironment().getRegisteredActions()
            ) as StarlarkAction?
        assertThat(action.getUnusedInputsList()).isEmpty()
        assertThat(action.discoversInputs()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputs() {
        setRuleContext(createRuleContext("//foo:bar"))
        val result = ev.eval("ruleContext.outputs.outs") as Iterable<*>
        assertThat((com.google.common.collect.Iterables.getOnlyElement(result) as Artifact).getFilename()).isEqualTo("d.txt")
    }

    // Regression test for b/329066920
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputsAddOutput_keyCollision_failsCleanly() {
        scratch.file(
            "test/rules.bzl",
            """
        def _undertest_impl(ctx):
            pass

        undertest_rule = rule(
            implementation = _undertest_impl,
            attrs = {'out_collision': attr.output(),},
            outputs = {'out_collision': '%{name}.out'},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule')
        undertest_rule(
            name = 'undertest',
        )
        
        """.trimIndent()
        )

        checkError("//test:undertest", "Multiple outputs with the same key: out_collision")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkRuleContextGetDefaultShellEnv() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.configuration.default_shell_env")
        Truth.assertThat(result).isInstanceOf(Dict::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckPlaceholders() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.check_placeholders('%{name}', ['name'])")
        Truth.assertThat(result).isEqualTo(true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckPlaceholdersBadPlaceholder() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.check_placeholders('%{name}', ['abc'])")
        Truth.assertThat(result).isEqualTo(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandMakeVariables() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.expand_make_variables('cmd', '$(ABC)', {'ABC': 'DEF'})")
        Truth.assertThat(result).isEqualTo("DEF")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandMakeVariablesShell() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.expand_make_variables('cmd', '$\$ABC', {})")
        Truth.assertThat(result).isEqualTo("\$ABC")
    }

    @Throws(java.lang.Exception::class)
    private fun setUpMakeVarToolchain() {
        scratch.file(
            "vars/vars.bzl",
            """
        def _make_var_supplier_impl(ctx):
          val = ctx.attr.value
          return [platform_common.TemplateVariableInfo({'MAKE_VAR_VALUE': val})]
        make_var_supplier = rule(
            implementation = _make_var_supplier_impl,
            attrs = {
                'value': attr.string(mandatory = True),
            })
        def _make_var_user_impl(ctx):
          return []
        make_var_user = rule(
            implementation = _make_var_user_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "vars/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(':vars.bzl', 'make_var_supplier', 'make_var_user')
        make_var_supplier(name = 'supplier', value = 'foo')
        cc_toolchain_alias(name = 'current_cc_toolchain')
        make_var_user(
            name = 'vars',
            toolchains = [':supplier', ':current_cc_toolchain'],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandMakeVariables_cc() {
        setUpMakeVarToolchain()
        setRuleContext(createRuleContext("//vars:vars"))
        val result = ev.eval("ruleContext.expand_make_variables('cmd', '$(CC)', {})") as String
        Truth.assertThat(result).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandMakeVariables_toolchain() {
        setUpMakeVarToolchain()
        setRuleContext(createRuleContext("//vars:vars"))
        val result: Any = ev.eval("ruleContext.expand_make_variables('cmd', '$(MAKE_VAR_VALUE)', {})")
        Truth.assertThat(result).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVar_toolchain() {
        setUpMakeVarToolchain()
        setRuleContext(createRuleContext("//vars:vars"))
        val result: Any = ev.eval("ruleContext.var['MAKE_VAR_VALUE']")
        Truth.assertThat(result).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfiguration() {
        val ruleContext: StarlarkRuleContext = createRuleContext("//foo:foo")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.configuration")
        assertThat(ruleContext.getRuleContext().getConfiguration()).isSameInstanceAs(result)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatures() {
        setRuleContext(createRuleContext("//foo:cc_with_features"))
        val result: Any = ev.eval("ruleContext.features")
        Truth.assertThat(result as net.starlark.java.eval.Sequence<*>?).containsExactly("f1", "f2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisabledFeatures() {
        setRuleContext(createRuleContext("//foo:cc_with_features"))
        val result: Any = ev.eval("ruleContext.disabled_features")
        Truth.assertThat(result as net.starlark.java.eval.Sequence<*>?).containsExactly("f3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeriveArtifact() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.actions.declare_file('a/b.txt')")
        val fragment: PathFragment = (result as Artifact).getRootRelativePath()
        assertThat(fragment.getPathString()).isEqualTo("foo/a/b.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeriveTreeArtifact() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any = ev.eval("ruleContext.actions.declare_directory('a/b')")
        val artifact: Artifact = result as Artifact
        val fragment: PathFragment = artifact.getRootRelativePath()
        assertThat(fragment.getPathString()).isEqualTo("foo/a/b")
        assertThat(artifact.isTreeArtifact()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeriveTreeArtifactType() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result = ev.eval("type(ruleContext.actions.declare_directory('a/b'))") as String
        Truth.assertThat(result).isEqualTo("File")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeriveTreeArtifactNextToSibling() {
        setRuleContext(createRuleContext("//foo:foo"))
        val artifact: Artifact =
            ev.eval(
                "ruleContext.actions.declare_directory('c',"
                        + " sibling=ruleContext.actions.declare_directory('a/b'))"
            ) as Artifact
        val fragment: PathFragment = artifact.getRootRelativePath()
        assertThat(fragment.getPathString()).isEqualTo("foo/a/c")
        assertThat(artifact.isTreeArtifact()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParamFileSuffix() {
        setRuleContext(createRuleContext("//foo:foo"))
        val result: Any =
            ev.eval(
                "ruleContext.actions.declare_file(ruleContext.files.tools[0].basename + '.params', "
                        + "sibling = ruleContext.files.tools[0])"
            )
        val fragment: PathFragment = (result as Artifact).getRootRelativePath()
        assertThat(fragment.getPathString()).isEqualTo("foo/t.exe.params")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictConvertsToTargetToStringMap() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "filegroup(name='dep')",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_dict={':dep': 'value'})"
        )

        invalidatePackages()
        setRuleContext(createRuleContext("//:r"))
        val keyLabel: Label = ev.eval("ruleContext.attr.label_dict.keys()[0].label") as Label
        assertThat(keyLabel).isEqualTo(Label.parseCanonical("//:dep"))
        val valueString = ev.eval("ruleContext.attr.label_dict.values()[0]") as String
        Truth.assertThat(valueString).isEqualTo("value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictTranslatesAliases() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "filegroup(name='dep')",
            "alias(name='alias', actual='dep')",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_dict={':alias': 'value'})"
        )

        invalidatePackages()
        setRuleContext(createRuleContext("//:r"))
        val keyLabel: Label = ev.eval("ruleContext.attr.label_dict.keys()[0].label") as Label
        assertThat(keyLabel).isEqualTo(Label.parseCanonical("//:dep"))
        val valueString = ev.eval("ruleContext.attr.label_dict.values()[0]") as String
        Truth.assertThat(valueString).isEqualTo("value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictAcceptsDefaultValues() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(default={Label('//:default'): 'defs'}),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "filegroup(name='default')",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r')"
        )

        invalidatePackages()
        setRuleContext(createRuleContext("//:r"))
        val keyLabel: Label = ev.eval("ruleContext.attr.label_dict.keys()[0].label") as Label
        assertThat(keyLabel).isEqualTo(Label.parseCanonical("//:default"))
        val valueString = ev.eval("ruleContext.attr.label_dict.values()[0]") as String
        Truth.assertThat(valueString).isEqualTo("defs")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictAllowsFilesWhenAllowFilesIsTrue() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_files=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("myfile.cc")

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_dict={'myfile.cc': 'value'})"
        )

        invalidatePackages()
        createRuleContext("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictAllowsFilesOfAppropriateTypes() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_files=['.cc']),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("myfile.cc")

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_dict={'myfile.cc': 'value'})"
        )

        invalidatePackages()
        createRuleContext("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictForbidsFilesOfIncorrectTypes() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_files=['.cc']),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("myfile.cpp")

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_dict={'myfile.cpp': 'value'})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent("file '//:myfile.cpp' is misplaced here (expected .cc)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictForbidsFilesWhenAllowFilesIsFalse() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_files=False),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("myfile.cpp")

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_dict={'myfile.cpp': 'value'})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "in label_dict attribute of my_rule rule //:r: "
                    + "source file '//:myfile.cpp' is misplaced here (expected no files)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictAllowsRulesWithRequiredProviders() {
        scratch.file(
            "my_rule.bzl",
            """
        load('//myinfo:myinfo.bzl', 'MyInfo')
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(providers=[MyInfo]),
          }
        )
        def _dep_impl(ctx):
          return MyInfo(my_provider=5)
        my_dep_rule = rule(
          implementation = _dep_impl,
          attrs = {}
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule', 'my_dep_rule')",
            "my_dep_rule(name='dep')",
            "my_rule(name='r',",
            "        label_dict={':dep': 'value'})"
        )

        invalidatePackages()
        createRuleContext("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictForbidsRulesMissingRequiredProviders() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        MyInfo = provider()
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(providers=[[MyInfo]]),
          }
        )
        def _dep_impl(ctx):
          return
        my_dep_rule = rule(
          implementation = _dep_impl,
          attrs = {}
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule', 'my_dep_rule')",
            "my_dep_rule(name='dep')",
            "my_rule(name='r',",
            "        label_dict={':dep': 'value'})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "in label_dict attribute of my_rule rule //:r: "
                    + "'//:dep' does not have mandatory providers: 'MyInfo'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictForbidsEmptyDictWhenAllowEmptyIsFalse() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_empty=False),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_dict={})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "in label_dict attribute of my_rule rule //:r: " + "attribute must be non empty"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictAllowsEmptyDictWhenAllowEmptyIsTrue() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(allow_empty=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_dict={})"
        )

        invalidatePackages()
        createRuleContext("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListNoDuplicatesNoError() {
        reporter.removeHandler(failFastHandler)
        scratch.file("a.txt", "")
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list': attr.label_list(allow_files=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list=[\"a.txt\"])"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListNoDuplicatesNonOverlappingSelectsNoError() {
        reporter.removeHandler(failFastHandler)
        scratch.file("a.txt", "")
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list': attr.label_list(allow_files=True),
          }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            """
        load('//:my_rule.bzl', 'my_rule')
        constraint_setting(name = "cpu")
        constraint_value(name = "arm_cpu", constraint_setting = "cpu")
        my_rule(
          name="r",
          label_list = select({
            ":arm_cpu": [],
            "//conditions:default": ["a.txt"],
          }) + select({
              ":arm_cpu": ["a.txt"],
              "//conditions:default": [],
          }),
        )
        
        """.trimIndent()
        )
        invalidatePackages()
        getConfiguredTarget("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListNoDuplicatesOverlappingSelectsHasError() {
        reporter.removeHandler(failFastHandler)
        scratch.file("a.txt", "")
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list': attr.label_list(allow_files=True),
          }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            """
        load('//:my_rule.bzl', 'my_rule')
        constraint_setting(name = "cpu")
        constraint_value(name = "arm_cpu", constraint_setting = "cpu")
        my_rule(
          name="r",
          label_list = select({
            ":arm_cpu": [],
            "//conditions:default": ["a.txt"],
          }) + select({
              ":arm_cpu": ["a.txt"],
              "//conditions:default": ["a.txt"],
          }),
        )
        
        """.trimIndent()
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "in label_list attribute of my_rule rule //:r: " + "Label \'//:a.txt\' is duplicated"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictAllowsDuplicatesAcrossKeys() {
        reporter.removeHandler(failFastHandler)
        scratch.file("a.txt", "")
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_files=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={'key1': ['a.txt'], 'key2': ['a.txt']})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictForbidsDuplicatesWithinSingleKey() {
        reporter.removeHandler(failFastHandler)
        scratch.file("a.txt", "")
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_files=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={'key': ['a.txt', 'a.txt']})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "Label '//:a.txt' is duplicated in the 'label_list_dict' attribute of rule 'r'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictAllowsDuplicatesAcrossKeysNonOverlappingSelects() {
        reporter.removeHandler(failFastHandler)
        scratch.file("a.txt", "")
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_files=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            """
        load('//:my_rule.bzl', 'my_rule')
        constraint_setting(name = "cpu")
        constraint_value(name = "arm_cpu", constraint_setting = "cpu")
        my_rule(
          name="r",
          label_list_dict = select({
            ":arm_cpu": {'key1': [], 'key2': ["a.txt"]},
            "//conditions:default": {'key1': ["a.txt"], 'key2': []},
          }),
        )
        
        """.trimIndent()
        )
        invalidatePackages()
        getConfiguredTarget("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictForbidsDuplicatesWithinSingleKeyOverlappingSelects() {
        reporter.removeHandler(failFastHandler)
        scratch.file("a.txt", "")
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_files=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            """
        load('//:my_rule.bzl', 'my_rule')
        constraint_setting(name = "cpu")
        constraint_value(name = "arm_cpu", constraint_setting = "cpu")
        my_rule(
          name="r",
          label_list_dict = select({
            ":arm_cpu": {'key': ["a.txt"]},
            "//conditions:default": {'key': ["a.txt", "a.txt"]},
          }),
        )
        
        """.trimIndent()
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "Label '//:a.txt' is duplicated in the 'label_list_dict' attribute of rule 'r'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictForbidsMissingAttributeWhenMandatoryIsTrue() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(mandatory=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("BUILD", "load('//:my_rule.bzl', 'my_rule')", "my_rule(name='r')")

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent("missing value for mandatory attribute 'label_dict' in 'my_rule' rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictAllowsMissingAttributeWhenMandatoryIsFalse() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_dict': attr.label_keyed_string_dict(mandatory=False),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("BUILD", "load('//:my_rule.bzl', 'my_rule')", "my_rule(name='r')")

        invalidatePackages()
        createRuleContext("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictConvertsToStringToTargetListMap() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "filegroup(name='dep')",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={'key': [':dep']})"
        )

        invalidatePackages()
        setRuleContext(createRuleContext("//:r"))
        val keyString = ev.eval("ruleContext.attr.label_list_dict.keys()[0]") as String
        Truth.assertThat(keyString).isEqualTo("key")
        val valueLabel: Label = ev.eval("ruleContext.attr.label_list_dict.values()[0][0].label") as Label
        assertThat(valueLabel).isEqualTo(Label.parseCanonical("//:dep"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictTranslatesAliases() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "alias(name='myalias', actual=':dep')",
            "filegroup(name='dep')",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={'key': [':myalias']})"
        )

        invalidatePackages()
        setRuleContext(createRuleContext("//:r"))
        val keyString = ev.eval("ruleContext.attr.label_list_dict.keys()[0]") as String
        Truth.assertThat(keyString).isEqualTo("key")
        val valueLabel: Label = ev.eval("ruleContext.attr.label_list_dict.values()[0][0].label") as Label
        assertThat(valueLabel).isEqualTo(Label.parseCanonical("//:dep"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictAcceptsDefaultValues() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(default={'default': [Label('//:default')]}),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "filegroup(name='default')",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r')"
        )

        invalidatePackages()
        setRuleContext(createRuleContext("//:r"))
        val keyString = ev.eval("ruleContext.attr.label_list_dict.keys()[0]") as String
        Truth.assertThat(keyString).isEqualTo("default")
        val valueLabel: Label = ev.eval("ruleContext.attr.label_list_dict.values()[0][0].label") as Label
        assertThat(valueLabel).isEqualTo(Label.parseCanonical("//:default"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictAllowsFilesWhenAllowFilesIsTrue() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_files=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("myfile.cc")
        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={'key': ['myfile.cc']})"
        )

        invalidatePackages()
        assertThat(getConfiguredTarget("//:r")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictAllowsFilesOfAppropriateTypes() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_files=['.cc']),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("myfile.cc")
        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={'key': ['myfile.cc']})"
        )

        invalidatePackages()
        assertThat(getConfiguredTarget("//:r")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictForbidsFilesOfIncorrectTypes() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_files=['.cc']),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("myfile.cpp")
        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={'key': ['myfile.cpp']})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "in label_list_dict attribute of my_rule rule //:r: source file '//:"
                    + "myfile.cpp' is misplaced here (expected .cc)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictForbidsFilesWhenAllowFilesIsFalse() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_files=False),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("myfile.cpp")
        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={'key': ['myfile.cpp']})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "in label_list_dict attribute of my_rule rule //:r: source file '//:"
                    + "myfile.cpp' is misplaced here (expected no files)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictAllowsRulesWithRequiredProviders() {
        scratch.file(
            "my_rule.bzl",
            """
        MyInfo = provider()
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(providers=[MyInfo]),
          }
        )
        def _dep_impl(ctx):
          return MyInfo(my_provider=5)
        my_dep_rule = rule(
          implementation = _dep_impl,
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule', 'my_dep_rule')",
            "my_dep_rule(name='dep')",
            "my_rule(name='r',",
            "        label_list_dict={'key': [':dep']})"
        )

        invalidatePackages()
        assertThat(getConfiguredTarget("//:r")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictForbidsRulesMissingRequiredProviders() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        MyInfo = provider()
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(providers=[[MyInfo]]),
          }
        )
        def _dep_impl(ctx):
          return
        my_dep_rule = rule(
          implementation = _dep_impl,
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule', 'my_dep_rule')",
            "my_dep_rule(name='dep')",
            "my_rule(name='r',",
            "        label_list_dict={'key': [':dep']})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "in label_list_dict attribute of my_rule rule //:r: '//:dep' does not have mandatory"
                    + " providers: 'MyInfo'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictForbidsEmptyDictWhenAllowEmptyIsFalse() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_empty=False),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={})"
        )

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "in label_list_dict attribute of my_rule rule //:r: attribute must be non empty"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictAllowsEmptyDictWhenAllowEmptyIsTrue() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(allow_empty=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD",
            "load('//:my_rule.bzl', 'my_rule')",
            "my_rule(name='r',",
            "        label_list_dict={})"
        )

        invalidatePackages()
        assertThat(getConfiguredTarget("//:r")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictForbidsMissingAttributeWhenMandatoryIsTrue() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(mandatory=True),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("BUILD", "load('//:my_rule.bzl', 'my_rule')", "my_rule(name='r')")

        invalidatePackages()
        getConfiguredTarget("//:r")
        assertContainsEvent(
            "missing value for mandatory attribute 'label_list_dict' in 'my_rule' rule"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelListDictAllowsMissingAttributeWhenMandatoryIsFalse() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'label_list_dict': attr.label_list_dict(mandatory=False),
          }
        )
        
        """.trimIndent()
        )

        scratch.file("BUILD", "load('//:my_rule.bzl', 'my_rule')", "my_rule(name='r')")

        invalidatePackages()
        createRuleContext("//:r")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelAttributeDefault() {
        scratch.file(
            "my_rule.bzl",
            """
        def _impl(ctx):
          return
        my_rule = rule(
          implementation = _impl,
          attrs = {
            'explicit_dep': attr.label(default = Label('//:dep')),
            '_implicit_dep': attr.label(default = Label('//:dep')),
            'explicit_dep_list': attr.label_list(default = [Label('//:dep')]),
            '_implicit_dep_list': attr.label_list(default = [Label('//:dep')]),
            'explicit_dep_list_dict': attr.label_list_dict(default = {'key': [Label('//:dep')]}),
            '_implicit_dep_list_dict': attr.label_list_dict(default = {'key': [Label('//:dep')]}),
          }
        )
        
        """.trimIndent()
        )

        scratch.file(
            "BUILD", "filegroup(name='dep')", "load('//:my_rule.bzl', 'my_rule')", "my_rule(name='r')"
        )

        invalidatePackages()
        setRuleContext(createRuleContext("//:r"))
        val explicitDepLabel: Label = ev.eval("ruleContext.attr.explicit_dep.label") as Label
        assertThat(explicitDepLabel).isEqualTo(Label.parseCanonical("//:dep"))
        val implicitDepLabel: Label = ev.eval("ruleContext.attr._implicit_dep.label") as Label
        assertThat(implicitDepLabel).isEqualTo(Label.parseCanonical("//:dep"))
        val explicitDepListLabel: Label = ev.eval("ruleContext.attr.explicit_dep_list[0].label") as Label
        assertThat(explicitDepListLabel).isEqualTo(Label.parseCanonical("//:dep"))
        val implicitDepListLabel: Label = ev.eval("ruleContext.attr._implicit_dep_list[0].label") as Label
        assertThat(implicitDepListLabel).isEqualTo(Label.parseCanonical("//:dep"))
        val explicitDepListDictLabel: Label =
            ev.eval("ruleContext.attr.explicit_dep_list_dict['key'][0].label") as Label
        assertThat(explicitDepListDictLabel).isEqualTo(Label.parseCanonical("//:dep"))
        val implicitDepListDictLabel: Label =
            ev.eval("ruleContext.attr._implicit_dep_list_dict['key'][0].label") as Label
        assertThat(implicitDepListDictLabel).isEqualTo(Label.parseCanonical("//:dep"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeLabelInExternalRepository() {
        scratch.file(
            "external_rule.bzl",
            """
        def _impl(ctx):
          return
        external_rule = rule(
          implementation = _impl,
          attrs = {
            'internal_dep': attr.label(default = Label('//:dep'))
          }
        )
        
        """.trimIndent()
        )

        scratch.file("BUILD", "filegroup(name='dep')")

        scratch.file("/r/MODULE.bazel", "module(name='r')")
        scratch.file(
            "/r/a/BUILD",
            """
        load('@@//:external_rule.bzl', 'external_rule')
        external_rule(name='r')
        
        """.trimIndent()
        )

        scratch.overwriteFile(
            "MODULE.bazel", "bazel_dep(name='r')", "local_path_override(module_name='r', path='/r')"
        )

        invalidatePackages( /* alsoConfigs= */
            false
        ) // Repository shuffling messes with toolchain labels.
        setRuleContext(createRuleContext("@@r+//a:r"))
        val depLabel: Label = ev.eval("ruleContext.attr.internal_dep.label") as Label
        assertThat(depLabel).isEqualTo(Label.parseCanonical("//:dep"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAccessingRunfiles() {
        scratch.file("test/a.py")
        scratch.file("test/b.py")
        scratch.file("test/__init__.py")
        scratch.file(
            "test/rule.bzl",
            """
        def _impl(ctx):
          return
        starlark_rule = rule(
          implementation = _impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('//test:rule.bzl', 'starlark_rule')",
            "load('//test_defs:foo_binary.bzl', 'foo_binary')",
            "foo_binary(name = 'lib', srcs = ['lib.py', 'lib2.py'])",
            "starlark_rule(name = 'foo', dep = ':lib')",
            "foo_binary(name = 'lib_with_init', srcs = ['lib_with_init.py', 'lib2.py', '__init__.py'])",
            "starlark_rule(name = 'foo_with_init', dep = ':lib_with_init')"
        )

        setRuleContext(createRuleContext("//test:foo"))
        val filenames: Any =
            ev.eval("[f.short_path for f in ruleContext.attr.dep.default_runfiles.files.to_list()]")
        Truth.assertThat(filenames).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val filenamesList: net.starlark.java.eval.Sequence<*>? = filenames as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(filenamesList).containsAtLeast("test/lib.py", "test/lib2.py")

        setRuleContext(createRuleContext("//test:foo_with_init"))
        val noEmptyFilenames: Any =
            ev.eval("ruleContext.attr.dep.default_runfiles.empty_filenames.to_list()")
        Truth.assertThat(noEmptyFilenames).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val noEmptyFilenamesList: net.starlark.java.eval.Sequence<*>? =
            noEmptyFilenames as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(noEmptyFilenamesList).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAccessingRunfilesSymlinks() {
        scratch.file("test/a.py")
        scratch.file("test/b.py")
        scratch.file(
            "test/rule.bzl",
            """
        def symlink_impl(ctx):
          symlinks = {
            'symlink_' + f.short_path: f
            for f in ctx.files.symlink
          }
          return DefaultInfo(
            runfiles = ctx.runfiles(
              symlinks=symlinks,
            )
          )
        symlink_rule = rule(
          implementation = symlink_impl,
          attrs = {
            'symlink': attr.label(allow_files=True),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'symlink_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        symlink_rule(name = 'lib_with_symlink', symlink = ':a.py')
        foo_binary(
          name = 'test_with_symlink',
          srcs = ['test/b.py'],
          data = [':lib_with_symlink'],
        )
        
        """.trimIndent()
        )
        setRuleContext(createRuleContext("//test:test_with_symlink"))
        val symlinkPaths: Any =
            ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]")
        Truth.assertThat(symlinkPaths).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val symlinkPathsList: net.starlark.java.eval.Sequence<*>? = symlinkPaths as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(symlinkPathsList).containsExactly("symlink_test/a.py").inOrder()
        val symlinkFilenames: Any =
            ev.eval(
                "[s.target_file.short_path for s in"
                        + " ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]"
            )
        Truth.assertThat(symlinkFilenames).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val symlinkFilenamesList: net.starlark.java.eval.Sequence<*>? =
            symlinkFilenames as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(symlinkFilenamesList).containsExactly("test/a.py").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAccessingRunfilesRootSymlinks() {
        scratch.file("test/a.py")
        scratch.file("test/b.py")
        scratch.file(
            "test/rule.bzl",
            """
        def root_symlink_impl(ctx):
          root_symlinks = {
            'root_symlink_' + f.short_path: f
            for f in ctx.files.root_symlink
          }
          return DefaultInfo(
            runfiles = ctx.runfiles(
              root_symlinks=root_symlinks,
            )
          )
        root_symlink_rule = rule(
          implementation = root_symlink_impl,
          attrs = {
            'root_symlink': attr.label(allow_files=True)
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'root_symlink_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        root_symlink_rule(name = 'lib_with_root_symlink', root_symlink = ':a.py')
        foo_binary(
          name = 'test_with_root_symlink',
          srcs = ['test/b.py'],
          data = [':lib_with_root_symlink'],
        )
        
        """.trimIndent()
        )
        setRuleContext(createRuleContext("//test:test_with_root_symlink"))
        val rootSymlinkPaths: Any =
            ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]")
        Truth.assertThat(rootSymlinkPaths).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val rootSymlinkPathsList: net.starlark.java.eval.Sequence<*>? =
            rootSymlinkPaths as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(rootSymlinkPathsList).containsExactly("root_symlink_test/a.py").inOrder()
        val rootSymlinkFilenames: Any =
            ev.eval(
                "[s.target_file.short_path for s in"
                        + " ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]"
            )
        Truth.assertThat(rootSymlinkFilenames).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val rootSymlinkFilenamesList: net.starlark.java.eval.Sequence<*>? =
            rootSymlinkFilenames as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(rootSymlinkFilenamesList).containsExactly("test/a.py").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForwardingDefaultInfoRetainsDataRunfiles() {
        scratch.file(
            "bar/rules.bzl",
            """
        def _forward_default_info_impl(ctx):
            return [
                ctx.attr.target[DefaultInfo],
            ]
        forward_default_info = rule(
            implementation = _forward_default_info_impl,
            attrs = {
                'target': attr.label(
                    mandatory = True,
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.file("bar/i_am_a_runfile")
        scratch.file(
            "bar/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        load(':rules.bzl', 'forward_default_info')
        java_library(
            name = 'lib',
            data = ['i_am_a_runfile'],
        )
        forward_default_info(
            name = 'forwarded_lib',
            target = ':lib',
        )
        
        """.trimIndent()
        )

        val nativeTarget: ConfiguredTarget = getConfiguredTarget("//bar:lib")

        val nativeRunfiles: com.google.common.collect.ImmutableList<Artifact?>? =
            getDataRunfiles(nativeTarget).getAllArtifacts().toList()
        val forwardedTarget: ConfiguredTarget = getConfiguredTarget("//bar:forwarded_lib")
        val forwardedRunfiles: com.google.common.collect.ImmutableList<Artifact?>? =
            getDataRunfiles(forwardedTarget).getAllArtifacts().toList()
        Truth.assertThat(forwardedRunfiles).isEqualTo(nativeRunfiles)
        Truth.assertThat(forwardedRunfiles).hasSize(1)
        assertThat(forwardedRunfiles.get(0).getPath().getBaseName()).isEqualTo("i_am_a_runfile")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAccessingRunfilesSymlinksAsDepsets() {
        // Prepare rule using ctx.runfiles() with `symlinks`/`root_symlinks` kwargs.
        scratch.file("test/a.py")
        scratch.file("test/b.py")
        scratch.file(
            "test/rule.bzl",
            """
        def symlink_impl(ctx):
          symlinks = {
            'symlink_' + f.short_path: f
            for f in ctx.files.symlink
          }
          root_symlinks = {
            'root_symlink_' + f.short_path: f
            for f in ctx.files.symlink
          }
          runfiles_from_dict = ctx.runfiles(
            symlinks=symlinks,
            root_symlinks=root_symlinks,
          )
          runfiles_from_depset = ctx.runfiles(
            symlinks = runfiles_from_dict.symlinks,
            root_symlinks = runfiles_from_dict.root_symlinks,
          )

          return DefaultInfo(runfiles = runfiles_from_depset,)
        symlink_rule = rule(
          implementation = symlink_impl,
          attrs = {
            'symlink': attr.label(allow_files=True),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'symlink_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        symlink_rule(name = 'lib_with_symlink', symlink = ':a.py')
        foo_binary(
          name = 'test_with_symlink',
          srcs = ['test/b.py'],
          data = [':lib_with_symlink'],
        )
        
        """.trimIndent()
        )
        setRuleContext(createRuleContext("//test:test_with_symlink"))

        // Evaluate path expression for runfiles symlinks.
        val symlinkPaths: Any =
            ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]")
        val rootSymlinkPaths: Any =
            ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]")

        // Confirm expected runfiles symlink behavior in returned sequences.
        Truth.assertThat(symlinkPaths).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val symlinkPathsList: net.starlark.java.eval.Sequence<*>? = symlinkPaths as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(symlinkPathsList).containsExactly("symlink_test/a.py").inOrder()
        val symlinkFilenames: Any =
            ev.eval(
                "[s.target_file.short_path for s in"
                        + " ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]"
            )
        Truth.assertThat(symlinkFilenames).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val symlinkFilenamesList: net.starlark.java.eval.Sequence<*>? =
            symlinkFilenames as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(symlinkFilenamesList).containsExactly("test/a.py").inOrder()
        Truth.assertThat(rootSymlinkPaths).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val rootSymlinkPathsList: net.starlark.java.eval.Sequence<*>? =
            rootSymlinkPaths as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(rootSymlinkPathsList).containsExactly("root_symlink_test/a.py").inOrder()
        val rootSymlinkFilenames: Any =
            ev.eval(
                "[s.target_file.short_path for s in"
                        + " ruleContext.attr.data[0].data_runfiles.root_symlinks.to_list()]"
            )
        Truth.assertThat(rootSymlinkFilenames).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val rootSymlinkFilenamesList: net.starlark.java.eval.Sequence<*>? =
            rootSymlinkFilenames as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(rootSymlinkFilenamesList).containsExactly("test/a.py").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runfiles_merge() {
        scratch.file("test/a.py")
        scratch.file("test/b.py")
        scratch.file("test/other.py")
        scratch.file(
            "test/rule.bzl",
            """
        def symlink_merge_impl(ctx):
          runfiles = ctx.runfiles(symlinks = {
            'symlink_' + ctx.file.symlink.short_path: ctx.file.symlink
          })
          if ctx.attr.dep:
            runfiles = runfiles.merge(ctx.attr.dep[DefaultInfo].default_runfiles)
          return DefaultInfo(
            runfiles = runfiles
          )
        symlink_merge_rule = rule(
          implementation = symlink_merge_impl,
          attrs = {
            'symlink': attr.label(allow_single_file=True),
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'symlink_merge_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        symlink_merge_rule(name = 'lib_a', symlink = ':a.py', dep = 'lib_b')
        symlink_merge_rule(name = 'lib_b', symlink = ':b.py')
        foo_binary(
          name = 'test',
          srcs = ['test/other.py'],
          data = [':lib_a'],
        )
        
        """.trimIndent()
        )
        setRuleContext(createRuleContext("//test:test"))
        val symlinkPaths: Any =
            ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]")
        Truth.assertThat(symlinkPaths).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val symlinkPathsList: net.starlark.java.eval.Sequence<*>? = symlinkPaths as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(symlinkPathsList)
            .containsExactly("symlink_test/a.py", "symlink_test/b.py")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runfiles_mergeAll() {
        scratch.file("test/a.py")
        scratch.file("test/b.py")
        scratch.file("test/c.py")
        scratch.file("test/other.py")
        scratch.file(
            "test/rule.bzl",
            """
        def symlink_merge_all_impl(ctx):
          runfiles = ctx.runfiles(symlinks = {
            'symlink_' + ctx.file.symlink.short_path: ctx.file.symlink
          })
          if ctx.attr.deps:
            runfiles = runfiles.merge_all([dep[DefaultInfo].default_runfiles
                                           for dep in ctx.attr.deps])
          return DefaultInfo(
            runfiles = runfiles
          )
        symlink_merge_all_rule = rule(
          implementation = symlink_merge_all_impl,
          attrs = {
            'symlink': attr.label(allow_single_file=True),
            'deps': attr.label_list(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'symlink_merge_all_rule')
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        symlink_merge_all_rule(name = 'lib_a', symlink = ':a.py', deps = [':lib_b', ':lib_c'])
        symlink_merge_all_rule(name = 'lib_b', symlink = ':b.py')
        symlink_merge_all_rule(name = 'lib_c', symlink = ':c.py')
        foo_binary(
          name = 'test',
          srcs = ['test/other.py'],
          data = [':lib_a'],
        )
        
        """.trimIndent()
        )
        setRuleContext(createRuleContext("//test:test"))
        val symlinkPaths: Any =
            ev.eval("[s.path for s in ruleContext.attr.data[0].data_runfiles.symlinks.to_list()]")
        Truth.assertThat(symlinkPaths).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
        val symlinkPathsList: net.starlark.java.eval.Sequence<*>? =
            net.starlark.java.eval.Sequence.cast<String?>(symlinkPaths, String::class.java, "symlinkPaths")
        Truth.assertThat(symlinkPathsList)
            .containsExactly("symlink_test/a.py", "symlink_test/b.py", "symlink_test/c.py")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runfiles_incompatibleTransitiveFilesOrder() {
        scratch.file(
            "test/rule.bzl",
            """
        def _bad_runfiles_impl(ctx):
          ctx.runfiles(transitive_files = depset(order = 'preorder'))
        bad_runfiles = rule(implementation = _bad_runfiles_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':rule.bzl', 'bad_runfiles')
        bad_runfiles(name = 'test')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler) // Error expected.
        assertThat(getConfiguredTarget("//test:test")).isNull()
        assertContainsEvent("Error in runfiles: order 'preorder' is invalid for transitive_files")
    }

    // regression test for b/237547165
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun runfiles_failOnRunfilesTreeInFiles() {
        scratch.file(
            "test/rule.bzl",
            """
        def _impl(ctx):
          internal_output_group = ctx.attr.bin[OutputGroupInfo]._hidden_top_level_INTERNAL_
          ctx.runfiles(files = internal_output_group.to_list())
        bad_runfiles = rule(
          implementation = _impl,
          attrs = {'bin' : attr.label()}
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load(':rule.bzl', 'bad_runfiles')
        cc_binary(name = 'bin')
        bad_runfiles(name = 'test', bin = ':bin')
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler) // Error expected.
        assertThat(getConfiguredTarget("//test:test")).isNull()
        assertContainsEvent(
            "Error in runfiles: could not add all 'files': unexpected runfiles tree artifact"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExternalShortPath() {
        scratch.file("/bar/MODULE.bazel", "module(name='foo')")
        scratch.file("/bar/bar.txt")
        scratch.file("/bar/BUILD", "exports_files(['bar.txt'])")
        scratch.overwriteFile(
            "MODULE.bazel",
            "bazel_dep(name='foo')",
            "local_path_override(module_name='foo', path='/bar')"
        )
        scratch.file(
            "test/BUILD",
            """
        genrule(
            name = 'lib',
            srcs = ['@foo//:bar.txt'],
            cmd = 'echo ${'$'}(SRCS) ${'$'}@',
            outs = ['lib.out'],
            executable = 1,
        )
        
        """.trimIndent()
        )
        invalidatePackages()
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:lib")
        setRuleContext(ruleContext)
        val filename: String? = ev.eval("ruleContext.files.srcs[0].short_path").toString()
        Truth.assertThat(filename).isEqualTo("../foo+/bar.txt")
    }

    private val testingRuleDefinition = linesAsString(
        "def _testing_impl(ctx):",
        "  pass",
        "testing_rule = rule(",
        "  implementation = _testing_impl,",
        "  attrs = {'dep': attr.label()},",
        ")"
    )

    private val simpleBuildDefinition = linesAsString(
        "load(':rules.bzl', 'undertest_rule', 'testing_rule')",
        "undertest_rule(",
        "    name = 'undertest',",
        ")",
        "testing_rule(",
        "    name = 'testing',",
        "    dep = ':undertest',",
        ")"
    )

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependencyActionsProvider() {
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition(
                "ctx.actions.run_shell(outputs=[out], command='echo foo123 > ' + out.path)"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)

        val provider: Any = ev.eval("ruleContext.attr.dep[Actions]")
        Truth.assertThat(provider).isInstanceOf(StructImpl::class.java)
        assertThat((provider as StructImpl).getProvider()).isEqualTo(ActionsProvider.INSTANCE)
        ev.update("actions", provider)

        val mapping: MutableMap<*, *> = ev.eval("actions.by_file") as Dict<*, *>
        Truth.assertThat(mapping).hasSize(1)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        val actionUnchecked: Any = ev.eval("actions.by_file[file]")
        Truth.assertThat(actionUnchecked).isInstanceOf(ActionAnalysisMetadata::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoAccessToDependencyActionsWithoutStarlarkTest() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "test/rules.bzl",
            getSimpleNontestableUnderTestDefinition(
                "ctx.actions.run_shell(outputs=[out], command='echo foo123 > ' + out.path)"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)

        val e: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { ev.eval("ruleContext.attr.dep[Actions]") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "<target //test:undertest> (rule 'undertest_rule') doesn't contain "
                        + "declared provider 'Actions'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbstractActionInterface() {
        setBuildLanguageOptions("--incompatible_no_rule_outputs_param=false")
        scratch.file(
            "test/rules.bzl",
            "load('//test:providers.bzl', 'AInfo')",
            "def _undertest_impl(ctx):",
            "  out1 = ctx.outputs.out1",
            "  out2 = ctx.outputs.out2",
            "  ctx.actions.write(output=out1, content='foo123')",
            "  ctx.actions.run_shell(outputs=[out2], inputs=[out1],",
            "                        command='cp ' + out1.path + ' ' + out2.path)",
            "  return AInfo(out1=out1, out2=out2)",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out1': '%{name}1.txt',",
            "             'out2': '%{name}2.txt'},",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("AInfo", StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(A_KEY))
        ev.update("file1", ev.eval("ruleContext.attr.dep[AInfo].out1"))
        ev.update("file2", ev.eval("ruleContext.attr.dep[AInfo].out2"))
        ev.update("action1", ev.eval("ruleContext.attr.dep[Actions].by_file[file1]"))
        ev.update("action2", ev.eval("ruleContext.attr.dep[Actions].by_file[file2]"))

        Truth.assertThat(ev.eval("action1.inputs")).isInstanceOf(Depset::class.java)
        Truth.assertThat(ev.eval("action1.outputs")).isInstanceOf(Depset::class.java)

        Truth.assertThat(ev.eval("action1.argv")).isEqualTo(Starlark.NONE)
        Truth.assertThat(ev.eval("action2.content")).isEqualTo(Starlark.NONE)
        Truth.assertThat(ev.eval("action1.substitutions")).isEqualTo(Starlark.NONE)

        Truth.assertThat(ev.eval("action1.inputs.to_list()")).isEqualTo(ev.eval("[]"))
        Truth.assertThat(ev.eval("action1.outputs.to_list()")).isEqualTo(ev.eval("[file1]"))
        Truth.assertThat(ev.eval("action2.inputs.to_list()")).isEqualTo(ev.eval("[file1]"))
        Truth.assertThat(ev.eval("action2.outputs.to_list()")).isEqualTo(ev.eval("[file2]"))
    }

    // For created_actions() tests, the "undertest" rule represents both the code under test and the
    // Starlark user test code itself.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatedActions() {
        setBuildLanguageOptions("--incompatible_no_rule_outputs_param=false")
        // createRuleContext() gives us the context for a rule upon entry into its analysis function.
        // But we need to inspect the result of calling created_actions() after the rule context has
        // been modified by creating actions. So we'll call created_actions() from within the analysis
        // function and pass it along as a provider.
        scratch.file(
            "test/rules.bzl",
            "load('//test:providers.bzl', 'AInfo')",
            "def _undertest_impl(ctx):",
            "  out1 = ctx.outputs.out1",
            "  out2 = ctx.outputs.out2",
            "  ctx.actions.run_shell(outputs=[out1], command='echo foo123 > ' + out1.path,",
            "                        mnemonic='foo')",
            "  v = ctx.created_actions().by_file",
            "  ctx.actions.run_shell(outputs=[out2], command='echo bar123 > ' + out2.path)",
            "  return AInfo(v=v, out1=out1, out2=out2)",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out1': '%{name}1.txt',",
            "             'out2': '%{name}2.txt'},",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("AInfo", StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(A_KEY))

        val mapUnchecked: Any = ev.eval("ruleContext.attr.dep[AInfo].v")
        Truth.assertThat(mapUnchecked).isInstanceOf(Dict::class.java)
        val map: MutableMap<*, *>? = mapUnchecked as Dict<*, *>?
        // Should only have the first action because created_actions() was called
        // before the second action was created.
        val file: Any = ev.eval("ruleContext.attr.dep[AInfo].out1")
        Truth.assertThat(map).hasSize(1)
        Truth.assertThat(map).containsKey(file)
        val actionUnchecked: Any = map!!.get(file)!!
        Truth.assertThat(actionUnchecked).isInstanceOf(ActionAnalysisMetadata::class.java)
        assertThat((actionUnchecked as ActionAnalysisMetadata).getMnemonic()).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoAccessToCreatedActionsWithoutStarlarkTest() {
        scratch.file(
            "test/rules.bzl",
            getSimpleNontestableUnderTestDefinition(
                "ctx.actions.run_shell(outputs=[out], command='echo foo123 > ' + out.path)"
            )
        )
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule')
        undertest_rule(
            name = 'undertest',
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:undertest")
        setRuleContext(ruleContext)

        val result: Any = ev.eval("ruleContext.created_actions()")
        Truth.assertThat(result).isEqualTo(Starlark.NONE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpawnActionInterface() {
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition(
                "ctx.actions.run_shell(outputs=[out], command='echo foo123 > ' + out.path)"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")

        val argvUnchecked: Any = ev.eval("action.argv")
        Truth.assertThat(argvUnchecked).isInstanceOf(StarlarkList::class.java)
        val argv: StarlarkList<*>? = argvUnchecked as StarlarkList<*>?
        Truth.assertThat(argv as MutableList<*>?).hasSize(3)
        Truth.assertThat(argv.isImmutable()).isTrue()
        val result: Any = ev.eval("action.argv[2].startswith('echo foo123')")
        Truth.assertThat(result as Boolean?).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunShellUsesHelperScriptForLongCommand() {
        setBuildLanguageOptions("--incompatible_no_rule_outputs_param=false")
        // createRuleContext() gives us the context for a rule upon entry into its analysis function.
        // But we need to inspect the result of calling created_actions() after the rule context has
        // been modified by creating actions. So we'll call created_actions() from within the analysis
        // function and pass it along as a provider.
        scratch.file(
            "test/rules.bzl",
            "load('//test:providers.bzl', 'AInfo')",
            "def _undertest_impl(ctx):",
            "  out1 = ctx.outputs.out1",
            "  out2 = ctx.outputs.out2",
            "  out3 = ctx.outputs.out3",
            "  ctx.actions.run_shell(outputs=[out1],",
            "                        command='( %s ; ) > $1' % (",
            "                            ' ; '.join(['echo xxx%d' % i for i in range(0, 7000)])),",
            "                        mnemonic='mnemonic1',",
            "                        arguments=[out1.path])",
            "  ctx.actions.run_shell(outputs=[out2],",
            "                        command='echo foo > ' + out2.path,",
            "                        mnemonic='mnemonic2')",
            "  ctx.actions.run_shell(outputs=[out3],",
            "                        command='( %s ; ) > $1' % (",
            "                            ' ; '.join(['echo yyy%d' % i for i in range(0, 7000)])),",
            "                        mnemonic='mnemonic3',",
            "                        arguments=[out3.path])",
            "  v = ctx.created_actions().by_file",
            "  return AInfo(v=v, out1=out1, out2=out2, out3=out3)",
            "",
            "undertest_rule = rule(",
            "    implementation=_undertest_impl,",
            "    outputs={'out1': '%{name}1.txt',",
            "             'out2': '%{name}2.txt',",
            "             'out3': '%{name}3.txt'},",
            "    _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("AInfo", StarlarkProvider.builder(net.starlark.java.syntax.Location.BUILTIN).buildExported(A_KEY))

        val mapUnchecked: Any = ev.eval("ruleContext.attr.dep[AInfo].v")
        Truth.assertThat(mapUnchecked).isInstanceOf(Dict::class.java)
        val map: MutableMap<*, *>? = mapUnchecked as Dict<*, *>?
        val out1: Any = ev.eval("ruleContext.attr.dep[AInfo].out1")
        val out2: Any = ev.eval("ruleContext.attr.dep[AInfo].out2")
        val out3: Any = ev.eval("ruleContext.attr.dep[AInfo].out3")
        // 5 actions in total: 3 SpawnActions and 2 FileWriteActions for the two long commands.
        Truth.assertThat(map).hasSize(5)
        Truth.assertThat(map).containsKey(out1)
        Truth.assertThat(map).containsKey(out2)
        Truth.assertThat(map).containsKey(out3)
        val action1Unchecked: Any? = map!!.get(out1)
        val action2Unchecked: Any? = map.get(out2)
        val action3Unchecked: Any? = map.get(out3)
        Truth.assertThat(action1Unchecked).isInstanceOf(ActionAnalysisMetadata::class.java)
        Truth.assertThat(action2Unchecked).isInstanceOf(ActionAnalysisMetadata::class.java)
        Truth.assertThat(action3Unchecked).isInstanceOf(ActionAnalysisMetadata::class.java)
        val spawnAction1: ActionAnalysisMetadata = action1Unchecked as ActionAnalysisMetadata
        val spawnAction2: ActionAnalysisMetadata = action2Unchecked as ActionAnalysisMetadata
        val spawnAction3: ActionAnalysisMetadata = action3Unchecked as ActionAnalysisMetadata
        assertThat(spawnAction1.getMnemonic()).isEqualTo("mnemonic1")
        assertThat(spawnAction2.getMnemonic()).isEqualTo("mnemonic2")
        assertThat(spawnAction3.getMnemonic()).isEqualTo("mnemonic3")
        val helper1: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.filter<T?>(
                    spawnAction1.getInputs().toList(),
                    com.google.common.base.Predicate { a: T? -> a.getFilename().equals("undertest.run_shell_0.sh") })
            )
        Truth.assertThat(
            com.google.common.collect.Iterables.filter<T?>(
                spawnAction2.getInputs().toList(),
                com.google.common.base.Predicate { a: T? -> a.getFilename().contains("run_shell_") })
        )
            .isEmpty()
        val helper3: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.filter<T?>(
                    spawnAction3.getInputs().toList(),
                    com.google.common.base.Predicate { a: T? -> a.getFilename().equals("undertest.run_shell_2.sh") })
            )
        Truth.assertThat(map).containsKey(helper1)
        Truth.assertThat(map).containsKey(helper3)
        val action4Unchecked: Any? = map.get(helper1)
        val action5Unchecked: Any? = map.get(helper3)
        Truth.assertThat(action4Unchecked).isInstanceOf(FileWriteAction::class.java)
        Truth.assertThat(action5Unchecked).isInstanceOf(FileWriteAction::class.java)
        val fileWriteAction1: FileWriteAction = action4Unchecked as FileWriteAction
        val fileWriteAction2: FileWriteAction = action5Unchecked as FileWriteAction
        com.google.common.truth.Subject.contains("echo xxx6999 ;")
        com.google.common.truth.Subject.contains("echo yyy6999 ;")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidMnemonic() {
        scratch.file(
            "test/rule.bzl",
            """
        def _impl(ctx):
          out = ctx.actions.declare_file('f')
          ctx.actions.run_shell(
              outputs=[out], command='false', mnemonic='@@@')
        r = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'r')
        r(name = 'target')
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:target")
        assertContainsEvent(
            "mnemonic must only contain letters and/or digits, and have non-zero length, was: \"@@@\""
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionInterface() {
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition("ctx.actions.write(output=out, content='foo123')"),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")
        Truth.assertThat(ev.eval("action.mnemonic")).isEqualTo("FileWrite")

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        Truth.assertThat(contentUnchecked).isEqualTo("foo123")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionInterfaceWithCustomMnemonic() {
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition(
                "ctx.actions.write(output=out, content='foo123', mnemonic='MyWrite')"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")
        Truth.assertThat(ev.eval("action.mnemonic")).isEqualTo("MyWrite")

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        Truth.assertThat(contentUnchecked).isEqualTo("foo123")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionInterfaceWithArgs() {
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition(
                "args = ctx.actions.args()",
                "args.add('foo123')",
                "ctx.actions.write(output=out, content=args)"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")
        Truth.assertThat(ev.eval("action.mnemonic")).isEqualTo("FileWrite")

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        // Args content ends the file with a newline
        Truth.assertThat(contentUnchecked).isEqualTo("foo123\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionInterfaceWithArgsAndCustomMnemonic() {
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition(
                "args = ctx.actions.args()",
                "args.add('foo123')",
                "ctx.actions.write(output=out, content=args, mnemonic='MyWrite')"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")
        Truth.assertThat(ev.eval("action.mnemonic")).isEqualTo("MyWrite")

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        // Args content ends the file with a newline
        Truth.assertThat(contentUnchecked).isEqualTo("foo123\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionInterfaceWithArgsAndSupportsPathMapping() {
        useConfiguration("--experimental_output_paths=strip")
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition(
                "args = ctx.actions.args()",
                "args.add('foo123')",
                "ctx.actions.write(output=out, content=args,"
                        + " execution_requirements={'supports-path-mapping': ''})"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        val action: Action = ev.eval("ruleContext.attr.dep[Actions].by_file[file]") as Action
        ev.update("action", action)

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")
        assertThat(action.getExecutionInfo()).containsEntry("supports-path-mapping", "")

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        // Args content ends the file with a newline
        Truth.assertThat(contentUnchecked).isEqualTo("foo123\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionInterfaceWithArgsContainingTreeArtifact() {
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition(
                "directory = ctx.actions.declare_directory('dir')",
                "ctx.actions.run_shell(",
                "    outputs = [directory],",
                "    command = 'mkdir {out}'",
                ")",
                "args = ctx.actions.args()",
                "args.add_all([directory])",
                "ctx.actions.write(output=out, content=args)"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")

        // If the Args contain a directory File that needs to be expanded, the contents are not known
        // at analysis time.
        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isEqualTo(Starlark.NONE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileWriteActionInterfaceWithArgsExpansionError() {
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition(
                "args = ctx.actions.args()",
                "args.add_all(['args expansion error message'], map_each = fail_with_message)",
                "ctx.actions.write(output=out, content=args)"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")

        // If there's a failure when expanding Args, that error message is propagated.
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                "Should be an error expanding action.content",
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("action.content") })

        // e has a trivial stack (just <expr>, aka action.content), but its message
        // contains a stack that has evidently been flattened into a string and passed
        // through an event reporter as an ERROR at :7:15 (?).
        // Ideally we would remove some of this cruft.
        // ```
        // Error expanding command line:
        //
        //     /workspace/test/rules.bzl:7:15: Traceback (most recent call last):
        //          File "/workspace/test/rules.bzl", line 2, column 9, in fail_with_message
        //     Error in fail: args expansion error message
        // ```

        // stack=[fail_with_message@rules.bzl:2, fail@<builtin>]
        Truth.assertThat(e).hasMessageThat().contains("Error expanding command line:")
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("File \"/workspace/test/rules.bzl\", line 2, column 9, in fail_with_message")
        Truth.assertThat(e).hasMessageThat().contains("Error in fail: args expansion error message")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsMapEachFunctionMustBeGlobal() {
        // lambda
        scratch.file(
            "p/inc.bzl",
            """
        def _impl(ctx):
          ctx.actions.args().add_all([], map_each=lambda x: x)  # error
        r = rule(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "p/BUILD",
            """
        load('inc.bzl', 'r')
        r(name='r')
        
        """.trimIndent()
        )
        var ex: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//p:r") })
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains(
                "map_each function (declared at /workspace/p/inc.bzl:2:43) must be "
                        + "declared by a top-level def statement"
            )

        // non-global def
        scratch.file(
            "q/inc.bzl",
            """
        def _impl(ctx):
          def id(x): return x
          ctx.actions.args().add_all([], map_each=id)  # error
        r = rule(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "q/BUILD",
            """
        load('inc.bzl', 'r')
        r(name='r')
        
        """.trimIndent()
        )
        ex = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//q:r") })
        Truth.assertThat(ex)
            .hasMessageThat()
            .contains(
                "map_each function (declared at /workspace/q/inc.bzl:2:7) must be "
                        + "declared by a top-level def statement"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsMapEachFunctionAllowClosure() {
        // lambda
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition(
                "def local_fn(x): return 'local:%s' % x",
                "args = ctx.actions.args()",
                "args.add_all(['a', 'b'], allow_closure=True, map_each=lambda x: 'lambda:%s' % x)",
                "args.add_joined(['c', 'd'], join_with=';', allow_closure=True, map_each=local_fn)",
                "args.set_param_file_format('multiline')",
                "ctx.actions.write(output=out, content=args)"
            ),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        // Args content ends the file with a newline
        Truth.assertThat(ev.eval("action.content")).isEqualTo("lambda:a\nlambda:b\nlocal:c;local:d\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsMapEachWithPathMapper() {
        scratch.file(
            "test/rules.bzl",
            getSimpleUnderTestDefinition("ctx.actions.write(out, '')"),
            testingRuleDefinition
        )
        scratch.file("test/BUILD", simpleBuildDefinition)
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)

        ev.update("file1", ev.eval("ruleContext.actions.declare_file('file1')"))
        ev.update("file2", ev.eval("ruleContext.actions.declare_file('dir/file2')"))
        val result: Any =
            ev.eval(
                ("ruleContext.actions.args().add_all("
                        + "  [file1, file2],"
                        + "  allow_closure=True,"
                        + "  map_each=lambda f: 'file:%s:%s:%s:%s:%s' % (" // Verify that mapped roots are comparable.
                        + "        f.path, f.dirname, f.root.path, type(f.root), f.root <= f.root)"
                        + ")")
            )

        val stripConfig: PathMapper =
            PathMapper { execPath -> execPath.subFragment(0, 1).getRelative(execPath.subFragment(2)) }

        Truth.assertThat(result).isInstanceOf(Args::class.java)
        val args: CommandLine = (result as Args).build({ RepositoryMapping.EMPTY })
        val out = TestConstants.PRODUCT_NAME + "-out"
        assertThat(args.arguments(null, stripConfig))
            .containsExactly(
                String.format("file:%1\$s/bin/test/file1:%1\$s/bin/test:%1\$s/bin:mapped_root:True", out),
                String.format(
                    "file:%1\$s/bin/test/dir/file2:%1\$s/bin/test/dir:%1\$s/bin:mapped_root:True", out
                )
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionActionInterface() {
        scratch.file(
            "test/rules.bzl",
            "def _undertest_impl(ctx):",
            "  out = ctx.outputs.out",
            "  ctx.actions.expand_template(output=out,",
            "                              template=ctx.file.template, substitutions={'a': 'b'})",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out': '%{name}.txt'},",
            "  attrs = {'template': attr.label(allow_single_file=True)},",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt", "aaaaa", "bcdef")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        Truth.assertThat(contentUnchecked).isEqualTo("bbbbb\nbcdef\n")

        val substitutionsUnchecked: Any = ev.eval("action.substitutions")
        Truth.assertThat(substitutionsUnchecked).isInstanceOf(Dict::class.java)
        Truth.assertThat(substitutionsUnchecked)
            .isEqualTo(com.google.common.collect.ImmutableMap.of<String?, String?>("a", "b"))
    }

    @Throws(java.lang.Exception::class)
    private fun setUpCoverageInstrumentedTest() {
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
          name = 'foo',
          srcs = ['foo.cc'],
          deps = [':bar'],
        )
        cc_library(
          name = 'bar',
          srcs = ['bar.cc'],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCoverageInstrumentedCoverageDisabled() {
        setUpCoverageInstrumentedTest()
        useConfiguration("--nocollect_code_coverage", "--instrumentation_filter=.")
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:foo")
        setRuleContext(ruleContext)
        val result: Any = ev.eval("ruleContext.coverage_instrumented()")
        Truth.assertThat(result as Boolean?).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCoverageInstrumentedFalseForSourceFileLabel() {
        setUpCoverageInstrumentedTest()
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=.")
        setRuleContext(createRuleContext("//test:foo"))
        val result: Any = ev.eval("ruleContext.coverage_instrumented(ruleContext.attr.srcs[0])")
        Truth.assertThat(result as Boolean?).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCoverageInstrumentedDoesNotMatchFilter() {
        setUpCoverageInstrumentedTest()
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=:foo")
        setRuleContext(createRuleContext("//test:bar"))
        val result: Any = ev.eval("ruleContext.coverage_instrumented()")
        Truth.assertThat(result as Boolean?).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCoverageInstrumentedMatchesFilter() {
        setUpCoverageInstrumentedTest()
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=:foo")
        setRuleContext(createRuleContext("//test:foo"))
        val result: Any = ev.eval("ruleContext.coverage_instrumented()")
        Truth.assertThat(result as Boolean?).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCoverageInstrumentedDoesNotMatchFilterNonDefaultLabel() {
        setUpCoverageInstrumentedTest()
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=:foo")
        setRuleContext(createRuleContext("//test:foo"))
        // //test:bar does not match :foo, though //test:foo would.
        val result: Any = ev.eval("ruleContext.coverage_instrumented(ruleContext.attr.deps[0])")
        Truth.assertThat(result as Boolean?).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCoverageInstrumentedMatchesFilterNonDefaultLabel() {
        setUpCoverageInstrumentedTest()
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=:bar")
        setRuleContext(createRuleContext("//test:foo"))
        // //test:bar does match :bar, though //test:foo would not.
        val result: Any = ev.eval("ruleContext.coverage_instrumented(ruleContext.attr.deps[0])")
        Truth.assertThat(result as Boolean?).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFrozenRuleContextHasInaccessibleAttributes() {
        scratch.file(
            "test/BUILD",
            """
        load('//test:rules.bzl', 'main_rule', 'dep_rule')
        dep_rule(name = 'dep')
        main_rule(name = 'main', deps = [':dep'])
        
        """.trimIndent()
        )
        scratch.file("test/rules.bzl")

        for (attribute in CTX_ATTRIBUTES) {
            scratch.overwriteFile(
                "test/rules.bzl",
                "load('//myinfo:myinfo.bzl', 'MyInfo')",
                "def _main_impl(ctx):",
                "  dep = ctx.attr.deps[0]",
                "  file = ctx.outputs.file",
                "  foo = dep[MyInfo].dep_ctx." + attribute,
                "main_rule = rule(",
                "  implementation = _main_impl,",
                "  attrs = {",
                "    'deps': attr.label_list()",
                "  },",
                "  outputs = {'file': 'output.txt'},",
                ")",
                "def _dep_impl(ctx):",
                "  return MyInfo(dep_ctx = ctx)",
                "dep_rule = rule(implementation = _dep_impl)"
            )
            initializeSkyframeExecutor()
            val e: java.lang.AssertionError? =
                org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                    "Should have been unable to access dep_ctx." + attribute,
                    java.lang.AssertionError::class.java,
                    org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:main") })
            Truth.assertThat(e)
                .hasMessageThat()
                .contains(
                    ("cannot access field or method '"
                            + com.google.common.collect.Iterables.get<String?>(
                        com.google.common.base.Splitter.on('(').split(attribute), 0
                    )
                            + "' of rule context for '//test:dep' outside of its own rule implementation "
                            + "function")
                )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFrozenRuleContextForAspectsHasInaccessibleAttributes() {
        val attributes: MutableList<String> = java.util.ArrayList<String>()
        attributes.addAll(CTX_ATTRIBUTES)
        attributes.addAll(
            com.google.common.collect.ImmutableList.of<String?>(
                "rule.attr",
                "rule.executable",
                "rule.file",
                "rule.files",
                "rule.kind"
            )
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:rules.bzl', 'my_rule')
        my_rule(name = 'dep')
        my_rule(name = 'mid', deps = [':dep'])
        my_rule(name = 'main', deps = [':mid'])
        
        """.trimIndent()
        )
        scratch.file("test/rules.bzl")
        for (attribute in attributes) {
            scratch.overwriteFile(
                "test/rules.bzl",
                "MyInfo = provider()",
                "def _rule_impl(ctx):",
                "  pass",
                "def _aspect_impl(target, ctx):",
                "  if ctx.rule.attr.deps:",
                "    dep = ctx.rule.attr.deps[0]",
                "    file = ctx.actions.declare_file('file.txt')",
                "    foo = dep[MyInfo]." + (if (attribute.startsWith("rule.")) "" else "ctx.") + attribute,
                "  return MyInfo(ctx = ctx, rule=ctx.rule)",
                "MyAspect = aspect(implementation=_aspect_impl)",
                "my_rule = rule(",
                "  implementation = _rule_impl,",
                "  attrs = {",
                "    'deps': attr.label_list(aspects = [MyAspect])",
                "  },",
                ")"
            )

            reporter.removeHandler(failFastHandler)
            invalidatePackages()

            getConfiguredTarget("//test:main")

            // Typical value of e.getMessage():
            //
            // ERROR /workspace/test/BUILD:3:8: \
            //   in //test:rules.bzl%MyAspect aspect on my_rule rule //test:mid:
            // Traceback (most recent call last):
            //        File "/workspace/test/BUILD", line 3, column 8, in //test:rules.bzl%MyAspect
            //        File "/workspace/test/rules.bzl", line 7, column 18, in _aspect_impl
            // Error: cannot access field or method 'attr' of rule context for '//test:dep' \
            // outside of its own rule implementation function
            assertContainsEvent(
                ("cannot access field or method '"
                        + com.google.common.collect.Iterables.get<String?>(
                    com.google.common.base.Splitter.on('(').split(attribute), 0
                )
                        + "' of rule context for '//test:dep' outside of its own rule implementation "
                        + "function")
            )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMapAttributeOrdering() {
        scratch.file(
            "a/a.bzl",
            """
        key_provider = provider(fields=['keys'])
        def _impl(ctx):
          return [key_provider(keys=ctx.attr.value.keys())]
        a = rule(implementation=_impl, attrs={'value': attr.string_dict()})
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load(':a.bzl', 'a')
        a(name='a', value={'c': 'c', 'b': 'b', 'a': 'a', 'f': 'f', 'e': 'e', 'd': 'd'})
        
        """.trimIndent()
        )

        val a: ConfiguredTarget = getConfiguredTarget("//a")
        val key: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//a:a.bzl")), "key_provider")

        val keyInfo: StarlarkInfo = a.get(key) as StarlarkInfo
        val keys: net.starlark.java.eval.Sequence<*>? = keyInfo.getValue("keys") as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(keys).containsExactly("c", "b", "a", "f", "e", "d").inOrder()
    }

    @Throws(java.lang.Exception::class)
    private fun writeIntFlagBuildSettingFiles() {
        scratch.file(
            "test/build_setting.bzl",
            """
        BuildSettingInfo = provider(fields = ['name', 'value'])
        def _impl(ctx):
          return [BuildSettingInfo(name = ctx.attr.name, value = ctx.build_setting_value)]

        int_flag = rule(
          implementation = _impl,
          build_setting = config.int(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:build_setting.bzl', 'int_flag')
        int_flag(name = 'int_flag', build_setting_default = 42)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildSettingValue_explicitlySet() {
        writeIntFlagBuildSettingFiles()
        useConfiguration("--//test:int_flag=24")

        val buildSetting: ConfiguredTarget = getConfiguredTarget("//test:int_flag")
        val key: Provider.Key =
            Key(
                keyForBuild(
                    Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")
                ),
                "BuildSettingInfo"
            )
        val buildSettingInfo: StructImpl = buildSetting.get(key) as StructImpl

        assertThat(buildSettingInfo.getValue("value")).isEqualTo(StarlarkInt.of(24))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildSettingValue_defaultFallback() {
        writeIntFlagBuildSettingFiles()

        val buildSetting: ConfiguredTarget = getConfiguredTarget("//test:int_flag")
        val key: Provider.Key =
            Key(
                keyForBuild(
                    Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")
                ),
                "BuildSettingInfo"
            )
        val buildSettingInfo: StructImpl = buildSetting.get(key) as StructImpl

        assertThat(buildSettingInfo.getValue("value")).isEqualTo(StarlarkInt.of(42))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildSettingValue_allowMultipleSetting() {
        scratch.file(
            "test/build_setting.bzl",
            """
        BuildSettingInfo = provider(fields = ['name', 'value'])
        def _impl(ctx):
          return [BuildSettingInfo(name = ctx.attr.name, value = ctx.build_setting_value)]

        string_flag = rule(
          implementation = _impl,
          build_setting = config.string(flag = True, allow_multiple = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:build_setting.bzl', 'string_flag')
        string_flag(name = 'string_flag', build_setting_default = 'some-value')
        
        """.trimIndent()
        )

        // from default
        var buildSetting: ConfiguredTarget = getConfiguredTarget("//test:string_flag")
        var key: Provider.Key =
            Key(
                keyForBuild(
                    Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")
                ),
                "BuildSettingInfo"
            )
        var buildSettingInfo: StructImpl = buildSetting.get(key) as StructImpl

        assertThat(buildSettingInfo.getValue("value")).isInstanceOf(MutableList::class.java)
        Truth.assertThat(buildSettingInfo.getValue("value") as MutableList<String?>?).containsExactly("some-value")

        // Set multiple times
        useConfiguration(
            "--//test:string_flag=some-other-value", "--//test:string_flag=some-other-other-value"
        )
        buildSetting = getConfiguredTarget("//test:string_flag")
        key =
            Key(
                keyForBuild(
                    Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")
                ),
                "BuildSettingInfo"
            )
        buildSettingInfo = buildSetting.get(key) as StructImpl

        assertThat(buildSettingInfo.getValue("value")).isInstanceOf(MutableList::class.java)
        Truth.assertThat(buildSettingInfo.getValue("value") as MutableList<String?>?)
            .containsExactly("some-other-value", "some-other-other-value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildSettingValue_isRepeatedSetting() {
        scratch.file(
            "test/build_setting.bzl",
            """
        BuildSettingInfo = provider(fields = ['name', 'value'])
        def _impl(ctx):
          return [BuildSettingInfo(name = ctx.attr.name, value = ctx.build_setting_value)]

        string_list_flag = rule(
          implementation = _impl,
          build_setting = config.string_list(flag = True, repeatable = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:build_setting.bzl', 'string_list_flag')
        string_list_flag(name = 'string_list_flag', build_setting_default = ['some-value'])
        
        """.trimIndent()
        )

        // from default
        var buildSetting: ConfiguredTarget = getConfiguredTarget("//test:string_list_flag")
        var key: Provider.Key =
            Key(
                keyForBuild(
                    Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")
                ),
                "BuildSettingInfo"
            )
        var buildSettingInfo: StructImpl = buildSetting.get(key) as StructImpl

        assertThat(buildSettingInfo.getValue("value")).isInstanceOf(MutableList::class.java)
        Truth.assertThat(buildSettingInfo.getValue("value") as MutableList<String?>?).containsExactly("some-value")

        // Set multiple times
        useConfiguration(
            "--//test:string_list_flag=some-other-value",
            "--//test:string_list_flag=some-other-other-value"
        )
        buildSetting = getConfiguredTarget("//test:string_list_flag")
        key =
            Key(
                keyForBuild(
                    Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")
                ),
                "BuildSettingInfo"
            )
        buildSettingInfo = buildSetting.get(key) as StructImpl

        assertThat(buildSettingInfo.getValue("value")).isInstanceOf(MutableList::class.java)
        Truth.assertThat(buildSettingInfo.getValue("value") as MutableList<String?>?)
            .containsExactly("some-other-value", "some-other-other-value")

        // No splitting on comma.
        useConfiguration(
            "--//test:string_list_flag=a,b,c",
            "--//test:string_list_flag=a",
            "--//test:string_list_flag=b,c"
        )
        buildSetting = getConfiguredTarget("//test:string_list_flag")
        key =
            Key(
                keyForBuild(
                    Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")
                ),
                "BuildSettingInfo"
            )
        buildSettingInfo = buildSetting.get(key) as StructImpl

        assertThat(buildSettingInfo.getValue("value")).isInstanceOf(MutableList::class.java)
        Truth.assertThat(buildSettingInfo.getValue("value") as MutableList<String?>?)
            .containsExactly("a,b,c", "a", "b,c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildSettingValue_nonBuildSettingRule() {
        scratch.file(
            "test/rule.bzl",
            """
        def _impl(ctx):
          foo = ctx.build_setting_value
          return []
        non_build_setting = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'non_build_setting')
        non_build_setting(name = 'my_non_build_setting')
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//test:my_non_build_setting")
        assertContainsEvent(
            "attempting to access 'build_setting_value' of non-build setting "
                    + "//test:my_non_build_setting"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun createToolchains() {
        scratch.file(
            "rule/test_toolchain.bzl",
            """
        def _impl(ctx):
            value = ctx.attr.value
            toolchain = platform_common.ToolchainInfo(value = value)
            return [toolchain]
        test_toolchain = rule(
            implementation = _impl,
            attrs = {'value': attr.string()},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "rule/test_rule.bzl",
            """
        result = provider()
        def _impl(ctx):
            toolchain = ctx.toolchains['//rule:toolchain_type']
            return [result(
                value_from_toolchain = toolchain.value,
            )]
        test_rule = rule(
            implementation = _impl,
            toolchains = ['//rule:toolchain_type'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "rule/BUILD",
            """
        exports_files(['test_toolchain/bzl', 'test_rule.bzl'])
        toolchain_type(name = 'toolchain_type')
        
        """.trimIndent()
        )
        scratch.file(
            "toolchain/BUILD",
            """
        load('//rule:test_toolchain.bzl', 'test_toolchain')
        test_toolchain(
            name = 'foo',
            value = 'foo',
        )
        toolchain(
            name = 'foo_toolchain',
            toolchain_type = '//rule:toolchain_type',
            target_compatible_with = ['//platform:constraint_1'],
            toolchain = ':foo',
        )
        test_toolchain(
            name = 'bar',
            value = 'bar',
        )
        toolchain(
            name = 'bar_toolchain',
            toolchain_type = '//rule:toolchain_type',
            target_compatible_with = ['//platform:constraint_2'],
            toolchain = ':bar',
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun createPlatforms() {
        scratch.overwriteFile(
            "platform/BUILD",
            """
        constraint_setting(name = 'setting')
        constraint_value(
            name = 'constraint_1',
            constraint_setting = ':setting',
        )
        constraint_value(
            name = 'constraint_2',
            constraint_setting = ':setting',
        )
        platform(
            name = 'platform_1',
            constraint_values = [':constraint_1'],
        )
        platform(
            name = 'platform_2',
            constraint_values = [':constraint_2'],
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun getToolchainResult(targetName: String?): String? {
        val myRuleTarget: ConfiguredTarget = getConfiguredTarget(targetName)
        val info: StructImpl =
            myRuleTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//rule:test_rule.bzl")), "result"
                )
            ) as StructImpl

        assertThat(info).isNotNull()
        return info.getValue("value_from_toolchain") as String?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchains() {
        createToolchains()
        createPlatforms()
        scratch.file(
            "demo/BUILD",
            """
        load('//rule:test_rule.bzl', 'test_rule')
        test_rule(
            name = 'demo',
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
            "--platforms=//platform:platform_1"
        )
        var value = getToolchainResult("//demo")
        Truth.assertThat(value).isEqualTo("foo")

        // Re-test with the other platform.
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
            "--platforms=//platform:platform_2"
        )
        value = getToolchainResult("//demo")
        Truth.assertThat(value).isEqualTo("bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetPlatformHasConstraint() {
        createPlatforms()

        scratch.file(
            "demo/test_rule.bzl",
            """
        result = provider()
        def _impl(ctx):
            constraint = ctx.attr._constraint[platform_common.ConstraintValueInfo]
            has_constraint = ctx.target_platform_has_constraint(constraint)
            return [result(
                has_constraint = has_constraint,
            )]
        test_rule = rule(
            implementation = _impl,
            attrs = {
                '_constraint': attr.label(default = '//platform:constraint_1'),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "demo/BUILD",
            """
        load(':test_rule.bzl', 'test_rule')
        test_rule(
            name = 'demo',
        )
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platform:platform_1")

        var myRuleTarget: ConfiguredTarget = getConfiguredTarget("//demo")
        var info: StructImpl =
            myRuleTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//demo:test_rule.bzl")), "result"
                )
            ) as StructImpl

        assertThat(info).isNotNull()
        var hasConstraint = info.getValue("has_constraint") as Boolean
        Truth.assertThat(hasConstraint).isTrue()

        // Re-test with the other platform.
        useConfiguration("--platforms=//platform:platform_2")
        myRuleTarget = getConfiguredTarget("//demo")
        info =
            myRuleTarget.get(
                Key(
                    keyForBuild(Label.parseCanonical("//demo:test_rule.bzl")), "result"
                )
            ) as StructImpl

        assertThat(info).isNotNull()
        hasConstraint = info.getValue("has_constraint") as Boolean
        Truth.assertThat(hasConstraint).isFalse()
    }

    @Throws(java.lang.Exception::class)
    private fun writeExecGroups() {
        createToolchains()
        createPlatforms()
        scratch.file(
            "something/defs.bzl",
            """
        result = provider()
        def _impl(ctx):
          exec_groups = ctx.exec_groups
          toolchain = ctx.exec_groups['dragonfruit'].toolchains['//rule:toolchain_type']
          return [result(
            toolchain_value = toolchain.value,
            exec_groups = exec_groups,
          )]
        use_exec_groups = rule(
          implementation = _impl,
          exec_groups = {
            'dragonfruit': exec_group(toolchains = ['//rule:toolchain_type']),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "something/BUILD",
            """
        load('//something:defs.bzl', 'use_exec_groups')
        use_exec_groups(name = 'nectarine')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
            "--platforms=//platform:platform_1"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecGroup_toolchain() {
        writeExecGroups()

        val target: ConfiguredTarget = getConfiguredTarget("//something:nectarine")
        val info: StructImpl =
            target.get(
                Key(
                    keyForBuild(Label.parseCanonicalUnchecked("//something:defs.bzl")), "result"
                )
            ) as StructImpl
        assertThat(info).isNotNull()
        assertThat(info.getValue("toolchain_value")).isEqualTo("foo")
        assertThat(info.getValue("exec_groups")).isInstanceOf(StarlarkExecGroupCollection::class.java)
        val toolchainContexts: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (info.getValue("exec_groups") as StarlarkExecGroupCollection)
                .getToolchainCollectionForTesting()
        assertThat(toolchainContexts.keySet()).containsExactly(DEFAULT_EXEC_GROUP_NAME, "dragonfruit")
        assertThat(toolchainContexts.get(DEFAULT_EXEC_GROUP_NAME).toolchainTypes()).isEmpty()
        assertThat(toolchainContexts.get("dragonfruit").resolvedToolchainLabels())
            .containsExactly(Label.parseCanonicalUnchecked("//toolchain:foo"))
    }

    // Tests for an error that occurs when two exec groups have different requirements (toolchain
    // types and exec constraints), but have the same toolchain type. This also requires the toolchain
    // transition to be enabled.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecGroup_duplicateToolchainType() {
        createToolchains()
        createPlatforms()
        scratch.file(
            "something/defs.bzl",
            """
        result = provider()
        def _impl(ctx):
          exec_groups = ctx.exec_groups
          toolchain = ctx.exec_groups['dragonfruit'].toolchains['//rule:toolchain_type']
          return [result(
            toolchain_value = toolchain.value,
            exec_groups = exec_groups,
          )]
        use_exec_groups = rule(
          implementation = _impl,
          exec_groups = {
            'dragonfruit': exec_group(toolchains = ['//rule:toolchain_type']),
            'passionfruit': exec_group(
              toolchains = ['//rule:toolchain_type'],
              exec_compatible_with = ['//something:extra'],
            ),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "something/BUILD",
            """
        constraint_setting(name = 'setting', default_constraint_value = ':extra')
        constraint_value(name = 'extra', constraint_setting = ':setting')
        load('//something:defs.bzl', 'use_exec_groups')
        use_exec_groups(name = 'nectarine')
        
        """.trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
            "--platforms=//platform:platform_1"
        )

        val target: ConfiguredTarget = getConfiguredTarget("//something:nectarine")
        val info: StructImpl =
            target.get(
                Key(
                    keyForBuild(Label.parseCanonicalUnchecked("//something:defs.bzl")), "result"
                )
            ) as StructImpl
        assertThat(info).isNotNull()
        assertThat(info.getValue("toolchain_value")).isEqualTo("foo")
        assertThat(info.getValue("exec_groups")).isInstanceOf(StarlarkExecGroupCollection::class.java)
        val toolchainContexts: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (info.getValue("exec_groups") as StarlarkExecGroupCollection)
                .getToolchainCollectionForTesting()
        assertThat(toolchainContexts.keySet())
            .containsExactly(DEFAULT_EXEC_GROUP_NAME, "dragonfruit", "passionfruit")
        assertThat(toolchainContexts.get(DEFAULT_EXEC_GROUP_NAME).toolchainTypes()).isEmpty()
        assertThat(toolchainContexts.get("dragonfruit").resolvedToolchainLabels())
            .containsExactly(Label.parseCanonicalUnchecked("//toolchain:foo"))
        assertThat(toolchainContexts.get("passionfruit").resolvedToolchainLabels())
            .containsExactly(Label.parseCanonicalUnchecked("//toolchain:foo"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidExecGroup() {
        writeExecGroups()

        scratch.overwriteFile(
            "something/defs.bzl",
            """
        result = provider()
        def _impl(ctx):
          exec_groups = ctx.exec_groups
          toolchain = ctx.exec_groups['unknown_fruit']
          return []
        use_exec_groups = rule(
          implementation = _impl,
          exec_groups = {
            'dragonfruit': exec_group(toolchains = ['//rule:toolchain_type']),
          },
        )
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//something:nectarine") })
        assertContainsEvent(
            "unrecognized exec group 'unknown_fruit' requested. Available exec groups: [dragonfruit]"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCannotAccessDefaultGroupViaExecGroups() {
        writeExecGroups()

        scratch.overwriteFile(
            "something/defs.bzl",
            "result = provider()",
            "def _impl(ctx):",
            "  exec_groups = ctx.exec_groups",
            "  toolchain = ctx.exec_groups['" + DEFAULT_EXEC_GROUP_NAME + "']",
            "  return []",
            "use_exec_groups = rule(",
            "  implementation = _impl,",
            "  exec_groups = {",
            "    'dragonfruit': exec_group(toolchains = ['//rule:toolchain_type']),",
            "  },",
            ")"
        )

        org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//something:nectarine") })
        assertContainsEvent(
            ("unrecognized exec group '"
                    + DEFAULT_EXEC_GROUP_NAME
                    + "' requested. Available exec groups: [dragonfruit]")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidExecGroupName() {
        writeExecGroups()
        val badName = "1bad-stuff-name"

        scratch.overwriteFile(
            "something/defs.bzl",
            "result = provider()",
            "def _impl(ctx):",
            "  exec_groups = ctx.exec_groups",
            "  toolchain = ctx.exec_groups['" + badName + "']",
            "  return []",
            "use_exec_groups = rule(",
            "  implementation = _impl,",
            "  exec_groups = {",
            "    '" + badName + "': exec_group(toolchains = ['//rule:toolchain_type']),",
            "  },",
            ")"
        )

        org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//something:nectarine") })
        assertContainsEvent("Exec group name '" + badName + "' is not a valid name.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildFilePath() {
        scratch.file("/foo/MODULE.bazel", "module(name='foo')")
        scratch.file("/foo/bar/BUILD", "genrule(name = 'baz', cmd = 'dummy_cmd', outs = ['a.txt'])")

        scratch.overwriteFile(
            "MODULE.bazel",
            "bazel_dep(name='foo')",
            "local_path_override(module_name='foo', path='/foo')"
        )

        invalidatePackages(false)

        setRuleContext(createRuleContext("@@foo+//bar:baz"))
        var result: Any = ev.eval("ruleContext.build_file_path")
        Truth.assertThat(result).isEqualTo("bar/BUILD")

        // The reason `build_file_path` should be deprecated. It's just another trivial knob on `ctx`.
        // The results are always the same as `ctx.label.package + '/BUILD'`
        result = ev.eval("ruleContext.label.package + '/BUILD'")
        Truth.assertThat(result).isEqualTo("bar/BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStopExportingBuildFilePath() {
        scratch.file("/foo/MODULE.bazel", "module(name='foo')")
        scratch.file("/foo/bar/BUILD", "genrule(name = 'baz', cmd = 'dummy_cmd', outs = ['a.txt'])")

        scratch.overwriteFile(
            "MODULE.bazel",
            "bazel_dep(name='foo')",
            "local_path_override(module_name='foo', path='/foo')"
        )

        invalidatePackages(false)
        setBuildLanguageOptions("--incompatible_stop_exporting_build_file_path")

        setRuleContext(createRuleContext("@@foo+//bar:baz"))

        val evalException: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("ruleContext.build_file_path") })
        Truth.assertThat(evalException)
            .hasMessageThat()
            .isEqualTo(
                ("Use ctx.label.package + '/BUILD' instead of ctx.build_file_path.\nUse"
                        + " --incompatible_stop_exporting_build_file_path=false to temporarily disable this"
                        + " check.")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisallowCtxResolveTools() {
        scratch.file("pkg/BUILD", "genrule(name = 'foo', cmd = 'dummy_cmd', outs = ['a.txt'])")

        setBuildLanguageOptions("--incompatible_disallow_ctx_resolve_tools")

        setRuleContext(createRuleContext("//pkg:foo"))

        val evalException: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("ruleContext.resolve_tools()") })
        Truth.assertThat(evalException)
            .hasMessageThat()
            .isEqualTo(
                ("Pass an executable or tools argument to ctx.actions.run or ctx.actions.run_shell"
                        + " instead of calling ctx.resolve_tools.\n"
                        + "Use --noincompatible_disallow_ctx_resolve_tools to temporarily disable this"
                        + " check.")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoToolchainContext() {
        // Build setting rules do not have a toolchain context, as they are part of the configuration.
        scratch.file(
            "test/BUILD",
            """
        load(':rule.bzl', 'sample_setting')
        toolchain_type(name = 'toolchain_type')
        sample_setting(
            name = 'test',
            build_setting_default = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rule.bzl",
            """
        def _sample_impl(ctx):
            # This should raise an error.
            ctx.toolchains['//:toolchain_type']
            fail('Toolchain was not empty')
        sample_setting = rule(
            implementation = _sample_impl,
            build_setting = config.bool(flag = True),
        )
        
        """.trimIndent()
        )
        org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:test") })
        assertContainsEvent("Toolchains are not valid in this context")
        assertDoesNotContainEvent("Toolchain was not empty")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoToolchainContext_toolchainTypesEmpty() {
        // Build setting rules do not have a toolchain context, as they are part of the configuration.
        scratch.file(
            "test/BUILD",
            """
        load(':rule.bzl', 'sample_setting')
        toolchain_type(name = 'toolchain_type')
        sample_setting(
            name = 'test',
            build_setting_default = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/rule.bzl",
            """
        def _sample_impl(ctx):
            if ctx.toolchains.toolchain_types():
                fail('Toolchain context should be empty')
        sample_setting = rule(
            implementation = _sample_impl,
            build_setting = config.bool(flag = True),
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//test:test")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionComputedSubstitution() {
        scratch.file(
            "test/rules.bzl",
            "def _artifact_to_basename(file):",
            "  return file.basename if file.basename != 'ignored.txt' else None",
            "",
            "def _undertest_impl(ctx):",
            "  template_dict = ctx.actions.template_dict()",
            "  template_dict.add('a', 'X')",
            "  template_dict.add_joined('td_files_key', depset(ctx.files.srcs),",
            "                           map_each = _artifact_to_basename,",
            "                           join_with = '%%',",
            "                           format_joined = 'header/%s/footer',",
            "                          )",
            "  ctx.actions.expand_template(output=ctx.outputs.out,",
            "                              template=ctx.file.template,",
            "                              substitutions={'b': 'Y'},",
            "                              computed_substitutions=template_dict,",
            "                              )",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out': '%{name}.txt'},",
            "  attrs = {'template': attr.label(allow_single_file=True),",
            "           'srcs':attr.label_list(allow_files=True)",
            "           },",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt", "aaaaa", "bbb-pqr", "td_files_key")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
            srcs = ['foo.txt', 'bar.txt', 'baz.txt', 'ignored.txt'],
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        Truth.assertThat(contentUnchecked)
            .isEqualTo("XXXXX\nYYY-pqr\nheader/foo.txt%%bar.txt%%baz.txt/footer\n")

        val substitutionsUnchecked: Any = ev.eval("action.substitutions")
        Truth.assertThat(substitutionsUnchecked).isInstanceOf(Dict::class.java)
        Truth.assertThat(substitutionsUnchecked)
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "a", "X",
                    "b", "Y",
                    "td_files_key", "header/foo.txt%%bar.txt%%baz.txt/footer"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionComputedSubstitutionWithUniquify() {
        scratch.file(
            "test/rules.bzl",
            "def _artifact_to_extension(file):",
            "  return file.extension",
            "",
            "def _undertest_impl(ctx):",
            "  template_dict = ctx.actions.template_dict()",
            "  template_dict.add_joined('exts', depset(ctx.files.srcs),",
            "                           map_each = _artifact_to_extension,",
            "                           uniquify = True,",
            "                           join_with = '%%',",
            "                          )",
            "  ctx.actions.expand_template(output=ctx.outputs.out,",
            "                              template=ctx.file.template,",
            "                              computed_substitutions=template_dict,",
            "                              )",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out': '%{name}.txt'},",
            "  attrs = {'template': attr.label(allow_single_file=True),",
            "           'srcs':attr.label_list(allow_files=True)",
            "           },",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt", "exts", "exts")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
            srcs = ['foo.txt', 'bar.log', 'baz.txt', 'bak.exe', 'far.sh', 'boo.sh'],
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        Truth.assertThat(contentUnchecked).isEqualTo("txt%%log%%exe%%sh\ntxt%%log%%exe%%sh\n")

        val substitutionsUnchecked: Any = ev.eval("action.substitutions")
        Truth.assertThat(substitutionsUnchecked).isInstanceOf(Dict::class.java)
        Truth.assertThat(substitutionsUnchecked)
            .isEqualTo(com.google.common.collect.ImmutableMap.of<String?, String?>("exts", "txt%%log%%exe%%sh"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionComputedSubstitutionWithUniquifyAndListMapEach() {
        scratch.file(
            "test/rules.bzl",
            "def _artifact_to_extension(file):",
            "  if file.extension == 'sh':",
            "    return [file.extension]",
            "  return [file.extension, '.' + file.extension]",
            "",
            "def _undertest_impl(ctx):",
            "  template_dict = ctx.actions.template_dict()",
            "  template_dict.add_joined('exts', depset(ctx.files.srcs),",
            "                           map_each = _artifact_to_extension,",
            "                           uniquify = True,",
            "                           join_with = '%%',",
            "                          )",
            "  ctx.actions.expand_template(output=ctx.outputs.out,",
            "                              template=ctx.file.template,",
            "                              computed_substitutions=template_dict,",
            "                              )",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out': '%{name}.txt'},",
            "  attrs = {'template': attr.label(allow_single_file=True),",
            "           'srcs':attr.label_list(allow_files=True)",
            "           },",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt", "exts", "exts")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
            srcs = ['foo.txt', 'bar.log', 'baz.txt', 'bak.exe', 'far.sh', 'boo.sh'],
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        Truth.assertThat(ev.eval("type(action)")).isEqualTo("Action")

        val contentUnchecked: Any = ev.eval("action.content")
        Truth.assertThat(contentUnchecked).isInstanceOf(String::class.java)
        Truth.assertThat(contentUnchecked)
            .isEqualTo("txt%%.txt%%log%%.log%%exe%%.exe%%sh\ntxt%%.txt%%log%%.log%%exe%%.exe%%sh\n")

        val substitutionsUnchecked: Any = ev.eval("action.substitutions")
        Truth.assertThat(substitutionsUnchecked).isInstanceOf(Dict::class.java)
        Truth.assertThat(substitutionsUnchecked)
            .isEqualTo(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "exts",
                    "txt%%.txt%%log%%.log%%exe%%.exe%%sh"
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionComputedSubstitutionDuplicateKeys() {
        scratch.file(
            "test/rules.bzl",
            """
        def _undertest_impl(ctx):
          template_dict = ctx.actions.template_dict()
          template_dict.add('a', '1')
          template_dict.add('a', '2')
          ctx.actions.expand_template(output=ctx.outputs.out,
                                      template=ctx.file.template,
                                      computed_substitutions=template_dict,
                                      )
        undertest_rule = rule(
          implementation = _undertest_impl,
          outputs = {'out': '%{name}.txt'},
          attrs = {'template': attr.label(allow_single_file=True),},
        )
        
        """.trimIndent()
        )
        scratch.file("test/template.txt")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        
        """.trimIndent()
        )

        checkError("//test:undertest", "Error in expand_template: Multiple entries with same key: a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionComputedSubstitutionNoParamMapEach() {
        scratch.file(
            "test/rules.bzl",
            "def no_args_func():",
            "  return 'magic-string'",
            "",
            "def _undertest_impl(ctx):",
            "  template_dict = ctx.actions.template_dict()",
            "  template_dict.add_joined('%the_key%', depset(ctx.files.template),",
            "                           map_each = no_args_func,",
            "                           join_with = '')",
            "  ctx.actions.expand_template(output=ctx.outputs.out,",
            "                              template=ctx.file.template,",
            "                              computed_substitutions=template_dict,",
            "                              )",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out': '%{name}.txt'},",
            "  attrs = {'template': attr.label(allow_single_file=True),},",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt", "%the_key%")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        val evalException: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("action.content") })
        Truth.assertThat(evalException)
            .hasMessageThat()
            .isEqualTo("no_args_func() does not accept positional arguments, but got 1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionComputedSubstitutionTwoParamMapEach() {
        scratch.file(
            "test/rules.bzl",
            "def two_args_func(arg1, arg2):",
            "  return 'magic-string'",
            "",
            "def _undertest_impl(ctx):",
            "  template_dict = ctx.actions.template_dict()",
            "  template_dict.add_joined('%the_key%', depset(ctx.files.template),",
            "                           map_each = two_args_func,",
            "                           join_with = '')",
            "  ctx.actions.expand_template(output=ctx.outputs.out,",
            "                              template=ctx.file.template,",
            "                              computed_substitutions=template_dict,",
            "                              )",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out': '%{name}.txt'},",
            "  attrs = {'template': attr.label(allow_single_file=True),},",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt", "%the_key%")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        val evalException: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("action.content") })
        Truth.assertThat(evalException)
            .hasMessageThat()
            .isEqualTo("two_args_func() missing 1 required positional argument: arg2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionComputedSubstitutionMapEachBadReturnType() {
        scratch.file(
            "test/rules.bzl",
            "def file_to_owner_label(file):",
            "  return file.owner",
            "",
            "def _undertest_impl(ctx):",
            "  template_dict = ctx.actions.template_dict()",
            "  template_dict.add_joined('%files%', depset(ctx.files.template),",
            "                           map_each = file_to_owner_label,",
            "                           join_with = '')",
            "  ctx.actions.expand_template(output=ctx.outputs.out,",
            "                              template=ctx.file.template,",
            "                              computed_substitutions=template_dict,",
            "                              )",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out': '%{name}.txt'},",
            "  attrs = {'template': attr.label(allow_single_file=True),},",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        val evalException: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("action.content") })
        Truth.assertThat(evalException)
            .hasMessageThat()
            .isEqualTo(
                ("Function provided to map_each must return string, None, or list of strings, "
                        + "but returned type Label for key '%files%' and value: "
                        + "File:[/workspace[source]]test/template.txt")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionComputedSubstitutionMapEachBadListReturnType() {
        scratch.file(
            "test/rules.bzl",
            "def file_to_owner_label(file):",
            "  return [file.owner]",
            "",
            "def _undertest_impl(ctx):",
            "  template_dict = ctx.actions.template_dict()",
            "  template_dict.add_joined('%files%', depset(ctx.files.template),",
            "                           map_each = file_to_owner_label,",
            "                           join_with = '')",
            "  ctx.actions.expand_template(output=ctx.outputs.out,",
            "                              template=ctx.file.template,",
            "                              computed_substitutions=template_dict,",
            "                              )",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out': '%{name}.txt'},",
            "  attrs = {'template': attr.label(allow_single_file=True),},",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        
        """.trimIndent()
        )
        val ruleContext: StarlarkRuleContext = createRuleContext("//test:testing")
        setRuleContext(ruleContext)
        ev.update("file", ev.eval("ruleContext.attr.dep[DefaultInfo].files.to_list()[0]"))
        ev.update("action", ev.eval("ruleContext.attr.dep[Actions].by_file[file]"))

        val evalException: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { ev.eval("action.content") })
        Truth.assertThat(evalException)
            .hasMessageThat()
            .isEqualTo(
                ("Function provided to map_each must return string, None, or list of strings, "
                        + "but returned list containing element '//test:template.txt' of type Label for "
                        + "key '%files%' and value: File:[/workspace[source]]test/template.txt")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTemplateExpansionComputedSubstitutionMapEachMustBeTopLevel() {
        scratch.file(
            "test/rules.bzl",
            "def _undertest_impl(ctx):",
            "",
            "  def file_to_shortpath(file):",
            "    return file.short_path",
            "",
            "  template_dict = ctx.actions.template_dict()",
            "  template_dict.add_joined('%files%', depset(ctx.files.template),",
            "                           map_each = file_to_shortpath,",
            "                           join_with = '')",
            "  ctx.actions.expand_template(output=ctx.outputs.out,",
            "                              template=ctx.file.template,",
            "                              computed_substitutions=template_dict,",
            "                              )",
            "undertest_rule = rule(",
            "  implementation = _undertest_impl,",
            "  outputs = {'out': '%{name}.txt'},",
            "  attrs = {'template': attr.label(allow_single_file=True),},",
            "  _skylark_testable = True,",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'undertest_rule', 'testing_rule')
        undertest_rule(
            name = 'undertest',
            template = ':template.txt',
        )
        testing_rule(
            name = 'testing',
            dep = ':undertest',
        )
        
        """.trimIndent()
        )

        checkError("//test:testing", "must be declared by a top-level def statement")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transformFile_correctActionGenerated(
        @TestParameter("ctx.actions.transform_info_file", "ctx.actions.transform_version_file") apiMethod: String
    ) {
        val volatileAndExcuteUnconditionally =
            apiMethod == "ctx.actions.transform_version_file"
        scratch.file(
            "test/rules.bzl",
            "def t(d):",
            " r = {}",
            " r['{NAME}'] = d['name'] + '_foo'",
            " r['{CLIENT}'] = d['client'] + '_c'",
            " return r",
            "def _buildinfo_impl(ctx):",
            String.format(
                "  output = %s(transform_func = t, template = ctx.file.template, output_file_name ="
                        + " 'buildinfo.h')",
                apiMethod
            ),
            "  return DefaultInfo(files = depset([output]))",
            "buildinfo_rule = rule(",
            "  implementation = _buildinfo_impl,",
            "  attrs = {'template': attr.label(allow_single_file=True)},",
            ")",
            testingRuleDefinition
        )
        scratch.file("test/template.txt", "#define NAME {NAME}", "#define CLIENT {CLIENT}")
        scratch.file(
            "test/BUILD",
            """
        load(':rules.bzl', 'buildinfo_rule')
        buildinfo_rule(
            name = 'generating_target',
            template = ':template.txt',
        )
        
        """.trimIndent()
        )

        val buildInfo: ConfiguredTarget = getConfiguredTarget("//test:generating_target")
        val buildInfoArtifact: Artifact = getFilesToBuild(buildInfo).getSingleton()
        val buildInfoAction: BuildInfoFileWriteAction =
            getGeneratingAction(buildInfoArtifact) as BuildInfoFileWriteAction

        assertThat(buildInfoAction).isNotNull()
        assertThat(buildInfoArtifact).isNotNull()
        assertThat(buildInfoArtifact.getFilename()).isEqualTo("buildinfo.h")
        assertThat(buildInfoArtifact.isConstantMetadata()).isEqualTo(volatileAndExcuteUnconditionally)
        assertThat(buildInfoAction.getMnemonic()).isEqualTo("TranslateBuildInfo")
        assertThat(buildInfoAction.executeUnconditionally())
            .isEqualTo(volatileAndExcuteUnconditionally)
        assertThat(buildInfoAction.isVolatile()).isEqualTo(volatileAndExcuteUnconditionally)
        Truth.assertThat(artifactsToStrings(buildInfoAction.getInputs())).contains("src test/template.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transformFile_cannotBeAccessedOutsideOfAllowlist(
        @TestParameter("ctx.actions.transform_version_file", "ctx.actions.transform_info_file") apiMethod: String?
    ) {
        scratch.file(
            "some_dir/rules.bzl",
            "def t(d):",
            " pass",
            "def _buildinfo_impl(ctx):",
            String.format(
                "  output = %s(transform_func = t, template = ctx.file.template, output_file_name ="
                        + " 'buildinfo.h')",
                apiMethod
            ),
            "  return DefaultInfo(files = depset([output]))",
            "buildinfo_rule = rule(",
            "  implementation = _buildinfo_impl,",
            "  attrs = {'template': attr.label(allow_single_file=True)},",
            ")",
            testingRuleDefinition
        )
        scratch.file("some_dir/template.txt", "")

        checkError(
            "some_dir",
            "generating_target",
            "file '//some_dir:rules.bzl' cannot use private API",
            "load(':rules.bzl', 'buildinfo_rule')",
            "buildinfo_rule(",
            "    name = 'generating_target',",
            "    template = ':template.txt',",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidDefaultValue_stringSet() {
        scratch.file(
            "test/build_setting.bzl",
            """
        def _build_setting_impl(ctx):
            return []

        string_set_flag = rule(
            implementation = _build_setting_impl,
            build_setting = config.string_set(flag = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_setting.bzl", "string_set_flag")

        string_set_flag(
            name = "my_flag",
            build_setting_default = set(["v1", 123]),
        )
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_flag") })
        assertContainsEvent(
            "expected value of type 'string' for element 1 of attribute 'build_setting_default' of"
                    + " 'string_set_flag', but got 123 (int)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun stringSet_repeatableWithoutFlag_fails() {
        scratch.file(
            "test/build_settings.bzl",
            """
        def _impl(ctx):
            return []

        string_set_setting = rule(
            implementation = _impl,
            build_setting = config.string_set(repeatable = True),
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:build_settings.bzl", "string_set_setting")

        string_set_setting(
            name = "my_flag",
            build_setting_default = set(["default_value"]),
        )
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:my_flag") })
        assertContainsEvent("'repeatable' can only be set for a setting with 'flag = True'")
    }

    @org.junit.Test
    @TestParameters( // Only default value is set.
        ("{defaultValue: ['v2', 'v1', 'v1', 'v3', 'v2', 'v3', 'v4'], repeatable: false, cmdValue: null,"
                + " expectedValue: ['v1', 'v2', 'v3', 'v4']}") // Not repeatable, flag value is not the same as the default.
        , ("{defaultValue: ['default'], repeatable: false, cmdValue:"
                + " ['v1,v4,v3,v2,v3,v1,v4,v1'], expectedValue: ['v1', 'v2', 'v3', 'v4']}") // Repeatable, flag value is not the same as the default.
        , ("{defaultValue: ['default'], repeatable: true, cmdValue: ['v2', 'v2', 'v1', 'v3', 'v4', 'v1'],"
                + " expectedValue: ['v1', 'v2', 'v3', 'v4']}") // Not repeatable, flag value is the same as the default.
        , ("{defaultValue: ['v2', 'v1', 'v1', 'v3', 'v2', 'v3', 'v4'], repeatable: false, cmdValue:"
                + " ['v1,v4,v3,v2,v3,v1,v4,v1'], expectedValue: ['v1', 'v2', 'v3', 'v4']}") // Repeatable, flag value is the same as the default.
        , ("{defaultValue: ['v2', 'v1', 'v1', 'v3', 'v2', 'v3', 'v4'], repeatable: true, cmdValue: ['v2',"
                + " 'v2', 'v1', 'v3', 'v4', 'v1'], expectedValue: ['v1', 'v2', 'v3', 'v4']}")
    )
    @Throws(java.lang.Exception::class)
    fun testStringSetFlag(
        defaultValue: MutableList<String?>,
        repeatable: Boolean,
        cmdValue: MutableList<String?>?,
        expectedValue: MutableList<String?>
    ) {
        scratch.file(
            "test/build_setting.bzl",
            String.format(
                """
            BuildSettingInfo = provider(fields = ['name', 'value'])
            def _impl(ctx):
              if type(ctx.build_setting_value) != "set":
                fail("expected value of type 'set(string)' for attribute 'build_setting_value', but got {}".format(type(ctx.build_setting_value)))
              return [BuildSettingInfo(name = ctx.attr.name, value = ctx.build_setting_value)]

            string_set_flag = rule(
              implementation = _impl,
              build_setting = config.string_set(flag = True, repeatable = %s),
            )
            
            """.trimIndent(),
                if (repeatable) "True" else "False"
            )
        )
        scratch.file(
            "test/BUILD",
            String.format(
                """
            load('//test:build_setting.bzl', 'string_set_flag')
            string_set_flag(
                name = "my_flag",
                build_setting_default = set([%s]),
            )
            
            """.trimIndent(),
                defaultValue.stream().map<String?> { v: String? -> String.format("'%s'", v) }
                    .collect(Collectors.joining(","))))
        if (cmdValue != null) {
            useConfiguration(
                *cmdValue.stream()
                    .map<String?> { v: String? -> String.format("--//test:my_flag=%s", v) }
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                    .toTypedArray<String?>())
        }

        val buildSetting: ConfiguredTarget = getConfiguredTarget("//test:my_flag")

        val key: Provider.Key =
            Key(
                keyForBuild(
                    Label.create(buildSetting.getLabel().getPackageIdentifier(), "build_setting.bzl")
                ),
                "BuildSettingInfo"
            )
        val buildSettingInfo: StructImpl = buildSetting.get(key) as StructImpl
        assertThat(buildSettingInfo.getValue("value")).isInstanceOf(MutableSet::class.java)
        assertThat(buildSettingInfo.getValue("value")).isEqualTo(
            com.google.common.collect.ImmutableSet.copyOf<String?>(
                expectedValue
            )
        )

        val buildSettingProvider: BuildSettingProvider =
            buildSetting.getProvider(BuildSettingProvider::class.java)
        assertThat(buildSettingProvider.getDefaultValue()).isInstanceOf(MutableSet::class.java)
        assertThat(buildSettingProvider.getDefaultValue()).isEqualTo(
            com.google.common.collect.ImmutableSet.copyOf<String?>(
                defaultValue
            )
        )
        assertThat(buildSettingProvider.getType()).isEqualTo(Types.STRING_SET)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelKeyedStringDictAttrTransitioned() {
        scratch.file(
            "test/rules.bzl",
            """
        MyProvider = provider()

        def _my_transition_impl(settings, attr):
            return {"//command_line_option:compilation_mode": "opt"}

        my_transition = transition(
            implementation = _my_transition_impl,
            inputs = [],
            outputs = ["//command_line_option:compilation_mode"],
        )

        def _impl(ctx):
            result = []
            for k, v in ctx.attr.dict_attr.items():
              result.append(k[DefaultInfo].files.to_list()[0].path)
            return MyProvider(result = result)

        my_rule = rule(
          implementation = _impl,
            attrs = {
                'dict_attr': attr.label_keyed_string_dict(
                    cfg = my_transition,
                ),
            },
        )

        def _dep_impl(ctx):
          f = ctx.actions.declare_file(ctx.label.name + ".txt")
          ctx.actions.write(f, "hello")
          return DefaultInfo(files = depset([f]))

        my_dep = rule(
            implementation = _dep_impl,
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:rules.bzl", "my_rule", "my_dep")

        my_rule(
            name = "my_target",
            dict_attr = {":d1": "s1", ":d2": "s2"},
        )
        my_dep(name = "d1")
        my_dep(name = "d2")
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//test:my_target")
        val myProviderKey: Provider.Key =
            Key(
                keyForBuild(Label.create(myTarget.getLabel().getPackageIdentifier(), "rules.bzl")),
                "MyProvider"
            )
        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            (myTarget.get(myProviderKey) as StarlarkInfo).getValue("result")
        // Dependencies output path should have `-opt` after the transition.
        (result as Iterable<*>).forEach { s: Any? -> Truth.assertThat(s.toString()).contains("-opt/") }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageRelativeLabel() {
        scratch.file("rules/BUILD")
        scratch.file(
            "rules/rules.bzl",
            """
        MyProvider = provider()

        def _impl(ctx):
            return MyProvider(result = ctx.package_relative_label(":some_target"))

        my_rule = rule(
          implementation = _impl,
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//rules:rules.bzl", "my_rule")

        my_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        val myTarget: ConfiguredTarget = getConfiguredTarget("//test:my_target")
        val myProviderKey: Provider.Key =
            Key(
                keyForBuild(Label.create(PackageIdentifier.createInMainRepo("rules"), "rules.bzl")),
                "MyProvider"
            )
        val result: Label? = (myTarget.get(myProviderKey) as StarlarkInfo).getValue("result") as Label?
        assertThat(result).isEqualTo(Label.parseCanonicalUnchecked("//test:some_target"))
    }

    companion object {
        private val providersBzlKey: BzlLoadValue.Key? =
            keyForBuild(Label.parseCanonicalUnchecked("//test:providers.bzl"))
        private val A_KEY: StarlarkProvider.Key = Key(providersBzlKey, "AInfo")
        private val B_KEY: StarlarkProvider.Key = Key(providersBzlKey, "BInfo")
        private val C_KEY: StarlarkProvider.Key = Key(providersBzlKey, "CInfo")

        /** A test rule that exercises the semantics of mandatory providers.  */
        private val TESTING_RULE_FOR_MANDATORY_PROVIDERS: MockRule = MockRule {
            MockRule.define(
                "testing_rule_for_mandatory_providers",
                { builder, env ->
                    builder
                        .setUndocumented()
                        .add(attr("srcs", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                        .add(
                            attr("deps", LABEL_LIST)
                                .legacyAllowAnyFileType()
                                .mandatoryProvidersList(
                                    com.google.common.collect.ImmutableList.of<E?>(
                                        com.google.common.collect.ImmutableList.of<E?>(
                                            StarlarkProviderIdentifier.forKey(
                                                A_KEY
                                            )
                                        ),
                                        com.google.common.collect.ImmutableList.of<E?>(
                                            StarlarkProviderIdentifier.forKey(B_KEY),
                                            StarlarkProviderIdentifier.forKey(C_KEY)
                                        )
                                    )
                                )
                        )
                })
        }

        private val FAKE_CC_LIBRARY: RuleDefinition = MockRule {
            MockRule.define(
                "fake_cc_library",
                { builder, env ->
                    builder
                        .add(attr("srcs", LABEL_LIST).legacyAllowAnyFileType())
                        .add(
                            attr("deps", LABEL_LIST)
                                .allowedFileTypes(FileTypeSet.NO_FILE)
                                .allowedRuleClasses("fake_cc_library")
                        )
                        .add(attr("generator_name", STRING))
                        .add(attr("generator_function", STRING))
                })
        } as MockRule

        private fun assertArtifactList(result: Any?, artifacts: MutableList<String?>) {
            Truth.assertThat(result).isInstanceOf(net.starlark.java.eval.Sequence::class.java)
            val resultList: net.starlark.java.eval.Sequence<*>? = result as net.starlark.java.eval.Sequence<*>?
            Truth.assertThat(resultList).hasSize(artifacts.size)
            var i = 0
            for (artifact in artifacts) {
                assertThat((resultList.get(i++) as Artifact).getFilename()).isEqualTo(artifact)
            }
        }

        // Borrowed from Scratch.java.
        private fun linesAsString(vararg lines: String?): String {
            val builder: java.lang.StringBuilder = java.lang.StringBuilder()
            for (line in lines) {
                builder.append(line)
                builder.append('\n')
            }
            return builder.toString()
        }

        // The common structure of the following actions tests is a rule under test depended upon by
        // a testing rule, where the rule under test has one output and one caller-supplied action.
        private fun getSimpleUnderTestDefinition(
            withStarlarkTestable: Boolean, actionLines: Array<String?>
        ): String {
            return linesAsString( // TODO(b/153667498): Just passing fail to map_each parameter of Args.add_all does not work.
                "def fail_with_message(s):",
                "    fail(s)",
                "",
                "def _undertest_impl(ctx):",
                "  out = ctx.outputs.out",
                "  " + com.google.common.base.Joiner.on("\n  ").join(actionLines),
                "undertest_rule = rule(",
                "  implementation = _undertest_impl,",
                "  outputs = {'out': '%{name}.txt'},",
                if (withStarlarkTestable) "  _skylark_testable = True," else "",
                ")"
            )
        }

        private fun getSimpleUnderTestDefinition(vararg actionLines: String?): String {
            return Companion.getSimpleUnderTestDefinition(true, actionLines)
        }

        private fun getSimpleNontestableUnderTestDefinition(vararg actionLines: String?): String {
            return Companion.getSimpleUnderTestDefinition(false, actionLines)
        }

        // A list of attributes and methods ctx objects have
        private val CTX_ATTRIBUTES: com.google.common.collect.ImmutableList<String> =
            com.google.common.collect.ImmutableList.of<String?>(
                "attr",
                "split_attr",
                "executable",
                "file",
                "files",
                "workspace_name",
                "label",
                "fragments",
                "configuration",
                "coverage_instrumented(dep)",
                "features",
                "bin_dir",
                "genfiles_dir",
                "outputs",
                "rule",
                "aspect_ids",
                "var",
                "tokenize('foo')",
                "actions.declare_file('foo.txt')",
                "actions.declare_file('foo.txt', sibling = file)",
                "actions.declare_directory('foo.txt')",
                "actions.declare_directory('foo.txt', sibling = file)",
                "actions.do_nothing(mnemonic = 'foo', inputs = [file])",
                "actions.expand_template(template = file, output = file, substitutions = {})",
                "actions.run(executable = file, outputs = [file])",
                "actions.run_shell(command = 'foo', outputs = [file])",
                "actions.write(file, 'foo')",
                "check_placeholders('foo', [])",
                "build_file_path",
                "runfiles()",
                "resolve_command(command = 'foo')",
                "resolve_tools()"
            )
    }
}
