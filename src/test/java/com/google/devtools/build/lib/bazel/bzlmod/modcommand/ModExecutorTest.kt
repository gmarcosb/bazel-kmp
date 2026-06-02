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
package com.google.devtools.build.lib.bazel.bzlmod.modcommand

import com.google.common.collect.*
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule
import net.starlark.java.syntax.Location
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.*

/** Tests for [ModExecutor].  */
@RunWith(JUnit4::class)
class ModExecutorTest {
    // TODO(andreisolo): Add a Json output test
    // TODO(andreisolo): Add a PATH query test
    private val outputStream: OutputStream = ByteArrayOutputStream()

    // Tests for the ModExecutor::expandAndPrune core function.
    //
    // (* In the ASCII graph hints "__>" or "-->" mean a direct edge, while "..>" means an indirect
    // edge. "aaa ..." means module "aaa" is unexpanded.)
    @Test
    @Throws(ParseException::class)
    fun testExpandFromTargetsFirst() {
        // aaa -> bbb -> ccc -> ddd
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "aaa",
                        Version.parse("1.0"),
                        true
                    )
                        .addDep("bbb", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addDep("ccc", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "1.0")
                        .addStillDependant("bbb", "1.0")
                        .addDep("ddd", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("ddd", "1.0").addStillDependant("ccc", "1.0")
                        .buildEntry()
                )
                .buildOrThrow()

        val options: ModOptions = ModOptions.getDefaultOptions()
        val executor = ModExecutor(depGraph, options, outputStream)

        // RESULT:
        // <root> ...> ccc -> ddd
        //       \___> bbb -> ccc ...
        Truth.assertThat(
            executor.expandAndPrune(
                ImmutableSet.of<ModuleKey>(
                    ModuleKey.ROOT,
                    BzlmodTestUtil.createModuleKey("ccc", "1.0")
                )
            )
        )
            .containsExactly(
                ModuleKey.ROOT,
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("bbb", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .addChild(BzlmodTestUtil.createModuleKey("ccc", "1.0"), IsExpanded.TRUE, IsIndirect.TRUE)
                    .build(),
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("ccc", "1.0"), IsExpanded.FALSE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("ccc", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("ddd", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("ddd", "1.0"),
                ResultNode.builder().build()
            )
            .inOrder()
    }

    @Test
    @Throws(ParseException::class)
    fun testPathsDepth1_containsAllTargetsWithNestedIndirect() {
        // <root> -> bbb -> ccc -> ddd -> eee -> fff -> ggg -> hhh
        //                          ^     /
        //                           \___/
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "aaa",
                        Version.parse("1.0"),
                        true
                    )
                        .addDep("bbb", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addDep("ccc", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "1.0")
                        .addStillDependant("bbb", "1.0")
                        .addDep("ddd", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("ddd", "1.0")
                        .addStillDependant("ccc", "1.0")
                        .addStillDependant("eee", "1.0")
                        .addDep("eee", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("eee", "1.0")
                        .addStillDependant("ddd", "1.0")
                        .addDep("fff", "1.0")
                        .addDep("ddd", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("fff", "1.0")
                        .addStillDependant("eee", "1.0")
                        .addDep("ggg", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("ggg", "1.0")
                        .addStillDependant("fff", "1.0")
                        .addDep("hhh", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("hhh", "1.0").addStillDependant("ggg", "1.0")
                        .buildEntry()
                )
                .buildOrThrow()

        val options: ModOptions = ModOptions.getDefaultOptions()
        options.cycles = true
        options.depth = 1
        val executor = ModExecutor(depGraph, options, outputStream)
        val targets: ImmutableSet<ModuleKey?> =
            ImmutableSet.of<ModuleKey?>(
                BzlmodTestUtil.createModuleKey("eee", "1.0"),
                BzlmodTestUtil.createModuleKey("hhh", "1.0")
            )

        // RESULT:
        // <root> --> bbb ..> ddd --> eee --> ddd (cycle)
        //                               \..> ggg --> hhh
        Truth.assertThat(executor.expandPathsToTargets(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT), targets, false))
            .containsExactly(
                ModuleKey.ROOT,
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("bbb", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("ddd", "1.0"), IsExpanded.TRUE, IsIndirect.TRUE)
                    .build(),
                BzlmodTestUtil.createModuleKey("ddd", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("eee", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("eee", "1.0"),
                ResultNode.builder()
                    .setTarget(true)
                    .addChild(BzlmodTestUtil.createModuleKey("ggg", "1.0"), IsExpanded.TRUE, IsIndirect.TRUE)
                    .build(),
                BzlmodTestUtil.createModuleKey("ggg", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("hhh", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("hhh", "1.0"),
                ResultNode.builder().setTarget(true).build()
            )
            .inOrder()
    }

    @Test
    @Throws(ParseException::class)
    fun testPathsDepth1_targetParentIsDirectAndIndirectChild() {
        // <root> --> bbb --> ccc
        //             \       |________
        //              \      V       |
        //               \__> ddd --> eee
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "aaa",
                        Version.parse("1.0"),
                        true
                    )
                        .addDep("bbb", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("bbb", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addDep("ccc", "1.0")
                        .addDep("ddd", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("ccc", "1.0")
                        .addStillDependant("bbb", "1.0")
                        .addDep("ddd", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("ddd", "1.0")
                        .addStillDependant("bbb", "1.0")
                        .addStillDependant("ccc", "1.0")
                        .addStillDependant("eee", "1.0")
                        .addDep("eee", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("eee", "1.0")
                        .addStillDependant("ddd", "1.0")
                        .addStillDependant("eee", "1.0")
                        .addDep("ddd", "1.0")
                        .buildEntry()
                )
                .buildOrThrow()

        val options: ModOptions = ModOptions.getDefaultOptions()
        options.cycles = true
        options.depth = 1
        val executor = ModExecutor(depGraph, options, outputStream)
        val targets: ImmutableSet<ModuleKey?> =
            ImmutableSet.of<ModuleKey?>(BzlmodTestUtil.createModuleKey("eee", "1.0"))

        // RESULT:
        // <root> --> bbb --- ddd --> eee --> ddd (c)
        //             \
        //              \..> ddd ...
        Truth.assertThat(executor.expandPathsToTargets(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT), targets, false))
            .containsExactly(
                ModuleKey.ROOT,
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("bbb", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("bbb", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("ddd", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("ddd", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("eee", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("eee", "1.0"),
                ResultNode.builder().setTarget(true).build()
            )
            .inOrder()
    }

    // TODO(andreisolo): Add more eventual edge-case tests for the #expandAndPrune core method
    /**/ Tests for the ModExecutor OutputFormatters */ //
    @Test
    @Throws(ParseException::class)
    fun testResolutionExplanation_mostCases() {
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "A",
                        Version.parse("1.0"),
                        true
                    )
                        .addDep("B", "1.0")
                        .addDep("C", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("B", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addChangedDep("C", "1.0", "0.1", ResolutionReason.MINIMAL_VERSION_SELECTION)
                        .addChangedDep("E", "", "1.0", ResolutionReason.NON_REGISTRY_OVERRIDE)
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("C", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addDependant("B", "1.0")
                        .addChangedDep("D", "1.5", "1.0", ResolutionReason.SINGLE_VERSION_OVERRIDE)
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("C", "0.1").addOriginalDependant("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("D", "1.0").addOriginalDependant("C", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("D", "1.5").addDependant("C", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("E", "1.0").addOriginalDependant("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("E", "").addDependant("B", "1.0").buildEntry()
                )
                .buildOrThrow()

        val options: ModOptions = ModOptions.getDefaultOptions()
        options.verbose = true
        options.includeUnused = true

        val formatter = OutputFormatters.getFormatter(ModOptions.OutputFormat.TEXT)
        Truth.assertThat(formatter.getExtraResolutionExplanation(ModuleKey.ROOT, null, depGraph, options))
            .isNull()
        Truth.assertThat(
            formatter.getExtraResolutionExplanation(
                BzlmodTestUtil.createModuleKey("B", "1.0"), ModuleKey.ROOT, depGraph, options
            )
        )
            .isNull()

        Truth.assertThat(
            formatter.getExtraResolutionExplanation(
                BzlmodTestUtil.createModuleKey("C", "1.0"),
                BzlmodTestUtil.createModuleKey("B", "1.0"),
                depGraph,
                options
            )
        )
            .isEqualTo(
                Explanation.create(
                    Version.parse("0.1"),
                    ResolutionReason.MINIMAL_VERSION_SELECTION,
                    ImmutableSet.of<E?>(ModuleKey.ROOT)
                )
            )

        Truth.assertThat(
            formatter.getExtraResolutionExplanation(
                BzlmodTestUtil.createModuleKey("C", "0.1"),
                BzlmodTestUtil.createModuleKey("B", "1.0"),
                depGraph,
                options
            )
        )
            .isEqualTo(
                Explanation.create(
                    Version.parse("1.0"),
                    ResolutionReason.MINIMAL_VERSION_SELECTION,
                    ImmutableSet.of<E?>(ModuleKey.ROOT)
                )
            )

        Truth.assertThat(
            formatter.getExtraResolutionExplanation(
                BzlmodTestUtil.createModuleKey("D", "1.0"),
                BzlmodTestUtil.createModuleKey("C", "1.0"),
                depGraph,
                options
            )
        )
            .isEqualTo(
                Explanation.create(
                    Version.parse("1.5"), ResolutionReason.SINGLE_VERSION_OVERRIDE, null
                )
            )

        Truth.assertThat(
            formatter.getExtraResolutionExplanation(
                BzlmodTestUtil.createModuleKey("D", "1.5"),
                BzlmodTestUtil.createModuleKey("C", "1.0"),
                depGraph,
                options
            )
        )
            .isEqualTo(
                Explanation.create(
                    Version.parse("1.0"), ResolutionReason.SINGLE_VERSION_OVERRIDE, null
                )
            )

        Truth.assertThat(
            formatter.getExtraResolutionExplanation(
                BzlmodTestUtil.createModuleKey("E", "1.0"),
                BzlmodTestUtil.createModuleKey("B", "1.0"),
                depGraph,
                options
            )
        )
            .isEqualTo(Explanation.create(Version.EMPTY, ResolutionReason.NON_REGISTRY_OVERRIDE, null))

        Truth.assertThat(
            formatter.getExtraResolutionExplanation(
                BzlmodTestUtil.createModuleKey("E", ""), BzlmodTestUtil.createModuleKey("B", "1.0"), depGraph, options
            )
        )
            .isEqualTo(
                Explanation.create(Version.parse("1.0"), ResolutionReason.NON_REGISTRY_OVERRIDE, null)
            )
    }

    @Test
    @Throws(ParseException::class, IOException::class)
    fun testTextAndGraphOutput_indirectAndNestedTargetPathsWithUnused() {
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "A",
                        Version.parse("1.0"),
                        true
                    )
                        .addDep("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("B", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addChangedDep("C", "1.0", "0.1", ResolutionReason.SINGLE_VERSION_OVERRIDE)
                        .addChangedDep("Y", "2.0", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("C", "0.1").addOriginalDependant("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("C", "1.0")
                        .addDependant("B", "1.0")
                        .addDep("D", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("D", "1.0")
                        .addStillDependant("C", "1.0")
                        .addStillDependant("E", "1.0")
                        .addDep("E", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("E", "1.0")
                        .addStillDependant("D", "1.0")
                        .addDep("F", "1.0")
                        .addDep("D", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("F", "1.0")
                        .addStillDependant("E", "1.0")
                        .addDep("G", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("G", "1.0")
                        .addStillDependant("F", "1.0")
                        .addDep("H", "1.0")
                        .addDep("Y", "2.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("H", "1.0").addStillDependant("G", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("Y", "1.0").addOriginalDependant("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("Y", "2.0")
                        .addDependant("B", "1.0")
                        .addStillDependant("G", "1.0")
                        .buildEntry()
                )
                .buildOrThrow()

        val result: ImmutableMap<ModuleKey?, ResultNode?> =
            ImmutableMap.of<K?, V?>(
                ModuleKey.ROOT,
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("B", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("B", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("Y", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .addChild(BzlmodTestUtil.createModuleKey("Y", "2.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .addChild(BzlmodTestUtil.createModuleKey("C", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .addChild(BzlmodTestUtil.createModuleKey("C", "0.1"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("C", "0.1"),
                ResultNode.builder().setTarget(true).build(),
                BzlmodTestUtil.createModuleKey("C", "1.0"),
                ResultNode.builder()
                    .setTarget(true)
                    .addChild(BzlmodTestUtil.createModuleKey("D", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("D", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("E", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("E", "1.0"),
                ResultNode.builder()
                    .setTarget(true)
                    .addChild(BzlmodTestUtil.createModuleKey("G", "1.0"), IsExpanded.TRUE, IsIndirect.TRUE)
                    .build(),
                BzlmodTestUtil.createModuleKey("G", "1.0"),
                ResultNode.builder()
                    .addChild(BzlmodTestUtil.createModuleKey("H", "1.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .addChild(BzlmodTestUtil.createModuleKey("Y", "2.0"), IsExpanded.TRUE, IsIndirect.FALSE)
                    .build(),
                BzlmodTestUtil.createModuleKey("H", "1.0"),
                ResultNode.builder().setTarget(true).build(),
                BzlmodTestUtil.createModuleKey("Y", "1.0"),
                ResultNode.builder().setTarget(true).build(),
                BzlmodTestUtil.createModuleKey("Y", "2.0"),
                ResultNode.builder().setTarget(true).build()
            )

        val options: ModOptions = ModOptions.getDefaultOptions()
        options.cycles = true
        options.includeUnused = true
        options.verbose = true
        options.depth = 1
        options.outputFormat = ModOptions.OutputFormat.TEXT

        val file = File.createTempFile("output_text", "txt")
        file.deleteOnExit()

        val targets: ImmutableSet<ModuleKey?> =
            ImmutableSet.of<ModuleKey?>(
                BzlmodTestUtil.createModuleKey("C", "0.1"),
                BzlmodTestUtil.createModuleKey("C", "1.0"),
                BzlmodTestUtil.createModuleKey("Y", "1.0"),
                BzlmodTestUtil.createModuleKey("Y", "2.0"),
                BzlmodTestUtil.createModuleKey("E", "1.0"),
                BzlmodTestUtil.createModuleKey("H", "1.0")
            )

        FileOutputStream(file).use { outputStream ->
            val executor = ModExecutor(depGraph, options, outputStream)
            // Double check for human error
            Truth.assertThat(executor.expandPathsToTargets(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT), targets, false))
                .isEqualTo(result)
            executor.allPaths(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT), targets)
        }
        val textOutput = Files.readAllLines(file.toPath())

        Truth.assertThat(textOutput)
            .containsExactly(
                "<root> (A@1.0)",
                "└───B@1.0 ",
                "    ├───C@0.1 # (to 1.0, cause single_version_override)",
                "    ├───C@1.0 # (was 0.1, cause single_version_override)",
                "    │   └───D@1.0 ",
                "    │       └───E@1.0 # ",
                "    │           └╌╌╌G@1.0 ",
                "    │               ├───H@1.0 # ",
                "    │               └───Y@2.0 # ",
                "    ├───Y@1.0 # (to 2.0, cause G@1.0)",
                "    └───Y@2.0 # (was 1.0, cause G@1.0)",
                ""
            )
            .inOrder()

        options.outputFormat = ModOptions.OutputFormat.GRAPH
        val fileGraph = File.createTempFile("output_graph", "txt")
        fileGraph.deleteOnExit()
        FileOutputStream(fileGraph).use { outputStream ->
            val executor = ModExecutor(depGraph, options, outputStream)
            executor.allPaths(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT), targets)
        }
        val graphOutput = Files.readAllLines(fileGraph.toPath())

        Truth.assertThat(graphOutput)
            .containsExactly(
                "digraph mygraph {",
                "  node [ shape=box ]",
                "  edge [ fontsize=8 ]",
                "  \"<root>\" [ label=\"<root> (A@1.0)\" ]",
                "  \"<root>\" -> \"B@1.0\" [  ]",
                "  \"B@1.0\" -> \"C@0.1\" [ label=SVO ]",
                "  \"B@1.0\" -> \"C@1.0\" [ label=SVO ]",
                "  \"B@1.0\" -> \"Y@1.0\" [ label=MVS ]",
                "  \"B@1.0\" -> \"Y@2.0\" [ label=MVS ]",
                "  \"C@0.1\" [ shape=diamond style=dotted ]",
                "  \"C@1.0\" [ shape=diamond style=solid ]",
                "  \"C@1.0\" -> \"D@1.0\" [  ]",
                "  \"Y@1.0\" [ shape=diamond style=dotted ]",
                "  \"Y@2.0\" [ shape=diamond style=solid ]",
                "  \"D@1.0\" -> \"E@1.0\" [  ]",
                "  \"E@1.0\" [ shape=diamond style=solid ]",
                "  \"E@1.0\" -> \"G@1.0\" [ style=dashed ]",
                "  \"G@1.0\" -> \"H@1.0\" [  ]",
                "  \"G@1.0\" -> \"Y@2.0\" [  ]",
                "  \"H@1.0\" [ shape=diamond style=solid ]",
                "}"
            )
            .inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun testExtensionsInfoTextAndGraph() {
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "A",
                        Version.parse("1.0"),
                        true
                    )
                        .addDep("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("B", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addChangedDep("C", "1.0", "0.1", ResolutionReason.SINGLE_VERSION_OVERRIDE)
                        .addChangedDep("Y", "2.0", "1.0", ResolutionReason.MINIMAL_VERSION_SELECTION)
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("C", "0.1").addOriginalDependant("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("C", "1.0")
                        .addDependant("B", "1.0")
                        .addDep("D", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("D", "1.0")
                        .addStillDependant("C", "1.0")
                        .addStillDependant("E", "1.0")
                        .addDep("E", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("E", "1.0")
                        .addStillDependant("D", "1.0")
                        .addDep("F", "1.0")
                        .addDep("D", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("F", "1.0")
                        .addStillDependant("E", "1.0")
                        .addDep("G", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("G", "1.0")
                        .addStillDependant("F", "1.0")
                        .addDep("H", "1.0")
                        .addDep("Y", "2.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("H", "1.0").addStillDependant("G", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("Y", "1.0").addOriginalDependant("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("Y", "2.0")
                        .addDependant("B", "1.0")
                        .addStillDependant("G", "1.0")
                        .buildEntry()
                )
                .buildOrThrow()

        val mavenId: ModuleExtensionId = createExtensionId("extensions", "maven")
        val gradleId: ModuleExtensionId = createExtensionId("extensions", "gradle")
        val extensionUsages: ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?> =
            ImmutableTable.Builder<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>()
                .put(
                    mavenId,
                    BzlmodTestUtil.createModuleKey("C", "1.0"),
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//extensions:extensions.bzl")
                        .setExtensionName("maven")
                        .setRepoOverrides(ImmutableMap.of<K?, V?>())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(Location.fromFileLineColumn("C@1.0/MODULE.bazel", 2, 23))
                                .setImports(ImmutableBiMap.< K, V > of<K?, V?>("repo1", "repo1", "repo3", "repo3"))
                                .setDevDependency(false)
                                .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build()
                        )
                        .build()
                )
                .put(
                    mavenId,
                    BzlmodTestUtil.createModuleKey("D", "1.0"),
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//extensions:extensions.bzl")
                        .setExtensionName("maven")
                        .setRepoOverrides(ImmutableMap.of<K?, V?>())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(Location.fromFileLineColumn("D@1.0/MODULE.bazel", 1, 10))
                                .setImports(ImmutableBiMap.< K, V > of<K?, V?>("repo1", "repo1", "repo2", "repo2"))
                                .setDevDependency(false)
                                .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build()
                        )
                        .build()
                )
                .put(
                    gradleId,
                    BzlmodTestUtil.createModuleKey("Y", "2.0"),
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//extensions:extensions.bzl")
                        .setExtensionName("gradle")
                        .setRepoOverrides(ImmutableMap.of<K?, V?>())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(Location.fromFileLineColumn("Y@2.0/MODULE.bazel", 2, 13))
                                .setImports(ImmutableBiMap.< K, V > of<K?, V?>("repo2", "repo2"))
                                .setDevDependency(false)
                                .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build()
                        )
                        .build()
                )
                .put(
                    mavenId,
                    BzlmodTestUtil.createModuleKey("Y", "2.0"),
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//extensions:extensions.bzl")
                        .setExtensionName("maven")
                        .setRepoOverrides(ImmutableMap.of<K?, V?>())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(Location.fromFileLineColumn("Y@2.0/MODULE.bazel", 13, 10))
                                .setImports(ImmutableBiMap.< K, V > of<K?, V?>("myrepo", "repo5"))
                                .setDevDependency(false)
                                .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build()
                        )
                        .addTag(BzlmodTestUtil.buildTag("dep").addAttr("coord", "junit").build())
                        .addTag(BzlmodTestUtil.buildTag("dep").addAttr("coord", "guava").build())
                        .addTag(
                            BzlmodTestUtil.buildTag("pom")
                                .addAttr(
                                    "pom_xmls",
                                    StarlarkList.immutableOf<String?>("//:pom.xml", "@bar//:pom.xml")
                                )
                                .build()
                        )
                        .build()
                )
                .buildOrThrow()

        val file = File.createTempFile("output_text", "txt")
        file.deleteOnExit()

        // Contains the already-filtered map of target extensions along with their full list of repos
        val extensionRepos: ImmutableSetMultimap<ModuleExtensionId?, String?> =
            ImmutableSetMultimap.Builder<ModuleExtensionId?, String?>()
                .putAll(mavenId, ImmutableSet.of<String?>("repo6", "repo1", "repo2", "repo3", "repo4", "repo5"))
                .putAll(gradleId, ImmutableSet.of<String?>("repo1", "repo2"))
                .build()

        val options: ModOptions = ModOptions.getDefaultOptions()
        options.outputFormat = ModOptions.OutputFormat.TEXT
        options.extensionInfo = ExtensionShow.ALL

        FileOutputStream(file).use { outputStream ->
            val executor =
                ModExecutor(
                    depGraph,
                    extensionUsages,
                    extensionRepos,
                    Optional.empty<MaybeCompleteSet<ModuleExtensionId?>>(),
                    options,
                    outputStream
                )
            executor.graph(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT))
        }
        val textOutput = Files.readAllLines(file.toPath())

        Truth.assertThat(textOutput)
            .containsExactly(
                "<root> (A@1.0)",
                "└───B@1.0 ",
                "    ├───C@1.0 ",
                "    │   ├───$@@//extensions:extensions%maven ",
                "    │   │   ├───repo1",
                "    │   │   ├───repo3",
                "    │   │   ├╌╌╌repo4",
                "    │   │   └╌╌╌repo6",
                "    │   └───D@1.0 ",
                "    │       ├───$@@//extensions:extensions%maven ... ",
                "    │       │   ├───repo1",
                "    │       │   └───repo2",
                "    │       └───E@1.0 ",
                "    │           └───F@1.0 ",
                "    │               └───G@1.0 ",
                "    │                   ├───Y@2.0 (*) ",
                "    │                   └───H@1.0 ",
                "    └───Y@2.0 ",
                "        ├───$@@//extensions:extensions%gradle ",
                "        │   ├───repo2",
                "        │   └╌╌╌repo1",
                "        └───$@@//extensions:extensions%maven ... ",
                "            └───repo5",
                ""
            )
            .inOrder()

        options.outputFormat = ModOptions.OutputFormat.GRAPH
        val fileGraph = File.createTempFile("output_graph", "txt")
        fileGraph.deleteOnExit()

        FileOutputStream(fileGraph).use { outputStream ->
            val executor =
                ModExecutor(
                    depGraph,
                    extensionUsages,
                    extensionRepos,
                    Optional.empty<MaybeCompleteSet<ModuleExtensionId?>>(),
                    options,
                    outputStream
                )
            executor.graph(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT))
        }
        val graphOutput = Files.readAllLines(fileGraph.toPath())

        Truth.assertThat(graphOutput)
            .containsExactly(
                "digraph mygraph {",
                "  node [ shape=box ]",
                "  edge [ fontsize=8 ]",
                "  \"<root>\" [ label=\"<root> (A@1.0)\" ]",
                "  \"<root>\" -> \"B@1.0\" [  ]",
                "  \"B@1.0\" -> \"C@1.0\" [  ]",
                "  \"B@1.0\" -> \"Y@2.0\" [  ]",
                "  subgraph \"cluster_@@//extensions:extensions%maven\" {",
                "    label=\"@@//extensions:extensions%maven\"",
                "    \"@@//extensions:extensions%maven%repo1\" [ label=\"repo1\" ]",
                "    \"@@//extensions:extensions%maven%repo2\" [ label=\"repo2\" ]",
                "    \"@@//extensions:extensions%maven%repo3\" [ label=\"repo3\" ]",
                "    \"@@//extensions:extensions%maven%repo5\" [ label=\"repo5\" ]",
                "    \"@@//extensions:extensions%maven%repo4\" [ label=\"repo4\" style=dotted ]",
                "    \"@@//extensions:extensions%maven%repo6\" [ label=\"repo6\" style=dotted ]",
                "  }",
                "  \"C@1.0\" -> \"@@//extensions:extensions%maven%repo1\"",
                "  \"C@1.0\" -> \"@@//extensions:extensions%maven%repo3\"",
                "  \"C@1.0\" -> \"D@1.0\" [  ]",
                "  subgraph \"cluster_@@//extensions:extensions%gradle\" {",
                "    label=\"@@//extensions:extensions%gradle\"",
                "    \"@@//extensions:extensions%gradle%repo2\" [ label=\"repo2\" ]",
                "    \"@@//extensions:extensions%gradle%repo1\" [ label=\"repo1\" style=dotted ]",
                "  }",
                "  \"Y@2.0\" -> \"@@//extensions:extensions%gradle%repo2\"",
                "  \"Y@2.0\" -> \"@@//extensions:extensions%maven%repo5\"",
                "  \"D@1.0\" -> \"@@//extensions:extensions%maven%repo1\"",
                "  \"D@1.0\" -> \"@@//extensions:extensions%maven%repo2\"",
                "  \"D@1.0\" -> \"E@1.0\" [  ]",
                "  \"E@1.0\" -> \"F@1.0\" [  ]",
                "  \"F@1.0\" -> \"G@1.0\" [  ]",
                "  \"G@1.0\" -> \"H@1.0\" [  ]",
                "  \"G@1.0\" -> \"Y@2.0\" [  ]",
                "}"
            )
            .inOrder()

        options.outputFormat = ModOptions.OutputFormat.TEXT
        options.depth = 1
        val fileText2 = File.createTempFile("output_text2", "txt")
        fileText2.deleteOnExit()
        FileOutputStream(fileText2).use { outputStream ->
            val executor =
                ModExecutor(
                    depGraph,
                    extensionUsages,
                    extensionRepos,
                    Optional.of<T?>(MaybeCompleteSet.copyOf(ImmutableSet.of<E?>(mavenId))),
                    options,
                    outputStream
                )
            executor.allPaths(
                ImmutableSet.of<ModuleKey>(ModuleKey.ROOT),
                ImmutableSet.of<ModuleKey>(BzlmodTestUtil.createModuleKey("Y", "2.0"))
            )
        }
        val textOutput2 = Files.readAllLines(fileText2.toPath())

        Truth.assertThat(textOutput2)
            .containsExactly(
                "<root> (A@1.0)",
                "└───B@1.0 ",
                "    ├───C@1.0 # ",
                "    │   ├───$@@//extensions:extensions%maven ",
                "    │   │   ├───repo1",
                "    │   │   ├───repo3",
                "    │   │   ├╌╌╌repo4",
                "    │   │   └╌╌╌repo6",
                "    │   └───D@1.0 # ",
                "    │       ├───$@@//extensions:extensions%maven ... ",
                "    │       │   ├───repo1",
                "    │       │   └───repo2",
                "    │       └╌╌╌G@1.0 ",
                "    │           └───Y@2.0 # ",
                "    │               └───$@@//extensions:extensions%maven ... ",
                "    │                   └───repo5",
                "    └───Y@2.0 # ",
                "        └───$@@//extensions:extensions%maven ... ",
                "            └───repo5",
                ""
            )
            .inOrder()
    }

    @Throws(LabelSyntaxException::class)
    private fun createExtensionId(targetName: String?, extensionName: String?): ModuleExtensionId {
        return ModuleExtensionId.create(
            Label.create(PackageIdentifier.createInMainRepo(targetName), targetName),
            extensionName,
            Optional.empty<T?>()
        )
    }

    @Throws(LabelSyntaxException::class)
    private fun createExtensionId(
        moduleName: String?, version: String?, path: String?, extensionName: String?
    ): ModuleExtensionId {
        return ModuleExtensionId.create(
            Label.parseCanonical("@@" + moduleName + "+" + version + "//" + path + ":" + path),
            extensionName,
            Optional.empty<T?>()
        )
    }

    @Test
    @Throws(ParseException::class, IOException::class)
    fun testModCommandPath_complexGraphFiltersCorrectly() {
        // <root> -> A -> B -> C -> D
        //   |       |         ^
        //   |       `-> E -> F/
        //   |
        //   `-> G -> H
        //   |
        //   `-> I -> D
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "main",
                        Version.parse("1.0"),
                        true
                    )
                        .addDep("A", "1.0")
                        .addDep("G", "1.0")
                        .addDep("I", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("A", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addDep("B", "1.0")
                        .addDep("E", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("B", "1.0")
                        .addStillDependant("A", "1.0")
                        .addDep("C", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("C", "1.0")
                        .addStillDependant("B", "1.0")
                        .addStillDependant("F", "1.0")
                        .addDep("D", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("D", "1.0")
                        .addStillDependant("C", "1.0")
                        .addStillDependant("I", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("E", "1.0")
                        .addStillDependant("A", "1.0")
                        .addDep("F", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("F", "1.0")
                        .addStillDependant("E", "1.0")
                        .addDep("C", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("G", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addDep("H", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("H", "1.0").addStillDependant("G", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("I", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addDep("D", "1.0")
                        .buildEntry()
                )
                .buildOrThrow()

        val options: ModOptions = ModOptions.getDefaultOptions()
        options.outputFormat = ModOptions.OutputFormat.TEXT

        val file = File.createTempFile("output_text", "txt")
        file.deleteOnExit()

        FileOutputStream(file).use { outputStream ->
            val executor = ModExecutor(depGraph, options, outputStream)
            // Test `executor.allPaths`, it should output all "interesting" paths to the target modules.
            executor.allPaths(
                ImmutableSet.of<ModuleKey>(ModuleKey.ROOT),
                ImmutableSet.of<ModuleKey>(BzlmodTestUtil.createModuleKey("D", "1.0"))
            )
        }
        val textOutput = Files.readAllLines(file.toPath())

        Truth.assertThat(textOutput)
            .containsExactly(
                "<root> (main@1.0)",
                "├───A@1.0 ",
                "│   └───B@1.0 ",
                "│       └───C@1.0 ",
                "│           └───D@1.0 # ",
                "└───I@1.0 ",
                "    └───D@1.0 # ",
                ""
            )
            .inOrder()

        // Also test `executor.path`, it should output a single path to the target module and it should
        // be the shortest one
        val file2 = File.createTempFile("output_text", "txt")
        file2.deleteOnExit()
        FileOutputStream(file2).use { outputStream2 ->
            val executor2 = ModExecutor(depGraph, options, outputStream2)
            executor2.path(
                ImmutableSet.of<ModuleKey>(ModuleKey.ROOT),
                ImmutableSet.of<ModuleKey>(BzlmodTestUtil.createModuleKey("D", "1.0"))
            )
        }
        val textOutput2 = Files.readAllLines(file2.toPath())
        Truth.assertThat(textOutput2)
            .containsExactly("<root> (main@1.0)", "└───I@1.0 ", "    └───D@1.0 # ", "")
            .inOrder()

        // Test multiple targets D and G for allPaths
        val file3 = File.createTempFile("output_text_multi_all", "txt")
        file3.deleteOnExit()
        FileOutputStream(file3).use { outputStream3 ->
            val executor3 = ModExecutor(depGraph, options, outputStream3)
            executor3.allPaths(
                ImmutableSet.of<ModuleKey>(ModuleKey.ROOT),
                ImmutableSet.of<ModuleKey>(
                    BzlmodTestUtil.createModuleKey("D", "1.0"),
                    BzlmodTestUtil.createModuleKey("G", "1.0")
                )
            )
        }
        val textOutput3 = Files.readAllLines(file3.toPath())
        Truth.assertThat(textOutput3)
            .containsExactly(
                "<root> (main@1.0)",
                "├───A@1.0 ",
                "│   └───B@1.0 ",
                "│       └───C@1.0 ",
                "│           └───D@1.0 # ",
                "├───G@1.0 # ",
                "└───I@1.0 ",
                "    └───D@1.0 # ",
                ""
            )
            .inOrder()

        // Test multiple targets D and G for path (shortest path)
        val file4 = File.createTempFile("output_text_multi_path", "txt")
        file4.deleteOnExit()
        FileOutputStream(file4).use { outputStream4 ->
            val executor4 = ModExecutor(depGraph, options, outputStream4)
            executor4.path(
                ImmutableSet.of<ModuleKey>(ModuleKey.ROOT),
                ImmutableSet.of<ModuleKey>(
                    BzlmodTestUtil.createModuleKey("D", "1.0"),
                    BzlmodTestUtil.createModuleKey("G", "1.0")
                )
            )
        }
        val textOutput4 = Files.readAllLines(file4.toPath())
        Truth.assertThat(textOutput4)
            .containsExactly("<root> (main@1.0)", "├───G@1.0 # ", "└───I@1.0 ", "    └───D@1.0 # ", "")
            .inOrder()

        // Test starting from E to D for allPaths
        val file5 = File.createTempFile("output_text_E_to_D_all", "txt")
        file5.deleteOnExit()
        FileOutputStream(file5).use { outputStream5 ->
            val executor5 = ModExecutor(depGraph, options, outputStream5)
            executor5.allPaths(
                ImmutableSet.of<ModuleKey>(BzlmodTestUtil.createModuleKey("E", "1.0")),
                ImmutableSet.of<ModuleKey>(BzlmodTestUtil.createModuleKey("D", "1.0"))
            )
        }
        val textOutput5 = Files.readAllLines(file5.toPath())
        Truth.assertThat(textOutput5)
            .containsExactly(
                "<root> (main@1.0)",
                "└╌╌╌E@1.0 ",
                "    └───F@1.0 ",
                "        └───C@1.0 ",
                "            └───D@1.0 # ",
                ""
            )
            .inOrder()
    }

    @Test
    @Throws(ParseException::class, IOException::class)
    fun testModCommandGraph_withCycle() {
        // <root> -> A -> B -> A (cycle)
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "main",
                        Version.parse("1.0"),
                        true
                    )
                        .addDep("A", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("A", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addStillDependant("B", "1.0")
                        .addDep("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("B", "1.0")
                        .addStillDependant("A", "1.0")
                        .addDep("A", "1.0")
                        .buildEntry()
                )
                .buildOrThrow()

        val options: ModOptions = ModOptions.getDefaultOptions()
        options.outputFormat = ModOptions.OutputFormat.TEXT
        options.cycles = true

        val file = File.createTempFile("output_text_cycle", "txt")
        file.deleteOnExit()
        FileOutputStream(file).use { outputStream ->
            val executor = ModExecutor(depGraph, options, outputStream)
            executor.graph(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT))
        }
        val textOutput = Files.readAllLines(file.toPath())

        Truth.assertThat(textOutput)
            .containsExactly(
                "<root> (main@1.0)", "└───A@1.0 ", "    └───B@1.0 ", "        └───A@1.0 (cycle) ", ""
            )
            .inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun testGraphWithExtensionFilterOnRoot() {
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "main",
                        Version.parse("1.0"),
                        true
                    )
                        .buildEntry()
                )
                .buildOrThrow()

        val mavenId: ModuleExtensionId = createExtensionId("extensions", "maven")
        val extensionUsages: ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?> =
            ImmutableTable.Builder<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>()
                .put(
                    mavenId,
                    ModuleKey.ROOT,
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//extensions:extensions.bzl")
                        .setExtensionName("maven")
                        .setRepoOverrides(ImmutableMap.of<K?, V?>())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(Location.fromFileLineColumn("MODULE.bazel", 1, 1))
                                .setImports(ImmutableBiMap.< K, V > of<K?, V?>("repo1", "repo1"))
                                .setDevDependency(false)
                                .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build()
                        )
                        .build()
                )
                .buildOrThrow()

        val extensionRepos: ImmutableSetMultimap<ModuleExtensionId?, String?> =
            ImmutableSetMultimap.Builder<ModuleExtensionId?, String?>()
                .putAll(mavenId, ImmutableSet.of<String?>("repo1"))
                .build()

        val options: ModOptions = ModOptions.getDefaultOptions()
        options.outputFormat = ModOptions.OutputFormat.TEXT
        options.extensionInfo = ExtensionShow.ALL

        val file = File.createTempFile("output_text_repro", "txt")
        file.deleteOnExit()
        FileOutputStream(file).use { outputStream ->
            val executor =
                ModExecutor(
                    depGraph,
                    extensionUsages,
                    extensionRepos,
                    Optional.of<T?>(MaybeCompleteSet.copyOf(ImmutableSet.of<E?>(mavenId))),
                    options,
                    outputStream
                )
            // This should not throw NPE
            executor.graph(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT))
        }
        val textOutput = Files.readAllLines(file.toPath())
        Truth.assertThat(textOutput)
            .containsExactly(
                "<root> (main@1.0)", "└───$@@//extensions:extensions%maven ", "    └───repo1", ""
            )
            .inOrder()
    }

    @Test
    @Throws(Exception::class)
    fun testGraphWithExtensionFilterAndCycle() {
        // <root> -> A -> B -> A (cycle)
        // A and B both use extension defined in A.
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.Builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule(
                        ModuleKey.ROOT,
                        "main",
                        Version.parse("1.0"),
                        true
                    )
                        .addDep("A", "1.0")
                        .addDep("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("A", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addStillDependant("B", "1.0")
                        .addDep("B", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("B", "1.0")
                        .addStillDependant(ModuleKey.ROOT)
                        .addStillDependant("A", "1.0")
                        .addDep("A", "1.0")
                        .buildEntry()
                )
                .buildOrThrow()

        val extensionId: ModuleExtensionId = createExtensionId("A", "1.0", "extensions", "ext")
        val extensionUsages: ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?> =
            ImmutableTable.Builder<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>()
                .put(
                    extensionId,
                    BzlmodTestUtil.createModuleKey("A", "1.0"),
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//extensions:extensions.bzl")
                        .setExtensionName("ext")
                        .setRepoOverrides(ImmutableMap.of<K?, V?>())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(Location.fromFileLineColumn("MODULE.bazel", 1, 1))
                                .setImports(ImmutableBiMap.< K, V > of<K?, V?>("repo1", "repo1"))
                                .setDevDependency(false)
                                .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build()
                        )
                        .build()
                )
                .put(
                    extensionId,
                    BzlmodTestUtil.createModuleKey("B", "1.0"),
                    ModuleExtensionUsage.builder()
                        .setExtensionBzlFile("//extensions:extensions.bzl")
                        .setExtensionName("ext")
                        .setRepoOverrides(ImmutableMap.of<K?, V?>())
                        .addProxy(
                            ModuleExtensionUsage.Proxy.builder()
                                .setLocation(Location.fromFileLineColumn("MODULE.bazel", 1, 1))
                                .setImports(ImmutableBiMap.< K, V > of<K?, V?>("repo2", "repo2"))
                                .setDevDependency(false)
                                .setContainingModuleFilePath(LabelConstants.MODULE_DOT_BAZEL_FILE_NAME)
                                .build()
                        )
                        .build()
                )
                .buildOrThrow()

        val extensionRepos: ImmutableSetMultimap<ModuleExtensionId?, String?> =
            ImmutableSetMultimap.Builder<ModuleExtensionId?, String?>()
                .putAll(extensionId, ImmutableSet.of<String?>("repo1", "repo2"))
                .build()

        val options: ModOptions = ModOptions.getDefaultOptions()
        options.outputFormat = ModOptions.OutputFormat.TEXT
        options.extensionInfo = ExtensionShow.ALL
        options.cycles = true

        val file = File.createTempFile("output_text_cycle_ext", "txt")
        file.deleteOnExit()
        FileOutputStream(file).use { outputStream ->
            val executor =
                ModExecutor(
                    depGraph,
                    extensionUsages,
                    extensionRepos,
                    Optional.of<T?>(MaybeCompleteSet.copyOf(ImmutableSet.of<E?>(extensionId))),
                    options,
                    outputStream
                )
            executor.graph(ImmutableSet.of<ModuleKey>(ModuleKey.ROOT))
        }
        val textOutput = Files.readAllLines(file.toPath())
        Truth.assertThat(textOutput)
            .containsExactly(
                "<root> (main@1.0)",
                "├───A@1.0 # ",
                "│   ├───$@@A+1.0//extensions:extensions%ext ",
                "│   │   └───repo1",
                "│   ├───B@1.0 (cycle) ",
                "│   └───B@1.0 # ",
                "│       ├───$@@A+1.0//extensions:extensions%ext ... ",
                "│       │   └───repo2",
                "│       ├───A@1.0 (cycle) ",
                "│       └───A@1.0 (cycle) ",
                "└───B@1.0 # ",
                "    ├───$@@A+1.0//extensions:extensions%ext ... ",
                "    │   └───repo2",
                "    ├───A@1.0 (cycle) ",
                "    └───A@1.0 # ",
                "        ├───$@@A+1.0//extensions:extensions%ext ... ",
                "        │   └───repo1",
                "        ├───B@1.0 (cycle) ",
                "        └───B@1.0 (cycle) ",
                ""
            )
            .inOrder()
    }
}
