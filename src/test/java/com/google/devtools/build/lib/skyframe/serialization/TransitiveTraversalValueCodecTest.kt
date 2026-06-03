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

import com.google.devtools.build.lib.analysis.TransitiveInfoProvider

/** Basic tests for codec for [TransitiveTraversalValue].  */
@RunWith(JUnit4::class)
class TransitiveTraversalValueCodecTest {
    private class PseudoProvider : TransitiveInfoProvider

    private class PseudoProvider1 : TransitiveInfoProvider

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        val ruleClassProvider: RuleClassProvider = Mockito.mock<RuleClassProvider>(RuleClassProvider::class.java)
        Mockito.`when`<T?>(ruleClassProvider.getRuleClassMap())
            .thenReturn(com.google.common.collect.ImmutableMap.of<K?, V?>())
        SerializationTester(
            TransitiveTraversalValue.create(
                AdvertisedProviderSet.EMPTY, "foo_kind",  /*errorMessage=*/null
            ),
            TransitiveTraversalValue.create(
                AdvertisedProviderSet.EMPTY, "foo_kind",  /*errorMessage=*/null
            ),
            TransitiveTraversalValue.create(
                AdvertisedProviderSet.EMPTY, "foo_kind",  /*errorMessage=*/""
            ),
            TransitiveTraversalValue.create(
                AdvertisedProviderSet.create(
                    com.google.common.collect.ImmutableSet.of<java.lang.Class<*>?>(
                        PseudoProvider::class.java,
                        PseudoProvider1::class.java
                    ),
                    com.google.common.collect.ImmutableSet.of<StarlarkProviderIdentifier?>()
                ),
                "foo_kind",  /*errorMessage=*/
                "baz"
            )
        )
            .addDependency(RuleClassProvider::class.java, ruleClassProvider)
            .runTests()
    }
}
