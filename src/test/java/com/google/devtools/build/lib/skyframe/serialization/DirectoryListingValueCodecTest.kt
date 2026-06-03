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

import com.google.devtools.build.lib.skyframe.DirectoryListingStateValue

/** Tests for [DirectoryListingValue].  */
@RunWith(JUnit4::class)
class DirectoryListingValueCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        val serializationTester: SerializationTester =
            SerializationTester(
                RegularDirectoryListingValue(
                    DirectoryListingStateValue.create(com.google.common.collect.ImmutableList.of<Dirent?>())
                ),
                RegularDirectoryListingValue(
                    DirectoryListingStateValue.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            Dirent("a", Dirent.Type.DIRECTORY),
                            Dirent("b", Dirent.Type.SYMLINK)
                        )
                    )
                ),
                DifferentRealPathDirectoryListingValue(
                    rootedPath("/foo", "bar"),
                    DirectoryListingStateValue.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            Dirent("c", Dirent.Type.UNKNOWN), Dirent("d", Dirent.Type.FILE)
                        )
                    )
                )
            )
        FsUtils.addDependencies(serializationTester)
        serializationTester.runTests()
    }

    companion object {
        private fun rootedPath(root: String?, relativePath: String?): RootedPath {
            return RootedPath.toRootedPath(
                Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath(root)), PathFragment.create(relativePath)
            )
        }
    }
}
