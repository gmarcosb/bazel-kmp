// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.AspectCollection.AspectCycleOnPathException

/** Tests for [AspectCollection]  */
@RunWith(JUnit4::class)
class AspectCollectionTest {
    private val a1Key: Provider.Key = object : BuiltinProvider("a1", StructImpl::class.java) {}.key
    private val a2Key: Provider.Key = object : BuiltinProvider("a2", StructImpl::class.java) {}.key
    private val a3Key: Provider.Key = object : BuiltinProvider("a3", StructImpl::class.java) {}.key

    /** a3 wants a1 and a2, a1 and a2 want no one, path is a1, a2, a3.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun linearAspectPath1() {
        val a1: Aspect = createAspect(a1Key)
        val a2: Aspect = createAspect(a2Key)
        val a3: Aspect = createAspect(a3Key, a1Key, a2Key)
        val collection: AspectCollection =
            AspectCollection.create(com.google.common.collect.ImmutableList.of<E?>(a1, a2, a3))
        validateAspectCollection(
            collection,
            com.google.common.collect.ImmutableList.of<Aspect?>(a1, a2, a3),
            expectDeps(a3, a1, a2),
            expectDeps(a1),
            expectDeps(a2)
        )
    }

    /** a3 wants a2, a2 wants a1, a1 wants no one, path is a1, a2, a3.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun linearAspectPath2() {
        val a1: Aspect = createAspect(a1Key)
        val a2: Aspect = createAspect(a2Key, a1Key)
        val a3: Aspect = createAspect(a3Key, a2Key)
        val collection: AspectCollection =
            AspectCollection.create(com.google.common.collect.ImmutableList.of<E?>(a1, a2, a3))
        validateAspectCollection(
            collection,
            com.google.common.collect.ImmutableList.of<Aspect?>(a1, a2, a3),
            expectDeps(a3, a2),
            expectDeps(a2, a1),
            expectDeps(a1)
        )
    }

    /** a3 wants a1, a1 wants a2, path is a1, a2, a3, so a2 comes after a1.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validateOrder() {
        val a1: Aspect = createAspect(a1Key, a2Key)
        val a2: Aspect = createAspect(a2Key)
        val a3: Aspect = createAspect(a3Key, a1Key)
        val collection: AspectCollection =
            AspectCollection.create(com.google.common.collect.ImmutableList.of<E?>(a1, a2, a3))
        validateAspectCollection(
            collection,
            com.google.common.collect.ImmutableList.of<Aspect?>(a1, a2, a3),
            expectDeps(a1),
            expectDeps(a2),
            expectDeps(a3, a1)
        )
    }

    /** a3 wants a1, a1 wants a2, a2 wants a1, path is a1, a2, a3, so a2 comes after a1.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validateOrder2() {
        val a1: Aspect = createAspect(a1Key, a2Key)
        val a2: Aspect = createAspect(a2Key, a1Key)
        val a3: Aspect = createAspect(a3Key, a1Key)
        val collection: AspectCollection =
            AspectCollection.create(com.google.common.collect.ImmutableList.of<E?>(a1, a2, a3))
        validateAspectCollection(
            collection,
            com.google.common.collect.ImmutableList.of<Aspect?>(a1, a2, a3),
            expectDeps(a1),
            expectDeps(a2, a1),
            expectDeps(a3, a1)
        )
    }

    /** a3 wants itself.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun recursive() {
        val a1: Aspect = createAspect(a1Key)
        val a2: Aspect = createAspect(a2Key)
        val a3: Aspect = createAspect(a3Key, a3Key)
        val collection: AspectCollection =
            AspectCollection.create(com.google.common.collect.ImmutableList.of<E?>(a1, a2, a3))
        validateAspectCollection(
            collection,
            com.google.common.collect.ImmutableList.of<Aspect?>(a1, a2, a3),
            expectDeps(a1),
            expectDeps(a2),
            expectDeps(a3)
        )
    }

    /** a2 wants a1, a3 wants nothing.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun threeAspects() {
        val a1: Aspect = createAspect(a1Key)
        val a2: Aspect = createAspect(a2Key, a1Key)
        val a3: Aspect = createAspect(a3Key)
        val collection: AspectCollection =
            AspectCollection.create(com.google.common.collect.ImmutableList.of<E?>(a1, a2, a3))
        validateAspectCollection(
            collection,
            com.google.common.collect.ImmutableList.of<Aspect?>(a1, a2, a3),
            expectDeps(a3),
            expectDeps(a2, a1),
            expectDeps(a1)
        )
    }

    /**
     * a2 wants a1, a3 wants a1 and a2, the path is [a2, a1, a2, a3], so a2 occurs twice.
     * 
     * 
     * First occurrence of a2 would not see a1, but the second would: that is an error.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateAspect() {
        val a1: Aspect = createAspect(a1Key)
        val a2: Aspect = createAspect(a2Key, a1Key)
        val a3: Aspect = createAspect(a3Key, a2Key, a1Key)
        val e: AspectCycleOnPathException =
            org.junit.Assert.assertThrows<T>(
                AspectCycleOnPathException::class.java,
                org.junit.function.ThrowingRunnable {
                    AspectCollection.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            a2,
                            a1,
                            a2,
                            a3
                        )
                    )
                })
        assertThat(e.getAspect()).isEqualTo(a2.getDescriptor())
        assertThat(e.getPreviousAspect()).isEqualTo(a1.getDescriptor())
    }

    /**
     * a2 wants a1, a3 wants a2, the path is [a2, a1, a2, a3], so a2 occurs twice.
     * 
     * 
     * First occurrence of a2 would not see a1, but the second would: that is an error.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateAspect2() {
        val a1: Aspect = createAspect(a1Key)
        val a2: Aspect = createAspect(a2Key, a1Key)
        val a3: Aspect = createAspect(a3Key, a2Key)
        val e: AspectCycleOnPathException =
            org.junit.Assert.assertThrows<T>(
                AspectCycleOnPathException::class.java,
                org.junit.function.ThrowingRunnable {
                    AspectCollection.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            a2,
                            a1,
                            a2,
                            a3
                        )
                    )
                })
        assertThat(e.getAspect()).isEqualTo(a2.getDescriptor())
        assertThat(e.getPreviousAspect()).isEqualTo(a1.getDescriptor())
    }

    /**
     * a3 wants a1 and a2, a2 does not want a1. The path is [a2, a1, a2, a3], so a2 occurs twice.
     * Second occurrence of a2 is consistent with the first.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateAspect2a() {
        val a1: Aspect = createAspect(a1Key)
        val a2: Aspect = createAspect(a2Key)
        val a3: Aspect = createAspect(a3Key, a1Key, a2Key)

        val collection: AspectCollection =
            AspectCollection.create(com.google.common.collect.ImmutableList.of<E?>(a2, a1, a2, a3))

        validateAspectCollection(
            collection,
            com.google.common.collect.ImmutableList.of<Aspect?>(a2, a1, a3),
            expectDeps(a2),
            expectDeps(a1),
            expectDeps(a3, a2, a1)
        )
    }

    /**
     * a2 wants a1, a3 wants a1 and a2, a1 wants a2. the path is [a2, a1, a2, a3], so a2 occurs twice.
     * First occurrence of a2 does not see a1, but the second does => error.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateAspect3() {
        val a1: Aspect = createAspect(a1Key, a2Key)
        val a2: Aspect = createAspect(a2Key, a1Key)
        val a3: Aspect = createAspect(a3Key, a1Key, a2Key)
        val e: AspectCycleOnPathException =
            org.junit.Assert.assertThrows<T>(
                AspectCycleOnPathException::class.java,
                org.junit.function.ThrowingRunnable {
                    AspectCollection.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            a2,
                            a1,
                            a2,
                            a3
                        )
                    )
                })
        assertThat(e.getAspect()).isEqualTo(a2.getDescriptor())
        assertThat(e.getPreviousAspect()).isEqualTo(a1.getDescriptor())
    }

    /**
     * a2 wants a1, a3 wants a2, a1 wants a2. the path is [a2, a1, a2, a3], so a2 occurs twice. First
     * occurrence of a2 does not see a1, but the second does => error. a1 disappears.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateAspect4() {
        val a1: Aspect = createAspect(a1Key, a2Key)
        val a2: Aspect = createAspect(a2Key, a1Key)
        val a3: Aspect = createAspect(a3Key, a2Key)
        val e: AspectCycleOnPathException =
            org.junit.Assert.assertThrows<T>(
                AspectCycleOnPathException::class.java,
                org.junit.function.ThrowingRunnable {
                    AspectCollection.create(
                        com.google.common.collect.ImmutableList.of<E?>(
                            a2,
                            a1,
                            a2,
                            a3
                        )
                    )
                })
        assertThat(e.getAspect()).isEqualTo(a2.getDescriptor())
        assertThat(e.getPreviousAspect()).isEqualTo(a1.getDescriptor())
    }

    /**
     * a3 wants a2, a1 wants a2. The path is [a2, a1, a2, a3], so a2 occurs twice. First occurrence of
     * a2 is consistent with the second. a1 disappears.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateAspect5() {
        val a1: Aspect = createAspect(a1Key, a2Key)
        val a2: Aspect = createAspect(a2Key)
        val a3: Aspect = createAspect(a3Key, a2Key)
        val collection: AspectCollection =
            AspectCollection.create(com.google.common.collect.ImmutableList.of<E?>(a2, a1, a2, a3))
        validateAspectCollection(
            collection,
            com.google.common.collect.ImmutableList.of<Aspect?>(a2, a1, a3),
            expectDeps(a2),
            expectDeps(a1, a2),
            expectDeps(a3, a2)
        )
    }

    /**
     * Creates an aspect with a class named `className` advertizing a provider `className`
     * that requires any of providers `requiredAspects`.
     */
    private fun createAspect(className: Provider.Key, vararg requiredAspects: Provider.Key?): Aspect {
        val requiredProvidersBuilder: com.google.common.collect.ImmutableList.Builder<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?> =
            com.google.common.collect.ImmutableList.builder<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>()

        for (requiredAspect in requiredAspects) {
            requiredProvidersBuilder.add(
                com.google.common.collect.ImmutableSet.of<E?>(StarlarkProviderIdentifier.forKey(requiredAspect))
            )
        }
        val requiredProviders: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?> =
            requiredProvidersBuilder.build()
        return Aspect.forNative(
            object : NativeAspectClass() {
                public override fun getName(): String {
                    return className.toString()
                }

                public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition {
                    return AspectDefinition.builder(this)
                        .requireAspectsWithProviders(requiredProviders)
                        .advertiseProvider(
                            com.google.common.collect.ImmutableList.of<E?>(
                                StarlarkProviderIdentifier.forKey(
                                    className
                                )
                            )
                        )
                        .build()
                }
            })
    }


    companion object {
        private fun expectDeps(
            a: Aspect?,
            vararg deps: Aspect?
        ): Pair<Aspect?, com.google.common.collect.ImmutableList<Aspect?>?> {
            return Pair.of(a, com.google.common.collect.ImmutableList.< E > copyOf < E ? > (deps))
        }

        @java.lang.SafeVarargs
        private fun validateAspectCollection(
            collection: AspectCollection,
            expectedUsedAspects: com.google.common.collect.ImmutableList<Aspect?>,
            vararg expectedPaths: Pair<Aspect?, com.google.common.collect.ImmutableList<Aspect?>?>?
        ) {
            Truth.assertThat(
                com.google.common.collect.Iterables.transform<F?, T?>(
                    collection.getUsedAspects(),
                    AspectDeps::aspect
                )
            )
                .containsExactlyElementsIn(
                    com.google.common.collect.Iterables.transform<Aspect?, Any?>(
                        expectedUsedAspects,
                        Aspect::getDescriptor
                    )
                )
                .inOrder()
            validateAspectPaths(
                collection,
                com.google.common.collect.ImmutableList.copyOf<Pair<Aspect?, com.google.common.collect.ImmutableList<Aspect?>?>?>(
                    expectedPaths
                )
            )
        }

        private fun validateAspectPaths(
            collection: AspectCollection,
            expectedList: com.google.common.collect.ImmutableList<Pair<Aspect?, com.google.common.collect.ImmutableList<Aspect?>?>>
        ) {
            val allPaths: HashMap<AspectDescriptor?, AspectDeps> = HashMap<AspectDescriptor?, AspectDeps>()
            for (aspectPath in collection.getUsedAspects()) {
                collectAndValidateAspectDeps(aspectPath, allPaths)
            }

            val expectedKeys: HashSet<AspectDescriptor?> = HashSet<AspectDescriptor?>()

            for (expected in expectedList) {
                Truth.assertThat(allPaths).containsKey(expected.first.getDescriptor())
                val aspectPath: AspectDeps = allPaths.get(expected.first.getDescriptor())
                Truth.assertThat(
                    com.google.common.collect.Iterables.transform<F?, T?>(
                        aspectPath.usedAspects(),
                        AspectDeps::aspect
                    )
                )
                    .containsExactlyElementsIn(
                        com.google.common.collect.Iterables.transform<Any?, Any?>(
                            expected.second,
                            Aspect::getDescriptor
                        )
                    )
                    .inOrder()
                expectedKeys.add(expected.first.getDescriptor())
            }
            Truth.assertThat(allPaths.keySet())
                .containsExactlyElementsIn(expectedKeys)
        }

        /**
         * Collects all aspect paths transitively visible from `aspectDeps`.
         * Validates that [AspectDeps] instance corresponding to a given [AspectDescriptor]
         * is unique.
         */
        private fun collectAndValidateAspectDeps(
            aspectDeps: AspectDeps,
            allDeps: HashMap<AspectDescriptor?, AspectDeps>
        ) {
            if (allDeps.containsKey(aspectDeps.aspect())) {
                assertWithMessage("Two different deps for aspect %s", aspectDeps.aspect())
                    .that(allDeps.get(aspectDeps.aspect()))
                    .isSameInstanceAs(aspectDeps)
                return
            }
            allDeps.put(aspectDeps.aspect(), aspectDeps)
            for (path in aspectDeps.usedAspects()) {
                collectAndValidateAspectDeps(path, allDeps)
            }
        }
    }
}
