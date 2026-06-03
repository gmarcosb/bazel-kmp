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
package com.google.devtools.build.lib.remote

import build.bazel.remote.execution.v2.Digest

@RunWith(JUnit4::class)
class DiskCacheIntegrationTest : BuildIntegrationTestCase() {
    // Collect digests of AC entries uploaded to the disk cache during the build.
    // Filter out actions other than genrules (e.g. WorkspaceStatusAction).
    private val actionDigests: HashSet<Digest?> = HashSet<Digest?>()
    private val actionDigestCollector: ExtendedEventHandler = object : ExtendedEventHandler {
        override fun post(obj: Postable?) {
            if (obj is ActionUploadFinishedEvent) {
                if (!obj.action().getMnemonic().equals("Genrule") || obj.store() !== Store.AC) {
                    return
                }
                actionDigests.add(obj.digest())
            }
        }

        override fun handle(event: com.google.devtools.build.lib.events.Event?) {}
    }

    private var digestUtil: DigestUtil? = null

    private fun enableRemoteExec(vararg additionalOptions: String?) {
        addOptions("--remote_executor=grpc://localhost:" + worker.getPort())
        addOptions(*additionalOptions)
    }

    private fun enableRemoteCache(vararg additionalOptions: String?) {
        addOptions("--remote_cache=grpc://localhost:" + worker.getPort())
        addOptions(*additionalOptions)
    }

    val startupOptionClasses: com.google.common.collect.ImmutableList<java.lang.Class<out OptionsBase?>?>?
        get() = com.google.common.collect.ImmutableList.builder<java.lang.Class<out OptionsBase?>?>()
            .addAll(super.startupOptionClasses)
            .add(RemoteStartupOptions::class.java)
            .build()

    @Throws(java.lang.Exception::class)
    override fun setupOptions() {
        super.setupOptions()

        addOptions("--disk_cache=" + diskCacheDir)
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        digestUtil = DigestUtil(SyscallCache.NO_CACHE, getFileSystem().getDigestFunction())
        events.addHandler(actionDigestCollector)
    }

    @org.junit.After
    @Throws(IOException::class)
    fun tearDown() {
        getWorkspace().getFileSystem().getPath(diskCacheDir).deleteTree()
    }

    val spawnModules: com.google.common.collect.ImmutableList<BlazeModule?>?
        get() = com.google.common.collect.ImmutableList.builder<BlazeModule?>()
            .addAll(super.spawnModules)
            .add(StandaloneModule())
            .build()

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder
            .addBlazeModule(CredentialModule())
            .addBlazeModule(RemoteModule())
            .addBlazeModule(BuildSummaryStatsModule())
            .addBlazeModule(BlockWaitingModule())

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hitDiskCache() {
        // Arrange: Prepare the workspace and populate disk cache.
        setupWorkspace()
        buildTarget("//:foobar")
        Truth.assertThat(actionDigests).hasSize(2)
        assertRecentlyModified(actionDigests, getBlobDigests("foo", "foobar", "out", "err"))

        // Act: Reset mtime on cache entries and do a clean build.
        resetRecentlyModified()
        cleanAndRestartServer()
        buildTarget("//:foobar")

        // Assert: Should download action results from cache and refresh mtime on cache entries.
        events.assertContainsInfo("2 disk cache hit")
        assertRecentlyModified(actionDigests, getBlobDigests("foo", "foobar", "out", "err"))
    }

    @Throws(java.lang.Exception::class)
    private fun doBlobsReferencedInAcAreMissingFromCasIgnoresAc(vararg additionalOptions: String?) {
        // Arrange: Prepare the workspace and populate disk cache.
        setupWorkspace()
        addOptions(*additionalOptions)
        buildTarget("//:foobar")

        // Act: Delete blobs in CAS from disk cache and do a clean build.
        getWorkspace().getFileSystem().getPath(diskCacheDir.getRelative("cas")).deleteTree()
        cleanAndRestartServer()
        addOptions(*additionalOptions)
        buildTarget("//:foobar")

        // Assert: Should ignore the stale AC and rerun the generating action.
        events.assertDoesNotContainEvent("disk cache hit")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun blobsReferencedInAcAreMissingFromCas_ignoresAc() {
        doBlobsReferencedInAcAreMissingFromCasIgnoresAc()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun bwob_blobsReferencedInAcAreMissingFromCas_ignoresAc() {
        doBlobsReferencedInAcAreMissingFromCasIgnoresAc("--remote_download_minimal")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun bwobAndRemoteExec_blobsReferencedInAcAreMissingFromCas_ignoresAc() {
        enableRemoteExec("--remote_download_minimal")
        doBlobsReferencedInAcAreMissingFromCasIgnoresAc()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun bwobAndRemoteCache_blobsReferencedInAcAreMissingFromCas_ignoresAc() {
        enableRemoteCache("--remote_download_minimal")
        doBlobsReferencedInAcAreMissingFromCasIgnoresAc()
    }

    @Throws(java.lang.Exception::class)
    private fun doRemoteExecWithDiskCache(vararg additionalOptions: String?) {
        // Arrange: Prepare the workspace and populate disk cache.
        setupWorkspace()
        enableRemoteExec(*additionalOptions)
        buildTarget("//:foobar")

        // Act: Do a clean build.
        cleanAndRestartServer()
        enableRemoteExec("--remote_download_minimal")
        buildTarget("//:foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteExecWithDiskCache_hitDiskCache() {
        // Download all outputs to populate the disk cache.
        doRemoteExecWithDiskCache("--remote_download_all")

        // Assert: Should hit the disk cache.
        events.assertContainsInfo("2 disk cache hit")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun bwob_remoteExecWithDiskCache_hitRemoteCache() {
        doRemoteExecWithDiskCache("--remote_download_minimal")

        // Assert: Should hit the remote cache because blobs referenced by the AC are missing from disk
        // cache due to BwoB.
        events.assertContainsInfo("2 remote cache hit")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun remoteExecWithDiskCache_inputsNotUploadedToDiskCache() {
        // Arrange: Set up workspace with tree artifact, runfiles, and source file as inputs.
        write(
            "defs.bzl",
            """
        def _tree_impl(ctx):
            out = ctx.actions.declare_directory(ctx.attr.name + "_tree")
            ctx.actions.run_shell(
                mnemonic = "TreeGen",
                outputs = [out],
                command = "mkdir -p {0}/subdir && echo -n tree_content > {0}/subdir/file.txt".format(
                    out.path
                ),
            )
            return DefaultInfo(files = depset([out]))

        tree = rule(implementation = _tree_impl)

        def _runfiles_lib_impl(ctx):
            out = ctx.actions.declare_file(ctx.attr.name + ".txt")
            ctx.actions.write(out, "runfiles_content")
            return DefaultInfo(
                files = depset([out]),
                runfiles = ctx.runfiles(files = [out]),
            )

        runfiles_lib = rule(implementation = _runfiles_lib_impl)

        def _consumer_impl(ctx):
            out = ctx.actions.declare_file(ctx.attr.name + ".out")
            tree_input = ctx.attr.tree[DefaultInfo].files.to_list()[0]
            runfiles_input = ctx.attr.runfiles_lib[DefaultInfo].files.to_list()[0]
            source_input = ctx.file.src
            ctx.actions.run_shell(
                mnemonic = "Consumer",
                inputs = depset(
                    [tree_input, runfiles_input, source_input],
                    transitive = [ctx.attr.runfiles_lib[DefaultInfo].default_runfiles.files],
                ),
                outputs = [out],
                command = "cat {0}/subdir/file.txt {1} {2} > {3}".format(
                    tree_input.path, runfiles_input.path, source_input.path, out.path
                ),
            )
            return DefaultInfo(files = depset([out]))

        consumer = rule(
            implementation = _consumer_impl,
            attrs = {
                "tree": attr.label(mandatory = True),
                "runfiles_lib": attr.label(mandatory = True),
                "src": attr.label(mandatory = True, allow_single_file = True),
            },
        )
        
        """.trimIndent()
        )
        write("source_input.txt", "source_content")
        write(
            "BUILD",
            """
        load(":defs.bzl", "tree", "runfiles_lib", "consumer")
        tree(name = "my_tree")
        runfiles_lib(name = "my_runfiles")
        consumer(
            name = "my_consumer",
            tree = ":my_tree",
            runfiles_lib = ":my_runfiles",
            src = "source_input.txt",
        )
        
        """.trimIndent()
        )

        enableRemoteExec()
        buildTarget("//:my_consumer")

        // Assert: The tree artifact content, runfiles content, and source content should be in the
        // remote cache but NOT in the disk cache (inputs should only be uploaded to remote, not disk
        // cache).
        val treeContentDigest: Digest =
            digestUtil.compute("tree_content".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val runfilesContentDigest: Digest =
            digestUtil.compute("runfiles_content".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        val sourceContentDigest: Digest =
            digestUtil.compute("source_content\n".toByteArray(java.nio.charset.StandardCharsets.UTF_8))

        // Verify inputs are in the remote cache (so the remote action could execute).
        Truth.assertThat(remoteCacheEntryExists(treeContentDigest)).isTrue()
        Truth.assertThat(remoteCacheEntryExists(runfilesContentDigest)).isTrue()
        Truth.assertThat(remoteCacheEntryExists(sourceContentDigest)).isTrue()

        // Verify inputs are NOT in the disk cache.
        Truth.assertThat(diskCacheEntryExists(Store.CAS, treeContentDigest)).isFalse()
        Truth.assertThat(diskCacheEntryExists(Store.CAS, runfilesContentDigest)).isFalse()
        Truth.assertThat(diskCacheEntryExists(Store.CAS, sourceContentDigest)).isFalse()
    }

    @Throws(java.lang.Exception::class)
    private fun cleanAndRestartServer() {
        getOutputBase().getRelative("action_cache").deleteTreesBelow()
        // Simulates a server restart
        createRuntimeWrapper()
    }

    @Throws(IOException::class)
    private fun setupWorkspace() {
        write(
            "BUILD",
            "genrule(",
            "  name = 'foo',",
            "  srcs = ['foo.in'],",
            "  outs = ['foo.out'],",
            "  cmd = 'echo -n foo > $@',",
            ")",
            "genrule(",
            "  name = 'foobar',",
            "  srcs = [':foo.out', 'bar.in'],",
            "  outs = ['foobar.out'],",
            "  cmd = 'echo -n out && echo -n err 1>&2 && echo -n foobar > $@',",
            ")"
        )
        write("foo.in", "foo")
        write("bar.in", "bar")
    }

    private fun getBlobDigests(vararg blobs: String): com.google.common.collect.ImmutableSet<Digest?> {
        // Uploaded CAS entries include the Action and Command protos which we don't care about,
        // so we can't collect them in the same manner as AC entries.
        val digests: com.google.common.collect.ImmutableSet.Builder<Digest?> =
            com.google.common.collect.ImmutableSet.builder<Digest?>()
        for (blob in blobs) {
            digests.add(digestUtil.compute(blob.toByteArray(java.nio.charset.StandardCharsets.UTF_8)))
        }
        return digests.build()
    }

    @Throws(IOException::class)
    private fun resetRecentlyModified() {
        val dirs: ArrayDeque<Path> = ArrayDeque<Path>()
        dirs.add(getWorkspace().getFileSystem().getPath(diskCacheDir))
        while (!dirs.isEmpty()) {
            val dir: Path = dirs.remove()
            for (child in dir.getDirectoryEntries()) {
                child.setLastModifiedTime(0)
                if (child.isDirectory()) {
                    dirs.add(child)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun assertRecentlyModified(acDigests: MutableCollection<Digest?>, casDigests: MutableCollection<Digest?>) {
        for (digest in acDigests) {
            assertRecentlyModified(Store.AC, digest)
        }
        for (digest in casDigests) {
            assertRecentlyModified(Store.CAS, digest)
        }
    }

    @Throws(IOException::class)
    private fun assertRecentlyModified(store: Store, digest: Digest) {
        val path: Path = getDiskCacheEntryPath(store, digest)
        Truth.assertWithMessage("disk cache entry %s/%s does not exist", store, digest.getHash())
            .that(path.exists())
            .isTrue()
        Truth.assertWithMessage("disk cache entry %s/%s is too old", store, digest.getHash())
            .that(path.getLastModifiedTime())
            .isGreaterThan(Instant.now().minusSeconds(60).toEpochMilli())
    }

    @Throws(IOException::class)
    private fun getDiskCacheEntryPath(store: Store, digest: Digest): Path {
        return getWorkspace()
            .getFileSystem()
            .getPath(
                diskCacheDir
                    .getRelative(store.toString())
                    .getRelative(digest.getHash().substring(0, 2))
                    .getRelative(digest.getHash())
            )
    }

    @Throws(IOException::class)
    private fun diskCacheEntryExists(store: Store, digest: Digest): Boolean {
        return getDiskCacheEntryPath(store, digest).exists()
    }

    private fun remoteCacheEntryExists(digest: Digest): Boolean {
        return fileSystem
            .getPath(
                worker
                    .getCasPath()
                    .getRelative("cas")
                    .getRelative(digest.getHash().substring(0, 2))
                    .getRelative(digest.getHash())
            )
            .exists()
    }

    companion object {
        @ClassRule
        @org.junit.Rule
        val worker: WorkerInstance = createWorker()

        private val diskCacheDir: PathFragment
            get() {
                val testTmpDir: PathFragment =
                    PathFragment.create(com.google.devtools.build.lib.testutil.TestUtils.tmpDirFile().getAbsolutePath())
                return testTmpDir.getRelative("disk_cache")
            }
    }
}
