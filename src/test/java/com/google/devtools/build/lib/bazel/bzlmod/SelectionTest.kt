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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.InterimModuleBuilder
import com.google.devtools.build.lib.bazel.repository.downloader.HttpStream.Factory.create
import com.google.devtools.build.lib.bazel.repository.downloader.ProgressInputStream.Factory.create
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [Selection].  */
@RunWith(JUnit4::class)
class SelectionTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diamond_simple() {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addDep("ddd_from_ccc", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry())
                .buildOrThrow()

        val selectionResult: Selection.Result =
            Selection.run(depGraph,  /* overrides= */com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diamond_nodeps() {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addNodepDep(BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry())
                .buildOrThrow()

        val selectionResult: Selection.Result =
            Selection.run(depGraph,  /* overrides= */com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addNodepDep(BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addNodepDep(BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry()
            )
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diamond_withFurtherRemoval() {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ddd", "1.0")
                        .addDep("eee", BzlmodTestUtil.createModuleKey("eee", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry()
                ) // Only D@1.0 needs E. When D@1.0 is removed, E should be gone as well (even though
                // E@1.0 is selected for E).
                .put(InterimModuleBuilder.Companion.create("eee", "1.0").buildEntry())
                .buildOrThrow()

        val selectionResult: Selection.Result =
            Selection.run(depGraph,  /* overrides= */com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0")
                    .addDep("eee", BzlmodTestUtil.createModuleKey("eee", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("eee", "1.0").buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun diamond_withFurtherRemoval_andNoDeps() {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ddd", "1.0")
                        .addDep("eee", BzlmodTestUtil.createModuleKey("eee", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ddd", "2.0")
                        .addNodepDep(BzlmodTestUtil.createModuleKey("eee", "2.0"))
                        .buildEntry()
                ) // eee@2.0 ends up being selected over eee@1.0. But eee@2.0 is not actually reachable
                // from the root, since ddd@1.0 isn't selected, and ddd@2.0 only has a nodep dep on
                // eee@2.0. So neither version of eee@2.0 ends up in the final dep graph.
                .put(InterimModuleBuilder.Companion.create("eee", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("eee", "2.0").buildEntry())
                .buildOrThrow()

        val selectionResult: Selection.Result =
            Selection.run(depGraph,  /* overrides= */com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0")
                    .addNodepDep(BzlmodTestUtil.createModuleKey("eee", "2.0"))
                    .buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .addOriginalDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0")
                    .addDep("eee", BzlmodTestUtil.createModuleKey("eee", "2.0"))
                    .addOriginalDep("eee", BzlmodTestUtil.createModuleKey("eee", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0")
                    .addNodepDep(BzlmodTestUtil.createModuleKey("eee", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("eee", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("eee", "2.0").buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun circularDependencyDueToSelection() {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0-pre"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0-pre")
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry())
                .buildOrThrow()

        val selectionResult: Selection.Result =
            Selection.run(depGraph,  /* overrides= */com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addOriginalDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0-pre"))
                    .buildEntry()
            )
            .inOrder()

        // D is completely gone.
        assertThat(selectionResult.unprunedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addOriginalDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0-pre"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0-pre")
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun maxCompatibilityBasedSelection_nonGreedySelection() {
        // A dep graph in which always picking the highest reachable version for each module resulted
        // in a valid selection. This test used to be about compatibility levels, but now it just
        // verifies standard greedy selection.
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ccc_from_bbb", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "2.0")
                        .addDep("ccc_from_bbb", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "1.0")
                        .addDep("bbb_from_ccc", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addDep("bbb_from_ccc", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .buildEntry()
                )
                .buildOrThrow()

        val selectionResult: Selection.Result =
            Selection.run(depGraph, com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                    .addOriginalDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "2.0")
                    .addDep("ccc_from_bbb", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc_from_bbb", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("bbb_from_ccc", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                    .addOriginalDep("bbb_from_ccc", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                    .addOriginalDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ccc_from_bbb", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "2.0")
                    .addDep("ccc_from_bbb", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc_from_bbb", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0")
                    .addDep("bbb_from_ccc", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("bbb_from_ccc", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                    .addOriginalDep("bbb_from_ccc", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .buildEntry()
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun differentCompatibilityLevelIsOkIfUnreferenced() {
        // aaa 1.0 -> bbb 1.0 -> ccc 2.0
        //       \-> ccc 1.0
        //        \-> ddd 1.0 -> bbb 1.1
        //         \-> eee 1.0 -> ccc 1.1
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    InterimModuleBuilder.Companion.create("aaa", "1.0")
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .addDep("eee", BzlmodTestUtil.createModuleKey("eee", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "1.0").buildEntry())
                .put(
                    InterimModuleBuilder.Companion.create("ddd", "1.0")
                        .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.1"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("bbb", "1.1").buildEntry())
                .put(
                    InterimModuleBuilder.Companion.create("eee", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.1"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ccc", "1.1").buildEntry())
                .buildOrThrow()

        // After selection, ccc 2.0 wins because it's now in the same selection group as ccc 1.1.
        // aaa 1.0 -> bbb 1.1
        //       \-> ccc 1.1 -> ccc 2.0
        //        \-> ddd 1.0 -> bbb 1.1
        //         \-> eee 1.0 -> ccc 1.1 -> ccc 2.0
        val selectionResult: Selection.Result =
            Selection.run(depGraph,  /* overrides= */com.google.common.collect.ImmutableMap.of<K?, V?>())
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "1.0")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.1"))
                    .addOriginalDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .addDep("eee", BzlmodTestUtil.createModuleKey("eee", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.1").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0")
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.1"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("eee", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.1"))
                    .buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph().entrySet())
            .containsExactly(
                InterimModuleBuilder.Companion.create("aaa", "1.0")
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.1"))
                    .addOriginalDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .addDep("eee", BzlmodTestUtil.createModuleKey("eee", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.1").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.1").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0")
                    .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.1"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("eee", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.1"))
                    .buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleVersionOverride_fork_allowedVersionMissingInDepGraph() {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("bbb", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("bbb", "2.0").buildEntry())
                .buildOrThrow()
        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "bbb",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(
                        Version.parse("1.0"),
                        Version.parse("2.0"),
                        Version.parse("3.0")
                    ),
                    ""
                )
            )

        val e: ExternalDepsException? =
            org.junit.Assert.assertThrows<T?>(
                ExternalDepsException::class.java,
                org.junit.function.ThrowingRunnable { Selection.run(depGraph, overrides) })
        assertThat(e)
            .hasMessageThat()
            .contains(
                "multiple_version_override for module bbb contains version 3.0, but it doesn't exist in"
                        + " the dependency graph"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleVersionOverride_fork_goodCase() {
        // For more complex good cases, see the "diamond" test cases below.
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("bbb", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("bbb", "2.0").buildEntry())
                .buildOrThrow()
        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "bbb",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(Version.parse("1.0"), Version.parse("2.0")), ""
                )
            )

        val selectionResult: Selection.Result = Selection.run(depGraph, overrides)
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "2.0").buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph()).isEqualTo(selectionResult.resolvedDepGraph())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleVersionOverride_fork_sameVersionUsedTwice() {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb", "1.3"))
                        .addDep("bbb3", BzlmodTestUtil.createModuleKey("bbb", "1.5"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("bbb", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("bbb", "1.3").buildEntry())
                .put(InterimModuleBuilder.Companion.create("bbb", "1.5").buildEntry())
                .buildOrThrow()
        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "bbb",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(Version.parse("1.0"), Version.parse("1.5")), ""
                )
            )

        val e: ExternalDepsException? =
            org.junit.Assert.assertThrows<T?>(
                ExternalDepsException::class.java,
                org.junit.function.ThrowingRunnable { Selection.run(depGraph, overrides) })
        assertThat(e)
            .hasMessageThat()
            .containsMatch(
                "aaa@_ depends on bbb@1.5 at least twice \\(with repo names (bbb2 and bbb3)|(bbb3 and"
                        + " bbb2)\\)"
            )
        assertThat(e)
            .hasMessageThat()
            .contains("if you want to depend on multiple versions of bbb simultaneously")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleVersionOverride_diamond_differentCompatibilityLevels() {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addDep("ddd_from_ccc", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry())
                .buildOrThrow()
        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "ddd",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(Version.parse("1.0"), Version.parse("2.0")), ""
                )
            )

        val selectionResult: Selection.Result = Selection.run(depGraph, overrides)
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph()).isEqualTo(selectionResult.resolvedDepGraph())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleVersionOverride_diamond_sameCompatibilityLevel() {
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addDep("ddd_from_ccc", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry())
                .buildOrThrow()
        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "ddd",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(Version.parse("1.0"), Version.parse("2.0")), ""
                )
            )

        val selectionResult: Selection.Result = Selection.run(depGraph, overrides)
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb_from_aaa", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                    .addDep("ccc_from_aaa", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb", "1.0")
                    .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0")
                    .addDep("ddd_from_ccc", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph()).isEqualTo(selectionResult.resolvedDepGraph())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleVersionOverride_diamond_snappingToNextHighestVersion() {
        // aaa --> bbb1@1.0 -> ccc@1.0
        //     \-> bbb2@1.0 -> ccc@1.3  [allowed]
        //     \-> bbb3@1.0 -> ccc@1.5
        //     \-> bbb4@1.0 -> ccc@1.7  [allowed]
        //     \-> bbb5@1.0 -> ccc@2.0  [allowed]
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb1", "1.0"))
                        .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.0"))
                        .addDep("bbb3", BzlmodTestUtil.createModuleKey("bbb3", "1.0"))
                        .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.0"))
                        .addDep("bbb5", BzlmodTestUtil.createModuleKey("bbb5", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb1", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb2", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.3"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb3", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.5"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb4", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.7"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb5", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ccc", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "1.3").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "1.5").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "1.7").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry())
                .buildOrThrow()
        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "ccc",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(
                        Version.parse("1.3"),
                        Version.parse("1.7"),
                        Version.parse("2.0")
                    ),
                    ""
                )
            )

        // aaa --> bbb1@1.0 -> ccc@1.3  [originally ccc@1.0]
        //     \-> bbb2@1.0 -> ccc@1.3  [allowed]
        //     \-> bbb3@1.0 -> ccc@1.7  [originally ccc@1.5]
        //     \-> bbb4@1.0 -> ccc@1.7  [allowed]
        //     \-> bbb5@1.0 -> ccc@2.0  [allowed]
        val selectionResult: Selection.Result = Selection.run(depGraph, overrides)
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb1", "1.0"))
                    .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.0"))
                    .addDep("bbb3", BzlmodTestUtil.createModuleKey("bbb3", "1.0"))
                    .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.0"))
                    .addDep("bbb5", BzlmodTestUtil.createModuleKey("bbb5", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb1", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.3"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb2", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.3"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb3", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.7"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.5"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb4", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.7"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb5", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.3").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.7").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb1", "1.0"))
                    .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.0"))
                    .addDep("bbb3", BzlmodTestUtil.createModuleKey("bbb3", "1.0"))
                    .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.0"))
                    .addDep("bbb5", BzlmodTestUtil.createModuleKey("bbb5", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb1", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.3"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb2", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.3"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb3", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.7"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.5"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb4", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.7"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb5", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.3").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.5").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.7").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry()
            )
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleVersionOverride_diamond_unknownCompatibility() {
        // aaa --> bbb1@1.0 -> ccc@1.0  [allowed]
        //     \-> bbb2@1.0 -> ccc@2.0  [allowed]
        //     \-> bbb3@1.0 -> ccc@3.0
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb1", "1.0"))
                        .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.0"))
                        .addDep("bbb3", BzlmodTestUtil.createModuleKey("bbb3", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb1", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb2", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb3", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "3.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ccc", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "3.0").buildEntry())
                .buildOrThrow()
        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "ccc",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(Version.parse("1.0"), Version.parse("2.0")), ""
                )
            )

        val e: ExternalDepsException? =
            org.junit.Assert.assertThrows<T?>(
                ExternalDepsException::class.java,
                org.junit.function.ThrowingRunnable { Selection.run(depGraph, overrides) })
        assertThat(e)
            .hasMessageThat()
            .contains(
                "bbb3@1.0 depends on ccc@3.0 which is not allowed by the multiple_version_override on"
                        + " ccc, which allows only [1.0, 2.0]"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleVersionOverride_diamond_badVersionsAreOkayIfUnreferenced() {
        // aaa --> bbb1@1.0 --> ccc@1.0  [allowed]
        //     \            \-> bbb2@1.1
        //     \-> bbb2@1.0 --> ccc@1.5
        //     \-> bbb3@1.0 --> ccc@2.0  [allowed]
        //     \            \-> bbb4@1.1
        //     \-> bbb4@1.0 --> ccc@3.0
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb1", "1.0"))
                        .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.0"))
                        .addDep("bbb3", BzlmodTestUtil.createModuleKey("bbb3", "1.0"))
                        .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb1", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                        .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.1"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb2", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.5"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("bbb2", "1.1").buildEntry())
                .put(
                    InterimModuleBuilder.Companion.create("bbb3", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.1"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb4", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "3.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("bbb4", "1.1").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "1.5").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "3.0").buildEntry())
                .buildOrThrow()
        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "ccc",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(Version.parse("1.0"), Version.parse("2.0")), ""
                )
            )

        // aaa --> bbb1@1.0 --> ccc@1.0  [allowed]
        //     \            \-> bbb2@1.1
        //     \-> bbb2@1.1
        //     \-> bbb3@1.0 --> ccc@2.0  [allowed]
        //     \            \-> bbb4@1.1
        //     \-> bbb4@1.1
        // ccc@1.5 and ccc@3.0 are now versions of ccc that snap to allowed versions.
        // Specifically, 1.5 snaps to 2.0 (the minimum allowed version >= 1.5).
        // 3.0 has no allowed version >= 3.0, so it would normally fail if referenced.
        // In this test, bbb2@1.0 (which depends on 1.5) is replaced by bbb2@1.1.
        // And bbb4@1.0 (which depends on 3.0) is replaced by bbb4@1.1.
        val selectionResult: Selection.Result = Selection.run(depGraph, overrides)
        assertThat(selectionResult.resolvedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb1", "1.0"))
                    .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.1"))
                    .addOriginalDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.0"))
                    .addDep("bbb3", BzlmodTestUtil.createModuleKey("bbb3", "1.0"))
                    .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.1"))
                    .addOriginalDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb1", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.1"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb2", "1.1").buildEntry(),
                InterimModuleBuilder.Companion.create("bbb3", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.1"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb4", "1.1").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry()
            )
            .inOrder()

        assertThat(selectionResult.unprunedDepGraph().entrySet())
            .containsExactly(
                create("aaa", Version.EMPTY)
                    .setKey(ModuleKey.ROOT)
                    .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb1", "1.0"))
                    .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.1"))
                    .addOriginalDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.0"))
                    .addDep("bbb3", BzlmodTestUtil.createModuleKey("bbb3", "1.0"))
                    .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.1"))
                    .addOriginalDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb1", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                    .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.1"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb2", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.5"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb2", "1.1").buildEntry(),
                InterimModuleBuilder.Companion.create("bbb3", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                    .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.1"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb4", "1.0")
                    .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "3.0"))
                    .buildEntry(),
                InterimModuleBuilder.Companion.create("bbb4", "1.1").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "1.5").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry(),
                InterimModuleBuilder.Companion.create("ccc", "3.0").buildEntry()
            )
    }
}
