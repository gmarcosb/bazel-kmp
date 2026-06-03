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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructure

@RunWith(JUnit4::class)
class DeferredNestedSetCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun empty() {
        SerializationTester(
            Order.STABLE_ORDER.emptySet(),
            Order.COMPILE_ORDER.emptySet(),
            Order.LINK_ORDER.emptySet(),
            Order.NAIVE_LINK_ORDER.emptySet()
        )
            .addCodec(DeferredNestedSetCodec())
            .makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singleton() {
        SerializationTester(
            NestedSetBuilder.stableOrder().add("A").build(),
            NestedSetBuilder.compileOrder().add("B").build(),
            NestedSetBuilder.linkOrder().add("C").build(),
            NestedSetBuilder.naiveLinkOrder().add("D").build()
        )
            .addCodec(DeferredNestedSetCodec())
            .makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
            .setVerificationFunction({ original: NestedSet, deserialized: NestedSet? ->
                verifyUsingShallowEquals(
                    original,
                    deserialized
                )
            })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun array() {
        SerializationTester(
            NestedSetBuilder.stableOrder().addAll(com.google.common.collect.ImmutableList.of<E?>(1, 2, 3)).build(),
            NestedSetBuilder.compileOrder().addAll(com.google.common.collect.ImmutableList.of<E?>("A", "B", "C"))
                .build(),
            NestedSetBuilder.linkOrder().addAll(com.google.common.collect.ImmutableList.of<E?>(5.56, 3.14, 10, 20))
                .build(),
            NestedSetBuilder.naiveLinkOrder()
                .addAll(com.google.common.collect.ImmutableList.of<E?>("one", "two", "three", "four", "five"))
                .build()
        )
            .addCodec(DeferredNestedSetCodec())
            .makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
            .setVerificationFunction({ original: NestedSet, deserialized: NestedSet? ->
                verifyUsingShallowEquals(
                    original,
                    deserialized
                )
            })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diamond() {
        val root: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedSetBuilder.stableOrder().addAll(com.google.common.collect.ImmutableList.of<E?>(1, 2)).build()
        val left: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedSetBuilder.stableOrder().add("left").addTransitive(root).build()
        val right: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedSetBuilder.stableOrder().add("right").addTransitive(root).build()
        val top: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            NestedSetBuilder.stableOrder()
                .addAll(com.google.common.collect.ImmutableList.of<E?>("this", "is", "the", "top"))
                .addTransitive(left)
                .addTransitive(right)
                .build()

        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForTesting()
        val codecs: ObjectCodecs =
            ObjectCodecs(AutoRegistry.get().getBuilder().add(DeferredNestedSetCodec()).build())

        val serialized: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, top)
        val futureToBlockWritesOn: com.google.common.util.concurrent.ListenableFuture<*>? =
            serialized.getFutureToBlockWritesOn()
        if (futureToBlockWritesOn != null) {
            val unused: Any? = futureToBlockWritesOn.get()
        }
        val bytes: ByteString? = serialized.getObject()

        val deserialized: NestedSet<*>? =
            codecs.deserializeMemoizedAndBlocking(fingerprintValueService, bytes) as NestedSet<*>?
        // Since dumpStructure doesn't perform equivalence reduction, equivalence here means the diamond
        // reference structure was preserved by deserialization.
        assertThat(dumpStructure(top)).isEqualTo(dumpStructure(deserialized))
    }

    companion object {
        private fun verifyUsingShallowEquals(original: NestedSet, deserialized: NestedSet?) {
            assertThat(original.shallowEquals(deserialized)).isTrue()
        }
    }
}
