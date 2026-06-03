// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.io.FileSymlinkCycleException

/** Tests for [FileSymlinkException] serialization.  */
@RunWith(JUnit4::class)
class FileSymlinkExceptionCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        val root: Root? = Root.absoluteRoot(FsUtils.TEST_FILESYSTEM)
        val serializationTester: SerializationTester =
            SerializationTester(
                FileSymlinkInfiniteExpansionException(
                    com.google.common.collect.ImmutableList.of<E?>(
                        RootedPath.toRootedPath(
                            root,
                            PathFragment.create("/dir")
                        )
                    ),
                    com.google.common.collect.ImmutableList.of<E?>(
                        RootedPath.toRootedPath(root, PathFragment.create("/dir/chain"))
                    )
                ),
                FileSymlinkCycleException(
                    com.google.common.collect.ImmutableList.of<E?>(
                        RootedPath.toRootedPath(
                            root,
                            PathFragment.create("/dir")
                        )
                    ),
                    com.google.common.collect.ImmutableList.of<E?>(
                        RootedPath.toRootedPath(root, PathFragment.create("/dir/cycle"))
                    )
                )
            )
                .makeMemoizing()
                .setVerificationFunction(verifyDeserialization)
        FsUtils.addDependencies(serializationTester)
        serializationTester.runTests()
    }

    companion object {
        private val verifyDeserialization: SerializationTester.VerificationFunction<FileSymlinkException?> =
            SerializationTester.VerificationFunction { deserialized, subject ->
                assertThat(deserialized).hasMessageThat().isEqualTo(subject.getMessage())
                if (deserialized is FileSymlinkInfiniteExpansionException) {
                    val fsSubject: FileSymlinkInfiniteExpansionException =
                        subject as FileSymlinkInfiniteExpansionException
                    assertThat(deserialized.getPathToChain()).isEqualTo(fsSubject.getPathToChain())
                    assertThat(deserialized.getChain()).isEqualTo(fsSubject.getChain())
                } else if (deserialized is FileSymlinkCycleException) {
                    val fsSubject: FileSymlinkCycleException = subject as FileSymlinkCycleException
                    assertThat(deserialized.getPathToCycle()).isEqualTo(fsSubject.getPathToCycle())
                    assertThat(deserialized.getCycle()).isEqualTo(fsSubject.getCycle())
                } else {
                    throw java.lang.AssertionError("unexpected subclass of FileSymlinkException")
                }
            }
    }
}
