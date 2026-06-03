// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.ArtifactRoot

/** Tests for [RemoteOutputChecker]  */
@RunWith(JUnit4::class)
class RemoteOutputCheckerTest {
    private val remoteOutputChecker: RemoteOutputChecker =
        RemoteOutputChecker("build", RemoteOutputsMode.MINIMAL, com.google.common.collect.ImmutableList.of<E?>())
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val execRoot: ArtifactRoot? =
        ArtifactRoot.asDerivedRoot(fs.getPath("/execroot"), ArtifactRoot.RootType.OUTPUT, "out")

    @org.junit.Test
    fun testShouldDownloadOutput() {
        remoteOutputChecker.addOutputToDownload(
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(execRoot, "foo/bar")
        )
        remoteOutputChecker.addOutputToDownload(
            ActionsTestUtil.createArtifact(execRoot, "foo/bar-baz")
        )
        assertThat(
            remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar-quz"), null)
        )
            .isFalse()
        assertThat(remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar"), null))
            .isTrue()
        assertThat(
            remoteOutputChecker.shouldDownloadOutput(
                PathFragment.create("out/foo/bar/data.txt"), PathFragment.create("out/foo/bar")
            )
        )
            .isTrue()
        assertThat(
            remoteOutputChecker.shouldDownloadOutput(PathFragment.create("out/foo/bar-baz"), null)
        )
            .isTrue()
    }
}
