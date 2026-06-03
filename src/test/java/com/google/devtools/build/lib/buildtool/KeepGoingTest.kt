// Copyright 2021 The Bazel Authors. All rights reserved.
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

// For debugging, uncomment these and the call to setupLogging() below.
//
// import com.google.devtools.build.lib.blaze.BlazeRuntime;
// import java.util.logging.Level;

import com.google.devtools.build.lib.actions.Artifact

/** A test of the semantics of the keepGoing flag: continue as much as possible after an error.  */
@RunWith(JUnit4::class)
class KeepGoingTest : BuildIntegrationTestCase() {
    @Before
    fun addOptions() {
        addOptions("--keep_going")
    }

    // "mask" is a bitmask of rules that succeed.
    @Throws(IOException::class)
    private fun writeFiles(mask: Int) {
        // A --> B --> C
        // |     +---> D
        // |
        // +---> E
        write(
            "keepgoing/BUILD",
            (genrule("A", "'B','E'", mask and A)
                    + genrule("B", "'C','D'", mask and B)
                    + genrule("C", "", mask and C)
                    + genrule("D", "", mask and D)
                    + genrule("E", "", mask and E))
        )

        write("keepgoing/in", "(input)")
    }

    // "mask" is a bitmask of rules that succeed.
    @Throws(java.lang.Exception::class)
    private fun assertBuilt(mask: Int) {
        for (ii in labels.indices) {
            assertOneBuilt(labels[ii], (mask and (1 shl ii)) != 0)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun assertOneBuilt(label: String?, shouldBeBuilt: Boolean) {
        val files: Iterable<Artifact> = getArtifacts(label)
        for (file in files) {
            val isActuallyBuilt: Boolean = file.getPath().exists()
            if (file.getPath().exists() !== shouldBeBuilt) {
                org.junit.Assert.fail(
                    (file.prettyPrint()
                            + ": shouldBeBuilt="
                            + shouldBeBuilt
                            + ", isActuallyBuilt="
                            + isActuallyBuilt)
                )
            }
        }
    }

    private fun assertNoMoreEvents(events: MutableIterator<com.google.devtools.build.lib.events.Event?>) {
        var ok = true
        while (events.hasNext()) {
            java.lang.System.err.println(events.next())
            ok = false
        }
        Truth.assertThat(ok).isTrue()
    }

    // Build //keepgoing:A, expecting failure.  (The BuildResult instance is
    // subsequently available via getRequest() for later assertions.)
    @Throws(java.lang.Exception::class)
    private fun buildA() {
        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//keepgoing:A") })
        assertThat(e).hasMessageThat().isNull()
    }

    /********************************************************************
     * *
     * Actual tests...                          *
     * *
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingAfterCFails() {
        // C fails due to error (logged).
        // B fails due to failed prereqs (logged).
        // A fails due to failed prereqs (logged).
        // D and E are built.
        // Then a BuildFailedException is thrown.
        writeFiles(A or B or D or E)
        buildA()

        val errors: MutableIterator<com.google.devtools.build.lib.events.Event?> = events.errors().iterator()
        Truth.assertThat(errors.next().getMessage())
            .containsMatch("Executing genrule //keepgoing:C failed: .*Exit 42.*")
        assertNoMoreEvents(errors)

        assertBuilt(D or E)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingAfterDFails() {
        // D fails due to error (logged).
        // B fails due to failed prereqs (logged).
        // A fails due to failed prereqs (logged).
        // C and E are built.
        // Then a BuildFailedException is thrown.
        writeFiles(A or B or C or E)
        buildA()

        val errors: MutableIterator<com.google.devtools.build.lib.events.Event?> = events.errors().iterator()
        Truth.assertThat(errors.next().getMessage())
            .containsMatch("Executing genrule //keepgoing:D failed: .*Exit 42.*")
        assertNoMoreEvents(errors)

        assertBuilt(C or E)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingAfterCAndDFail() {
        // C and D fail due to error (logged).
        // B fails due to failed prereqs (logged).
        // A fails due to failed prereqs (logged).
        // E is built.
        // Then a BuildFailedException is thrown.
        writeFiles(A or B or E)
        buildA()

        // C, D events are unordered:
        val errors: MutableIterator<com.google.devtools.build.lib.events.Event?> = events.errors().iterator()
        Truth.assertThat(errors.next().getMessage())
            .containsMatch("Executing genrule //keepgoing:(C|D) failed: .*Exit 42.*")
        Truth.assertThat(errors.next().getMessage())
            .containsMatch("Executing genrule //keepgoing:(C|D) failed: .*Exit 42.*")

        assertNoMoreEvents(errors)

        assertBuilt(E)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingAfterEFails() {
        // E fails due to error (logged).
        // A fails due to failed prereqs (logged).
        // B,C,D  are built.
        // Then a BuildFailedException is thrown.
        writeFiles(A or B or C or D)
        buildA()

        val errors: MutableIterator<com.google.devtools.build.lib.events.Event?> = events.errors().iterator()
        Truth.assertThat(errors.next().getMessage())
            .containsMatch("Executing genrule //keepgoing:E failed: .*Exit 42.*")
        assertNoMoreEvents(errors)

        assertBuilt(B or C or D)
    }

    // Regression test for b/8826301, incremental builder does not correctly set root actions.
    // Check that keep going works on second build. Note that this test failed non-deterministically
    // in b/8826301, because it depended on HashSet iteration order.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingOnSecondBuild() {
        val buildFile: java.lang.StringBuilder = java.lang.StringBuilder()
        buildFile
            .append("genrule(name='topgen', tools=[':badgen'], outs=['top.out'], ")
            .append("cmd='touch $@')\n")
            .append("genrule(name='badgen', executable=1, srcs=['badsrc.sh'], ")
            .append("outs=['bad.out'], cmd='bash $< >  $@', tools = [")
        // Make graph large so incremental dependency checker does graph culling, if enabled.
        for (i in 0..59) {
            buildFile.append("':gen" + i + "', ")
        }
        buildFile.append("])\n")
        for (i in 0..59) {
            buildFile
                .append("genrule(name='gen")
                .append(i)
                .append("', " + "outs=['gen")
                .append(i)
                .append(".out'], executable=1, cmd = 'echo \"#!/bin/true\" > $@')\n")
        }
        write("keepgoing/BUILD", buildFile.toString())
        write("keepgoing/badsrc.sh", "exit 0")
        buildTarget("//keepgoing:topgen")
        write("keepgoing/badsrc.sh", "exit 42")
        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildTarget("//keepgoing:topgen") })
        assertThat(e).hasMessageThat().isNull()
        val errors: MutableIterator<com.google.devtools.build.lib.events.Event?> = events.errors().iterator()
        Truth.assertThat(errors.next().getMessage())
            .containsMatch("Executing genrule //keepgoing:badgen.* failed: .*Exit 42.*")
        assertNoMoreEvents(errors)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConfigurationErrorsAreToleratedWithKeepGoing() {
        runtimeWrapper.addOptions("--experimental_builtins_injection_override=+cc_library")
        write(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', srcs=['missing.foo'])"
        )
        write(
            "b/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='b')"
        )

        /**
         * Regression coverage for bug 1191396: "blaze build -k exits zero if execution succeeds, even
         * if there were analysis errors".
         */
        assertBuildFailedExceptionFromBuilding(
            "command succeeded, but not all targets were analyzed", "//a", "//b"
        )
        events.assertContainsError(
            "in srcs attribute of cc_library rule @@//a:a: source file '@@//a:missing.foo' is misplaced"
                    + " here"
        )
        events.assertContainsInfo("Build succeeded for only 1 of 2 top-level targets")

        assertSameConfiguredTarget("//b:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingAfterLoadingPhaseErrors() {
        write(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a')"
        )
        write(
            "b/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='b', deps = ['//missing:lib'])"
        )

        assertBuildFailedExceptionFromBuilding(
            "command succeeded, but not all targets were analyzed", "//a", "//b"
        ) //
        events.assertContainsError("no such package 'missing': BUILD file not found in any of the")

        assertSameConfiguredTarget("//a:a")
        events.assertContainsInfo(" succeeded for only 1 of ")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingAfterTargetParsingErrors() {
        write(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', xyz)"
        )
        write(
            "b/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='b', xyz)"
        )
        write(
            "b/b1/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='b1')"
        )
        write(
            "b/b2/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='b2', xyz)"
        )

        assertBuildFailedExceptionFromBuilding(
            "command succeeded, but there were errors parsing the target pattern", "b/...", "//a"
        )
        events.assertContainsWarning("Target pattern parsing failed.")

        assertSameConfiguredTarget("//b/b1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingAfterSchedulingDependencyFailure() {
        write("foo/foo.cc", "int main() { return 0; }")
        write(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = "foo",
            srcs = [
                "foo.cc",
                "gen.h",
            ],
            malloc = "system_malloc",
        )

        cc_library(
            name = "system_malloc",
            linkstatic = 1,
        )

        genrule(
            name = "gen",
            srcs = [],
            outs = ["gen.h"],
            cmd = "exit 1",
        )
        
        """.trimIndent()
        )

        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//foo:foo") })
    }

    @Throws(java.lang.Exception::class)
    private fun assertSameConfiguredTarget(label: String?) {
        assertThat(com.google.common.collect.Iterables.getOnlyElement<T?>(getResult().getSuccessfulTargets()))
            .isSameInstanceAs(getConfiguredTarget(label))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKeepGoingAfterAnalysisFailure() {
        write(
            "analysiserror/failer.bzl",
            """
        def _failer_impl(ctx):
            fail("BOOM!")

        failer = rule(implementation = _failer_impl)
        
        """.trimIndent()
        )
        write(
            "analysiserror/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(":failer.bzl", "failer")

        genrule(
            name = "gen",
            srcs = [],
            outs = ["gen.h"],
            cmd = "exit 1",
        )

        # The next line has an analysis failure: the xmb_lint rule is devoid of xmb files.
        failer(name = "foo")

        cc_library(name = "bar")
        
        """.trimIndent()
        )

        assertBuildFailedExceptionFromBuilding(
            "command succeeded, but not all targets were analyzed",
            "//analysiserror:foo",
            "//analysiserror:bar"
        )
        events.assertContainsError("Error in fail: BOOM!")

        assertSameConfiguredTarget("//analysiserror:bar")
    }

    private fun assertBuildFailedExceptionFromBuilding(msg: String?, vararg targets: String?) {
        val e: BuildFailedException? = org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget(*targets) })
        assertThat(e).hasMessageThat().isEqualTo(msg)
        assertThat(getResult().getSuccess()).isFalse()
    }

    companion object {
        private fun genrule(name: String, deps: String?, succeeds: Int): String {
            val out = name + ".out"
            val cmd = if (succeeds != 0) "cp $(location in) $(location " + out + ")" else "exit 42"
            return ("genrule(name='"
                    + name
                    + "', "
                    + "           srcs=['in',"
                    + deps
                    + "], "
                    + "           outs=['"
                    + out
                    + "'], "
                    + "           cmd='"
                    + cmd
                    + "')\n")
        }

        private const val A = 0x01
        private const val B = 0x02
        private const val C = 0x04
        private const val D = 0x08
        private const val E = 0x10

        private val labels = arrayOf<String?>(
            "//keepgoing:A", "//keepgoing:B", "//keepgoing:C", "//keepgoing:D", "//keepgoing:E"
        )
    }
}
