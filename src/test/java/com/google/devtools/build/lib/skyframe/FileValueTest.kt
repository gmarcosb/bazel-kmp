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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileStateValue

/** Tests for [FileValue].  */
@RunWith(JUnit4::class)
class FileValueTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        // This test case assumes we have adequate coverage for FileStateValue serialization.
        val serializationTester: SerializationTester =
            SerializationTester(
                DifferentRealPathFileValueWithUnboundedAncestorExpansion(
                    FsUtils.TEST_ROOTED_PATH,
                    FileStateValue.DIRECTORY_FILE_STATE_NODE,
                    com.google.common.collect.ImmutableList.of<E?>(FsUtils.TEST_ROOTED_PATH),
                    com.google.common.collect.ImmutableList.of<E?>(),
                    com.google.common.collect.ImmutableList.of<E?>(FsUtils.TEST_ROOTED_PATH)
                ),
                SymlinkFileValueWithUnboundedAncestorExpansion(
                    FsUtils.TEST_ROOTED_PATH,
                    FileStateValue.DIRECTORY_FILE_STATE_NODE,
                    com.google.common.collect.ImmutableList.of<E?>(FsUtils.TEST_ROOTED_PATH),
                    PathFragment.create("doesntmatter"),
                    com.google.common.collect.ImmutableList.of<E?>(),
                    com.google.common.collect.ImmutableList.of<E?>(FsUtils.TEST_ROOTED_PATH)
                ),
                DifferentRealPathFileValueWithStoredChain(
                    FsUtils.TEST_ROOTED_PATH,
                    FileStateValue.DIRECTORY_FILE_STATE_NODE,
                    com.google.common.collect.ImmutableList.of<E?>(FsUtils.TEST_ROOTED_PATH)
                ),
                DifferentRealPathFileValueWithoutStoredChain(
                    FsUtils.TEST_ROOTED_PATH, FileStateValue.DIRECTORY_FILE_STATE_NODE
                ),
                SymlinkFileValueWithStoredChain(
                    FsUtils.TEST_ROOTED_PATH,
                    RegularFileStateValueWithDigest( /* size= */
                        100,  /* digest= */byteArrayOf(1, 2, 3, 4, 5)
                    ),
                    com.google.common.collect.ImmutableList.of<E?>(FsUtils.TEST_ROOTED_PATH),
                    PathFragment.create("somewhere/else")
                ),
                SymlinkFileValueWithoutStoredChain(
                    FsUtils.TEST_ROOTED_PATH,
                    RegularFileStateValueWithDigest( /* size= */
                        100,  /* digest= */byteArrayOf(1, 2, 3, 4, 5)
                    ),
                    PathFragment.create("somewhere/else")
                )
            )
        FsUtils.addDependencies(serializationTester)
        serializationTester.runTests()
    }
}
