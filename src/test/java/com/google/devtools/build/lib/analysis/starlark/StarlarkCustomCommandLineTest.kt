// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.actions.AbstractAction
import com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLineTest.Companion.verifyCommandLine

/** Tests for [StarlarkCustomCommandLine].  */
@RunWith(TestParameterInjector::class)
class StarlarkCustomCommandLineTest {
    @TestParameter
    private val useNestedSet = false

    private var derivedRoot: ArtifactRoot? = null
    private var artifact1: DerivedArtifact? = null
    private var artifact2: DerivedArtifact? = null
    private var artifact3: DerivedArtifact? = null
    private var action: AbstractAction? = null

    private val builder: StarlarkCustomCommandLine.Builder = Builder(StarlarkSemantics.DEFAULT)

    @Before
    @Throws(IOException::class)
    fun createArtifacts() {
        val execRoot: Path? = InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/execroot")
        derivedRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "bin")

        val derivedRoot2: ArtifactRoot? =
            ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "bazel-out", "k8-fastbuild", "bin")
        val derivedRoot3: ArtifactRoot? =
            ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "bazel-out", "k8-opt", "bin")
        artifact1 = ActionsTestUtil.createArtifact(derivedRoot2, "pkg/artifact1") as DerivedArtifact
        artifact2 = ActionsTestUtil.createArtifact(derivedRoot3, "pkg/artifact2") as DerivedArtifact
        artifact3 = ActionsTestUtil.createArtifact(derivedRoot3, "artifact3") as DerivedArtifact
        action =
            object : MockAction(
                com.google.common.collect.ImmutableList.of<Artifact>(artifact1, artifact2),
                com.google.common.collect.ImmutableSet.of<Artifact>(artifact3)
            ) {
                val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>
                    get() = com.google.common.collect.ImmutableMap.of<String?, String?>(
                        ExecutionRequirements.SUPPORTS_PATH_MAPPING,
                        ""
                    )
            }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun add() {
        val commandLine: CommandLine? =
            builder
                .add("one")
                .add("two")
                .add("three")
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(commandLine, "one", "two", "three")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializationWithFormattedArgumentsWorks() {
        val original: CommandLine? =
            builder.addFormatted("value", "key=%s").build(false, RepositoryMapping.EMPTY)
        val deserialized: CommandLine? = RoundTripping.roundTrip(original)
        Companion.verifyCommandLine(deserialized, "key=value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addPathMapped() {
        val commandLine: CommandLine? =
            builder
                .add(artifact1)
                .add(artifact2)
                .add(artifact3)
                .add(artifact1.getRoot())
                .add(artifact2.getRoot())
                .add(artifact3.getRoot())
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "bazel-out/k8-fastbuild/bin/pkg/artifact1",
            "bazel-out/k8-opt/bin/pkg/artifact2",
            "bazel-out/k8-opt/bin/artifact3",
            "bazel-out/k8-fastbuild/bin",
            "bazel-out/k8-opt/bin",
            "bazel-out/k8-opt/bin"
        )
        verifyStrippedCommandLine(
            commandLine,
            "bazel-out/cfg/bin/pkg/artifact1",
            "bazel-out/cfg/bin/pkg/artifact2",
            "bazel-out/cfg/bin/artifact3",
            "bazel-out/k8-fastbuild/bin",
            "bazel-out/k8-opt/bin",
            "bazel-out/k8-opt/bin"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addFormatted() {
        val commandLine: CommandLine? =
            builder
                .addFormatted("one", "--arg1=%s")
                .addFormatted("two", "--arg2=%s")
                .addFormatted("three", "--arg3=%s")
                .addFormatted(artifact1.getRoot(), "--arg1_root=%s")
                .addFormatted(artifact2.getRoot(), "--arg2_root=%s")
                .addFormatted(artifact3.getRoot(), "--arg3_root=%s")
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "--arg1=one",
            "--arg2=two",
            "--arg3=three",
            "--arg1_root=bazel-out/k8-fastbuild/bin",
            "--arg2_root=bazel-out/k8-opt/bin",
            "--arg3_root=bazel-out/k8-opt/bin"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addFormattedPathMapped() {
        val commandLine: CommandLine? =
            builder
                .addFormatted(artifact1, "--arg1=%s")
                .addFormatted(artifact2, "--arg2=%s")
                .addFormatted(artifact3, "--arg3=%s")
                .addFormatted(artifact1.getRoot(), "--arg1_root=%s")
                .addFormatted(artifact2.getRoot(), "--arg2_root=%s")
                .addFormatted(artifact3.getRoot(), "--arg3_root=%s")
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "--arg1=bazel-out/k8-fastbuild/bin/pkg/artifact1",
            "--arg2=bazel-out/k8-opt/bin/pkg/artifact2",
            "--arg3=bazel-out/k8-opt/bin/artifact3",
            "--arg1_root=bazel-out/k8-fastbuild/bin",
            "--arg2_root=bazel-out/k8-opt/bin",
            "--arg3_root=bazel-out/k8-opt/bin"
        )
        verifyStrippedCommandLine(
            commandLine,
            "--arg1=bazel-out/cfg/bin/pkg/artifact1",
            "--arg2=bazel-out/cfg/bin/pkg/artifact2",
            "--arg3=bazel-out/cfg/bin/artifact3",
            "--arg1_root=bazel-out/k8-fastbuild/bin",
            "--arg2_root=bazel-out/k8-opt/bin",
            "--arg3_root=bazel-out/k8-opt/bin"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun argName() {
        val commandLine: CommandLine? =
            builder
                .add(vectorArg("one", "two", "three").setArgName("--arg"))
                .add(vectorArg("four").setArgName("--other_arg"))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(commandLine, "--arg", "one", "two", "three", "--other_arg", "four")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun terminateWith() {
        val commandLine: CommandLine? =
            builder
                .add(vectorArg("one", "two", "three").setTerminateWith("end1"))
                .add(vectorArg("four").setTerminateWith("end2"))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(commandLine, "one", "two", "three", "end1", "four", "end2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun formatEach() {
        val commandLine: CommandLine? =
            builder
                .add(vectorArg("one", "two", "three").setFormatEach("--arg=%s"))
                .add(vectorArg("four").setFormatEach("--other_arg=%s"))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(commandLine, "--arg=one", "--arg=two", "--arg=three", "--other_arg=four")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun formatEachPathMapped() {
        val commandLine: CommandLine? =
            builder
                .add(vectorArg(artifact1, artifact2, artifact3).setFormatEach("--arg=%s"))
                .add(vectorArg(artifact1.getRoot(), artifact2.getRoot()).setFormatEach("--arg=%s"))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "--arg=bazel-out/k8-fastbuild/bin/pkg/artifact1",
            "--arg=bazel-out/k8-opt/bin/pkg/artifact2",
            "--arg=bazel-out/k8-opt/bin/artifact3",
            "--arg=bazel-out/k8-fastbuild/bin",
            "--arg=bazel-out/k8-opt/bin"
        )
        verifyStrippedCommandLine(
            commandLine,
            "--arg=bazel-out/cfg/bin/pkg/artifact1",
            "--arg=bazel-out/cfg/bin/pkg/artifact2",
            "--arg=bazel-out/cfg/bin/artifact3",
            "--arg=bazel-out/k8-fastbuild/bin",
            "--arg=bazel-out/k8-opt/bin"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun beforeEach() {
        val commandLine: CommandLine? =
            builder
                .add(vectorArg("one", "two", "three").setBeforeEach("b4"))
                .add(vectorArg("four").setBeforeEach("and"))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(commandLine, "b4", "one", "b4", "two", "b4", "three", "and", "four")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun beforeEachPathMapped() {
        val commandLine: CommandLine? =
            builder
                .add(vectorArg(artifact1, artifact2, artifact3).setBeforeEach("b4"))
                .add(vectorArg(artifact1.getRoot(), artifact2.getRoot()).setBeforeEach("b4"))
                .add(vectorArg(artifact3).setBeforeEach("and"))
                .add(vectorArg(artifact3.getRoot()).setBeforeEach("and"))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "b4",
            "bazel-out/k8-fastbuild/bin/pkg/artifact1",
            "b4",
            "bazel-out/k8-opt/bin/pkg/artifact2",
            "b4",
            "bazel-out/k8-opt/bin/artifact3",
            "b4",
            "bazel-out/k8-fastbuild/bin",
            "b4",
            "bazel-out/k8-opt/bin",
            "and",
            "bazel-out/k8-opt/bin/artifact3",
            "and",
            "bazel-out/k8-opt/bin"
        )
        verifyStrippedCommandLine(
            commandLine,
            "b4",
            "bazel-out/cfg/bin/pkg/artifact1",
            "b4",
            "bazel-out/cfg/bin/pkg/artifact2",
            "b4",
            "bazel-out/cfg/bin/artifact3",
            "b4",
            "bazel-out/k8-fastbuild/bin",
            "b4",
            "bazel-out/k8-opt/bin",
            "and",
            "bazel-out/cfg/bin/artifact3",
            "and",
            "bazel-out/k8-opt/bin"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun joinWith() {
        val commandLine: CommandLine? =
            builder
                .add(vectorArg("one", "two", "three").setJoinWith("..."))
                .add(vectorArg("four").setJoinWith("n/a"))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(commandLine, "one...two...three", "four")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun joinWithPathMapped() {
        val commandLine: CommandLine? =
            builder
                .add(vectorArg(artifact1, artifact2, artifact3).setJoinWith("..."))
                .add(vectorArg(artifact1.getRoot(), artifact2.getRoot()).setJoinWith("..."))
                .add(vectorArg(artifact3).setJoinWith("..."))
                .add(vectorArg(artifact3.getRoot()).setJoinWith("..."))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "bazel-out/k8-fastbuild/bin/pkg/artifact1...bazel-out/k8-opt/bin/pkg/artifact2...bazel-out/k8-opt/bin/artifact3",
            "bazel-out/k8-fastbuild/bin...bazel-out/k8-opt/bin",
            "bazel-out/k8-opt/bin/artifact3",
            "bazel-out/k8-opt/bin"
        )
        verifyStrippedCommandLine(
            commandLine,
            "bazel-out/cfg/bin/pkg/artifact1...bazel-out/cfg/bin/pkg/artifact2...bazel-out/cfg/bin/artifact3",
            "bazel-out/k8-fastbuild/bin...bazel-out/k8-opt/bin",
            "bazel-out/cfg/bin/artifact3",
            "bazel-out/k8-opt/bin"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun formatJoined() {
        val commandLine: CommandLine? =
            builder
                .add(vectorArg("one", "two", "three").setJoinWith("...").setFormatJoined("--arg=%s"))
                .add(vectorArg("four").setJoinWith("n/a").setFormatJoined("--other_arg=%s"))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(commandLine, "--arg=one...two...three", "--other_arg=four")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun formatJoinedPathMapped() {
        val commandLine: CommandLine? =
            builder
                .add(
                    vectorArg(artifact1, artifact2, artifact3)
                        .setJoinWith("...")
                        .setFormatJoined("--arg=%s")
                )
                .add(
                    vectorArg(artifact1.getRoot(), artifact2.getRoot())
                        .setJoinWith("...")
                        .setFormatJoined("--arg=%s")
                )
                .add(vectorArg(artifact3).setJoinWith("...").setFormatJoined("--other_arg=%s"))
                .add(
                    vectorArg(artifact3.getRoot()).setJoinWith("...").setFormatJoined("--other_arg=%s")
                )
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "--arg=bazel-out/k8-fastbuild/bin/pkg/artifact1...bazel-out/k8-opt/bin/pkg/artifact2...bazel-out/k8-opt/bin/artifact3",
            "--arg=bazel-out/k8-fastbuild/bin...bazel-out/k8-opt/bin",
            "--other_arg=bazel-out/k8-opt/bin/artifact3",
            "--other_arg=bazel-out/k8-opt/bin"
        )
        verifyStrippedCommandLine(
            commandLine,
            "--arg=bazel-out/cfg/bin/pkg/artifact1...bazel-out/cfg/bin/pkg/artifact2...bazel-out/cfg/bin/artifact3",
            "--arg=bazel-out/k8-fastbuild/bin...bazel-out/k8-opt/bin",
            "--other_arg=bazel-out/cfg/bin/artifact3",
            "--other_arg=bazel-out/k8-opt/bin"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyVectorArg_omit() {
        val commandLine: CommandLine? =
            builder
                .add("before")
                .add(vectorArg().omitIfEmpty(true).setJoinWith(",").setFormatJoined("--empty=%s"))
                .add("after")
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(commandLine, "before", "after")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyVectorArg_noOmit() {
        val commandLine: CommandLine? =
            builder
                .add("before")
                .add(vectorArg().omitIfEmpty(false).setJoinWith(",").setFormatJoined("--empty=%s"))
                .add("after")
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(commandLine, "before", "--empty=", "after")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun uniquifyPathMapped() {
        // NestedSet doesn't support mixed types.
        TruthJUnit.assume().that(useNestedSet).isFalse()

        val commandLine: CommandLine? =
            builder
                .add(
                    vectorArg(
                        artifact1,
                        artifact1,
                        artifact2,
                        artifact3,
                        artifact1.getExecPathString(),
                        artifact1.getExecPathString(),
                        artifact2.getExecPathString(),
                        artifact3.getExecPathString()
                    )
                        .uniquify(true)
                )
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "bazel-out/k8-fastbuild/bin/pkg/artifact1",
            "bazel-out/k8-opt/bin/pkg/artifact2",
            "bazel-out/k8-opt/bin/artifact3"
        )
        verifyStrippedCommandLine(
            commandLine,
            "bazel-out/cfg/bin/pkg/artifact1",
            "bazel-out/cfg/bin/pkg/artifact2",
            "bazel-out/cfg/bin/artifact3",
            "bazel-out/k8-fastbuild/bin/pkg/artifact1",
            "bazel-out/k8-opt/bin/pkg/artifact2",
            "bazel-out/k8-opt/bin/artifact3"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagPerLine() {
        val commandLine: CommandLine? =
            builder
                .recordArgStart()
                .add(vectorArg("is", "line", "one").setArgName("--this"))
                .recordArgStart()
                .add(vectorArg("this", "is", "line", "two").setArgName("--and"))
                .recordArgStart()
                .add("--line_three")
                .add("single_arg")
                .recordArgStart()
                .add(vectorArg("", "line", "four", "has", "no").setTerminateWith("flag"))
                .build( /* flagPerLine= */true, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "--this=is line one",
            "--and=this is line two",
            "--line_three=single_arg",
            "line four has no flag"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun flagPerLinePathMapped() {
        // NestedSet doesn't support mixed types.
        TruthJUnit.assume().that(useNestedSet).isFalse()

        val commandLine: CommandLine? =
            builder
                .recordArgStart()
                .add(vectorArg(artifact1, artifact2, artifact3).setArgName("--this"))
                .recordArgStart()
                .add(vectorArg(artifact3, artifact2, artifact1).setArgName("--and"))
                .recordArgStart()
                .add("--line_three")
                .add("single_arg")
                .recordArgStart()
                .add(vectorArg("", artifact1, artifact2, artifact3).setTerminateWith("flag"))
                .build( /* flagPerLine= */true, RepositoryMapping.EMPTY)
        Companion.verifyCommandLine(
            commandLine,
            "--this=bazel-out/k8-fastbuild/bin/pkg/artifact1 bazel-out/k8-opt/bin/pkg/artifact2"
                    + " bazel-out/k8-opt/bin/artifact3",
            "--and=bazel-out/k8-opt/bin/artifact3 bazel-out/k8-opt/bin/pkg/artifact2"
                    + " bazel-out/k8-fastbuild/bin/pkg/artifact1",
            "--line_three=single_arg",
            "bazel-out/k8-fastbuild/bin/pkg/artifact1 bazel-out/k8-opt/bin/pkg/artifact2"
                    + " bazel-out/k8-opt/bin/artifact3 flag"
        )
        verifyStrippedCommandLine(
            commandLine,
            "--this=bazel-out/cfg/bin/pkg/artifact1 bazel-out/cfg/bin/pkg/artifact2"
                    + " bazel-out/cfg/bin/artifact3",
            "--and=bazel-out/cfg/bin/artifact3 bazel-out/cfg/bin/pkg/artifact2"
                    + " bazel-out/cfg/bin/pkg/artifact1",
            "--line_three=single_arg",
            "bazel-out/cfg/bin/pkg/artifact1 bazel-out/cfg/bin/pkg/artifact2"
                    + " bazel-out/cfg/bin/artifact3 flag"
        )
    }

    @org.junit.Test
    fun vectorArg_treeArtifactMissingExpansion_fails() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val commandLine: CommandLine =
            builder
                .add(vectorArg(tree).setExpandDirectories(true))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)

        val e: CommandLineExpansionException? =
            org.junit.Assert.assertThrows<T?>(
                CommandLineExpansionException::class.java,
                org.junit.function.ThrowingRunnable {
                    commandLine.arguments(
                        com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
                        PathMapper.NOOP
                    )
                })
        assertThat(e).hasMessageThat().contains("Failed to expand directory <generated file tree>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vectorArgAddToFingerprint_expandFileset_includesInDigest() {
        val fileset: SpecialArtifact = createFileset("fileset")
        val commandLine: CommandLine =
            builder
                .add(vectorArg(fileset).setExpandDirectories(true))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        val symlink1: FilesetOutputSymlink = createFilesetSymlink("file1")
        val symlink2: FilesetOutputSymlink = createFilesetSymlink("file2")
        val actionKeyContext: ActionKeyContext = ActionKeyContext()
        val fingerprint: Fingerprint = Fingerprint()

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putFileset(
            fileset,
            FilesetOutputTree.create(
                com.google.common.collect.ImmutableList.of<E?>(symlink1, symlink2),  /* treeArtifacts= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        )
        commandLine.addToFingerprint(
            actionKeyContext, fakeActionInputFileCache, CoreOptions.OutputPathsMode.OFF, fingerprint
        )

        assertThat(fingerprint.digestAndReset()).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vectorArgAddToFingerprint_expandTreeArtifact_includesInDigest() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val child: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child")
        // The files won't be read so MISSING_FILE_MARKER will do
        val treeArtifactValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree)
                .putChild(child, FileArtifactValue.MISSING_FILE_MARKER)
                .build()

        val commandLine: CommandLine =
            builder
                .add(vectorArg(tree).setExpandDirectories(true))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)

        val actionKeyContext: ActionKeyContext = ActionKeyContext()
        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(tree, treeArtifactValue)

        val fingerprint: Fingerprint = Fingerprint()
        commandLine.addToFingerprint(
            actionKeyContext, fakeActionInputFileCache, CoreOptions.OutputPathsMode.OFF, fingerprint
        )
        assertThat(fingerprint.digestAndReset()).isNotEmpty()
    }

    @org.junit.Test
    fun vectorArg_expandFilesetMissingExpansion_fails() {
        val fileset: SpecialArtifact = createFileset("fileset")
        val commandLine: CommandLine =
            builder
                .add(vectorArg(fileset).setExpandDirectories(true))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)

        val e: CommandLineExpansionException? =
            org.junit.Assert.assertThrows<T?>(
                CommandLineExpansionException::class.java,
                org.junit.function.ThrowingRunnable {
                    commandLine.arguments(
                        com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
                        PathMapper.NOOP
                    )
                })
        assertThat(e)
            .hasMessageThat()
            .contains("Could not expand fileset: File:[[<execution_root>]bin]fileset")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vectorArgArguments_expandsTreeArtifact() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val child1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child1")
        val child2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child2")
        // The files won't be read so MISSING_FILE_MARKER will do
        val treeArtifactValue: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree)
                .putChild(child1, FileArtifactValue.MISSING_FILE_MARKER)
                .putChild(child2, FileArtifactValue.MISSING_FILE_MARKER)
                .build()

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(tree, treeArtifactValue)

        val commandLine: CommandLine =
            builder
                .add(vectorArg(tree).setExpandDirectories(true))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        val arguments: Iterable<String?>? = commandLine.arguments(fakeActionInputFileCache, PathMapper.NOOP)
        Truth.assertThat(arguments).containsExactly("bin/tree/child1", "bin/tree/child2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vectorArgArguments_expandsFileset() {
        val fileset: SpecialArtifact = createFileset("fileset")
        val symlink1: FilesetOutputSymlink = createFilesetSymlink("file1")
        val symlink2: FilesetOutputSymlink = createFilesetSymlink("file2")

        val fakeActionInputFileCache: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        fakeActionInputFileCache.putFileset(
            fileset,
            FilesetOutputTree.create(
                com.google.common.collect.ImmutableList.of<E?>(symlink1, symlink2),  /* treeArtifacts= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        )

        val commandLine: CommandLine =
            builder
                .add(vectorArg(fileset).setExpandDirectories(true))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)
        val arguments: Iterable<String?>? = commandLine.arguments(fakeActionInputFileCache, PathMapper.NOOP)

        Truth.assertThat(arguments).containsExactly("bin/fileset/file1", "bin/fileset/file2")
    }

    @org.junit.Test
    fun vectorArgArguments_treeArtifactMissingExpansion_fails() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val commandLine: CommandLine =
            builder
                .add(vectorArg(tree).setExpandDirectories(true))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)

        org.junit.Assert.assertThrows<T?>(
            CommandLineExpansionException::class.java,
            org.junit.function.ThrowingRunnable {
                commandLine.arguments(
                    com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
                    PathMapper.NOOP
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vectorArgArguments_manuallyExpandedTreeArtifactMissingExpansion_fails() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val commandLine: CommandLine =
            builder
                .add(
                    vectorArg(tree)
                        .setExpandDirectories(false)
                        .setLocation(net.starlark.java.syntax.Location.BUILTIN)
                        .setMapEach(
                            execStarlark(
                                """
                                def map_each(x, expander):
                                  expander.expand(x)
                                map_each
                                
                                """.trimIndent()
                            ) as StarlarkFunction?
                        )
                )
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)

        val e: CommandLineExpansionException? =
            org.junit.Assert.assertThrows<T?>(
                CommandLineExpansionException::class.java,
                org.junit.function.ThrowingRunnable {
                    commandLine.arguments(
                        com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
                        PathMapper.NOOP
                    )
                })
        assertThat(e).hasMessageThat().contains("Failed to expand directory <generated file tree>")
    }

    @org.junit.Test
    fun vectorArgArguments_filesetMissingExpansion_fails() {
        val fileset: SpecialArtifact = createFileset("fileset")
        val commandLine: CommandLine =
            builder
                .add(vectorArg(fileset).setExpandDirectories(true))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)

        org.junit.Assert.assertThrows<T?>(
            CommandLineExpansionException::class.java,
            org.junit.function.ThrowingRunnable {
                commandLine.arguments(
                    com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(),
                    PathMapper.NOOP
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vectorArgArguments_expandDirectoriesDisabled_manualExpansionReflectedInActionKey() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val child1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child1")
        val child2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child2")
        // The files won't be read so MISSING_FILE_MARKER will do
        val treeArtifactValueBefore: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree)
                .putChild(child1, FileArtifactValue.MISSING_FILE_MARKER)
                .putChild(child2, FileArtifactValue.MISSING_FILE_MARKER)
                .build()
        val treeArtifactValueAfter: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree)
                .putChild(child1, FileArtifactValue.MISSING_FILE_MARKER)
                .build()
        val commandLine: CommandLine =
            builder
                .add(
                    vectorArg(tree)
                        .setExpandDirectories(false)
                        .setLocation(net.starlark.java.syntax.Location.BUILTIN)
                        .setMapEach(
                            execStarlark(
                                """
                                def map_each(x, expander):
                                  return [f.path for f in expander.expand(x)]
                                map_each
                                
                                """.trimIndent()
                            ) as StarlarkFunction?
                        )
                )
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)

        val inputMetadataProviderBefore: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProviderBefore.putTreeArtifact(tree, treeArtifactValueBefore)
        val argumentsBefore: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandLine.arguments(inputMetadataProviderBefore, PathMapper.NOOP)
        val fingerprintBefore: Fingerprint = Fingerprint()
        commandLine.addToFingerprint(
            ActionKeyContext(),
            inputMetadataProviderBefore,
            CoreOptions.OutputPathsMode.OFF,
            fingerprintBefore
        )
        assertThat(argumentsBefore).containsExactly("bin/tree/child1", "bin/tree/child2")

        val inputMetadataProviderAfter: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProviderAfter.putTreeArtifact(tree, treeArtifactValueAfter)
        val argumentsAfter: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandLine.arguments(inputMetadataProviderAfter, PathMapper.NOOP)
        val fingerprintAfter: Fingerprint = Fingerprint()
        commandLine.addToFingerprint(
            ActionKeyContext(),
            inputMetadataProviderAfter,
            CoreOptions.OutputPathsMode.OFF,
            fingerprintAfter
        )
        assertThat(argumentsAfter).containsExactly("bin/tree/child1")

        assertThat(fingerprintBefore.hexDigestAndReset())
            .isNotEqualTo(fingerprintAfter.hexDigestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vectorArgArguments_expandDirectoriesDisabled_noMapEach_expansionDoesNotAffectActionKey() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val child1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child1")
        val child2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child2")
        // The files won't be read so MISSING_FILE_MARKER will do
        val treeArtifactValueBefore: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree)
                .putChild(child1, FileArtifactValue.MISSING_FILE_MARKER)
                .putChild(child2, FileArtifactValue.MISSING_FILE_MARKER)
                .build()
        val treeArtifactValueAfter: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree)
                .putChild(child1, FileArtifactValue.MISSING_FILE_MARKER)
                .build()
        val commandLine: CommandLine =
            builder
                .add(vectorArg(tree).setExpandDirectories(false).setLocation(net.starlark.java.syntax.Location.BUILTIN))
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)

        val inputMetadataProviderBefore: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProviderBefore.putTreeArtifact(tree, treeArtifactValueBefore)
        val argumentsBefore: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandLine.arguments(inputMetadataProviderBefore, PathMapper.NOOP)
        val fingerprintBefore: Fingerprint = Fingerprint()
        commandLine.addToFingerprint(
            ActionKeyContext(),
            inputMetadataProviderBefore,
            CoreOptions.OutputPathsMode.OFF,
            fingerprintBefore
        )
        assertThat(argumentsBefore).containsExactly("bin/tree")

        val inputMetadataProviderAfter: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProviderAfter.putTreeArtifact(tree, treeArtifactValueAfter)
        val argumentsAfter: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandLine.arguments(inputMetadataProviderAfter, PathMapper.NOOP)
        val fingerprintAfter: Fingerprint = Fingerprint()
        commandLine.addToFingerprint(
            ActionKeyContext(),
            inputMetadataProviderAfter,
            CoreOptions.OutputPathsMode.OFF,
            fingerprintAfter
        )
        assertThat(argumentsAfter).containsExactly("bin/tree")

        assertThat(fingerprintBefore.hexDigestAndReset())
            .isEqualTo(fingerprintAfter.hexDigestAndReset())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vectorArgArguments_expandDirectoriesDisabled_noManualExpansion_expansionDoesNotAffectActionKey() {
        val tree: SpecialArtifact = createTreeArtifact("tree")
        val child1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child1")
        val child2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(tree, "child2")
        // The files won't be read so MISSING_FILE_MARKER will do
        val treeArtifactValueBefore: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree)
                .putChild(child1, FileArtifactValue.MISSING_FILE_MARKER)
                .putChild(child2, FileArtifactValue.MISSING_FILE_MARKER)
                .build()
        val treeArtifactValueAfter: TreeArtifactValue? =
            TreeArtifactValue.newBuilder(tree)
                .putChild(child1, FileArtifactValue.MISSING_FILE_MARKER)
                .build()
        val commandLine: CommandLine =
            builder
                .add(
                    vectorArg(tree)
                        .setExpandDirectories(false)
                        .setLocation(net.starlark.java.syntax.Location.BUILTIN)
                        .setMapEach(
                            execStarlark(
                                """
                                def map_each(x):
                                  return x.path
                                map_each
                                
                                """.trimIndent()
                            ) as StarlarkFunction?
                        )
                )
                .build( /* flagPerLine= */false, RepositoryMapping.EMPTY)

        val inputMetadataProviderBefore: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProviderBefore.putTreeArtifact(tree, treeArtifactValueBefore)
        val argumentsBefore: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandLine.arguments(inputMetadataProviderBefore, PathMapper.NOOP)
        val fingerprintBefore: Fingerprint = Fingerprint()
        commandLine.addToFingerprint(
            ActionKeyContext(),
            inputMetadataProviderBefore,
            CoreOptions.OutputPathsMode.OFF,
            fingerprintBefore
        )
        assertThat(argumentsBefore).containsExactly("bin/tree")

        val inputMetadataProviderAfter: com.google.devtools.build.lib.exec.util.FakeActionInputFileCache =
            com.google.devtools.build.lib.exec.util.FakeActionInputFileCache()
        inputMetadataProviderAfter.putTreeArtifact(tree, treeArtifactValueAfter)
        val argumentsAfter: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandLine.arguments(inputMetadataProviderAfter, PathMapper.NOOP)
        val fingerprintAfter: Fingerprint = Fingerprint()
        commandLine.addToFingerprint(
            ActionKeyContext(),
            inputMetadataProviderAfter,
            CoreOptions.OutputPathsMode.OFF,
            fingerprintAfter
        )
        assertThat(argumentsAfter).containsExactly("bin/tree")

        assertThat(fingerprintBefore.hexDigestAndReset())
            .isEqualTo(fingerprintAfter.hexDigestAndReset())
    }

    private fun vectorArg(vararg elems: Any?): VectorArg.Builder {
        if (this.useNestedSet) {
            val commonType: java.lang.Class<*>?
            if (java.util.Arrays.stream<Any?>(elems).allMatch { obj: Any? -> FileApi::class.java.isInstance(obj) }) {
                commonType = FileApi::class.java
            } else if (java.util.Arrays.stream<Any?>(elems)
                    .allMatch { obj: Any? -> FileRootApi::class.java.isInstance(obj) }
            ) {
                commonType = FileRootApi::class.java
            } else if (java.util.Arrays.stream<Any?>(elems)
                    .allMatch { obj: Any? -> String::class.java.isInstance(obj) }
            ) {
                commonType = String::class.java
            } else {
                throw java.lang.IllegalArgumentException("Unsupported element types")
            }
            return Builder(
                NestedSetBuilder.wrap(Order.STABLE_ORDER, java.util.Arrays.< T > asList < T ? > (elems)), commonType
            )
                .setLocation(net.starlark.java.syntax.Location.BUILTIN)
        } else {
            return Builder(Tuple.of(*elems)).setLocation(net.starlark.java.syntax.Location.BUILTIN)
        }
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    private fun verifyStrippedCommandLine(commandLine: CommandLine?, vararg expected: String?) {
        verifyCommandLine(
            PathMappers.create(action, CoreOptions.OutputPathsMode.STRIP,  /* isStarlarkAction= */true),
            commandLine,
            expected
        )
    }

    private fun createFileset(relativePath: String?): SpecialArtifact {
        return createSpecialArtifact(relativePath, SpecialArtifactType.FILESET)
    }

    private fun createFilesetSymlink(relativePath: String?): FilesetOutputSymlink {
        return FilesetOutputSymlink(
            PathFragment.create(relativePath),
            ActionsTestUtil.createArtifact(derivedRoot, "some/target"),
            FileArtifactValue.createForNormalFile(byteArrayOf(1), null, 1)
        )
    }

    private fun createTreeArtifact(relativePath: String?): SpecialArtifact {
        val tree: SpecialArtifact = createSpecialArtifact(relativePath, SpecialArtifactType.TREE)
        tree.setGeneratingActionKey(ActionLookupData.create(ActionsTestUtil.NULL_ARTIFACT_OWNER, 0))
        return tree
    }

    private fun createSpecialArtifact(relativePath: String?, type: SpecialArtifactType?): SpecialArtifact {
        return SpecialArtifact.create(
            derivedRoot,
            derivedRoot.getExecPath().getRelative(relativePath),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            type
        )
    }

    companion object {
        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        private fun verifyCommandLine(commandLine: CommandLine?, vararg expected: String?) {
            verifyCommandLine(PathMapper.NOOP, commandLine, expected)
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        private fun verifyCommandLine(
            pathMapper: PathMapper?, commandLine: CommandLine, vararg expected: String?
        ) {
            val chunk: ArgChunk =
                commandLine.expand(com.google.devtools.build.lib.exec.util.FakeActionInputFileCache(), pathMapper)
            assertThat(chunk.arguments(pathMapper)).containsExactlyElementsIn(expected).inOrder()
            // Check consistency of the total argument length calculation with SimpleArgChunk, which
            // materializes strings and adds up their lengths.
            assertThat(chunk.totalArgLength(pathMapper))
                .isEqualTo(SimpleArgChunk(chunk.arguments(pathMapper)).totalArgLength(pathMapper))
        }

        @Throws(java.lang.Exception::class)
        private fun execStarlark(code: String?): Any? {
            Mutability.create("test").use { mutability ->
                val thread: StarlarkThread? = StarlarkThread.createTransient(mutability, StarlarkSemantics.DEFAULT)
                return Starlark.execFile(
                    net.starlark.java.syntax.ParserInput.fromString(code, "test/label.bzl"),
                    net.starlark.java.syntax.FileOptions.DEFAULT,
                    net.starlark.java.eval.Module.withPredeclaredAndData(
                        StarlarkSemantics.DEFAULT,
                        com.google.common.collect.ImmutableMap.of<String?, Any?>(),
                        BazelModuleContext.create(
                            BazelModuleKey.createFakeModuleKeyForTesting(
                                Label.parseCanonicalUnchecked("//test:label")
                            ),
                            RepositoryMapping.EMPTY,
                            "test/label.bzl",  /* loads= */
                            com.google.common.collect.ImmutableList.of<E?>(),  /* bzlTransitiveDigest= */
                            ByteArray(0),  /* docCommentsMap= */
                            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* unusedDocCommentLines= */
                            com.google.common.collect.ImmutableList.of<E?>()
                        )
                    ),
                    thread
                )
            }
        }
    }
}
