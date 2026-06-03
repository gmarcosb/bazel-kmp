// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

/** Tests for [FluentIterableCodec].  */
@RunWith(JUnit4::class)
class FluentIterableCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(
            com.google.common.collect.FluentIterable.of<E?>("x"),
            com.google.common.collect.FluentIterable.< E > of < E ? > ("abc", "def"
        ),
        com.google.common.collect.Iterables.< T > concat < T ? > (com.google.common.collect.ImmutableList.of<String?>(
            "first",
            "second"
        ), com.google.common.collect.ImmutableList.of<kotlin.String?>("third")))
        .setVerificationFunction({ first: com.google.common.collect.FluentIterable<kotlin.Any?>, second: com.google.common.collect.FluentIterable<kotlin.Any?> ->
            verifyEquals(
                first,
                second
            )
        })
            .runTests()
    }

    companion object {
        private fun verifyEquals(
            first: com.google.common.collect.FluentIterable<Any?>,
            second: com.google.common.collect.FluentIterable<Any?>
        ) {
            Truth.assertThat(first.toList()).isEqualTo(second.toList())
        }
    }
}
