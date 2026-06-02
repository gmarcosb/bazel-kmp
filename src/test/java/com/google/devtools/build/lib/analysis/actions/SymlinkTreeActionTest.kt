// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.ActionEnvironment

/** Tests [SymlinkTreeAction].  */
@RunWith(JUnit4::class)
class SymlinkTreeActionTest : BuildViewTestCase() {
    private enum class FilesetActionAttributes {
        FIXED_ENVIRONMENT,
        VARIABLE_ENVIRONMENT
    }

    private enum class RunfilesActionAttributes {
        RUNFILES,
        FIXED_ENVIRONMENT,
        VARIABLE_ENVIRONMENT
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComputeKey() {
        val filesetInputManifest: Artifact? =
            getTestAnalysisEnvironment()
                .getFilesetArtifact(
                    PathFragment.create("dir/manifest.in"),
                    targetConfig.getBinDirectory(RepositoryName.MAIN)
                )
        val runfilesInputManifest: Artifact? = getBinArtifactWithNoOwner("dir/manifest.in")
        val outputManifest: Artifact? = getBinArtifactWithNoOwner("dir/MANIFEST")
        val runfile: Artifact? = getBinArtifactWithNoOwner("dir/runfile")
        val runfile2: Artifact? = getBinArtifactWithNoOwner("dir/runfile2")

        var tester: ActionTester = ActionTester(actionKeyContext)

        for (runfileSymlinksMode in RunfileSymlinksMode.values()) {
            tester =
                tester.combinations<RunfilesActionAttributes?>(
                    RunfilesActionAttributes::class.java,
                    ActionCombinationFactory { attributesToFlip: com.google.common.collect.ImmutableSet<RunfilesActionAttributes?>? ->
                        SymlinkTreeAction(
                            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                            runfilesInputManifest,  /* runfiles= */
                            if (attributesToFlip.contains(RunfilesActionAttributes.RUNFILES))
                                Builder("TESTING").addArtifact(runfile).build()
                            else
                                Builder("TESTING").addArtifact(runfile2).build(),
                            outputManifest,  /* repoMappingManifest= */
                            null,
                            createActionEnvironment(
                                attributesToFlip.contains(RunfilesActionAttributes.FIXED_ENVIRONMENT),
                                attributesToFlip.contains(RunfilesActionAttributes.VARIABLE_ENVIRONMENT)
                            ),
                            runfileSymlinksMode,
                            "workspace"
                        )
                    })

            tester =
                tester.combinations<FilesetActionAttributes?>(
                    FilesetActionAttributes::class.java,
                    ActionCombinationFactory { attributesToFlip: com.google.common.collect.ImmutableSet<FilesetActionAttributes?>? ->
                        SymlinkTreeAction(
                            ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                            filesetInputManifest,  /* runfiles= */
                            null,
                            outputManifest,  /* repoMappingManifest= */
                            null,
                            createActionEnvironment(
                                attributesToFlip.contains(FilesetActionAttributes.FIXED_ENVIRONMENT),
                                attributesToFlip.contains(FilesetActionAttributes.VARIABLE_ENVIRONMENT)
                            ),
                            runfileSymlinksMode,
                            "workspace"
                        )
                    })
        }

        tester.runTest()
    }

    @org.junit.Test
    fun testNullRunfilesThrows() {
        val inputManifest: Artifact? = getBinArtifactWithNoOwner("dir/manifest.in")
        val outputManifest: Artifact? = getBinArtifactWithNoOwner("dir/MANIFEST")
        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable {
                SymlinkTreeAction(
                    ActionsTestUtil.Companion.NULL_ACTION_OWNER,
                    inputManifest,  /* runfiles= */
                    null,
                    outputManifest,  /* repoMappingManifest= */
                    null,
                    createActionEnvironment(false, false),
                    RunfileSymlinksMode.SKIP,
                    "workspace"
                )
            })
    }

    companion object {
        private fun createActionEnvironment(fixed: Boolean, variable: Boolean): ActionEnvironment {
            return ActionEnvironment.create(
                if (fixed) com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "a",
                    "b"
                ) else com.google.common.collect.ImmutableMap.of<K?, V?>(),
                if (variable) com.google.common.collect.ImmutableSet.of<E?>("c") else com.google.common.collect.ImmutableSet.of<E?>()
            )
        }
    }
}
