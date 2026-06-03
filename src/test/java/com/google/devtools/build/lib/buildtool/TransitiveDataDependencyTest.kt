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

/**
 * Tests that concern the transitive closure of data dependencies. Regression testing for bug
 * 1022571.
 */
@RunWith(JUnit4::class)
abstract class TransitiveDataDependencyTest : BuildIntegrationTestCase() {
    /**
     * Hook for subclasses to define which executor we use.  (The two concrete
     * subclasses, {Sequential,Parallel}TransitiveDataDependencyTest are at the bottom of this
     * source file.)
     */
    protected abstract fun numJobs(): Int

    @Before
    @Throws(java.lang.Exception::class)
    fun addJobNumberOption() {
        addOptions("--jobs", "" + numJobs())
    }

    @Throws(java.lang.Exception::class)
    private fun assertSameConfiguredTarget(label: String?) {
        assertThat(com.google.common.collect.Iterables.getOnlyElement<T?>(getResult().getSuccessfulTargets()))
            .isSameInstanceAs(getConfiguredTarget(label))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveDataDepIsBuilt() {
        write(
            "data/BUILD",
            """
        cc_library(
            name = "needsdata",
            data = [":data_bin"],
        )

        cc_binary(
            name = "data_bin",
            srcs = ["data_bin.c"],
        )
        
        """.trimIndent()
        )
        write("data/data_bin.c", "int main() { return 0; }")

        buildTarget("//data:needsdata")
        val dataLibTarget: ConfiguredTarget = getConfiguredTarget("//data:data_bin")
        assertThat(getFilesToBuild(dataLibTarget).toList()).isNotEmpty()
        for (dataOut in getFilesToBuild(dataLibTarget).toList()) {
            assertWithMessage("Missing output: %s", dataOut.getPath())
                .that(dataOut.getPath().exists())
                .isTrue()
        }
        assertSameConfiguredTarget("//data:needsdata")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingInputFile() {
        write(
            "data/BUILD",
            "cc_library(name = 'needsdata', data = [':data_file'])"
        )

        val recOutErr: RecordingOutErr = RecordingOutErr()
        val origOutErr: OutErr? = this.outErr
        this.outErr = recOutErr

        // Remove this flag after fixing:
        // "Remove source artifacts from top-level artifacts in SkyframeExecutor#buildArtifacts"
        // We are adding information about //data:needdata to error message only if
        // ActionExecutionFunction has requested that missing artifact. But there is small chance
        // that ArtifactFunction of that missing artifact has thrown exception before that request.
        // In case of keep_going we can be sure that ActionExecutionFunction has made request.
        addOptions("--keep_going")
        try {
            buildTarget("//data:needsdata")
            org.junit.Assert.fail()
        } catch (e: BuildFailedException) {
            assertThat(recOutErr.errAsLatin1())
                .containsMatch("//data:needsdata: missing input file '//data:data_file'")
        } finally {
            this.outErr = origOutErr
        }
        assertThat(getResult().getSuccess()).isFalse()
        assertThat(getResult().getSuccessfulTargets()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingExportsFiles() {
        write("data/BUILD", "exports_files(['nosuchfile'])")

        val recOutErr: RecordingOutErr = RecordingOutErr()
        val origOutErr: OutErr? = this.outErr
        this.outErr = recOutErr

        try {
            buildTarget("//data:nosuchfile")
            org.junit.Assert.fail()
        } catch (e: BuildFailedException) {
            assertThat(recOutErr.errAsLatin1()).containsMatch("missing input file '//data:nosuchfile'")
        } finally {
            this.outErr = origOutErr
        }
        assertThat(getResult().getSuccess()).isFalse()
        assertThat(getResult().getSuccessfulTargets()).isEmpty()
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingInputFilesKeepGoing() {
        write(
            "data/BUILD",
            """
        # Comment line
        cc_library(
            name = "needsdata1",
            data = [":data_file1"],
        )

        cc_library(
            name = "needsdata2",
            data = [":data_file2"],
        )
        
        """.trimIndent()
        )
        write("data/data_file2", "data_file2 exists")

        val recOutErr: RecordingOutErr = RecordingOutErr()
        val origOutErr: OutErr? = this.outErr
        this.outErr = recOutErr

        addOptions("--keep_going")
        try {
            buildTarget("//data:needsdata1", "//data:needsdata2")
            org.junit.Assert.fail()
        } catch (expected: BuildFailedException) {
            assertThat(recOutErr.errAsLatin1())
                .containsMatch(
                    "data/BUILD:2:1: //data:needsdata1: missing input file '//data:data_file1'"
                )
        } finally {
            this.outErr = origOutErr
        }

        assertThat(getResult().getSuccess()).isFalse()
        assertSameConfiguredTarget("//data:needsdata2")
    }

    // Concrete implementations of this abstract test:
    /** Tests with 1 job.  */
    @RunWith(JUnit4::class)
    class SequentialTransitiveDataDependencyTest : TransitiveDataDependencyTest() {
        override fun numJobs(): Int {
            return 0
        }
    }

    /** Tests with 100 jobs.  */
    @RunWith(JUnit4::class)
    class ParallelTransitiveDataDependencyTest : TransitiveDataDependencyTest() {
        override fun numJobs(): Int {
            return 100
        }
    }
}
