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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.cmdline.Label

/** A test for [CompileOneDependencyTransformer].  */
@RunWith(JUnit4::class)
class CompileOneDependencyTransformerTest : BuildViewTestCase() {
    private var parser: TargetPatternPreloader? = null
    private var transformer: CompileOneDependencyTransformer? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createTransformer() {
        parser = skyframeExecutor.newTargetPatternPreloader()
        transformer = CompileOneDependencyTransformer(packageManager)
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setupLangRules() {
        MockCcSupport.Companion.get().setup(MockToolsConfig(rootDirectory))
    }

    @Throws(IOException::class)
    private fun writeSimpleExample() {
        scratch.file(
            "foo/rule.bzl",
            """
        def _impl(ctx):
            ctx.actions.do_nothing(mnemonic = "Mnemonic")
            return []

        crule_without_srcs = rule(
            _impl,
            attrs = {
                "hdrs": attr.label_list(flags = ["DIRECT_COMPILE_TIME_INPUT"]),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(":rule.bzl", "crule_without_srcs")

        cc_library(
            name = "foo1",
            srcs = ["foo1.cc"],
            hdrs = ["foo1.h"],
        )

        crule_without_srcs(
            name = "foo2",
            hdrs = ["foo2.h"],
        )

        exports_files(["baz/bang"])
        
        """.trimIndent()
        )
        scratch.file(
            "foo/bar/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "bar1",
            alwayslink = 1,
        )

        cc_library(name = "bar2")

        exports_files([
            "wiz/bang",
            "wiz/all",
            "baz",
            "baz/bang",
            "undeclared.h",
        ])
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun parseCompileOneDep(vararg patterns: String?): ResolvedTargets<Target?>? {
        return parseListCompileOneDepWithOffset(PathFragment.EMPTY_FRAGMENT, patterns)
    }

    @Throws(java.lang.Exception::class)
    private fun parseListCompileOneDep(vararg patterns: String?): MutableSet<Label?> {
        return targetsToLabels(getFailFast(parseCompileOneDep(*patterns)))
    }

    @Throws(TargetParsingException::class, IOException::class, java.lang.InterruptedException::class)
    private fun parseListCompileOneDepRelative(vararg patterns: String?): MutableSet<Label?> {
        val foo: Path = scratch.dir("foo")
        val result: ResolvedTargets<Target?> =
            parseListCompileOneDepWithOffset(foo.relativeTo(rootDirectory), patterns)
        return targetsToLabels(getFailFast(result))
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    private fun parseListCompileOneDepWithOffset(
        offset: PathFragment?, vararg patterns: String?
    ): ResolvedTargets<Target?> {
        val resolvedTargetsMap: MutableMap<String?, MutableCollection<Target?>?> =
            parser.preloadTargetPatterns(
                reporter,
                TargetPattern.mainRepoParser(offset),
                com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (patterns),
                false
            )
        val result: ResolvedTargets.Builder<Target?> = ResolvedTargets.builder()
        for (pattern in patterns) {
            result.addAll(resolvedTargetsMap.get(pattern))
        }
        return transformer.transformCompileOneDependency(reporter, result.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDep() {
        writeSimpleExample()
        Truth.assertThat(parseListCompileOneDep("foo/foo1.cc"))
            .containsExactlyElementsIn(labels("@//foo:foo1"))
        Truth.assertThat(parseListCompileOneDep("foo/foo1.h"))
            .containsExactlyElementsIn(labels("@//foo:foo1"))
        Truth.assertThat(parseListCompileOneDep("foo:foo1.cc"))
            .containsExactlyElementsIn(labels("@//foo:foo1"))
        Truth.assertThat(parseListCompileOneDep("//foo:foo1.cc"))
            .containsExactlyElementsIn(labels("@//foo:foo1"))
        Truth.assertThat(parseListCompileOneDepRelative("//foo:foo1.cc"))
            .containsExactlyElementsIn(labels("@//foo:foo1"))
        Truth.assertThat(parseListCompileOneDepRelative(":foo1.cc"))
            .containsExactlyElementsIn(labels("@//foo:foo1"))
        Truth.assertThat(parseListCompileOneDepRelative("foo1.cc"))
            .containsExactlyElementsIn(labels("@//foo:foo1"))
        Truth.assertThat(parseListCompileOneDep("foo/foo2.h"))
            .containsExactlyElementsIn(labels("@//foo:foo2"))
    }

    /** Regression test for bug: "--compile_one_dependency should report error for missing input".  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDepOnMissingFile() {
        writeSimpleExample()
        var e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseCompileOneDep("//foo:missing.cc") })
        assertThat(e)
            .hasMessageThat()
            .matches(
                com.google.devtools.build.lib.testutil.TestUtils.createMissingTargetAssertionString(
                    "missing.cc",
                    "foo",
                    "/workspace",
                    ""
                )
            )

        // Also, try a valid input file which has no dependent rules in its package.
        e = org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { parseCompileOneDep("//foo:baz/bang") })
        assertThat(e).hasMessageThat().isEqualTo("Couldn't find dependency on target '//foo:baz/bang'")

        // Try a header that is in a package but where no cc_library explicitly lists it.
        e =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseCompileOneDep("//foo/bar:undeclared.h") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("Couldn't find dependency on target '//foo/bar:undeclared.h'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDepOnNonSourceTarget() {
        writeSimpleExample()
        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseCompileOneDep("//foo:foo1") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("--compile_one_dependency target '//foo:foo1' must be a file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDepOnTwoTargets() {
        scratch.file(
            "recursive/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "x",
            srcs = ["foox.cc"],
        )

        cc_library(
            name = "y",
            srcs = ["fooy.cc"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(parseListCompileOneDep("//recursive:foox.cc", "//recursive:fooy.cc"))
            .containsExactlyElementsIn(labels("//recursive:x", "//recursive:y"))
    }

    /**
     * Regression test for bug: "--compile_one_dependency should not crash in the presence of mutually
     * recursive targets"
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDepOnRecursiveTarget() {
        scratch.file(
            "recursive/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        filegroup(
            name = "x",
            srcs = [
                "foo.cc",
                ":y",
            ],
        )

        filegroup(
            name = "y",
            srcs = [":x"],
        )

        cc_library(
            name = "foo",
            srcs = [":y"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(parseListCompileOneDep("//recursive:foo.cc"))
            .containsExactlyElementsIn(labels("//recursive:foo"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDepOnRecursiveNotFoundTarget() {
        scratch.file(
            "recursive/BUILD",
            """
        filegroup(
            name = "x",
            srcs = [":y"],
        )

        filegroup(
            name = "y",
            srcs = [":x"],
        )

        exports_files(["foo"])
        
        """.trimIndent()
        )

        val e: TargetParsingException? =
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { parseCompileOneDep("//recursive:foo") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo("Couldn't find dependency on target '//recursive:foo'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDepOnDeepRecursiveTarget() {
        scratch.file(
            "recursive/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        filegroup(
            name = "x",
            srcs = [
                "foox.cc",
                ":y",
            ],
        )

        filegroup(
            name = "y",
            srcs = [
                "fooy.cc",
                ":z",
            ],
        )

        filegroup(
            name = "z",
            srcs = [
                "fooz.cc",
                ":x",
            ],
        )

        cc_library(
            name = "cc",
            srcs = [":x"],
        )
        
        """.trimIndent()
        )

        val result: MutableSet<Label?> =
            parseListCompileOneDep("//recursive:foox.cc", "//recursive:fooy.cc", "//recursive:fooy.cc")
        Truth.assertThat(result).containsExactlyElementsIn(labels("//recursive:cc"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOneDepOnCrossPackageRecursiveTarget() {
        scratch.file(
            "recursive/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        filegroup(
            name = "x",
            srcs = [
                "foo.cc",
                "//recursivetoo:x",
            ],
        )

        cc_library(
            name = "cc",
            srcs = [":x"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "recursivetoo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        filegroup(
            name = "x",
            srcs = [
                "foo.cc",
                "//recursive:x",
            ],
        )

        cc_library(
            name = "cc",
            srcs = [":x"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(parseListCompileOneDep("//recursive:foo.cc", "//recursivetoo:foo.cc"))
            .containsExactlyElementsIn(labels("//recursive:cc", "//recursivetoo:cc"))
    }

    /**
     * Tests that when multiple rules match the target, the one that appears first in the BUILD file
     * is chosen.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleChoiceOrdering() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "foo_lib",
            srcs = ["file.cc"],
        )

        cc_library(
            name = "bar_lib",
            srcs = ["file.cc"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "b/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "bar_lib",
            srcs = ["file.cc"],
        )

        cc_library(
            name = "foo_lib",
            srcs = ["file.cc"],
        )
        
        """.trimIndent()
        )

        Truth.assertThat(parseListCompileOneDep("a/file.cc"))
            .containsExactlyElementsIn(labels("//a:foo_lib"))
        Truth.assertThat(parseListCompileOneDep("b/file.cc"))
            .containsExactlyElementsIn(labels("//b:bar_lib"))
    }

    /** Tests that when multiple rule match a target, language-specific rules take precedence.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleChoiceLanguagePreferences() {
        val srcs = "srcs = [ 'a.cc', 'a.c', 'a.h', 'a.py', 'a.txt' ])"
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl','cc_binary')",
            "genrule(name = 'gen_rule', cmd = '', outs = [ 'out' ], " + srcs,
            "cc_binary(name = 'cc_rule', " + srcs
        )

        Truth.assertThat(parseListCompileOneDep("a/a.cc")).containsExactlyElementsIn(labels("//a:cc_rule"))
        Truth.assertThat(parseListCompileOneDep("a/a.c")).containsExactlyElementsIn(labels("//a:cc_rule"))
        Truth.assertThat(parseListCompileOneDep("a/a.h")).containsExactlyElementsIn(labels("//a:cc_rule"))
        Truth.assertThat(parseListCompileOneDep("a/a.txt")).containsExactlyElementsIn(labels("//a:gen_rule"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGeneratedFile() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        genrule(
            name = "gen_rule",
            outs = ["out.cc"],
            cmd = "",
        )

        cc_library(
            name = "cc",
            srcs = ["out.cc"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(parseListCompileOneDep("a/out.cc")).containsExactlyElementsIn(labels("//a:cc"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGeneratedFileDepOnGenerator() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        genrule(
            name = "gen_rule",
            outs = ["out.cc"],
            cmd = "",
        )

        cc_library(
            name = "cc",
            srcs = [":gen_rule"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(parseListCompileOneDep("a/out.cc")).containsExactlyElementsIn(labels("//a:cc"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHdrsFilegroup() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        filegroup(
            name = "headers",
            srcs = ["a.h"],
        )

        cc_library(
            name = "cc",
            srcs = ["a.cc"],
            hdrs = [":headers"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(parseListCompileOneDep("a/a.h")).containsExactlyElementsIn(labels("//a:cc"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurableSrcs() {
        // TODO(djasper): We currently flatten the contents of configurable attributes, which might not
        // always do the right thing. In this situation it is actually good as compiling "foo_select"
        // at least has the chance to actually be a correct --compile_one_dependency choice for both
        // "b.cc" and "c.cc". However, if it also contained "a.cc" it might be better to still always
        // choose "foo_always".
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        config_setting(
            name = "a",
            values = {"define": "foo=a"},
        )

        cc_library(
            name = "foo_select",
            srcs = select({
                ":a": ["b.cc"],
                ":b": ["c.cc"],
            }),
        )

        cc_library(
            name = "foo_always",
            srcs = ["a.cc"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(parseListCompileOneDep("a/a.cc"))
            .containsExactlyElementsIn(labels("//a:foo_always"))
        Truth.assertThat(parseListCompileOneDep("a/b.cc"))
            .containsExactlyElementsIn(labels("//a:foo_select"))
        Truth.assertThat(parseListCompileOneDep("a/c.cc"))
            .containsExactlyElementsIn(labels("//a:foo_select"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurableCopts() {
        // This configurable attribute doesn't preclude accurately knowing the srcs.
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        config_setting(
            name = "a",
            values = {"define": "foo=a"},
        )

        cc_library(
            name = "foo_select",
            srcs = ["a.cc"],
            copts = select({
                ":a": ["-DA"],
                ":b": ["-DB"],
            }),
        )

        cc_library(
            name = "foo_always",
            srcs = ["a.cc"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(parseListCompileOneDep("a/a.cc"))
            .containsExactlyElementsIn(labels("//a:foo_select"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHeaderOnlyLibrary() {
        // By default, we assume parse_headers is enabled (via --features + toolchain).
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'h', hdrs = ['h.h'])",
            "cc_library(name = 'l', srcs = ['l.cc'], deps = [':h'])"
        )
        Truth.assertThat(parseListCompileOneDep("a/h.h")).containsExactlyElementsIn(labels("//a:h"))

        // parse_headers explicitly disabled on the header-only target, use its reverse dep.
        scratch.file(
            "b/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'h', hdrs = ['h.h'], features = ['-parse_headers'])",
            "cc_library(name = 'l', srcs = ['l.cc'], deps = [':h'])"
        )
        Truth.assertThat(parseListCompileOneDep("b/h.h")).containsExactlyElementsIn(labels("//b:l"))

        // ... but if it has sources, the target itself is ok.
        scratch.file(
            "c/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'h', hdrs = ['h.h'], srcs = ['h.cc'], features = ['-parse_headers'])",
            "cc_library(name = 'l', srcs = ['l.cc'], deps = [':h'])"
        )
        Truth.assertThat(parseListCompileOneDep("c/h.h")).containsExactlyElementsIn(labels("//c:h"))

        // parse_headers disabled in the package
        scratch.file(
            "d/BUILD",
            "package(features = ['-parse_headers'])",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'h', hdrs = ['h.h'])",
            "cc_library(name = 'l', srcs = ['l.cc'], deps = [':h'])"
        )
        Truth.assertThat(parseListCompileOneDep("d/h.h")).containsExactlyElementsIn(labels("//d:l"))

        // parse_headers disabled in the package and enabled on the target, so enabled
        scratch.file(
            "e/BUILD",
            "package(features = ['-parse_headers'])",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'h', hdrs = ['h.h'], features = ['parse_headers'])",
            "cc_library(name = 'l', srcs = ['l.cc'], deps = [':h'])"
        )
        Truth.assertThat(parseListCompileOneDep("e/h.h")).containsExactlyElementsIn(labels("//e:h"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFallBackToHeaderOnlyLibrary() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'h', hdrs = ['a.h'], features = ['parse_headers'])"
        )
        Truth.assertThat(parseListCompileOneDep("a/a.h")).containsExactlyElementsIn(labels("//a:h"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doesNotCrashWhenPackageHasRuleWithDubiousSrcs() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        environment(name = "foo")

        environment(name = "baz")

        environment_group(
            name = "bar",
            defaults = [":baz"],
            environments = [
                ":baz",
                ":foo",
            ],
        )

        package_group(name = "pg")

        cc_library(
            name = "h1",
            srcs = [
                ":bar",
                ":pg",
            ],
        )

        cc_library(
            name = "h2",
            hdrs = ["a.h"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat(parseListCompileOneDep("a/a.h")).containsExactlyElementsIn(labels("//a:h2"))
    }

    companion object {
        private fun targetsToLabels(targets: Iterable<Target?>): MutableSet<Label?> {
            return AbstractTargetPatternEvaluatorTest.Companion.targetsToLabels(targets)
        }

        @Throws(LabelSyntaxException::class)
        private fun labels(vararg labelStrings: String?): MutableSet<Label?> {
            val labels: MutableSet<Label?> = HashSet<Label?>()
            for (labelString in labelStrings) {
                labels.add(Label.parseCanonical(labelString))
            }
            return labels
        }

        private fun getFailFast(result: ResolvedTargets<Target?>): MutableSet<Target?> {
            assertThat(result.hasError()).isFalse()
            return result.getTargets()
        }
    }
}
