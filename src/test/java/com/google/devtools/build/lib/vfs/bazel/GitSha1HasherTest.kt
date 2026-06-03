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
package com.google.devtools.build.lib.vfs.bazel

import com.google.devtools.build.lib.vfs.GitSha1HashFunction

/** Tests for [GitSha1MessageDigest].  */
@RunWith(JUnit4::class)
class GitSha1HasherTest {
    @org.junit.Test
    fun emptyHash() {
        val h: com.google.common.hash.Hasher = GitSha1HashFunction.INSTANCE.newHasher()

        val data = ByteArray(0)
        h.putBytes(data)

        Truth.assertThat(h.hash().toString()).isEqualTo("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391")
    }

    @org.junit.Test
    fun helloWorld() {
        val h: com.google.common.hash.Hasher = GitSha1HashFunction.INSTANCE.newHasher()

        val data: ByteArray = "hello world".toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        h.putBytes(data)

        Truth.assertThat(h.hash().toString()).isEqualTo("95d09f2b10159347eece71399a7e2e907ea3df4f")
    }
}
