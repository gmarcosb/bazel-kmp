// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.AbstractAction

/** Tests [SpawnAction].  */
@RunWith(JUnit4::class)
class SpawnActionTest : BuildViewTestCase() {
    private var welcomeArtifact: Artifact? = null
    private var destinationArtifact: Artifact? = null
    private var jarArtifact: Artifact? = null
    private var collectingAnalysisEnvironment: CollectingAnalysisEnvironment? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createArtifacts() {
        collectingAnalysisEnvironment =
            CollectingAnalysisEnvironment(getTestAnalysisEnvironment())
        welcomeArtifact = getSourceArtifact("pkg/welcome.txt")
        jarArtifact = getSourceArtifact("pkg/exe.jar")
        destinationArtifact = getBinArtifactWithNoOwner("dir/destination.txt")
    }

    private fun createCopyFromWelcomeToDestination(environmentVariables: MutableMap<String?, String?>?): SpawnAction {
        val cp: PathFragment? = PathFragment.create("/bin/cp")
        val arguments: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<E?>(
                welcomeArtifact.getExecPath().getPathString(),
                destinationArtifact.getExecPath().getPathString()
            )

        val action: SpawnAction =
            builder()
                .addInput(welcomeArtifact)
                .addOutput(destinationArtifact)
                .setExecutionInfo(com.google.common.collect.ImmutableMap.of<K?, V?>("local", ""))
                .setExecutable(cp)
                .setProgressMessage("hi, mom!")
                .setMnemonic("Dummy")
                .setEnvironment(environmentVariables)
                .addCommandLine(CommandLine.of(arguments))
                .build(nullOwnerWithTargetConfig(), targetConfig)
        collectingAnalysisEnvironment.registerAction(action)
        return action
    }

    @org.junit.Test
    fun testWelcomeArtifactIsInput() {
        val copyFromWelcomeToDestination: SpawnAction =
            createCopyFromWelcomeToDestination(com.google.common.collect.ImmutableMap.of<String?, String?>())
        val inputs: com.google.common.collect.ImmutableList<Artifact?>? =
            copyFromWelcomeToDestination.getInputs().toList()
        Truth.assertThat(inputs).containsExactly(welcomeArtifact)
    }

    @org.junit.Test
    fun testDestinationArtifactIsOutput() {
        val copyFromWelcomeToDestination: SpawnAction =
            createCopyFromWelcomeToDestination(com.google.common.collect.ImmutableMap.of<String?, String?>())
        val outputs: MutableCollection<Artifact?>? = copyFromWelcomeToDestination.getOutputs()
        Truth.assertThat(outputs).containsExactly(destinationArtifact)
    }

    @org.junit.Test
    fun testExecutionInfoCopied() {
        val copyFromWelcomeToDestination: SpawnAction =
            createCopyFromWelcomeToDestination(com.google.common.collect.ImmutableMap.of<String?, String?>())
        val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>? =
            copyFromWelcomeToDestination.getExecutionInfo()
        Truth.assertThat(executionInfo).containsExactly("local", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionInfo_fromExecutionPlatform() {
        val actionOwner: ActionOwner? =
            ActionOwner.createDummy(
                Label.parseCanonicalUnchecked("//target"),
                net.starlark.java.syntax.Location("dummy-file", 0, 0),  /* targetKind= */
                "dummy-kind",  /* buildConfigurationMnemonic= */
                "dummy-configuration-mnemonic",  /* configurationChecksum= */
                "dummy-configuration",
                BuildConfigurationEvent(
                    BuildEventStreamProtos.BuildEventId.getDefaultInstance(),
                    BuildEventStreamProtos.BuildEvent.getDefaultInstance()
                ),  /* isToolConfiguration= */
                false,  /* executionPlatform= */
                PlatformInfo.EMPTY_PLATFORM_INFO,
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
                    .put("prop1", "foo")
                    .put("prop2", "bar")
                    .buildOrThrow()
            )

        val action: SpawnAction =
            builder()
                .addInput(welcomeArtifact)
                .addOutput(destinationArtifact)
                .setExecutionInfo(
                    com.google.common.collect.ImmutableMap.builder<String?, String?>()
                        .put("prop2", "quux") // Overwrite the value from ActionOwner's exec properties.
                        .buildOrThrow()
                )
                .setExecutable(scratch.file("/bin/xxx").asFragment())
                .setProgressMessage("hi, mom!")
                .setMnemonic("Dummy")
                .build(actionOwner, targetConfig)

        val result: com.google.common.collect.ImmutableMap<String?, String?>? = action.getExecutionInfo()
        Truth.assertThat(result).containsEntry("prop1", "foo")
        Truth.assertThat(result).containsEntry("prop2", "quux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilder() {
        val input: Artifact = getSourceArtifact("input")
        val output: Artifact = getBinArtifactWithNoOwner("output")
        val action: SpawnAction =
            builder()
                .addInput(input)
                .addOutput(output)
                .setExecutable(scratch.file("/bin/xxx").asFragment())
                .setProgressMessage("Test")
                .build(nullOwnerWithTargetConfig(), targetConfig)
        collectingAnalysisEnvironment.registerAction(action)
        assertThat(action.getOwner().getLabel())
            .isEqualTo(ActionsTestUtil.Companion.NULL_ACTION_OWNER.getLabel())
        assertThat(action.getInputs().toList()).containsExactly(input)
        assertThat(action.getOutputs()).containsExactly(output)
        assertThat(action.getSpawnForTesting().getLocalResources())
            .isEqualTo(AbstractAction.DEFAULT_RESOURCE_SET)
        assertThat(action.getArguments()).containsExactly("/bin/xxx")
        assertThat(action.getProgressMessage()).isEqualTo("Test")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilderWithExecutable() {
        val action: SpawnAction =
            builder()
                .setExecutable(welcomeArtifact)
                .addOutput(destinationArtifact)
                .build(nullOwnerWithTargetConfig(), targetConfig)
        collectingAnalysisEnvironment.registerAction(action)
        assertThat(action.getArguments())
            .containsExactly(welcomeArtifact.getExecPath().getPathString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilderWithExecutableInRootPackage() {
        val tool: Artifact = getSourceArtifact("tool.bin")
        val action: SpawnAction =
            builder()
                .setExecutable(tool)
                .addOutput(destinationArtifact)
                .build(nullOwnerWithTargetConfig(), targetConfig)
        collectingAnalysisEnvironment.registerAction(action)
        assertThat(action.getArguments()).hasSize(1)
        assertThat(action.getArguments().get(0)).matches("\\.[/\\\\]tool.bin")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilderWithJarExecutable() {
        val action: SpawnAction =
            builder()
                .addOutput(destinationArtifact)
                .setJarExecutable(
                    PathFragment.create("/bin/java"),
                    jarArtifact,
                    NestedSetBuilder.create(Order.STABLE_ORDER, "-jvmarg")
                )
                .build(nullOwnerWithTargetConfig(), targetConfig)
        collectingAnalysisEnvironment.registerAction(action)
        assertThat(action.getArguments())
            .containsExactly("/bin/java", "-jvmarg", "-jar", "pkg/exe.jar")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilderWithJarExecutableAndParameterFile2() {
        useConfiguration("--min_param_file_size=0")
        collectingAnalysisEnvironment =
            CollectingAnalysisEnvironment(getTestAnalysisEnvironment())
        val output: Artifact = getBinArtifactWithNoOwner("output")
        val action: SpawnAction =
            builder()
                .addOutput(output)
                .setJarExecutable(
                    PathFragment.create("/bin/java"),
                    jarArtifact,
                    NestedSetBuilder.create(Order.STABLE_ORDER, "-jvmarg")
                )
                .addCommandLine(
                    CustomCommandLine.builder().add("-X").build(),
                    ParamFileInfo.builder(ParameterFileType.UNQUOTED).build()
                )
                .build(nullOwnerWithTargetConfig(), targetConfig)

        // The action reports all arguments, including those inside the param file
        assertThat(action.getArguments())
            .containsExactly("/bin/java", "-jvmarg", "-jar", "pkg/exe.jar", "-X")
            .inOrder()

        val actionExecutionContext: ActionExecutionContext? =
            ActionExecutionContextBuilder()
                .setMetadataProvider(com.google.devtools.build.lib.exec.util.FakeActionInputFileCache())
                .build()

        val spawn: Spawn =
            action.getSpawn(
                actionExecutionContext,
                com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* envResolved= */
                false,  /* reportOutputs= */
                true
            )
        val paramFileName = output.getExecPathString() + "-0.params"
        // The spawn's primary arguments should reference the param file
        assertThat(spawn.getArguments())
            .containsExactly("/bin/java", "-jvmarg", "-jar", "pkg/exe.jar", "@" + paramFileName)
            .inOrder()

        // Asserts that the inputs contain the param file virtual input
        val input: java.util.Optional<out ActionInput?>? =
            spawn.getInputFiles().toList().stream()
                .filter({ i -> i is VirtualActionInput })
                .findFirst()
        Truth.assertThat(input).isPresent()
        val paramFile: VirtualActionInput = input.get() as VirtualActionInput
        assertThat(paramFile.getBytes().toString(java.nio.charset.StandardCharsets.ISO_8859_1).trim()).isEqualTo("-X")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilderWithExtraExecutableArguments() {
        val action: SpawnAction =
            builder()
                .addOutput(destinationArtifact)
                .setJarExecutable(
                    PathFragment.create("/bin/java"),
                    jarArtifact,
                    NestedSetBuilder.create(Order.STABLE_ORDER, "-jvmarg")
                )
                .addExecutableArguments("execArg1", "execArg2")
                .addCommandLine(CustomCommandLine.builder().add("arg1").build())
                .build(nullOwnerWithTargetConfig(), targetConfig)
        collectingAnalysisEnvironment.registerAction(action)
        assertThat(action.getArguments())
            .containsExactly(
                "/bin/java", "-jvmarg", "-jar", "pkg/exe.jar", "execArg1", "execArg2", "arg1"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuilderWithNoExecutableCommand_buildsActionWithCorrectArgs() {
        val action: SpawnAction =
            builder()
                .addOutput(getBinArtifactWithNoOwner("output"))
                .addCommandLine(CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("arg1", "arg2")))
                .addCommandLine(CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("arg3")))
                .build(nullOwnerWithTargetConfig(), targetConfig)

        assertThat(action.getArguments()).containsExactly("arg1", "arg2", "arg3").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleCommandLines() {
        val input: Artifact = getSourceArtifact("input")
        val output: Artifact = getBinArtifactWithNoOwner("output")
        val action: SpawnAction =
            builder()
                .addInput(input)
                .addOutput(output)
                .setExecutable(scratch.file("/bin/xxx").asFragment())
                .addCommandLine(CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("arg1")))
                .addCommandLine(CommandLine.of(com.google.common.collect.ImmutableList.of<E?>("arg2")))
                .build(nullOwnerWithTargetConfig(), targetConfig)
        assertThat(action.getArguments()).containsExactly("/bin/xxx", "arg1", "arg2").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraActionInfo() {
        val action: SpawnAction =
            createCopyFromWelcomeToDestination(com.google.common.collect.ImmutableMap.of<String?, String?>())
        val info: ExtraActionInfo = action.getExtraActionInfo(actionKeyContext).build()
        assertThat(info.getMnemonic()).isEqualTo("Dummy")

        val spawnInfo: SpawnInfo = info.getExtension(SpawnInfo.spawnInfo)
        assertThat(info.hasExtension(SpawnInfo.spawnInfo)).isTrue()

        assertThat(spawnInfo.getArgumentList()).containsExactlyElementsIn(action.getArguments())

        val inputPaths: MutableList<String?>? = Artifact.asExecPaths(action.getInputs())
        val outputPaths: MutableList<String?>? = Artifact.asExecPaths(action.getOutputs())

        assertThat(spawnInfo.getInputFileList()).containsExactlyElementsIn(inputPaths)
        assertThat(spawnInfo.getOutputFileList()).containsExactlyElementsIn(outputPaths)
        val environment: com.google.common.collect.ImmutableMap<String?, String?> =
            action.getIncompleteEnvironmentForTesting()
        assertThat(spawnInfo.getVariableCount()).isEqualTo(environment.size())

        for (variable in spawnInfo.getVariableList()) {
            Truth.assertThat(environment).containsEntry(variable.getName(), variable.getValue())
        }
    }

    /** Test that environment variables are not escaped or quoted.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraActionInfoEnvironmentVariables() {
        val env: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "P1", "simple",
                "P2", "spaces are not escaped",
                "P3", ":",
                "P4", "",
                "NONSENSE VARIABLE", "value"
            )

        val spawnInfo: SpawnInfo =
            createCopyFromWelcomeToDestination(env)
                .getExtraActionInfo(actionKeyContext)
                .build()
                .getExtension(SpawnInfo.spawnInfo)
        Truth.assertThat(env).hasSize(spawnInfo.getVariableCount())
        for (variable in spawnInfo.getVariableList()) {
            Truth.assertThat(env).containsEntry(variable.getName(), variable.getValue())
        }
    }

    private enum class KeyAttributes {
        EXECUTABLE_PATH,
        EXECUTABLE,
        MNEMONIC,
        ENVIRONMENT
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testComputeKey() {
        val artifactA: Artifact = getSourceArtifact("a")
        val artifactB: Artifact = getSourceArtifact("b")

        ActionTester.runTest<KeyAttributes?>(
            com.google.devtools.build.lib.analysis.actions.SpawnActionTest.KeyAttributes::class.java,
            object : ActionCombinationFactory<KeyAttributes?> {
                override fun generate(attributesToFlip: com.google.common.collect.ImmutableSet<KeyAttributes?>): Action? {
                    val builder: SpawnAction.Builder = builder()
                    builder.addOutput(destinationArtifact)

                    val executable: PathFragment? =
                        if (attributesToFlip.contains(com.google.devtools.build.lib.analysis.actions.SpawnActionTest.KeyAttributes.EXECUTABLE_PATH))
                            artifactA.getExecPath()
                        else
                            artifactB.getExecPath()
                    if (attributesToFlip.contains(com.google.devtools.build.lib.analysis.actions.SpawnActionTest.KeyAttributes.EXECUTABLE)) {
                        builder.setExecutable(executable)
                    } else {
                        builder.setJarExecutable(
                            executable, jarArtifact, NestedSetBuilder.emptySet(Order.STABLE_ORDER)
                        )
                    }

                    builder.setMnemonic(if (attributesToFlip.contains(com.google.devtools.build.lib.analysis.actions.SpawnActionTest.KeyAttributes.MNEMONIC)) "a" else "b")

                    val env: MutableMap<String?, String?> = HashMap<String?, String?>()
                    if (attributesToFlip.contains(com.google.devtools.build.lib.analysis.actions.SpawnActionTest.KeyAttributes.ENVIRONMENT)) {
                        env.put("foo", "bar")
                    }
                    builder.setEnvironment(env)

                    val action: SpawnAction? = builder.build(nullOwnerWithTargetConfig(), targetConfig)
                    collectingAnalysisEnvironment.registerAction(action)
                    return action
                }
            },
            actionKeyContext
        )
    }

    @org.junit.Test
    fun testMnemonicMustNotContainSpaces() {
        val builder: SpawnAction.Builder = builder()
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { builder.setMnemonic("contains space") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { builder.setMnemonic("contains\nnewline") })
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { builder.setMnemonic("contains/slash") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProgressMessagePlaceholders() {
        val action: SpawnAction =
            builder()
                .addInput(getSourceArtifact("some/input"))
                .addOutput(getBinArtifactWithNoOwner("some/output"))
                .setExecutable(scratch.file("/bin/xxx").asFragment())
                .setProgressMessage("Progress for %{label}: %{input} -> %{output}")
                .build(nullOwnerWithTargetConfig(), targetConfig)
        assertThat(action.getProgressMessage())
            .isEqualTo("Progress for //null/action:owner: some/input -> some/output")
    }

    /**
     * Tests that the ExtraActionInfo proto that's generated from an action, contains Aspect-related
     * information.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetExtraActionInfoOnAspects() {
        scratch.file(
            "a/BUILD",
            """
        load("//a:def.bzl", "testrule")

        testrule(
            name = "a",
            deps = [":b"],
        )

        testrule(name = "b")
        
        """.trimIndent()
        )
        scratch.file(
            "a/def.bzl",
            """
        MyInfo = provider()

        def _aspect_impl(target, ctx):
            f = ctx.actions.declare_file("foo.txt")
            ctx.actions.run_shell(outputs = [f], command = 'echo foo > "${'$'}1"')
            return MyInfo(output = f)

        def _rule_impl(ctx):
            return DefaultInfo(
                files = depset([artifact[MyInfo].output for artifact in ctx.attr.deps]),
            )

        aspect1 = aspect(
            _aspect_impl,
            attr_aspects = ["deps"],
            attrs = {"parameter": attr.string(values = ["param_value"])},
        )
        testrule = rule(_rule_impl, attrs = {
            "deps": attr.label_list(aspects = [aspect1]),
            "parameter": attr.string(default = "param_value"),
        })
        
        """.trimIndent()
        )

        update(
            com.google.common.collect.ImmutableList.of<String?>("//a:a"),  /* keepGoing= */
            false,  /* loadingPhaseThreads= */
            1,  /* doAnalysis= */
            true,
            com.google.common.eventbus.EventBus()
        )

        val artifact: Artifact? = BuildViewTestCase.getFilesToBuild(getConfiguredTarget("//a:a")).getSingleton()
        val extraActionInfo: ExtraActionInfo.Builder =
            getGeneratingAction(artifact).getExtraActionInfo(actionKeyContext)
        assertThat(extraActionInfo.getAspectName()).isEqualTo("//a:def.bzl%aspect1")
        assertThat(extraActionInfo.getAspectParametersMap())
            .containsExactly(
                "parameter", ExtraActionInfo.StringList.newBuilder().addValue("param_value").build()
            )
    }

    @Throws(java.lang.Exception::class)
    private fun createWorkerSupportSpawn(executionInfoVariables: MutableMap<String?, String?>?): SpawnAction {
        val input: Artifact = getSourceArtifact("input")
        val output: Artifact = getBinArtifactWithNoOwner("output")
        return builder()
            .addInput(input)
            .addOutput(output)
            .setMnemonic("ActionToolMnemonic")
            .setExecutionInfo(executionInfoVariables)
            .setExecutable(scratch.file("/bin/xxx").asFragment())
            .build(nullOwnerWithTargetConfig(), targetConfig)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkerSupport() {
        val workerSupportSpawn: SpawnAction =
            createWorkerSupportSpawn(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "supports-workers",
                    "1"
                )
            )
        assertThat(Spawns.supportsWorkers(workerSupportSpawn.getSpawnForTesting())).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiplexWorkerSupport() {
        val multiplexWorkerSupportSpawn: SpawnAction =
            createWorkerSupportSpawn(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "supports-multiplex-workers",
                    "1"
                )
            )
        assertThat(Spawns.supportsMultiplexWorkers(multiplexWorkerSupportSpawn.getSpawnForTesting()))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkerProtocolFormat_defaultIsProto() {
        val spawn: SpawnAction = createWorkerSupportSpawn(
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "supports-workers",
                "1"
            )
        )
        assertThat(Spawns.getWorkerProtocolFormat(spawn.getSpawnForTesting()))
            .isEqualTo(WorkerProtocolFormat.PROTO)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkerProtocolFormat_explicitProto() {
        val spawn: SpawnAction =
            createWorkerSupportSpawn(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "supports-workers",
                    "1",
                    "requires-worker-protocol",
                    "proto"
                )
            )
        assertThat(Spawns.getWorkerProtocolFormat(spawn.getSpawnForTesting()))
            .isEqualTo(WorkerProtocolFormat.PROTO)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkerProtocolFormat_explicitJson() {
        val spawn: SpawnAction =
            createWorkerSupportSpawn(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "supports-workers",
                    "1",
                    "requires-worker-protocol",
                    "json"
                )
            )
        assertThat(Spawns.getWorkerProtocolFormat(spawn.getSpawnForTesting()))
            .isEqualTo(WorkerProtocolFormat.JSON)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkerMnemonicDefault() {
        val defaultMnemonicSpawn: SpawnAction =
            createWorkerSupportSpawn(com.google.common.collect.ImmutableMap.of<String?, String?>())
        assertThat(Spawns.getWorkerKeyMnemonic(defaultMnemonicSpawn.getSpawnForTesting()))
            .isEqualTo("ActionToolMnemonic")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkerMnemonicOverride() {
        val customMnemonicSpawn: SpawnAction =
            createWorkerSupportSpawn(
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "worker-key-mnemonic",
                    "ToolPoolMnemonic"
                )
            )
        assertThat(Spawns.getWorkerKeyMnemonic(customMnemonicSpawn.getSpawnForTesting()))
            .isEqualTo("ToolPoolMnemonic")
    }

    private fun nullOwnerWithTargetConfig(): ActionOwner {
        return ActionOwner.create(
            ActionsTestUtil.Companion.NULL_ACTION_OWNER.getLabel(),
            ActionsTestUtil.Companion.NULL_ACTION_OWNER.getLocation(),
            ActionsTestUtil.Companion.NULL_ACTION_OWNER.getTargetKind(),
            targetConfig,
            ActionsTestUtil.Companion.NULL_ACTION_OWNER.getExecutionPlatform(),
            ActionsTestUtil.Companion.NULL_ACTION_OWNER.getAspectDescriptors(),
            ActionsTestUtil.Companion.NULL_ACTION_OWNER.getExecProperties()
        )
    }

    companion object {
        private fun builder(): SpawnAction.Builder {
            return Builder()
        }
    }
}
