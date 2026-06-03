// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.serialization.testutils.FsUtils

/** Tests for [ObjectCodec] for [Path].  */
@RunWith(JUnit4::class)
class PathCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(
            FsUtils.TEST_FILESYSTEM.getPath("/"),
            FsUtils.TEST_FILESYSTEM.getPath("/some/path"),
            FsUtils.TEST_FILESYSTEM.getPath("/some/other/path/with/empty/last/fragment/")
        )
            .addDependency(FileSystem::class.java, FsUtils.TEST_FILESYSTEM)
            .runTests()
    }
}
