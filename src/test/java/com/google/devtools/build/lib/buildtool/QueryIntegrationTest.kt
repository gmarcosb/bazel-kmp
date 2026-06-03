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

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Integration tests for 'blaze query'.  */
@RunWith(TestParameterInjector::class)
class QueryIntegrationTest : BuildIntegrationTestCase() {
    private val fs = CustomFileSystem()
    private val syscallCache: SyscallCache = DefaultSyscallCache.newBuilder().build()

    private val options: MutableList<String?> = java.util.ArrayList<String?>()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.getRuntimeBuilder()
            .addBlazeModule(
                object : BlazeModule() {
                    public override fun workspaceInit(
                        runtime: BlazeRuntime?, directories: BlazeDirectories?, builder: WorkspaceBuilder
                    ) {
                        builder.setSyscallCache(syscallCache)
                    }
                })

    override fun additionalEventsToCollect(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?> {
        return com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.events.EventKind?>(
            com.google.devtools.build.lib.events.EventKind.STDOUT,
            com.google.devtools.build.lib.events.EventKind.STDERR,
            com.google.devtools.build.lib.events.EventKind.PROGRESS
        )
    }

    private class CustomFileSystem : UnixFileSystem(
        DigestHashFunction.SHA256,  /* hashAttributeName= */
        "",
        NativePosixFilesServiceImpl()
    ) {
        val stubbedStats: MutableMap<PathFragment?, FileStatus?> = HashMap<PathFragment?, FileStatus?>()
        val watchedPaths: MutableMap<PathFragment?, java.lang.Runnable?> =
            ConcurrentHashMap<PathFragment?, java.lang.Runnable?>()

        fun stubStat(path: Path, stubbedResult: FileStatus?) {
            stubbedStats.put(path.asFragment(), stubbedResult)
        }

        @Throws(IOException::class)
        public override fun statIfFound(path: PathFragment?, followSymlinks: Boolean): FileStatus? {
            val runnable: java.lang.Runnable? = watchedPaths.get(path)
            if (runnable != null) {
                runnable.run()
            }
            if (stubbedStats.containsKey(path)) {
                return stubbedStats.get(path)
            }
            return super.statIfFound(path, followSymlinks)
        }
    }

    private class QueryOutput(blazeCommandResult: BlazeCommandResult, stdout: ByteArray) {
        private val blazeCommandResult: BlazeCommandResult
        val stdout: ByteArray

        init {
            this.blazeCommandResult = blazeCommandResult
            this.stdout = stdout
        }

        fun getBlazeCommandResult(): BlazeCommandResult {
            return blazeCommandResult
        }
    }

    private class ProtoQueryOutput(queryOutput: QueryOutput?, queryResult: QueryResult) {
        private val queryResult: QueryResult
        val queryOutput: QueryOutput?

        init {
            this.queryResult = queryResult
            this.queryOutput = queryOutput
        }

        fun getQueryResult(): QueryResult {
            return queryResult
        }
    }

    override fun createFileSystem(): FileSystem {
        return fs
    }

    @Before
    fun setQueryOptions() {
        runtimeWrapper.addOptionsClass(QueryOptions::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProtoUnorderedAndOrdered() {
        val expected: MutableList<String?> = java.util.ArrayList<String?>(NUM_DEPS + 1)
        var targets = ""
        var depString = ""
        for (i in 0..<NUM_DEPS) {
            val dep = i.toString()
            depString += "'" + dep + "', "
            expected.add("//foo:" + dep)
            targets += "foo_library(name = '" + dep + "')\n"
        }
        expected.add("//foo:a")
        Collections.sort<String?>(expected, Collections.reverseOrder<String?>())
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a', deps = [" + depString + "])",
            targets
        )
        var result = getProtoQueryResult("deps(//foo:a)")
        assertSameElementsDifferentOrder(getTargetNames(result.getQueryResult()), expected)
        options.add("--order_output=full")
        result = getProtoQueryResult("deps(//foo:a)")
        Truth.assertThat(getTargetNames(result.getQueryResult()))
            .containsExactlyElementsIn(expected)
            .inOrder()
    }

    /**
     * Test that {min,max}rank work as expected with ordering. Since minrank and maxrank have special
     * handling for cycles in the graph, we put a cycle in to exercise that code.
     */
    @Throws(java.lang.Exception::class)
    private fun assertRankUnorderedAndOrdered(minRank: Boolean) {
        val expected: MutableList<String?> = java.util.ArrayList<String?>(2 * NUM_DEPS + 1)
        // The build file looks like:
        // foo_library(name = 'a', deps = ['cycle1', '1', '2', ..., ]
        // foo_library(name = '1')
        // ...
        // foo_library(name = 'n')
        // foo_library(name = 'cycle0', deps = ['cyclen'])
        // foo_library(name = 'cycle1', deps = ['cycle0'])
        // ...
        // foo_library(name = 'cyclen', deps = ['cycle{n-1}'])
        var targets = ""
        var depString = ""
        for (i in 0..<NUM_DEPS) {
            val dep = i.toString()
            depString += "'" + dep + "', "
            expected.add("1 //foo:" + dep)
            expected.add("1 //foo:cycle" + dep)
            targets += "foo_library(name = '" + dep + "')\n"
            targets += "foo_library(name = 'cycle" + dep + "', deps = ['cycle"
            if (i > 0) {
                targets += i - 1
            } else {
                targets += NUM_DEPS - 1
            }
            targets += "'])\n"
        }
        Collections.sort<String?>(expected)
        expected.add(0, "0 //foo:a")
        options.add("--output=" + (if (minRank) "minrank" else "maxrank"))
        options.add("--keep_going")
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a', deps = ['cycle0', " + depString + "])",
            targets
        )
        var result = getStringQueryResult("deps(//foo:a)")
        Truth.assertWithMessage(result.toString()).that(result.get(0)).isEqualTo("0 //foo:a")
        assertSameElementsDifferentOrder(result, expected)
        options.add("--order_output=full")
        result = getStringQueryResult("deps(//foo:a)")
        Truth.assertWithMessage(result.toString()).that(result.get(0)).isEqualTo("0 //foo:a")
        Truth.assertThat(result).containsExactlyElementsIn(expected).inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMinrankUnorderedAndOrdered() {
        assertRankUnorderedAndOrdered(true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaxrankUnorderedAndOrdered() {
        assertRankUnorderedAndOrdered(false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelOrderedFullAndDeps() {
        val expected: MutableList<String?> = java.util.ArrayList<String?>(NUM_DEPS + 1)
        var targets = ""
        var depString = ""
        for (i in 0..<NUM_DEPS) {
            val dep = i.toString()
            depString += "'" + dep + "', "
            expected.add("//foo:" + dep)
            targets += "foo_library(name = '" + dep + "')\n"
        }
        expected.add("//foo:a")
        Collections.sort<String?>(expected)
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a', deps = [" + depString + "])",
            targets
        )
        var result = getStringQueryResult("deps(//foo:a)")
        Truth.assertThat(result).containsExactlyElementsIn(expected).inOrder()
        options.add("--order_output=deps")
        result = getStringQueryResult("deps(//foo:a)")
        assertSameElementsDifferentOrder(result, expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInputFileElementContainsPackageGroups() {
        write(
            "fruit/BUILD",
            """
        package_group(
            name = "coconut",
            packages = ["//fruit/walnut"],
        )

        exports_files(
            ["chestnut"],
            visibility = [":coconut"],
        )
        
        """.trimIndent()
        )

        val result: org.w3c.dom.Document? = getXmlQueryResult("//fruit:chestnut")
        val resultNode: org.w3c.dom.Element? = getResultNode(result, "//fruit:chestnut")

        Truth.assertThat(
            com.google.common.collect.Iterables.getOnlyElement<org.w3c.dom.Node?>(
                xpathSelect(resultNode, "package-group[@name='//fruit:coconut']")
            )
        )
            .isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonStrictTests() {
        write(
            "donut/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_binary(
            name = "thief",
            srcs = ["thief.sh"],
        )

        foo_test(
            name = "shop",
            srcs = ["shop.cc"],
        )

        test_suite(
            name = "cop",
            tests = [
                ":shop",
                ":thief",
            ],
        )
        
        """.trimIndent()
        )

        // This should not throw an exception, and return 0 targets.
        val result = getProtoQueryResult("tests(//donut:cop)")
        val queryResult: QueryResult = result.getQueryResult()
        assertThat(queryResult.getTargetCount()).isEqualTo(1)
        assertThat(queryResult.getTarget(0).getRule().getName()).isEqualTo("//donut:shop")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStrictTests() {
        options.add("--strict_test_suite=true")
        write(
            "donut/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        foo_binary(
            name = "thief",
            srcs = ["thief.sh"],
        )

        test_suite(
            name = "cop",
            tests = [":thief"],
        )
        
        """.trimIndent()
        )

        val result = getProtoQueryResult("tests(//donut:cop)")
        val blazeCommandResult: BlazeCommandResult = result.queryOutput!!.getBlazeCommandResult()
        assertExitCode(result.queryOutput, ExitCode.ANALYSIS_FAILURE)
        com.google.common.truth.Subject.contains(
            "The label '//donut:thief' in the test_suite "
                    + "'//donut:cop' does not refer to a test"
        )
    }

    @Throws(IOException::class)
    private fun createBadBarBuild() {
        val barBuildFile: Path =
            write(
                "bar/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'bar/baz')"
            )
        val inconsistentFileStatus: FileStatus =
            object : FileStatus() {
                val isFile: Boolean
                    get() = false

                val isSpecialFile: Boolean
                    get() = false

                val isDirectory: Boolean
                    get() = false

                val isSymbolicLink: Boolean
                    get() = false

                val size: Long
                    get() = 0

                val lastModifiedTime: Long
                    get() = 0

                val lastChangeTime: Long
                    get() = 0

                val nodeId: Long
                    get() = 0
            }
        fs.stubStat(barBuildFile, inconsistentFileStatus)
    }

    // Regression test for b/14248208.
    @Throws(java.lang.Exception::class)
    private fun runInconsistentFileSystem(keepGoing: Boolean) {
        createBadBarBuild()
        if (keepGoing) {
            options.add("--keep_going")
        }
        val result = getQueryResult("deps(//bar:baz)")
        assertExitCode(result, ExitCode.ANALYSIS_FAILURE)
        events.assertContainsError("Inconsistent filesystem operations")
        Truth.assertThat(events.errors()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inconsistentFileSystemKeepGoing() {
        runInconsistentFileSystem( /*keepGoing=*/true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inconsistentFileSystemNoKeepGoing() {
        runInconsistentFileSystem( /*keepGoing=*/false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun depInconsistentFileSystem(@TestParameter keepGoing: Boolean) {
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'foo', deps = ['//bar:baz'])"
        )
        createBadBarBuild()
        if (keepGoing) {
            options.add("--keep_going")
        }
        val result = getQueryResult("deps(//foo:foo)")
        val expectedExitcode: ExitCode? =
            if (keepGoing) ExitCode.PARTIAL_ANALYSIS_FAILURE else ExitCode.ANALYSIS_FAILURE
        assertExitCode(result, expectedExitcode)
        events.assertContainsError("Inconsistent filesystem operations")
        events.assertContainsError("and referenced by '//foo:foo'")
        if (keepGoing) {
            events.assertContainsError("Evaluation of query \"deps(//foo:foo)\" failed: errors were ")
        } else {
            events.assertContainsError(
                "Evaluation of query \"deps(//foo:foo)\" failed: preloading transitive closure failed: "
            )
        }
        // TODO(janakr): We emit duplicate events: in the ErrorPrintingTargetEdgeErrorObserver and in
        //  TransitiveTargetFunction. Should be able to remove one of them, most likely
        //  TransitiveTargetFunction.
        Truth.assertThat(events.errors()).hasSize(if (keepGoing) 3 else 2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidQueryFailsParsing() {
        val result = getQueryResult("deps(\"--bad_target_name_from_bad_script\")")

        assertCommandLineErrorExitCode(result)
        Truth.assertThat(result.stdout).isEmpty()
        events.assertContainsError("target literal must not begin with (-)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun siblingsFunction() {
        write(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(name = "t1")

        foo_library(name = "t2")

        foo_library(name = "t3")

        foo_library(name = "t4")

        foo_library(name = "t5")
        
        """.trimIndent()
        )

        val result = getQueryResult("siblings(//foo:t1)")
        assertSuccessfulExitCode(result)
        Truth.assertThat(result.stdout).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun samePackageDirectRDepsFunction() {
        write(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_library(
            name = "t1",
            srcs = ["t1.sh"],
        )

        foo_library(
            name = "t2",
            srcs = ["t2.sh"],
        )

        foo_library(
            name = "t3",
            srcs = ["t2.sh"],
        )
        
        """.trimIndent()
        )

        val result = getQueryResult("same_pkg_direct_rdeps(//foo:t1.sh)")
        assertSuccessfulExitCode(result)

        assertQueryOutputContains(result, "//foo:t1")
        assertQueryOutputDoesNotContain(result, "//foo:t2", "/foo:t3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun graphlessQuery() {
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='foo', srcs=['foo.sh'])"
        )

        val result =
            getQueryResult("//foo", "--experimental_graphless_query", "--order_output=no")
        assertSuccessfulExitCode(result)
        assertQueryOutputContains(result, "//foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun graphlessQueryRequiresUnorderedOutput() {
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='foo', srcs=['foo.sh'])"
        )

        val result =
            getQueryResult("//foo", "--experimental_graphless_query", "--order_output=deps")
        events.assertContainsError(
            "--experimental_graphless_query requires --order_output=no or --order_output=auto"
        )
        assertCommandLineErrorExitCode(result)
        Truth.assertThat(result.stdout).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun graphlessQueryRequiresStreamedFormatter() {
        write(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='foo', srcs=['foo.sh'])"
        )

        val result =
            getQueryResult(
                "//foo", "--experimental_graphless_query", "--order_output=no", "--output=maxrank"
            )

        assertCommandLineErrorExitCode(result)
        Truth.assertThat(result.stdout).isEmpty()
        events.assertContainsError(
            "--experimental_graphless_query requires --order_output=no or --order_output=auto and an"
                    + " --output option that supports streaming"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleStackInBuildOutput() {
        /*
     * See b/151165647 - This needs a non-trivial package name to avoid
     * including extraneous directories in the generator_location.
     */

        write(
            "package/inc.bzl",
            """
        def _impl(ctx): pass
        myrule = rule(implementation = _impl)
        def f():
          g()
        def g():
          myrule(name='a')
        
        """.trimIndent()
        )

        write("package/BUILD", "load('inc.bzl', 'f')\n" + "f()")

        val result = getQueryResult("//package:a", "--output=build")
        assertSuccessfulExitCode(result)
        // TODO(b/151165647): fix the heuristic that incorrectly creates generator_location by//
        //  relativizing package name "p" relative to /foo/tmp/ regardless of segment boundaries.
        // TODO(b/151151653): the output should contain only workspace-relative paths.
        val workspaceDir = getWorkspace().toString()
        val expectedOut =
            ("# "
                    + workspaceDir
                    + "/package/BUILD:2:2\n"
                    + "myrule(\n"
                    + "  name = \"a\",\n"
                    + "  generator_name = \"a\",\n"
                    + "  generator_function = \"f\",\n"
                    + "  generator_location = "
                    + "\"package/BUILD:2:2\",\n"
                    + ")\n"
                    + "# Rule a instantiated at (most recent call last):\n"
                    + "#   "
                    + workspaceDir
                    + "/package/BUILD:2:2   in <toplevel>\n"
                    + "#   "
                    + workspaceDir
                    + "/package/inc.bzl:4:4 in f\n"
                    + "#   "
                    + workspaceDir
                    + "/package/inc.bzl:6:9 in g\n"
                    + "# Rule myrule defined at (most recent call last):\n"
                    + "#   "
                    + workspaceDir
                    + "/package/inc.bzl:2:14 in <toplevel>\n\n")

        val out = String(result.stdout, java.nio.charset.StandardCharsets.UTF_8)

        Truth.assertThat(out).isEqualTo(expectedOut)
    }

    /*
   * Test of instantiation_stack (b/36593041) through query --output=build
   */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleStackInProtoOutput() {
        write(
            "p/inc.bzl",
            """
        def _impl(ctx): pass
        myrule = rule(implementation = _impl)
        def f():
          g()
        def g():
          myrule(name='a')
        
        """.trimIndent()
        )

        write(
            "p/BUILD",
            """
        load('inc.bzl', 'f')
        f()
        
        """.trimIndent()
        )
        val result =
            getProtoQueryResult("//p:a", "--output=proto", "--proto:instantiation_stack=true")
        assertSuccessfulExitCode(result.queryOutput!!)

        val expectedProtoOut =
            ("    instantiation_stack: \"p/BUILD:2:2: <toplevel>\"\n"
                    + "    instantiation_stack: \"p/inc.bzl:4:4: f\"\n"
                    + "    instantiation_stack: \"p/inc.bzl:6:9: g\"")
        val actualProtoOut: String? = result.getQueryResult().toString()

        Truth.assertThat(actualProtoOut).contains(expectedProtoOut)
    }

    /*
   * Regression test for b/162110273.
   */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleStackRegressionTest() {
        /*
     * See b/151165647 - This needs a non-trivial package name to avoid
     * including extraneous directories in the generator_location.
     */

        write(
            "package/inc.bzl",
            """
        def g(name):
            native.filegroup(name = name)

        def f(name):
            g(name)
        
        """.trimIndent()
        )

        write(
            "package/BUILD",
            """
        load("inc.bzl", "f")
        f(name = "a")
        f(name = "b")
        
        """.trimIndent()
        )
        val result = getQueryResult("//package:all", "--output=build")
        assertSuccessfulExitCode(result)

        val workspaceDir = getWorkspace().toString()
        val expectedOut =
            ("# "
                    + workspaceDir
                    + "/package/BUILD:2:2\n"
                    + "filegroup(\n"
                    + "  name = \"a\",\n"
                    + "  generator_name = \"a\",\n"
                    + "  generator_function = \"f\",\n"
                    + "  generator_location = "
                    + "\"package/BUILD:2:2\",\n"
                    + ")\n"
                    + "# Rule a instantiated at (most recent call last):\n"
                    + "#   "
                    + workspaceDir
                    + "/package/BUILD:2:2    in <toplevel>\n"
                    + "#   "
                    + workspaceDir
                    + "/package/inc.bzl:5:6  in f\n"
                    + "#   "
                    + workspaceDir
                    + "/package/inc.bzl:2:21 in g\n"
                    + "\n"
                    + "# "
                    + workspaceDir
                    + "/package/BUILD:3:2\n"
                    + "filegroup(\n"
                    + "  name = \"b\",\n"
                    + "  generator_name = \"b\",\n"
                    + "  generator_function = \"f\",\n"
                    + "  generator_location = "
                    + "\"package/BUILD:3:2\",\n"
                    + ")\n"
                    + "# Rule b instantiated at (most recent call last):\n"
                    + "#   "
                    + workspaceDir
                    + "/package/BUILD:3:2    in <toplevel>\n"
                    + "#   "
                    + workspaceDir
                    + "/package/inc.bzl:5:6  in f\n"
                    + "#   "
                    + workspaceDir
                    + "/package/inc.bzl:2:21 in g\n\n")

        val out = String(result.stdout, java.nio.charset.StandardCharsets.UTF_8)
        Truth.assertThat(out).isEqualTo(expectedOut)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun depthBoundedQuery(@TestParameter orderResults: Boolean) {
        if (orderResults) {
            options.add("--order_output=auto")
        } else {
            options.add("--order_output=no")
            options.add("--universe_scope=//depth:*")
        }

        write(
            "depth/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        load('//test_defs:foo_library.bzl', 'foo_library')

        foo_binary(
            name = "one",
            srcs = ["one.sh"],
            deps = [":two"],
        )

        foo_library(
            name = "two",
            srcs = ["two.sh"],
            deps = [
                ":div2",
                ":three",
                "//depth2:three",
            ],
        )

        foo_library(
            name = "three",
            srcs = ["three.sh"],
            deps = [":four"],
        )

        foo_library(
            name = "four",
            srcs = ["four.sh"],
            deps = [
                ":div2",
                ":five",
            ],
        )

        foo_library(
            name = "five",
            srcs = ["five.sh"],
        )

        foo_library(
            name = "div2",
            srcs = ["two.sh"],
        )
        
        """.trimIndent()
        )

        write(
            "depth2/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'three', srcs = ['three.sh'])"
        )
        write("depth/one.sh", "")
        write("depth/two.sh", "")
        write("depth/three.sh", "")
        write("depth/four.sh", "")
        write("depth/five.sh", "")

        write("depth2/three.sh", "")

        val oneDep = getQueryResult("deps(//depth:one, 1)")
        assertQueryOutputContains(oneDep, "//depth:one.sh", "//depth:two")
        assertQueryOutputDoesNotContain(oneDep, "//depth2")

        // Ensure that the whole transitive closure wasn't pulled in earlier if not pre-loading.
        val threeDep =
            getQueryResult("deps(//depth:one, 3)", "--experimental_ui_debug_all_events")

        if (orderResults) {
            assertContainsEvent(com.google.devtools.build.lib.events.EventKind.PROGRESS, "Loading package: depth2")
        }

        assertQueryOutputContains(
            threeDep,
            "//depth:one",
            "//depth:one.sh",
            "//depth:two",
            "//depth:two.sh",
            "//depth:div2",
            "//depth:three",
            "//depth:three.sh",
            "//depth:four",
            "//depth2:three",
            "//depth2:three.sh"
        )

        val oneDepNonExperimental = getQueryResult("deps(//depth:one, 3)")

        /*
     * --experimental_ui_debug_all_events and expect_query_targets are not
     * mutually compatible at this time, so we run this again to check that the
     * output is exact rather than a superset.
     */
        assertQueryOutputContains(
            oneDepNonExperimental,
            "//depth:one",
            "//depth:one.sh",
            "//depth:two",
            "//depth:two.sh",
            "//depth:div2",
            "//depth:three",
            "//depth:three.sh",
            "//depth:four",
            "//depth2:three",
            "//depth2:three.sh"
        )

        events.clear()

        val twoDep =
            getQueryResult("deps(//depth:one, 2)", "--experimental_ui_debug_all_events")

        // Restricting the query, however, should not cause reloading.
        assertDoesNotContainEvent("Loading package:")

        assertQueryOutputContains(
            twoDep,
            "//depth:one",
            "//depth:one.sh",
            "//depth:two",
            "//depth:two.sh",
            "//depth:three",
            "//depth:div2",
            "//depth2:three"
        )

        // Same as above
        val twoDepNonExperimental = getQueryResult("deps(//depth:one, 2)")

        assertQueryOutputContains(
            twoDepNonExperimental,
            "//depth:one",
            "//depth:one.sh",
            "//depth:two",
            "//depth:two.sh",
            "//depth:three",
            "//depth:div2",
            "//depth2:three"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inconsistentSkyQueryIncremental() {
        write("foo/BUILD")
        val barFile: PathFragment = PathFragment.create("bar/BUILD")
        val bar: PathFragment = barFile.getParentDirectory()
        val badFile: Path = write(barFile.getPathString())
        fs.stubStat(badFile, null)
        val directoryListingLatch: CountDownLatch = CountDownLatch(1)
        getSkyframeExecutor()
            .getEvaluator()
            .injectGraphTransformerForTesting(
                NotifyingHelper.makeNotifyingTransformer(
                    NotifyingHelper.Listener { key: SkyKey?, type: NotifyingHelper.EventType?, order: NotifyingHelper.Order?, context: Any? ->
                        if (NotifyingHelper.EventType.IS_READY == type
                            && FileStateKey.FILE_STATE.equals(key.functionName())
                            && barFile.equals((key.argument() as RootedPath).getRootRelativePath())
                        ) {
                            TrackingAwaiter.INSTANCE.awaitLatchAndTrackExceptions(
                                directoryListingLatch, "Directory never listed"
                            )
                        } else if (NotifyingHelper.EventType.SET_VALUE == type
                            && NotifyingHelper.Order.AFTER == order
                            && SkyFunctions.DIRECTORY_LISTING_STATE.equals(key.functionName())
                            && bar.equals((key.argument() as RootedPath).getRootRelativePath())
                        ) {
                            directoryListingLatch.countDown()
                        }
                    })
            )
        val queryResult =
            getQueryResult("set()", "--universe_scope=//bar/...", "-k", "--order_output=no")
        assertThat(
            queryResult
                .getBlazeCommandResult()
                .getDetailedExitCode()
                .getFailureDetail()
                .getPackageLoading()
                .getCode()
        )
            .isEqualTo(FailureDetails.PackageLoading.Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR)
        Truth.assertThat(directoryListingLatch.await(0, TimeUnit.SECONDS)).isTrue()
        TrackingAwaiter.INSTANCE.assertNoErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skyQueryStatExtensionPackage() {
        write("foo/BUILD", "load('//foo/bar:bar.bzl', 'sym')")
        write("foo/bar/bar.bzl", "sym = 0")
        val barBuild: Path = write("foo/bar/BUILD")
        val barBuildCount: AtomicInteger = AtomicInteger(0)
        fs.watchedPaths.put(barBuild.asFragment(), java.lang.Runnable { barBuildCount.incrementAndGet() })
        val queryResult =
            getQueryResult("buildfiles(//foo:*)", "--universe_scope=//foo/...", "--order_output=no")
        assertQueryOutputContains(queryResult, "//foo:BUILD", "//foo/bar:BUILD", "//foo/bar:bar.bzl")
        Truth.assertThat(barBuildCount.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skyQueryExtensionPackageBuildFileDeletedAfterStat() {
        write("foo/BUILD", "load('//foo/bar:bar.bzl', 'sym')")
        val barBzl: Path = write("foo/bar/bar.bzl", "sym = 0")
        val barBuild: Path = write("foo/bar/BUILD")
        val barBuildCount: AtomicInteger = AtomicInteger(0)
        fs.watchedPaths.put(barBuild.asFragment(), java.lang.Runnable { barBuildCount.incrementAndGet() })
        fs.watchedPaths.put(
            barBzl.asFragment(),
            java.lang.Runnable {
                syscallCache.clear()
                try {
                    barBuild.delete()
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException(e)
                }
            })
        val queryResult =
            getQueryResult("buildfiles(//foo:*)", "--universe_scope=//foo/...", "--order_output=no")
        assertQueryOutputContains(queryResult, "//foo:BUILD", "//foo/bar:BUILD", "//foo/bar:bar.bzl")
        Truth.assertThat(barBuildCount.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skyQueryExtensionPackageBuildFileNotInUniverseHasError() {
        write("foo/BUILD", "load('//foo/bar:bar.bzl', 'sym')")
        val barBzl: Path = write("foo/bar/bar.bzl", "sym = 0")
        val barBuild: Path = write("foo/bar/BUILD", "bad syntax won't matter")
        val barBuildCount: AtomicInteger = AtomicInteger(0)
        fs.watchedPaths.put(barBuild.asFragment(), java.lang.Runnable { barBuildCount.incrementAndGet() })
        fs.watchedPaths.put(
            barBzl.asFragment(),
            java.lang.Runnable {
                syscallCache.clear()
                try {
                    barBuild.delete()
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException(e)
                }
            })
        val queryResult =
            getQueryResult("buildfiles(//foo:*)", "--universe_scope=//foo:*", "--order_output=no")
        assertQueryOutputContains(queryResult, "//foo:BUILD", "//foo/bar:BUILD", "//foo/bar:bar.bzl")
        Truth.assertThat(barBuildCount.get()).isEqualTo(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nokeepGoingStopsLoadingPackages() {
        val fooBuild: Path =
            write(
                "foo/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'foo', deps = ['//deppackage'])"
            )
        write(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', deps= ['//missing'])"
        )
        fs.watchedPaths.put(
            fooBuild.getParentDirectory().getChild("deppackage").asFragment(),
            java.lang.Runnable { org.junit.Assert.fail("deppackage should not have been statted") })
        val depPackageBuild: PathFragment? = PathFragment.create("deppackage/BUILD")
        getSkyframeExecutor()
            .getEvaluator()
            .injectGraphTransformerForTesting(
                NotifyingHelper.makeNotifyingTransformer(
                    NotifyingHelper.Listener { key: SkyKey?, type: NotifyingHelper.EventType?, order: NotifyingHelper.Order?, context: Any? ->
                        if (order == NotifyingHelper.Order.BEFORE
                            && key.functionName() == SkyFunctions.FILE
                        ) {
                            if (!(key.argument() as RootedPath)
                                    .getRootRelativePath()
                                    .endsWith(depPackageBuild)
                            ) {
                                return@makeNotifyingTransformer
                            }
                            try {
                                java.lang.Thread.sleep(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
                                org.junit.Assert.fail("Should have been interrupted")
                            } catch (e: java.lang.InterruptedException) {
                                // Expected.
                                java.lang.Thread.currentThread().interrupt()
                            }
                        }
                    })
            )
        val queryResult = getQueryResult("deps(//foo:all + //bar:all)", "--nokeep_going")
        assertExitCode(queryResult, ExitCode.ANALYSIS_FAILURE)
        assertDoesNotContainEvent("deppackage")
    }

    // Regression test for b/454393488.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun crossPackageWithValidOutputFile() {
        write("package/subpkg/BUILD")

        // Add a rule with an implicit output file with a name which has a prefix. This facilitates a
        // target with name "subpkg/foo", which incorrectly crosses package boundary "subpkg/", but its
        // output file is "prefixsubpkg/foo", which is a valid name.
        write(
            "package/rule.bzl",
            """
        def _impl(ctx): pass
        my_rule = rule(implementation = _impl,
                      outputs = {"o": "prefix%{name}.out"})
        
        """.trimIndent()
        )
        write(
            "package/BUILD",
            """
        load("//package:rule.bzl", "my_rule")
        my_rule(name = "valid_lib")
        my_rule(name = "subpkg/invalid_lib")
        
        """.trimIndent()
        )

        val result =
            getQueryResult("visible(//package:valid_lib, //package:prefixsubpkg/invalid_lib.out)")
        // Expect an analysis failure and not a complete crash.
        assertExitCode(result, ExitCode.ANALYSIS_FAILURE)
        com.google.common.truth.Subject.contains(
            "'//package:subpkg/invalid_lib' is invalid because 'package/subpkg' is a subpackage"
        )
    }

    private fun assertExitCode(result: QueryOutput, expected: ExitCode?) {
        assertThat(result.getBlazeCommandResult().getExitCode()).isEqualTo(expected)
    }

    private fun assertSuccessfulExitCode(result: QueryOutput) {
        assertExitCode(result, ExitCode.SUCCESS)
    }

    private fun assertCommandLineErrorExitCode(result: QueryOutput) {
        assertExitCode(result, ExitCode.COMMAND_LINE_ERROR)
    }

    private fun assertQueryOutputContains(result: QueryOutput, vararg expectedStrings: String?) {
        val out = String(result.stdout, java.nio.charset.StandardCharsets.UTF_8)
        for (expectedString in expectedStrings) {
            Truth.assertThat(out).contains(expectedString)
        }
    }

    private fun assertQueryOutputDoesNotContain(result: QueryOutput, vararg unexpected: String?) {
        val out = String(result.stdout, java.nio.charset.StandardCharsets.UTF_8)
        for (log in unexpected) {
            Truth.assertThat(out).doesNotContain(log)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun getQueryResult(queryString: String?, vararg flags: String?): QueryOutput {
        Collections.addAll<String?>(options, *flags)
        setupOptions()
        runtimeWrapper.addOptions(options)
        runtimeWrapper.addOptions(queryString)
        val env: CommandEnvironment = runtimeWrapper.newCommand(QueryCommand::class.java)
        val options: OptionsParsingResult? = env.getOptions()
        for (module in getRuntime().getBlazeModules()) {
            module.beforeCommand(env)
        }

        env.getEventBus()
            .post(
                GotOptionsEvent(
                    getRuntime().getStartupOptionsProvider(),
                    options,
                    InvocationPolicy.getDefaultInstance()
                )
            )

        for (module in getRuntime().getBlazeModules()) {
            env.getSkyframeExecutor().injectExtraPrecomputedValues(module.getPrecomputedValues())
        }

        // In this test we are allowed to omit the beforeCommand; so force setting of a command
        // id in the CommandEnvironment, as we will need it in a moment even though we deviate from
        // normal calling order.
        try {
            env.getCommandId()
        } catch (e: java.lang.IllegalArgumentException) {
            // Ignored, as we know the test deviates from normal calling order.
        }

        val stdout: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        env.getReporter()
            .addHandler(
                { event ->
                    if (event.getKind().equals(com.google.devtools.build.lib.events.EventKind.STDOUT)) {
                        try {
                            stdout.write(event.getMessageBytes())
                        } catch (e: IOException) {
                            throw java.lang.IllegalStateException(e)
                        }
                    }
                })
        val lastBlazeCommandResult: BlazeCommandResult = QueryCommand().exec(env, options)
        for (module in getRuntime().getBlazeModules()) {
            module.afterCommand()
        }
        return QueryOutput(lastBlazeCommandResult, stdout.toByteArray())
    }

    @Throws(java.lang.Exception::class)
    private fun getXmlQueryResult(queryString: String?): org.w3c.dom.Document? {
        options.add("--output=xml")
        val queryResult = getQueryResult(queryString).stdout
        return DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(queryResult))
    }

    @Throws(java.lang.Exception::class)
    private fun getStringQueryResult(queryString: String?): MutableList<String?> {
        val result = getQueryResult(queryString)
        return java.util.Arrays.asList<String?>(
            *String(result.stdout, java.nio.charset.Charset.defaultCharset()).split(
                "\n".toRegex()
            ).dropLastWhile { it.isEmpty() }.toTypedArray()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun getProtoQueryResult(queryString: String?, vararg flags: String?): ProtoQueryOutput {
        options.add("--output=proto")
        Collections.addAll<String?>(options, *flags)
        val result = getQueryResult(queryString)
        val stdout = result.stdout
        val queryResult: QueryResult = QueryResult.parseFrom(stdout, ExtensionRegistry.getEmptyRegistry())

        return ProtoQueryOutput(result, queryResult)
    }

    @Throws(java.lang.Exception::class)
    fun getResultNode(xml: org.w3c.dom.Document?, ruleName: String?): org.w3c.dom.Element? {
        return com.google.common.collect.Iterables.getOnlyElement<org.w3c.dom.Node?>(
            xpathSelect(
                xml,
                String.format("/query/*[@name='%s']", ruleName)
            )
        ) as org.w3c.dom.Element?
    }

    companion object {
        // Number large enough that an unordered collection with this many elements will never happen to
        // iterate over them in their "natural" order.
        private const val NUM_DEPS = 1000

        private fun assertSameElementsDifferentOrder(actual: MutableList<String?>, expected: MutableList<String?>) {
            Truth.assertThat(actual).containsExactlyElementsIn(expected)
            var i = 0
            while (i < expected.size) {
                if (actual.get(i) != expected.get(i)) {
                    break
                }
                i++
            }
            Truth.assertWithMessage("Lists should not have been in same order")
                .that(i < expected.size)
                .isTrue()
        }

        private fun getTargetNames(result: QueryResult): MutableList<String?> {
            val results: MutableList<String?> = java.util.ArrayList<String?>()
            for (target in result.getTargetList()) {
                results.add(target.getRule().getName())
            }
            return results
        }

        @Throws(java.lang.Exception::class)
        private fun xpathSelect(doc: org.w3c.dom.Node?, expression: String?): MutableList<org.w3c.dom.Node?> {
            val expr: javax.xml.xpath.XPathExpression =
                javax.xml.xpath.XPathFactory.newInstance().newXPath().compile(expression)
            val result: org.w3c.dom.NodeList = expr.evaluate(doc, XPathConstants.NODESET) as org.w3c.dom.NodeList
            val list: MutableList<org.w3c.dom.Node?> = java.util.ArrayList<org.w3c.dom.Node?>()
            for (i in 0..<result.getLength()) {
                list.add(result.item(i))
            }
            return list
        }
    }
}
