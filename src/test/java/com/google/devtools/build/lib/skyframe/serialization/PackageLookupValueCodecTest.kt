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

import com.google.devtools.build.lib.packages.BuildFileName

/** Unit tests for [PackageLookupValueCodec].  */
@RunWith(JUnit4::class)
class PackageLookupValueCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        val serializationTester: SerializationTester =
            SerializationTester(
                PackageLookupValue.success(
                    Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath("/success")), BuildFileName.BUILD
                ),
                PackageLookupValue.success(
                    Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath("/success")),
                    BuildFileName.WORKSPACE
                ),
                PackageLookupValue.invalidPackageName("junkjunkjunk"),
                PackageLookupValue.NO_BUILD_FILE_VALUE,
                PackageLookupValue.DELETED_PACKAGE_VALUE
            )
        FsUtils.addDependencies(serializationTester)
        serializationTester.runTests()
    }
}
