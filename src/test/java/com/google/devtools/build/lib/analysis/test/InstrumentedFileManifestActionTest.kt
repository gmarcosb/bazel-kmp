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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.actions.Action

/**
 * Tests for the instrumented file manifest creation
 */
@RunWith(JUnit4::class)
class InstrumentedFileManifestActionTest : AnalysisTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun initializeConfiguration() {
        useConfiguration("--collect_code_coverage")
    }

    /** regression test for b/9607864.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInstrumentedFileManifestConflicts() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "foo.so",
            srcs = ["Bar.java"],
        )

        java_library(
            name = "foo",
            srcs = ["Foo.java"],
        )
        
        """.trimIndent()
        )

        update("//foo:foo", "//foo:foo.so")
    }

    private fun createArtifact(rootRelativePath: String?): Artifact {
        val execRoot: Path = outputBase.getRelative("exec")
        val rootSegment = "out"
        val root: Path = execRoot.getRelative(rootSegment)
        return ActionsTestUtil.createArtifact(
            ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, rootSegment),
            root.getRelative(rootRelativePath)
        )
    }

    private enum class KeyAttributes {
        FILE_A,
        FILE_B
    }

    /** Regression test for b/28261106.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCacheKey() {
        val a: Artifact = createArtifact("foo/a")
        val b: Artifact = createArtifact("foo/b")
        ActionTester.Companion.runTest<KeyAttributes?>(
            com.google.devtools.build.lib.analysis.test.InstrumentedFileManifestActionTest.KeyAttributes::class.java,
            object : ActionCombinationFactory<KeyAttributes?> {
                override fun generate(attributesToFlip: com.google.common.collect.ImmutableSet<KeyAttributes?>): Action? {
                    val files: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
                    if (attributesToFlip.contains(com.google.devtools.build.lib.analysis.test.InstrumentedFileManifestActionTest.KeyAttributes.FILE_A)) {
                        files.add(a)
                    }
                    if (attributesToFlip.contains(com.google.devtools.build.lib.analysis.test.InstrumentedFileManifestActionTest.KeyAttributes.FILE_B)) {
                        files.add(b)
                    }
                    val output: Artifact = createArtifact("foo/manifest")
                    return InstrumentedFileManifestAction(
                        ActionOwner.SYSTEM_ACTION_OWNER, files.build(), output
                    )
                }
            },
            actionKeyContext
        )
    }
}
