// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

@RunWith(TestParameterInjector::class)
class TransitiveInfoProviderMapImplCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serialization(@TestParameter useSharedValues: Boolean) {
        val tester: SerializationTester =
            SerializationTester(
                TransitiveInfoProviderMapImpl.create(com.google.common.collect.ImmutableMap.of<K?, V?>()),
                TransitiveInfoProviderMapImpl.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "key",
                        1,
                        "key2",
                        2
                    )
                )
            )

        if (useSharedValues) {
            tester
                .addCodec(TransitiveInfoProviderMapImpl.valueSharingCodec())
                .makeMemoizingAndAllowFutureBlocking(true)
        }

        tester.runTests()
    }
}
