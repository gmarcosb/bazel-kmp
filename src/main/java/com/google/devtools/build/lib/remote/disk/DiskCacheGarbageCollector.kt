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

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ComparisonChain
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor
import com.google.devtools.build.lib.util.StringUtilities
import com.google.devtools.build.lib.vfs.Dirent
import com.google.devtools.build.lib.vfs.Path
import java.time.Duration
import java.util.*
import java.util.concurrent.atomic.LongAdder
import java.util.function.Function

/**
 * A garbage collector for the disk cache.
 * 
 * 
 * Garbage collection works by enumerating the entire contents of the disk cache, identifying
 * candidates for deletion according to a [CollectionPolicy], and deleting them. This process
 * may take a significant amount of time on large disk caches and slow filesystems, and may be
 * interrupted at any time.
 */
class DiskCacheGarbageCollector(
    @get:VisibleForTesting val root: Path,
    executorService: ExecutorService?,
    @get:VisibleForTesting val policy: CollectionPolicy
) {
    /**
     * Describes a disk cache entry.
     * 
     * @param path path relative to the root directory of the disk cache
     * @param size file size in bytes
     * @param mtime file modification time
     */
    @kotlin.jvm.JvmRecord
    private data class Entry(val path: String?, val size: Long, val mtime: Long)

    /**
     * Determines which entries should be collected.
     * 
     * @param maxSizeBytes the maximum total size in bytes, or empty for no size limit
     * @param maxAge the maximum age of cache entries, or empty for no age limit
     */
    @kotlin.jvm.JvmRecord
    data class CollectionPolicy(val maxSizeBytes: Optional<Long?>?, val maxAge: Optional<Duration?>?) {
        /**
         * Returns the entries to be deleted.
         * 
         * @param entries the full list of entries
         */
        fun getEntriesToDelete(entries: MutableList<Entry?>): MutableList<Entry> {
            entries.sort(COMPARATOR)

            var excessSizeBytes = getExcessSizeBytes(entries)
            val timeCutoff = this.timeCutoff

            var i = 0
            while (i < entries.size()) {
                if (excessSizeBytes <= 0 && entries.get(i)!!.mtime >= timeCutoff) {
                    break
                }
                excessSizeBytes -= entries.get(i)!!.size
                i++
            }

            return entries.subList(0, i)
        }

        private fun getExcessSizeBytes(entries: MutableList<Entry?>): Long {
            if (maxSizeBytes!!.isEmpty()) {
                return 0
            }
            val currentSizeBytes = entries.stream().mapToLong(Entry::size).sum()
            return currentSizeBytes - maxSizeBytes.get()
        }

        private val timeCutoff: Long
            get() {
                if (maxAge!!.isEmpty()) {
                    return 0
                }
                return Instant.now().minus(maxAge.get()).toEpochMilli()
            }

        companion object {
            // Sort older entries before newer ones, tie breaking by path. This causes AC entries to be
            // sorted before CAS entries with the same age, making it less likely for garbage collection
            // to break referential integrity in the event that mtime resolution is insufficient.
            private val COMPARATOR = Comparator { x: Entry?, y: Entry? ->
                ComparisonChain.start()
                    .compare(x!!.mtime, y!!.mtime)
                    .compare(x.path, y.path)
                    .result()
            }
        }
    }

    @kotlin.jvm.JvmRecord
    private data class DeletionStats(val deletedEntries: Long, val deletedBytes: Long, val concurrentUpdate: Boolean)

    /** Stats for a garbage collection run.  */
    @kotlin.jvm.JvmRecord
    data class CollectionStats(
      @kotlin.jvm.JvmField val totalEntries: Long,
      @kotlin.jvm.JvmField val totalBytes: Long,
      @kotlin.jvm.JvmField val deletedEntries: Long,
      @kotlin.jvm.JvmField val deletedBytes: Long,
      @kotlin.jvm.JvmField val concurrentUpdate: Boolean,
      val elapsedTime: Duration?
    ) {
        /** Returns a human-readable summary.  */
        fun displayString(): String? {
            val elapsedSeconds = elapsedTime!!.toSecondsPart() + elapsedTime.toMillisPart() / 1000.0
            val filesPerSecond = Math.round(deletedEntries.toDouble() / elapsedSeconds).toInt()
            val bytesPerSecond = Math.round(deletedBytes.toDouble() / elapsedSeconds).toInt()

            return "Deleted %d of %d files, reclaimed %s of %s in %.2f seconds (%d files/s, %s/s)%s"
                .formatted(
                    this.deletedEntries,
                    this.totalEntries,
                    StringUtilities.bytesCountToDisplayString(this.deletedBytes),
                    StringUtilities.bytesCountToDisplayString(this.totalBytes),
                    elapsedSeconds,
                    filesPerSecond,
                    StringUtilities.bytesCountToDisplayString(bytesPerSecond.toLong()),
                    if (this.concurrentUpdate) " (concurrent update detected)" else ""
                )
        }
    }

    private val executorService: ExecutorService?
    private val excludedDirs: ImmutableSet<Path?>

    /**
     * Creates a new garbage collector.
     * 
     * @param root the root directory of the disk cache
     * @param executorService the executor service to schedule I/O operations onto
     * @param policy the garbage collection policy to use
     */
    init {
        this.executorService = executorService
        this.excludedDirs =
            EXCLUDED_DIRS.stream().map<Path?>(Function { child: String? -> root.getChild(child) }).collect(
                ImmutableSet.toImmutableSet<Path?>()
            )
    }

    /**
     * Runs garbage collection.
     * 
     * @throws IOException if an I/O error occurred
     * @throws InterruptedException if the thread was interrupted
     */
    @Throws(IOException::class, InterruptedException::class)
    fun run(): CollectionStats {
        // Acquire an exclusive lock to prevent two Bazel processes from simultaneously running
        // garbage collection, which can waste resources and lead to incorrect results.
        FileSystemLock.tryGet(root.getRelative("gc/lock"), LockMode.EXCLUSIVE).use { lock ->
            return runUnderLock()
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun runUnderLock(): CollectionStats {
        val startTime: Instant = Instant.now()
        val scanner = EntryScanner()
        val deleter = EntryDeleter()

        val allEntries = scanner.scan()
        val entriesToDelete = policy.getEntriesToDelete(allEntries)

        for (entry in entriesToDelete) {
            deleter.delete(entry)
        }

        val deletionStats = deleter.await()
        val elapsedTime = Duration.between(startTime, Instant.now())

        return CollectionStats(
            allEntries.size().toLong(),
            allEntries.stream().mapToLong(Entry::size).sum(),
            deletionStats.deletedEntries,
            deletionStats.deletedBytes,
            deletionStats.concurrentUpdate,
            elapsedTime
        )
    }

    /** Lists all disk cache entries, performing I/O in parallel.  */
    private inner class EntryScanner : AbstractQueueVisitor(
        executorService,
        ExecutorOwnership.SHARED,
        ExceptionHandlingMode.FAIL_FAST,
        ErrorClassifier.DEFAULT
    ) {
        private val entries = ArrayList<Entry?>()

        /** Lists all disk cache entries.  */
        @Throws(IOException::class, InterruptedException::class)
        fun scan(): MutableList<Entry?> {
            execute({ visitDirectory(root) })
            try {
                awaitQuiescence(true)
            } catch (e: UncheckedIOException) {
                throw e.getCause()
            }
            return entries
        }

        fun visitDirectory(path: Path) {
            try {
                for (dirent in path.readdir(Symlinks.NOFOLLOW)) {
                    val childPath = path.getChild(dirent.name)
                    if (dirent.type == Dirent.Type.FILE) {
                        // The file may be gone by the time we stat it.
                        val status: FileStatus? = childPath.statIfFound()
                        if (status != null) {
                            val entry =
                                Entry(
                                    childPath.relativeTo(root).getPathString(),
                                    status.getSize(),
                                    status.getLastModifiedTime()
                                )
                            synchronized(entries) {
                                entries.add(entry)
                            }
                        }
                    } else if (dirent.type == Dirent.Type.DIRECTORY
                        && !excludedDirs.contains(childPath)
                    ) {
                        execute({ visitDirectory(childPath) })
                    }
                    // Deliberately ignore other file types, which should never occur in a well-formed cache.
                }
            } catch (e: IOException) {
                throw UncheckedIOException(e)
            }
        }
    }

    /** Deletes disk cache entries, performing I/O in parallel.  */
    private inner class EntryDeleter : AbstractQueueVisitor(
        executorService,
        ExecutorOwnership.SHARED,
        ExceptionHandlingMode.FAIL_FAST,
        ErrorClassifier.DEFAULT
    ) {
        private val deletedEntries = LongAdder()
        private val deletedBytes = LongAdder()
        private val concurrentUpdate: AtomicBoolean = AtomicBoolean(false)

        /** Enqueues an entry to be deleted.  */
        fun delete(entry: Entry) {
            execute(
                {
                    val path = root.getRelative(entry.path)
                    try {
                        val status: FileStatus? = path.statIfFound()
                        if (status == null) {
                            // The entry is already gone.
                            concurrentUpdate.set(true)
                            return@execute
                        }
                        if (status.getLastModifiedTime() != entry.mtime) {
                            // The entry was likely accessed by a build since we statted it.
                            concurrentUpdate.set(true)
                            return@execute
                        }
                        if (path.delete()) {
                            deletedEntries.increment()
                            deletedBytes.add(entry.size)
                        } else {
                            // The entry is already gone.
                            concurrentUpdate.set(true)
                        }
                    } catch (e: IOException) {
                        throw UncheckedIOException(e)
                    }
                })
        }

        /** Waits for all enqueued deletions to complete.  */
        @Throws(IOException::class, InterruptedException::class)
        fun await(): DeletionStats {
            try {
                awaitQuiescence(true)
            } catch (e: UncheckedIOException) {
                throw e.getCause()
            }
            return DeletionStats(deletedEntries.sum(), deletedBytes.sum(), concurrentUpdate.get())
        }
    }

    companion object {
        private val EXCLUDED_DIRS: ImmutableSet<String?> = ImmutableSet.of<String?>("tmp", "gc")
    }
}
