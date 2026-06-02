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
package com.google.devtools.build.lib.collect.nestedset

import com.google.common.truth.Truth
import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder
import com.google.devtools.build.lib.collect.nestedset.NestedSetStore
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import java.io.IOException

/** Utilities for testing NestedSet serialization.  */
object NestedSetCodecTestUtils {
    private val SHARED_NESTED_SET: NestedSet<String?>? =
        NestedSetBuilder.Companion.stableOrder<String?>().add("e").build()

    /** Perform serialization/deserialization checks for several simple NestedSet examples.  */
    @Throws(java.lang.Exception::class)
    fun checkCodec(
        objectCodecs: ObjectCodecs?, allowFutureBlocking: Boolean, assertSymmetricEquality: Boolean
    ) {
        com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester(
            NestedSetBuilder.Companion.emptySet<Any?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER),
            NestedSetBuilder.Companion.emptySet<Any?>(com.google.devtools.build.lib.collect.nestedset.Order.NAIVE_LINK_ORDER),
            NestedSetBuilder.Companion.create<String?>(
                com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER,
                "a"
            ),
            NestedSetBuilder.Companion.create<String?>(
                com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER,
                "a",
                "b",
                "c"
            ),
            NestedSetBuilder.Companion.stableOrder<String?>()
                .add("a")
                .add("b")
                .addTransitive(
                    NestedSetBuilder.Companion.stableOrder<String?>()
                        .add("c")
                        .addTransitive(SHARED_NESTED_SET)
                        .build()
                )
                .addTransitive(
                    NestedSetBuilder.Companion.stableOrder<String?>()
                        .add("d")
                        .addTransitive(SHARED_NESTED_SET)
                        .build()
                )
                .addTransitive(NestedSetBuilder.Companion.emptySet<String?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER))
                .build(),
            NestedSetBuilder.Companion.create<HasNestedSet?>(
                com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER,
                HasNestedSet(
                    NestedSetBuilder.Companion.create<String?>(
                        com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER,
                        "a"
                    )
                )
            )
        )
            .setObjectCodecs(objectCodecs)
            .makeMemoizingAndAllowFutureBlocking(allowFutureBlocking)
            .setVerificationFunction<NestedSet<String?>?>(verificationFunction(assertSymmetricEquality))
            .runTests()
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun writeToStoreFuture(
        store: NestedSetStore, nestedSet: NestedSet<*>, serializationContext: SerializationContext?
    ): com.google.common.util.concurrent.ListenableFuture<*>? {
        return store
            .computeFingerprintAndStore(nestedSet.getChildren() as Array<Any?>?, serializationContext)
            .writeStatus
    }

    private fun verificationFunction(
        assertSymmetricEquality: Boolean
    ): com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester.VerificationFunction<NestedSet<String?>?> {
        return com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester.VerificationFunction { subject: NestedSet<kotlin.String?>?, deserialized: NestedSet<kotlin.String?>? ->
            if (assertSymmetricEquality) {
                Truth.assertThat(deserialized).isEqualTo(subject)
                Truth.assertThat(subject).isEqualTo(deserialized)
            }
            Truth.assertThat<com.google.devtools.build.lib.collect.nestedset.Order?>(subject.getOrder())
                .isEqualTo(deserialized.getOrder())
            Truth.assertThat(subject.toSet()).isEqualTo(deserialized.toSet())
            verifyStructure(subject.getChildren(), deserialized.getChildren())
        }
    }

    private fun verifyStructure(lhs: Any?, rhs: Any?) {
        if (lhs is Array<Any>) {
            Truth.assertThat(rhs).isInstanceOf(Array<Any>::class.java)
            val rhsArray = rhs as Array<Any?>
            val n: Int = lhs.length
            Truth.assertThat<Any?>(rhsArray).hasLength(n)
            for (i in 0..<n) {
                verifyStructure(lhs[i], rhsArray[i])
            }
            if (lhs.length == 0) {
                // Verify empty-children is optimized - we're not creating multiple empty arrays.
                Truth.assertThat<Any?>(lhs).isSameInstanceAs(rhsArray)
            }
        } else {
            Truth.assertThat(lhs).isEqualTo(rhs)
        }
    }

    private class HasNestedSet(nestedSetField: NestedSet<String?>) {
        private val nestedSetField: NestedSet<String?>

        init {
            this.nestedSetField = nestedSetField
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o == null || getClass() != o.getClass()) {
                return false
            }
            val that = o as HasNestedSet
            return com.google.common.base.Objects.equal(nestedSetField.getChildren(), that.nestedSetField.getChildren())
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(nestedSetField)
        }
    }
}
