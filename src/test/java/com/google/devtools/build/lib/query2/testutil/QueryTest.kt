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
package com.google.devtools.build.lib.query2.testutil

import com.google.common.base.Function
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.packages.BuildType
import com.google.devtools.build.lib.query2.engine.QueryEnvironment
import org.junit.Test

/** Tests for the blaze query implementation, --nokeep_going.  */
abstract class QueryTest : AbstractQueryTest<Target?>() {
    override fun createQueryHelper(): QueryHelper<Target?> {
        return object : SkyframeQueryHelper() {
            override fun getRootDirectoryNameForSetup(): String {
                return "/workspace"
            }

            @Throws(IOException::class)
            override fun performAdditionalClientSetup(mockToolsConfig: MockToolsConfig?) {
            }

            override fun getExtraQueryFunctions(): Iterable<QueryFunction?> {
                return ImmutableList.of<QueryFunction?>()
            }
        }
    }

    protected fun setLazyMacroExpansionPackages(
        lazyMacroExpansionPackages: LazyMacroExpansionPackages?
    ) {
        (helper as SkyframeQueryHelper).setLazyMacroExpansionPackages(lazyMacroExpansionPackages)
    }

    override fun includeCppToolchainDependencies(): Boolean {
        return false
    }

    @Test
    @Throws(Exception::class)
    fun testFindsAllTargets_nativeRuleMacro() {
        writeFile(
            "test/starlark/extension.bzl",
            """
        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >${'$'}@")
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension.bzl", "macro")

        macro(name = "rule1")

        macro(name = "rule2")
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("//test/starlark:*")))
            .containsExactly(
                "//test/starlark:rule1",
                "//test/starlark:rule2",
                "//test/starlark:BUILD",
                "//test/starlark:rule1.txt",
                "//test/starlark:rule2.txt"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testFindsAllTargets_starlarkRuleMacro() {
        writeFile(
            "test//starlark/extension.bzl",
            """
        def impl(ctx):
            return None

        starlark_rule = rule(implementation = impl)

        def macro(name):
            starlark_rule(name = name)
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension.bzl", "macro")

        macro(name = "rule1")

        macro(name = "rule2")
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("//test/starlark:*")))
            .containsExactly("//test/starlark:rule1", "//test/starlark:rule2", "//test/starlark:BUILD")
    }

    @Test
    @Throws(Exception::class)
    fun testFindsAllTargets_symbolicMacro() {
        writeFile(
            "test//starlark/extension.bzl",
            """
        def _rule_impl(ctx):
            return None

        starlark_rule = rule(implementation = _rule_impl)

        def _macro_impl(name, visibility):
            starlark_rule(name = name, visibility = visibility)
            native.genrule(name = name + "_gen", outs = [name + "_gen.txt"], cmd = "echo hi >${'$'}@")

        symbolic_macro = macro(implementation = _macro_impl)
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension.bzl", "symbolic_macro")

        symbolic_macro(name = "foo")
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("//test/starlark:*")))
            .containsExactly(
                "//test/starlark:BUILD",
                "//test/starlark:foo",
                "//test/starlark:foo_gen",
                "//test/starlark:foo_gen.txt"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testFindsAllTargets_symbolicMacro_withLazyMacroExpansion() {
        setLazyMacroExpansionPackages(LazyMacroExpansionPackages.ALL)
        writeFile(
            "test//starlark/extension.bzl",
            """
        def _rule_impl(ctx):
            return None

        starlark_rule = rule(implementation = _rule_impl)

        def _macro_impl(name, visibility):
            starlark_rule(name = name, visibility = visibility)
            native.genrule(name = name + "_gen", outs = [name + "_gen.txt"], cmd = "echo hi >${'$'}@")

        symbolic_macro = macro(implementation = _macro_impl)
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension.bzl", "symbolic_macro")

        symbolic_macro(name = "foo")
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("//test/starlark:*")))
            .containsExactly(
                "//test/starlark:BUILD",
                "//test/starlark:foo",
                "//test/starlark:foo_gen",
                "//test/starlark:foo_gen.txt"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testBuildfiles_starlarkDep() {
        writeFile(
            "test//starlark/extension.bzl",
            """
        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >${'$'}@")
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension.bzl", "macro")

        macro(name = "rule1")
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("buildfiles(//test/starlark:BUILD)")))
            .containsExactly("//test/starlark:extension.bzl", "//test/starlark:BUILD")
    }

    @Test
    @Throws(Exception::class)
    fun testBuildfiles_starlarkDep_withLazyMacroExpansion() {
        setLazyMacroExpansionPackages(LazyMacroExpansionPackages.ALL)
        writeFile(
            "test//starlark/extension.bzl",
            """
        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >${'$'}@")
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension.bzl", "macro")

        macro(name = "rule1")
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("buildfiles(//test/starlark:BUILD)")))
            .containsExactly("//test/starlark:extension.bzl", "//test/starlark:BUILD")
    }

    @Test
    @Throws(Exception::class)
    fun testLoadfiles_starlarkDep() {
        writeFile(
            "test//starlark/extension.bzl",
            """
        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >${'$'}@")
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension.bzl", "macro")

        macro(name = "rule1")
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("loadfiles(//test/starlark:BUILD)")))
            .containsExactly("//test/starlark:extension.bzl")
    }

    @Test
    @Throws(Exception::class)
    fun testLoadfiles_sclDep() {
        writeBzlAndSclFiles()

        Truth.assertThat(targetLabels(eval("loadfiles(//foo:BUILD)")))
            .containsExactly(
                "//bar:direct.scl",
                "//bar:indirect.scl",
                "//bar:intermediate.bzl",
                "//test_defs:foo_library.bzl"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testLoadfiles_sclDep_withLazyMacroExpansion() {
        setLazyMacroExpansionPackages(LazyMacroExpansionPackages.ALL)
        writeBzlAndSclFiles()

        Truth.assertThat(targetLabels(eval("loadfiles(//foo:BUILD)")))
            .containsExactly(
                "//bar:direct.scl",
                "//bar:indirect.scl",
                "//bar:intermediate.bzl",
                "//test_defs:foo_library.bzl"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testDeps_labelKeyedStringDictDeps() {
        writeFile(
            "test//starlark/rule.bzl",
            """
        def _impl(ctx):
            return

        my_rule = rule(
            implementation = _impl,
            attrs = {
                "value_dict": attr.label_keyed_string_dict(allow_files = True),
            },
        )
        
        """.trimIndent()
        )
        writeFile("test//starlark/dep.cc")
        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:rule.bzl", "my_rule")

        filegroup(
            name = "group",
            srcs = ["dep.cc"],
        )

        my_rule(
            name = "rule",
            value_dict = {":group": "queried"},
        )
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("deps(//test/starlark:rule)")))
            .containsExactly("//test/starlark:rule", "//test/starlark:group", "//test/starlark:dep.cc")
    }

    @Test
    @Throws(Exception::class)
    fun testBuildfiles_transitiveStarlarkDeps() {
        writeFile(
            "test//starlark/extension1.bzl",
            """
        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >${'$'}@")
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/extension2.bzl",
            """
        load("//test/starlark:extension1.bzl", "macro")

        def func(name):
            macro(name)
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension2.bzl", "func")

        func(name = "rule1")
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("buildfiles(//test/starlark:BUILD)")))
            .containsExactly(
                "//test/starlark:extension1.bzl",
                "//test/starlark:extension2.bzl",
                "//test/starlark:BUILD"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testBuildfiles_diamondStarlarkDeps() {
        writeFile(
            "test//starlark/extension1.bzl",
            """
        my_constant = "rule1"

        def macro(name):
            native.genrule(name = name, outs = [name + ".txt"], cmd = "echo hi >${'$'}@")
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/extension2.bzl",
            """
        load("//test/starlark:extension1.bzl", "macro")

        def func(name):
            macro(name)
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/extension3.bzl",
            """
        load("//test/starlark:extension1.bzl", "my_constant")

        my_rule_name = my_constant
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/extension4.bzl",
            """
        load("//test/starlark:extension2.bzl", "func")
        load("//test/starlark:extension3.bzl", "my_rule_name")

        my_dummy_name = my_rule_name
        
        """.trimIndent()
        )

        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension2.bzl", "func")
        load("//test/starlark:extension3.bzl", "my_rule_name")
        load("//test/starlark:extension4.bzl", "my_dummy_name")

        func(name = my_rule_name + "-" + my_dummy_name)
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("buildfiles(//test/starlark:BUILD)")))
            .containsExactly(
                "//test/starlark:extension1.bzl",
                "//test/starlark:extension2.bzl",
                "//test/starlark:extension3.bzl",
                "//test/starlark:extension4.bzl",
                "//test/starlark:BUILD"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testBuildfiles_starlarkDepPackageBuildfileIncluded() {
        writeFile("test//starlark2/BUILD")
        writeFile("test//starlark2/extension.bzl", "file_ext = '.txt'")

        writeFile("test//starlark1/BUILD")
        writeFile(
            "test//starlark1/extension.bzl",
            """
        load("//test/starlark2:extension.bzl", "file_ext")

        def macro(name):
            native.genrule(name = name, outs = [name + file_ext], cmd = "echo hi >${'$'}@")
        
        """.trimIndent()
        )

        writeFile(
            "test/pkg/BUILD",
            """
        load("//test/starlark1:extension.bzl", "macro")

        macro(name = "rule1")
        
        """.trimIndent()
        )

        Truth.assertThat(targetLabels(eval("buildfiles(//test/pkg:BUILD)")))
            .containsExactly(
                "//test/pkg:BUILD",
                "//test/starlark1:extension.bzl",
                "//test/starlark1:BUILD",
                "//test/starlark2:extension.bzl",
                "//test/starlark2:BUILD"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testQueryTimeLoadingWhenPackageDoesNotExist() {
        // Given a workspace containing a package "//a",
        writeFile(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')"
        )

        // When the query environment is queried for "//a/b:b" which doesn't exist,
        val nonExistentPackage = "a/b"
        val s = evalThrows("//" + nonExistentPackage + ":itsNotThere", false).getMessage()

        // Then an exception is thrown that says that the specified package does not exist.
        Truth.assertThat(s).containsMatch("no such package '" + nonExistentPackage + "'")
    }

    @Test
    @Throws(Exception::class)
    fun testQueryTimeLoadingWhenPackageIsMalformed() {
        // Given a workspace containing a malformed package "//a",
        writeFile(
            "a/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a') BUT WAIT THERE'S MORE"
        )

        // When the query environment is queried for "//a:a" which belongs to a malformed package,
        val s = evalThrows("//a:a", false).getMessage()

        // Then an exception is thrown,
        Truth.assertThat(s).isNotNull()

        // And then the query output contains a description of the malformed package error.
        assertContainsEvent("unclosed string literal")
    }

    @Test
    @Throws(Exception::class)
    fun testQueryTimeLoadingOfSymlinkCyclePackage() {
        // Given a workspace containing a symlink cycle that looks like a BUILD file at "//a/BUILD",
        ensureSymbolicLink("a/BUILD", "a/BUILD")

        // When the query environment is queried for "//a:*",
        val s = evalThrows("//a:*", false).getMessage()

        // Then an exception is thrown,
        Truth.assertThat(s).isNotNull()

        // And then the query output contains a description of the circular symlink problem.
        assertContainsEvent("circular symlinks detected")
    }

    @Test
    @Throws(Exception::class)
    fun boundedDepsQueryWithError() {
        writeFile(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name ='foo', deps = ['//bar'])"
        )
        writeFile(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name ='bar')"
        )
        writeFile(
            "errorparent",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'errorparent', deps = ['//error'])"
        )
        writeFile("error", "has errors")

        evalThrows("deps(//foo:all + //errorparent:all, 25)", false)
    }

    @Test
    @Throws(Exception::class)
    fun testIgnoredSubdirectories() {
        useReducedSetOfRules()
        writeFile(helper.getIgnoredSubdirectoriesFile().getPathString(), "a/b", "a/c")
        writeFile("a/BUILD", "filegroup(name = 'a')")
        writeFile("b/BUILD", "filegroup(name = 'b')")
        writeFile("a/b/BUILD", "filegroup(name = 'a_b')")
        writeFile("a/c/BUILD", "filegroup(name = 'a_c')")
        writeFile("a/d/BUILD", "filegroup(name = 'a_d')")
        writeFile("a/e/BUILD", "filegroup(name = 'a_e')")
        // Ensure that modified files are invalidated in the skyframe. If a file has
        // already been read prior to the test's writes, this forces the query to
        // pick up the modified versions.
        helper.maybeHandleDiffs()
        var result = targetLabels(eval("//..."))
        Truth.assertThat(result).containsAtLeast("//a:a", "//b:b", "//a/d:a_d", "//a/e:a_e")
        Truth.assertThat(result).containsNoneOf("//a/b:a_b", "//a/c:a_c")
        result = targetLabels(eval("//a/..."))
        Truth.assertThat(result).containsExactly("//a:a", "//a/d:a_d", "//a/e:a_e")
    }

    @Throws(IOException::class)
    private fun writeStarlarkDefinedRuleClassBzlFile() {
        writeFile(
            "test//starlark/extension.bzl",
            """
        def custom_rule_impl(ctx):
            ftb = depset(ctx.attr._secret_labels)
            return DefaultInfo(runfiles = ctx.runfiles(), files = ftb)

        def secret_labels_func(prefix, suffix):
            return [
                Label("//test/starlark:" + prefix + "01" + suffix),
                Label("//test/starlark:" + prefix + "02" + suffix),
            ]

        custom_rule = rule(
            implementation = custom_rule_impl,
            attrs = {
                "prefix": attr.string(default = "default_prefix"),
                "suffix": attr.string(default = "default_suffix"),
                "_secret_labels": attr.label_list(default = secret_labels_func),
            },
        )
        
        """.trimIndent()
        )
    }

    @Test
    @Throws(Exception::class)
    fun testQueryStarlarkComputedDefault() {
        writeStarlarkDefinedRuleClassBzlFile()
        writeFile(
            "test//starlark/BUILD",
            """
        load("//test/starlark:extension.bzl", "custom_rule")

        custom_rule(
            name = "custom",
            prefix = "a",
            suffix = "b",
        )
        
        """.trimIndent()
        )

        val targets: MutableSet<Target?> = eval("//test/starlark:*")
        Truth.assertThat(targetLabels(targets))
            .containsExactly(
                "//test/starlark:BUILD",
                "//test/starlark:custom",
                "//test/starlark:a01b",
                "//test/starlark:a02b"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testQueryStarlarkComputedDefaultWithConfigurableDeps() {
        writeStarlarkDefinedRuleClassBzlFile()
        writeFile(
            "test//starlark/BUILD",
            "load('//test/starlark:extension.bzl', 'custom_rule')",
            "",
            "config_setting(",
            "    name = 'cfg_a',",
            "    values = {'test_arg': 'something'})",
            "config_setting(",
            "    name = 'cfg_b',",
            "    values = {'test_arg': 'something_else'})",
            "",
            "custom_rule(",
            "    name = 'custom',",
            "    prefix = select({",
            "        ':cfg_a':'a',",
            "        ':cfg_b':'b',",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def'}),",
            "    suffix = select({",
            "        ':cfg_a':'a',",
            "        ':cfg_b':'b',",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def'}))"
        )

        val computedLabelsBuilder = ImmutableList.builder<String?>()
        for (prefix in ImmutableList.of<String?>("a", "b", "def")) {
            for (middle in ImmutableList.of<String?>("01", "02")) {
                for (suffix in ImmutableList.of<String?>("a", "b", "def")) {
                    computedLabelsBuilder.add("//test/starlark:" + prefix + middle + suffix)
                }
            }
        }
        val computedLabels = computedLabelsBuilder.build()

        val targets: MutableSet<Target?> = eval("//test/starlark:*")
        Truth.assertThat(targetLabels(targets))
            .containsAtLeastElementsIn(
                Iterables.concat<String?>(
                    ImmutableList.of<String?>(
                        "//test/starlark:BUILD",
                        "//test/starlark:cfg_a",
                        "//test/starlark:cfg_b",
                        "//test/starlark:custom"
                    ),
                    computedLabels
                )
            )
    }

    @Test
    @Throws(Exception::class)
    fun testQueryStarlarkComputedDefaultWithConfigurableDepsUsedTwice() {
        writeStarlarkDefinedRuleClassBzlFile()
        writeFile(
            "test//starlark/BUILD",
            "load('//test/starlark:extension.bzl', 'custom_rule')",
            "",
            "config_setting(",
            "    name = 'cfg_a',",
            "    values = {'test_arg': 'something'})",
            "config_setting(",
            "    name = 'cfg_b',",
            "    values = {'test_arg': 'something_else'})",
            "",
            "custom_rule(",
            "    name = 'custom_one',",
            "    prefix = select({",
            "        ':cfg_a':'a_one',",
            "        ':cfg_b':'b_one',",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def_one'}),",
            "    suffix = select({",
            "        ':cfg_a':'a_one',",
            "        ':cfg_b':'b_one',",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def_one'}))",
            "custom_rule(",
            "    name = 'custom_two',",
            "    prefix = select({",
            "        ':cfg_a':'a_two',",
            "        ':cfg_b':'b_two',",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def_two'}),",
            "    suffix = select({",
            "        ':cfg_a':'a_two',",
            "        ':cfg_b':'b_two',",
            "        '" + BuildType.Selector.DEFAULT_CONDITION_KEY + "':'def_two'}))"
        )

        val computedLabelsBuilder = ImmutableList.builder<String?>()
        for (prefix in ImmutableList.of<String?>("a_one", "b_one", "def_one")) {
            for (middle in ImmutableList.of<String?>("01", "02")) {
                for (suffix in ImmutableList.of<String?>("a_one", "b_one", "def_one")) {
                    computedLabelsBuilder.add("//test/starlark:" + prefix + middle + suffix)
                }
            }
        }
        for (prefix in ImmutableList.of<String?>("a_two", "b_two", "def_two")) {
            for (middle in ImmutableList.of<String?>("01", "02")) {
                for (suffix in ImmutableList.of<String?>("a_two", "b_two", "def_two")) {
                    computedLabelsBuilder.add("//test/starlark:" + prefix + middle + suffix)
                }
            }
        }
        val computedLabels = computedLabelsBuilder.build()

        val targets: MutableSet<Target?> = eval("//test/starlark:*")
        Truth.assertThat(targetLabels(targets))
            .containsExactlyElementsIn(
                Iterables.concat<String?>(
                    ImmutableList.of<String?>(
                        "//test/starlark:BUILD",
                        "//test/starlark:cfg_a",
                        "//test/starlark:cfg_b",
                        "//test/starlark:custom_one",
                        "//test/starlark:custom_two"
                    ),
                    computedLabels
                )
            )
    }

    @Test
    @Throws(Exception::class)
    fun testFileTargetLiteralInSubdirectory() {
        writeFile(
            "foo/BUILD",
            "exports_files(glob(['**/*.txt']))"
        )
        writeFile("foo/bar/file1.txt")
        writeFile("foo/bar/file2.txt")
        val targets: MutableSet<Target?> = eval("foo/bar/file1.txt + foo/bar/file2.txt")
        Truth.assertThat(targetLabels(targets))
            .containsExactly(
                "//foo:bar/file1.txt",
                "//foo:bar/file2.txt"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testShorthandTargetLiteralUnion() {
        writeFile(
            "foo/bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar')"
        )
        writeFile(
            "foo/baz/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'baz')"
        )
        val targets: MutableSet<Target?> = eval("foo/bar + foo/baz")
        Truth.assertThat(targetLabels(targets))
            .containsExactly(
                "//foo/bar:bar",
                "//foo/baz:baz"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testShorthandAbsoluteTargetLiteralUnion() {
        writeFile(
            "foo/bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar')"
        )
        writeFile(
            "foo/baz/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'baz')"
        )
        val targets: MutableSet<Target?> = eval("//foo/bar + //foo/baz")
        Truth.assertThat(targetLabels(targets))
            .containsExactly(
                "//foo/bar:bar",
                "//foo/baz:baz"
            )
    }

    @Test
    @Throws(Exception::class)
    fun testLoadfilesWithDuplicates() {
        writeFile(
            "foo/BUILD",
            """
        load("//bar:bar.bzl", "B")
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "foo",
            deps = ["//bar"],
        )
        
        """.trimIndent()
        )
        writeFile(
            "bar/BUILD",
            """
        load("//bar:bar.bzl", "B")
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(name = "bar")
        
        """.trimIndent()
        )
        writeFile("bar/bar.bzl", "B = []")
        Truth.assertThat(evalToString("loadfiles(deps(//foo))"))
            .isEqualTo("//bar:bar.bzl //test_defs:foo_library.bzl")
    }

    @Throws(Exception::class)
    protected fun runTestRdepsWithNonDefaultDependencyFilter(query: String?, expected: String?) {
        writeFile(
            "foo/BUILD",
            """
        load("//test_defs:foo_binary.bzl", "foo_binary")
        genrule(
            name = "gen",
            srcs = ["doesntmatter.txt"],
            outs = ["out.txt"],
            cmd = "blah",
            tools = [":a"],
        )

        foo_binary(
            name = "a",
        )

        foo_binary(
            name = "b",
            srcs = [":a"],
        )

        foo_binary(
            name = "c",
            srcs = [":out.txt"],
        )
        
        """.trimIndent()
        )
        helper.setQuerySettings(QueryEnvironment.Setting.ONLY_TARGET_DEPS)
        Truth.assertThat(evalToString(query)).isEqualTo(expected)
    }

    @Test
    @Throws(Exception::class)
    fun testRdepsUnboundedWithNonDefaultDependencyFilter() {
        runTestRdepsWithNonDefaultDependencyFilter("rdeps(//foo:all, //foo:a)", "//foo:a //foo:b")
    }

    @Test
    @Throws(Exception::class)
    fun testRdepsBoundedWithNonDefaultDependencyFilter() {
        runTestRdepsWithNonDefaultDependencyFilter("rdeps(//foo:all, //foo:a, 1)", "//foo:a //foo:b")
    }

    // Regression test for default visibility of output file targets being traversed even with
    // --noimplicit_deps is set.
    @Test
    @Throws(Exception::class)
    fun testDefaultVisibilityOfOutputTarget_noImplicitDeps() {
        writeFile(
            "foo/BUILD",
            """
        package(default_visibility = [':pg'])
        genrule(name = 'gen', srcs = ['in'], outs = ['out'], cmd = 'doesntmatter')
        package_group(name = 'pg', includes = [':other-pg'])
        package_group(name = 'other-pg')
        
        """.trimIndent()
        )
        assertEqualsFiltered(
            "deps(//foo:gen) + //foo:out + //foo:pg + //foo:other-pg"
                    + getDependencyCorrectionWithGen(),
            "deps(//foo:out)" + getDependencyCorrectionWithGen(),
            QueryEnvironment.Setting.NO_IMPLICIT_DEPS
        )
    }

    @Test
    @Throws(Exception::class)
    fun testDormantDepsAreReturned() {
        writeFile(
            "a/a.bzl",
            """
        def _impl(*args):
          fail("should not be called")

        r = rule(
          implementation = _impl,
          dependency_resolution_rule = True,
          attrs = { "dormant": attr.dormant_label(), "dormant_list": attr.dormant_label_list() })
        
        """.trimIndent()
        )

        writeFile(
            "a/BUILD",
            """
        load(":a.bzl", "r")
        filegroup(name="a")
        filegroup(name="b1")
        filegroup(name="b2")

        r(name="r", dormant=":a", dormant_list=[":b1", ":b2"])
        
        """.trimIndent()
        )

        Truth.assertThat(evalToListOfStrings("deps('//a:r')"))
            .containsAtLeast("//a:r", "//a:a", "//a:b1", "//a:b2")
    }

    @Test
    @Throws(Exception::class)
    fun testMaterializerRuleQuery() {
        writeFile(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
   f = ctx.actions.declare_file(ctx.label.name + ".txt")
   ctx.actions.write(f, ctx.label.name)
   return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    # interleave these to make it more interesting
    for cd in ctx.attr.all_components_dormant:
        if "yes" in str(cd.label):
            selected.append(cd)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components_dormant": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        writeFile(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components_dormant = [":a_yes", ":b_yes", ":c_no", ":d_no"],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "c_no")
component(name = "d_no")
component(name = "zzz")

""".trimIndent()
        )

        // This should return all the possible deps, as opposed to just the selected deps.
        Truth.assertThat(evalToListOfStrings("deps('//:bin')"))
            .containsAtLeast(
                "//:aaa",
                "//:a_yes",
                "//:b_yes",
                "//:c_no",
                "//:d_no",
                "//:zzz",
                "//:component_selector"
            )

        // The direct deps should contain only component_selector and none of the selected deps
        // because it's not known at query (i.e. only loading time) what deps are selected.
        val directDeps = evalToListOfStrings("deps('//:bin', 1)")
        Truth.assertThat(directDeps).containsAtLeast("//:aaa", "//:component_selector", "//:zzz")
        Truth.assertThat(directDeps).containsNoneOf("//:a_yes", "//:b_yes", "//:c_no", "//:d_no")
    }

    @Test
    @Throws(Exception::class)
    fun testMaterializerRuleRealDepsQuery() {
        writeFile(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output", "info"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f, info = ctx.attr.info)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
    attrs = {
        "info": attr.string(),
    }
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in c[ComponentInfo].info:
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        writeFile(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components = [":a", ":b", ":c", ":d"],
)

component(name = "aaa")
component(name = "a", info = "yes")
component(name = "b", info = "yes")
component(name = "c", info = "no")
component(name = "d", info = "no")
component(name = "zzz")

""".trimIndent()
        )

        // The transitive deps should return all the possible deps, as opposed to just the selected
        // deps because the materialize rule will have all the deps.
        val allDeps = evalToListOfStrings("deps('//:bin')")
        Truth.assertThat(allDeps)
            .containsAtLeast(
                "//:aaa", "//:a", "//:b", "//:c", "//:d", "//:zzz", "//:component_selector"
            )

        val directDeps = evalToListOfStrings("deps('//:bin', 1)")
        Truth.assertThat(directDeps).containsAtLeast("//:aaa", "//:component_selector", "//:zzz")
        // The selected deps materialized from the materializer rule can't be known until
        // analysis time, so they should not be included in the direct deps. After analysis, a and b
        // would be direct depds of bin.
        Truth.assertThat(directDeps).containsNoneOf("//:a", "//:b", "//:c", "//:d")
    }

    protected fun targetLabels(targets: MutableSet<Target?>): Iterable<String?> {
        return Iterables.transform<Target?, String?>(targets, object : Function<Target?, String?> {
            override fun apply(input: Target): String {
                return input.getLabel().toString()
            }
        })
    }
}
