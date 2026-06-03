// Copyright 2023 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec

import com.github.luben.zstd.ZstdInputStream

/** Tests for [CompactSpawnLogContext].  */
@RunWith(TestParameterInjector::class)
class CompactSpawnLogContextTest : SpawnLogContextTestBase() {
    private val logPath: Path = fs.getPath("/log")

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveNestedSet(@TestParameter inputsMode: InputsMode) {
        val file1: Artifact = ActionsTestUtil.createArtifact(rootDir, "file1")
        val file2: Artifact = ActionsTestUtil.createArtifact(rootDir, "file2")
        val file3: Artifact = ActionsTestUtil.createArtifact(rootDir, "file3")

        SpawnLogContextTestBase.Companion.writeFile(file1, "abc")
        SpawnLogContextTestBase.Companion.writeFile(file2, "def")
        SpawnLogContextTestBase.Companion.writeFile(file3, "ghi")

        val inputs: NestedSet<ActionInput?> =
            NestedSetBuilder.< ActionInput > stableOrder < ActionInput ? > ()
                .add(file1)
                .addTransitive(
                    NestedSetBuilder.< ActionInput > stableOrder < ActionInput ? > ().add(file2).add(file3).build()
                )
                .build()

        assertThat(inputs.getLeaves()).hasSize(1)
        assertThat(inputs.getNonLeaves()).hasSize(1)

        var spawn: SpawnBuilder = SpawnLogContextTestBase.Companion.defaultSpawnBuilder().withInputs(inputs)
        if (inputsMode.isTool()) {
            spawn = spawn.withTools(inputs)
        }

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(file1, file2, file3),
            createInputMap(file1, file2, file3),
            fs,
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            SpawnLogContextTestBase.Companion.defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath("file1")
                        .setDigest(getDigest("abc"))
                        .setIsTool(inputsMode.isTool())
                )
                .addInputs(
                    File.newBuilder()
                        .setPath("file2")
                        .setDigest(getDigest("def"))
                        .setIsTool(inputsMode.isTool())
                )
                .addInputs(
                    File.newBuilder()
                        .setPath("file3")
                        .setDigest(getDigest("ghi"))
                        .setIsTool(inputsMode.isTool())
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun testSymlinkAction() {
        val source: Artifact? = ActionsTestUtil.createArtifact(rootDir, "source")
        val target: Artifact? = ActionsTestUtil.createArtifact(rootDir, "target")
        val owner: ActionOwner? =
            ActionOwner.createDummy(
                Label.parseCanonicalUnchecked("//pkg:symlink"),
                net.starlark.java.syntax.Location("dummy-file", 0, 0),
                "some_rule",
                "configurationMnemonic",  /* configurationChecksum= */
                "configurationChecksum",
                BuildConfigurationEvent(
                    BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                    BuildEventStreamProtos.BuildEvent.getDefaultInstance()
                ),  /* isToolConfiguration= */
                false,  /* executionPlatform= */
                null,  /* aspectDescriptors= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* execProperties= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        val symlinkAction: SymlinkAction? =
            SymlinkAction.toArtifact(owner, source, target, "Creating symlink")

        val context: SpawnLogContext = createSpawnLogContext()
        context.logSymlinkAction(symlinkAction)

        val entries: com.google.common.collect.ImmutableList<Protos.ExecLogEntry?> = closeAndReadCompactLog(context)
        Truth.assertThat(entries)
            .containsExactly(
                Protos.ExecLogEntry.newBuilder()
                    .setInvocation(
                        Protos.ExecLogEntry.Invocation.newBuilder()
                            .setHashFunctionName("SHA-256")
                            .setWorkspaceRunfilesDirectory(TestConstants.WORKSPACE_NAME)
                            .setSiblingRepositoryLayout(siblingRepositoryLayout)
                            .setId("00000000-0000-0000-0000-000000000000")
                    )
                    .build(),
                Protos.ExecLogEntry.newBuilder()
                    .setSymlinkAction(
                        Protos.ExecLogEntry.SymlinkAction.newBuilder()
                            .setInputPath("source")
                            .setOutputPath("target")
                            .setMnemonic("Symlink")
                            .setTargetLabel("//pkg:symlink")
                    )
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesTreeReusedForTool() {
        val tool: Artifact = ActionsTestUtil.createArtifact(rootDir, "data.txt")
        SpawnLogContextTestBase.Companion.writeFile(tool, "abc")
        val toolRunfiles: Artifact = ActionsTestUtil.createRunfilesArtifact(outputDir, "tool.runfiles")

        val runfilesRoot: PathFragment? = outputDir.getExecPath().getRelative("foo.runfiles")
        val runfilesTree: RunfilesTree? = SpawnLogContextTestBase.Companion.createRunfilesTree(runfilesRoot, tool)

        val firstInput: Artifact = ActionsTestUtil.createArtifact(rootDir, "first_input")
        SpawnLogContextTestBase.Companion.writeFile(firstInput, "def")
        val secondInput: Artifact = ActionsTestUtil.createArtifact(rootDir, "second_input")
        SpawnLogContextTestBase.Companion.writeFile(secondInput, "ghi")

        val firstSpawn: Spawn =
            SpawnLogContextTestBase.Companion.defaultSpawnBuilder().withTool(toolRunfiles)
                .withInputs(firstInput, toolRunfiles).build()
        val secondSpawn: Spawn =
            SpawnLogContextTestBase.Companion.defaultSpawnBuilder().withTool(toolRunfiles)
                .withInputs(secondInput, toolRunfiles).build()

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            firstSpawn,
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(runfilesTree, toolRunfiles, firstInput),
            createInputMap(runfilesTree, firstInput),
            fs,
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )
        context.logSpawn(
            secondSpawn,
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(runfilesTree, toolRunfiles, secondInput),
            createInputMap(runfilesTree, secondInput),
            fs,
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )

        val entries: com.google.common.collect.ImmutableList<Protos.ExecLogEntry?> = closeAndReadCompactLog(context)
        Truth.assertThat(entries.stream().filter(Protos.ExecLogEntry::hasRunfilesTree)).hasSize(1)

        closeAndAssertLog(
            context,
            SpawnLogContextTestBase.Companion.defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/data.txt")
                        )
                        .setDigest(getDigest("abc"))
                        .setIsTool(true)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath("first_input")
                        .setDigest(getDigest("def"))
                        .setIsTool(false)
                )
                .build(),
            SpawnLogContextTestBase.Companion.defaultSpawnExecBuilder()
                .addInputs(
                    File.newBuilder()
                        .setPath(
                            (TestConstants.PRODUCT_NAME
                                    + "-out/k8-fastbuild/bin/foo.runfiles/"
                                    + TestConstants.WORKSPACE_NAME
                                    + "/data.txt")
                        )
                        .setDigest(getDigest("abc"))
                        .setIsTool(true)
                )
                .addInputs(
                    File.newBuilder()
                        .setPath("second_input")
                        .setDigest(getDigest("ghi"))
                        .setIsTool(false)
                )
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnreadableOutputs(@TestParameter outputsMode: OutputsMode) {
        val readableFile: Artifact = ActionsTestUtil.createArtifact(outputDir, "readable")
        val unreadableFile: Artifact = ActionsTestUtil.createArtifact(outputDir, "unreadable")
        val unreadableFileDir: Artifact =
            ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "unreadableFileDir")

        writeFile(readableFile.getPath(), "xyz")
        // Make the files unreadable.
        writeFile(unreadableFile.getPath(), "abc")
        unreadableFile.getPath().setReadable(false)
        writeFile(unreadableFileDir.getPath().getChild("file"), "def")
        unreadableFileDir.getPath().getChild("file").setReadable(false)

        val spawn: SpawnBuilder =
            SpawnLogContextTestBase.Companion.defaultSpawnBuilder()
                .withOutputs(readableFile, unreadableFile, unreadableFileDir)

        val context: SpawnLogContext = createSpawnLogContext()

        context.logSpawn(
            spawn.build(),
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(),
            SpawnLogContextTestBase.Companion.createInputMap(),
            outputsMode.getActionFileSystem(fs),
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            SpawnLogContextTestBase.Companion.defaultSpawnExecBuilder()
                .addActualOutputs(
                    File.newBuilder()
                        .setPath(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/readable")
                        .setDigest(getDigest("xyz"))
                        .setIsTool(false)
                )
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/readable")
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/unreadable")
                .addListedOutputs(TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/unreadableFileDir")
                .build()
        )

        assertContainsEvent(
            storedEventHandler.getEvents(),
            "The compact execution log is incomplete because some outputs could not be read. Refer"
                    + " to the server log file for details."
        )
        Truth.assertThat(storedEventHandler.getEvents()).hasSize(1)
        Truth.assertThat(storedEventHandler.getPosts()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMnemonicFilter() {
        val spawn1: SpawnBuilder = SpawnLogContextTestBase.Companion.defaultSpawnBuilder().withMnemonic("Mnemonic1")
        val spawn2: SpawnBuilder = SpawnLogContextTestBase.Companion.defaultSpawnBuilder().withMnemonic("Mnemonic2")

        val context: SpawnLogContext =
            createSpawnLogContext(java.util.function.Predicate { spawn: Spawn? ->
                spawn.getMnemonic().equals("Mnemonic1")
            })

        context.logSpawn(
            spawn1.build(),
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(),
            SpawnLogContextTestBase.Companion.createInputMap(),
            fs,
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )
        context.logSpawn(
            spawn2.build(),
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(),
            SpawnLogContextTestBase.Companion.createInputMap(),
            fs,
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )

        closeAndAssertLog(
            context,
            SpawnLogContextTestBase.Companion.defaultSpawnExecBuilder().setMnemonic("Mnemonic1").build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStreaming() {
        val file: Artifact = ActionsTestUtil.createArtifact(rootDir, "file")
        SpawnLogContextTestBase.Companion.writeFile(file, "abc")

        val spawn: SpawnBuilder = SpawnLogContextTestBase.Companion.defaultSpawnBuilder().withInput(file)

        val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val out: BufferedOutputStream = BufferedOutputStream(baos)

        val context: SpawnLogContext =
            CompactSpawnLogContext(
                out,
                "stream",
                execRoot.asFragment(),
                TestConstants.WORKSPACE_NAME,
                siblingRepositoryLayout,
                com.google.devtools.common.options.Options.getDefaults<O?>(RemoteOptions::class.java),
                DigestHashFunction.SHA256,
                SyscallCache.NO_CACHE,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                storedEventHandler,  /* logSpawnPredicate= */
                { s -> true })

        context.logSpawn(
            spawn.build(),
            SpawnLogContextTestBase.Companion.createInputMetadataProvider(file),
            createInputMap(file),
            fs,
            SpawnLogContextTestBase.Companion.defaultTimeout(),
            SpawnLogContextTestBase.Companion.defaultSpawnResult()
        )

        context.close()

        val actual: java.util.ArrayList<SpawnExec?> = java.util.ArrayList<SpawnExec?>()
        ByteArrayInputStream(baos.toByteArray()).use { `in` ->
            SpawnLogReconstructor(`in`).use { reconstructor ->
                var ex: SpawnExec?
                while ((reconstructor.read().also { ex = it }) != null) {
                    actual.add(ex)
                }
            }
        }
        Truth.assertThat(actual)
            .containsExactly(
                SpawnLogContextTestBase.Companion.defaultSpawnExecBuilder()
                    .addInputs(File.newBuilder().setPath("file").setDigest(getDigest("abc")))
                    .build()
            )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun createSpawnLogContext(platformProperties: com.google.common.collect.ImmutableMap<String?, String?>): SpawnLogContext {
        return createSpawnLogContext(
            platformProperties,  /* logSpawnPredicate= */
            java.util.function.Predicate { spawn: Spawn? -> true })
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun createSpawnLogContext(logSpawnPredicate: java.util.function.Predicate<Spawn?>?): SpawnLogContext {
        return createSpawnLogContext(com.google.common.collect.ImmutableMap.of<String?, String?>(), logSpawnPredicate)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun createSpawnLogContext(
        platformProperties: com.google.common.collect.ImmutableMap<String?, String?>,
        logSpawnPredicate: java.util.function.Predicate<Spawn?>?
    ): SpawnLogContext {
        val remoteOptions: RemoteOptions =
            com.google.devtools.common.options.Options.getDefaults<O>(RemoteOptions::class.java)
        remoteOptions.setRemoteDefaultExecPropertiesField(platformProperties.entries.asList())

        return CompactSpawnLogContext(
            BufferedOutputStream(logPath.getOutputStream()),
            logPath.toString(),
            execRoot.asFragment(),
            TestConstants.WORKSPACE_NAME,
            siblingRepositoryLayout,
            remoteOptions,
            DigestHashFunction.SHA256,
            SyscallCache.NO_CACHE,
            UUID.fromString("00000000-0000-0000-0000-000000000000"),
            storedEventHandler,
            logSpawnPredicate
        )
    }

    @Throws(IOException::class)
    override fun closeAndAssertLog(context: SpawnLogContext, vararg expected: SpawnExec?) {
        context.close()

        val actual: java.util.ArrayList<SpawnExec?> = java.util.ArrayList<SpawnExec?>()
        SpawnLogReconstructor(logPath.getInputStream()).use { reconstructor ->
            var ex: SpawnExec?
            while ((reconstructor.read().also { ex = it }) != null) {
                actual.add(ex)
            }
        }
        Truth.assertThat(actual).containsExactlyElementsIn(expected).inOrder()
    }

    @Throws(IOException::class)
    private fun closeAndReadCompactLog(context: SpawnLogContext): com.google.common.collect.ImmutableList<Protos.ExecLogEntry?> {
        context.close()

        val entries: com.google.common.collect.ImmutableList.Builder<Protos.ExecLogEntry?> =
            com.google.common.collect.ImmutableList.builder<Protos.ExecLogEntry?>()
        logPath.getInputStream().use { `in` ->
            ZstdInputStream(`in`).use { zstdIn ->
                var entry: Protos.ExecLogEntry?
                while ((Protos.ExecLogEntry.parseDelimitedFrom(zstdIn).also { entry = it }) != null) {
                    entries.add(entry)
                }
            }
        }
        return entries.build()
    }
}
