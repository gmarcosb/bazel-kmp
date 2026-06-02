// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.vfs.FileSystemUtils.readContent

@RunWith(TestParameterInjector::class)
class ActionCacheCheckerTest {
    private var cache: CorruptibleActionCache? = null
    private var cacheChecker: ActionCacheChecker? = null
    private var filesToDelete: MutableSet<Path>? = null
    private var digestHashFunction: DigestHashFunction? = null
    private var fileSystem: FileSystem? = null
    private var execRoot: Path? = null
    private var artifactRoot: ArtifactRoot? = null
    private val proxyMetadataFactory: ProxyMetadataFactory =
        Mockito.mock<ProxyMetadataFactory>(ProxyMetadataFactory::class.java)

    @Before
    @Throws(java.lang.Exception::class)
    fun setupCache() {
        val scratch: Scratch = Scratch()
        val clock: com.google.devtools.build.lib.clock.Clock = com.google.devtools.build.lib.testutil.ManualClock()
        val cacheRoot: Path = scratch.resolve("/cache_root")
        val corruptedCacheRoot: Path = scratch.resolve("/corrupted_cache_root")
        val tmpDir: Path = scratch.resolve("/cache_tmp_dir")

        execRoot = scratch.resolve("/output")
        cache = CorruptibleActionCache(cacheRoot, corruptedCacheRoot, tmpDir, clock)
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/false)
        digestHashFunction = DigestHashFunction.SHA256
        fileSystem = InMemoryFileSystem(digestHashFunction)
        artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "bin")
    }

    private fun digest(content: ByteArray?): ByteArray {
        return digestHashFunction.getHashFunction().hashBytes(content).asBytes()
    }

    private fun createActionCacheChecker(storeOutputMetadata: Boolean): ActionCacheChecker {
        return ActionCacheChecker(
            cache,
            FakeArtifactResolverBase(),
            ActionKeyContext(),
            { action -> true },
            proxyMetadataFactory,
            ActionCacheChecker.CacheConfig.builder()
                .setEnabled(true)
                .setStoreOutputMetadata(storeOutputMetadata)
                .build()
        )
    }

    @Before
    fun clearFilesToDeleteAfterTest() {
        filesToDelete = HashSet<Path>()
    }

    @org.junit.After
    @Throws(java.lang.Exception::class)
    fun deleteFilesCreatedDuringTest() {
        for (path in filesToDelete!!) {
            if (path.isDirectory()) {
                path.deleteTree()
            } else {
                path.delete()
            }
        }
    }

    /** "Executes" the given action from the point of view of the cache's lifecycle.  */
    @Throws(java.lang.Exception::class)
    private fun runAction(action: Action?) {
        runAction(action, com.google.common.collect.ImmutableMap.of<String?, String?>())
    }

    @Throws(java.lang.Exception::class)
    private fun runAction(
        action: Action?,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore?
    ) {
        runAction(
            action,
            com.google.common.collect.ImmutableMap.of<String?, String?>(),
            "",
            inputMetadataProvider,
            outputMetadataStore
        )
    }

    /**
     * "Executes" the given action from the point of view of the cache's lifecycle with a custom
     * client environment.
     */
    @Throws(java.lang.Exception::class)
    private fun runAction(action: Action?, clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?) {
        runAction(action, clientEnv, "")
    }

    @Throws(java.lang.Exception::class)
    private fun runAction(
        action: Action?,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
        actionExecutionSalt: String?
    ) {
        val metadataHandler = FakeInputMetadataHandler()
        runAction(action, clientEnv, actionExecutionSalt, metadataHandler, metadataHandler)
    }

    @Throws(java.lang.Exception::class)
    private fun runAction(
        action: Action?,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
        actionExecutionSalt: String?,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore?
    ) {
        runAction(
            action,
            clientEnv,
            actionExecutionSalt,
            inputMetadataProvider,
            outputMetadataStore,
            OutputChecker.TRUST_ALL
        )
    }

    @Throws(java.lang.Exception::class)
    private fun runAction(
        action: Action,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
        actionExecutionSalt: String?,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore?,
        outputChecker: OutputChecker?
    ) {
        runAction(
            action,
            clientEnv,
            actionExecutionSalt,
            inputMetadataProvider,
            outputMetadataStore,
            outputChecker,  /* useArchivedTreeArtifacts= */
            false
        )
    }

    @Throws(java.lang.Exception::class)
    private fun runAction(
        action: Action,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
        actionExecutionSalt: String?,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore?,
        outputChecker: OutputChecker?,
        useArchivedTreeArtifacts: Boolean
    ) {
        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,
                clientEnv,
                OutputPermissions.READONLY,  /* handler= */
                null,
                inputMetadataProvider,
                outputMetadataStore,
                actionExecutionSalt,
                outputChecker,  /* useArchivedTreeArtifacts= */
                useArchivedTreeArtifacts
            )
        runAction(
            action,
            clientEnv,
            actionExecutionSalt,
            inputMetadataProvider,
            outputMetadataStore,
            token,
            useArchivedTreeArtifacts
        )
    }

    @Throws(java.lang.Exception::class)
    private fun runAction(
        action: Action,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
        actionExecutionSalt: String?,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore?,
        token: Token?
    ) {
        runAction(
            action,
            clientEnv,
            actionExecutionSalt,
            inputMetadataProvider,
            outputMetadataStore,
            token,  /* useArchivedTreeArtifacts= */
            false
        )
    }

    @Throws(java.lang.Exception::class)
    private fun runAction(
        action: Action,
        clientEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
        actionExecutionSalt: String?,
        inputMetadataProvider: InputMetadataProvider?,
        outputMetadataStore: OutputMetadataStore?,
        token: Token?,
        useArchivedTreeArtifacts: Boolean
    ) {
        if (token != null) {
            for (artifact in action.getOutputs()) {
                val path: Path = artifact.getPath()

                // Record all action outputs as files to be deleted across tests to prevent cross-test
                // pollution.  We need to do this on a path basis because we don't know upfront which file
                // system they live in so we cannot just recreate the file system.  (E.g. all NullActions
                // share an in-memory file system to hold dummy outputs.)
                filesToDelete!!.add(path)

                val parent: Path? = path.getParentDirectory()
                if (parent != null) {
                    parent.createDirectoryAndParents()
                }
            }

            // Real action execution would happen here.
            val context: ActionExecutionContext =
                Mockito.mock<ActionExecutionContext>(ActionExecutionContext::class.java)
            Mockito.`when`<T?>(context.getOutputMetadataStore()).thenReturn(outputMetadataStore)
            action.execute(context)

            cacheChecker.updateActionCache(
                action,
                token,
                inputMetadataProvider,
                outputMetadataStore,
                clientEnv,
                OutputPermissions.READONLY,
                actionExecutionSalt,
                useArchivedTreeArtifacts
            )
        }
    }

    /** Ensures that the cache statistics match exactly the given values.  */
    private fun assertStatistics(hits: Int, misses: Iterable<MissDetail?>?) {
        val builder: ActionCacheStatistics.Builder = ActionCacheStatistics.newBuilder()
        cache!!.mergeIntoActionCacheStatistics(builder)
        val stats: ActionCacheStatistics = builder.build()

        assertThat(stats.getHits()).isEqualTo(hits)
        assertThat(stats.getMissDetailsList()).containsExactlyElementsIn(misses)
    }

    @Throws(java.lang.Exception::class)
    private fun doTestNotCached(action: Action?, missReason: MissReason?) {
        runAction(action)

        assertStatistics(0, MissDetailsBuilder().set(missReason, 1).build())
    }

    @Throws(java.lang.Exception::class)
    private fun doTestCached(action: Action?, missReason: MissReason?) {
        val runs = 5
        for (i in 0..<runs) {
            runAction(action)
        }

        assertStatistics(runs - 1, MissDetailsBuilder().set(missReason, 1).build())
    }

    @Throws(java.lang.Exception::class)
    private fun doTestCorruptedCacheEntry(action: Action?) {
        cache!!.corruptAllEntries()
        runAction(action)

        assertStatistics(
            0,
            MissDetailsBuilder().set(MissReason.CORRUPTED_CACHE_ENTRY, 1).build()
        )
    }

    @org.junit.Test
    fun testNoActivity() {
        assertStatistics(0, MissDetailsBuilder().build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNotCached() {
        doTestNotCached(WriteEmptyOutputAction(), MissReason.NOT_CACHED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCached() {
        doTestCached(WriteEmptyOutputAction(), MissReason.NOT_CACHED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCorruptedCacheEntry() {
        doTestCorruptedCacheEntry(WriteEmptyOutputAction())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDifferentActionKey() {
        var action: Action =
            object : WriteEmptyOutputAction() {
                override fun computeKey(
                    actionKeyContext: ActionKeyContext?,
                    inputMetadataProvider: InputMetadataProvider?,
                    fp: Fingerprint
                ) {
                    fp.addString("key1")
                }
            }
        runAction(action)
        action =
            object : NullAction() {
                override fun computeKey(
                    actionKeyContext: ActionKeyContext?,
                    inputMetadataProvider: InputMetadataProvider?,
                    fp: Fingerprint
                ) {
                    fp.addString("key2")
                }
            }
        runAction(action)

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.DIGEST_MISMATCH, 1)
                .set(MissReason.NOT_CACHED, 1)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDifferentEnvironment() {
        val action: Action =
            object : WriteEmptyOutputAction() {
                public override fun getClientEnvironmentVariables(): com.google.common.collect.ImmutableList<String?> {
                    return com.google.common.collect.ImmutableList.of<String?>("used-var")
                }
            }

        runAction(action, com.google.common.collect.ImmutableMap.of<String?, String?>("unused-var", "1")) // Not cached.
        runAction(
            action, com.google.common.collect.ImmutableMap.of<String?, String?>()
        ) // Cache hit because we only modified uninteresting variables.
        runAction(
            action, com.google.common.collect.ImmutableMap.of<String?, String?>("used-var", "2")
        ) // Cache miss because of different environment.
        runAction(
            action, com.google.common.collect.ImmutableMap.of<String?, String?>("used-var", "2")
        ) // Cache hit because we did not change anything.

        assertStatistics(
            2,
            MissDetailsBuilder()
                .set(MissReason.DIGEST_MISMATCH, 1)
                .set(MissReason.NOT_CACHED, 1)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDifferentSalt() {
        val action: Action = WriteEmptyOutputAction()
        val env: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("unused-var", "1")

        // Not cached.
        runAction(action, env, "foo")
        // Cache hit because actionExecutionSalt did not change.
        runAction(action, env, "foo")
        // Cache miss because actionExecutionSalt changed.
        runAction(action, env, "bar")

        assertStatistics(
            1,
            MissDetailsBuilder()
                .set(MissReason.DIGEST_MISMATCH, 1)
                .set(MissReason.NOT_CACHED, 1)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDifferentFiles() {
        val action: Action = WriteEmptyOutputAction()
        runAction(action) // Not cached.
        assertThat(readContent(action.getPrimaryOutput().getPath(), java.nio.charset.StandardCharsets.UTF_8)).isEmpty()
        writeContentAsLatin1(action.getPrimaryOutput().getPath(), "modified")
        runAction(action) // Cache miss because output files were modified.

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.DIGEST_MISMATCH, 1)
                .set(MissReason.NOT_CACHED, 1)
                .build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnconditionalExecution() {
        val action: Action =
            object : WriteEmptyOutputAction() {
                public override fun executeUnconditionally(): Boolean {
                    return true
                }

                public override fun isVolatile(): Boolean {
                    return true
                }
            }

        val runs = 5
        for (i in 0..<runs) {
            runAction(action)
        }

        assertStatistics(
            0, MissDetailsBuilder().set(MissReason.UNCONDITIONAL_EXECUTION, runs).build()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeletedConstantMetadataOutputCausesReexecution() {
        val output: SpecialArtifact =
            SpecialArtifact.create(
                artifactRoot,
                PathFragment.create("bin/dummy"),
                ActionsTestUtil.Companion.NULL_ARTIFACT_OWNER,
                SpecialArtifactType.CONSTANT_METADATA
            )
        output.getPath().getParentDirectory().createDirectoryAndParents()
        val action: Action = WriteEmptyOutputAction(output)
        runAction(action)
        output.getPath().delete()
        val fakeMetadataHandler = FakeInputMetadataHandler()
        assertThat(
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                fakeMetadataHandler,
                fakeMetadataHandler,  /* actionExecutionSalt= */
                "",
                OutputChecker.TRUST_ALL,  /* useArchivedTreeArtifacts= */
                false
            )
        )
            .isNotNull()
    }

    private fun createRemoteMetadata(content: String): FileArtifactValue {
        return createRemoteMetadata(content,  /* resolvedPath= */null)
    }

    private fun createRemoteMetadata(
        content: String, resolvedPath: PathFragment?
    ): FileArtifactValue {
        val bytes: ByteArray = content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        var metadata: FileArtifactValue =
            FileArtifactValue.createForRemoteFileWithMaterializationData(
                digest(bytes), bytes.size, 1,  /* expirationTime= */null
            )
        if (resolvedPath != null) {
            metadata = FileArtifactValue.createFromExistingWithResolvedPath(metadata, resolvedPath)
        }
        return metadata
    }

    private fun createRemoteMetadata(
        content: String, expirationTime: Instant?, resolvedPath: PathFragment?
    ): FileArtifactValue? {
        val bytes: ByteArray = content.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        var metadata: FileArtifactValue? =
            FileArtifactValue.createForRemoteFileWithMaterializationData(
                digest(bytes), bytes.size, 1, expirationTime
            )
        if (resolvedPath != null) {
            metadata = FileArtifactValue.createFromExistingWithResolvedPath(metadata, resolvedPath)
        }
        return metadata
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_remoteFileMetadataSaved() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val action: Action = InjectOutputFileMetadataAction(output, createRemoteMetadata(content))

        // Not cached.
        runAction(action)

        assertThat(output.getPath().exists()).isFalse()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputFile(output)).isEqualTo(createRemoteMetadata(content))
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_localFileMetadataNotSaved() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val action: Action = WriteEmptyOutputAction(output)
        output.getPath().delete()

        runAction(action)

        assertThat(output.getPath().exists()).isTrue()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputFile(output)).isNull()
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_remoteMetadataInjectedAndLocalFilesStored() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val action: Action =
            object : WriteEmptyOutputAction(output) {
                override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult? {
                    actionExecutionContext
                        .getOutputMetadataStore()
                        .injectFile(output, createRemoteMetadata(""))
                    return super.execute(actionExecutionContext)
                }
            }
        output.getPath().delete()

        runAction(action)

        assertThat(output.getPath().exists()).isTrue()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputFile(output)).isEqualTo(createRemoteMetadata(""))
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_notSavedIfDisabled() {
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val action: Action = InjectOutputFileMetadataAction(output, createRemoteMetadata(content))

        runAction(action)

        assertThat(output.getPath().exists()).isFalse()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputFile(output)).isNull()
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_remoteFileMetadataLoaded() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val action: Action = InjectOutputFileMetadataAction(output, createRemoteMetadata(content))
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                OutputChecker.TRUST_ALL,  /* useArchivedTreeArtifacts= */
                false
            )

        assertThat(output.getPath().exists()).isFalse()
        assertThat(token).isNull()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputFile(output)).isEqualTo(createRemoteMetadata(content))
        assertThat(metadataHandler.getOutputMetadata(output)).isEqualTo(createRemoteMetadata(content))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_remoteFileExpired_remoteFileMetadataNotLoaded() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val action: Action =
            InjectOutputFileMetadataAction(
                output,
                createRemoteMetadata(
                    content,  /* expirationTime= */Instant.ofEpochMilli(1),  /* resolvedPath= */null
                )
            )
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                CHECK_TTL,  /* useArchivedTreeArtifacts= */
                false
            )

        assertThat(output.getPath().exists()).isFalse()
        assertThat(token).isNotNull()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_storeOutputMetadataDisabled_remoteFileMetadataNotLoaded() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */false)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val action: Action = InjectOutputFileMetadataAction(output, createRemoteMetadata(content))
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",  /* outputChecker= */
                null,  /* useArchivedTreeArtifacts= */
                false
            )

        assertThat(output.getPath().exists()).isFalse()
        assertThat(token).isNotNull()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_localMetadataIsSameAsRemoteMetadata_cached(
        @TestParameter hasResolvedPath: Boolean
    ) {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val resolvedPath: PathFragment? =
            if (hasResolvedPath) execRoot.getRelative("some/path").asFragment() else null
        val action: Action =
            InjectOutputFileMetadataAction(output, createRemoteMetadata(content, resolvedPath))
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        writeContentAsLatin1(output.getPath(), content)
        // Cached since local metadata is same as remote metadata
        runAction(action)

        assertStatistics(1, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputFile(output)).isEqualTo(createRemoteMetadata(content, resolvedPath))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_localMetadataIsDifferentFromRemoteMetadata_notCached() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content1 = "content1"
        val content2 = "content2"
        val action: Action =
            InjectOutputFileMetadataAction(
                output, createRemoteMetadata(content1), createRemoteMetadata(content2)
            )
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        writeContentAsLatin1(output.getPath(), content2)

        // Assert that if local file exists, shouldTrustArtifact is not called for the remote
        // metadata.
        val metadataHandler = FakeInputMetadataHandler()
        val outputChecker: OutputChecker? = Mockito.mock<OutputChecker?>(OutputChecker::class.java)
        val token: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                outputChecker,  /* useArchivedTreeArtifacts= */
                false
            )
        Mockito.verify<Any?>(outputChecker)
            .shouldTrustMetadata(ArgumentMatchers.argThat<T?>(ArgumentMatcher { arg: T? ->
                arg.getExecPathString().endsWith("bin/dummy")
            }), ArgumentMatchers.any<T?>())
        // Not cached since local file changed
        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionExecutionSalt= */
            "",
            metadataHandler,
            metadataHandler,
            token
        )

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.NOT_CACHED, 1)
                .set(MissReason.DIGEST_MISMATCH, 1)
                .build()
        )
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputFile(output)).isEqualTo(createRemoteMetadata(content2))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_trustedRemoteMetadataFromOutputStore_cached() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val metadata: FileArtifactValue = createRemoteMetadata(content)
        val action: Action = InjectOutputFileMetadataAction(output, metadata, metadata)
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        val fakeOutputMetadataStore = FakeInputMetadataHandler()
        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* actionExecutionSalt= */
            "",
            FakeInputMetadataHandler(),
            fakeOutputMetadataStore
        )
        assertStatistics(1, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
        Truth.assertThat(fakeOutputMetadataStore.fileMetadata).containsExactly(output, metadata)

        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputFile(output)).isEqualTo(metadata)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_untrustedRemoteMetadataFromOutputStore_notCached() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val metadata: FileArtifactValue = createRemoteMetadata(content)
        val action: Action = InjectOutputFileMetadataAction(output, metadata, metadata)
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        val outputChecker: OutputChecker = Mockito.mock<OutputChecker>(OutputChecker::class.java)
        Mockito.`when`<T?>(outputChecker.shouldTrustMetadata(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(false)

        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionExecutionSalt= */
            "",
            FakeInputMetadataHandler(),
            FakeInputMetadataHandler(),
            outputChecker
        )

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.NOT_CACHED, 1)
                .set(MissReason.DIGEST_MISMATCH, 1)
                .build()
        )

        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputFile(output)).isEqualTo(metadata)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadata_remoteFileMetadataSaved() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file1", createRemoteMetadata("content1"),
                "file2", createRemoteMetadata("content2")
            )
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    children,  /* archivedArtifactValue= */
                    java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )

        runAction(action)

        assertThat(output.getPath().exists()).isFalse()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(
                SerializableTreeArtifactValue(
                    children,  /* archivedFileValue= */
                    java.util.Optional.empty<T?>(),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadata_remoteArchivedArtifactSaved() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),
                    java.util.Optional.of<FileArtifactValue?>(createRemoteMetadata("content")),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )

        runAction(action)

        assertThat(output.getPath().exists()).isFalse()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(
                SerializableTreeArtifactValue( /* childValues= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* archivedFileValue= */
                    java.util.Optional.of<T?>(createRemoteMetadata("content")),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadata_resolvedPathSaved() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),  /* archivedArtifactValue= */
                    java.util.Optional.empty<FileArtifactValue?>(),
                    java.util.Optional.of<T?>(execRoot.getRelative("some/path").asFragment())
                )
            )

        runAction(action)

        assertThat(output.getPath().exists()).isFalse()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(
                SerializableTreeArtifactValue( /* childValues= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* archivedFileValue= */
                    java.util.Optional.empty<T?>(),
                    java.util.Optional.of<T?>(execRoot.getRelative("some/path").asFragment())
                )
            )
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_emptyTreeMetadata_saved() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val treeMetadata: TreeArtifactValue =
            createTreeMetadata(
                output,  /* children= */
                com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),  /* archivedArtifactValue= */
                java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        val action: Action = InjectOutputTreeMetadataAction(output, treeMetadata)
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                OutputChecker.TRUST_ALL,  /* useArchivedTreeArtifacts= */
                false
            )

        assertThat(token).isNull()
        assertThat(output.getPath().exists()).isFalse()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(
                SerializableTreeArtifactValue( /* childValues= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* archivedFileValue= */
                    java.util.Optional.empty<T?>(),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
        assertStatistics(1, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadata_localFileMetadataNotSaved() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        writeIsoLatin1(fileSystem.getPath("/file2"), "")
        val children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "file1", createRemoteMetadata("content1"),
                "file2", FileArtifactValue.createForTesting(fileSystem.getPath("/file2"))
            )
        fileSystem.getPath("/file2").delete()
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    children,  /* archivedArtifactValue= */
                    java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )

        runAction(action)

        assertThat(output.getPath().exists()).isFalse()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(
                SerializableTreeArtifactValue(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "file1",
                        createRemoteMetadata("content1")
                    ),  /* archivedFileValue= */
                    java.util.Optional.empty<T?>(),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadata_localArchivedArtifactNotSaved() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        writeIsoLatin1(fileSystem.getPath("/archive"), "")
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,  /* children= */
                    com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),
                    java.util.Optional.of<T?>(FileArtifactValue.createForTesting(fileSystem.getPath("/archive"))),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
        fileSystem.getPath("/archive").delete()

        runAction(action)

        assertThat(output.getPath().exists()).isFalse()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output).archivedFileValue()).isEmpty()
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadata_remoteFileMetadataLoaded() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file1", createRemoteMetadata("content1"),
                "file2", createRemoteMetadata("content2")
            )
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    children,  /* archivedArtifactValue= */
                    java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                OutputChecker.TRUST_ALL,  /* useArchivedTreeArtifacts= */
                false
            )

        val expectedMetadata: TreeArtifactValue =
            createTreeMetadata(
                output,
                children,  /* archivedArtifactValue= */
                java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        assertThat(token).isNull()
        assertThat(output.getPath().exists()).isFalse()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(SerializableTreeArtifactValue.create(expectedMetadata))
        assertThat(metadataHandler.getTreeArtifactValue(output)).isEqualTo(expectedMetadata)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadata_localFileMetadataLoaded() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val children1: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file1", createRemoteMetadata("content1"),
                "file2", createRemoteMetadata("content2")
            )
        val children2: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file1", createRemoteMetadata("content1"),
                "file2", createRemoteMetadata("modified_remote")
            )
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    children1,  /* archivedArtifactValue= */
                    java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                ),
                createTreeMetadata(
                    output,
                    children2,  /* archivedArtifactValue= */
                    java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        writeIsoLatin1(output.getPath().getRelative("file2"), "modified_local")
        val outputChecker: OutputChecker = Mockito.mock<OutputChecker>(OutputChecker::class.java)
        Mockito.`when`<T?>(outputChecker.shouldTrustMetadata(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(true)
        val token: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                outputChecker,  /* useArchivedTreeArtifacts= */
                false
            )
        Mockito.verify<Any?>(outputChecker)
            .shouldTrustMetadata(ArgumentMatchers.argThat<T?>(ArgumentMatcher { arg: T? ->
                arg.getExecPathString().endsWith("file1")
            }), ArgumentMatchers.any<T?>())
        Mockito.verify<Any?>(outputChecker)
            .shouldTrustMetadata(ArgumentMatchers.argThat<T?>(ArgumentMatcher { arg: T? ->
                arg.getExecPathString().endsWith("file2")
            }), ArgumentMatchers.any<T?>())
        // Not cached since local file changed
        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionExecutionSalt= */
            "",
            metadataHandler,
            metadataHandler,
            token
        )

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.NOT_CACHED, 1)
                .set(MissReason.DIGEST_MISMATCH, 1)
                .build()
        )
        assertThat(output.getPath().exists()).isTrue()
        val expectedMetadata: TreeArtifactValue =
            createTreeMetadata(
                output,
                com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                    "file1", createRemoteMetadata("content1"),
                    "file2", createRemoteMetadata("modified_remote")
                ),  /* archivedArtifactValue= */
                java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(SerializableTreeArtifactValue.create(expectedMetadata))
        assertThat(metadataHandler.getTreeArtifactValue(output)).isEqualTo(expectedMetadata)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadata_localArchivedArtifactLoaded() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,  /* children= */
                    com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),  /* archivedArtifactValue= */
                    java.util.Optional.of<FileArtifactValue?>(createRemoteMetadata("content")),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                ),
                createTreeMetadata(
                    output,  /* children= */
                    com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),  /* archivedArtifactValue= */
                    java.util.Optional.of<FileArtifactValue?>(createRemoteMetadata("modified")),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        output.getPath().createDirectoryAndParents()
        writeIsoLatin1(ArchivedTreeArtifact.createForTree(output).getPath(), "modified")

        val outputChecker: OutputChecker = Mockito.mock<OutputChecker>(OutputChecker::class.java)
        Mockito.`when`<T?>(outputChecker.shouldTrustMetadata(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(true)
        val token: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                outputChecker,  /* useArchivedTreeArtifacts= */
                false
            )
        Mockito.`when`<T?>(outputChecker.shouldTrustMetadata(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(true)
        // Not cached since local file changed
        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionExecutionSalt= */
            "",
            metadataHandler,
            metadataHandler,
            token
        )

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.NOT_CACHED, 1)
                .set(MissReason.DIGEST_MISMATCH, 1)
                .build()
        )
        val expectedMetadata: TreeArtifactValue =
            createTreeMetadata(
                output,
                com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),
                java.util.Optional.of<FileArtifactValue?>(createRemoteMetadata("modified")),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(SerializableTreeArtifactValue.create(expectedMetadata))
        assertThat(metadataHandler.getTreeArtifactValue(output)).isEqualTo(expectedMetadata)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeFileExpired_treeMetadataNotLoaded() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file1", createRemoteMetadata("content1"),
                "file2",
                createRemoteMetadata(
                    "content2",  /* expirationTime= */
                    Instant.ofEpochMilli(1),  /* resolvedPath= */
                    null
                )
            )
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    children,  /* archivedArtifactValue= */
                    java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                CHECK_TTL,  /* useArchivedTreeArtifacts= */
                false
            )

        assertThat(output.getPath().exists()).isFalse()
        assertThat(token).isNotNull()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_archivedRepresentationExpired_treeMetadataNotLoaded() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file1", createRemoteMetadata("content1"),
                "file2", createRemoteMetadata("content2")
            )
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    children,  /* archivedArtifactValue= */
                    java.util.Optional.of<FileArtifactValue?>(
                        createRemoteMetadata(
                            "archived",  /* expirationTime= */
                            Instant.ofEpochMilli(1),  /* resolvedPath= */
                            null
                        )
                    ),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                CHECK_TTL,  /* useArchivedTreeArtifacts= */
                false
            )

        assertThat(output.getPath().exists()).isFalse()
        assertThat(token).isNotNull()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_toggleArchivedTreeArtifacts_notLoaded(
        @TestParameter initiallyEnabled: Boolean
    ) {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file1", createRemoteMetadata("content1"),
                "file2", createRemoteMetadata("content2")
            )
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    children,  /* archivedArtifactValue= */
                    if (initiallyEnabled)
                        java.util.Optional.of<FileArtifactValue?>(createRemoteMetadata("archived"))
                    else
                        java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
        val metadataHandler = FakeInputMetadataHandler()

        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionExecutionSalt= */
            "",
            metadataHandler,
            metadataHandler,
            OutputChecker.TRUST_ALL,
            initiallyEnabled
        )

        assertThat(cache!!.get(output.getExecPathString())).isNotNull()

        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                CHECK_TTL,
                !initiallyEnabled
            )

        assertThat(token).isNotNull()
        assertThat(cache!!.get(output.getExecPathString())).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadataWithSameLocalFileMetadata_cached() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file1", createRemoteMetadata("content1"),
                "file2", createRemoteMetadata("content2")
            )
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,
                    children,  /* archivedArtifactValue= */
                    java.util.Optional.empty<FileArtifactValue?>(),
                    java.util.Optional.empty<PathFragment?>()
                )
            )
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        writeContentAsLatin1(output.getPath().getRelative("file1"), "content1")
        // Cache hit
        val token: Token? =
            cacheChecker.getTokenIfNeedToExecute(
                action,  /* resolvedCacheArtifacts= */
                null,  /* clientEnv= */
                com.google.common.collect.ImmutableMap.of<K?, V?>(),
                OutputPermissions.READONLY,  /* handler= */
                null,
                metadataHandler,
                metadataHandler,  /* actionExecutionSalt= */
                "",
                OutputChecker.TRUST_ALL,  /* useArchivedTreeArtifacts= */
                false
            )

        assertThat(token).isNull()
        assertStatistics(1, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
        assertThat(output.getPath().exists()).isTrue()
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(
                SerializableTreeArtifactValue(
                    children,  /* archivedFileValue= */java.util.Optional.empty<T?>(), java.util.Optional.empty<T?>()
                )
            )

        assertThat(metadataHandler.getTreeArtifactValue(output))
            .isEqualTo(
                createTreeMetadata(
                    output,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "file1",
                        FileArtifactValue.createForTesting(output.getPath().getRelative("file1")),
                        "file2",
                        createRemoteMetadata("content2")
                    ),  /* archivedArtifactValue= */
                    java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_treeMetadataWithSameLocalArchivedArtifact_cached() {
        cacheChecker = createActionCacheChecker( /*storeOutputMetadata=*/true)
        val output: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val action: Action =
            InjectOutputTreeMetadataAction(
                output,
                createTreeMetadata(
                    output,  /* children= */
                    com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),  /* archivedArtifactValue= */
                    java.util.Optional.of<FileArtifactValue?>(createRemoteMetadata("content")),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
        val archivedArtifact: ArchivedTreeArtifact = ArchivedTreeArtifact.createForTree(output)
        val metadataHandler = FakeInputMetadataHandler()

        runAction(action)
        output.getPath().createDirectoryAndParents()
        writeContentAsLatin1(archivedArtifact.getPath(), "content")
        // Cache hit
        runAction(action, metadataHandler, metadataHandler)

        assertStatistics(1, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(output))
            .isEqualTo(
                SerializableTreeArtifactValue( /* childValues= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* archivedFileValue= */
                    java.util.Optional.of<T?>(createRemoteMetadata("content")),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
        assertThat(metadataHandler.getTreeArtifactValue(output))
            .isEqualTo(
                createTreeMetadata(
                    output,  /* children= */
                    com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),
                    java.util.Optional.of<T?>(FileArtifactValue.createForTesting(archivedArtifact)),  /* resolvedPath= */
                    java.util.Optional.empty<PathFragment?>()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_trustedRemoteTreeMetadataFromOutputStore_cached() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val tree: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file",
                createRemoteMetadata("content")
            )
        val treeMetadata: TreeArtifactValue =
            createTreeMetadata(
                tree,
                children,  /* archivedArtifactValue= */
                java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        val action: Action = InjectOutputTreeMetadataAction(tree, treeMetadata, treeMetadata)
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        val fakeOutputMetadataStore = FakeInputMetadataHandler()
        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* actionExecutionSalt= */
            "",
            FakeInputMetadataHandler(),
            fakeOutputMetadataStore
        )

        assertStatistics(1, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
        Truth.assertThat(fakeOutputMetadataStore.treeMetadata).containsExactly(tree, treeMetadata)

        val entry: ActionCache.Entry = cache!!.get(tree.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(tree))
            .isEqualTo(
                SerializableTreeArtifactValue(
                    children,  /* archivedFileValue= */
                    java.util.Optional.empty<T?>(),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_emptyTreeMetadataFromOutputStore_cached() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val tree: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val treeMetadata: TreeArtifactValue =
            createTreeMetadata(
                tree,  /* children= */
                com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(),  /* archivedArtifactValue= */
                java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        val action: Action = InjectOutputTreeMetadataAction(tree, treeMetadata, treeMetadata)
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        val fakeOutputMetadataStore = FakeInputMetadataHandler()
        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* actionExecutionSalt= */
            "",
            FakeInputMetadataHandler(),
            fakeOutputMetadataStore
        )

        assertStatistics(1, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
        Truth.assertThat(fakeOutputMetadataStore.treeMetadata).containsExactly(tree, treeMetadata)

        val entry: ActionCache.Entry = cache!!.get(tree.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(tree))
            .isEqualTo(
                SerializableTreeArtifactValue( /* childValues= */
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* archivedFileValue= */
                    java.util.Optional.empty<T?>(),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_untrustedRemoteTreeMetadataFromOutputStore_notCached() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val tree: SpecialArtifact =
            createTreeArtifactWithGeneratingAction(artifactRoot, PathFragment.create("bin/dummy"))
        val children: com.google.common.collect.ImmutableMap<String?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<String?, FileArtifactValue?>(
                "file",
                createRemoteMetadata("content")
            )
        val treeMetadata: TreeArtifactValue =
            createTreeMetadata(
                tree,
                children,  /* archivedArtifactValue= */
                java.util.Optional.empty<FileArtifactValue?>(),  /* resolvedPath= */
                java.util.Optional.empty<PathFragment?>()
            )
        val action: Action = InjectOutputTreeMetadataAction(tree, treeMetadata, treeMetadata)
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        val outputChecker: OutputChecker = Mockito.mock<OutputChecker>(OutputChecker::class.java)
        Mockito.`when`<T?>(outputChecker.shouldTrustMetadata(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(false)

        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<K?, V?>(),  /* actionExecutionSalt= */
            "",
            FakeInputMetadataHandler(),
            FakeInputMetadataHandler(),
            outputChecker
        )

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.NOT_CACHED, 1)
                .set(MissReason.DIGEST_MISMATCH, 1)
                .build()
        )

        val entry: ActionCache.Entry = cache!!.get(tree.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getOutputTree(tree))
            .isEqualTo(
                SerializableTreeArtifactValue(
                    children,  /* archivedFileValue= */
                    java.util.Optional.empty<T?>(),  /* resolvedPath= */
                    java.util.Optional.empty<T?>()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_sameProxyMetadata_cachedAndInjected() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val metadata: ProxyFileArtifactValue = createProxyMetadata(output, content)
        val action: Action = InjectOutputFileMetadataAction(output, metadata, metadata)
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        Mockito.`when`<T?>(proxyMetadataFactory.createProxyMetadata(output)).thenReturn(metadata)
        val fakeOutputMetadataStore = FakeInputMetadataHandler()

        // Hide the local metadata from the OutputMetadataStore, emulating an action file system.
        fakeOutputMetadataStore.fileMetadata.put(output, null)

        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* actionExecutionSalt= */
            "",
            FakeInputMetadataHandler(),
            fakeOutputMetadataStore
        )

        assertStatistics(1, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())
        Truth.assertThat(fakeOutputMetadataStore.fileMetadata).containsExactly(output, metadata)

        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getProxyOutputs()).containsExactly(output.getExecPathString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_differentProxyMetadata_notCached() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val metadata: ProxyFileArtifactValue = createProxyMetadata(output, content)
        val action: Action = InjectOutputFileMetadataAction(output, metadata, metadata)
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        Mockito.`when`<T?>(proxyMetadataFactory.createProxyMetadata(output))
            .thenReturn(createProxyMetadata(output, "changed"))
        val fakeOutputMetadataStore = FakeInputMetadataHandler()

        // Hide the local metadata from the OutputMetadataStore, emulating an action file system.
        fakeOutputMetadataStore.fileMetadata.put(output, null)

        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* actionExecutionSalt= */
            "",
            FakeInputMetadataHandler(),
            fakeOutputMetadataStore
        )

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.NOT_CACHED, 1)
                .set(MissReason.DIGEST_MISMATCH, 1)
                .build()
        )

        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getProxyOutputs()).containsExactly(output.getExecPathString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_nullProxyMetadata_notCached() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val metadata: ProxyFileArtifactValue = createProxyMetadata(output, content)
        val action: Action = InjectOutputFileMetadataAction(output, metadata, metadata)
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        Mockito.`when`<T?>(proxyMetadataFactory.createProxyMetadata(output)).thenReturn(null)
        val fakeOutputMetadataStore = FakeInputMetadataHandler()

        // Hide the local metadata from the OutputMetadataStore, emulating an action file system.
        fakeOutputMetadataStore.fileMetadata.put(output, null)

        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* actionExecutionSalt= */
            "",
            FakeInputMetadataHandler(),
            fakeOutputMetadataStore
        )

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.NOT_CACHED, 1)
                .set(MissReason.DIGEST_MISMATCH, 1)
                .build()
        )

        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getProxyOutputs()).containsExactly(output.getExecPathString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun saveOutputMetadata_ioExceptionProxyMetadata_notCached() {
        cacheChecker = createActionCacheChecker( /* storeOutputMetadata= */true)
        val output: Artifact = ActionsTestUtil.Companion.createArtifact(artifactRoot, "bin/dummy")
        val content = "content"
        val metadata: ProxyFileArtifactValue = createProxyMetadata(output, content)
        val action: Action = InjectOutputFileMetadataAction(output, metadata, metadata)
        runAction(action)
        assertStatistics(0, MissDetailsBuilder().set(MissReason.NOT_CACHED, 1).build())

        Mockito.`when`<T?>(proxyMetadataFactory.createProxyMetadata(output)).thenThrow(IOException("IO error"))
        val fakeOutputMetadataStore = FakeInputMetadataHandler()

        // Hide the local metadata from the OutputMetadataStore, emulating an action file system.
        fakeOutputMetadataStore.fileMetadata.put(output, null)

        runAction(
            action,  /* clientEnv= */
            com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* actionExecutionSalt= */
            "",
            FakeInputMetadataHandler(),
            fakeOutputMetadataStore
        )

        assertStatistics(
            0,
            MissDetailsBuilder()
                .set(MissReason.NOT_CACHED, 1)
                .set(MissReason.DIGEST_MISMATCH, 1)
                .build()
        )

        val entry: ActionCache.Entry = cache!!.get(output.getExecPathString())
        assertThat(entry).isNotNull()
        assertThat(entry.getProxyOutputs()).containsExactly(output.getExecPathString())
    }

    // TODO(tjgq): Add tests for cached tree artifacts with a materialization path. They should take
    // into account every combination of entirely/partially remote metadata and symlink present/not
    // present in the filesystem.
    /** An [ActionCache] that allows injecting corruption for testing.  */
    private class CorruptibleActionCache(
        cacheRoot: Path?,
        corruptedCacheRoot: Path?,
        tmpDir: Path?,
        clock: com.google.devtools.build.lib.clock.Clock?
    ) : ActionCache {
        private val delegate: CompactPersistentActionCache
        private var corrupted = false

        init {
            this.delegate =
                CompactPersistentActionCache.create(
                    cacheRoot, corruptedCacheRoot, tmpDir, clock, NullEventHandler.INSTANCE
                )
        }

        fun corruptAllEntries() {
            corrupted = true
        }

        public override fun get(key: String?): Entry {
            return if (corrupted) ActionCache.Entry.CORRUPTED else delegate.get(key)
        }

        public override fun put(key: String?, entry: Entry?) {
            delegate.put(key, entry)
        }

        public override fun remove(key: String?) {
            delegate.remove(key)
        }

        public override fun removeIf(predicate: java.util.function.Predicate<Entry?>?) {
            delegate.removeIf(predicate)
        }

        @Throws(IOException::class)
        public override fun save(): Long {
            return delegate.save()
        }

        public override fun clear() {
            delegate.clear()
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        public override fun trim(threshold: Float, maxAge: java.time.Duration?): ActionCache {
            return delegate.trim(threshold, maxAge)
        }

        public override fun dump(out: PrintStream?) {
            delegate.dump(out)
        }

        public override fun size(): Int {
            return delegate.size()
        }

        public override fun accountHit() {
            delegate.accountHit()
        }

        public override fun accountMiss(reason: MissReason?) {
            delegate.accountMiss(reason)
        }

        public override fun mergeIntoActionCacheStatistics(builder: ActionCacheStatistics.Builder?) {
            delegate.mergeIntoActionCacheStatistics(builder)
        }

        public override fun resetStatistics() {
            delegate.resetStatistics()
        }
    }

    /** A fake metadata handler that is able to obtain metadata from the file system.  */
    private class FakeInputMetadataHandler : FakeInputMetadataHandlerBase() {
        private val fileMetadata: MutableMap<Artifact?, FileArtifactValue?> = HashMap<Artifact?, FileArtifactValue?>()
        private val treeMetadata: MutableMap<SpecialArtifact?, TreeArtifactValue?> =
            HashMap<SpecialArtifact?, TreeArtifactValue?>()

        override fun injectFile(output: Artifact?, metadata: FileArtifactValue?) {
            fileMetadata.put(output, metadata)
        }

        override fun injectTree(treeArtifact: SpecialArtifact?, tree: TreeArtifactValue?) {
            treeMetadata.put(treeArtifact, tree)
        }

        @Throws(IOException::class)
        public override fun getInputMetadata(input: ActionInput?): FileArtifactValue? {
            if (input !is Artifact) {
                return null
            }

            return FileArtifactValue.createForTesting(input as Artifact?)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        override fun getOutputMetadata(output: Artifact): FileArtifactValue? {
            if (output.isTreeArtifact()) {
                val treeArtifactValue: TreeArtifactValue? = getTreeArtifactValue(output as SpecialArtifact)
                if (treeArtifactValue != null) {
                    return treeArtifactValue.getMetadata()
                } else {
                    return null
                }
            }

            if (fileMetadata.containsKey(output)) {
                return fileMetadata.get(output)
            }
            return FileArtifactValue.createForTesting(output)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        override fun getTreeArtifactValue(output: SpecialArtifact): TreeArtifactValue? {
            if (treeMetadata.containsKey(output)) {
                return treeMetadata.get(output)
            }

            val treeDir: Path = output.getPath()
            if (!treeDir.exists()) {
                throw FileNotFoundException(output.toString() + " does not exist")
            }

            val tree: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(output)
            TreeArtifactValue.visitTree(
                treeDir,
                { parentRelativePath, type, traversedSymlink ->
                    if (type === Dirent.Type.DIRECTORY) {
                        return@visitTree
                    }
                    val child: Artifact.TreeFileArtifact? =
                        Artifact.TreeFileArtifact.createTreeOutput(output, parentRelativePath)
                    val metadata: FileArtifactValue? =
                        FileArtifactValue.createForTesting(treeDir.getRelative(parentRelativePath))
                    synchronized(tree) {
                        tree.putChild(child, metadata)
                    }
                })

            val archivedTreeArtifact: ArchivedTreeArtifact = ArchivedTreeArtifact.createForTree(output)
            if (archivedTreeArtifact.getPath().exists()) {
                tree.setArchivedRepresentation(
                    archivedTreeArtifact,
                    FileArtifactValue.createForTesting(archivedTreeArtifact.getPath())
                )
            }

            return tree.build()
        }
    }

    private open class WriteEmptyOutputAction : NullAction {
        internal constructor()

        internal constructor(vararg outputs: Artifact?) : super(*outputs)

        override fun execute(actionExecutionContext: ActionExecutionContext?): ActionResult? {
            for (output in getOutputs()) {
                val path: Path = output.getPath()
                try {
                    writeContentAsLatin1(path, "")
                } catch (e: IOException) {
                    throw java.lang.IllegalStateException("Failed to create output", e)
                }
            }

            return super.execute(actionExecutionContext)
        }
    }

    private class InjectOutputFileMetadataAction(output: Artifact?, vararg metadata: FileArtifactValue?) :
        NullAction(output) {
        private val output: Artifact?
        private val metadataDeque: Deque<FileArtifactValue?>

        init {
            this.output = output
            this.metadataDeque = ArrayDeque<FileArtifactValue?>(
                com.google.common.collect.ImmutableList.copyOf<FileArtifactValue?>(metadata)
            )
        }

        override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult? {
            actionExecutionContext.getOutputMetadataStore().injectFile(output, metadataDeque.pop())
            return super.execute(actionExecutionContext)
        }
    }

    private class InjectOutputTreeMetadataAction(output: SpecialArtifact?, vararg metadata: TreeArtifactValue?) :
        NullAction(output) {
        private val output: SpecialArtifact?
        private val metadataDeque: Deque<TreeArtifactValue?>

        init {
            this.output = output
            this.metadataDeque = ArrayDeque<TreeArtifactValue?>(
                com.google.common.collect.ImmutableList.copyOf<TreeArtifactValue?>(metadata)
            )
        }

        override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult? {
            actionExecutionContext.getOutputMetadataStore().injectTree(output, metadataDeque.pop())
            return super.execute(actionExecutionContext)
        }
    }

    companion object {
        private val CHECK_TTL: OutputChecker = OutputChecker { file, metadata ->
            metadata.getExpirationTime() == null
                    || metadata.getExpirationTime().isAfter(Instant.now())
        }

        @Throws(IOException::class)
        private fun createProxyMetadata(artifact: Artifact, content: String?): ProxyFileArtifactValue {
            artifact.getPath().getParentDirectory().createDirectoryAndParents()
            FileSystemUtils.writeContentAsLatin1(artifact.getPath(), content)
            return ProxyFileArtifactValue(
                FileArtifactValue.createForTesting(artifact), artifact.getPath()
            )
        }

        private fun createTreeMetadata(
            parent: SpecialArtifact?,
            children: com.google.common.collect.ImmutableMap<String?, out FileArtifactValue?>,
            archivedArtifactValue: java.util.Optional<FileArtifactValue?>,
            resolvedPath: java.util.Optional<PathFragment?>
        ): TreeArtifactValue {
            val builder: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(parent)
            for (entry in children.entrySet()) {
                builder.putChild(
                    Artifact.TreeFileArtifact.createTreeOutput(parent, entry.getKey()), entry.getValue()
                )
            }
            archivedArtifactValue.ifPresent(
                java.util.function.Consumer { metadata: FileArtifactValue? ->
                    val artifact: ArchivedTreeArtifact? = ArchivedTreeArtifact.createForTree(parent)
                    builder.setArchivedRepresentation(
                        TreeArtifactValue.ArchivedRepresentation.create(artifact, metadata)
                    )
                })
            resolvedPath.ifPresent(builder::setResolvedPath)
            return builder.build()
        }

        @Throws(IOException::class)
        private fun writeContentAsLatin1(path: Path, content: String?) {
            val parent: Path? = path.getParentDirectory()
            if (parent != null) {
                parent.createDirectoryAndParents()
            }
            FileSystemUtils.writeContentAsLatin1(path, content)
        }
    }
}
