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

import com.google.devtools.build.lib.vfs.bazel.Blake3Hasher
import com.google.devtools.build.lib.vfs.bazel.Blake3Hasher.hash
import com.google.devtools.build.lib.vfs.bazel.Blake3Hasher.putBytes
import com.google.devtools.build.lib.vfs.bazel.Blake3MessageDigest
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [Blake3MessageDigest].  */
@RunWith(JUnit4::class)
class Blake3HasherTest {
    @org.junit.Test
    fun emptyHash() {
        val h: Blake3Hasher = Blake3Hasher(Blake3MessageDigest())

        val data = ByteArray(0)
        h.putBytes(data)

        org.junit.Assert.assertEquals(
            "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262", h.hash().toString()
        )
    }

    @org.junit.Test
    fun helloWorld() {
        val h: Blake3Hasher = Blake3Hasher(Blake3MessageDigest())

        val data: ByteArray = "hello world".toByteArray(java.nio.charset.StandardCharsets.US_ASCII)
        h.putBytes(data)

        org.junit.Assert.assertEquals(
            "d74981efa70a0c880b8d8c1985d075dbcbf679b99a5f9914e5aaf96b831a9e24", h.hash().toString()
        )
    }
}
