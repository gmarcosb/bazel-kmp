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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** Tests for [NoSuchPackageException] serialization.  */
@RunWith(JUnit4::class)
class NoSuchPackageExceptionCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        SerializationTester(
            BuildFileNotFoundException(
                PackageIdentifier.create("repo", PathFragment.create("foo")), "msg"
            ),
            BuildFileNotFoundException(
                PackageIdentifier.create("repo", PathFragment.create("foo")),
                "msg",
                IOException("bar")
            ),
            BuildFileContainsErrorsException(
                PackageIdentifier.create("repo", PathFragment.create("foo")), "msg"
            ),
            BuildFileContainsErrorsException(
                PackageIdentifier.create("repo", PathFragment.create("foo")),
                "msg",
                IOException("bar")
            ),
            InvalidPackageNameException(
                PackageIdentifier.create("repo", PathFragment.create("foo")), "msg"
            ),
            NoSuchPackageException(
                PackageIdentifier.create("repo", PathFragment.create("foo")), "msg"
            ),
            NoSuchPackageException(
                PackageIdentifier.create("repo", PathFragment.create("foo")),
                "msg",
                IOException("bar")
            )
        )
            .setVerificationFunction(verifyDeserialization)
            .makeMemoizing()
            .runTests()
    }

    companion object {
        private val verifyDeserialization: SerializationTester.VerificationFunction<NoSuchPackageException?> =
            SerializationTester.VerificationFunction { deserialized, subject ->
                assertThat(deserialized).hasMessageThat().isEqualTo(subject.getMessage())
                assertThat(deserialized.getPackageId()).isEqualTo(subject.getPackageId())
                if (subject.getCause() == null) {
                    assertThat(deserialized).hasCauseThat().isNull()
                } else {
                    assertThat(deserialized)
                        .hasCauseThat()
                        .hasMessageThat()
                        .isEqualTo(subject.getCause().getMessage())
                }
            }
    }
}
