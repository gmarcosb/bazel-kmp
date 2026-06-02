// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.starlark.StarlarkSubrule.getRuleAttrName

@RunWith(JUnit4::class)
class StarlarkSubruleTest : BuildViewTestCase() {
    private val ev: BazelEvaluationTestCase = BazelEvaluationTestCase("//subrule_testing:label")
    private val evOutsideAllowlist: BazelEvaluationTestCase = BazelEvaluationTestCase("//foo:bar")

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleFunctionSymbol_notVisibleInBUILD() {
        scratch.file("foo/BUILD", "subrule")

        checkLoadingPhaseError("//foo", "'subrule' is not defined")
    }

    @org.junit.Test // checks that 'subrule' symbol visibility in bzl files, not whether it's callable
    @Throws(java.lang.Exception::class)
    fun testSubruleFunctionSymbol_isVisibleInBzl() {
        val subruleFunction: Any? = ev.eval("subrule")

        assertNoEvents()
        Truth.assertThat(subruleFunction).isNotNull()
        Truth.assertThat(subruleFunction).isInstanceOf(BuiltinFunction::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleInstantiation_inAllowlistedPackage_succeeds() {
        val subrule: Any? = ev.eval("subrule(implementation = lambda : 0 )")

        Truth.assertThat(subrule).isNotNull()
        Truth.assertThat(subrule).isInstanceOf(StarlarkSubruleApi::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_isCallableOnlyFromRuleOrAspectImplementation() {
        ev.execAndExport("x = subrule(implementation = lambda : 'dummy result')")

        ev.checkEvalErrorContains("x can only be called from a rule or aspect implementation", "x()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_isCallableOnlyAfterExport() {
        ev.checkEvalErrorContains(
            "Invalid subrule hasn't been exported by a bzl file",
            "unexported = [subrule(implementation = lambda: None)]",
            "unexported[0]()"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_ruleMustDeclareSubrule() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        _my_subrule = subrule(implementation = lambda: "")

        def _rule_impl(ctx):
            _my_subrule()

        my_rule = rule(implementation = _rule_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                "Error in _my_subrule: rule 'my_rule' must declare '_my_subrule' in" + " 'subrules'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_attrLengthLimitAppliedOnUserDefinedName() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        _my_subrule = subrule(
          implementation = lambda ctx: "dummy rule result",
          attrs = {
            "_%s": attr.label(default = "//subrule_testing:bar")
          }
        )

        my_rule = rule(implementation = lambda ctx: [], subrules = [_my_subrule])
        
        """
                .trimIndent()
                .formatted("a".repeat(130))
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        cc_binary(name = "bar")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getTarget("//subrule_testing:foo") })

        Truth.assertThat(error).hasMessageThat().contains("name is too long (131 > 128)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_attrLengthLimitIgnoresBzlLabel() {
        // deserialized package does not have a reference to subrules
        initializeSkyframeExecutor( /* doPackageLoadingChecks= */false)

        val longPackageName: String = "a".repeat(130)
        scratch.file("subrule_testing/%s/BUILD".formatted(longPackageName))
        scratch.file(
            "subrule_testing/%s/myrule.bzl".formatted(longPackageName),
            """
        _my_subrule = subrule(
          implementation = lambda ctx: "dummy rule result",
          attrs = {
            "_a": attr.label(default = "//subrule_testing:bar")
          }
        )

        my_rule = rule(implementation = lambda ctx: [], subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//subrule_testing/%s:myrule.bzl", "my_rule")

        my_rule(name = "foo")
        cc_binary(name = "bar")
        
        """
                .trimIndent()
                .formatted(longPackageName)
        )

        getTarget("//subrule_testing:foo")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_childAndParentSubrules() {
        scratch.file(
            "subrule_testing/parent.bzl",
            "_parent_subrule = subrule(implementation = lambda ctx: None)",
            "def _rule_impl(ctx):",
            "  _parent_subrule()",
            "parent_rule = rule(implementation = _rule_impl, extendable = True, subrules ="
                    + " [_parent_subrule])"
        )
        scratch.file(
            "subrule_testing/child.bzl",
            "load('parent.bzl', 'parent_rule')",
            "_child_subrule = subrule(implementation = lambda ctx: None)",
            "def _rule_impl(ctx):",
            "  ctx.super()",
            "  _child_subrule()",
            "child_rule = rule(implementation = _rule_impl, parent = parent_rule, subrules ="
                    + " [_child_subrule])"
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("child.bzl", "child_rule")

        child_rule(name = "foo")
        
        """.trimIndent()
        )

        getConfiguredTarget("//subrule_testing:foo")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_childAndParent_canUseTheSameSubrule() {
        scratch.file(
            "subrule_testing/parent.bzl",
            "parent_subrule = subrule(",
            "  implementation = lambda ctx, _tool: None,",
            "  attrs = {'_tool': attr.label(default = ':tool')}",
            ")",
            "def _rule_impl(ctx):",
            "  parent_subrule()",
            "parent_rule = rule(implementation = _rule_impl, extendable = True, subrules ="
                    + " [parent_subrule])"
        )
        scratch.file(
            "subrule_testing/child.bzl",
            "load('parent.bzl', 'parent_rule', 'parent_subrule')",
            "def _rule_impl(ctx):",
            "  ctx.super()",
            "  parent_subrule()",
            "child_rule = rule(implementation = _rule_impl, parent = parent_rule, subrules ="
                    + " [parent_subrule])"
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("child.bzl", "child_rule")

        child_rule(name = "foo")

        filegroup(name = "tool")
        
        """.trimIndent()
        )

        getConfiguredTarget("//subrule_testing:foo")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_childCantUseParentsSubrule() {
        scratch.file(
            "subrule_testing/parent.bzl",
            "parent_subrule = subrule(implementation = lambda ctx: None)",
            "def _rule_impl(ctx):",
            "  parent_subrule()",
            "parent_rule = rule(implementation = _rule_impl, extendable = True, subrules ="
                    + " [parent_subrule])"
        )
        scratch.file(
            "subrule_testing/child.bzl",
            """
        load("parent.bzl", "parent_rule", "parent_subrule")

        _child_subrule = subrule(implementation = lambda ctx: None)

        def _rule_impl(ctx):
            ctx.super()
            parent_subrule()

        child_rule = rule(implementation = _rule_impl, parent = parent_rule)
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("child.bzl", "child_rule")

        child_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                "Error in parent_subrule: rule 'child_rule' must declare 'parent_subrule' in"
                        + " 'subrules'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_parentCantUseChildsSubrule() {
        scratch.file(
            "subrule_testing/parent.bzl",
            """
        my_subrule = subrule(implementation = lambda ctx: None)

        def _rule_impl(ctx):
            my_subrule()

        parent_rule = rule(implementation = _rule_impl, extendable = True)
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/child.bzl",
            "load('parent.bzl', 'parent_rule', 'my_subrule')",
            "def _rule_impl(ctx):",
            "  ctx.super()",
            "  my_subrule()",
            "child_rule = rule(implementation = _rule_impl, parent = parent_rule, subrules ="
                    + " [my_subrule])"
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("child.bzl", "child_rule")

        child_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                "Error in my_subrule: rule 'parent_rule' must declare 'my_subrule' in 'subrules'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_aspectMustDeclareSubrule() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        _my_subrule = subrule(implementation = lambda ctx: "dummy aspect result")

        def _aspect_impl(ctx, target):
            res = _my_subrule()

        _my_aspect = aspect(implementation = _aspect_impl)

        my_rule = rule(
            implementation = lambda ctx: [],
            attrs = {"dep": attr.label(mandatory = True, aspects = [_my_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        load("myrule.bzl", "my_rule")

        java_library(name = "bar")

        my_rule(
            name = "foo",
            dep = "bar",
        )
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                "Error in _my_subrule: aspect '//subrule_testing:myrule.bzl%_my_aspect' must"
                        + " declare '_my_subrule' in 'subrules'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleCallingUndeclaredSibling_fails() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule1_impl(ctx):
            return "result from subrule1"

        _my_subrule1 = subrule(implementation = _subrule1_impl)

        def _subrule2_impl(ctx):
            return _my_subrule1()

        _my_subrule2 = subrule(implementation = _subrule2_impl)

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule2()
            return [MyInfo(result = res)]

        my_rule = rule(_rule_impl, subrules = [_my_subrule2, _my_subrule1])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error).isNotNull()
        Truth.assertThat(error)
            .hasMessageThat()
            .contains("subrule _my_subrule2 must declare _my_subrule1 in 'subrules'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_implementationMustAcceptSubruleContext() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        _my_subrule = subrule(implementation = lambda: "")

        def _rule_impl(ctx):
            _my_subrule()

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains("Error: lambda() does not accept positional arguments, but got 1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_isCallableFromRule() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        _my_subrule = subrule(implementation = lambda ctx: "dummy rule result")

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val provider: StructImpl =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")

        assertThat(provider).isNotNull()
        assertThat(provider.getValue("result")).isEqualTo("dummy rule result")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_isCallableFromAspect() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        _my_subrule = subrule(implementation = lambda ctx: "dummy aspect result")

        MyInfo = provider()

        def _aspect_impl(ctx, target):
            res = _my_subrule()
            return MyInfo(result = res)

        _my_aspect = aspect(implementation = _aspect_impl, subrules = [_my_subrule])

        my_rule = rule(
            implementation = lambda ctx: [ctx.attr.dep[MyInfo]],
            attrs = {"dep": attr.label(mandatory = True, aspects = [_my_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        load("myrule.bzl", "my_rule")

        java_library(name = "bar")

        my_rule(
            name = "foo",
            dep = "bar",
        )
        
        """.trimIndent()
        )

        val provider: StructImpl =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")

        assertThat(provider).isNotNull()
        assertThat(provider.getValue("result")).isEqualTo("dummy aspect result")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_subruleContextExposesRuleLabel() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            return "called in: " + str(ctx.label)

        _my_subrule = subrule(implementation = _subrule_impl)

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val provider: StructImpl =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")

        assertThat(provider).isNotNull()
        assertThat(provider.getValue("result")).isEqualTo("called in: @@//subrule_testing:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrule_subruleContextExposesActionsApi() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.write(out, "subrule file content")
            return out

        _my_subrule = subrule(implementation = _subrule_impl)

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val artifact: Artifact =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("result") as Artifact

        assertThat(artifact).isNotNull()
        assertThat(artifact.getFilename()).isEqualTo("foo.out")
        assertThat((getGeneratingAction(artifact) as FileWriteAction).getFileContents())
            .isEqualTo("subrule file content")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleActions_run_doesNotAllowSettingToolchain() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.run(toolchain = "foo", executable = "/path/to/tool", outputs = [out])

        _my_subrule = subrule(implementation = _subrule_impl)

        MyInfo = provider()

        def _rule_impl(ctx):
            _my_subrule()

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error).hasMessageThat().contains("'toolchain' may not be specified in subrules")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleActions_run_doesNotAllowSettingExecGroup() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.run(exec_group = "foo", executable = "/path/to/tool", outputs = [out])

        _my_subrule = subrule(implementation = _subrule_impl)

        MyInfo = provider()

        def _rule_impl(ctx):
            _my_subrule()

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error).hasMessageThat().contains("'exec_group' may not be specified in subrules")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleContext_cannotBeUsedOutsideImplementationFunction() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            return ctx

        _my_subrule = subrule(implementation = _subrule_impl)

        def _rule_impl(ctx):
            subrule_ctx = _my_subrule()
            subrule_ctx.label

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                "Error: cannot access field or method 'label' of subrule context outside of its own"
                        + " implementation function"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleContext_cannotBeUsedInSubruleImplementation() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx, rule_ctx):
            rule_ctx.label

        _my_subrule = subrule(implementation = _subrule_impl)

        def _rule_impl(ctx):
            subrule_ctx = _my_subrule(ctx)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                "Error: cannot access field or method 'label' of rule context for"
                        + " '//subrule_testing:foo' outside of its own rule implementation function"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_publicAttributesAreNotPermitted() {
        ev.checkEvalErrorContains(
            "illegal attribute name 'foo': subrules may only define private attributes",
            "subrule(implementation = lambda: None, attrs = {'foo': attr.string()})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_computedDefaultsAreNotPermitted() {
        ev.checkEvalErrorContains(
            "for attribute '_foo': subrules cannot define computed defaults.",
            "subrule(",
            "  implementation = lambda: None,",
            "  attrs = {'_foo': attr.label(default = lambda: '')}",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_onlyLabelsOrLabelListsPermitted() {
        ev.checkEvalErrorContains(
            "bad type for attribute '_foo': subrule attributes may only be label or lists of labels.",
            "subrule(",
            "  implementation = lambda: None,",
            "  attrs = {'_foo': attr.int()}",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_attributeMustHaveDefaultValue() {
        ev.checkEvalErrorContains(
            "for attribute '_foo': no default value specified",
            "subrule(",
            "  implementation = lambda: None,",
            "  attrs = {'_foo': attr.label()}",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_cannotHaveStarlarkTransitions() {
        ev.checkEvalErrorContains(
            "bad cfg for attribute '_foo': subrules may only have target/exec attributes.",
            "my_transition = transition(implementation = lambda: None, inputs = [], outputs = [])",
            "_my_subrule = subrule(",
            "  implementation = lambda: None,",
            "  attrs = {'_foo': attr.label(cfg = my_transition)}",
            ")"
        )
    }

    /**
     * A test-only transition used to test native transitions on subrules. Must implement [ ] so that it is allowed by `rule`.
     */
    private class NativeTransition

        : TransitionFactory<AttributeTransitionData?>, ConfigurationTransitionApi {
        public override fun create(data: AttributeTransitionData?): ConfigurationTransition? {
            return null
        }

        public override fun transitionType(): TransitionType {
            return TransitionType.ATTRIBUTE
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_cannotHaveNativeTransitions() {
        ev.update("native_transition", NativeTransition())
        ev.checkEvalErrorContains(
            "bad cfg for attribute '_foo': subrules may only have target/exec attributes.",
            "_my_subrule = subrule(",
            "  implementation = lambda: None,",
            "  attrs = {'_foo': attr.label(cfg = native_transition)}",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_notVisibleInRuleCtx() {
        scratch.file("default/BUILD", "genrule(name = 'default', outs = ['a'], cmd = '')")
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            return

        _my_subrule = subrule(
            implementation = _subrule_impl,
            attrs = {"_foo": attr.label(default = "//default")},
        )
        MyInfo = provider()

        def _rule_impl(ctx):
            res = dir(ctx.attr)
            return MyInfo(result = res)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val ruleClassAttributes: com.google.common.collect.ImmutableList<String?>? =
            getRuleContext(getConfiguredTarget("//subrule_testing:foo"))
                .getRule()
                .getRuleClassObject()
                .getAttributeProvider()
                .getAttributes()
                .stream()
                .map(Attribute::getName)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        val attributesVisibleToStarlark: com.google.common.collect.ImmutableList<String?>? =
            net.starlark.java.eval.Sequence.cast<T?>(
                getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                    .getValue("result"),
                String::class.java,
                ""
            )
                .getImmutableList()
        val ruleAttrName: String? =
            getRuleAttrName(
                Label.parseCanonical("//subrule_testing:myrule.bzl"),
                "_my_subrule",
                "_foo",
                AttributeValueSource.DIRECT
            )

        Truth.assertThat(ruleClassAttributes).contains(ruleAttrName)
        Truth.assertThat(attributesVisibleToStarlark).doesNotContain(ruleAttrName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_notVisibleInAspectCtx() {
        scratch.file("default/BUILD", "genrule(name = 'default', outs = ['a'], cmd = '')")
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        _my_subrule = subrule(
            implementation = lambda: None,
            attrs = {"_foo": attr.label(default = "//default")},
        )
        MyInfo = provider()

        def _aspect_impl(target, ctx):
            res = dir(ctx.attr)
            return MyInfo(result = res)

        my_aspect = aspect(implementation = _aspect_impl, subrules = [_my_subrule])

        def _rule_impl(ctx):
            return ctx.attr.dep[MyInfo]

        my_rule = rule(
            implementation = _rule_impl,
            attrs = {"dep": attr.label(aspects = [my_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(
            name = "foo",
            dep = "//default",
        )
        
        """.trimIndent()
        )

        val attributesVisibleToStarlark: com.google.common.collect.ImmutableList<String?>? =
            net.starlark.java.eval.Sequence.cast<T?>(
                getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                    .getValue("result"),
                String::class.java,
                ""
            )
                .getImmutableList()
        val aspectAttributes: com.google.common.collect.ImmutableMap<String?, Attribute?>? =
            (getAspect("//subrule_testing:myrule.bzl%my_aspect") as AspectValue)
                .getAspect()
                .getDefinition()
                .getAttributes()
        val ruleAttrName: String? =
            getRuleAttrName(
                Label.parseCanonical("//subrule_testing:myrule.bzl"),
                "_my_subrule",
                "_foo",
                AttributeValueSource.DIRECT
            )

        Truth.assertThat(aspectAttributes).containsKey(ruleAttrName)
        Truth.assertThat(attributesVisibleToStarlark).doesNotContain(ruleAttrName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_overridingImplicitAttributeValueFails() {
        scratch.file("default/BUILD", "genrule(name = 'default', outs = ['a'], cmd = '')")
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx, _foo):
            return

        _my_subrule = subrule(
            implementation = _subrule_impl,
            attrs = {"_foo": attr.label(default = "//default")},
        )

        def _rule_impl(ctx):
            res = _my_subrule(_foo = "//override")
            return []

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                "Error in _my_subrule: got invalid named argument: '_foo' is an implicit dependency and"
                        + " cannot be overridden"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_implicitLabelDepsAreResolvedToTargets_inRule() {
        scratch.file(
            "some/pkg/BUILD",  //
            "genrule(name = 'tool', cmd = '', outs = ['tool.exe'])"
        )
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx, _tool):
            return _tool

        _my_subrule = subrule(
            implementation = _subrule_impl,
            attrs = {"_tool": attr.label(default = "//some/pkg:tool")},
        )

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val provider: StructImpl =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")

        assertThat(provider).isNotNull()
        val value: Any = provider.getValue("result")
        Truth.assertThat(value).isInstanceOf(ConfiguredTarget::class.java)
        assertThat((value as ConfiguredTarget).getLabel().toString()).isEqualTo("//some/pkg:tool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_implicitLabelDepsAreResolvedToTargets_inAspect() {
        scratch.file(
            "some/pkg/BUILD",  //
            "genrule(name = 'tool', cmd = '', outs = ['tool.exe'])"
        )
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx, _tool):
            return _tool

        _my_subrule = subrule(
            implementation = _subrule_impl,
            attrs = {"_tool": attr.label(default = "//some/pkg:tool")},
        )

        MyInfo = provider()

        def _aspect_impl(ctx, target):
            res = _my_subrule()
            return [MyInfo(result = res)]

        _my_aspect = aspect(implementation = _aspect_impl, subrules = [_my_subrule])

        my_rule = rule(
            implementation = lambda ctx: [ctx.attr.dep[MyInfo]],
            attrs = {"dep": attr.label(mandatory = True, aspects = [_my_aspect])},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")
        filegroup(name = 'bar')
        my_rule(name = "foo", dep = ":bar")
        
        """.trimIndent()
        )

        val provider: StructImpl =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")

        assertThat(provider).isNotNull()
        val value: Any = provider.getValue("result")
        Truth.assertThat(value).isInstanceOf(ConfiguredTarget::class.java)
        assertThat((value as ConfiguredTarget).getLabel().toString()).isEqualTo("//some/pkg:tool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_singleFileLabelAttributesAreResolvedToFile() {
        scratch.file(
            "some/pkg/BUILD",  //
            "genrule(name = 'tool', cmd = '', outs = ['tool.exe'])"
        )
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx, _tool):
            return _tool

        _my_subrule = subrule(
            implementation = _subrule_impl,
            attrs = {"_tool": attr.label(allow_single_file = True, default = "//some/pkg:tool")},
        )

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val provider: StructImpl =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")

        assertThat(provider).isNotNull()
        val value: Any = provider.getValue("result")
        Truth.assertThat(value).isInstanceOf(Artifact::class.java)
        assertThat((value as Artifact).getRootRelativePathString()).isEqualTo("some/pkg/tool.exe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttr_executableAttrIsPassedAsFilesToRun() {
        scratch.file(
            "my/BUILD",  //
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(name = 'tool')
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx, _tool):
            return _tool

        _my_subrule = subrule(
            implementation = _subrule_impl,
            attrs = {"_tool": attr.label(default = "//my:tool", executable = True, cfg = "exec")},
        )

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val result: Any =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("result")

        Truth.assertThat(result).isInstanceOf(FilesToRunProvider::class.java)
        assertThat((result as FilesToRunProvider).getExecutable().getRootRelativePathString())
            .isEqualTo("my/tool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAction_executableMustBeFilesToRunProvider() {
        scratch.file(
            "my/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(name = 'tool')
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx, _tool):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.run(executable = _tool.executable, outputs = [out])
            return out

        _my_subrule = subrule(
            implementation = _subrule_impl,
            attrs = {"_tool": attr.label(default = "//my:tool", executable = True, cfg = "exec")},
        )

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains("Error in run: for 'executable', expected FilesToRunProvider, got File")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleAttrs_lateBoundDefaultsAreResolved() {
        scratch.file(
            "my/BUILD",  //
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(name = 'tool')
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx, _tool):
            return _tool

        _my_subrule = subrule(
            implementation = _subrule_impl,
            attrs = {"_tool": attr.label(
                default = configuration_field(fragment = "coverage", name = "output_generator"),
            )},
        )

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(implementation = _rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )
        // TODO: b/293304174 - use a custom fragment instead of coverage
        useConfiguration("--collect_code_coverage", "--coverage_output_generator=//my:tool")

        val provider: StructImpl =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")

        assertThat(provider).isNotNull()
        val value: Any = provider.getValue("result")
        Truth.assertThat(value).isInstanceOf(ConfiguredTarget::class.java)
        assertThat((value as ConfiguredTarget).getLabel().toString()).isEqualTo("//my:tool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleToolchains_cannotRequireMoreThanOne() {
        ev.checkEvalErrorContains(
            "subrules may require at most 1 toolchain",
            "_my_subrule = subrule(",
            "  implementation = lambda: None,",
            "  toolchains = ['//t1', '//t2'],",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleToolchains_cannotAccessUnrequestedToolchain() {
        useConfiguration("--incompatible_auto_exec_groups")
        scratch.file(
            "subrule_testing/myrule.bzl",
            "def _subrule_impl(ctx):",
            "  ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "']",
            "_my_subrule = subrule(",
            "  implementation = _subrule_impl,",
            ")",
            "",
            "def _rule_impl(ctx):",
            "  _my_subrule()",
            "",
            "my_rule = rule(",
            "  implementation = _rule_impl,",
            "  subrules = [_my_subrule],",
            ")"
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            TestConstants.JAVA_TOOLCHAIN_TYPE + " was requested but only types [] are configured",
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleToolchains_cannotAccessToolchainFromRule() {
        useConfiguration("--incompatible_auto_exec_groups")
        scratch.file(
            "subrule_testing/myrule.bzl",
            "def _subrule_impl(ctx):",
            "  ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "']",
            "_my_subrule = subrule(",
            "  implementation = _subrule_impl,",
            ")",
            "",
            "def _rule_impl(ctx):",
            "  _my_subrule()",
            "",
            "my_rule = rule(",
            "  implementation = _rule_impl,",
            "  subrules = [_my_subrule],",
            "  toolchains = ['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            ")"
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            TestConstants.JAVA_TOOLCHAIN_TYPE + " was requested but only types [] are configured",
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleToolchains_requestedToolchainIsResolved_inRule() {
        useConfiguration("--incompatible_auto_exec_groups")
        scratch.file(
            "subrule_testing/myrule.bzl",
            "def _subrule_impl(ctx):",
            "  return ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "']",
            "_my_subrule = subrule(",
            "  implementation = _subrule_impl,",
            "  toolchains = ['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            ")",
            "MyInfo = provider()",
            "def _rule_impl(ctx):",
            "  return [MyInfo(result = _my_subrule())]",
            "",
            "my_rule = rule(",
            "  implementation = _rule_impl,",
            "  subrules = [_my_subrule],",
            ")"
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val toolchainInfo: ToolchainInfo =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("result", ToolchainInfo::class.java)

        assertThat(toolchainInfo).isNotNull()
        assertThat(toolchainInfo.getValue("java", StarlarkInfo::class.java).getProvider().getKey())
            .isEqualTo(JavaToolchainProvider.PROVIDER.getKey())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleToolchains_requstedToolchainIsResolved_inAspect() {
        useConfiguration("--incompatible_auto_exec_groups")
        scratch.file("default/BUILD", "genrule(name = 'default', outs = ['a'], cmd = '')")
        scratch.file(
            "subrule_testing/myrule.bzl",
            "def _subrule_impl(ctx):",
            "  return ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "']",
            "_my_subrule = subrule(",
            "  implementation = _subrule_impl,",
            "  toolchains = ['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            ")",
            "MyInfo=provider()",
            "def _aspect_impl(target, ctx):",
            "  return MyInfo(result = _my_subrule())",
            "my_aspect = aspect(implementation = _aspect_impl, subrules = [_my_subrule])",
            "def _rule_impl(ctx):",
            "  return ctx.attr.dep[MyInfo]",
            "my_rule = rule(",
            "  implementation = _rule_impl,",
            "  attrs = {'dep' : attr.label(aspects = [my_aspect])}",
            ")"
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(
            name = "foo",
            dep = "//default",
        )
        
        """.trimIndent()
        )

        val toolchainInfo: ToolchainInfo =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("result", ToolchainInfo::class.java)

        assertThat(toolchainInfo).isNotNull()
        assertThat(toolchainInfo.getValue("java", StarlarkInfo::class.java).getProvider().getKey())
            .isEqualTo(JavaToolchainProvider.PROVIDER.getKey())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleToolchains_noToolchainIsSuppliedToAction() {
        useConfiguration("--incompatible_auto_exec_groups")
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".out")
            ctx.actions.run(outputs = [out], executable = "/bin/ls", tools = [depset()])
            return out

        _my_subrule = subrule(
            implementation = _subrule_impl,
        )

        def _rule_impl(ctx):
            return [DefaultInfo(files = depset([_my_subrule()]))]

        my_rule = rule(
            implementation = _rule_impl,
            subrules = [_my_subrule],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//subrule_testing:foo")
        val action: Action = getGeneratingAction(target, "subrule_testing/foo.out")

        assertThat(action).isNotNull()
        assertThat(action.getOwner()).isEqualTo(getRuleContext(target).getActionOwner())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleToolchains_requestedToolchainIsSuppliedToAction() {
        useConfiguration("--incompatible_auto_exec_groups")
        scratch.file(
            "subrule_testing/myrule.bzl",
            "def _subrule_impl(ctx):",
            "  out = ctx.actions.declare_file(ctx.label.name + '.out')",
            "  ctx.actions.run(outputs = [out], executable = '/bin/ls', tools = [depset()])",
            "  return out",
            "_my_subrule = subrule(",
            "  implementation = _subrule_impl,",
            "  toolchains = ['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            ")",
            "def _rule_impl(ctx):",
            "  return [DefaultInfo(files = depset([_my_subrule()]))]",
            "",
            "my_rule = rule(",
            "  implementation = _rule_impl,",
            "  subrules = [_my_subrule],",
            ")"
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//subrule_testing:foo")
        val action: Action = getGeneratingAction(target, "subrule_testing/foo.out")

        assertThat(action).isNotNull()
        assertThat(action.getOwner())
            .isEqualTo(getRuleContext(target).getActionOwner(TestConstants.JAVA_TOOLCHAIN_TYPE))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleFragments_errorForInvalidFragments() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            return ctx.fragments.foobar

        _my_subrule = subrule(
            implementation = _subrule_impl,
            fragments = ["java", "cpp"],
        )
        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(_rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val assertionError: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(assertionError)
            .hasMessageThat()
            .contains(
                "There is no configuration fragment named 'foobar'. Available fragments: 'java',"
                        + " 'cpp'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleFragments_onlyDeclaredFragmentsAreVisible() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            return dir(ctx.fragments)

        _my_subrule = subrule(
            implementation = _subrule_impl,
            fragments = ["cpp", "python"],
        )
        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(_rule_impl, subrules = [_my_subrule], fragments = ["java"])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val fragments: net.starlark.java.eval.Sequence<String?>? =
            net.starlark.java.eval.Sequence.cast<T?>(
                getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                    .getValue("result"),
                String::class.java,
                "ctx.fragments"
            )

        Truth.assertThat(fragments).isNotNull()
        Truth.assertThat(fragments).containsExactly("cpp", "python")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleFragments_ruleCannotAccessSubruleFragments() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            pass

        _my_subrule = subrule(
            implementation = _subrule_impl,
            fragments = ["cpp"],
        )

        def _rule_impl(ctx):
            ctx.fragments.cpp
            return []

        my_rule = rule(_rule_impl, subrules = [_my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val assertionError: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(assertionError)
            .hasMessageThat()
            .contains("my_rule has to declare 'cpp' as a required fragment in order to access it")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleFragments_canAccessDeclaredFragments() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            return ctx.fragments.cpp

        _my_subrule = subrule(
            implementation = _subrule_impl,
            fragments = ["cpp"],
        )
        MyInfo = provider()

        def _rule_impl(ctx):
            res = _my_subrule()
            return MyInfo(result = res)

        my_rule = rule(_rule_impl, subrules = [_my_subrule], fragments = ["java"])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val fragment: CppConfigurationApi<*>? =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("result", CppConfigurationApi::class.java)

        Truth.assertThat(fragment).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubruleFragments_mustDeclareFragmentsIfAccessed() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _subrule_impl(ctx):
            ctx.fragments.java

        _my_subrule = subrule(
            implementation = _subrule_impl,
            fragments = ["cpp", "python"],
        )

        def _rule_impl(ctx):
            res = _my_subrule()
            return []

        my_rule = rule(_rule_impl, subrules = [_my_subrule], fragments = ["java"])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val assertionError: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(assertionError)
            .hasMessageThat()
            .contains("_my_subrule has to declare 'java' as a required fragment in order to access it")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveSubrules_subruleMustDeclareCalledSubrule() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _A_impl(ctx):
            return "from subruleA"

        _A = subrule(implementation = _A_impl)

        def _B_impl(ctx):
            return _A()

        _B = subrule(implementation = _B_impl)

        def _rule_impl(ctx):
            res = _B()

        my_rule = rule(_rule_impl, subrules = [_B])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val assertionError: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(assertionError)
            .hasMessageThat()
            .contains("Error in _A: subrule _B must declare _A in 'subrules'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveSubrules_ruleCannotCallUndeclaredTransitiveSubrule() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _A_impl(ctx):
            return "from subruleA"

        _A = subrule(implementation = _A_impl)

        def _B_impl(ctx):
            return _A()

        _B = subrule(implementation = _B_impl, subrules = [_A])

        def _rule_impl(ctx):
            _A()

        my_rule = rule(_rule_impl, subrules = [_B])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val assertionError: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(assertionError)
            .hasMessageThat()
            .contains("rule 'my_rule' must declare '_A' in 'subrules'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveSubrules_subruleCanCallDeclaredSubrule() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _A_impl(ctx):
            return "from subruleA"

        _A = subrule(implementation = _A_impl)

        def _B_impl(ctx):
            return _A()

        _B = subrule(implementation = _B_impl, subrules = [_A])

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _B()
            return MyInfo(result = res)

        my_rule = rule(_rule_impl, subrules = [_B])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val result: String? =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("result", String::class.java)

        Truth.assertThat(result).isEqualTo("from subruleA")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveSubrules_arbitrarilyLongTransitiveChainsAreResolved() {
        scratch.file("a/BUILD", "genrule(name = 'tool', cmd = '', outs = ['tool.out'])")
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _A_impl(ctx, _tool):
            return "tool name: " + _tool.label.name

        _A = subrule(implementation = _A_impl, attrs = {"_tool": attr.label(default = "//a:tool")})
        _B = subrule(implementation = lambda ctx: _A(), subrules = [_A])
        _C = subrule(implementation = lambda ctx: _B(), subrules = [_B])
        _D = subrule(implementation = lambda ctx: _C(), subrules = [_C])

        MyInfo = provider()

        def _rule_impl(ctx):
            return MyInfo(result = _D())

        my_rule = rule(_rule_impl, subrules = [_D])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val result: String? =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("result", String::class.java)

        Truth.assertThat(result).isEqualTo("tool name: tool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveSubrules_ruleAndSubruleCanHaveCommonSubruleDependency() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _A_impl(ctx):
            return "from subruleA"

        _A = subrule(implementation = _A_impl)

        def _B_impl(ctx):
            _A()
            return "from subruleB"

        _B = subrule(implementation = _B_impl, subrules = [_A])

        MyInfo = provider()

        def _rule_impl(ctx):
            resA = _A()
            resB = _B()
            return MyInfo(resA = resA, resB = resB)

        my_rule = rule(_rule_impl, subrules = [_A, _B])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val resA: String? =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("resA", String::class.java)
        val resB: String? =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("resB", String::class.java)

        Truth.assertThat(resA).isEqualTo("from subruleA")
        Truth.assertThat(resB).isEqualTo("from subruleB")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveSubrules_callerSubruleCtxIsLocked() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _A_impl(ctx, ctxB):
            return ctxB.label

        _A = subrule(implementation = _A_impl)

        def _B_impl(ctx):
            return _A(ctx)

        _B = subrule(implementation = _B_impl, subrules = [_A])

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _B()
            return MyInfo(result = res)

        my_rule = rule(_rule_impl, subrules = [_B])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val error: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//subrule_testing:foo") })

        Truth.assertThat(error)
            .hasMessageThat()
            .contains(
                "cannot access field or method 'label' of subrule context outside of its own"
                        + " implementation function"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveSubrules_callerSubruleCtxIsUnlockedUponResumption() {
        scratch.file(
            "subrule_testing/myrule.bzl",
            """
        def _A_impl(ctx):
            return "from A"

        _A = subrule(implementation = _A_impl)

        def _B_impl(ctx):
            _A()
            return "from B: " + ctx.label.name

        _B = subrule(implementation = _B_impl, subrules = [_A])

        MyInfo = provider()

        def _rule_impl(ctx):
            res = _B()
            return MyInfo(result = res)

        my_rule = rule(_rule_impl, subrules = [_B])
        
        """.trimIndent()
        )
        scratch.file(
            "subrule_testing/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        val result: String? =
            getProvider("//subrule_testing:foo", "//subrule_testing:myrule.bzl", "MyInfo")
                .getValue("result", String::class.java)

        Truth.assertThat(result).isEqualTo("from B: foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrulesParamForRule_isPrivateAPI() {
        setBuildLanguageOptions("--noexperimental_rule_extension_api")
        scratch.file(
            "foo/myrule.bzl",
            """
        def _impl(ctx):
            pass
        my_subrule = subrule(implementation = _impl)
        my_rule = rule(_impl, subrules = [my_subrule])
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load("myrule.bzl", "my_rule")

        my_rule(name = "foo")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        reporter.addHandler(ev.getEventCollector())
        getConfiguredTarget("//foo")

        ev.assertContainsError("Non-allowlisted attempt to use subrules.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrulesParamForAspect_isPrivateAPI() {
        evOutsideAllowlist.setSemantics("--noexperimental_rule_extension_api")
        evOutsideAllowlist.checkEvalErrorContains(
            "'//foo:bar' cannot use private API", "aspect(implementation = lambda: 0, subrules = [1])"
        )
    }

    @Throws(LabelSyntaxException::class)
    private fun getProvider(targetLabel: String?, providerLabel: String?, providerName: String?): StructImpl {
        val target: ConfiguredTarget? = getConfiguredTarget(targetLabel)
        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical(providerLabel)), providerName)
        return target.get(key) as StructImpl
    }
}
