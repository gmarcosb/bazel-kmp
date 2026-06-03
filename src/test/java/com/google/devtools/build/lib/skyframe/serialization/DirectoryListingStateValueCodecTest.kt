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

/** Tests for codec for [DirectoryListingStateValue].  */
@RunWith(JUnit4::class)
class DirectoryListingStateValueCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(
            DirectoryListingStateValue.create(com.google.common.collect.ImmutableList.of<Dirent?>()),
            DirectoryListingStateValue.create(
                com.google.common.collect.ImmutableList.of<E?>(
                    Dirent("foo", Dirent.Type.DIRECTORY),
                    Dirent("bar", Dirent.Type.FILE),
                    Dirent("baz", Dirent.Type.SYMLINK),
                    Dirent("bazinga", Dirent.Type.UNKNOWN)
                )
            )
        )
            .runTests()
    }
}
