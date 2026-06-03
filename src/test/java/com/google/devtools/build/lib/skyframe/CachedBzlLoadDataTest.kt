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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [CachedBzlLoadData].  */
@RunWith(JUnit4::class)
class CachedBzlLoadDataTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepsAreNotVisitedMultipleTimesForDiamondDependencies() {
        // Graph structure of BzlLoadValues:
        //
        //     p
        //   /  \
        //  c1  c2
        //   \  /
        //    gc

        val dummyValue: BzlLoadValue? = Mockito.mock<BzlLoadValue?>(BzlLoadValue::class.java)
        val cachedBzlLoadDataBuilderFactory: CachedBzlLoadDataBuilderFactory =
            CachedBzlLoadDataBuilderFactory()

        val gcKey: BzlLoadValue.Key = createStarlarkKey("//gc")
        val gcKey1: SkyKey = createKey("gc key1")
        val gcKey2: SkyKey = createKey("gc key2")
        val gcKey3: SkyKey = createKey("gc key3")
        val gc: CachedBzlLoadData? =
            cachedBzlLoadDataBuilderFactory
                .newCachedBzlLoadDataBuilder()
                .addDep(gcKey1)
                .addDeps(com.google.common.collect.ImmutableList.of<E?>(gcKey2, gcKey3))
                .setKey(gcKey)
                .setValue(dummyValue)
                .build()

        val c1Key: BzlLoadValue.Key = createStarlarkKey("//c1")
        val c1Key1: SkyKey = createKey("c1 key1")
        val c1: CachedBzlLoadData? =
            cachedBzlLoadDataBuilderFactory
                .newCachedBzlLoadDataBuilder()
                .addDep(c1Key1)
                .addTransitiveDeps(gc)
                .setValue(dummyValue)
                .setKey(c1Key)
                .build()

        val c2Key: BzlLoadValue.Key = createStarlarkKey("//c2")
        val c2Key1: SkyKey = createKey("c2 key1")
        val c2Key2: SkyKey = createKey("c2 key2")
        val c2: CachedBzlLoadData? =
            cachedBzlLoadDataBuilderFactory
                .newCachedBzlLoadDataBuilder()
                .addDeps(com.google.common.collect.ImmutableList.of<E?>(c2Key1, c2Key2))
                .addTransitiveDeps(gc)
                .setValue(dummyValue)
                .setKey(c2Key)
                .build()

        val pKey: BzlLoadValue.Key = createStarlarkKey("//p")
        val pKey1: SkyKey = createKey("p key1")
        val p: CachedBzlLoadData =
            cachedBzlLoadDataBuilderFactory
                .newCachedBzlLoadDataBuilder()
                .addDep(pKey1)
                .addTransitiveDeps(c1)
                .addTransitiveDeps(c2)
                .setValue(dummyValue)
                .setKey(pKey)
                .build()

        val registeredDeps: MutableList<Iterable<SkyKey?>?> = java.util.ArrayList<Iterable<SkyKey?>?>()
        val visitedBzls: MutableMap<BzlLoadValue.Key?, CachedBzlLoadData?> =
            HashMap<BzlLoadValue.Key?, CachedBzlLoadData?>()
        p.traverse(registeredDeps::add, visitedBzls)

        Truth.assertThat(registeredDeps)
            .containsExactly(
                com.google.common.collect.ImmutableList.of<Any?>(pKey1),
                com.google.common.collect.ImmutableList.of<Any?>(c1Key1),
                com.google.common.collect.ImmutableList.of<Any?>(gcKey1),
                com.google.common.collect.ImmutableList.of<Any?>(gcKey2, gcKey3),
                com.google.common.collect.ImmutableList.of<Any?>(c2Key1, c2Key2)
            )
            .inOrder()

        Truth.assertThat(visitedBzls).containsExactly(pKey, p, c1Key, c1, c2Key, c2, gcKey, gc)
    }

    companion object {
        private fun createKey(name: String): SkyKey {
            return object : SkyKey() {
                public override fun functionName(): SkyFunctionName {
                    return SkyFunctionName.createHermetic(name)
                }

                // Override toString to assist debugging.
                public override fun toString(): String {
                    return name
                }
            }
        }

        private fun createStarlarkKey(name: String?): BzlLoadValue.Key {
            return BzlLoadValue.keyForBuild(Label.parseCanonicalUnchecked(name))
        }
    }
}
