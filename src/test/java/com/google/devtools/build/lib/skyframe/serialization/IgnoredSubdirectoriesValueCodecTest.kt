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

import com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue

/** Tests for `IgnoredSubdirectoriesValueCodec`.  */
@RunWith(JUnit4::class)
class IgnoredSubdirectoriesValueCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(
            IgnoredSubdirectoriesValue.of(prefixes(), patterns()),
            IgnoredSubdirectoriesValue.of(prefixes("foo"), patterns()),
            IgnoredSubdirectoriesValue.of(prefixes("foo", "bar/moo"), patterns()),
            IgnoredSubdirectoriesValue.of(prefixes(), patterns("foo")),
            IgnoredSubdirectoriesValue.of(prefixes(), patterns("foo")),
            IgnoredSubdirectoriesValue.of(prefixes(), patterns("foo/**")),
            IgnoredSubdirectoriesValue.of(prefixes("foo"), patterns("foo/**"))
        )
            .runTests()
    }

    companion object {
        private fun prefixes(vararg prefixes: String?): com.google.common.collect.ImmutableSet<PathFragment?> {
            return java.util.Arrays.stream<String?>(prefixes).map<Any?>(PathFragment::create)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        }

        private fun patterns(vararg patterns: String?): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.copyOf<String?>(patterns)
        }
    }
}
