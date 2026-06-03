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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.CollectPackagesUnderDirectoryValue.NoErrorCollectPackagesUnderDirectoryValue

/** Test for codec for [CollectPackagesUnderDirectoryValue].  */
@RunWith(JUnit4::class)
class CollectPackagesUnderDirectoryCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        val serializationTester: SerializationTester =
            SerializationTester(
                NoErrorCollectPackagesUnderDirectoryValue.EMPTY,
                CollectPackagesUnderDirectoryValue.ofNoError(
                    true, com.google.common.collect.ImmutableList.of<E?>(rootedPath("/a", "b"))
                ),
                CollectPackagesUnderDirectoryValue.ofNoError(
                    false, com.google.common.collect.ImmutableList.of<E?>(rootedPath("/c", "d"))
                ),
                CollectPackagesUnderDirectoryValue.ofError(
                    "my error message", com.google.common.collect.ImmutableList.of<E?>(rootedPath("/c", "d"))
                )
            )
        FsUtils.addDependencies(serializationTester)
        serializationTester.runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyDeserializesToSingletonValue() {
        assertThat(RoundTripping.roundTrip(NoErrorCollectPackagesUnderDirectoryValue.EMPTY))
            .isSameInstanceAs(NoErrorCollectPackagesUnderDirectoryValue.EMPTY)
    }

    companion object {
        private fun rootedPath(root: String?, relativePath: String?): RootedPath {
            return RootedPath.toRootedPath(
                Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath(root)), PathFragment.create(relativePath)
            )
        }
    }
}
