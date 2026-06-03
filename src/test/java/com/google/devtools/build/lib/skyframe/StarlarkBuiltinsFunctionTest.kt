// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

/** Tests for [StarlarkBuiltinsFunction].  */
@RunWith(JUnit4::class)
class StarlarkBuiltinsFunctionTest : BuildViewTestCase() {
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        // Add a fake rule and top-level symbol to override.
        val builder: ConfiguredRuleClassProvider.Builder =
            Builder()
                .addRuleDefinition(OVERRIDABLE_RULE)
                .addRuleDefinition(JUST_A_RULE)
                .addBzlToplevel("overridable_symbol", "original_value")
                .addBzlToplevel("just_a_symbol", "another_value")
        TestRuleClassProvider.addStandardRules(builder)
        return builder.build()
    }

    // TODO(#11437): Add tests for predeclared env of BUILD (and WORKSPACE?) files, once
    // StarlarkBuiltinsFunction manages that functionality.
    /** Sets up exports.bzl with the given contents and evaluates the `@_builtins`.  */
    @Throws(java.lang.Exception::class)
    private fun evalBuiltins(vararg lines: String?): EvaluationResult<StarlarkBuiltinsValue?> {
        scratch.file("tools/builtins_staging/exports.bzl", lines)
        setBuildLanguageOptions("--experimental_builtins_bzl_path=tools/builtins_staging")

        val key: SkyKey? = StarlarkBuiltinsValue.key()
        return SkyframeExecutorTestUtils.evaluate<T?>(
            getSkyframeExecutor(), key,  /*keepGoing=*/false, reporter
        )
    }

    /**
     * Sets up exports.bzl with the given contents, evaluates the `@_builtins`, and asserts that
     * a BuiltinsFailedException is raised with the given message.
     */
    @Throws(java.lang.Exception::class)
    private fun assertBuiltinsFailure(message: String?, vararg lines: String?) {
        reporter.removeHandler(failFastHandler)
        val result: EvaluationResult<StarlarkBuiltinsValue?> = evalBuiltins(*lines)

        val key: SkyKey? = StarlarkBuiltinsValue.key()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        ErrorInfoSubjectFactory.assertThatErrorInfo(result.getError(key)).isNotTransient()
        val ex: java.lang.Exception? = result.getError(key).getException()
        Truth.assertThat(ex).isInstanceOf(BuiltinsFailedException::class.java)
        Truth.assertThat(ex).hasMessageThat().contains(message)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun successfulEvaluation() {
        val result: EvaluationResult<StarlarkBuiltinsValue?> =
            evalBuiltins(
                "exported_toplevels = {'overridable_symbol': 'new_value'}",
                "exported_rules = {'overridable_rule': 'new_rule'}",
                "exported_to_java = {'for_native_code': 'secret_sauce'}"
            )

        val key: SkyKey? = StarlarkBuiltinsValue.key()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val value: StarlarkBuiltinsValue = result.get(key)

        // Universe symbols are omitted (they're added by the interpreter).
        assertThat(value.predeclaredForBuildBzl).doesNotContainKey("print")
        // General Bazel symbols are present.
        assertThat(value.predeclaredForBuildBzl).containsKey("rule")
        // Non-overridden rule-specific symbols are present.
        assertThat(value.predeclaredForBuildBzl).containsKey("just_a_symbol")
        // Overridden symbol.
        assertThat(value.predeclaredForBuildBzl).containsEntry("overridable_symbol", "new_value")
        // Overridden native field.
        val nativeObject: Structure = value.predeclaredForBuildBzl.get("native") as Structure
        Truth.assertThat(nativeObject.getValue("overridable_rule")).isEqualTo("new_rule")
        Truth.assertThat(nativeObject.getFieldNames()).contains("just_a_rule")

        // Analogous assertions for build files.
        assertThat(value.predeclaredForBuild).doesNotContainKey("print")
        assertThat(value.predeclaredForBuild).containsKey("glob")
        assertThat(value.predeclaredForBuild).containsEntry("overridable_rule", "new_rule")
        assertThat(value.predeclaredForBuild).containsKey("just_a_rule")

        // Stuff for native rules.
        assertThat(value.exportedToJava).containsExactly("for_native_code", "secret_sauce").inOrder()

        // Digest should be same as the exports file.
        val exportsDigest: ByteArray? =
            (SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(),
                StarlarkBuiltinsFunction.EXPORTS_ENTRYPOINT_KEY,  /*keepGoing=*/
                false,
                reporter
            )
                .get(StarlarkBuiltinsFunction.EXPORTS_ENTRYPOINT_KEY) as BzlLoadValue).transitiveDigest
        assertThat(value.transitiveDigest).isEqualTo(exportsDigest)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exportsDictMustExist() {
        assertBuiltinsFailure(
            "Failed to apply declared builtins: expected a 'exported_rules' dictionary to be defined",  //
            "exported_toplevels = {}",
            "# exported_rules missing",
            "exported_to_java = {}"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exportsDictMustBeDict() {
        assertBuiltinsFailure(
            "Failed to apply declared builtins: got NoneType for 'exported_rules dict', want dict",  //
            "exported_toplevels = {}",
            "exported_rules = None",
            "exported_to_java = {}"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exportsDictKeyMustBeString() {
        assertBuiltinsFailure(
            "Failed to apply declared builtins: got dict<int, string> for 'exported_rules dict', want"
                    + " dict<string, unknown>",  //
            "exported_toplevels = {}",
            "exported_rules = {1: 'a'}",
            "exported_to_java = {}"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cannotOverrideGeneralSymbol() {
        assertBuiltinsFailure(
            "Failed to apply declared builtins: Cannot override 'glob' with an injected rule",  //
            "exported_toplevels = {}",  //
            "exported_rules = {'glob': 'new_builtin'}",
            "exported_to_java = {}"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseErrorInExportsHandledGracefully() {
        assertBuiltinsFailure(
            "Failed to load builtins sources: compilation of module 'exports.bzl' (internal) failed",  //
            "exported_toplevels = {}",
            "exported_rules = {}",
            "exported_to_java = {}",
            "asdf asdf  # <-- parse error"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun evalErrorInExportsHandledGracefully() {
        assertBuiltinsFailure(
            "Failed to load builtins sources: initialization of module 'exports.bzl' (internal) failed",  //
            "exported_toplevels = {}",
            "exported_rules = {}",
            "exported_to_java = {}",
            "1 // 0  # <-- dynamic error"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsBzlCannotAccessNative() {
        assertBuiltinsFailure(
            "compilation of module 'exports.bzl' (internal) failed",  //
            "native.overridable_rule",
            "exported_toplevels = {}",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        assertContainsEvent("name 'native' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsBzlCannotAccessRuleSpecificSymbol() {
        assertBuiltinsFailure(
            "compilation of module 'exports.bzl' (internal) failed",  //
            "overridable_symbol",
            "exported_toplevels = {}",
            "exported_rules = {}",
            "exported_to_java = {}"
        )
        assertContainsEvent("name 'overridable_symbol' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun builtinsBzlCanAccessBuiltinsInternalModule() {
        val result: EvaluationResult<StarlarkBuiltinsValue?> =
            evalBuiltins(
                "print(_builtins)",
                "",
                "exported_toplevels = {}",
                "exported_rules = {}",
                "exported_to_java = {}"
            )
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertContainsEvent("<_builtins module>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun regularBzlCannotAccessBuiltinsInternalModule() {
        scratch.file(
            "pkg/BUILD",  //
            "load(':dummy.bzl', 'dummy_symbol')"
        )
        scratch.file(
            "pkg/dummy.bzl",
            """
        _builtins
        dummy_symbol = None
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//pkg:BUILD")
        assertContainsEvent("name '_builtins' is not defined")
    }

    companion object {
        private val OVERRIDABLE_RULE: MockRule = MockRule { MockRule.define("overridable_rule") }
        private val JUST_A_RULE: MockRule = MockRule { MockRule.define("just_a_rule") }
    }
}
