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

import com.google.common.util.concurrent.MoreExecutors
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.vfs.Path
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.time.Duration
import java.util.*

/** Tests for [DiskCacheGarbageCollector].  */
@RunWith(JUnit4::class)
class DiskCacheGarbageCollectorTest {
    private val executorService: ExecutorService = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1))

    private var rootDir: Path? = null

    internal class Entry(val path: String?, val size: Long, mtime: Instant?) {
        val mtime: Instant?

        init {
            this.mtime = mtime
        }

        companion object {
            fun of(path: String?, size: Long, mtime: Instant?): Entry {
                return Entry(path, size, mtime)
            }
        }
    }

    @Before
    @Throws(Exception::class)
    fun setUp() {
        rootDir = TestUtils.createUniqueTmpDir(null)
    }

    @Test
    @Throws(Exception::class)
    fun sizePolicy_noCollection() {
        writeFiles(
            Entry.Companion.of("ac/123", kbytes(1), Instant.now()),
            Entry.Companion.of("cas/456", kbytes(1), Instant.now())
        )

        val stats: CollectionStats = runGarbageCollector(Optional.of<Long?>(kbytes(2)), Optional.empty<Duration?>())

        Truth.assertThat(stats).isEqualTo(CollectionStats(2, kbytes(2), 0, 0, false, Duration.ZERO))
        assertFilesExist("ac/123", "cas/456")
    }

    @Test
    @Throws(Exception::class)
    fun sizePolicy_collectsOldest() {
        writeFiles(
            Entry.Companion.of("ac/123", kbytes(1), daysAgo(1)),
            Entry.Companion.of("cas/456", kbytes(1), daysAgo(2)),
            Entry.Companion.of("ac/abc", kbytes(1), daysAgo(3)),
            Entry.Companion.of("cas/def", kbytes(1), daysAgo(4))
        )

        val stats: CollectionStats = runGarbageCollector(Optional.of<Long?>(kbytes(2)), Optional.empty<Duration?>())

        Truth.assertThat(stats)
            .isEqualTo(CollectionStats(4, kbytes(4), 2, kbytes(2), false, Duration.ZERO))
        assertFilesExist("ac/123", "cas/456")
        assertFilesDoNotExist("ac/abc", "cas/def")
    }

    @Test
    @Throws(Exception::class)
    fun sizePolicy_tieBreakByPath() {
        writeFiles(
            Entry.Companion.of("ac/123", kbytes(1), daysAgo(1)),
            Entry.Companion.of("cas/456", kbytes(1), daysAgo(1)),
            Entry.Companion.of("ac/abc", kbytes(1), daysAgo(1)),
            Entry.Companion.of("cas/def", kbytes(1), daysAgo(1))
        )

        val stats: CollectionStats = runGarbageCollector(Optional.of<Long?>(kbytes(2)), Optional.empty<Duration?>())

        Truth.assertThat(stats)
            .isEqualTo(CollectionStats(4, kbytes(4), 2, kbytes(2), false, Duration.ZERO))
        assertFilesExist("cas/456", "cas/def")
        assertFilesDoNotExist("ac/123", "ac/abc")
    }

    @Test
    @Throws(Exception::class)
    fun agePolicy_noCollection() {
        writeFiles(
            Entry.Companion.of("ac/123", kbytes(1), Instant.now()),
            Entry.Companion.of("cas/456", kbytes(1), Instant.now())
        )

        val stats: CollectionStats = runGarbageCollector(Optional.empty<Long?>(), Optional.of<Duration?>(days(3)!!))

        Truth.assertThat(stats).isEqualTo(CollectionStats(2, kbytes(2), 0, 0, false, Duration.ZERO))
        assertFilesExist("ac/123", "cas/456")
    }

    @Test
    @Throws(Exception::class)
    fun agePolicy_collectsOldest() {
        writeFiles(
            Entry.Companion.of("ac/123", kbytes(1), daysAgo(1)),
            Entry.Companion.of("cas/456", kbytes(1), daysAgo(2)),
            Entry.Companion.of("ac/abc", kbytes(1), daysAgo(4)),
            Entry.Companion.of("cas/def", kbytes(1), daysAgo(5))
        )

        val stats: CollectionStats =
            runGarbageCollector(Optional.empty<Long?>(), Optional.of<Duration?>(Duration.ofDays(3)))

        Truth.assertThat(stats)
            .isEqualTo(CollectionStats(4, kbytes(4), 2, kbytes(2), false, Duration.ZERO))
        assertFilesExist("ac/123", "cas/456")
        assertFilesDoNotExist("ac/abc", "cas/def")
    }

    @Test
    @Throws(Exception::class)
    fun sizeAndAgePolicy_noCollection() {
        writeFiles(
            Entry.Companion.of("ac/123", kbytes(1), Instant.now()),
            Entry.Companion.of("cas/456", kbytes(1), Instant.now())
        )

        val stats: CollectionStats =
            runGarbageCollector(Optional.of<Long?>(kbytes(2)), Optional.of<Duration?>(days(1)!!))

        Truth.assertThat(stats).isEqualTo(CollectionStats(2, kbytes(2), 0, 0, false, Duration.ZERO))
        assertFilesExist("ac/123", "cas/456")
    }

    @Test
    @Throws(Exception::class)
    fun sizeAndAgePolicy_sizeMoreRestrictiveThanAge_collectsOldest() {
        writeFiles(
            Entry.Companion.of("ac/123", kbytes(1), daysAgo(1)),
            Entry.Companion.of("cas/456", kbytes(1), daysAgo(2)),
            Entry.Companion.of("ac/abc", kbytes(1), daysAgo(3)),
            Entry.Companion.of("cas/def", kbytes(1), daysAgo(4))
        )

        val stats: CollectionStats =
            runGarbageCollector(Optional.of<Long?>(kbytes(2)), Optional.of<Duration?>(days(4)!!))

        Truth.assertThat(stats)
            .isEqualTo(CollectionStats(4, kbytes(4), 2, kbytes(2), false, Duration.ZERO))
        assertFilesExist("ac/123", "cas/456")
        assertFilesDoNotExist("ac/abc", "cas/def")
    }

    @Test
    @Throws(Exception::class)
    fun sizeAndAgePolicy_ageMoreRestrictiveThanSize_collectsOldest() {
        writeFiles(
            Entry.Companion.of("ac/123", kbytes(1), daysAgo(1)),
            Entry.Companion.of("cas/456", kbytes(1), daysAgo(2)),
            Entry.Companion.of("ac/abc", kbytes(1), daysAgo(3)),
            Entry.Companion.of("cas/def", kbytes(1), daysAgo(4))
        )

        val stats: CollectionStats =
            runGarbageCollector(Optional.of<Long?>(kbytes(3)), Optional.of<Duration?>(days(3)!!))

        Truth.assertThat(stats)
            .isEqualTo(CollectionStats(4, kbytes(4), 2, kbytes(2), false, Duration.ZERO))
        assertFilesExist("ac/123", "cas/456")
        assertFilesDoNotExist("ac/abc", "cas/def")
    }

    @Test
    @Throws(Exception::class)
    fun ignoresTmpAndGcSubdirectories() {
        writeFiles(
            Entry.Companion.of("gc/foo", kbytes(1), daysAgo(1)), Entry.Companion.of("tmp/foo", kbytes(1), daysAgo(1))
        )

        val stats: CollectionStats = runGarbageCollector(Optional.of<Long?>(1L), Optional.of<Duration?>(days(1)!!))

        Truth.assertThat(stats).isEqualTo(CollectionStats(0, 0, 0, 0, false, Duration.ZERO))
        assertFilesExist("gc/foo", "tmp/foo")
    }

    @Test
    @Throws(Exception::class)
    fun failsWhenLockIsAlreadyHeld() {
        ExternalFileSystemLock.getShared(rootDir.getRelative("gc/lock")).use { externalLock ->
            val e =
                Assert.assertThrows<Exception?>(
                    Exception::class.java,
                    ThrowingRunnable { runGarbageCollector(Optional.of<Long?>(1L), Optional.empty<Duration?>()) })
            Truth.assertThat(e).isInstanceOf(IOException::class.java)
            Truth.assertThat(e).hasMessageThat().contains("failed to acquire exclusive filesystem lock")
        }
    }

    @Throws(IOException::class)
    private fun assertFilesExist(vararg relativePaths: String?) {
        for (relativePath in relativePaths) {
            val path: Path = rootDir.getRelative(relativePath)
            Truth.assertWithMessage("expected %s to exist".formatted(relativePath))
                .that(path.exists())
                .isTrue()
        }
    }

    @Throws(IOException::class)
    private fun assertFilesDoNotExist(vararg relativePaths: String?) {
        for (relativePath in relativePaths) {
            val path: Path = rootDir.getRelative(relativePath)
            Truth.assertWithMessage("expected %s to not exist".formatted(relativePath))
                .that(path.exists())
                .isFalse()
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun runGarbageCollector(
        maxSizeBytes: Optional<Long?>?, maxAge: Optional<Duration?>?
    ): CollectionStats {
        val gc =
            DiskCacheGarbageCollector(
                rootDir,
                executorService,
                CollectionPolicy(maxSizeBytes, maxAge)
            )
        val resultStats: CollectionStats = gc.run()
        return CollectionStats(
            resultStats.totalEntries(),
            resultStats.totalBytes(),
            resultStats.deletedEntries(),
            resultStats.deletedBytes(),
            resultStats.concurrentUpdate(),
            Duration.ZERO
        )
    }

    @Throws(IOException::class)
    private fun writeFiles(vararg entries: Entry) {
        for (entry in entries) {
            writeFile(entry.path, entry.size, entry.mtime)
        }
    }

    @Throws(IOException::class)
    private fun writeFile(relativePath: String?, size: Long, mtime: Instant) {
        val path: Path = rootDir.getRelative(relativePath)
        path.getParentDirectory().createDirectoryAndParents()
        path.getOutputStream().use { out ->
            out.write(ByteArray(size.toInt()))
        }
        path.setLastModifiedTime(mtime.toEpochMilli())
    }

    companion object {
        private fun daysAgo(days: Int): Instant? {
            return Instant.now().minus(Duration.ofDays(days.toLong()))
        }

        private fun days(days: Int): Duration? {
            return Duration.ofDays(days.toLong())
        }

        private fun kbytes(kbytes: Int): Long {
            return kbytes * 1024L
        }
    }
}
