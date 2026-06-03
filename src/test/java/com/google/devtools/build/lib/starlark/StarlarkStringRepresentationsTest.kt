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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Tests for string representations of Starlark objects.  */
@RunWith(JUnit4::class)
class StarlarkStringRepresentationsTest : BuildViewTestCase() {
    /**
     * Evaluates `code` in the loading phase in a .bzl file
     * 
     * @param code The code to execute
     * @param definition Additional code to define necessary variables
     */
    @Throws(java.lang.Exception::class)
    private fun starlarkLoadingEval(code: String?, definition: String? = ""): Any {
        scratch.overwriteFile(
            "eval/BUILD",
            """
        load(":eval.bzl", "eval")

        eval(name = "eval")
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "eval/eval.bzl",
            definition,
            String.format("x = %s", code),  // Should be placed here to execute during the loading phase
            "Info = provider()",
            "def _impl(ctx):",
            "  return Info(result = x)",
            "eval = rule(implementation = _impl)"
        )
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter,
            Builder()
                .modify(PathFragment.create("eval/BUILD"))
                .modify(PathFragment.create("eval/eval.bzl"))
                .build(),
            Root.fromPath(rootDirectory)
        )

        val target: ConfiguredTarget = getConfiguredTarget("//eval")
        return getStarlarkProvider(target, "Info").getValue("result")
    }

    /**
     * Evaluates `code` in the loading phase in a BUILD file. `code` must return a string.
     * 
     * @param code The code to execute
     */
    @Throws(java.lang.Exception::class)
    private fun starlarkLoadingEvalInBuildFile(code: String?): Any {
        scratch.overwriteFile(
            "eval/BUILD",
            "load(':eval.bzl', 'eval')",
            String.format("eval(name='eval', param = %s)", code)
        )
        scratch.overwriteFile(
            "eval/eval.bzl",
            """
        Info = provider()
        def _impl(ctx):
            return Info(result = ctx.attr.param)

        eval = rule(implementation = _impl, attrs = {"param": attr.string()})
        
        """.trimIndent()
        )
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter,
            Builder()
                .modify(PathFragment.create("eval/BUILD"))
                .modify(PathFragment.create("eval/eval.bzl"))
                .build(),
            Root.fromPath(rootDirectory)
        )

        val target: ConfiguredTarget = getConfiguredTarget("//eval")
        return getStarlarkProvider(target, "Info").getValue("result")
    }

    /**
     * Asserts that all 5 different ways to convert an object to a string of `expression`
     * (`str`, `repr`, `'%s'`, `'%r'`, `'{}'.format` return the correct
     * `representation`. Not applicable for objects that have different `str` and `repr` representations.
     * 
     * @param expression the expression to evaluate a string representation of
     * @param representation desired string representation
     */
    @Throws(java.lang.Exception::class)
    private fun assertStringRepresentationInBuildFile(
        expression: String?, representation: String?
    ) {
        Truth.assertThat(starlarkLoadingEvalInBuildFile(String.format("str(%s)", expression)))
            .isEqualTo(representation)
        Truth.assertThat(starlarkLoadingEvalInBuildFile(String.format("repr(%s)", expression)))
            .isEqualTo(representation)
        Truth.assertThat(starlarkLoadingEvalInBuildFile(String.format("'%%s' %% (%s,)", expression)))
            .isEqualTo(representation)
        Truth.assertThat(starlarkLoadingEvalInBuildFile(String.format("'%%r' %% (%s,)", expression)))
            .isEqualTo(representation)
        Truth.assertThat(starlarkLoadingEvalInBuildFile(String.format("'{}'.format(%s)", expression)))
            .isEqualTo(representation)
    }

    /**
     * Asserts that all 5 different ways to convert an object to a string of `expression`
     * (`str`, `repr`, `'%s'`, `'%r'`, `'{}'.format` return the correct
     * `representation`. Not applicable for objects that have different `str` and `repr` representations.
     * 
     * @param definition optional definition required to evaluate the `expression`
     * @param expression the expression to evaluate a string representation of
     * @param representation desired string representation
     */
    @Throws(java.lang.Exception::class)
    private fun assertStringRepresentation(
        definition: String?, expression: String?, representation: String?
    ) {
        Truth.assertThat(starlarkLoadingEval(String.format("str(%s)", expression), definition))
            .isEqualTo(representation)
        Truth.assertThat(starlarkLoadingEval(String.format("repr(%s)", expression), definition))
            .isEqualTo(representation)
        Truth.assertThat(starlarkLoadingEval(String.format("'%%s' %% (%s,)", expression), definition))
            .isEqualTo(representation)
        Truth.assertThat(starlarkLoadingEval(String.format("'%%r' %% (%s,)", expression), definition))
            .isEqualTo(representation)
        Truth.assertThat(starlarkLoadingEval(String.format("'{}'.format(%s)", expression), definition))
            .isEqualTo(representation)
    }

    @Throws(java.lang.Exception::class)
    private fun assertStringRepresentation(expression: String?, representation: String?) {
        assertStringRepresentation("", expression, representation)
    }

    /**
     * Creates a set of BUILD and .bzl files that gathers objects of many different types available in
     * Starlark and creates their string representations by calling `str` and `repr` on them. The
     * strings are available in the configured target for //test/starlark:check
     */
    @Throws(java.lang.Exception::class)
    private fun generateFilesToTestStrings() {
        // Generate string representations of Starlark rule contexts, targets, and files.
        // Objects are gathered in the implementation of the `check` rule.
        // prepare_params(objects) converts a dict of objects to a dict of their string representations.

        scratch.file(
            "test/starlark/rules.bzl",
            """
        aspect_ctx_provider = provider()

        def prepare_params(objects):
            params = {}
            for k, v in objects.items():
                params[k + "_str"] = str(v)
                params[k + "_repr"] = repr(v)
                params[k + "_format"] = "{}".format(v)
                params[k + "_str_perc"] = "%s" % (v,)
                params[k + "_repr_perc"] = "%r" % (v,)
            return params

        def _impl_aspect(target, ctx):
            return [aspect_ctx_provider(ctx = ctx, rule = ctx.rule)]

        my_aspect = aspect(implementation = _impl_aspect)

        def _impl(ctx):
            pass

        dep = rule(implementation = _impl)

        def _genfile_impl(ctx):
            ctx.actions.write(output = ctx.outputs.my_output, content = "foo")

        genfile = rule(
            implementation = _genfile_impl,
            outputs = {"my_output": "%{name}.txt"},
        )
        CheckInfo = provider()
        def _check_impl(ctx):
            source_file = ctx.attr.srcs[0].files.to_list()[0]
            generated_file = ctx.attr.srcs[1].files.to_list()[0]
            objects = {
                "target": ctx.attr.deps[0],
                "alias_target": ctx.attr.deps[1],
                "aspect_target": ctx.attr.asp_deps[0],
                "input_target": ctx.attr.srcs[0],
                "output_target": ctx.attr.srcs[1],
                "rule_ctx": ctx,
                "aspect_ctx": ctx.attr.asp_deps[0][aspect_ctx_provider].ctx,
                "aspect_ctx.rule": ctx.attr.asp_deps[0][aspect_ctx_provider].rule,
                "source_file": source_file,
                "generated_file": generated_file,
                "source_root": source_file.root,
                "generated_root": generated_file.root,
            }
            return CheckInfo(**prepare_params(objects))

        check = rule(
            implementation = _check_impl,
            attrs = {
                "deps": attr.label_list(),
                "asp_deps": attr.label_list(aspects = [my_aspect]),
                "srcs": attr.label_list(allow_files = True),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/starlark/BUILD",
            """
        load(":rules.bzl", "check", "dep", "genfile")

        dep(name = "foo")

        dep(name = "bar")

        alias(
            name = "foobar",
            actual = ":foo",
        )

        genfile(name = "output")

        check(
            name = "check",
            srcs = [
                "input.txt",
                "output.txt",
            ],
            asp_deps = [":bar"],
            deps = [
                ":foo",
                ":foobar",
            ],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_strings() {
        Truth.assertThat(starlarkLoadingEval("str('foo')")).isEqualTo("foo")
        Truth.assertThat(starlarkLoadingEval("'%s' % 'foo'")).isEqualTo("foo")
        Truth.assertThat(starlarkLoadingEval("'{}'.format('foo')")).isEqualTo("foo")
        Truth.assertThat(starlarkLoadingEval("repr('foo')")).isEqualTo("\"foo\"")
        Truth.assertThat(starlarkLoadingEval("'%r' % 'foo'")).isEqualTo("\"foo\"")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_labels() {
        Truth.assertThat(starlarkLoadingEval("str(Label('//foo:bar'))")).isEqualTo("@@//foo:bar")
        Truth.assertThat(starlarkLoadingEval("'%s' % Label('//foo:bar')")).isEqualTo("@@//foo:bar")
        Truth.assertThat(starlarkLoadingEval("'{}'.format(Label('//foo:bar'))")).isEqualTo("@@//foo:bar")
        Truth.assertThat(starlarkLoadingEval("repr(Label('//foo:bar'))")).isEqualTo("Label(\"@@//foo:bar\")")
        Truth.assertThat(starlarkLoadingEval("'%r' % Label('//foo:bar')"))
            .isEqualTo("Label(\"@@//foo:bar\")")

        Truth.assertThat(starlarkLoadingEval("'{}'.format([Label('//foo:bar')])"))
            .isEqualTo("[Label(\"@@//foo:bar\")]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_primitives() {
        // Strings are tested in a separate test case as they have different str and repr values.
        assertStringRepresentation("1543", "1543")
        assertStringRepresentation("True", "True")
        assertStringRepresentation("False", "False")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_containers() {
        assertStringRepresentation("['a', 'b']", "[\"a\", \"b\"]")
        assertStringRepresentation("('a', 'b')", "(\"a\", \"b\")")
        assertStringRepresentation("{'a': 'b', 'c': 'd'}", "{\"a\": \"b\", \"c\": \"d\"}")
        assertStringRepresentation("struct(d = 4, c = 3)", "struct(c = 3, d = 4)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_functions() {
        assertStringRepresentation("all", "<built-in function all>")
        assertStringRepresentation("def f(): pass", "f", "<function f from //eval:eval.bzl>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_rules() {
        assertStringRepresentation(
            "def f(): pass\n" + "myrule = rule(implementation=f)", "myrule", "<rule myrule>"
        )
        assertStringRepresentation("def f(): pass", "rule(implementation=f)", "<rule>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_aspects() {
        assertStringRepresentation("def f(): pass", "aspect(implementation=f)", "<aspect>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_providers() {
        assertStringRepresentation("provider()", "<provider>")
        assertStringRepresentation(
            "p = provider()", "p(b = 'foo', a = 1)", "struct(a = 1, b = \"foo\")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_select() {
        assertStringRepresentation(
            "select({'//foo': ['//bar']}) + select({'//foo2': ['//bar2']})",
            "select({\"//foo\": [\"//bar\"]}) + select({\"//foo2\": [\"//bar2\"]})"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_ruleContext() {
        generateFilesToTestStrings()
        val target: ConfiguredTarget = getConfiguredTarget("//test/starlark:check")
        val checkInfo: StarlarkInfo = getStarlarkProvider(target, "CheckInfo")

        for (suffix in SUFFIXES) {
            assertThat(checkInfo.getValue("rule_ctx" + suffix))
                .isEqualTo("<rule context for //test/starlark:check>")
            assertThat(checkInfo.getValue("aspect_ctx" + suffix))
                .isEqualTo("<aspect context for //test/starlark:bar>")
            assertThat(checkInfo.getValue("aspect_ctx.rule" + suffix))
                .isEqualTo("<rule collection for //test/starlark:bar>")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_files() {
        generateFilesToTestStrings()
        val target: ConfiguredTarget = getConfiguredTarget("//test/starlark:check")
        val checkInfo: StarlarkInfo = getStarlarkProvider(target, "CheckInfo")

        for (suffix in SUFFIXES) {
            assertThat(checkInfo.getValue("source_file" + suffix))
                .isEqualTo("<source file test/starlark/input.txt>")
            assertThat(checkInfo.getValue("generated_file" + suffix))
                .isEqualTo("<generated file test/starlark/output.txt>")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_root() {
        generateFilesToTestStrings()
        val target: ConfiguredTarget = getConfiguredTarget("//test/starlark:check")
        val checkInfo: StarlarkInfo = getStarlarkProvider(target, "CheckInfo")

        for (suffix in SUFFIXES) {
            assertThat(checkInfo.getValue("source_root" + suffix)).isEqualTo("<source root>")
            assertThat(checkInfo.getValue("generated_root" + suffix)).isEqualTo("<derived root>")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_glob() {
        scratch.file("eval/one.txt")
        scratch.file("eval/two.txt")
        scratch.file("eval/three.txt")

        assertStringRepresentationInBuildFile(
            "glob(['*.txt'])",
            "[\"one.txt\", \"three.txt\", \"two.txt\"]"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_attr() {
        assertStringRepresentation("attr", "<attr>")
        assertStringRepresentation("attr.int()", "<attr.int>")
        assertStringRepresentation("attr.string()", "<attr.string>")
        assertStringRepresentation("attr.label()", "<attr.label>")
        assertStringRepresentation("attr.string_list()", "<attr.string_list>")
        assertStringRepresentation("attr.int_list()", "<attr.int_list>")
        assertStringRepresentation("attr.label_list()", "<attr.label_list>")
        assertStringRepresentation("attr.label_keyed_string_dict()", "<attr.label_keyed_string_dict>")
        assertStringRepresentation("attr.bool()", "<attr.bool>")
        assertStringRepresentation("attr.output()", "<attr.output>")
        assertStringRepresentation("attr.output_list()", "<attr.output_list>")
        assertStringRepresentation("attr.string_dict()", "<attr.string_dict>")
        assertStringRepresentation("attr.string_list_dict()", "<attr.string_list_dict>")
        assertStringRepresentation("attr.label_list_dict()", "<attr.label_list_dict>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStringRepresentations_targets() {
        generateFilesToTestStrings()
        val target: ConfiguredTarget = getConfiguredTarget("//test/starlark:check")
        val checkInfo: StarlarkInfo = getStarlarkProvider(target, "CheckInfo")

        for (suffix in SUFFIXES) {
            assertThat(checkInfo.getValue("target" + suffix)).isEqualTo("<target //test/starlark:foo>")
            assertThat(checkInfo.getValue("input_target" + suffix))
                .isEqualTo("<input file target //test/starlark:input.txt>")
            assertThat(checkInfo.getValue("output_target" + suffix))
                .isEqualTo("<output file target //test/starlark:output.txt>")
            assertThat(checkInfo.getValue("alias_target" + suffix))
                .isEqualTo("<alias target //test/starlark:foobar of //test/starlark:foo>")
            assertThat(checkInfo.getValue("aspect_target" + suffix))
                .isEqualTo("<merged target //test/starlark:bar>")
        }
    }

    companion object {
        // Different ways to format objects, these suffixes are used in the `prepare_params` function
        private val SUFFIXES: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("_str", "_repr", "_format", "_str_perc", "_repr_perc")
    }
}
