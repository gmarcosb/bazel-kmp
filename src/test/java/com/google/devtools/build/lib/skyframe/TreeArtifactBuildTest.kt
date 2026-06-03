// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Action
import com.google.devtools.build.lib.skyframe.TreeArtifactBuildTest.Companion.touchFile
import com.google.devtools.build.lib.skyframe.TreeArtifactBuildTest.Companion.writeFile

/** Timestamp builder tests for TreeArtifacts.  */
@RunWith(JUnit4::class)
class TreeArtifactBuildTest : TimestampBuilderTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun codec() {
        val parent: SpecialArtifact = createTreeArtifact("parent")
        parent.setGeneratingActionKey(ActionLookupData.create(TimestampBuilderTestCase.Companion.ACTION_LOOKUP_KEY, 0))
        SerializationTester(parent, TreeFileArtifact.createTreeOutput(parent, "child"))
            .addDependencies(getCommonSerializationDependencies())
            .addDependencies(SerializationDepsUtils.SERIALIZATION_DEPS_FOR_TEST)
            .runTests()
    }

    /** Simple smoke test. If this isn't passing, something is very wrong...  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeArtifactSimpleCase() {
        val parent: SpecialArtifact = createTreeArtifact("parent")
        val action: TouchingTestAction = TouchingTestAction(parent, "out1", "out2")
        registerAction<T?>(action)

        val result: TreeArtifactValue = buildArtifact(parent)

        verifyOutputTree(result, parent, "out1", "out2")
    }

    /** Simple test for the case with dependencies.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dependentTreeArtifacts() {
        val tree1: SpecialArtifact = createTreeArtifact("tree1")
        val action1: TouchingTestAction = TouchingTestAction(tree1, "out1", "out2")
        registerAction<T?>(action1)

        val tree2: SpecialArtifact = createTreeArtifact("tree2")
        val action2 = CopyTreeAction(tree1, tree2)
        registerAction<T?>(action2)

        val result: TreeArtifactValue = buildArtifact(tree2)

        assertThat(tree1.getPath().getRelative("out1").exists()).isTrue()
        assertThat(tree1.getPath().getRelative("out2").exists()).isTrue()
        verifyOutputTree(result, tree2, "out1", "out2")
    }

    /** Test for tree artifacts with sub directories.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeArtifactWithSubDirectory() {
        val parent: SpecialArtifact = createTreeArtifact("parent")
        val action: TouchingTestAction = TouchingTestAction(parent, "sub1/file1", "sub2/file2")
        registerAction<T?>(action)

        val result: TreeArtifactValue = buildArtifact(parent)

        verifyOutputTree(result, parent, "sub1/file1", "sub2/file2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun inputTreeArtifactMetadataProvider() {
        val treeArtifactInput: SpecialArtifact = createTreeArtifact("tree")
        val action1: TouchingTestAction = TouchingTestAction(treeArtifactInput, "out1", "out2")
        registerAction<T?>(action1)

        val normalOutput: Artifact = createDerivedArtifact("normal/out")
        val testAction: Action =
            object : SimpleTestAction(
                com.google.common.collect.ImmutableList.of<Artifact?>(treeArtifactInput),
                normalOutput
            ) {
                @Throws(IOException::class)
                override fun run(actionExecutionContext: ActionExecutionContext) {
                    // Check the metadata provider for input TreeFileArtifacts.
                    val inputMetadataProvider: InputMetadataProvider =
                        actionExecutionContext.getInputMetadataProvider()
                    assertThat(
                        inputMetadataProvider
                            .getInputMetadata(
                                TreeFileArtifact.createTreeOutput(treeArtifactInput, "out1")
                            )
                            .getType()
                            .isFile()
                    )
                        .isTrue()
                    assertThat(
                        inputMetadataProvider
                            .getInputMetadata(
                                TreeFileArtifact.createTreeOutput(treeArtifactInput, "out2")
                            )
                            .getType()
                            .isFile()
                    )
                        .isTrue()

                    // Touch the action output.
                    Companion.touchFile(normalOutput)
                }
            }

        registerAction<ActionAnalysisMetadata?>(testAction)
        buildArtifact(normalOutput)
    }

    /** Unchanged TreeArtifact outputs should not cause reexecution.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheCheckingForTreeArtifactsDoesNotCauseReexecution() {
        val out1: SpecialArtifact = createTreeArtifact("out1")
        val button1: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button =
            com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()

        val out2: SpecialArtifact = createTreeArtifact("out2")
        val button2: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button =
            com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()

        val action1: TouchingTestAction = TouchingTestAction(button1, out1, "file_one", "file_two")
        registerAction<T?>(action1)

        val action2 = CopyTreeAction(button2, out1, out2)
        registerAction<T?>(action2)

        button1.pressed = false
        button2.pressed = false
        buildArtifact(out2)
        Truth.assertThat(button1.pressed).isTrue() // built
        Truth.assertThat(button2.pressed).isTrue() // built

        button1.pressed = false
        button2.pressed = false
        buildArtifact(out2)
        Truth.assertThat(button1.pressed).isFalse() // not built
        Truth.assertThat(button2.pressed).isFalse() // not built
    }

    /** Test rebuilding TreeArtifacts for inputs, outputs, and dependents. Also a test for caching.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun transitiveReexecutionForTreeArtifacts() {
        val `in`: Artifact = createSourceArtifact("input")
        Companion.writeFile(`in`, "input content")

        val button1: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button =
            com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()
        val out1: SpecialArtifact = createTreeArtifact("output1")
        val action1 =
            WriteInputToFilesAction(button1, `in`, out1, "file1", "file2")
        registerAction<T?>(action1)

        val button2: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button =
            com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()
        val out2: SpecialArtifact = createTreeArtifact("output2")
        val action2 = CopyTreeAction(button2, out1, out2)
        registerAction<T?>(action2)

        button1.pressed = false
        button2.pressed = false
        buildArtifact(out2)
        Truth.assertThat(button1.pressed).isTrue() // built
        Truth.assertThat(button2.pressed).isTrue() // built

        button1.pressed = false
        button2.pressed = false
        Companion.writeFile(`in`, "modified input")
        buildArtifact(out2)
        Truth.assertThat(button1.pressed).isTrue() // built
        Truth.assertThat(button2.pressed).isTrue() // built

        button1.pressed = false
        button2.pressed = false
        writeFile(TreeFileArtifact.createTreeOutput(out1, "file1"), "modified output")
        buildArtifact(out2)
        Truth.assertThat(button1.pressed).isTrue() // built
        Truth.assertThat(button2.pressed).isFalse() // should have been cached

        button1.pressed = false
        button2.pressed = false
        writeFile(TreeFileArtifact.createTreeOutput(out2, "file1"), "more modified output")
        buildArtifact(out2)
        Truth.assertThat(button1.pressed).isFalse() // not built
        Truth.assertThat(button2.pressed).isTrue() // built
    }

    /** Tests that changing a TreeArtifact directory should cause reexeuction.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun directoryContentsCachingForTreeArtifacts() {
        val `in`: Artifact = createSourceArtifact("input")
        Companion.writeFile(`in`, "input content")

        val button1: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button =
            com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()
        val out1: SpecialArtifact = createTreeArtifact("output1")
        val action1 =
            WriteInputToFilesAction(button1, `in`, out1, "file1", "file2")
        registerAction<T?>(action1)

        val button2: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button =
            com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()
        val out2: SpecialArtifact = createTreeArtifact("output2")
        val action2 = CopyTreeAction(button2, out1, out2)
        registerAction<T?>(action2)

        button1.pressed = false
        button2.pressed = false
        buildArtifact(out2)
        // just a smoke test--if these aren't built we have bigger problems!
        Truth.assertThat(button1.pressed).isTrue()
        Truth.assertThat(button2.pressed).isTrue()

        // Adding a file to a directory should cause reexecution.
        button1.pressed = false
        button2.pressed = false
        val spuriousOutputOne: Path = out1.getPath().getRelative("spuriousOutput")
        Companion.touchFile(spuriousOutputOne)
        buildArtifact(out2)
        // Should re-execute, and delete spurious output
        assertThat(spuriousOutputOne.exists()).isFalse()
        Truth.assertThat(button1.pressed).isTrue()
        Truth.assertThat(button2.pressed).isFalse() // should have been cached

        button1.pressed = false
        button2.pressed = false
        val spuriousOutputTwo: Path = out2.getPath().getRelative("anotherSpuriousOutput")
        Companion.touchFile(spuriousOutputTwo)
        buildArtifact(out2)
        assertThat(spuriousOutputTwo.exists()).isFalse()
        Truth.assertThat(button1.pressed).isFalse()
        Truth.assertThat(button2.pressed).isTrue()

        // Deleting should cause reexecution.
        button1.pressed = false
        button2.pressed = false
        val out1File1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(out1, "file1")
        deleteFile(out1File1)
        buildArtifact(out2)
        assertThat(out1File1.getPath().exists()).isTrue()
        Truth.assertThat(button1.pressed).isTrue()
        Truth.assertThat(button2.pressed).isFalse() // should have been cached

        button1.pressed = false
        button2.pressed = false
        val out2File1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(out2, "file1")
        deleteFile(out2File1)
        buildArtifact(out2)
        assertThat(out2File1.getPath().exists()).isTrue()
        Truth.assertThat(button1.pressed).isFalse()
        Truth.assertThat(button2.pressed).isTrue()
    }

    /** TreeArtifacts don't care about mtime, even when the file is empty.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mTimeForTreeArtifactsDoesNotMatter() {
        // For this test, we only touch the input file.
        val `in`: Artifact = createSourceArtifact("touchable_input")
        Companion.touchFile(`in`)

        val button1: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button =
            com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()
        val out1: SpecialArtifact = createTreeArtifact("output1")
        val action1 =
            WriteInputToFilesAction(button1, `in`, out1, "file1", "file2")
        registerAction<T?>(action1)

        val button2: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button =
            com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button()
        val out2: SpecialArtifact = createTreeArtifact("output2")
        val action2 = CopyTreeAction(button2, out1, out2)
        registerAction<T?>(action2)

        button1.pressed = false
        button2.pressed = false
        buildArtifact(out2)
        Truth.assertThat(button1.pressed).isTrue() // built
        Truth.assertThat(button2.pressed).isTrue() // built

        button1.pressed = false
        button2.pressed = false
        Companion.touchFile(`in`)
        buildArtifact(out2)
        // mtime does not matter.
        Truth.assertThat(button1.pressed).isFalse()
        Truth.assertThat(button2.pressed).isFalse()

        // None of the below following should result in anything being built.
        button1.pressed = false
        button2.pressed = false
        touchFile(TreeFileArtifact.createTreeOutput(out1, "file1"))
        buildArtifact(out2)
        // Nothing should be built.
        Truth.assertThat(button1.pressed).isFalse()
        Truth.assertThat(button2.pressed).isFalse()

        button1.pressed = false
        button2.pressed = false
        touchFile(TreeFileArtifact.createTreeOutput(out1, "file2"))
        buildArtifact(out2)
        // Nothing should be built.
        Truth.assertThat(button1.pressed).isFalse()
        Truth.assertThat(button2.pressed).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputsAreReadOnlyAndExecutable() {
        val out: SpecialArtifact = createTreeArtifact("output")

        val action: Action =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(context: ActionExecutionContext?) {
                    writeFile(out.getPath().getChild("one"), "one")
                    writeFile(out.getPath().getChild("two"), "two")
                    writeFile(out.getPath().getChild("three").getChild("four"), "three/four")
                }
            }

        registerAction<ActionAnalysisMetadata?>(action)
        buildArtifact(out)

        checkDirectoryPermissions(out.getPath())
        checkFilePermissions(out.getPath().getChild("one"))
        checkFilePermissions(out.getPath().getChild("two"))
        checkDirectoryPermissions(out.getPath().getChild("three"))
        checkFilePermissions(out.getPath().getChild("three").getChild("four"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doesNotSetPermissionsAfterTraversingSymlink() {
        val out: SpecialArtifact = createTreeArtifact("output")

        val fileTarget: Path = scratch.file("file")
        Companion.writeFile(fileTarget, "file")

        val dirTarget: Path = scratch.dir("dir")
        val dirFileTarget: Path = dirTarget.getChild("file")
        Companion.writeFile(dirFileTarget, "dir/file")

        val action: Action =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(context: ActionExecutionContext?) {
                    out.getPath().getChild("file_link").createSymbolicLink(fileTarget.asFragment())
                    out.getPath().getChild("dir_link").createSymbolicLink(dirTarget.asFragment())
                }
            }

        registerAction<ActionAnalysisMetadata?>(action)
        buildArtifact(out)

        assertThat(fileTarget.isWritable()).isTrue()
        assertThat(dirTarget.isWritable()).isTrue()
        assertThat(dirFileTarget.isWritable()).isTrue()
    }

    @org.junit.Test
    fun symlinkLoopRejected() {
        // Failure expected
        val eventCollector: EventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.ERROR)
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        reporter.addHandler(eventCollector)

        val out: SpecialArtifact = createTreeArtifact("output")

        val action: Action =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(context: ActionExecutionContext?) {
                    writeFile(out.getPath().getRelative("dir/file"), "contents")
                    out.getPath().getRelative("dir/sym").createSymbolicLink(PathFragment.create("../dir"))
                }
            }

        registerAction<ActionAnalysisMetadata?>(action)
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildArtifact(out) })

        val errors: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> =
            com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.Event?>(eventCollector)
        Truth.assertThat(errors).hasSize(2)
        Truth.assertThat(errors.get(0).getMessage()).contains("Too many levels of symbolic links")
        Truth.assertThat(errors.get(1).getMessage()).contains("not all outputs were created or valid")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validAbsoluteSymlinkAccepted() {
        scratch.overwriteFile("/random/pointer")

        val out: SpecialArtifact = createTreeArtifact("output")

        val action: Action =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(actionExecutionContext: ActionExecutionContext?) {
                    writeFile(out.getPath().getChild("one"), "one")
                    writeFile(out.getPath().getChild("two"), "two")
                    FileSystemUtils.ensureSymbolicLink(
                        out.getPath().getChild("links").getChild("link"), "/random/pointer"
                    )
                }
            }

        registerAction<ActionAnalysisMetadata?>(action)
        buildArtifact(out)
    }

    @org.junit.Test
    fun danglingAbsoluteSymlinkRejected() {
        // Failure expected
        val eventCollector: EventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.ERROR)
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        reporter.addHandler(eventCollector)

        val out: SpecialArtifact = createTreeArtifact("output")

        val action: Action =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(actionExecutionContext: ActionExecutionContext?) {
                    writeFile(out.getPath().getChild("one"), "one")
                    writeFile(out.getPath().getChild("two"), "two")
                    FileSystemUtils.ensureSymbolicLink(
                        out.getPath().getChild("links").getChild("link"), "/random/pointer"
                    )
                }
            }

        registerAction<ActionAnalysisMetadata?>(action)
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildArtifact(out) })

        val errors: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> =
            com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.Event?>(eventCollector)
        Truth.assertThat(errors).hasSize(2)
        Truth.assertThat(errors.get(0).getMessage()).contains("child links/link is a dangling symbolic link")
        Truth.assertThat(errors.get(1).getMessage()).contains("not all outputs were created or valid")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validRelativeSymlinkAccepted() {
        val out: SpecialArtifact = createTreeArtifact("output")

        val action: Action =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(actionExecutionContext: ActionExecutionContext?) {
                    writeFile(out.getPath().getChild("one"), "one")
                    writeFile(out.getPath().getChild("two"), "two")
                    FileSystemUtils.ensureSymbolicLink(
                        out.getPath().getChild("links").getChild("link"), "../one"
                    )
                }
            }

        registerAction<ActionAnalysisMetadata?>(action)
        buildArtifact(out)
    }

    @org.junit.Test
    fun danglingRelativeSymlinkRejected() {
        // Failure expected
        val eventCollector: EventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.ERROR)
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        reporter.addHandler(eventCollector)

        val out: SpecialArtifact = createTreeArtifact("output")

        val action: Action =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(actionExecutionContext: ActionExecutionContext?) {
                    writeFile(out.getPath().getChild("one"), "one")
                    writeFile(out.getPath().getChild("two"), "two")
                    FileSystemUtils.ensureSymbolicLink(
                        out.getPath().getChild("links").getChild("link"), "../invalid"
                    )
                }
            }

        registerAction<ActionAnalysisMetadata?>(action)
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildArtifact(out) })

        val errors: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> =
            com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.Event?>(eventCollector)
        Truth.assertThat(errors).hasSize(2)
        Truth.assertThat(errors.get(0).getMessage()).contains("child links/link is a dangling symbolic link")
        Truth.assertThat(errors.get(1).getMessage()).contains("not all outputs were created or valid")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun validRelativeSymlinkToOutsideOfTreeArtifactAccepted() {
        val out: SpecialArtifact = createTreeArtifact("output")

        scratch.file(out.getPath().getRelative("../some/file").getPathString())

        val action: TestAction =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(actionExecutionContext: ActionExecutionContext?) {
                    writeFile(out.getPath().getChild("one"), "one")
                    writeFile(out.getPath().getChild("two"), "two")
                    FileSystemUtils.ensureSymbolicLink(
                        out.getPath().getChild("links").getChild("link"), "../../some/file"
                    )
                }
            }

        registerAction<T?>(action)
        buildArtifact(out)
    }

    @org.junit.Test
    fun danglingRelativeSymlinkOutsideOfTreeArtifactRejected() {
        // Failure expected
        val eventCollector: EventCollector = EventCollector(com.google.devtools.build.lib.events.EventKind.ERROR)
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        reporter.addHandler(eventCollector)

        val out: SpecialArtifact = createTreeArtifact("output")

        val action: Action =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(actionExecutionContext: ActionExecutionContext?) {
                    writeFile(out.getPath().getChild("one"), "one")
                    writeFile(out.getPath().getChild("two"), "two")
                    FileSystemUtils.ensureSymbolicLink(
                        out.getPath().getChild("links").getChild("link"), "../../some/file"
                    )
                }
            }

        registerAction<ActionAnalysisMetadata?>(action)
        org.junit.Assert.assertThrows<T?>(
            BuildFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildArtifact(out) })

        val errors: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> =
            com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.Event?>(eventCollector)
        Truth.assertThat(errors).hasSize(2)
        Truth.assertThat(errors.get(0).getMessage()).contains("child links/link is a dangling symbolic link")
        Truth.assertThat(errors.get(1).getMessage()).contains("not all outputs were created or valid")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteDirectoryInjection() {
        val out: SpecialArtifact = createTreeArtifact("output")
        val remoteFile1: FileArtifactValue? =
            FileArtifactValue.createForRemoteFile(
                com.google.common.hash.Hashing.sha256().hashString("one", java.nio.charset.StandardCharsets.UTF_8)
                    .asBytes(),  /* size= */
                3,  /* locationIndex= */
                1
            )
        val remoteFile2: FileArtifactValue? =
            FileArtifactValue.createForRemoteFile(
                com.google.common.hash.Hashing.sha256().hashString("two", java.nio.charset.StandardCharsets.UTF_8)
                    .asBytes(),  /* size= */
                3,  /* locationIndex= */
                2
            )

        val action: Action =
            object : SimpleTestAction(out) {
                @Throws(IOException::class)
                override fun run(actionExecutionContext: ActionExecutionContext) {
                    val child1: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(out, "one")
                    val child2: TreeFileArtifact? = TreeFileArtifact.createTreeOutput(out, "two")
                    writeFile(child1, "one")
                    writeFile(child2, "two")

                    actionExecutionContext
                        .getOutputMetadataStore()
                        .injectTree(
                            out,
                            TreeArtifactValue.newBuilder(out)
                                .putChild(child1, remoteFile1)
                                .putChild(child2, remoteFile2)
                                .build()
                        )
                }
            }

        registerAction<ActionAnalysisMetadata?>(action)
        val result: TreeArtifactValue = buildArtifact(out)

        assertThat(result.getChildValues())
            .containsExactly(
                TreeFileArtifact.createTreeOutput(out, "one"),
                remoteFile1,
                TreeFileArtifact.createTreeOutput(out, "two"),
                remoteFile2
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expandedActionsBuildInActionTemplate() {
        // artifact1 is a tree artifact generated by a TouchingTestAction.
        val artifact1: SpecialArtifact = createTreeArtifact("treeArtifact1")
        registerAction<T?>(TouchingTestAction(artifact1, "file1", "file2"))

        // artifact2 is a tree artifact generated by an action template.
        val artifact2: SpecialArtifact = createTreeArtifact("treeArtifact2")
        val actionTemplate: SpawnActionTemplate? =
            ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2)
        registerAction<ActionAnalysisMetadata?>(actionTemplate)

        // We mock out the action template function to expand into two actions that just touch the
        // output files.
        val secondOwner: ActionTemplateExpansionKey? =
            ActionTemplateExpansionValue.key(TimestampBuilderTestCase.Companion.ACTION_LOOKUP_KEY, 1)
        val expectedExpansionOutput1: TreeFileArtifact? =
            TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child1", secondOwner)
        val expectedExpansionOutput2: TreeFileArtifact? =
            TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child2", secondOwner)
        val expandedAction1: Action =
            DummyAction(
                TreeFileArtifact.createTreeOutput(artifact1, "file1"), expectedExpansionOutput1
            )
        val expandedAction2: Action =
            DummyAction(
                TreeFileArtifact.createTreeOutput(artifact1, "file2"), expectedExpansionOutput2
            )

        actionTemplateExpansionFunction =
            DummyActionTemplateExpansionFunction(
                actionKeyContext,
                com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>(expandedAction1, expandedAction2)
            )

        val result: TreeArtifactValue = buildArtifact(artifact2)

        assertThat(result.getChildren())
            .containsExactly(expectedExpansionOutput1, expectedExpansionOutput2)
    }

    @org.junit.Test
    fun expandedActionDoesNotGenerateOutputInActionTemplate() {
        // expect errors
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        // artifact1 is a tree artifact generated by a TouchingTestAction.
        val artifact1: SpecialArtifact = createTreeArtifact("treeArtifact1")
        registerAction<T?>(TouchingTestAction(artifact1, "child1", "child2"))

        // artifact2 is a tree artifact generated by an action template.
        val artifact2: SpecialArtifact = createTreeArtifact("treeArtifact2")
        val actionTemplate: SpawnActionTemplate? =
            ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2)
        registerAction<ActionAnalysisMetadata?>(actionTemplate)

        // We mock out the action template function to expand into two actions:
        // One Action that touches the output file.
        // The other action that does not generate the output file.
        val secondOwner: ActionTemplateExpansionKey? =
            ActionTemplateExpansionKey.of(TimestampBuilderTestCase.Companion.ACTION_LOOKUP_KEY, 1)
        val expectedExpansionOutput1: TreeFileArtifact? =
            TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child1", secondOwner)
        val expectedExpansionOutput2: TreeFileArtifact =
            TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child2", secondOwner)
        val generateOutputAction: Action =
            DummyAction(
                TreeFileArtifact.createTreeOutput(artifact1, "child1"), expectedExpansionOutput1
            )
        val noGenerateOutputAction: Action =
            NoOpDummyAction(
                TreeFileArtifact.createTreeOutput(artifact1, "child2"), expectedExpansionOutput2
            )

        actionTemplateExpansionFunction =
            DummyActionTemplateExpansionFunction(
                actionKeyContext,
                com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>(
                    generateOutputAction,
                    noGenerateOutputAction
                )
            )

        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifact(artifact2) })
        assertThat(e).hasMessageThat().contains("not all outputs were created or valid")
    }

    @org.junit.Test
    fun oneExpandedActionThrowsInActionTemplate() {
        // expect errors
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        // artifact1 is a tree artifact generated by a TouchingTestAction.
        val artifact1: SpecialArtifact = createTreeArtifact("treeArtifact1")
        registerAction<T?>(TouchingTestAction(artifact1, "child1", "child2"))

        // artifact2 is a tree artifact generated by an action template.
        val artifact2: SpecialArtifact = createTreeArtifact("treeArtifact2")
        val actionTemplate: SpawnActionTemplate? =
            ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2)
        registerAction<ActionAnalysisMetadata?>(actionTemplate)

        // We mock out the action template function to expand into two actions:
        // One Action that touches the output file.
        // The other action that just throws when executed.
        val secondOwner: ActionTemplateExpansionKey? =
            ActionTemplateExpansionKey.of(TimestampBuilderTestCase.Companion.ACTION_LOOKUP_KEY, 1)
        val expectedExpansionOutput1: TreeFileArtifact? =
            TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child1", secondOwner)
        val expectedExpansionOutput2: TreeFileArtifact =
            TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child2", secondOwner)
        val generateOutputAction: Action =
            DummyAction(
                TreeFileArtifact.createTreeOutput(artifact1, "child1"), expectedExpansionOutput1
            )
        val throwingAction: Action =
            ThrowingDummyAction(
                TreeFileArtifact.createTreeOutput(artifact1, "child2"), expectedExpansionOutput2
            )

        actionTemplateExpansionFunction =
            DummyActionTemplateExpansionFunction(
                actionKeyContext,
                com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>(
                    generateOutputAction,
                    throwingAction
                )
            )

        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifact(artifact2) })
        assertThat(e).hasMessageThat().contains("Throwing dummy action")
    }

    @org.junit.Test
    fun allExpandedActionsThrowInActionTemplate() {
        // expect errors
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        // artifact1 is a tree artifact generated by a TouchingTestAction.
        val artifact1: SpecialArtifact = createTreeArtifact("treeArtifact1")
        registerAction<T?>(TouchingTestAction(artifact1, "child1", "child2"))

        // artifact2 is a tree artifact generated by an action template.
        val artifact2: SpecialArtifact = createTreeArtifact("treeArtifact2")
        val actionTemplate: SpawnActionTemplate? =
            ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2)
        registerAction<ActionAnalysisMetadata?>(actionTemplate)

        // We mock out the action template function to expand into two actions that throw when executed.
        val secondOwner: ActionTemplateExpansionKey? =
            ActionTemplateExpansionKey.of(TimestampBuilderTestCase.Companion.ACTION_LOOKUP_KEY, 1)
        val expectedExpansionOutput1: TreeFileArtifact =
            TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child1", secondOwner)
        val expectedExpansionOutput2: TreeFileArtifact =
            TreeFileArtifact.createTemplateExpansionOutput(artifact2, "child2", secondOwner)
        val throwingAction: Action =
            ThrowingDummyAction(
                TreeFileArtifact.createTreeOutput(artifact1, "child1"), expectedExpansionOutput1
            )
        val anotherThrowingAction: Action =
            ThrowingDummyAction(
                TreeFileArtifact.createTreeOutput(artifact1, "child2"), expectedExpansionOutput2
            )

        actionTemplateExpansionFunction =
            DummyActionTemplateExpansionFunction(
                actionKeyContext,
                com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>(
                    throwingAction,
                    anotherThrowingAction
                )
            )

        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifact(artifact2) })
        assertThat(e).hasMessageThat().contains("Throwing dummy action")
    }

    @org.junit.Test
    fun inputTreeArtifactCreationFailedInActionTemplate() {
        // expect errors
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        // artifact1 is created by a action that throws.
        val artifact1: SpecialArtifact = createTreeArtifact("treeArtifact1")
        registerAction<T?>(ThrowingDummyAction(artifact1))

        // artifact2 is a tree artifact generated by an action template.
        val artifact2: SpecialArtifact = createTreeArtifact("treeArtifact2")
        val actionTemplate: SpawnActionTemplate? =
            ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2)
        registerAction<ActionAnalysisMetadata?>(actionTemplate)

        val e: BuildFailedException? =
            org.junit.Assert.assertThrows<T?>(
                BuildFailedException::class.java,
                org.junit.function.ThrowingRunnable { buildArtifact(artifact2) })
        assertThat(e).hasMessageThat().contains("Throwing dummy action")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyInputAndOutputTreeArtifactInActionTemplate() {
        // artifact1 is an empty tree artifact which is generated by a single no-op dummy action.
        val artifact1: SpecialArtifact = createTreeArtifact("treeArtifact1")
        registerAction<T?>(NoOpDummyAction(artifact1))

        // artifact2 is a tree artifact generated by an action template that takes artifact1 as input.
        val artifact2: SpecialArtifact = createTreeArtifact("treeArtifact2")
        val actionTemplate: SpawnActionTemplate? =
            ActionsTestUtil.createDummySpawnActionTemplate(artifact1, artifact2)
        registerAction<ActionAnalysisMetadata?>(actionTemplate)

        buildArtifact(artifact2)

        assertThat(artifact1.getPath().exists()).isTrue()
        assertThat(artifact1.getPath().getDirectoryEntries()).isEmpty()
        assertThat(artifact2.getPath().exists()).isTrue()
        assertThat(artifact2.getPath().getDirectoryEntries()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeArtifactWithSymlinkToFile() {
        val treeArtifact: SpecialArtifact = createTreeArtifact("tree")
        registerAction<T?>(
            object : SimpleTestAction( /* output= */treeArtifact) {
                @Throws(IOException::class)
                override fun run(context: ActionExecutionContext?) {
                    touchFile(treeArtifact.getPath().getRelative("subdir/file"))
                    treeArtifact
                        .getPath()
                        .getRelative("link")
                        .createSymbolicLink(PathFragment.create("subdir/file"))
                }
            })

        val tree: TreeArtifactValue = buildArtifact(treeArtifact)

        assertThat(tree.getChildren())
            .containsExactly(
                TreeFileArtifact.createTreeOutput(treeArtifact, "subdir/file"),
                TreeFileArtifact.createTreeOutput(treeArtifact, "link")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun treeArtifactWithSymlinkToDirectory() {
        val treeArtifact: SpecialArtifact = createTreeArtifact("tree")
        registerAction<T?>(
            object : SimpleTestAction( /* output= */treeArtifact) {
                @Throws(IOException::class)
                override fun run(context: ActionExecutionContext?) {
                    touchFile(treeArtifact.getPath().getRelative("subdir/file"))
                    treeArtifact
                        .getPath()
                        .getRelative("link")
                        .createSymbolicLink(PathFragment.create("subdir"))
                }
            })

        val tree: TreeArtifactValue = buildArtifact(treeArtifact)

        assertThat(tree.getChildren())
            .containsExactly(
                TreeFileArtifact.createTreeOutput(treeArtifact, "subdir/file"),
                TreeFileArtifact.createTreeOutput(treeArtifact, "link/file")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun symlinkActionToTreeArtifact() {
        val tree1: SpecialArtifact = createTreeArtifact("tree1")
        registerAction<T?>(
            object : SimpleTestAction( /* output= */tree1) {
                @Throws(IOException::class)
                override fun run(context: ActionExecutionContext?) {
                    touchFile(tree1.getPath().getChild("file"))
                }
            })

        val tree2: SpecialArtifact = createTreeArtifact("tree2")
        registerAction<T?>(
            SymlinkAction.toArtifact(
                ActionsTestUtil.NULL_ACTION_OWNER, tree1, tree2, "Symlinking tree2 -> tree1"
            )
        )

        // The SymlinkAction should produce a TreeArtifactValue with tree2 as the parent.
        val tree2Value: TreeArtifactValue = buildArtifact(tree2)
        assertThat(tree2Value.getChildren())
            .containsExactly(TreeFileArtifact.createTreeOutput(tree2, "file"))
    }

    private abstract class SimpleTestAction(
        button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button,
        inputs: Iterable<Artifact?>?,
        output: Artifact
    ) : TestAction(
        TestAction.Companion.NO_EFFECT,
        NestedSetBuilder.wrap(Order.STABLE_ORDER, inputs),
        com.google.common.collect.ImmutableSet.of<E?>(output)
    ) {
        private val button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button

        internal constructor(output: Artifact) : this( /* inputs= */com.google.common.collect.ImmutableList.of<Artifact?>(),
            output
        )

        internal constructor(
            inputs: Iterable<Artifact?>?,
            output: Artifact
        ) : this(com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button(), inputs, output)

        init {
            this.button = button
        }

        @Throws(ActionExecutionException::class)
        override fun execute(context: ActionExecutionContext?): ActionResult {
            button.pressed = true
            try {
                run(context)
            } catch (e: IOException) {
                throw ActionExecutionException(
                    e, this,  /*catastrophe=*/false, CrashFailureDetails.detailedExitCodeForThrowable(e)
                )
            }
            return ActionResult.EMPTY
        }

        @Throws(IOException::class)
        abstract fun run(context: ActionExecutionContext?)
    }

    /** An action that touches some output TreeFileArtifacts. Takes no inputs.  */
    private class TouchingTestAction(
        button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button,
        output: SpecialArtifact,
        vararg outputFiles: String?
    ) : SimpleTestAction(button,  /*inputs=*/com.google.common.collect.ImmutableList.of<Artifact?>(), output) {
        private val outputFiles: com.google.common.collect.ImmutableList<String?>

        internal constructor(
            output: SpecialArtifact,
            vararg outputFiles: String?
        ) : this(com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button(), output, *outputFiles)

        init {
            this.outputFiles = com.google.common.collect.ImmutableList.copyOf<String?>(outputFiles)
        }

        @Throws(IOException::class)
        override fun run(context: ActionExecutionContext?) {
            for (file in outputFiles) {
                touchFile(getPrimaryOutput().getPath().getRelative(file))
            }
        }
    }

    /** Takes an input file and populates several copies inside a TreeArtifact.  */
    private class WriteInputToFilesAction(
        button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button,
        input: Artifact,
        output: SpecialArtifact,
        vararg outputFiles: String?
    ) : SimpleTestAction(button, com.google.common.collect.ImmutableList.of<Artifact?>(input), output) {
        private val outputFiles: com.google.common.collect.ImmutableList<String?>

        init {
            this.outputFiles = com.google.common.collect.ImmutableList.copyOf<String?>(outputFiles)
        }

        @Throws(IOException::class)
        override fun run(actionExecutionContext: ActionExecutionContext?) {
            for (file in outputFiles) {
                val newOutput: Path = getPrimaryOutput().getPath().getRelative(file)
                newOutput.createDirectoryAndParents()
                FileSystemUtils.copyFile(getPrimaryInput().getPath(), newOutput)
            }
        }
    }

    /** Copies the given TreeFileArtifact inputs to the given outputs, in respective order.  */
    private class CopyTreeAction(
        button: com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button,
        input: SpecialArtifact,
        output: SpecialArtifact
    ) : SimpleTestAction(button, com.google.common.collect.ImmutableList.of<Artifact?>(input), output) {
        internal constructor(
            input: SpecialArtifact,
            output: SpecialArtifact
        ) : this(com.google.devtools.build.lib.skyframe.TimestampBuilderTestCase.Button(), input, output)

        @Throws(IOException::class)
        override fun run(context: ActionExecutionContext) {
            for (child in context.getInputMetadataProvider().getTreeMetadata(getPrimaryInput()).getChildren()) {
                val newOutput: Path = getPrimaryOutput().getPath().getRelative(child.getParentRelativePath())
                newOutput.createDirectoryAndParents()
                FileSystemUtils.copyFile(child.getPath(), newOutput)
            }
        }
    }

    private fun createTreeArtifact(name: String?): SpecialArtifact {
        val fs: FileSystem = scratch.getFileSystem()
        val execRoot: Path? =
            fs.getPath(com.google.devtools.build.lib.testutil.TestUtils.tmpDir()).getRelative("execroot")
                .getRelative("default-exec-root")
        val execPath: PathFragment? = PathFragment.create("out").getRelative(name)
        return SpecialArtifact.create(
            ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "out"),
            execPath,
            TimestampBuilderTestCase.Companion.ACTION_LOOKUP_KEY,
            SpecialArtifactType.TREE
        )
    }

    @Throws(java.lang.Exception::class)
    private fun buildArtifact(treeArtifact: SpecialArtifact): TreeArtifactValue {
        com.google.common.base.Preconditions.checkArgument(treeArtifact.isTreeArtifact(), treeArtifact)
        val builder: BuilderWithResult = cachingBuilder()
        buildArtifacts(builder, treeArtifact)
        return builder.getLatestResult().get(treeArtifact) as TreeArtifactValue
    }

    @Throws(java.lang.Exception::class)
    private fun buildArtifact(normalArtifact: Artifact?) {
        buildArtifacts(cachingBuilder(), normalArtifact)
    }

    /** A dummy action template expansion function that just returns the injected actions.  */
    private class DummyActionTemplateExpansionFunction(
        actionKeyContext: ActionKeyContext?,
        actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?
    ) : SkyFunction {
        private val actionKeyContext: ActionKeyContext?
        private val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?

        init {
            this.actionKeyContext = actionKeyContext
            this.actions = actions
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun compute(skyKey: SkyKey?, env: Environment?): SkyValue {
            try {
                Actions.assignOwnersAndThrowIfConflictToleratingSharedActions(
                    actionKeyContext, actions, skyKey as ActionLookupKey?
                )
            } catch (e: ActionConflictException) {
                throw java.lang.IllegalStateException(e)
            } catch (e: Actions.ArtifactGeneratedByOtherRuleException) {
                throw java.lang.IllegalStateException(e)
            }
            return ActionTemplateExpansionValue(actions)
        }
    }

    /** No-op action that does not generate the action outputs.  */
    private class NoOpDummyAction : SimpleTestAction {
        internal constructor(output: Artifact) : super( /*inputs=*/com.google.common.collect.ImmutableList.of<Artifact?>(),
            output
        )

        internal constructor(
            input: Artifact,
            output: Artifact
        ) : super(com.google.common.collect.ImmutableList.of<Artifact?>(input), output)

        /** Does nothing.  */
        override fun run(actionExecutionContext: ActionExecutionContext?) {}
    }

    /** No-op action that throws when executed.  */
    private class ThrowingDummyAction : TestAction {
        internal constructor(output: Artifact) : super(
            TestAction.Companion.NO_EFFECT,
            NestedSetBuilder.emptySet(Order.STABLE_ORDER),
            com.google.common.collect.ImmutableSet.of<E?>(output)
        )

        internal constructor(input: Artifact?, output: Artifact) : super(
            TestAction.Companion.NO_EFFECT,
            NestedSetBuilder.create(Order.STABLE_ORDER, input),
            com.google.common.collect.ImmutableSet.of<E?>(output)
        )

        /** Unconditionally throws.  */
        @Throws(ActionExecutionException::class)
        override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult {
            val code: DetailedExitCode? =
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setCrash(Crash.newBuilder().setCode(Code.CRASH_UNKNOWN))
                        .build()
                )
            throw ActionExecutionException(
                "Throwing dummy action", this,  /*catastrophe=*/true, code
            )
        }
    }

    companion object {
        @Throws(IOException::class)
        private fun checkDirectoryPermissions(path: Path) {
            assertThat(path.isDirectory()).isTrue()
            assertThat(path.isExecutable()).isTrue()
            assertThat(path.isReadable()).isTrue()
            assertThat(path.isWritable()).isFalse()
        }

        @Throws(IOException::class)
        private fun checkFilePermissions(path: Path) {
            assertThat(path.isDirectory()).isFalse()
            assertThat(path.isExecutable()).isTrue()
            assertThat(path.isReadable()).isTrue()
            assertThat(path.isWritable()).isFalse()
        }

        private fun verifyOutputTree(
            result: TreeArtifactValue, parent: SpecialArtifact, vararg expectedChildPaths: String?
        ) {
            com.google.common.base.Preconditions.checkArgument(parent.isTreeArtifact(), parent)
            val expectedChildren: MutableSet<TreeFileArtifact> =
                java.util.Arrays.stream<String?>(expectedChildPaths)
                    .map<Any?> { path: String? -> TreeFileArtifact.createTreeOutput(parent, path) }
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
            for (child in expectedChildren) {
                Truth.assertWithMessage("%s does not exist", child).that(child.getPath().exists()).isTrue()
            }
            assertThat(result.getChildren()).isEqualTo(expectedChildren)
        }

        @Throws(IOException::class)
        private fun writeFile(path: Path, contents: String?) {
            path.getParentDirectory().createDirectoryAndParents()
            // sometimes we write read-only files
            if (path.exists()) {
                path.setWritable(true)
            }
            FileSystemUtils.writeContentAsLatin1(path, contents)
        }

        @Throws(IOException::class)
        private fun writeFile(file: Artifact, contents: String?) {
            writeFile(file.getPath(), contents)
        }

        @Throws(IOException::class)
        private fun touchFile(path: Path) {
            path.getParentDirectory().createDirectoryAndParents()
            path.getParentDirectory().setWritable(true)
            FileSystemUtils.touchFile(path)
        }

        @Throws(IOException::class)
        private fun touchFile(file: Artifact) {
            touchFile(file.getPath())
        }

        @Throws(IOException::class)
        private fun deleteFile(file: Artifact) {
            val path: Path = file.getPath()
            // sometimes we write read-only files
            path.setWritable(true)
            // work around the sticky bit (this might depend on the behavior of the OS?)
            path.getParentDirectory().setWritable(true)
            path.delete()
        }
    }
}
