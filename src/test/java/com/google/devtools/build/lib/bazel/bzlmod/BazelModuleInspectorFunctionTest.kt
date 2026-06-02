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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule

/** Tests for [BazelModuleInspectorFunction].  */
@RunWith(JUnit4::class)
class BazelModuleInspectorFunctionTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDiamond_simple() {
        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
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
                        .addDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .addOriginalDep("ddd_from_bbb", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
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

        val usedModules: com.google.common.collect.ImmutableSet<ModuleKey?> =
            com.google.common.collect.ImmutableSet.of<ModuleKey?>(
                ModuleKey.ROOT,
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                BzlmodTestUtil.createModuleKey("ccc", "2.0"),
                BzlmodTestUtil.createModuleKey("ddd", "2.0")
            )

        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, AugmentedModule?> =
            BazelModuleInspectorFunction.computeAugmentedGraph(
                unprunedDepGraph, usedModules,  /* overrides= */com.google.common.collect.ImmutableMap.of<K?, V?>()
            )

        Truth.assertThat(depGraph.entries)
            .containsExactly(
                buildAugmentedModule(ModuleKey.ROOT, "aaa")
                    .addDep("bbb_from_aaa", "bbb", "1.0")
                    .addDep("ccc_from_aaa", "ccc", "2.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0")
                    .addChangedDep(
                        "ddd_from_bbb", "ddd", "2.0", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION
                    )
                    .addStillDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "2.0")
                    .addDep("ddd_from_ccc", "ddd", "2.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ddd", "2.0")
                    .addDependant("bbb", "1.0")
                    .addStillDependant("ccc", "2.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ddd", "1.0").addOriginalDependant("bbb", "1.0")
                    .buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDiamond_withFurtherRemoval() {
        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
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
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .addOriginalDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry())
                .put(
                    InterimModuleBuilder.Companion.create("ddd", "1.0")
                        .addDep("eee", BzlmodTestUtil.createModuleKey("eee", "1.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("eee", "1.0").buildEntry())
                .buildOrThrow()

        val usedModules: com.google.common.collect.ImmutableSet<ModuleKey?> =
            com.google.common.collect.ImmutableSet.of<ModuleKey?>(
                ModuleKey.ROOT,
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                BzlmodTestUtil.createModuleKey("ccc", "2.0"),
                BzlmodTestUtil.createModuleKey("ddd", "2.0")
            )

        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, AugmentedModule?> =
            BazelModuleInspectorFunction.computeAugmentedGraph(
                unprunedDepGraph, usedModules,  /*overrides*/com.google.common.collect.ImmutableMap.of<K?, V?>()
            )

        Truth.assertThat(depGraph.entries)
            .containsExactly(
                buildAugmentedModule(ModuleKey.ROOT, "aaa")
                    .addDep("bbb", "1.0")
                    .addDep("ccc", "2.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0")
                    .addChangedDep("ddd", "2.0", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                    .addStillDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "2.0")
                    .addDep("ddd", "2.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ddd", "2.0")
                    .addDependant("bbb", "1.0")
                    .addStillDependant("ccc", "2.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ddd", "1.0")
                    .addDep("eee", "1.0")
                    .addOriginalDependant("bbb", "1.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("eee", "1.0").addOriginalDependant("ddd", "1.0")
                    .buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCircularDependencyDueToSelection() {
        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
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
                        .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addOriginalDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0-pre"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0-pre")
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "1.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ddd", "1.0").buildEntry())
                .buildOrThrow()

        val usedModules: com.google.common.collect.ImmutableSet<ModuleKey?> =
            com.google.common.collect.ImmutableSet.of<ModuleKey?>(
                ModuleKey.ROOT,
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                BzlmodTestUtil.createModuleKey("ccc", "2.0")
            )

        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, AugmentedModule?> =
            BazelModuleInspectorFunction.computeAugmentedGraph(
                unprunedDepGraph, usedModules,  /*overrides*/com.google.common.collect.ImmutableMap.of<K?, V?>()
            )

        Truth.assertThat(depGraph.entries)
            .containsExactly(
                buildAugmentedModule(ModuleKey.ROOT, "aaa").addDep("bbb", "1.0").buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0")
                    .addDep("ccc", "2.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addDependant("ccc", "2.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "2.0")
                    .addChangedDep("bbb", "1.0", "1.0-pre", ResolutionReason.MINIMAL_VERSION_SELECTION)
                    .addStillDependant("bbb", "1.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0-pre")
                    .addDep("ddd", "1.0")
                    .addOriginalDependant("ccc", "2.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ddd", "1.0")
                    .addOriginalDependant("bbb", "1.0-pre").buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleVersionOverride_withRemoval() {
        // Original (non-resolved) dep graph
        // single_version_override (ccc, 2.0)
        // aaa -> bbb 1.0 -> ccc 1.0 -> ddd 1.0
        //                   ccc 2.0 -> ddd 2.0
        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
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
                        .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("ccc", "2.0")
                        .addDep("ddd", BzlmodTestUtil.createModuleKey("ddd", "2.0"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ddd", "2.0").buildEntry())
                .buildOrThrow()

        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "ccc",
                SingleVersionOverride.create(
                    Version.parse("2.0"),
                    "",
                    com.google.common.collect.ImmutableList.of<E?>(),
                    com.google.common.collect.ImmutableList.of<E?>(),
                    0
                )
            )

        val usedModules: com.google.common.collect.ImmutableSet<ModuleKey?> =
            com.google.common.collect.ImmutableSet.of<ModuleKey?>(
                ModuleKey.ROOT,
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                BzlmodTestUtil.createModuleKey("ccc", "1.0"),
                BzlmodTestUtil.createModuleKey("ccc", "2.0"),
                BzlmodTestUtil.createModuleKey("ddd", "1.0"),
                BzlmodTestUtil.createModuleKey("ddd", "2.0")
            )

        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, AugmentedModule?> =
            BazelModuleInspectorFunction.computeAugmentedGraph(
                unprunedDepGraph, usedModules, overrides
            )

        Truth.assertThat(depGraph.entries)
            .containsExactly(
                buildAugmentedModule(ModuleKey.ROOT, "aaa").addDep("bbb", "1.0").buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0")
                    .addChangedDep("ccc", "2.0", "1.0", ResolutionReason.SINGLE_VERSION_OVERRIDE)
                    .addStillDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "1.0", false)
                    .addOriginalDependant("bbb", "1.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "2.0")
                    .addDependant("bbb", "1.0")
                    .addDep("ddd", "2.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ddd", "2.0").addStillDependant("ccc", "2.0")
                    .buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonRegistryOverride_withRemoval() {
        // Original (non-resolved) dep graph
        // archive_override "file://users/user/bbb.zip"
        // aaa    -> bbb 1.0        -> ccc 1.0 (not loaded)
        //   (local) bbb 1.0-hotfix -> ccc 1.1
        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb", BzlmodTestUtil.createModuleKey("bbb", ""))
                        .addOriginalDep("bbb", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .setKey(BzlmodTestUtil.createModuleKey("bbb", ""))
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.1"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ccc", "1.1").buildEntry())
                .buildOrThrow()

        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<String?, ModuleOverride?>(
                "bbb",
                NonRegistryOverride(
                    ArchiveRepoSpecBuilder()
                        .setUrls(com.google.common.collect.ImmutableList.of<E?>("file://users/user/bbb.zip"))
                        .build()
                )
            )

        val usedModules: com.google.common.collect.ImmutableSet<ModuleKey?> =
            com.google.common.collect.ImmutableSet.of<ModuleKey?>(
                ModuleKey.ROOT,
                BzlmodTestUtil.createModuleKey("bbb", ""),
                BzlmodTestUtil.createModuleKey("ccc", "1.1")
            )

        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, AugmentedModule?> =
            BazelModuleInspectorFunction.computeAugmentedGraph(
                unprunedDepGraph, usedModules, overrides
            )

        Truth.assertThat(depGraph.entries)
            .containsExactly(
                buildAugmentedModule(ModuleKey.ROOT, "aaa")
                    .addChangedDep("bbb", "", "1.0", ResolutionReason.NON_REGISTRY_OVERRIDE)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0", false)
                    .addOriginalDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule(
                    BzlmodTestUtil.createModuleKey("bbb", ""),
                    "bbb",
                    Version.parse("1.0"),
                    true
                )
                    .addDep("ccc", "1.1")
                    .addDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "1.1").addStillDependant("bbb", "")
                    .buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleVersionOverride_simpleSnapToHigher() {
        // Initial dep graph
        // aaa  -> (bbb1)bbb 1.0 -> ccc 1.0
        //     \-> (bbb2)bbb 2.0 -> ccc 1.5
        //     \-> ccc 2.0
        // multiple_version_override ccc: [1.5, 2.0]
        // multiple_version_override bbb: [1.0, 2.0]
        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb", "1.0"))
                        .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb", "2.0"))
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "2.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "1.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.5"))
                        .addOriginalDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.0"))
                        .buildEntry()
                )
                .put(
                    InterimModuleBuilder.Companion.create("bbb", "2.0")
                        .addDep("ccc", BzlmodTestUtil.createModuleKey("ccc", "1.5"))
                        .buildEntry()
                )
                .put(InterimModuleBuilder.Companion.create("ccc", "1.0").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "1.5").buildEntry())
                .put(InterimModuleBuilder.Companion.create("ccc", "2.0").buildEntry())
                .buildOrThrow()

        val overrides: com.google.common.collect.ImmutableMap<String?, ModuleOverride?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "bbb",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(Version.parse("1.0"), Version.parse("2.0")), ""
                ),
                "ccc",
                MultipleVersionOverride.create(
                    com.google.common.collect.ImmutableList.of<E?>(Version.parse("1.5"), Version.parse("2.0")), ""
                )
            )

        val usedModules: com.google.common.collect.ImmutableSet<ModuleKey?> =
            com.google.common.collect.ImmutableSet.of<ModuleKey?>(
                ModuleKey.ROOT,
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                BzlmodTestUtil.createModuleKey("bbb", "2.0"),
                BzlmodTestUtil.createModuleKey("ccc", "1.5"),
                BzlmodTestUtil.createModuleKey("ccc", "2.0")
            )
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, AugmentedModule?> =
            BazelModuleInspectorFunction.computeAugmentedGraph(
                unprunedDepGraph, usedModules, overrides
            )

        Truth.assertThat(depGraph.entries)
            .containsExactly(
                buildAugmentedModule(ModuleKey.ROOT, "aaa")
                    .addDep("bbb1", "bbb", "1.0")
                    .addDep("bbb2", "bbb", "2.0")
                    .addDep("ccc", "2.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addChangedDep("ccc", "1.5", "1.0", ResolutionReason.MULTIPLE_VERSION_OVERRIDE)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "2.0")
                    .addStillDependant(ModuleKey.ROOT)
                    .addDep("ccc", "1.5")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "1.0").addOriginalDependant("bbb", "1.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "1.5")
                    .addDependant("bbb", "1.0")
                    .addStillDependant("bbb", "2.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "2.0").addStillDependant(ModuleKey.ROOT)
                    .buildEntry()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleVersionOverride_badDepsUnreferenced() {
        // Initial dep graph
        // aaa --> bbb1@1.0 --> ccc@1.0  [allowed]
        //     \            \-> bbb2@1.1
        //     \-> bbb2@1.0 --> ccc@1.5
        //     \-> bbb3@1.0 --> ccc@2.0  [allowed]
        //     \            \-> bbb4@1.1
        //     \-> bbb4@1.0 --> ccc@3.0
        //
        // Resolved dep graph
        // aaa --> bbb1@1.0 --> ccc@1.0  [allowed]
        //     \            \-> bbb2@1.1
        //     \-> bbb2@1.1
        //     \-> bbb3@1.0 --> ccc@2.0  [allowed]
        //     \            \-> bbb4@1.1
        //     \-> bbb4@1.1
        // ccc@1.5 and ccc@3.0, the versions violating the allowlist, are gone.
        val unprunedDepGraph: com.google.common.collect.ImmutableMap<ModuleKey?, InterimModule?> =
            com.google.common.collect.ImmutableMap.builder<ModuleKey?, InterimModule?>()
                .put(
                    create("aaa", Version.EMPTY)
                        .setKey(ModuleKey.ROOT)
                        .addDep("bbb1", BzlmodTestUtil.createModuleKey("bbb1", "1.0"))
                        .addDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.1"))
                        .addOriginalDep("bbb2", BzlmodTestUtil.createModuleKey("bbb2", "1.0"))
                        .addDep("bbb3", BzlmodTestUtil.createModuleKey("bbb3", "1.0"))
                        .addDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.1"))
                        .addOriginalDep("bbb4", BzlmodTestUtil.createModuleKey("bbb4", "1.0"))
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

        val usedModules: com.google.common.collect.ImmutableSet<ModuleKey?> =
            com.google.common.collect.ImmutableSet.< ModuleKey > of < ModuleKey ? > (
                    ModuleKey.ROOT,
        BzlmodTestUtil.createModuleKey("bbb1", "1.0"),
        BzlmodTestUtil.createModuleKey("bbb2", "1.1"),
        BzlmodTestUtil.createModuleKey("bbb3", "1.0"),
        BzlmodTestUtil.createModuleKey("bbb4", "1.1"),
        BzlmodTestUtil.createModuleKey("ccc", "1.0"),
        BzlmodTestUtil.createModuleKey("ccc", "2.0"))
        val depGraph: com.google.common.collect.ImmutableMap<ModuleKey?, AugmentedModule?> =
            BazelModuleInspectorFunction.computeAugmentedGraph(
                unprunedDepGraph, usedModules, overrides
            )

        Truth.assertThat(depGraph.entries)
            .containsExactly(
                buildAugmentedModule(ModuleKey.ROOT, "aaa")
                    .addDep("bbb1", "1.0")
                    .addChangedDep("bbb2", "1.1", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                    .addDep("bbb3", "1.0")
                    .addChangedDep("bbb4", "1.1", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb1", "1.0")
                    .addDep("ccc", "1.0")
                    .addDep("bbb2", "1.1")
                    .addStillDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb2", "1.0")
                    .addDep("ccc", "1.5")
                    .addOriginalDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb2", "1.1")
                    .addDependant(ModuleKey.ROOT)
                    .addStillDependant("bbb1", "1.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb3", "1.0")
                    .addDep("ccc", "2.0")
                    .addDep("bbb4", "1.1")
                    .addStillDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb4", "1.0")
                    .addDep("ccc", "3.0")
                    .addOriginalDependant(ModuleKey.ROOT)
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb4", "1.1")
                    .addDependant(ModuleKey.ROOT)
                    .addStillDependant("bbb3", "1.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "1.0").addStillDependant("bbb1", "1.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "1.5").addOriginalDependant("bbb2", "1.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "2.0").addStillDependant("bbb3", "1.0")
                    .buildEntry(),
                AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "3.0").addOriginalDependant("bbb4", "1.0")
                    .buildEntry()
            )
    }
}
