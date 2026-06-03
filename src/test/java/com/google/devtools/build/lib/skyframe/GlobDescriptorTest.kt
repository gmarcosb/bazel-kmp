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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.LabelSyntaxException

/** Tests for [GlobDescriptor].  */
@RunWith(JUnit4::class)
class GlobDescriptorTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerialization() {
        val serializationTester: SerializationTester =
            SerializationTester(
                GlobDescriptor.create(
                    PackageIdentifier.create("foo", PathFragment.create("//bar")),
                    Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath("/packageRoot")),
                    PathFragment.create("subdir"),
                    "pattern",
                    Globber.Operation.FILES_AND_DIRS
                ),
                GlobDescriptor.create(
                    PackageIdentifier.create("bar", PathFragment.create("//foo")),
                    Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath("/anotherPackageRoot")),
                    PathFragment.create("anotherSubdir"),
                    "pattern",
                    Globber.Operation.FILES
                )
            )
                .setVerificationFunction({ orig: GlobDescriptor?, deserialized: GlobDescriptor? ->
                    verifyEquivalent(
                        orig,
                        deserialized
                    )
                })
        FsUtils.addDependencies(serializationTester)
        serializationTester.runTests()
    }

    @org.junit.Test
    @Throws(LabelSyntaxException::class)
    fun testCreateReturnsInternedInstances() {
        val original: GlobDescriptor =
            GlobDescriptor.create(
                PackageIdentifier.create("foo", PathFragment.create("//bar")),
                Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath("/packageRoot")),
                PathFragment.create("subdir"),
                "pattern",
                Globber.Operation.FILES_AND_DIRS
            )

        val sameCopy: GlobDescriptor? =
            GlobDescriptor.create(
                original.getPackageId(),
                original.getPackageRoot(),
                original.getSubdir(),
                original.pattern,
                original.globberOperation()
            )
        assertThat(sameCopy).isSameInstanceAs(original)

        val diffCopy: GlobDescriptor? =
            GlobDescriptor.create(
                original.getPackageId(),
                original.getPackageRoot(),
                original.getSubdir(),
                original.pattern,
                Globber.Operation.FILES
            )
        assertThat(diffCopy).isNotEqualTo(original)
    }

    companion object {
        private fun verifyEquivalent(orig: GlobDescriptor?, deserialized: GlobDescriptor?) {
            assertThat(deserialized).isSameInstanceAs(orig)
        }
    }
}
