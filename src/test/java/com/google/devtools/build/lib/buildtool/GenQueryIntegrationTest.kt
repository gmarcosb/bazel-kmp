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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.Artifact

/** Integration tests for the 'genquery' rule.  */
@RunWith(TestParameterInjector::class)
class GenQueryIntegrationTest : BuildIntegrationTestCase() {
    @TestParameter
    private val keepGoing = false

    @Throws(java.lang.Exception::class)
    override fun setupOptions() {
        super.setupOptions()
        runtimeWrapper.addOptions(if (keepGoing) "--keep_going" else "--nokeep_going")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesNotFailHorribly() {
        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        
        """.trimIndent()
        )
        assertQueryResult("//fruits:q", "//fruits:melon", "//fruits:papaya")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeterministic() {
        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [
                ":apple",
                ":papaya",
            ],
        )

        foo_library(
            name = "papaya",
            deps = [":banana"],
        )

        foo_library(
            name = "banana",
            deps = [":apple"],
        )

        foo_library(
            name = "apple",
            deps = [":cherry"],
        )

        foo_library(name = "cherry")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        
        """.trimIndent()
        )
        val firstResult = getQueryResult("//fruits:q")
        for (i in 0..9) {
            createFilesAndMocks() // Do a clean.
            Truth.assertThat(getQueryResult("//fruits:q")).isEqualTo(firstResult)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateName() {
        write(
            "one/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='foo')"
        )
        write(
            "two/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='foo')"
        )
        write(
            "query/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "common",
            deps = [
                "//one:foo",
                "//two:foo",
            ],
        )

        genquery(
            name = "q",
            expression = "deps(//query:common)",
            scope = ["//query:common"],
        )
        
        """.trimIndent()
        )
        Truth.assertThat<String?>(getQueryResult("//query:q").split("\n".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()).hasLength(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailsIfGoesOutOfScope() {
        write(
            "vegetables/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "tomato",
            deps = [":cabbage"],
        )

        foo_library(name = "cabbage")

        genquery(
            name = "q",
            expression = "deps(//vegetables:tomato)",
            scope = [":cabbage"],
        )
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//vegetables:q") })

        assertContainsEvent("is not within the scope of the query")
    }

    // Regression test for http://b/29964062.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailsIfGoesOutOfScopeViaSelect() {
        write(
            "q/BUILD",
            """
        genquery(
            name = "q",
            expression = "deps(//q:f)",
            scope = ["f"],
        )

        config_setting(
            name = "cs",
            values = {"define": "D=1"},
        )

        filegroup(
            name = "f",
            srcs = select({
                "cs": [],
                "//conditions:default": ["//dne"],
            }),
        )
        
        """.trimIndent()
        )

        addOptions("--define=D=1")
        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//q") })

        events.assertContainsError(
            "in genquery rule //q:q: errors were encountered while computing transitive closure of the"
                    + " scope"
        )
        events.assertContainsError(
            java.util.regex.Pattern.compile(
                ("no such package 'dne': BUILD file not found in any of the following directories. Add a"
                        + " BUILD file to a directory to mark it as a package.\n"
                        + " - dne")
            )
        )
    }

    // Regression test for http://b/34132681
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailsIfBrokenDependencyViaSelect() {
        write(
            "q/BUILD",
            """
        genquery(
            name = "q",
            expression = "deps(//q:f)",
            scope = ["f"],
        )

        config_setting(
            name = "cs",
            values = {"define": "D=1"},
        )

        filegroup(
            name = "f",
            srcs = select({
                "cs": [],
                "//conditions:default": ["//d"],
            }),
        )
        
        """.trimIndent()
        )
        // d exists but has nonexistent "deps"
        write("d/BUILD", "filegroup(name = 'd', deps = [])")

        addOptions("--define=D=1")
        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//q") })

        events.assertContainsError(
            "in genquery rule //q:q: errors were encountered while computing transitive closure of the"
                    + " scope"
        )
        events.assertContainsError("Target '//d:d' contains an error and its package is in error")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResultsAlphabetized() {
        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [
                ":1",
                ":a",
                ":c",
                ":z",
                "//a:z",
                "//c",
                "//z:a",
            ],
        )

        foo_library(name = "a")

        foo_library(name = "z")

        foo_library(name = "1")

        foo_library(name = "c")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        
        """.trimIndent()
        )
        write(
            "z/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'a')"
        )
        write(
            "a/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name = 'z')"
        )
        write(
            "c/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'c', deps = ['//z:a'])"
        )
        assertQueryResult(
            "//fruits:q",  // Results are ordered in lexicographical order (uses graphless genquery by default).
            "//a:z",
            "//c:c",
            "//fruits:1",
            "//fruits:a",
            "//fruits:c",
            "//fruits:melon",
            "//fruits:z",
            "//z:a"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testQueryReexecutedIfDepsChange() {
        write(
            "food/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "fruit_salad",
            deps = ["//fruits:tropical"],
        )

        genquery(
            name = "q",
            expression = "deps(//food:fruit_salad)",
            scope = [":fruit_salad"],
        )
        
        """.trimIndent()
        )

        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "tropical",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")
        
        """.trimIndent()
        )

        assertQueryResult("//food:q", "//food:fruit_salad", "//fruits:papaya", "//fruits:tropical")

        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "tropical",
            deps = [
                ":coconut",
                ":papaya",
            ],
        )

        foo_library(name = "papaya")

        foo_library(name = "coconut")
        
        """.trimIndent()
        )

        assertQueryResult(
            "//food:q",
            "//food:fruit_salad",
            "//fruits:coconut",
            "//fruits:papaya",
            "//fruits:tropical"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGenQueryEncountersAnotherGenQuery() {
        write(
            "spices/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "cinnamon",
            deps = [":nutmeg"],
        )

        foo_library(name = "nutmeg")

        genquery(
            name = "q",
            expression = "deps(//spices:cinnamon)",
            scope = [":cinnamon"],
        )
        
        """.trimIndent()
        )

        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "pear",
            deps = [":plum"],
        )

        foo_library(name = "plum")

        genquery(
            name = "q",
            expression = "deps(//fruits:pear) + deps(//spices:q)",
            scope = [
                ":pear",
                "//spices:q",
            ],
        )
        
        """.trimIndent()
        )

        assertQueryResult(
            "//fruits:q",
            "//fruits:pear",
            "//fruits:plum",
            "//spices:cinnamon",
            "//spices:nutmeg",
            "//spices:q"
        )
    }

    /**
     * Regression test for b/14227750: genquery referring to non-existent target crashes on skyframe.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHandlesMissingTargetGracefully() {
        write(
            "a/BUILD",
            "genquery(name='query', scope=['//b:target'], expression='deps(//b:nosuchtarget)')"
        )
        write(
            "b/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'target')"
        )
        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//a:query") })
        events.assertContainsError(
            "in genquery rule //a:query: query failed: no such target '//b:nosuchtarget'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsMissingScopeTarget() {
        write("a/BUILD", "genquery(name='query', scope=['//b:target'], expression='set()')")
        write("b/BUILD")
        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//a:query") })
        events.assertContainsError(
            "in genquery rule //a:query: errors were encountered while computing transitive closure of"
                    + " the scope"
        )
        events.assertContainsError(
            java.util.regex.Pattern.compile(
                "no such target '//b:target': target 'target' not declared in package 'b' defined by"
                        + " .*/b/BUILD"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsMissingTransitiveScopeTarget() {
        write(
            "a/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        genquery(
            name = "query",
            expression = "set()",
            scope = [":missingdep"],
        )

        foo_library(
            name = "missingdep",
            deps = ["//b:target"],
        )
        
        """.trimIndent()
        )
        write("b/BUILD")
        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//a:query") })
        events.assertContainsError(
            "in genquery rule //a:query: errors were encountered while computing transitive closure of"
                    + " the scope"
        )
        events.assertContainsError(
            java.util.regex.Pattern.compile(
                "no such target '//b:target': target 'target' not declared in package 'b' defined by"
                        + " .*/b/BUILD"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsMissingScopePackage() {
        write("a/BUILD", "genquery(name='query', scope=['//b:target'], expression='set()')")
        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//a:query") })
        events.assertContainsError(
            "in genquery rule //a:query: errors were encountered while computing transitive closure of"
                    + " the scope"
        )
        events.assertContainsError(
            java.util.regex.Pattern.compile(
                ("no such package 'b': BUILD file not found in any of the following directories. Add a"
                        + " BUILD file to a directory to mark it as a package.\n"
                        + " - b")
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsMissingTransitiveScopePackage() {
        write(
            "a/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        genquery(
            name = "query",
            expression = "set()",
            scope = [":missingdep"],
        )

        foo_library(
            name = "missingdep",
            deps = ["//b:target"],
        )
        
        """.trimIndent()
        )
        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//a:query") })
        events.assertContainsError(
            "in genquery rule //a:query: errors were encountered while computing transitive closure"
                    + " of the scope"
        )
        events.assertContainsError(
            java.util.regex.Pattern.compile(
                ("no such package 'b': BUILD file not found in any of the following"
                        + " directories. Add a BUILD file to a directory to mark it as a package.\n"
                        + " - b")
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiplePatternsInQuery() {
        var buildFile = "load('//test_defs:foo_library.bzl', 'foo_library')\n"
        var genQuery =
            "genquery(name = 'q', scope = [':top'], expression = 'deps(//spices:top) ' + \n"
        var topTarget = "foo_library(name = 'top', deps = [\n"
        for (i in 0..19) {
            val targetName = (if (i % 2 == 0) "in" else "out") + i
            buildFile += "foo_library(name = '" + targetName + "')\n"
            if (i % 2 != 0) {
                genQuery += "' - //spices:" + targetName + " ' + \n"
            }
            topTarget += "    ':" + targetName + "',\n"
        }
        topTarget += "]\n)\n"
        genQuery += "'')"
        write("spices/BUILD", buildFile, topTarget, genQuery)
        val expected: MutableList<String?> = java.util.ArrayList<String?>(11)
        var i = 0
        while (i < 20) {
            expected.add(i / 2, "//spices:in" + i)
            i += 2
        }
        expected.add(0, "//spices:top")
        Collections.sort<String?>(expected)
        assertQueryResult("//spices:q", *expected.toTypedArray<String?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGraphOutput_factored() {
        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [
                ":coconut",
                ":mango",
                ":papaya",
            ],
        )

        foo_library(name = "papaya")

        foo_library(name = "mango")

        foo_library(name = "coconut")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            opts = ["--output=graph"],
            scope = [":melon"],
        )
        
        """.trimIndent()
        )
        assertPartialQueryResult(
            "//fruits:q",
            "  \"//fruits:melon\"",
            "  \"//fruits:melon\" -> \"//fruits:coconut\\n//fruits:mango\\n//fruits:papaya\"",
            "  \"//fruits:coconut\\n//fruits:mango\\n//fruits:papaya\""
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGraphOutput_unfactored() {
        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [
                ":coconut",
                ":mango",
                ":papaya",
            ],
        )

        foo_library(name = "papaya")

        foo_library(name = "mango")

        foo_library(name = "coconut")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            opts = [
                "--output=graph",
                "--nograph:factored",
            ],
            scope = [":melon"],
        )
        
        """.trimIndent()
        )
        assertPartialQueryResult(
            "//fruits:q",
            "  \"//fruits:melon\"",
            "  \"//fruits:melon\" -> \"//fruits:coconut\"",
            "  \"//fruits:melon\" -> \"//fruits:mango\"",
            "  \"//fruits:melon\" -> \"//fruits:papaya\"",
            "  \"//fruits:papaya\"",
            "  \"//fruits:mango\"",
            "  \"//fruits:coconut\""
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesntAllowLocationOutputWithLoadfiles() {
        write("foo/bzl.bzl", "x = 2")
        write(
            "foo/BUILD",
            """
        load("//foo:bzl.bzl", "x")

        filegroup(name = "foo")

        genquery(
            name = "gen-loadfiles",
            expression = "loadfiles(//foo:foo)",
            scope = ["//foo"],
        )

        genquery(
            name = "gen-loadfiles-location",
            expression = "loadfiles(//foo:foo)",
            opts = ["--output=location"],
            scope = ["//foo"],
        )
        
        """.trimIndent()
        )
        assertQueryResult("//foo:gen-loadfiles", "//foo:bzl.bzl")
        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//foo:gen-loadfiles-location") })
        events.assertContainsError(
            "in genquery rule //foo:gen-loadfiles-location: query failed: Query expressions "
                    + "involving 'buildfiles' or 'loadfiles' cannot be used with --output=location"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoesntAllowLocationOutputWithBuildfiles() {
        write("foo/bzl.bzl", "x = 2")
        write(
            "foo/BUILD",
            """
        load("//foo:bzl.bzl", "x")

        filegroup(name = "foo")

        genquery(
            name = "gen-buildfiles",
            expression = "buildfiles(//foo:foo)",
            scope = ["//foo"],
        )

        genquery(
            name = "gen-buildfiles-location",
            expression = "buildfiles(//foo:foo)",
            opts = ["--output=location"],
            scope = ["//foo"],
        )
        
        """.trimIndent()
        )
        assertQueryResult("//foo:gen-buildfiles", "//foo:BUILD", "//foo:bzl.bzl")
        org.junit.Assert.assertThrows(
            expectedExceptionClass(),
            org.junit.function.ThrowingRunnable { buildTarget("//foo:gen-buildfiles-location") })
        events.assertContainsError(
            "in genquery rule //foo:gen-buildfiles-location: query failed: Query expressions "
                    + "involving 'buildfiles' or 'loadfiles' cannot be used with --output=location"
        )
    }

    /** Regression test for b/127644784.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun somepathOutputDeterministic() {
        /*
     * This graph structure routinely reproduces the bug within 10 iterations:
     *
     *   ----------top------------
     *   |       |       |       |
     *  mid1    mid2    mid3    mid4
     *   |       |       |       |
     *   --lower--       |       |
     *       |           |       |
     *       -----bottom----------
     */
        write(
            "query/BUILD",
            """
        genquery(
            name = "query",
            expression = "somepath(//top, //bottom)",
            scope = [
                "//top",
                "//bottom",
            ],
        )
        
        """.trimIndent()
        )
        write(
            "top/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'top', deps = ['//mid1', '//mid2', '//mid3', '//mid4'])"
        )
        write(
            "mid1/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'mid1', deps = ['//lower'])"
        )
        write(
            "mid2/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'mid2', deps = ['//lower'])"
        )
        write(
            "mid3/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'mid3', deps = ['//bottom'])"
        )
        write(
            "mid4/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'mid4', deps = ['//bottom'])"
        )
        write(
            "lower/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'lower', deps = ['//bottom'])"
        )
        write(
            "bottom/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bottom')"
        )

        val firstResult = getQueryResult("//query")
        for (i in 0..9) {
            createFilesAndMocks() // Do a clean.
            Truth.assertThat(getQueryResult("//query")).isEqualTo(firstResult)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun runNodepDepsTest(optsStringValue: String?, expectVisibilityDep: Boolean) {
        write(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "t1",
            visibility = [
                ":pg",
                "//query:__pkg__",
            ],
            deps = [":t2"],
        )

        foo_library(name = "t2")

        package_group(name = "pg")
        
        """.trimIndent()
        )
        write(
            "query/BUILD",
            "genquery(",
            "  name = 'gen',",
            "  expression = 'deps(//foo:t1)',",
            "  scope = ['//foo:t1'],",
            "  opts = " + optsStringValue,
            ")"
        )

        val queryResultStrings: MutableList<String?> =
            com.google.common.collect.ImmutableList.copyOf<String?>(
                getQueryResult("//query:gen").split("\n".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            )
        if (expectVisibilityDep) {
            Truth.assertThat(queryResultStrings).contains("//foo:pg")
        } else {
            Truth.assertThat(queryResultStrings).doesNotContain("//foo:pg")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNodepDeps_defaultIsFalse() {
        runNodepDepsTest( /* optsStringValue= */"[]",  /* expectVisibilityDep= */false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNodepDeps_false() {
        runNodepDepsTest( /* optsStringValue= */
            "['--nodep_deps=false']",  /* expectVisibilityDep= */false
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNodepDeps_true() {
        runNodepDepsTest( /* optsStringValue= */
            "['--nodep_deps=true']",  /* expectVisibilityDep= */true
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadingPhaseCycle() {
        // This test uses a target in a self-cycle to demonstrate that a genquery rule having a cycle in
        // its scope does not cause it to fail.
        write(
            "cycle/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        genquery(
            name = "gen",
            expression = "//cycle",
            scope = [":cycle"],
        )

        foo_library(
            name = "cycle",
            deps = [":cycle"],
        )
        
        """.trimIndent()
        )
        assertQueryResult("//cycle:gen", "//cycle:cycle")
    }

    @Throws(java.lang.Exception::class)
    private fun writeAspectDefinition(aspectPackage: String?, extraDep: String?) {
        write(aspectPackage + "/BUILD")
        write(
            aspectPackage + "/aspect.bzl",
            "def _aspect_impl(target, ctx):",
            "   return []",
            "def _rule_impl(ctx):",
            "   return []",
            "MyAspect = aspect(",
            "   implementation=_aspect_impl,",
            "   attr_aspects=['deps'],",
            "   attrs = {'_extra_deps': attr.label(default = Label('" + extraDep + "'))})",
            "aspect_rule = rule(",
            "   implementation=_rule_impl,",
            "   attrs = { 'attr' : ",
            "             attr.label_list(mandatory=True, allow_files=True, aspects = [MyAspect]),",
            "             'param' : attr.string(),",
            "           },",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectDepChain() {
        writeAspectDefinition("aspect1", "//middle")
        writeAspectDefinition("aspect2", "//end")
        write(
            "start/BUILD",
            """
        load("//aspect1:aspect.bzl", "aspect_rule")
        load('//test_defs:foo_library.bzl', 'foo_library')

        genquery(
            name = "gen",
            expression = "deps(//start)",
            scope = [":start"],
        )

        aspect_rule(
            name = "start",
            attr = [":startdep"],
        )

        foo_library(name = "startdep")
        
        """.trimIndent()
        )
        write(
            "middle/BUILD",
            """
        load("//aspect2:aspect.bzl", "aspect_rule")
        load('//test_defs:foo_library.bzl', 'foo_library')

        aspect_rule(
            name = "middle",
            attr = [":middledep"],
        )

        foo_library(name = "middledep")
        
        """.trimIndent()
        )
        write(
            "end/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "end",
            deps = [":enddep"],
        )

        foo_library(name = "enddep")
        
        """.trimIndent()
        )
        assertQueryResult(
            "//start:gen",
            "//end:end",
            "//end:enddep",
            "//middle:middle",
            "//middle:middledep",
            "//start:start",
            "//start:startdep"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGenQueryOutputCompressed() {
        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")

        genquery(
            name = "q",
            compressed_output = True,
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        
        """.trimIndent()
        )

        buildTarget("//fruits:q")
        val output: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<Artifact?>(getArtifacts("//fruits:q"))
        val compressedContent: ByteString = readContentAsByteArray(output)

        val decompressedOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        GZIPInputStream(compressedContent.newInput()).use { gzipIn ->
            com.google.common.io.ByteStreams.copy(gzipIn, decompressedOut)
        }
        Truth.assertThat(decompressedOut.toString(java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo("//fruits:melon\n//fruits:papaya\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConsistentLabels() {
        write(
            "fruits/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
            opts = ["--consistent_labels"],
        )
        
        """.trimIndent()
        )
        assertQueryResult("//fruits:q", "@@//fruits:melon", "@@//fruits:papaya")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGenQueryInExternalRepo() {
        if (!AnalysisMock.get().isThisBazel()) {
            return
        }
        write(
            "MODULE.bazel",
            """
        bazel_dep(name = "other_module")
        local_path_override(
            module_name = "other_module",
            path = "other_module",
        )
        
        """.trimIndent()
        )
        write(
            "other_module/MODULE.bazel",
            """
        module(name = 'other_module')
        
        """.trimIndent()
        )
        write(
            "other_module/fruits/BUILD",
            """
        load('@@//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "melon",
            deps = [":papaya"],
        )

        foo_library(name = "papaya")

        genquery(
            name = "q",
            expression = "deps(//fruits:melon)",
            scope = [":melon"],
        )
        
        """.trimIndent()
        )

        assertQueryResult(
            "@@other_module+//fruits:q",
            "@@other_module+//fruits:melon",
            "@@other_module+//fruits:papaya"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun assertQueryResult(queryTarget: String?, vararg expected: String?) {
        Truth.assertThat<String?>(getQueryResult(queryTarget).split("\n".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray())
            .asList()
            .containsExactlyElementsIn(com.google.common.collect.ImmutableList.copyOf<String?>(expected))
            .inOrder()
    }

    @Throws(java.lang.Exception::class)
    private fun assertPartialQueryResult(queryTarget: String?, vararg expected: String?) {
        Truth.assertThat<String?>(getQueryResult(queryTarget).split("\n".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray())
            .asList()
            .containsAtLeastElementsIn(com.google.common.collect.ImmutableList.copyOf<String?>(expected))
            .inOrder()
    }

    @Throws(java.lang.Exception::class)
    private fun getQueryResult(queryTarget: String?): String? {
        buildTarget(queryTarget)
        val output: Artifact? = com.google.common.collect.Iterables.getOnlyElement<Artifact?>(getArtifacts(queryTarget))
        Truth.assertThat(getAllKeysInGraph().stream().anyMatch { key: SkyKey? -> key is TransitiveTargetKey })
            .isFalse()
        return readContentAsLatin1String(output)
    }

    private fun expectedExceptionClass(): java.lang.Class<out Throwable?> {
        return if (keepGoing) BuildFailedException::class.java else ViewCreationFailedException::class.java
    }
}
