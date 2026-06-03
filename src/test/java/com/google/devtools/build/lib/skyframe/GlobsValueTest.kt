// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

@RunWith(JUnit4::class)
class GlobsValueTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerialization() {
        val packageId: PackageIdentifier? = PackageIdentifier.create("foo", PathFragment.create("//bar"))
        val packageRoot: Root? = Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath("/packageRoot"))

        val globRequest1: GlobRequest = GlobRequest.create("*", Operation.FILES_AND_DIRS)
        val globRequest2: GlobRequest? = GlobRequest.create("foo/**", Operation.SUBPACKAGES)
        val globRequest3: GlobRequest? = GlobRequest.create("**/*", Operation.FILES)

        val serializationTester: SerializationTester =
            SerializationTester(
                GlobsValue.key(
                    packageId,
                    packageRoot,
                    com.google.common.collect.ImmutableSet.of<E?>(globRequest1, globRequest2)
                ),
                GlobsValue.key(
                    packageId,
                    packageRoot,
                    com.google.common.collect.ImmutableSet.of<E?>(globRequest2, globRequest3)
                )
            )
                .setVerificationFunction({ orig: GlobsValue.Key?, deserialized: GlobsValue.Key? ->
                    verifyEquivalent(
                        orig,
                        deserialized
                    )
                })
        FsUtils.addDependencies(serializationTester)
        serializationTester.runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrintingDeterministic() {
        val packageId: PackageIdentifier? = PackageIdentifier.create("foo", PathFragment.create("//bar"))
        val packageRoot: Root? = Root.fromPath(FsUtils.TEST_FILESYSTEM.getPath("/packageRoot"))

        val globRequest1: GlobRequest = GlobRequest.create("*", Operation.FILES_AND_DIRS)
        val globRequest2: GlobRequest? = GlobRequest.create("foo/**", Operation.SUBPACKAGES)
        val globRequest3: GlobRequest? = GlobRequest.create("**/*", Operation.FILES)

        val key1: GlobsValue.Key =
            GlobsValue.key(
                packageId,
                packageRoot,
                com.google.common.collect.ImmutableSet.of<E?>(globRequest1, globRequest2, globRequest3)
            )
        val key2: GlobsValue.Key =
            GlobsValue.key(
                packageId,
                packageRoot,
                com.google.common.collect.ImmutableSet.of<E?>(globRequest1, globRequest3, globRequest2)
            )
        val key3: GlobsValue.Key =
            GlobsValue.key(
                packageId,
                packageRoot,
                com.google.common.collect.ImmutableSet.of<E?>(globRequest2, globRequest1, globRequest3)
            )
        val key4: GlobsValue.Key =
            GlobsValue.key(
                packageId,
                packageRoot,
                com.google.common.collect.ImmutableSet.of<E?>(globRequest2, globRequest3, globRequest1)
            )
        val key5: GlobsValue.Key =
            GlobsValue.key(
                packageId,
                packageRoot,
                com.google.common.collect.ImmutableSet.of<E?>(globRequest3, globRequest1, globRequest2)
            )
        val key6: GlobsValue.Key =
            GlobsValue.key(
                packageId,
                packageRoot,
                com.google.common.collect.ImmutableSet.of<E?>(globRequest3, globRequest2, globRequest1)
            )
        EqualsTester()
            .addEqualityGroup(
                key1.toString(),
                key2.toString(),
                key3.toString(),
                key4.toString(),
                key5.toString(),
                key6.toString(),
                ("<GlobsKey packageRoot = /packageRoot, packageIdentifier = @@foo///bar,"
                        + " globRequests = [GlobRequest: * FILES_AND_DIRS,GlobRequest: **/* FILES,"
                        + "GlobRequest: foo/** SUBPACKAGES]>")
            )
            .testEquals()
    }

    companion object {
        private fun verifyEquivalent(orig: GlobsValue.Key?, deserialized: GlobsValue.Key?) {
            assertThat(deserialized).isSameInstanceAs(orig)
        }
    }
}
