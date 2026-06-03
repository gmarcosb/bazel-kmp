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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.Action

/** Integration tests for Skymeld with a dummy output service.  */
@RunWith(JUnit4::class)
class SkymeldOutputServiceBuildIntegrationTest : BuildIntegrationTestCase() {
    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.getRuntimeBuilder()
            .addBlazeModule(
                object : BlazeModule() {
                    val outputService: OutputService?
                        get() =// An output service that fails when #startBuild or #finalizeBuild is called.
                            object : OutputService() {
                                public override fun getFileSystemName(outputBaseFileSystemName: String?): String {
                                    return "dummyTestFileSystem"
                                }

                                public override fun startBuild(
                                    buildId: UUID?,
                                    workspaceName: String?,
                                    eventHandler: com.google.devtools.build.lib.events.EventHandler?,
                                    finalizeActions: Boolean
                                ): ModifiedFileSet? {
                                    throw java.lang.IllegalStateException()
                                }

                                public override fun finalizeBuild(buildSuccessful: Boolean) {
                                    throw java.lang.IllegalStateException()
                                }

                                public override fun finalizeAction(
                                    action: Action?, outputMetadataStore: OutputMetadataStore?
                                ) {
                                }

                                val batchStatter: BatchStat?
                                    get() = null

                                public override fun canCreateSymlinkTree(): Boolean {
                                    return false
                                }

                                public override fun createSymlinkTree(
                                    symlinks: MutableMap<PathFragment?, PathFragment?>?,
                                    symlinkTreeRoot: PathFragment?
                                ) {
                                }

                                public override fun clean() {}
                            }
                })

    @Before
    fun setUp() {
        addOptions("--experimental_merged_skyframe_analysis_execution")
    }

    // Regression test for b/287277301.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noAnalyze_outputServiceStartBuildFinalizeBuildNotCalled() {
        write(
            "foo/BUILD",
            """
        genrule(
            name = "foo",
            srcs = ["foo.in"],
            outs = ["foo.out"],
            cmd = "cp ${'$'}< ${'$'}@",
        )
        
        """.trimIndent()
        )
        write("foo/foo.in")
        addOptions("--noanalyze")

        val result: BuildResult = buildTarget("//foo:foo")

        assertThat(result.getSuccess()).isTrue()
    }
}
