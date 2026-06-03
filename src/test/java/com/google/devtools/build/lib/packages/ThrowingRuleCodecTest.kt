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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry

/** Tests that [RuleCodec] throws.  */
@RunWith(JUnit4::class)
class ThrowingRuleCodecTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        scratch.file(
            "cc/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='lib', srcs = ['a.cc'])"
        )
        val rule: Rule? = getTarget("//cc:lib") as Rule?

        val objectCodecs: ObjectCodecs =
            ObjectCodecs(
                AutoRegistry.get().getBuilder().setAllowDefaultCodec(true).build(),
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?>()
            )
        try {
            objectCodecs.serialize(rule)
            throw java.lang.AssertionError("Should have thrown")
        } catch (e: SerializationException) {
            assertThat(e).hasMessageThat()
                .isEqualTo(java.lang.String.format(RuleCodec.SERIALIZATION_ERROR_TEMPLATE, rule))
        }
    }
}
