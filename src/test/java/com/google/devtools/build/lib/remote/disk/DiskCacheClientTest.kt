// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.disk

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.remote.util.Utils.getFromFuture
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.vfs.util.FileSystems
import com.google.errorprone.annotations.CanIgnoreReturnValue
import org.junit.After
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.Future
import kotlin.collections.ArrayList

/** Tests for [DiskCacheClient].  */
@RunWith(JUnit4::class)
class DiskCacheClientTest {
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val root: Path = fs.getPath("/disk_cache")
    private var client: DiskCacheClient? = null

    @Before
    @Throws(Exception::class)
    fun setUp() {
        client = DiskCacheClient(root, DIGEST_UTIL)
    }

    @After
    fun tearDown() {
        client!!.close()
    }

    @Test
    @Throws(Exception::class)
    fun findMissingDigests_returnsAllDigests() {
        val digests: ImmutableList<Digest?> =
            ImmutableList.of<Digest?>(getDigest("foo"), getDigest("bar"), getDigest("baz"))
        assertThat(getFromFuture(client!!.findMissingDigests(digests)))
            .containsExactlyElementsIn(digests)
    }

    @Test
    @Throws(Exception::class)
    fun toPath_forCas_forOldStyleHashFunction() {
        val digest: Digest? = Digest.newBuilder().setHash("0123456789abcdef").setSizeBytes(42).build()

        val path: Path = client.toPath(digest, Store.CAS)

        assertThat(path).isEqualTo(root.getRelative("cas/01/0123456789abcdef"))
    }

    @Test
    @Throws(Exception::class)
    fun toPath_forAc_forOldStyleHashFunction() {
        val digest: Digest? = Digest.newBuilder().setHash("0123456789abcdef").setSizeBytes(42).build()

        val path: Path = client.toPath(digest, Store.AC)

        assertThat(path).isEqualTo(root.getRelative("ac/01/0123456789abcdef"))
    }

    @Test
    @Throws(Exception::class)
    fun toPath_forCas_forNewStyleHashFunction() {
        Assume.assumeNotNull(BazelHashFunctions.BLAKE3) // BLAKE3 not available in Blaze.

        val client =
            DiskCacheClient(root, DigestUtil(SyscallCache.NO_CACHE, BazelHashFunctions.BLAKE3))
        val digest: Digest? = Digest.newBuilder().setHash("0123456789abcdef").setSizeBytes(42).build()
        val path: Path = client.toPath(digest, Store.CAS)

        assertThat(path).isEqualTo(root.getRelative("blake3/cas/01/0123456789abcdef"))
    }

    @Test
    @Throws(Exception::class)
    fun toPath_forAc_forNewStyleHashFunction() {
        Assume.assumeNotNull(BazelHashFunctions.BLAKE3) // BLAKE3 not available in Blaze.

        val client =
            DiskCacheClient(root, DigestUtil(SyscallCache.NO_CACHE, BazelHashFunctions.BLAKE3))
        val digest: Digest? = Digest.newBuilder().setHash("0123456789abcdef").setSizeBytes(42).build()
        val path: Path = client.toPath(digest, Store.AC)

        assertThat(path).isEqualTo(root.getRelative("blake3/ac/01/0123456789abcdef"))
    }

    @Test
    @Throws(Exception::class)
    fun uploadFile_whenMissing_populatesCas() {
        assertThat(root.exists()).isTrue()
        val file: Path = fs.getPath("/file")
        FileSystemUtils.writeContent(file, StandardCharsets.UTF_8, "contents")
        val digest: Digest = getDigest("contents")

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.uploadFile(digest, file))

        assertThat(FileSystemUtils.readContent(getCasPath(digest), StandardCharsets.UTF_8)).isEqualTo("contents")
    }

    @Test
    @Throws(Exception::class)
    fun uploadFile_whenPresent_updatesMtime() {
        val file: Path = fs.getPath("/file")
        FileSystemUtils.writeContent(file, StandardCharsets.UTF_8, "contents")
        val digest: Digest = getDigest("contents")

        // The contents would match under normal operation. This serves to check that we don't
        // unnecessarily overwrite the file.
        val path: Path = populateCas(digest, "existing contents")

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.uploadFile(digest, file))

        assertThat(FileSystemUtils.readContent(path, StandardCharsets.UTF_8)).isEqualTo("existing contents")
        assertThat(path.getLastModifiedTime()).isNotEqualTo(0)
    }

    @Test
    @Throws(Exception::class)
    fun uploadBlob_whenMissing_populatesCas() {
        val blob: ByteString = ByteString.copyFromUtf8("contents")
        val digest: Digest = getDigest("contents")

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client.uploadBlob(digest, blob))

        assertThat(FileSystemUtils.readContent(getCasPath(digest), StandardCharsets.UTF_8)).isEqualTo("contents")
    }

    @Test
    @Throws(Exception::class)
    fun uploadBlob_whenPresent_updatesMtime() {
        val blob: ByteString = ByteString.copyFromUtf8("contents")
        val digest: Digest = getDigest("contents")

        // The contents would match under normal operation. This serves to check that we don't
        // unnecessarily overwrite the file.
        val path: Path = populateCas(digest, "existing contents")

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client.uploadBlob(digest, blob))

        assertThat(FileSystemUtils.readContent(path, StandardCharsets.UTF_8)).isEqualTo("existing contents")
        assertThat(path.getLastModifiedTime()).isNotEqualTo(0)
    }

    @Test
    @Throws(Exception::class)
    fun uploadActionResult_whenMissing_populatesAc() {
        val actionKey: ActionKey = ActionKey(getDigest("key"))
        val actionResult: ActionResult = ActionResult.newBuilder().setExitCode(42).build()
        val path: Path = getAcPath(actionKey)

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.uploadActionResult(actionKey, actionResult))

        assertThat(FileSystemUtils.readContent(path)).isEqualTo(actionResult.toByteArray())
    }

    @Test
    @Throws(Exception::class)
    fun uploadActionResult_whenPresent_updatesContent() {
        val actionKey: ActionKey = ActionKey(getDigest("key"))
        val actionResult1: ActionResult = ActionResult.newBuilder().setExitCode(42).build()

        val path: Path = populateAc(actionKey, actionResult1)

        val actionResult2: ActionResult = ActionResult.newBuilder().setExitCode(43).build()
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.uploadActionResult(actionKey, actionResult2))

        assertThat(FileSystemUtils.readContent(path)).isEqualTo(actionResult2.toByteArray())
        assertThat(path.getLastModifiedTime()).isNotEqualTo(0)
    }

    @Test
    @Throws(Exception::class)
    fun downloadBlob_whenPresent_returnsContents() {
        val digest: Digest = getDigest("contents")
        populateCas(digest, "contents")
        val out: Path = fs.getPath("/out")

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.downloadBlob(digest, out.getOutputStream()))

        assertThat(FileSystemUtils.readContent(out, StandardCharsets.UTF_8)).isEqualTo("contents")
    }

    @Test
    @Throws(Exception::class)
    fun downloadBlob_whenMissing_throwsCacheNotFoundException() {
        val out: Path = fs.getPath("/out")

        Assert.assertThrows<T?>(
            CacheNotFoundException::class.java,
            ThrowingRunnable { getFromFuture(client!!.downloadBlob(getDigest("contents"), out.getOutputStream())) })
    }

    @Test
    @Throws(Exception::class)
    fun downloadActionResult_whenPresent_returnsCachedActionResult() {
        val actionKey: ActionKey = ActionKey(getDigest("key"))
        val actionResult: ActionResult = ActionResult.newBuilder().setExitCode(42).build()

        val path: Path = populateAc(actionKey, actionResult)

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.downloadActionResult(actionKey))

        assertThat(result).isEqualTo(actionResult)
        assertThat(path.getLastModifiedTime()).isNotEqualTo(0)
    }

    @Test
    @Throws(Exception::class)
    fun downloadActionResult_whenMissing_returnsNull() {
        val actionKey: ActionKey = ActionKey(getDigest("key"))

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.downloadActionResult(actionKey))

        assertThat(result).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun downloadActionResult_withReferencedBlobsPresent_updatesMtimeOnBlobs() {
        val stdoutDigest: Digest = getDigest("stdout contents")
        val stderrDigest: Digest = getDigest("stderr contents")
        val fileDigest: Digest = getDigest("file contents")
        val treeFileDigest: Digest = getDigest("tree file contents")
        val tree: Tree = getTreeWithFile(treeFileDigest)
        val treeDigest: Digest? = getDigest(tree)
        val actionKey: ActionKey = ActionKey(getDigest("key"))
        val actionResult: ActionResult =
            ActionResult.newBuilder()
                .setStdoutDigest(stdoutDigest)
                .setStderrDigest(stderrDigest)
                .addOutputFiles(OutputFile.newBuilder().setDigest(fileDigest).build())
                .addOutputDirectories(OutputDirectory.newBuilder().setTreeDigest(getDigest(tree)))
                .build()

        val acPath: Path = populateAc(actionKey, actionResult)
        val stdoutCasPath: Path = populateCas(stdoutDigest, "stdout contents")
        val stderrCasPath: Path = populateCas(stderrDigest, "stderr contents")
        val fileCasPath: Path = populateCas(fileDigest, "file contents")
        val treeCasPath: Path = populateCas(treeDigest, tree)
        val treeFileCasPath: Path = populateCas(treeFileDigest, "tree file contents")

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.downloadActionResult(actionKey))

        assertThat(result).isEqualTo(actionResult)
        assertThat(acPath.getLastModifiedTime()).isNotEqualTo(0)
        assertThat(stdoutCasPath.getLastModifiedTime()).isNotEqualTo(0)
        assertThat(stderrCasPath.getLastModifiedTime()).isNotEqualTo(0)
        assertThat(fileCasPath.getLastModifiedTime()).isNotEqualTo(0)
        assertThat(treeCasPath.getLastModifiedTime()).isNotEqualTo(0)
        assertThat(treeFileCasPath.getLastModifiedTime()).isNotEqualTo(0)
    }

    @Test
    @Throws(Exception::class)
    fun downloadActionResult_withReferencedFileMissing_returnsNull() {
        val fileDigest: Digest = getDigest("contents")
        val actionKey: ActionKey = ActionKey(getDigest("key"))
        val actionResult: ActionResult =
            ActionResult.newBuilder()
                .addOutputFiles(OutputFile.newBuilder().setDigest(fileDigest).build())
                .build()

        populateAc(actionKey, actionResult)

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.downloadActionResult(actionKey))

        assertThat(result).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun downloadActionResult_withReferencedTreeMissing_returnsNull() {
        val fileDigest: Digest = getDigest("contents")
        val tree: Tree = getTreeWithFile(fileDigest)
        val treeDigest: Digest? = getDigest(tree)
        val actionKey: ActionKey = ActionKey(getDigest("key"))
        val actionResult: ActionResult =
            ActionResult.newBuilder()
                .addOutputDirectories(OutputDirectory.newBuilder().setTreeDigest(treeDigest))
                .build()

        populateAc(actionKey, actionResult)
        populateCas(fileDigest, "contents")

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.downloadActionResult(actionKey))

        assertThat(result).isNull()
    }

    @Test
    @Throws(Exception::class)
    fun downloadActionResult_withReferencedTreeFileMissing_returnsNull() {
        val fileDigest: Digest = getDigest("contents")
        val tree: Tree = getTreeWithFile(fileDigest)
        val treeDigest: Digest? = getDigest(tree)
        val actionKey: ActionKey = ActionKey(getDigest("key"))
        val actionResult: ActionResult =
            ActionResult.newBuilder()
                .addOutputDirectories(OutputDirectory.newBuilder().setTreeDigest(treeDigest))
                .build()

        populateAc(actionKey, actionResult)
        populateCas(treeDigest, tree)

        val result: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getFromFuture(client!!.downloadActionResult(actionKey))

        assertThat(result).isNull()
    }

    @Test
    @Throws(IOException::class, ExecutionException::class, InterruptedException::class)
    fun concurrentUploadDownload() {
        val nativeDiskCacheDir: Path = TestUtils.createUniqueTmpDir(FileSystems.getNativeFileSystem())
        val nativeClient = DiskCacheClient(nativeDiskCacheDir, DIGEST_UTIL)
        val tasks = ArrayList<Future<*>>()
        // Use 1 MB blobs to increase the window for concurrent access during write/rename.
        val contentSize = 1024 * 1024
        val numConcurrentOps = 10
        Executors.newFixedThreadPool(numConcurrentOps).use { executor ->
            for (attempt in 0..99) {
                val contentArray = ByteArray(contentSize)
                // Fill with a pattern based on the attempt number.
                for (i in 0..<contentSize) {
                    contentArray[i] = (attempt + i).toByte()
                }
                val contentBytes: ByteString = ByteString.copyFrom(contentArray)
                val contentDigest: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    DIGEST_UTIL.compute(contentArray)
                // Use a latch to ensure all concurrent tasks start at roughly the same time.
                val startLatch: CountDownLatch = CountDownLatch(numConcurrentOps)
                // Half the tasks do uploads, half do downloads with a slow OutputStream to keep the file
                // open longer. This maximizes the chance of a rename failing because a download has the
                // file open.
                for (concurrentOp in 0..<numConcurrentOps) {
                    val isUploader = concurrentOp % 2 == 0
                    tasks.add(
                        executor.submit<Any?>(
                            Callable {
                                // Signal ready and wait for all tasks to be ready.
                                startLatch.countDown()
                                startLatch.await()
                                if (isUploader) {
                                    getFromFuture(nativeClient.uploadBlob(contentDigest, contentBytes))
                                } else {
                                    // Use a slow OutputStream that pauses periodically to keep the file open
                                    // longer during download.
                                    val out: OutputStream? =
                                        object : OutputStream() {
                                            private var bytesWritten = 0

                                            @Throws(IOException::class)
                                            override fun write(b: Int) {
                                                bytesWritten++
                                                maybeSleep()
                                            }

                                            @Throws(IOException::class)
                                            override fun write(b: ByteArray?, off: Int, len: Int) {
                                                bytesWritten += len
                                                maybeSleep()
                                            }

                                            fun maybeSleep() {
                                                // Sleep every 64KB to slow down the download.
                                                if (bytesWritten % (64 * 1024) < 100) {
                                                    try {
                                                        Thread.sleep(1)
                                                    } catch (e: InterruptedException) {
                                                        Thread.currentThread().interrupt()
                                                    }
                                                }
                                            }
                                        }
                                    try {
                                        getFromFuture(nativeClient.downloadBlob(contentDigest, out!!))
                                    } catch (ignored: CacheNotFoundException) {
                                        // File not yet uploaded by another task.
                                    }
                                }
                                null
                            })
                    )
                }
            }
            for (task in tasks) {
                task.get()
            }
        }
    }

    private fun getTreeWithFile(fileDigest: Digest?): Tree {
        return Tree.newBuilder()
            .addChildren(Directory.newBuilder().addFiles(FileNode.newBuilder().setDigest(fileDigest)))
            .build()
    }

    private fun getCasPath(digest: Digest?): Path {
        return client.toPath(digest, Store.CAS)
    }

    @CanIgnoreReturnValue
    @Throws(IOException::class)
    private fun populateCas(digest: Digest?, contents: String): Path {
        return populateCas(digest, contents.toByteArray(StandardCharsets.UTF_8))
    }

    @CanIgnoreReturnValue
    @Throws(IOException::class)
    private fun populateCas(digest: Digest?, m: Message): Path? {
        return populateCas(digest, m.toByteArray())
    }

    @Throws(IOException::class)
    private fun populateCas(digest: Digest?, contents: ByteArray?): Path {
        val path: Path = getCasPath(digest)
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContent(path, contents)
        path.setLastModifiedTime(0)
        return path
    }

    private fun getAcPath(actionKey: ActionKey): Path {
        return client.toPath(actionKey.digest(), Store.AC)
    }

    @CanIgnoreReturnValue
    @Throws(IOException::class)
    private fun populateAc(actionKey: ActionKey, actionResult: ActionResult): Path {
        val path: Path = getAcPath(actionKey)
        path.getParentDirectory().createDirectoryAndParents()
        FileSystemUtils.writeContent(path, actionResult.toByteArray())
        path.setLastModifiedTime(0)
        return path
    }

    private fun getDigest(contents: String?): Digest {
        return DIGEST_UTIL.computeAsUtf8(contents)
    }

    private fun getDigest(m: Message): Digest {
        return DIGEST_UTIL.compute(m.toByteArray())
    }

    companion object {
        private val DIGEST_UTIL: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, DigestHashFunction.SHA256)
    }
}
