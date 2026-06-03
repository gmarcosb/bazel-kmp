// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.collect.nestedset.Order.LINK_ORDER

/** Tests for [NestedSet].  */
@RunWith(JUnit4::class)
class NestedSetTest {
    @org.junit.Test
    fun simple() {
        val set: NestedSet<String?> = nestedSetBuilder("a").build()

        assertThat(set.toList()).containsExactly("a")
        assertThat(set.isEmpty()).isFalse()
    }

    @org.junit.Test
    fun flatToString() {
        assertThat(nestedSetBuilder().build().toString()).isEqualTo("[]")
        assertThat(nestedSetBuilder("a").build().toString()).isEqualTo("[a]")
        assertThat(nestedSetBuilder("a", "b").build().toString()).isEqualTo("[a, b]")
    }

    @org.junit.Test
    fun nestedToString() {
        val b: NestedSet<String?>? = nestedSetBuilder("b1", "b2").build()
        val c: NestedSet<String?>? = nestedSetBuilder("c1", "c2").build()

        assertThat(nestedSetBuilder("a").addTransitive(b).build().toString()).isEqualTo("[b1, b2, a]")
        assertThat(nestedSetBuilder("a").addTransitive(b).addTransitive(c).build().toString())
            .isEqualTo("[b1, b2, c1, c2, a]")
        val linkOrderSet: NestedSet<String?> =
            NestedSetBuilder.< String > linkOrder < kotlin . String ? > ().add("a").addTransitive(b).addTransitive(c)
                .build()
        assertThat(linkOrderSet.toString()).isEqualTo("[a, b2, b1, c2, c1]")

        assertThat(nestedSetBuilder().addTransitive(b).build().toString()).isEqualTo("[b1, b2]")
    }

    @org.junit.Test
    fun tooLongToString() {
        val builder: NestedSetBuilder<Int?> = NestedSetBuilder.stableOrder()
        for (i in 0..<NestedSet.MAX_ELEMENTS_TO_STRING + 3) {
            builder.add(i)
        }
        val stringRep: String? = builder.build().toString()
        Truth.assertThat(stringRep).contains("[0, 1, 2, 3")
        Truth.assertThat(stringRep)
            .containsMatch(
                ("\\[0, 1, 2, 3, .*"
                        + (NestedSet.MAX_ELEMENTS_TO_STRING - 2)
                        + ", "
                        + (NestedSet.MAX_ELEMENTS_TO_STRING - 1)
                        + "] \\(truncated, full size "
                        + (NestedSet.MAX_ELEMENTS_TO_STRING + 3)
                        + "\\)")
            )
    }

    @get:org.junit.Test
    val isEmpty: Unit
        get() {
            val triviallyEmpty: NestedSet<String?> = nestedSetBuilder().build()
            assertThat(triviallyEmpty.isEmpty()).isTrue()

            val emptyLevel1: NestedSet<String?> =
                nestedSetBuilder().addTransitive(triviallyEmpty).build()
            assertThat(emptyLevel1.isEmpty()).isTrue()

            val emptyLevel2: NestedSet<String?> =
                nestedSetBuilder().addTransitive(emptyLevel1).build()
            assertThat(emptyLevel2.isEmpty()).isTrue()

            val triviallyNonEmpty: NestedSet<String?> =
                nestedSetBuilder("mango").build()
            assertThat(triviallyNonEmpty.isEmpty()).isFalse()

            val nonEmptyLevel1: NestedSet<String?> =
                nestedSetBuilder().addTransitive(triviallyNonEmpty).build()
            assertThat(nonEmptyLevel1.isEmpty()).isFalse()

            val nonEmptyLevel2: NestedSet<String?> =
                nestedSetBuilder().addTransitive(nonEmptyLevel1).build()
            assertThat(nonEmptyLevel2.isEmpty()).isFalse()
        }

    @org.junit.Test
    fun canIncludeAnyOrderInStableOrderAndViceVersa() {
        NestedSetBuilder.stableOrder()
            .addTransitive(
                NestedSetBuilder.compileOrder()
                    .addTransitive(NestedSetBuilder.stableOrder().build())
                    .build()
            )
            .addTransitive(
                NestedSetBuilder.linkOrder()
                    .addTransitive(NestedSetBuilder.stableOrder().build())
                    .build()
            )
            .addTransitive(
                NestedSetBuilder.naiveLinkOrder()
                    .addTransitive(NestedSetBuilder.stableOrder().build())
                    .build()
            )
            .build()
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            "Shouldn't be able to include a non-stable order inside a different non-stable order!",
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                NestedSetBuilder.compileOrder()
                    .addTransitive(NestedSetBuilder.linkOrder().build())
                    .build()
            })
    }

    @org.junit.Test
    fun reusesSingleTransitiveSet_noDirectMembers() {
        val set: NestedSet<String?>? = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b", "c")
        val built: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().addTransitive(set).build()
        assertThat(built).isSameInstanceAs(set)
    }

    @org.junit.Test
    fun reusesSingleTransitiveSet_singletonEqualsDirects() {
        val set: NestedSet<String?>? = NestedSetBuilder.create(Order.STABLE_ORDER, "a")
        val built: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").addTransitive(set).build()
        assertThat(built).isSameInstanceAs(set)
    }

    @org.junit.Test
    fun noReuseOfSingleTransitiveSet_orderWouldDiffer() {
        val set: NestedSet<String?> = NestedSetBuilder.create(Order.NAIVE_LINK_ORDER, "b", "a")
        val built: NestedSet<String?> =
            NestedSetBuilder.< String > naiveLinkOrder < kotlin . String ? > ().add("a").add("b").addTransitive(set)
                .build()
        assertThat(built).isNotSameInstanceAs(set)
        assertThat(set.toList()).containsExactly("b", "a").inOrder()
        assertThat(built.toList()).containsExactly("a", "b").inOrder()
    }

    /**
     * A handy wrapper that allows us to use EqualsTester to test shallowEquals and shallowHashCode.
     */
    private class SetWrapper<E>(wrapped: NestedSet<E?>) {
        var set: NestedSet<E?>

        init {
            set = wrapped
        }

        override fun hashCode(): Int {
            return set.shallowHashCode()
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is SetWrapper<*>) {
                return false
            }
            try {
                val other = o as SetWrapper<E?>
                return set.shallowEquals(other.set)
            } catch (e: java.lang.ClassCastException) {
                return false
            }
        }
    }

    @org.junit.Test
    fun shallowEquality() {
        // Used below to check that inner nested sets can be compared by reference equality.
        val myRef = nest<Int?>(nest<Int?>(flat<Int?>(7, 8)), flat<Int?>(9))
        // Used to check equality for deserializing nested sets
        val contents: com.google.common.util.concurrent.ListenableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.Futures.immediateFuture<Array<Any?>?>(
                arrayOf<Any>("a", "b")
            )
        val referenceNestedSet: NestedSet<String?> =
            NestedSet.withFuture(Order.STABLE_ORDER, UNKNOWN_DEPTH, contents)
        val otherReferenceNestedSet: NestedSet<String?> =
            NestedSet.withFuture(Order.STABLE_ORDER, UNKNOWN_DEPTH, contents)

        // Each "equality group" contains elements that are equal to one another
        // (according to equals() and hashCode()), yet distinct from all elements
        // of all other equality groups.
        EqualsTester()
            .addEqualityGroup(flat<Any?>(), flat<Any?>(), nest<Any?>(flat<Any?>())) // Empty set elision.
            .addEqualityGroup(NestedSetBuilder.< Integer > linkOrder < Int ? > ().build())
            .addEqualityGroup(flat<Int?>(3), flat<Int?>(3), flat<Int?>(3, 3)) // Element de-duplication.
            .addEqualityGroup(flat<Int?>(4), nest<Int?>(flat<Int?>(4))) // Automatic elision of one-element nested sets.
            .addEqualityGroup(NestedSetBuilder.< Integer > linkOrder < Int ? > ().add(4).build())
            .addEqualityGroup(nestedSetBuilder("4").build()) // Like flat("4").
            .addEqualityGroup(
                flat<Int?>(3, 4),
                flat<Int?>(3, 4)
            ) // Make a couple sets deep enough that shallowEquals() fails.
            // If this test case fails because you improve the representation, just delete it.
            .addEqualityGroup(
                nest<Int?>(
                    nest<Int?>(flat<Int?>(3, 4), flat<Int?>(5)),
                    nest<Int?>(flat<Int?>(6, 7), flat<Int?>(8))
                )
            )
            .addEqualityGroup(
                nest<Int?>(
                    nest<Int?>(flat<Int?>(3, 4), flat<Int?>(5)),
                    nest<Int?>(flat<Int?>(6, 7), flat<Int?>(8))
                )
            )
            .addEqualityGroup(nest<Int?>(myRef), nest<Int?>(myRef), nest<Int?>(myRef, myRef)) // Set de-duplication.
            .addEqualityGroup(nest(3, myRef))
            .addEqualityGroup(nest(4, myRef))
            .addEqualityGroup(
                SetWrapper<Any?>(referenceNestedSet), SetWrapper<Any?>(otherReferenceNestedSet)
            )
            .testEquals()

        // Some things that are not tested by the above:
        //  - ordering among direct members
        //  - ordering among transitive sets
    }

    @org.junit.Test
    fun shallowInequality() {
        assertThat(nestedSetBuilder("a").build().shallowEquals(null)).isFalse()
        val contents = arrayOf<Any?>("a", "b")
        assertThat(
            NestedSet.withFuture(
                Order.STABLE_ORDER,
                UNKNOWN_DEPTH,
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(contents)
            )
                .shallowEquals(null)
        )
            .isFalse()

        // shallowEquals() should require reference equality for underlying futures
        assertThat(
            NestedSet.withFuture(
                Order.STABLE_ORDER,
                UNKNOWN_DEPTH,
                com.google.common.util.concurrent.Futures.immediateFuture<V?>(contents)
            )
                .shallowEquals(
                    NestedSet.withFuture(
                        Order.STABLE_ORDER,
                        UNKNOWN_DEPTH,
                        com.google.common.util.concurrent.Futures.immediateFuture<V?>(contents)
                    )
                )
        )
            .isFalse()
    }

    /** Checks that the builder always return a nested set with the correct order.  */
    @org.junit.Test
    fun correctOrder() {
        for (order in Order.values()) {
            for (numDirects in 0..2) {
                for (numTransitives in 0..2) {
                    assertThat(createNestedSet(order, numDirects, numTransitives, order).getOrder())
                        .isEqualTo(order)
                    // We allow mixing orders if one of them is stable. This tests that the top level order is
                    // the correct one.
                    assertThat(
                        createNestedSet(order, numDirects, numTransitives, Order.STABLE_ORDER).getOrder()
                    )
                        .isEqualTo(order)
                }
            }
        }
    }

    @org.junit.Test
    fun memoizedFlattenAndGetSize() {
        val empty: NestedSet<String?> = NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().build()
        checkSize(empty, 0) // {}

        val singleton: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").build()
        checkSize(singleton, 1) // {a}

        val deuce: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()
        checkSize(deuce, 2) // {a, b}

        checkSize(
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .add("a")
                .addTransitive(deuce)
                .addTransitive(singleton)
                .addTransitive(empty)
                .build(),
            2
        ) // {a, b}
        checkSize(
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .add("c")
                .addTransitive(deuce)
                .addTransitive(singleton)
                .addTransitive(empty)
                .build(),
            3
        ) // {a, b, c}

        // 25000 has a 3-digit base128 encoding.
        val largeShallow: NestedSetBuilder<Int?> = NestedSetBuilder.stableOrder()
        for (i in 0..24999) {
            largeShallow.add(i)
        }
        checkSize(largeShallow.build(), 25000) // {0, 1, ..., 24999}

        // a deep and narrow graph
        var deep: NestedSet<String?> = deuce
        for (i in 0..199) {
            deep = NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().addTransitive(deep).add("c").build()
        }
        checkSize(deep, 3) // {a, b, c}
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concurrentMemoizedFlattenAndGetSize() {
        var deep: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()
        for (i in 0..199) {
            deep = NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().addTransitive(deep).add("c").build()
        }
        val underTest: NestedSet<String?> = deep
        val threads: MutableList<TestThread> = java.util.ArrayList<TestThread>(20)
        for (i in 0..19) {
            threads.add(TestThread(underTest::memoizedFlattenAndGetSize))
        }
        for (thread in threads) {
            thread.start()
        }
        for (thread in threads) {
            thread.joinAndAssertState(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS)
        }
    }

    @org.junit.Test
    fun hoistingKeepsSetSmall() {
        val first: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").build()
        val second: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").build()
        val singleton: NestedSet<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().addTransitive(first).addTransitive(second)
                .build()
        assertThat(singleton.toList()).containsExactly("a")
        assertThat(singleton.isSingleton()).isTrue()
    }

    @org.junit.Test
    fun buildInterruptibly_propagatesInterrupt() {
        val deserializingNestedSet: NestedSet<String?>? =
            NestedSet.withFuture(
                Order.STABLE_ORDER,
                UNKNOWN_DEPTH,
                com.google.common.util.concurrent.SettableFuture.create<V?>()
            )
        val builder: NestedSetBuilder<String?> =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().addTransitive(deserializingNestedSet)
                .add("a")
        java.lang.Thread.currentThread().interrupt()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            builder::buildInterruptibly
        )
    }

    @get:org.junit.Test
    val childrenInterruptibly_propagatesInterrupt: Unit
        get() {
            val deserializingNestedSet: NestedSet<String?> =
                NestedSet.withFuture(
                    Order.STABLE_ORDER,
                    UNKNOWN_DEPTH,
                    com.google.common.util.concurrent.SettableFuture.create<V?>()
                )
            java.lang.Thread.currentThread().interrupt()
            org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
                java.lang.InterruptedException::class.java,
                deserializingNestedSet::getChildrenInterruptibly
            )
        }

    @org.junit.Test
    fun toListInterruptibly_propagatesInterrupt() {
        val deserializingNestedSet: NestedSet<String?> =
            NestedSet.withFuture(
                Order.STABLE_ORDER,
                UNKNOWN_DEPTH,
                com.google.common.util.concurrent.SettableFuture.create<V?>()
            )
        java.lang.Thread.currentThread().interrupt()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            deserializingNestedSet::toListInterruptibly
        )
    }

    @org.junit.Test
    fun toListInterruptibly_propagatesMissingFingerprintValueException() {
        val deserializingNestedSet: NestedSet<String?> =
            NestedSet.withFuture(
                Order.STABLE_ORDER,
                UNKNOWN_DEPTH,
                com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(
                    MissingFingerprintValueException(getFingerprintForTesting("fingerprint"))
                )
            )
        org.junit.Assert.assertThrows<T?>(
            MissingFingerprintValueException::class.java, deserializingNestedSet::toListInterruptibly
        )
    }

    @org.junit.Test
    fun toListWithTimeout_propagatesInterrupt() {
        val deserializingNestedSet: NestedSet<String?> =
            NestedSet.withFuture(
                Order.STABLE_ORDER,
                UNKNOWN_DEPTH,
                com.google.common.util.concurrent.SettableFuture.create<V?>()
            )
        java.lang.Thread.currentThread().interrupt()
        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { deserializingNestedSet.toListWithTimeout(java.time.Duration.ofDays(1)) })
    }

    @org.junit.Test
    fun toListWithTimeout_propagatesMissingFingerprintValueException() {
        val deserializingNestedSet: NestedSet<String?> =
            NestedSet.withFuture(
                Order.STABLE_ORDER,
                UNKNOWN_DEPTH,
                com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(
                    MissingFingerprintValueException(getFingerprintForTesting("fingerprint"))
                )
            )
        org.junit.Assert.assertThrows<T?>(
            MissingFingerprintValueException::class.java,
            org.junit.function.ThrowingRunnable { deserializingNestedSet.toListWithTimeout(java.time.Duration.ofNanos(1)) })
    }

    @org.junit.Test
    fun toListWithTimeout_timesOut() {
        val deserializingNestedSet: NestedSet<String?> =
            NestedSet.withFuture(
                Order.STABLE_ORDER,
                UNKNOWN_DEPTH,
                com.google.common.util.concurrent.SettableFuture.create<V?>()
            )
        org.junit.Assert.assertThrows<java.util.concurrent.TimeoutException?>(
            java.util.concurrent.TimeoutException::class.java,
            org.junit.function.ThrowingRunnable { deserializingNestedSet.toListWithTimeout(java.time.Duration.ofNanos(1)) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toListWithTimeout_waits() {
        val future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
            com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
        val deserializingNestedSet: NestedSet<String?> =
            NestedSet.withFuture(Order.STABLE_ORDER, UNKNOWN_DEPTH, future)
        val result: java.util.concurrent.Future<com.google.common.collect.ImmutableList<String?>?> =
            Executors.newSingleThreadExecutor()
                .submit(java.lang.Runnable { deserializingNestedSet.toListWithTimeout(java.time.Duration.ofMinutes(1)) })
        java.lang.Thread.sleep(100)
        Truth.assertThat(result.isDone()).isFalse()
        future.set(arrayOf<Any>("a", "b"))
        Truth.assertThat(result.get()).containsExactly("a", "b")
    }

    @get:org.junit.Test
    val isFromStorage_true: Unit
        get() {
            val deserializingNestedSet: NestedSet<*> =
                NestedSet.withFuture(
                    Order.STABLE_ORDER,
                    UNKNOWN_DEPTH,
                    com.google.common.util.concurrent.SettableFuture.create<V?>()
                )
            assertThat(deserializingNestedSet.isFromStorage()).isTrue()
        }

    @get:org.junit.Test
    val isFromStorage_false: Unit
        get() {
            val inMemoryNestedSet: NestedSet<*> = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b")
            assertThat(inMemoryNestedSet.isFromStorage()).isFalse()
        }

    @get:org.junit.Test
    val isReady_inMemory: Unit
        get() {
            val inMemoryNestedSet: NestedSet<*> = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b")
            assertThat(inMemoryNestedSet.isReady()).isTrue()
        }

    @get:org.junit.Test
    val isReady_fromStorage: Unit
        get() {
            val future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?> =
                com.google.common.util.concurrent.SettableFuture.create<Array<Any?>?>()
            val deserializingNestedSet: NestedSet<*> =
                NestedSet.withFuture(Order.STABLE_ORDER, UNKNOWN_DEPTH, future)
            assertThat(deserializingNestedSet.isReady()).isFalse()
            future.set(arrayOf<Any>("a", "b"))
            assertThat(deserializingNestedSet.isReady()).isTrue()
        }

    @get:org.junit.Test
    val isReady_fromStorage_cancelled: Unit
        get() {
            val deserializingNestedSet: NestedSet<*> =
                NestedSet.withFuture(
                    Order.STABLE_ORDER,
                    UNKNOWN_DEPTH,
                    com.google.common.util.concurrent.Futures.immediateCancelledFuture<V?>()
                )
            assertThat(deserializingNestedSet.isReady()).isFalse()
        }

    @get:org.junit.Test
    val isReady_fromStorage_failed: Unit
        get() {
            val deserializingNestedSet: NestedSet<*> =
                NestedSet.withFuture(
                    Order.STABLE_ORDER,
                    UNKNOWN_DEPTH,
                    com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(
                        MissingFingerprintValueException(getFingerprintForTesting("fingerprint"))
                    )
                )
            assertThat(deserializingNestedSet.isReady()).isFalse()
        }

    @get:org.junit.Test
    val approxDepth: Unit
        get() {
            val empty: NestedSet<String?> = nestedSetBuilder().build()
            val justA: NestedSet<String?> = nestedSetBuilder("a").build()
            val justB: NestedSet<String?> = nestedSetBuilder("b").build()
            val ab: NestedSet<String?>? =
                nestedSetBuilder().addTransitive(justA).addTransitive(justB).build()

            assertThat(empty.getApproxDepth()).isEqualTo(0)
            assertThat(
                nestedSetBuilder().addTransitive(empty).addTransitive(empty).build()
                    .getApproxDepth()
            )
                .isEqualTo(0)
            assertThat(justA.getApproxDepth()).isEqualTo(1)
            assertThat(justB.getApproxDepth()).isEqualTo(1)
            assertThat(
                nestedSetBuilder().addTransitive(empty).addTransitive(empty).build()
                    .getApproxDepth()
            )
                .isEqualTo(0)
            assertThat(
                nestedSetBuilder().addTransitive(empty).addTransitive(justA).build()
                    .getApproxDepth()
            )
                .isEqualTo(1)
            assertThat(
                nestedSetBuilder().addTransitive(justA).addTransitive(empty).build()
                    .getApproxDepth()
            )
                .isEqualTo(1)
            assertThat(
                nestedSetBuilder().addTransitive(justA).addTransitive(justA).build()
                    .getApproxDepth()
            )
                .isEqualTo(1)
            assertThat(
                nestedSetBuilder().addTransitive(justA).addTransitive(justB).build()
                    .getApproxDepth()
            )
                .isEqualTo(2)
            assertThat(
                nestedSetBuilder("a", "b", "c")
                    .addTransitive(justA)
                    .addTransitive(justB)
                    .addTransitive(ab)
                    .build()
                    .getApproxDepth()
            )
                .isEqualTo(3)
        }

    @org.junit.Test
    fun linkOrder_toList_withTransitiveInputAliases_areConsistent() {
        val inputA: NestedSet<String?>? = NestedSetBuilder.create(LINK_ORDER, "A")
        val inputB: NestedSet<String?>? = NestedSetBuilder.create(LINK_ORDER, "B")
        val inputC: NestedSet<String?>? = NestedSetBuilder.create(LINK_ORDER, "C")
        val inputB2: NestedSet<String?>? = NestedSetBuilder.create(LINK_ORDER, "B")

        val withDuplicates: NestedSet<String?> =
            NestedSet.< String > builder < kotlin . String ? > (LINK_ORDER)
                .addTransitive(inputA)
                .addTransitive(inputB)
                .addTransitive(inputC)
                .addTransitive(inputB)
                .build()

        val withAlias: NestedSet<String?> =
            NestedSet.< String > builder < kotlin . String ? > (LINK_ORDER)
                .addTransitive(inputA)
                .addTransitive(inputB)
                .addTransitive(inputC)
                .addTransitive(inputB2)
                .build()

        assertThat(withAlias.toList()).isEqualTo(withDuplicates.toList())
    }

    @org.junit.Test
    fun linkOrder_toList_withDuplicateDirectInputs_keepsFirst() {
        val duplicateInputs: NestedSet<String?> = NestedSetBuilder.create(LINK_ORDER, "A", "B", "C", "A")
        assertThat(duplicateInputs.toList()).containsExactly("A", "B", "C").inOrder()
    }

    companion object {
        private fun nestedSetBuilder(vararg directMembers: String?): NestedSetBuilder<String?> {
            val builder: NestedSetBuilder<String?> = NestedSetBuilder.stableOrder()
            builder.addAll(com.google.common.collect.Lists.< E > newArrayList < E ? > (directMembers))
            return builder
        }

        @java.lang.SafeVarargs
        private fun <E> flat(vararg directMembers: E?): SetWrapper<E?> {
            val builder: NestedSetBuilder<E?> = NestedSetBuilder.stableOrder()
            builder.addAll(com.google.common.collect.Lists.newArrayList<E?>(*directMembers))
            return SetWrapper<E?>(builder.build())
        }

        @java.lang.SafeVarargs
        private fun <E> nest(vararg nested: SetWrapper<E?>): SetWrapper<E?> {
            val builder: NestedSetBuilder<E?> = NestedSetBuilder.stableOrder()
            for (wrap in nested) {
                builder.addTransitive(wrap.set)
            }
            return SetWrapper<E?>(builder.build())
        }

        @java.lang.SafeVarargs // Restricted to <Integer> to avoid ambiguity with the other nest() function.
        private fun nest(elem: Int?, vararg nested: SetWrapper<Int?>): SetWrapper<Int?> {
            val builder: NestedSetBuilder<Int?> = NestedSetBuilder.stableOrder()
            builder.add(elem)
            for (wrap in nested) {
                builder.addTransitive(wrap.set)
            }
            return SetWrapper<E?>(builder.build())
        }

        private const val UNKNOWN_DEPTH = 7

        private fun createNestedSet(
            order: Order?, numDirects: Int, numTransitives: Int, transitiveOrder: Order?
        ): NestedSet<Int?> {
            val builder: NestedSetBuilder<Int?> = NestedSetBuilder.newBuilder(order)

            for (direct in 0..<numDirects) {
                builder.add(direct)
            }
            for (transitive in 0..<numTransitives) {
                builder.addTransitive(NestedSet.< Integer > builder < Int ? > (transitiveOrder).add(transitive).build())
            }
            return builder.build()
        }

        private fun checkSize(set: NestedSet<*>, size: Int) {
            assertThat(set.memoizedFlattenAndGetSize()).isEqualTo(size) // first call: flattens
            assertThat(set.memoizedFlattenAndGetSize()).isEqualTo(size) // second call: memoized
        }
    }
}
