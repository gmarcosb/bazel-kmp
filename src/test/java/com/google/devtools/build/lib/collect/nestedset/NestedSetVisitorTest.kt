// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.collect.nestedset.NestedSetVisitor.VisitedState

/** Unit tests for [NestedSetVisitor].  */
@RunWith(JUnit4::class)
class NestedSetVisitorTest {
    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun stableOrder() {
        val set: NestedSet<Int?> =
            NestedSetBuilder.< Integer > stableOrder < Int ? > ()
                .addTransitive(NestedSetBuilder.< Integer > stableOrder < Int ? > ().add(1).add(2).add(3).build())
                .add(4)
                .add(5)
                .add(6)
                .addTransitive(NestedSetBuilder.< Integer > stableOrder < Int ? > ().add(7).add(8).add(9).build())
                .build()

        val visited: MutableList<Int?> = java.util.ArrayList<Int?>()
        NestedSetVisitor<Int?>(visited::add, VisitedState.create(HashSet<Any?>()::add))
            .visit(set)

        Truth.assertThat(visited).isEqualTo(set.toList())
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun compileOrder() {
        val set: NestedSet<Int?> =
            NestedSetBuilder.< Integer > compileOrder < Int ? > ()
                .addTransitive(NestedSetBuilder.< Integer > compileOrder < Int ? > ().add(1).add(2).add(3).build())
                .add(4)
                .add(5)
                .add(6)
                .addTransitive(NestedSetBuilder.< Integer > compileOrder < Int ? > ().add(7).add(8).add(9).build())
                .build()

        val visited: MutableList<Int?> = java.util.ArrayList<Int?>()
        NestedSetVisitor<Int?>(visited::add, VisitedState.create(HashSet<Any?>()::add))
            .visit(set)

        Truth.assertThat(visited).isEqualTo(set.toList())
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun linkOrder() {
        val set: NestedSet<Int?> =
            NestedSetBuilder.< Integer > linkOrder < Int ? > ()
                .addTransitive(NestedSetBuilder.< Integer > linkOrder < Int ? > ().add(1).add(2).add(3).build())
                .add(4)
                .add(5)
                .add(6)
                .addTransitive(NestedSetBuilder.< Integer > linkOrder < Int ? > ().add(7).add(8).add(9).build())
                .build()

        val visited: MutableList<Int?> = java.util.ArrayList<Int?>()
        NestedSetVisitor<Int?>(visited::add, VisitedState.create(HashSet<Any?>()::add))
            .visit(set)

        // #toList() for LINK_ORDER reverses the result list.
        Truth.assertThat(visited).isEqualTo(set.toList().reverse())
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun naiveLinkOrder() {
        val set: NestedSet<Int?> =
            NestedSetBuilder.< Integer > naiveLinkOrder < Int ? > ()
                .addTransitive(NestedSetBuilder.< Integer > naiveLinkOrder < Int ? > ().add(1).add(2).add(3).build())
                .add(4)
                .add(5)
                .add(6)
                .addTransitive(NestedSetBuilder.< Integer > naiveLinkOrder < Int ? > ().add(7).add(8).add(9).build())
                .build()

        val visited: MutableList<Int?> = java.util.ArrayList<Int?>()
        NestedSetVisitor<Int?>(visited::add, VisitedState.create(HashSet<Any?>()::add))
            .visit(set)

        Truth.assertThat(visited).isEqualTo(set.toList())
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun mixedOrders() {
        val set: NestedSet<Int?> =
            NestedSetBuilder.< Integer > stableOrder < Int ? > ()
                .addTransitive(
                    NestedSetBuilder.< Integer > linkOrder < Int ? > ()
                        .add(1)
                        .addTransitive(NestedSetBuilder.< Integer > linkOrder < Int ? > ().add(2).add(3).build())
                        .addTransitive(NestedSetBuilder.< Integer > linkOrder < Int ? > ().add(4).add(5).build())
                        .build()
                )
                .add(6)
                .add(7)
                .add(8)
                .addTransitive(
                    NestedSetBuilder.< Integer > naiveLinkOrder < Int ? > ()
                        .addTransitive(NestedSetBuilder.< Integer > naiveLinkOrder < Int ? > ().add(7).add(8).build())
                        .addTransitive(
                            NestedSetBuilder.< Integer > naiveLinkOrder < Int ? > ().add(9).add(10).build()
                        )
                        .add(11)
                        .build()
                )
                .build()

        val visited: MutableList<Int?> = java.util.ArrayList<Int?>()
        NestedSetVisitor<Int?>(visited::add, VisitedState.create(HashSet<Any?>()::add))
            .visit(set)

        Truth.assertThat(visited).isEqualTo(set.toList())
    }

    @org.junit.Test
    @Throws(java.lang.InterruptedException::class)
    fun duplicatesSkipped() {
        val subset: NestedSet<Int?>? =
            NestedSetBuilder.< Integer > compileOrder < Int ? > ().add(1).add(2).add(3).build()
        val set: NestedSet<Int?> =
            NestedSetBuilder.< Integer > compileOrder < Int ? > ()
                .addTransitive(subset)
                .addTransitive(
                    NestedSetBuilder.< Integer > compileOrder < Int ? > ().add(4).addTransitive(subset).build()
                )
                .add(5)
                .add(6)
                .add(7)
                .addTransitive(
                    NestedSetBuilder.< Integer > compileOrder < Int ? > ()
                        .add(8)
                        .add(9)
                        .addTransitive(subset)
                        .build()
                )
                .build()

        val visited: MutableList<Int?> = java.util.ArrayList<Int?>()
        NestedSetVisitor<Int?>(visited::add, VisitedState.create(HashSet<Any?>()::add))
            .visit(set)

        Truth.assertThat(visited).isEqualTo(set.toList())
        Truth.assertThat(visited).hasSize(9)
    }
}
