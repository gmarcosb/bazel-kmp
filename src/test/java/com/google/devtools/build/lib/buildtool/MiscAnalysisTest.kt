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

import com.google.devtools.build.lib.actions.BuildFailedException

/**
 * Miscellaneous tests of the analysis phase. (Sometimes it's easier to express these in terms of
 * the BuildTool than of the BuildView because the latter's class interface is quite complex.)
 */
@RunWith(JUnit4::class)
class MiscAnalysisTest : BuildIntegrationTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWarningsNotReplayed() {
        AnalysisMock.get().pySupport().setup(mockToolsConfig)
        write(
            "y/BUILD",
            "genrule(name='y', outs=['y.out'], cmd='touch $@', deprecation='generate a warning')"
        )
        addOptions("--nobuild")

        buildTarget("//y")
        events.assertContainsWarning("target '//y:y' is deprecated")

        buildTarget("//y")
        assertDoesNotContainEvent("target '//y:y' is deprecated")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeprecatedTargetOnCommandLine() {
        write(
            "raspberry/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='raspberry', srcs=['raspberry.sh'], deprecation='rotten')"
        )
        addOptions("--nobuild")
        buildTarget("//raspberry:raspberry")
        events.assertContainsWarning("target '//raspberry:raspberry' is deprecated: rotten")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetAnalyzedInTwoConfigurations_deprecationWarningDisplayedOncePerBuild() {
        // :a depends on :dep in the target configuration. :b depends on :dep in the exec configuration.
        write(
            "foo/BUILD",
            """
        genrule(
            name = "a",
            srcs = [":dep"],
            outs = ["a.out"],
            cmd = "touch ${'$'}@",
        )

        genrule(
            name = "b",
            outs = ["b.out"],
            cmd = "touch ${'$'}@",
            tools = [":dep"],
        )

        genrule(
            name = "dep",
            srcs = ["//deprecated"],
            outs = ["dep.out"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        write(
            "deprecated/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'deprecated', deprecation = 'old')"
        )
        addOptions("--nobuild")
        buildTarget("//foo:a", "//foo:b")
        events.assertContainsEventWithFrequency(
            "'//foo:dep' depends on deprecated target '//deprecated:deprecated'", 1
        )

        // Edit to force re-analysis.
        write(
            "deprecated/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'deprecated', deprecation = 'very old')"
        )
        buildTarget("//foo:a", "//foo:b")
        events.assertContainsEventWithFrequency(
            "'//foo:dep' depends on deprecated target '//deprecated:deprecated'", 1
        )
    }

    // Regression test for http://b/12465751: "IllegalStateException in ParallelEvaluator".
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShBinaryTwoSrcs() {
        write(
            "test_defs/foo_one.bzl",
            """
        def _impl(ctx):
          if len(ctx.files.srcs) != 1:
             fail("you must specify exactly one file in 'srcs'", attr = "srcs")
        foo_one = rule(
          implementation = _impl,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
          },
        )
        
        """.trimIndent()
        )
        write(
            "sh/BUILD",
            "load('//test_defs:foo_one.bzl', 'foo_one')",
            "foo_one(name = 'double', srcs = ['a','b'])"
        )
        addOptions("--nobuild")

        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//sh:double") })
        events.assertContainsError("you must specify exactly one file in 'srcs'")
    }

    // Note that the cache_analysis flag has been deleted, as it is now standard app behavior.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisCachingAndKeepGoing() {
        write(
            "fruit/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "apple",
            deps = [":banana"],
        )

        cc_library(
            name = "banana",
            deps = [":cherry"],
        )

        cc_library(
            name = "cherry",
            deps = [":durian__hdrs__"],
        )

        genrule(
            name = "durian",
            outs = ["durian.out"],
            cmd = ":",
        )
        
        """.trimIndent()
        )
        addOptions("--nobuild", "--keep_going")

        var e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//fruit:apple") })
        assertThat(e).hasMessageThat().contains("command succeeded")
        events.assertContainsError(
            "in deps attribute of cc_library rule //fruit:cherry: "
                    + "target '//fruit:durian__hdrs__' does not exist"
        )

        e = org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//fruit:apple") })
        assertThat(e).hasMessageThat().contains("command succeeded")
        events.assertContainsError(
            "in deps attribute of cc_library rule //fruit:cherry: "
                    + "target '//fruit:durian__hdrs__' does not exist"
        )
    }

    // Note that the cache_analysis flag has been deleted, as it is now standard app behavior.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorsAreReplayedEvenWithAnalysisCaching() {
        write(
            "fruit/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "apple",
            deps = [":banana__hdrs__"],
        )

        genrule(
            name = "banana",
            outs = ["banana.out"],
            cmd = ":",
        )
        
        """.trimIndent()
        )
        addOptions("--nobuild")

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//fruit:apple") })
        events.assertContainsError(
            "in deps attribute of cc_library rule //fruit:apple: "
                    + "target '//fruit:banana__hdrs__' does not exist"
        )

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//fruit:apple") })
        events.assertContainsError(
            "in deps attribute of cc_library rule //fruit:apple: "
                    + "target '//fruit:banana__hdrs__' does not exist"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildAllAndParsingError() {
        write(
            "pkg/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_binary')",
            "java_binary(",
            "name = \"foo\",",
            "  syntax error here",
            ")"
        )

        addOptions("--nobuild")

        val e: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//pkg:all") })
        events.assertContainsError("syntax error at 'error'")
        assertPkgErrorMsg(e)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDiscardAnalysisCache() {
        write(
            "sh/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(
            name = "sh",
            srcs = [],
            deps = [":dep"],
        )

        foo_library(
            name = "dep",
            srcs = [],
        )
        
        """.trimIndent()
        )
        buildTarget("//sh:sh")
        // We test with dep because we may keep references to the top-level configured targets.
        val ct: ConfiguredTarget? = getConfiguredTarget("//sh:dep")
        addOptions("--discard_analysis_cache")
        buildTarget("//sh:sh")
        addOptions("--nodiscard_analysis_cache")
        buildTarget("//sh:sh")
        // Configured target was replaced.
        var newCt: ConfiguredTarget? = getConfiguredTarget("//sh:dep")
        assertThat(newCt).isNotSameInstanceAs(ct)
        val ref: java.lang.ref.WeakReference<ConfiguredTarget?> = java.lang.ref.WeakReference<ConfiguredTarget?>(newCt)
        newCt = null
        addOptions("--discard_analysis_cache")
        buildTarget("//sh:sh")
        GcFinalization.awaitClear(ref)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDiscardAnalysisCacheWithError() {
        write(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "x",
            deps = [":z__hdrs__"],
        )

        genrule(
            name = "z",
            outs = ["z.out"],
            cmd = ":",
        )
        
        """.trimIndent()
        )
        write("y/BUILD", "load('//test_defs:foo_library.bzl', 'foo_library')", "foo_library(name='y')")
        addOptions("--discard_analysis_cache", "--keep_going")
        val collector: EventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.STDERR)
        events.addHandler(collector)
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//x:x", "//y:y") })
        events.assertContainsError(
            "in deps attribute of cc_library rule //x:x: target '//x:z__hdrs__' does not exist"
        )
        MoreAsserts.assertContainsEvent(
            collector,
            "Target //y:y up-to-date",
            com.google.devtools.build.lib.events.EventKind.STDERR
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildAllAndEvaluationError() {
        write(
            "pkg/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = "foo",
            srcs = unknown_value,
        )
        
        """.trimIndent()
        )

        addOptions("--nobuild")

        val e: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//pkg:all") })
        events.assertContainsError("name 'unknown_value' is not defined")
        assertPkgErrorMsg(e)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoTestTargetsFoundMessageForBuildCommand() {
        write("pkg/BUILD")
        for (option in com.google.common.collect.ImmutableList.of<String?>("", "--nobuild", "--noanalyze")) {
            setupOptions()
            addOptions(TestConstants.PRODUCT_SPECIFIC_BUILD_LANG_OPTIONS)
            addOptions(option)
            buildTarget("//pkg:all")
            assertDoesNotContainEvent("test target")
        }
    }

    companion object {
        private fun assertPkgErrorMsg(e: java.lang.Exception?) {
            Truth.assertThat(e).hasMessageThat().containsMatch("[pP]ackage.*contains errors")
        }
    }
}
