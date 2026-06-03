// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.remote.util.Utils.getFromFuture

/** Base test class for [AbstractActionInputPrefetcher] implementations.  */
abstract class ActionInputPrefetcherTestBase {
    private class DelayedChmodFileSystem(delegateFs: FileSystem?) : DelegateFileSystem(delegateFs) {
        private var chmodDelay: java.time.Duration = java.time.Duration.ZERO

        @Throws(IOException::class)
        public override fun chmod(path: PathFragment?, mode: Int) {
            if (!chmodDelay.isZero()) {
                try {
                    java.lang.Thread.sleep(chmodDelay.toMillis())
                } catch (e: java.lang.InterruptedException) {
                    throw java.lang.RuntimeException(e)
                }
            }
            super.chmod(path, mode)
        }

        fun setChmodDelay(chmodDelay: java.time.Duration) {
            this.chmodDelay = chmodDelay
        }
    }

    protected var fs: SpiedFileSystem? = null
    protected var execRoot: Path? = null
    protected var artifactRoot: ArtifactRoot? = null
    protected var tempPathGenerator: TempPathGenerator? = null
    protected var eventBus: com.google.common.eventbus.EventBus? = null

    protected var action: ActionExecutionMetadata? = null

    @Before
    @Throws(IOException::class)
    open fun setUp() {
        action = Mockito.mock<ActionExecutionMetadata>(ActionExecutionMetadata::class.java)
        Mockito.`when`<T?>(action.getMnemonic()).thenReturn("DummyAction")
        Mockito.`when`<T?>(action.getOwner()).thenReturn(ActionsTestUtil.Companion.NULL_ACTION_OWNER)

        fs =
            SpiedFileSystem.createSpy(
                DelayedChmodFileSystem(InMemoryFileSystem(DigestHashFunction.SHA256))
            )
        execRoot = fs.getPath("/exec")
        execRoot.createDirectoryAndParents()
        artifactRoot = ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, "root")
        artifactRoot.getRoot().asPath().createDirectoryAndParents()
        val tempDir: Path = fs.getPath("/tmp")
        tempDir.createDirectoryAndParents()
        tempPathGenerator = TempPathGenerator(tempDir)

        eventBus = com.google.common.eventbus.EventBus()
    }

    protected fun createRemoteArtifact(
        pathFragment: String?,
        contents: String,
        resolvedPath: PathFragment?,
        metadata: MutableMap<ActionInput?, FileArtifactValue?>,
        cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>?
    ): Artifact {
        val p: Path? = artifactRoot.getRoot().getRelative(pathFragment)
        val a: Artifact = ActionsTestUtil.createArtifact(artifactRoot, p)
        val contentsBytes: ByteArray = contents.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        val hashCode: com.google.common.hash.HashCode = HASH_FUNCTION.getHashFunction().hashBytes(contentsBytes)
        var f: FileArtifactValue? =
            FileArtifactValue.createForRemoteFileWithMaterializationData(
                hashCode.asBytes(),
                contentsBytes.size,  /* locationIndex= */
                1,  /* expirationTime= */
                null
            )
        if (resolvedPath != null) {
            f = FileArtifactValue.createFromExistingWithResolvedPath(f, resolvedPath)
        }
        metadata.put(a, f)
        if (cas != null) {
            cas.put(hashCode, contentsBytes)
        }
        return a
    }

    protected fun createRemoteArtifact(
        pathFragment: String?,
        contents: String,
        metadata: MutableMap<ActionInput?, FileArtifactValue?>,
        cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>?
    ): Artifact {
        return createRemoteArtifact(pathFragment, contents,  /* resolvedPath= */null, metadata, cas)
    }

    @Throws(IOException::class)
    protected fun createRemoteTreeArtifact(
        pathFragment: String?,
        localContentMap: MutableMap<String?, String?>,
        remoteContentMap: MutableMap<String?, String?>,
        resolvedPath: PathFragment?,
        metadata: MutableMap<ActionInput?, FileArtifactValue?>,
        cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>,
        isActionTemplateExpansion: Boolean
    ): Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> {
        val parent: SpecialArtifact? = createTreeArtifactWithGeneratingAction(artifactRoot, pathFragment)

        val treeBuilder: TreeArtifactValue.Builder = TreeArtifactValue.newBuilder(parent)
        for (entry in localContentMap.entries) {
            val child: TreeFileArtifact? =
                TreeFileArtifact.createTreeOutput(parent, PathFragment.create(entry.key))
            val contents: ByteArray = entry.value.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            val hashCode: com.google.common.hash.HashCode = HASH_FUNCTION.getHashFunction().hashBytes(contents)
            val childValue: FileArtifactValue? =
                FileArtifactValue.createForNormalFile(
                    hashCode.asBytes(),  /* proxy= */null, contents.size
                )
            treeBuilder.putChild(child, childValue)
            metadata.put(child, childValue)
            cas.put(hashCode, contents)
        }
        for (entry in remoteContentMap.entries) {
            val parentRelativePath: PathFragment? = PathFragment.create(entry.key)
            val child: TreeFileArtifact? =
                if (isActionTemplateExpansion)
                    TreeFileArtifact.createTemplateExpansionOutput(
                        parent,
                        parentRelativePath,
                        ActionsTestUtil.NULL_TEMPLATE_EXPANSION_ARTIFACT_OWNER
                    )
                else
                    TreeFileArtifact.createTreeOutput(parent, parentRelativePath)
            val contents: ByteArray = entry.value.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            val hashCode: com.google.common.hash.HashCode = HASH_FUNCTION.getHashFunction().hashBytes(contents)
            val childValue: FileArtifactValue? =
                FileArtifactValue.createForRemoteFileWithMaterializationData(
                    hashCode.asBytes(),
                    contents.size,  /* locationIndex= */
                    1,  /* expirationTime= */
                    null
                )
            treeBuilder.putChild(child, childValue)
            metadata.put(child, childValue)
            cas.put(hashCode, contents)
        }
        if (resolvedPath != null) {
            treeBuilder.setResolvedPath(resolvedPath)
        }
        val treeValue: TreeArtifactValue = treeBuilder.build()

        metadata.put(parent, treeValue.getMetadata())

        return Pair.of(parent, treeValue.getChildren().asList())
    }

    @Throws(IOException::class)
    protected fun createRemoteTreeArtifact(
        pathFragment: String?,
        localContentMap: MutableMap<String?, String?>,
        remoteContentMap: MutableMap<String?, String?>,
        metadata: MutableMap<ActionInput?, FileArtifactValue?>,
        cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>
    ): Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> {
        return createRemoteTreeArtifact(
            pathFragment,
            localContentMap,
            remoteContentMap,  /* resolvedPath= */
            null,
            metadata,
            cas,  /* isActionTemplateExpansion= */
            false
        )
    }

    @Throws(IOException::class)
    protected fun createRemoteTreeArtifact(
        pathFragment: String?,
        localContentMap: MutableMap<String?, String?>,
        remoteContentMap: MutableMap<String?, String?>,
        resolvedPath: PathFragment?,
        metadata: MutableMap<ActionInput?, FileArtifactValue?>,
        cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>
    ): Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> {
        return createRemoteTreeArtifact(
            pathFragment,
            localContentMap,
            remoteContentMap,
            resolvedPath,
            metadata,
            cas,  /* isActionTemplateExpansion= */
            false
        )
    }

    @Throws(IOException::class)
    protected fun createRemoteTreeArtifactForActionTemplateExpansion(
        pathFragment: String?,
        localContentMap: MutableMap<String?, String?>,
        remoteContentMap: MutableMap<String?, String?>,
        metadata: MutableMap<ActionInput?, FileArtifactValue?>,
        cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>
    ): Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> {
        return createRemoteTreeArtifact(
            pathFragment,
            localContentMap,
            remoteContentMap,  /* resolvedPath= */
            null,
            metadata,
            cas,  /* isActionTemplateExpansion= */
            true
        )
    }

    protected abstract fun createPrefetcher(cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>?): AbstractActionInputPrefetcher

    @org.junit.Test
    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    fun prefetchFiles_fileExists_doNotDownload() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val a: Artifact = createRemoteArtifact("file", "hello world", metadata, cas)
        FileSystemUtils.writeContent(a.getPath(), "hello world".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val prefetcher: AbstractActionInputPrefetcher = spy(createPrefetcher(cas))

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action, metadata.keys, { key: Any? -> metadata.get(key) }, Priority.MEDIUM, Reason.INPUTS
            )
        )

        Mockito.verify<Any?>(prefetcher, Mockito.never())
            .doDownloadFile(< T > eq < T ? > (action), ArgumentMatchers.any<T?>(), <T>eq<T?>(a), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        assertThat(prefetcher.downloadedFiles()).containsExactly(a.getPath())
        assertThat(prefetcher.downloadsInProgress()).isEmpty()
    }

    @org.junit.Test
    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    fun prefetchFiles_fileExistsButContentMismatches_download() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val a: Artifact = createRemoteArtifact("file", "hello world remote", metadata, cas)
        FileSystemUtils.writeContent(
            a.getPath(),
            "hello world local".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
        )
        val prefetcher: AbstractActionInputPrefetcher = spy(createPrefetcher(cas))

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action, metadata.keys, { key: Any? -> metadata.get(key) }, Priority.MEDIUM, Reason.INPUTS
            )
        )

        Mockito.verify<Any?>(prefetcher)
            .doDownloadFile(< T > eq < T ? > (action), ArgumentMatchers.any<T?>(), <T>eq<T?>(a), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        assertThat(prefetcher.downloadedFiles()).containsExactly(a.getPath())
        assertThat(prefetcher.downloadsInProgress()).isEmpty()
        assertThat(
            FileSystemUtils.readContent(
                a.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello world remote")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_downloadRemoteFiles() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val a1: Artifact = createRemoteArtifact("file1", "hello world", metadata, cas)
        val a2: Artifact = createRemoteArtifact("file2", "fizz buzz", metadata, cas)
        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action, metadata.keys, { key: Any? -> metadata.get(key) }, Priority.MEDIUM, Reason.INPUTS
            )
        )

        assertThat(
            FileSystemUtils.readContent(
                a1.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello world")
        assertReadableNonWritableAndExecutable(a1.getPath())
        assertThat(
            FileSystemUtils.readContent(
                a2.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("fizz buzz")
        assertReadableNonWritableAndExecutable(a2.getPath())
        assertThat(prefetcher.downloadedFiles()).containsExactly(a1.getPath(), a2.getPath())
        assertThat(prefetcher.downloadsInProgress()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_downloadRemoteFiles_withResolvedPath() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val resolvedPath: PathFragment? = artifactRoot.getRoot().asPath().getChild("target").asFragment()
        val a: Artifact = createRemoteArtifact("file", "hello world", resolvedPath, metadata, cas)
        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action, metadata.keys, { key: Any? -> metadata.get(key) }, Priority.MEDIUM, Reason.INPUTS
            )
        )

        assertThat(a.getPath().isSymbolicLink()).isTrue()
        assertThat(a.getPath().readSymbolicLink()).isEqualTo(resolvedPath)
        assertThat(
            FileSystemUtils.readContent(
                a.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello world")
        assertThat(prefetcher.downloadedFiles()).containsExactly(a.getPath(), fs.getPath(resolvedPath))
        assertThat(prefetcher.downloadsInProgress()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_downloadRemoteTrees() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val treeAndChildren: Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> =
            createRemoteTreeArtifact(
                "dir",  /* localContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* remoteContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "file1", "content1", "nested_dir/file2", "content2"
                ),
                metadata,
                cas
            )
        val tree: SpecialArtifact = treeAndChildren.first
        val children: com.google.common.collect.ImmutableList<TreeFileArtifact> = treeAndChildren.second
        val firstChild: Artifact = children.get(0)
        val secondChild: Artifact = children.get(1)

        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action, children, { key: Any? -> metadata.get(key) }, Priority.MEDIUM, Reason.INPUTS
            )
        )

        assertThat(
            FileSystemUtils.readContent(
                firstChild.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("content1")
        assertThat(
            FileSystemUtils.readContent(
                secondChild.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("content2")

        assertTreeReadableNonWritableAndExecutable(tree.getPath())

        assertThat(prefetcher.downloadedFiles())
            .containsExactly(firstChild.getPath(), secondChild.getPath())
        assertThat(prefetcher.downloadsInProgress()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_downloadRemoteTrees_partial() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val treeAndChildren: Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> =
            createRemoteTreeArtifact(
                "dir",  /* localContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "file1",
                    "content1"
                ),  /* remoteContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>("file2", "content2"),
                metadata,
                cas
            )
        val tree: SpecialArtifact = treeAndChildren.first
        val children: com.google.common.collect.ImmutableList<TreeFileArtifact> = treeAndChildren.second
        val firstChild: Artifact = children.get(0)
        val secondChild: Artifact = children.get(1)

        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action,
                com.google.common.collect.ImmutableList.of<E?>(firstChild, secondChild),
                { key: Any? -> metadata.get(key) },
                Priority.MEDIUM,
                Reason.INPUTS
            )
        )

        assertThat(firstChild.getPath().exists()).isFalse()
        assertThat(
            FileSystemUtils.readContent(
                secondChild.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("content2")
        assertTreeReadableNonWritableAndExecutable(tree.getPath())
        assertThat(prefetcher.downloadedFiles()).containsExactly(secondChild.getPath())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_downloadRemoteTrees_withResolvedPath() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val resolvedPath: PathFragment? = artifactRoot.getRoot().asPath().getChild("target").asFragment()
        val treeAndChildren: Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> =
            createRemoteTreeArtifact(
                "dir",  /* localContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* remoteContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "file1", "content1", "nested_dir/file2", "content2"
                ),
                resolvedPath,
                metadata,
                cas
            )
        val tree: SpecialArtifact = treeAndChildren.first
        val children: com.google.common.collect.ImmutableList<TreeFileArtifact> = treeAndChildren.second
        val firstChild: Artifact = children.get(0)
        val secondChild: Artifact = children.get(1)

        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action, children, { key: Any? -> metadata.get(key) }, Priority.MEDIUM, Reason.INPUTS
            )
        )

        assertThat(tree.getPath().isSymbolicLink()).isTrue()
        assertThat(tree.getPath().readSymbolicLink()).isEqualTo(resolvedPath)
        assertThat(
            FileSystemUtils.readContent(
                firstChild.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("content1")
        assertThat(
            FileSystemUtils.readContent(
                secondChild.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("content2")

        assertTreeReadableNonWritableAndExecutable(fs.getPath(resolvedPath))

        assertThat(prefetcher.downloadedFiles())
            .containsExactly(
                tree.getPath(),
                fs.getPath(resolvedPath).getRelative(firstChild.getParentRelativePath()),
                fs.getPath(resolvedPath).getRelative(secondChild.getParentRelativePath())
            )
        assertThat(prefetcher.downloadsInProgress()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_downloadRemoteTrees_forActionTemplateExpansion() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val treeAndChildren: Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> =
            createRemoteTreeArtifactForActionTemplateExpansion(
                "dir",  /* localContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* remoteContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "subdir/file1", "content1", "subdir/file2", "content2"
                ),
                metadata,
                cas
            )
        val tree: SpecialArtifact = treeAndChildren.first
        val children: com.google.common.collect.ImmutableList<TreeFileArtifact> = treeAndChildren.second
        val firstChild: Artifact = children.get(0)
        val secondChild: Artifact = children.get(1)

        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action,
                com.google.common.collect.ImmutableList.of<E?>(firstChild),
                { key: Any? -> metadata.get(key) },
                Priority.MEDIUM,
                Reason.INPUTS
            )
        )

        assertTreeReadableWritableAndExecutable(tree.getPath())

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action,
                com.google.common.collect.ImmutableList.of<E?>(secondChild),
                { key: Any? -> metadata.get(key) },
                Priority.MEDIUM,
                Reason.INPUTS
            )
        )

        assertTreeReadableWritableAndExecutable(tree.getPath())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_missingFiles_fails() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val a: Artifact = createRemoteArtifact(
            "file1",
            "hello world",
            metadata,  /* cas= */
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        )
        val prefetcher: AbstractActionInputPrefetcher =
            createPrefetcher(HashMap<com.google.common.hash.HashCode?, ByteArray?>())

        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable {
                wait(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        com.google.common.collect.ImmutableList.of<E?>(a),
                        { key: Any? -> metadata.get(key) },
                        Priority.MEDIUM,
                        Reason.INPUTS
                    )
                )
            })

        assertThat(prefetcher.downloadedFiles()).isEmpty()
        assertThat(prefetcher.downloadsInProgress()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_ignoreNonRemoteFiles() {
        // Test that non-remote files are not downloaded.

        val p: Path? = execRoot.getRelative(artifactRoot.getExecPath()).getRelative("file1")
        FileSystemUtils.writeContent(p, java.nio.charset.StandardCharsets.UTF_8, "hello world")
        val a: Artifact = ActionsTestUtil.createArtifact(artifactRoot, p)
        val f: FileArtifactValue = FileArtifactValue.createForTesting(a)
        val metadata: com.google.common.collect.ImmutableMap<ActionInput?, FileArtifactValue?> =
            com.google.common.collect.ImmutableMap.of<ActionInput?, FileArtifactValue?>(a, f)
        val prefetcher: AbstractActionInputPrefetcher =
            createPrefetcher(HashMap<com.google.common.hash.HashCode?, ByteArray?>())

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action, com.google.common.collect.ImmutableList.of<E?>(a), metadata::get, Priority.MEDIUM, Reason.INPUTS
            )
        )

        assertThat(prefetcher.downloadedFiles()).isEmpty()
        assertThat(prefetcher.downloadsInProgress()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_ignoreNonRemoteFiles_tree() {
        // Test that non-remote tree files are not downloaded, but other files in the tree are.

        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val treeAndChildren: Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> =
            createRemoteTreeArtifact(
                "dir",
                com.google.common.collect.ImmutableMap.of<String?, String?>("file1", "content1"),
                com.google.common.collect.ImmutableMap.of<String?, String?>("file2", "content2"),
                metadata,
                cas
            )
        val tree: SpecialArtifact = treeAndChildren.first
        val children: com.google.common.collect.ImmutableList<TreeFileArtifact> = treeAndChildren.second
        val firstChild: Artifact = children.get(0)
        val secondChild: Artifact = children.get(1)

        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action, children, { key: Any? -> metadata.get(key) }, Priority.MEDIUM, Reason.INPUTS
            )
        )

        assertThat(firstChild.getPath().exists()).isFalse()
        assertThat(
            FileSystemUtils.readContent(
                secondChild.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("content2")
        assertTreeReadableNonWritableAndExecutable(tree.getPath())
        assertThat(prefetcher.downloadedFiles()).containsExactly(secondChild.getPath())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_treeFiles_minimizeFilesystemOperations() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val treeAndChildren: Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> =
            createRemoteTreeArtifact(
                "dir",  /* localContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* remoteContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "subdir/file1", "content1", "subdir/file2", "content2"
                ),
                metadata,
                cas
            )
        val tree: SpecialArtifact = treeAndChildren.first
        val root: PathFragment? = tree.getPath().asFragment()
        val subdir: PathFragment? = tree.getPath().getChild("subdir").asFragment()
        val children: com.google.common.collect.ImmutableList<TreeFileArtifact> = treeAndChildren.second
        val firstChild: Artifact = children.get(0)
        val secondChild: Artifact = children.get(1)

        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        Mockito.reset<SpiedFileSystem?>(fs)

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action,
                com.google.common.collect.ImmutableList.of<E?>(firstChild, secondChild),
                { key: Any? -> metadata.get(key) },
                Priority.MEDIUM,
                Reason.INPUTS
            )
        )

        Mockito.verify<SpiedFileSystem?>(fs).createDirectory(root)
        Mockito.verify<SpiedFileSystem?>(fs).createDirectory(subdir)
        Mockito.verify<SpiedFileSystem?>(fs).chmod(root, 365)
        Mockito.verify<SpiedFileSystem?>(fs).chmod(subdir, 365)

        Mockito.reset<SpiedFileSystem?>(fs)

        wait(
            prefetcher.prefetchFilesInterruptibly(
                action,
                com.google.common.collect.ImmutableList.of<E?>(firstChild, secondChild),
                { key: Any? -> metadata.get(key) },
                Priority.MEDIUM,
                Reason.INPUTS
            )
        )

        Mockito.verify<SpiedFileSystem?>(fs, Mockito.never()).createDirectory(root)
        Mockito.verify<SpiedFileSystem?>(fs, Mockito.never()).createDirectory(subdir)
        Mockito.verify<SpiedFileSystem?>(fs, Mockito.never()).chmod(root, 365)
        Mockito.verify<SpiedFileSystem?>(fs, Mockito.never()).chmod(subdir, 365)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_treeFiles_multipleThreads_waitForPermissionsToBeSet() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val treeAndChildren: Pair<SpecialArtifact?, com.google.common.collect.ImmutableList<TreeFileArtifact?>?> =
            createRemoteTreeArtifact(
                "dir",  /* localContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>(),  /* remoteContentMap= */
                com.google.common.collect.ImmutableMap.of<String?, String?>("subdir/file", "content"),
                metadata,
                cas
            )
        val tree: SpecialArtifact = treeAndChildren.first
        val child: Artifact? = com.google.common.collect.Iterables.getOnlyElement<Artifact?>(treeAndChildren.second)

        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        // Prefetch the same tree artifact in two concurrent calls.
        // Verify that the second waits until the download operation completes *and* sets the output
        // permissions on the entire tree artifact before returning.
        // Delay the chmod() calls to make it much more likely that we'd catch a bug where the second
        // call returns after the download completes but before the permissions have been set.
        // Regression test for b/299934607.
        (fs.getDelegateFs() as DelayedChmodFileSystem).setChmodDelay(java.time.Duration.ofMillis(100))

        val pool: ThreadPoolExecutor =
            ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, LinkedBlockingQueue<java.lang.Runnable?>())

        val prefetch: java.util.concurrent.Callable<java.lang.Void?> =
            java.util.concurrent.Callable {
                wait(
                    prefetcher.prefetchFilesInterruptibly(
                        action,
                        com.google.common.collect.ImmutableList.of<E?>(child),
                        { key: Any? -> metadata.get(key) },
                        Priority.MEDIUM,
                        Reason.INPUTS
                    )
                )
                assertTreeReadableNonWritableAndExecutable(tree.getPath())
                null
            }

        val f1: java.util.concurrent.Future<java.lang.Void?> = pool.submit<java.lang.Void?>(prefetch)
        val f2: java.util.concurrent.Future<java.lang.Void?> = pool.submit<java.lang.Void?>(prefetch)

        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        f1.get()
        f2.get()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_multipleThreads_downloadIsCancelled() {
        // Test shared downloads are cancelled if all threads/callers are interrupted

        // arrange

        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val artifact: Artifact = createRemoteArtifact("file1", "hello world", metadata, cas)

        val prefetcher: AbstractActionInputPrefetcher = spy(createPrefetcher(cas))
        val downloadThatNeverFinishes: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        mockDownload(prefetcher, cas, java.util.function.Supplier { downloadThatNeverFinishes })

        val cancelledThread1: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        wait(
                            prefetcher.prefetchFilesInterruptibly(
                                action,
                                com.google.common.collect.ImmutableList.of<E?>(artifact),
                                { key: Any? -> metadata.get(key) },
                                Priority.MEDIUM,
                                Reason.INPUTS
                            )
                        )
                    } catch (ignored: IOException) {
                        // do nothing
                    } catch (ignored: ExecException) {
                    } catch (ignored: java.lang.InterruptedException) {
                    }
                })

        val cancelledThread2: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        wait(
                            prefetcher.prefetchFilesInterruptibly(
                                action,
                                com.google.common.collect.ImmutableList.of<E?>(artifact),
                                { key: Any? -> metadata.get(key) },
                                Priority.MEDIUM,
                                Reason.INPUTS
                            )
                        )
                    } catch (ignored: IOException) {
                        // do nothing
                    } catch (ignored: ExecException) {
                    } catch (ignored: java.lang.InterruptedException) {
                    }
                })

        // act
        cancelledThread1.start()
        cancelledThread2.start()
        cancelledThread1.interrupt()
        cancelledThread2.interrupt()
        cancelledThread1.join()
        cancelledThread2.join()

        // assert
        Truth.assertThat(downloadThatNeverFinishes.isCancelled()).isTrue()
        assertThat(artifact.getPath().exists()).isFalse()
        assertThat(tempPathGenerator.getTempDir().getDirectoryEntries()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_multipleThreads_downloadIsNotCancelledByOtherThreads() {
        // Test multiple threads can share downloads, but do not cancel each other when interrupted

        // arrange

        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val artifact: Artifact = createRemoteArtifact("file1", "hello world", metadata, cas)
        val download: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        val prefetcher: AbstractActionInputPrefetcher = spy(createPrefetcher(cas))
        mockDownload(prefetcher, cas, java.util.function.Supplier { download })
        val cancelledThread: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        wait(
                            prefetcher.prefetchFilesInterruptibly(
                                action,
                                com.google.common.collect.ImmutableList.of<E?>(artifact),
                                { key: Any? -> metadata.get(key) },
                                Priority.MEDIUM,
                                Reason.INPUTS
                            )
                        )
                    } catch (ignored: IOException) {
                        // do nothing
                    } catch (ignored: ExecException) {
                    } catch (ignored: java.lang.InterruptedException) {
                    }
                })

        val successful: AtomicBoolean = AtomicBoolean(false)
        val successfulThread: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        wait(
                            prefetcher.prefetchFilesInterruptibly(
                                action,
                                com.google.common.collect.ImmutableList.of<E?>(artifact),
                                { key: Any? -> metadata.get(key) },
                                Priority.MEDIUM,
                                Reason.INPUTS
                            )
                        )
                        successful.set(true)
                    } catch (ignored: IOException) {
                        // do nothing
                    } catch (ignored: ExecException) {
                    } catch (ignored: java.lang.InterruptedException) {
                    }
                })
        cancelledThread.start()
        successfulThread.start()
        while (true) {
            if (prefetcher
                    .getDownloadCache()
                    .getSubscriberCount(execRoot.getRelative(artifact.getExecPath()))
                === 2
            ) {
                break
            }
        }

        // act
        cancelledThread.interrupt()
        cancelledThread.join()
        // simulate the download finishing
        Truth.assertThat(download.isCancelled()).isFalse()
        download.set(null)
        successfulThread.join()

        // assert
        Truth.assertThat(successful.get()).isTrue()
        assertThat(
            FileSystemUtils.readContent(
                artifact.getPath(),
                java.nio.charset.StandardCharsets.UTF_8
            )
        ).isEqualTo("hello world")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFile_interruptingMetadataSupplier_interruptsDownload() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val a1: Artifact = createRemoteArtifact("file1", "hello world", metadata, cas)
        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)

        val interruptedMetadataSupplier: MetadataSupplier =
            MetadataSupplier { unused ->
                throw java.lang.InterruptedException()
            }

        val future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? =
            prefetcher.prefetchFilesInterruptibly(
                action,
                com.google.common.collect.ImmutableList.of<E?>(a1),
                interruptedMetadataSupplier,
                Priority.MEDIUM,
                Reason.INPUTS
            )

        org.junit.Assert.assertThrows<java.lang.InterruptedException?>(
            java.lang.InterruptedException::class.java,
            org.junit.function.ThrowingRunnable { getFromFuture(future) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prefetchFiles_onInterrupt_deletePartialDownloadedFile() {
        val startSemaphore: Semaphore = Semaphore(0)
        val endSemaphore: Semaphore = Semaphore(0)
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val a1: Artifact = createRemoteArtifact("file1", "hello world", metadata, cas)
        val prefetcher: AbstractActionInputPrefetcher = spy(createPrefetcher(cas))
        mockDownload(
            prefetcher,
            cas,
            java.util.function.Supplier {
                startSemaphore.release()
                com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>() // A future that never complete so we can interrupt later
            })

        val interrupted: AtomicBoolean = AtomicBoolean(false)
        val t: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        getFromFuture(
                            prefetcher.prefetchFilesInterruptibly(
                                action,
                                com.google.common.collect.ImmutableList.of<E?>(a1),
                                { key: Any? -> metadata.get(key) },
                                Priority.MEDIUM,
                                Reason.INPUTS
                            )
                        )
                    } catch (ignored: IOException) {
                        // Intentionally left empty
                    } catch (e: java.lang.InterruptedException) {
                        interrupted.set(true)
                    }
                    endSemaphore.release()
                })
        t.start()
        startSemaphore.acquire()
        t.interrupt()
        endSemaphore.acquire()

        Truth.assertThat(interrupted.get()).isTrue()
        assertThat(a1.getPath().exists()).isFalse()
        assertThat(tempPathGenerator.getTempDir().getDirectoryEntries()).isEmpty()
    }

    @org.junit.Test
    fun missingInputs_exceptionHasLostInputs() {
        val metadata: MutableMap<ActionInput?, FileArtifactValue?> = HashMap<ActionInput?, FileArtifactValue?>()
        val cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?> =
            HashMap<com.google.common.hash.HashCode?, ByteArray?>()
        val input: Artifact = createRemoteArtifact("file", "hello world", metadata,  /* cas= */null)
        val prefetcher: AbstractActionInputPrefetcher = createPrefetcher(cas)
        val metadataProvider: StaticInputMetadataProvider = StaticInputMetadataProvider(metadata)

        val e: T =
            org.junit.Assert.assertThrows<T>(
                BulkTransferException::class.java,
                org.junit.function.ThrowingRunnable {
                    wait(
                        prefetcher.prefetchFilesInterruptibly(
                            action, metadata.keys, { key: Any? -> metadata.get(key) }, Priority.MEDIUM, Reason.INPUTS
                        )
                    )
                })
        assertThat(e.getLostArtifacts(metadataProvider::getInput).byDigest())
            .containsExactly(
                DigestUtil.toString(
                    DigestUtil(SyscallCache.NO_CACHE, HASH_FUNCTION).computeAsUtf8("hello world")
                ),
                input
            )
    }

    @Throws(IOException::class)
    private fun assertReadableNonWritableAndExecutable(path: Path) {
        Truth.assertWithMessage("%s should be readable", path).that(path.isReadable()).isTrue()
        Truth.assertWithMessage("%s should not be writable", path).that(path.isWritable()).isFalse()
        Truth.assertWithMessage("%s should be executable", path).that(path.isExecutable()).isTrue()
    }

    @Throws(IOException::class)
    private fun assertTreeReadableNonWritableAndExecutable(path: Path) {
        com.google.common.base.Preconditions.checkState(path.isDirectory())
        assertReadableNonWritableAndExecutable(path)
        for (dirent in path.readdir(Symlinks.NOFOLLOW)) {
            if (dirent.type.equals(Dirent.Type.DIRECTORY)) {
                assertTreeReadableNonWritableAndExecutable(path.getChild(dirent.name))
            }
        }
    }

    @Throws(IOException::class)
    private fun assertReadableWritableAndExecutable(path: Path) {
        Truth.assertWithMessage("%s should be readable", path).that(path.isReadable()).isTrue()
        Truth.assertWithMessage("%s should be writable", path).that(path.isWritable()).isTrue()
        Truth.assertWithMessage("%s should be executable", path).that(path.isExecutable()).isTrue()
    }

    @Throws(IOException::class)
    private fun assertTreeReadableWritableAndExecutable(path: Path) {
        com.google.common.base.Preconditions.checkState(path.isDirectory())
        assertReadableWritableAndExecutable(path)
        for (dirent in path.readdir(Symlinks.NOFOLLOW)) {
            if (dirent.type.equals(Dirent.Type.DIRECTORY)) {
                assertTreeReadableWritableAndExecutable(path.getChild(dirent.name))
            }
        }
    }

    companion object {
        protected val HASH_FUNCTION: DigestHashFunction = DigestHashFunction.SHA256

        @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
        protected fun wait(future: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>) {
            try {
                future.get()
            } catch (e: ExecutionException) {
                val cause: Throwable? = e.cause
                if (cause != null) {
                    com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(cause, IOException::class.java)
                    com.google.common.base.Throwables.throwIfInstanceOf<X?>(cause, ExecException::class.java)
                    com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                        cause,
                        java.lang.InterruptedException::class.java
                    )
                    com.google.common.base.Throwables.throwIfInstanceOf<java.lang.RuntimeException?>(
                        cause,
                        java.lang.RuntimeException::class.java
                    )
                }
                throw IOException(e)
            } catch (e: java.lang.InterruptedException) {
                future.cancel( /* mayInterruptIfRunning= */true)
                throw e
            }
        }

        @Throws(IOException::class)
        protected fun mockDownload(
            prefetcher: AbstractActionInputPrefetcher?,
            cas: MutableMap<com.google.common.hash.HashCode?, ByteArray?>,
            resultSupplier: java.util.function.Supplier<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>
        ) {
            Mockito.doAnswer(
                Answer { invocation: InvocationOnMock? ->
                    val path: Path? = invocation.getArgument<Path?>(3)
                    val metadata: FileArtifactValue = invocation.getArgument<FileArtifactValue>(4)
                    val content = cas.get(com.google.common.hash.HashCode.fromBytes(metadata.getDigest()))
                    if (content == null) {
                        return@doAnswer com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(
                            IOException("Not found")
                        )
                    }
                    FileSystemUtils.writeContent(path, content)
                    resultSupplier.get()
                })
                .`when`<Any?>(prefetcher)
                .doDownloadFile(
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>(),
                    ArgumentMatchers.any<T?>()
                )
        }
    }
}
