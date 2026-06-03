// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime

import com.github.luben.zstd.ZstdInputStream

/** Unit tests for [ExecutionGraphModule].  */
@RunWith(TestParameterInjector::class)
class ExecutionGraphModuleTest : FoundationTestCase() {
    @TestParameter("-1", "1", "256")
    private val queueSize = 0

    @TestParameter("-1", "1", "256")
    private val queuedBytesLimit = 0

    private val module: ExecutionGraphModule = ExecutionGraphModule()
    private var artifactRoot: ArtifactRoot? = null

    @Before
    fun initializeRoots() {
        artifactRoot = ArtifactRoot.asDerivedRoot(scratch.resolve("/"), RootType.OUTPUT, "output")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOneSpawn() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val spawn: Spawn =
            SimpleSpawn(
                FakeOwnerWithPrimaryOutput(
                    "Mnemonic", "Progress message", "//foo", "output/foo/out"
                ),
                com.google.common.collect.ImmutableList.of<E?>("cmd"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("exec", "value"),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(ActionInputHelper.fromPath("output/foo/out")),
                ResourceSet.ZERO
            )
        val result: SpawnResult? =
            Builder()
                .setRunnerName("local")
                .setStatus(Status.SUCCESS)
                .setExitCode(0)
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forLocalExec()
                        .setTotalTimeInMs(1234)
                        .setExecutionWallTimeInMs(2345)
                        .setProcessOutputsTimeInMs(3456)
                        .build()
                )
                .build()
        startLogging(eventBus, buffer, DependencyInfo.NONE)
        val startTimeInstant: Instant = Instant.now()
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                result,
                startTimeInstant,  /* spawnIdentifier= */
                "foo"
            )
        )
        module.buildComplete(
            BuildCompleteEvent(BuildResult(startTimeInstant.toEpochMilli() + 1000))
        )

        val nodes: com.google.common.collect.ImmutableList<ExecutionGraph.Node?> = parse(buffer)
        Truth.assertThat(nodes).hasSize(1)
        assertThat(nodes.get(0).getTargetLabel()).isEqualTo("//foo:foo")
        assertThat(nodes.get(0).getMnemonic()).isEqualTo("Mnemonic")
        assertThat(nodes.get(0).getMetrics().getDurationMillis()).isEqualTo(1234L)
        assertThat(nodes.get(0).getMetrics().getFetchMillis()).isEqualTo(0)
        assertThat(nodes.get(0).getMetrics().getProcessOutputsMillis()).isEqualTo(3456)
        assertThat(nodes.get(0).getMetrics().getStartTimestampMillis())
            .isEqualTo(startTimeInstant.toEpochMilli())
        assertThat(nodes.get(0).getIndex()).isEqualTo(0)
        assertThat(nodes.get(0).getDependentIndexList()).isEmpty()
        assertThat(nodes.get(0).getIdentifier()).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpawnWithDiscoverInputs() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val spawn: Spawn =
            SimpleSpawn(
                FakeOwnerWithPrimaryOutput(
                    "Mnemonic", "Progress message", "//foo", "output/foo/out"
                ),
                com.google.common.collect.ImmutableList.of<E?>("cmd"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("exec", "value"),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(createOutputArtifact("output/foo/out")),
                ResourceSet.ZERO
            )
        val result: SpawnResult? =
            Builder()
                .setRunnerName("local")
                .setStatus(Status.SUCCESS)
                .setExitCode(0)
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forLocalExec()
                        .setTotalTimeInMs(1234)
                        .setExecutionWallTimeInMs(2345)
                        .setProcessOutputsTimeInMs(3456)
                        .setParseTimeInMs(2000)
                        .build()
                )
                .build()
        startLogging(eventBus, buffer, DependencyInfo.NONE)
        val startTimeInstant: Instant = Instant.ofEpochMilli(999888777L)
        module.discoverInputs(
            DiscoveredInputsEvent(
                SpawnMetrics.Builder.forOtherExec().setParseTimeInMs(987).setTotalTimeInMs(987).build(),
                NullAction(createOutputArtifact("output/foo/out")),
                0
            )
        )
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                result,
                startTimeInstant,  /* spawnIdentifier= */
                "foo"
            )
        )
        module.buildComplete(
            BuildCompleteEvent(BuildResult(startTimeInstant.toEpochMilli() + 1000))
        )

        val nodes: com.google.common.collect.ImmutableList<ExecutionGraph.Node?> = parse(buffer)
        val metrics: ExecutionGraph.Metrics = nodes.get(0).getMetrics()
        assertThat(metrics.getDurationMillis()).isEqualTo(2221)
        assertThat(metrics.getFetchMillis()).isEqualTo(0)
        assertThat(metrics.getProcessMillis()).isEqualTo(2345)
        assertThat(metrics.getProcessOutputsMillis()).isEqualTo(3456)
        assertThat(metrics.getParseMillis()).isEqualTo(2000)
        assertThat(metrics.getDiscoverInputsMillis()).isEqualTo(987)
        assertThat(nodes.get(0).getIdentifier()).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun actionDepsWithThreeSpawns() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        val out1: ActionInput = ActionInputHelper.fromPath("output/foo/out1")
        val out2: ActionInput = ActionInputHelper.fromPath("output/foo/out2")
        val outTop: ActionInput = ActionInputHelper.fromPath("output/foo/out.top")

        val spawnOut1: Spawn =
            SimpleSpawn(
                FakeOwnerWithPrimaryOutput(
                    "Mnemonic", "Progress message", "//foo", out1.getExecPathString()
                ),
                com.google.common.collect.ImmutableList.of<E?>("cmd"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("exec", "value"),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(out1),
                ResourceSet.ZERO
            )
        val spawnOut2: Spawn =
            SimpleSpawn(
                FakeOwnerWithPrimaryOutput(
                    "Mnemonic", "Progress message", "//foo", out2.getExecPathString()
                ),
                com.google.common.collect.ImmutableList.of<E?>("cmd"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("exec", "value"),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(out2),
                ResourceSet.ZERO
            )
        val spawnTop: Spawn =
            SimpleSpawn(
                FakeOwnerWithPrimaryOutput(
                    "Mnemonic", "Progress message", "//foo", outTop.getExecPathString()
                ),
                com.google.common.collect.ImmutableList.of<E?>("cmd"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("exec", "value"),  /* inputs= */
                NestedSetBuilder.create(Order.COMPILE_ORDER, out1, out2),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(outTop),
                ResourceSet.ZERO
            )
        val result: SpawnResult? =
            Builder()
                .setRunnerName("local")
                .setStatus(Status.SUCCESS)
                .setExitCode(0)
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forLocalExec()
                        .setTotalTimeInMs(1234)
                        .setExecutionWallTimeInMs(2345)
                        .setProcessOutputsTimeInMs(3456)
                        .build()
                )
                .build()
        startLogging(eventBus, buffer, DependencyInfo.ALL)
        val startTimeInstant: Instant = Instant.now()
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawnOut1,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                result,
                startTimeInstant,  /* spawnIdentifier= */
                "out1"
            )
        )
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawnOut2,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                result,
                startTimeInstant,  /* spawnIdentifier= */
                "out2"
            )
        )
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawnTop,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                result,
                startTimeInstant,  /* spawnIdentifier= */
                "top"
            )
        )
        module.buildComplete(
            BuildCompleteEvent(BuildResult(startTimeInstant.plusMillis(1000).toEpochMilli()))
        )

        val nodes: com.google.common.collect.ImmutableList<ExecutionGraph.Node?> = parse(buffer)
        Truth.assertThat(nodes).hasSize(3)

        assertThat(nodes.get(0).getIndex()).isEqualTo(0)
        assertThat(nodes.get(0).getDependentIndexList()).isEmpty()

        assertThat(nodes.get(1).getIndex()).isEqualTo(1)
        assertThat(nodes.get(1).getDependentIndexList()).isEmpty()

        assertThat(nodes.get(2).getIndex()).isEqualTo(2)
        assertThat(nodes.get(2).getDependentIndexList()).containsExactly(0, 1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun changePruning_hasEdgesToPrunedSpawn() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()

        val out1: Artifact = createOutputArtifact("foo/out1")
        val out2: DerivedArtifact = createOutputArtifact("foo/out2") as DerivedArtifact
        val out3: Artifact = createOutputArtifact("foo/out3")

        val spawnOut1: Spawn =
            SimpleSpawn(
                FakeOwnerWithPrimaryOutput(
                    "Mnemonic", "Progress message", "//foo1", out1.getExecPathString()
                ),
                com.google.common.collect.ImmutableList.of<E?>("cmd"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("exec", "value"),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(out1),
                ResourceSet.ZERO
            )
        val actionOut2: MockAction = MockAction(
            com.google.common.collect.ImmutableList.of<Artifact>(out1),
            com.google.common.collect.ImmutableSet.of<Artifact>(out2)
        )
        val spawnOut2: Spawn =
            SimpleSpawn(
                actionOut2,
                com.google.common.collect.ImmutableList.of<E?>("cmd"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("exec", "value"),  /* inputs= */
                NestedSetBuilder.create(Order.STABLE_ORDER, out1),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(out2),
                ResourceSet.ZERO
            )
        val spawnOut3: Spawn =
            SimpleSpawn(
                FakeOwnerWithPrimaryOutput(
                    "Mnemonic", "Progress message", "//foo3", out3.getExecPathString()
                ),
                com.google.common.collect.ImmutableList.of<E?>("cmd"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("exec", "value"),  /* inputs= */
                NestedSetBuilder.create(Order.COMPILE_ORDER, out2),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(out3),
                ResourceSet.ZERO
            )
        val result: SpawnResult? =
            Builder()
                .setRunnerName("local")
                .setStatus(Status.SUCCESS)
                .setExitCode(0)
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forLocalExec()
                        .setTotalTimeInMs(1234)
                        .setExecutionWallTimeInMs(2345)
                        .setProcessOutputsTimeInMs(3456)
                        .build()
                )
                .build()
        module.setGraph(
            object : WalkableGraph() {
                public override fun getValue(key: SkyKey?): SkyValue? {
                    if (key is ActionLookupKey) {
                        return object : ActionLookupValue() {
                            val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>
                                get() = com.google.common.collect.ImmutableList.of<E?>(actionOut2)
                        }
                    }
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun getSuccessfulValues(keys: Iterable<out SkyKey?>?): MutableMap<SkyKey?, SkyValue?>? {
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun getMissingAndExceptions(keys: Iterable<SkyKey?>?): MutableMap<SkyKey?, java.lang.Exception?>? {
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun getException(key: SkyKey?): java.lang.Exception? {
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun isCycle(key: SkyKey?): Boolean {
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun getDirectDeps(keys: Iterable<SkyKey?>?): MutableMap<SkyKey?, Iterable<SkyKey?>?>? {
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun getDirectDeps(key: SkyKey?): Iterable<SkyKey?>? {
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun getReverseDeps(keys: Iterable<out SkyKey?>?): MutableMap<SkyKey?, Iterable<SkyKey?>?>? {
                    throw java.lang.UnsupportedOperationException()
                }

                public override fun getValueAndRdeps(
                    keys: Iterable<SkyKey?>?
                ): MutableMap<SkyKey?, Pair<SkyValue?, Iterable<SkyKey?>?>?>? {
                    throw java.lang.UnsupportedOperationException()
                }
            })
        val startTimeInstant: Instant = Instant.now()
        startLogging(eventBus, buffer, DependencyInfo.ALL)
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawnOut1,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                result,
                startTimeInstant,  /* spawnIdentifier= */
                "out1"
            )
        )
        // spawnOut2 is change pruned.
        val unused: Spawn = spawnOut2
        module.actionChangePruned(
            ActionChangePrunedEvent(
                ActionsTestUtil.NULL_ACTION_LOOKUP_DATA, startTimeInstant.toEpochMilli() * 1000000
            )
        )
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawnOut3,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                result,
                startTimeInstant,  /* spawnIdentifier= */
                "out3"
            )
        )
        module.buildComplete(
            BuildCompleteEvent(BuildResult(startTimeInstant.plusMillis(1000).toEpochMilli()))
        )

        val nodes: com.google.common.collect.ImmutableList<ExecutionGraph.Node?> = parse(buffer)
        Truth.assertThat(nodes).hasSize(3)

        assertThat(nodes.get(0).getTargetLabel()).isEqualTo("//foo1:foo1")
        assertThat(nodes.get(0).getIndex()).isEqualTo(0)
        assertThat(nodes.get(0).getDependentIndexList()).isEmpty()

        assertThat(nodes.get(1).getTargetLabel()).isEqualTo("//null/action:owner")
        assertThat(nodes.get(1).getDependentIndexList()).containsExactly(nodes.get(0).getIndex())

        assertThat(nodes.get(2).getTargetLabel()).isEqualTo("//foo3:foo3")
        assertThat(nodes.get(2).getDependentIndexList()).containsExactly(nodes.get(1).getIndex())
    }

    private enum class FailingOutputStreamFactory {
        CLOSE {
            @Throws(IOException::class)
            public override fun get(): ZstdOutputStream? {
                return object : ZstdOutputStream(java.io.OutputStream.nullOutputStream()) {
                    @kotlin.jvm.Synchronized
                    @Throws(IOException::class)
                    public override fun close() {
                        throw IOException("Simulated close failure")
                    }
                }
            }
        },

        /** Called from [com.google.protobuf.CodedOutputStream.flush].  */
        WRITE {
            @Throws(IOException::class)
            public override fun get(): ZstdOutputStream? {
                return object : ZstdOutputStream(java.io.OutputStream.nullOutputStream()) {
                    @kotlin.jvm.Synchronized
                    @Throws(IOException::class)
                    public override fun write(b: ByteArray?, off: Int, len: Int) {
                        throw IOException("oh no!")
                    }
                }
            }
        };

        @Throws(IOException::class)
        abstract fun get(): ZstdOutputStream?
    }

    /** Regression test for b/218721483.  */
    @org.junit.Test(timeout = 30000)
    fun failureInOutputDoesNotHang(
        @TestParameter failingOutputStream: FailingOutputStreamFactory
    ) {
        val writer: ActionDumpWriter =
            object : ActionDumpWriter(
                BugReporter.defaultInstance(),
                com.google.common.eventbus.EventBus(),  /* localLockFreeOutputEnabled= */
                false,  /* logFileWriteEdges= */
                false,
                java.io.OutputStream.nullOutputStream(),
                DependencyInfo.NONE,
                queueSize,
                queuedBytesLimit
            ) {
                protected override fun updateLogs(logs: BuildToolLogCollection?) {}

                @Throws(IOException::class)
                protected override fun createCompressingOutputStream(): ZstdOutputStream? {
                    return failingOutputStream.get()
                }
            }
        module.setWriter(writer)
        eventBus.register(module)

        val startTimeInstant: Instant = Instant.now()
        eventBus.post(BuildCompleteEvent(BuildResult(startTimeInstant.toEpochMilli() + 1000)))
    }

    private fun startLogging(
        eventBus: com.google.common.eventbus.EventBus,
        buffer: java.io.OutputStream?,
        depType: DependencyInfo?
    ) {
        startLogging(
            eventBus,
            BugReporter.defaultInstance(),  /* localLockFreeOutputEnabled= */
            false,  /* logFileWriteEdges= */
            false,
            buffer,
            depType
        )
    }

    private fun startLogging(
        eventBus: com.google.common.eventbus.EventBus,
        bugReporter: BugReporter?,
        localLockFreeOutputEnabled: Boolean,
        logFileWriteEdges: Boolean,
        buffer: java.io.OutputStream?,
        depType: DependencyInfo?
    ) {
        val writer: ActionDumpWriter =
            object : ActionDumpWriter(
                bugReporter,
                eventBus,
                localLockFreeOutputEnabled,
                logFileWriteEdges,
                buffer,
                depType,
                queueSize,
                queuedBytesLimit
            ) {
                protected override fun updateLogs(logs: BuildToolLogCollection?) {}
            }
        module.setWriter(writer)
        eventBus.register(module)
    }

    @org.junit.Test
    fun shutDownWithoutStartTolerated() {
        eventBus.register(module)
        val startTimeInstant: Instant = Instant.now()
        // Doesn't crash.
        eventBus.post(BuildCompleteEvent(BuildResult(startTimeInstant.toEpochMilli() + 1000)))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpawnWithNullOwnerLabel() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val spawn: Spawn =
            SimpleSpawn(
                object : FakeOwnerWithPrimaryOutput(
                    "Mnemonic", "Progress message", "//unused:label", "output/foo/out"
                ) {
                    val owner: ActionOwner
                        get() = ActionOwner.create( /* label= */
                            null,
                            ActionsTestUtil.NULL_ACTION_OWNER.getLocation(),
                            ActionsTestUtil.NULL_ACTION_OWNER.getTargetKind(),
                            ActionsTestUtil.NULL_ACTION_OWNER.getBuildConfigurationInfo(),
                            ActionsTestUtil.NULL_ACTION_OWNER.getExecutionPlatform(),
                            ActionsTestUtil.NULL_ACTION_OWNER.getAspectDescriptors(),
                            ActionsTestUtil.NULL_ACTION_OWNER.getExecProperties()
                        )
                },
                com.google.common.collect.ImmutableList.of<E?>("cmd"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("env", "value"),
                com.google.common.collect.ImmutableMap.of<K?, V?>("exec", "value"),  /* inputs= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),  /* outputs= */
                com.google.common.collect.ImmutableSet.of<E?>(ActionInputHelper.fromPath("output/foo/out")),
                ResourceSet.ZERO
            )
        val result: SpawnResult? =
            Builder()
                .setRunnerName("local")
                .setStatus(Status.SUCCESS)
                .setExitCode(0)
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forLocalExec()
                        .setTotalTimeInMs(1234)
                        .setExecutionWallTimeInMs(2345)
                        .setProcessOutputsTimeInMs(3456)
                        .build()
                )
                .build()
        startLogging(eventBus, buffer, DependencyInfo.NONE)
        val startTimeInstant: Instant = Instant.now()
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                result,
                startTimeInstant,  /* spawnIdentifier= */
                "foo"
            )
        )
        module.buildComplete(
            BuildCompleteEvent(BuildResult(startTimeInstant.toEpochMilli() + 1000))
        )

        val nodes: com.google.common.collect.ImmutableList<ExecutionGraph.Node?> = parse(buffer)
        Truth.assertThat(nodes).hasSize(1)
        assertThat(nodes.get(0).getTargetLabel()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun spawnAndAction_withSameOutputs() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        startLogging(eventBus, buffer, DependencyInfo.ALL)

        module.spawnExecuted(
            SpawnExecutedEvent(
                SpawnBuilder().withOwnerPrimaryOutput(createOutputArtifact("foo/out")).build(),
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                createRemoteSpawnResult(200),
                Instant.ofEpochMilli(100),  /* spawnIdentifier= */
                "foo"
            )
        )
        module.actionComplete(
            ActionCompletionEvent(
                0,
                0,
                NullAction(createOutputArtifact("foo/out")),
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        module.buildComplete(BuildCompleteEvent(BuildResult(1000)))

        Truth.assertThat(parse(buffer))
            .containsExactly(
                executionGraphNodeBuilderForSpawnBuilderSpawn()
                    .setIndex(0)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(100)
                            .setDurationMillis(200)
                            .setOtherMillis(200)
                    )
                    .setRunner("remote")
                    .setIdentifier("foo")
                    .setRuleClass("dummy-target-kind")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun spawnAndAction_withDifferentOutputs() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        startLogging(eventBus, buffer, DependencyInfo.ALL)
        val nanosToMillis: com.google.devtools.build.lib.clock.BlazeClock.NanosToMillisSinceEpochConverter =
            com.google.devtools.build.lib.clock.BlazeClock.createNanosToMillisSinceEpochConverter()
        module.setNanosToMillis(nanosToMillis)

        module.spawnExecuted(
            SpawnExecutedEvent(
                SpawnBuilder().withOwnerPrimaryOutput(createOutputArtifact("foo/out")).build(),
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                createRemoteSpawnResult(200),
                Instant.ofEpochMilli(100),  /* spawnIdentifier= */
                "foo"
            )
        )
        val action: NullAction = NullAction(createOutputArtifact("bar/out"))
        module.actionComplete(
            ActionCompletionEvent(
                0,
                0,
                action,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        module.buildComplete(BuildCompleteEvent(BuildResult(1000)))

        Truth.assertThat(parse(buffer))
            .containsExactly(
                executionGraphNodeBuilderForSpawnBuilderSpawn()
                    .setIndex(0)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(100)
                            .setDurationMillis(200)
                            .setOtherMillis(200)
                    )
                    .setRuleClass("dummy-target-kind")
                    .setRunner("remote")
                    .setIdentifier("foo")
                    .build(),
                executionGraphNodeBuilderForAction(action)
                    .setIndex(1)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(nanosToMillis.toEpochMillis(0))
                    )
                    .setRuleClass("dummy-kind")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noSpawnAction_hasCorrectDuration() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        startLogging(eventBus, buffer, DependencyInfo.ALL)
        val nanosToMillis: com.google.devtools.build.lib.clock.BlazeClock.NanosToMillisSinceEpochConverter =
            com.google.devtools.build.lib.clock.BlazeClock.createNanosToMillisSinceEpochConverter()
        module.setNanosToMillis(nanosToMillis)

        val action: NullAction = NullAction(createOutputArtifact("foo/out"))
        module.actionComplete(
            ActionCompletionEvent(
                1000000,
                2000000,
                action,
                FakeActionInputFileCache(),
                < T > mock < T ? > (OutputMetadataStore::class.java),
            < T > mock < T ? > (ActionLookupData::class.java)))
        module.buildComplete(BuildCompleteEvent(BuildResult(1000)))

        Truth.assertThat(parse(buffer))
            .containsExactly(
                executionGraphNodeBuilderForAction(action)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(nanosToMillis.toEpochMillis(1000000))
                            .setDurationMillis(1)
                            .setProcessMillis(1)
                    )
                    .setRuleClass("dummy-kind")
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleSpawnsWithSameOutput_recordsBothSpawnsWithRetry() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        startLogging(eventBus, buffer, DependencyInfo.ALL)
        val localResult: SpawnResult = createLocalSpawnResult(100)
        val remoteResult: SpawnResult = createRemoteSpawnResult(200)
        val spawn: Spawn =
            SpawnBuilder().withOwnerPrimaryOutput(createOutputArtifact("foo/out")).build()

        module.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                localResult,
                Instant.EPOCH,  /* spawnIdentifier= */
                "foo1"
            )
        )
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                remoteResult,
                Instant.ofEpochMilli(100),  /* spawnIdentifier= */
                "foo2"
            )
        )
        module.buildComplete(BuildCompleteEvent(BuildResult(1000)))

        val nodes: com.google.common.collect.ImmutableList<ExecutionGraph.Node?> = parse(buffer)
        Truth.assertThat(nodes)
            .containsExactly(
                executionGraphNodeBuilderForSpawnBuilderSpawn()
                    .setIndex(0)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(0)
                            .setDurationMillis(100)
                            .setOtherMillis(100)
                    )
                    .setRunner("local")
                    .setIdentifier("foo1")
                    .build(),
                executionGraphNodeBuilderForSpawnBuilderSpawn()
                    .setIndex(1)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(100)
                            .setDurationMillis(200)
                            .setOtherMillis(200)
                    )
                    .setRunner("remote")
                    .setIdentifier("foo2")
                    .setRetryOf(0)
                    .build()
            )
            .inOrder()
    }

    internal enum class LocalLockFreeOutput(private val optionValue: Boolean) {
        LOCAL_LOCK_FREE_OUTPUT_ENABLED( /* optionValue= */true) {
            override fun assertBugReport(bugReporter: BugReporter?) {
                Mockito.verify<BugReporter?>(bugReporter, Mockito.never())
                    .sendNonFatalBugReport(ArgumentMatchers.any<Throwable?>())
            }
        },
        LOCAL_LOCK_FREE_OUTPUT_DISABLED( /* optionValue= */false) {
            override fun assertBugReport(bugReporter: BugReporter?) {
                val captor: ArgumentCaptor<java.lang.Exception?> =
                    ArgumentCaptor.forClass<java.lang.Exception?, java.lang.Exception?>(java.lang.Exception::class.java)
                Mockito.verify<BugReporter?>(bugReporter).sendNonFatalBugReport(captor.capture())
                Truth.assertThat(captor.getValue())
                    .hasMessageThat()
                    .contains("Multiple spawns produced 'output/foo/out' with overlapping execution time.")
            }
        };

        abstract fun assertBugReport(bugReporter: BugReporter?)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleSpawnsWithSameOutput_overlapping_recordsBothSpawnsWithoutRetry(
        @TestParameter localLockFreeOutput: LocalLockFreeOutput
    ) {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val bugReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)
        startLogging(
            eventBus,
            bugReporter,
            localLockFreeOutput.optionValue,  /* logFileWriteEdges= */
            false,
            buffer,
            DependencyInfo.ALL
        )
        val localResult: SpawnResult = createLocalSpawnResult(100)
        val remoteResult: SpawnResult = createRemoteSpawnResult(200)
        val spawn: Spawn =
            SpawnBuilder().withOwnerPrimaryOutput(createOutputArtifact("foo/out")).build()

        module.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                localResult,
                Instant.EPOCH,  /* spawnIdentifier= */
                "foo1"
            )
        )
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                remoteResult,
                Instant.ofEpochMilli(10),  /* spawnIdentifier= */
                "foo2"
            )
        )
        module.buildComplete(BuildCompleteEvent(BuildResult(1000)))

        val nodes: com.google.common.collect.ImmutableList<ExecutionGraph.Node?> = parse(buffer)
        Truth.assertThat(nodes)
            .containsExactly(
                executionGraphNodeBuilderForSpawnBuilderSpawn()
                    .setIndex(0)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(0)
                            .setDurationMillis(100)
                            .setOtherMillis(100)
                    )
                    .setRunner("local")
                    .setIdentifier("foo1")
                    .build(),
                executionGraphNodeBuilderForSpawnBuilderSpawn()
                    .setIndex(1)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(10)
                            .setDurationMillis(200)
                            .setOtherMillis(200)
                    )
                    .setRunner("remote")
                    .setIdentifier("foo2")
                    .build()
            )
            .inOrder()
        localLockFreeOutput.assertBugReport(bugReporter)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleSpawnsWithSameOutput_overlapping_ignoresSecondSpawnForDependencies() {
        val buffer: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        startLogging(
            eventBus,
            BugReporter.defaultInstance(),  /* localLockFreeOutputEnabled= */
            true,  /* logFileWriteEdges= */
            false,
            buffer,
            DependencyInfo.ALL
        )
        val localResult: SpawnResult = createLocalSpawnResult(100)
        val remoteResult: SpawnResult = createRemoteSpawnResult(200)
        val input: Artifact = createOutputArtifact("foo/input")
        val spawn: Spawn = SpawnBuilder().withOwnerPrimaryOutput(input).build()
        val dependentSpawn: Spawn =
            SpawnBuilder()
                .withOwnerPrimaryOutput(createOutputArtifact("foo/output"))
                .withInput(input)
                .build()
        val dependentResult: SpawnResult = createRemoteSpawnResult(300)

        module.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                localResult,
                Instant.EPOCH,  /* spawnIdentifier= */
                "foo1"
            )
        )
        module.spawnExecuted(
            SpawnExecutedEvent(
                spawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                remoteResult,
                Instant.ofEpochMilli(10),  /* spawnIdentifier= */
                "foo2"
            )
        )
        module.spawnExecuted(
            SpawnExecutedEvent(
                dependentSpawn,
                FakeActionInputFileCache(),
                null,
                TestFileOutErr(),
                dependentResult,
                Instant.ofEpochMilli(300),  /* spawnIdentifier= */
                "foo3"
            )
        )
        module.buildComplete(BuildCompleteEvent(BuildResult(1000)))

        val nodes: com.google.common.collect.ImmutableList<ExecutionGraph.Node?> = parse(buffer)
        Truth.assertThat(nodes)
            .containsExactly(
                executionGraphNodeBuilderForSpawnBuilderSpawn()
                    .setIndex(0)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(0)
                            .setDurationMillis(100)
                            .setOtherMillis(100)
                    )
                    .setRunner("local")
                    .setIdentifier("foo1")
                    .build(),
                executionGraphNodeBuilderForSpawnBuilderSpawn()
                    .setIndex(1)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(10)
                            .setDurationMillis(200)
                            .setOtherMillis(200)
                    )
                    .setRunner("remote")
                    .setIdentifier("foo2")
                    .build(),
                executionGraphNodeBuilderForSpawnBuilderSpawn()
                    .setIndex(2)
                    .setMetrics(
                        ExecutionGraph.Metrics.newBuilder()
                            .setStartTimestampMillis(300)
                            .setDurationMillis(300)
                            .setOtherMillis(300)
                    )
                    .setRunner("remote")
                    .setIdentifier("foo3")
                    .addDependentIndex(0)
                    .build()
            )
            .inOrder()
    }

    private open inner class FakeOwnerWithPrimaryOutput(
        mnemonic: String?,
        progressMessage: String,
        ownerLabel: String?,
        private val primaryOutput: String?
    ) : FakeOwner(mnemonic, progressMessage, ownerLabel) {
        override fun getPrimaryOutput(): Artifact {
            return ActionsTestUtil.createArtifactWithExecPath(
                artifactRoot, PathFragment.create(primaryOutput)
            )
        }
    }

    private fun createOutputArtifact(rootRelativePath: String?): Artifact {
        val artifact: DerivedArtifact =
            ActionsTestUtil.createArtifactWithExecPath(
                artifactRoot, artifactRoot.getExecPath().getRelative(rootRelativePath)
            ) as DerivedArtifact
        artifact.setGeneratingActionKey(ActionsTestUtil.NULL_ACTION_LOOKUP_DATA)
        return artifact
    }

    companion object {
        @Throws(IOException::class)
        private fun parse(buffer: java.io.ByteArrayOutputStream): com.google.common.collect.ImmutableList<ExecutionGraph.Node?> {
            val data: ByteArray = buffer.toByteArray()
            ZstdInputStream(ByteArrayInputStream(data)).use { `in` ->
                val nodeListBuilder: com.google.common.collect.ImmutableList.Builder<ExecutionGraph.Node?> =
                    com.google.common.collect.ImmutableList.Builder<ExecutionGraph.Node?>()
                var node: ExecutionGraph.Node?
                while ((ExecutionGraph.Node.parseDelimitedFrom(`in`).also { node = it }) != null) {
                    nodeListBuilder.add(node)
                }
                return nodeListBuilder.build()
            }
        }

        private fun createLocalSpawnResult(totalTimeInMs: Int): SpawnResult {
            return Builder()
                .setRunnerName("local")
                .setStatus(Status.SUCCESS)
                .setExitCode(0)
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forLocalExec().setTotalTimeInMs(totalTimeInMs).build()
                )
                .build()
        }

        private fun createRemoteSpawnResult(totalTimeInMs: Int): SpawnResult {
            return Builder()
                .setRunnerName("remote")
                .setStatus(Status.SUCCESS)
                .setExitCode(0)
                .setSpawnMetrics(
                    SpawnMetrics.Builder.forRemoteExec().setTotalTimeInMs(totalTimeInMs).build()
                )
                .build()
        }

        /**
         * Creates a [ExecutionGraph.Node.Builder] with pre-populated defaults for spawns created
         * using [SpawnBuilder].
         */
        private fun executionGraphNodeBuilderForSpawnBuilderSpawn(): ExecutionGraph.Node.Builder {
            return ExecutionGraph.Node.newBuilder()
                .setDescription("action 'progress message'")
                .setTargetLabel("//dummy:label")
                .setMnemonic("Mnemonic")
                .setRuleClass("dummy-target-kind") // This comes from SpawnResult.Builder, which defaults to an empty string.
                .setRunnerSubtype("")
        }

        /**
         * Creates a [ExecutionGraph.Node.Builder] with pre-populated defaults for action events.
         */
        private fun executionGraphNodeBuilderForAction(action: Action): ExecutionGraph.Node.Builder {
            return ExecutionGraph.Node.newBuilder()
                .setDescription(action.prettyPrint())
                .setTargetLabel(action.getOwner().getLabel().toString())
                .setMnemonic(action.getMnemonic())
        }
    }
}
