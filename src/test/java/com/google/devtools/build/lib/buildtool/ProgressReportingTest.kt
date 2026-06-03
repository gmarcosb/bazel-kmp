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

import com.google.devtools.build.lib.unix.NativePosixFilesServiceImpl

/** Test for progress reporting.  */
@RunWith(JUnit4::class)
class ProgressReportingTest : BuildIntegrationTestCase() {
    private enum class PathOp {
        DELETE,
    }

    private fun interface Receiver {
        fun accept(path: PathFragment?, op: PathOp?)
    }

    private var receiver: Receiver? = com.google.devtools.build.lib.buildtool.ProgressReportingTest.Receiver?
    { x: PathFragment?, y: PathOp? -> }

    override fun additionalEventsToCollect(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.events.EventKind?> {
        return com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.events.EventKind?>(
            com.google.devtools.build.lib.events.EventKind.PROGRESS,
            com.google.devtools.build.lib.events.EventKind.START
        )
    }

    override fun createFileSystem(): FileSystem? {
        return object : UnixFileSystem(
            DigestHashFunction.SHA256,  /* hashAttributeName= */"", NativePosixFilesServiceImpl()
        ) {
            fun recordAccess(op: PathOp?, path: PathFragment?) {
                if (receiver != null) {
                    receiver!!.accept(path, op)
                }
            }

            @Throws(IOException::class)
            public override fun delete(path: PathFragment?): Boolean {
                recordAccess(PathOp.DELETE, path)
                return super.delete(path)
            }
        }
    }

    /**
     * Tests that [for tool] tags are added to the progress messages of actions in the exec
     * configuration, but not in the target configuration.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAdditionalInfo() {
        AnalysisMock.get().pySupport().setup(mockToolsConfig)
        write(
            "x/BUILD",
            "genrule(name = 'tool',",
            "          outs = ['sometool'],",
            "          cmd = 'touch $@')",
            "genrule(name = 'x',",
            ("        outs = ['out'],"
                    + "        cmd = 'echo test > $@',"
                    + "        tools = [':tool'])")
        )

        buildTarget("//x")

        assertContainsEvent("Executing genrule //x:tool [for tool]")
        assertContainsEvent("Executing genrule //x:x")
        assertDoesNotContainEvent("Executing genrule //x:x [for tool]")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPreparingMessage() {
        write(
            "x/BUILD",
            """
        genrule(
            name = "x",
            outs = ["slowdelete"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        buildTarget("//x")
        val output: Path =
            com.google.common.collect.Iterables.getOnlyElement<Artifact?>(getArtifacts("//x:x")).getPath()
        assertThat(output.delete()).isTrue()
        receiver =
            com.google.devtools.build.lib.buildtool.ProgressReportingTest.Receiver? { path: PathFragment?, op: PathOp? ->
            if (output.asFragment().equals(path) && op == PathOp.DELETE) {
                try {
                    // When the action tries to delete its outputs (during the "preparing" stage of action
                    // execution), we block on the deletion for enough time that the status reporter
                    // prints out a "Preparing:" progress message.
                    java.lang.Thread.sleep(4000)
                } catch (e: java.lang.InterruptedException) {
                    throw java.lang.IllegalStateException(e)
                }
            }
        }
        addOptions("--progress_report_interval=1")

        buildTarget("//x")
        assertContainsEvent("Preparing:")
        assertContainsEvent("Executing genrule //x:x")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWaitForResources() {
        write(
            "x/BUILD",
            """
        genrule(
            name = "x",
            outs = ["x.out"],
            cmd = "sleep 3; touch ${'$'}@",
            local = 1,
        )

        genrule(
            name = "y",
            outs = ["y.out"],
            cmd = "sleep 3; touch ${'$'}@",
            local = 1,
        )
        
        """.trimIndent()
        )
        // GenRuleAction currently specifies 300,1.0,0.0. If that changes, this may have to be changed
        // in order to keep exactly one genrule running at a time.
        addOptions(
            "--progress_report_interval=1",
            "--local_resources=cpu=1",
            "--local_resources=memory=1000",
            "--show_progress_rate_limit=-1"
        )
        buildTarget("//x:x", "//x:y")

        assertContainsEvent("Scheduling:")
        assertContainsEvent("Executing genrule //x:x")
        assertContainsEvent("Executing genrule //x:y")
    }
}
