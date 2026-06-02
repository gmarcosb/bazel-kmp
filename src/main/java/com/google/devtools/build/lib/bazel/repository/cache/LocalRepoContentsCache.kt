// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.cache

import com.google.devtools.build.lib.util.FileSystemLock
import com.google.devtools.build.lib.util.FileSystemLock.LockMode
import com.google.devtools.build.lib.vfs.DigestHashFunction
import com.google.devtools.build.lib.vfs.Symlinks
import com.google.devtools.build.zip.ZipFileEntry.getName
import java.io.IOException
import java.time.Instant
import java.util.HashMap
import java.util.HashSet
import java.util.UUID

/**
 * A cache directory that stores the contents of fetched repos across different workspaces.
 * 
 * 
 * The repo contents cache is laid out in two layers. The first layer is a lookup by "predeclared
 * inputs hash", which is defined as the hash of all predeclared inputs of a repo (such as
 * transitive bzl digest, repo attrs, starlark semantics, etc). Each distinct predeclared inputs
 * hash is its own entry directory in the first layer.
 * 
 * 
 * Inside each entry directory are pairs of directories and files `<UUID, UUID.recorded_inputs>`. The file `UUID.recorded_inputs` contains the recorded inputs and
 * their values of a cached repo, and the directory `UUID` contains the cached repo contents.
 * 
 * 
 * On a cache hit (that is, the predeclared inputs hash matches, and recorded inputs are
 * up-to-date), the recorded inputs file has its mtime updated. Cached repos whose recorded inputs
 * file is older than `--repo_contents_cache_gc_max_age` are garbage collected.
 */
class LocalRepoContentsCache {
    private var path: com.google.devtools.build.lib.vfs.Path? = null
    private var sharedLock: FileSystemLock? = null

    fun setPath(path: com.google.devtools.build.lib.vfs.Path?) {
        this.path = path
    }

    fun getPath(): com.google.devtools.build.lib.vfs.Path? {
        return path
    }

    val isEnabled: Boolean
        get() = path != null

    /** A candidate repo in the repo contents cache for one predeclared input hash.  */
    class CandidateRepo(
        recordedInputsFile: com.google.devtools.build.lib.vfs.Path?,
        contentsDir: com.google.devtools.build.lib.vfs.Path
    ) {
        /** Updates the mtime of the recorded inputs file, to delay GC for this entry.  */
        fun touch() {
            try {
                recordedInputsFile.setLastModifiedTime(com.google.devtools.build.lib.vfs.Path.NOW_SENTINEL_TIME)
            } catch (e: IOException) {
                // swallow the exception. it's not a huge deal.
            }
        }

        val recordedInputsFile: com.google.devtools.build.lib.vfs.Path?
        val contentsDir: com.google.devtools.build.lib.vfs.Path

        init {
            this.recordedInputsFile = recordedInputsFile
            this.contentsDir = contentsDir
        }

        companion object {
            private fun fromRecordedInputsFile(recordedInputsFile: com.google.devtools.build.lib.vfs.Path): CandidateRepo {
                val recordedInputsFileBaseName: String = recordedInputsFile.getBaseName()
                val contentsDirBaseName: String =
                    recordedInputsFileBaseName.substring(
                        0, recordedInputsFileBaseName.length() - RECORDED_INPUTS_SUFFIX.length()
                    )
                return CandidateRepo(
                    recordedInputsFile, recordedInputsFile.replaceName(contentsDirBaseName)
                )
            }
        }
    }

    /** Returns the list of candidate repos for the given predeclared input hash.  */
    fun getCandidateRepos(predeclaredInputHash: String?): com.google.common.collect.ImmutableList<CandidateRepo?> {
        com.google.common.base.Preconditions.checkState(path != null)
        val entryDir: com.google.devtools.build.lib.vfs.Path = path.getRelative(predeclaredInputHash)
        try {
            // Prefer more recently used cache entries over older ones. They're more likely to be
            // up-to-date; plus, if a repo is force-fetched, we want to use the new repo instead of always
            // being stuck with the old one. Since the inputs file is touched on use, we can just sort by
            // mtime. This is slightly more complex than in runGc below as the files may be touched
            // concurrently and we need to ensure that the equality relation is consistent.
            val mtimes: HashMap<com.google.devtools.build.lib.vfs.Path?, Long?> =
                HashMap<com.google.devtools.build.lib.vfs.Path?, Long?>()
            return entryDir.getDirectoryEntries().stream()
                .filter(java.util.function.Predicate { path: com.google.devtools.build.lib.vfs.Path? ->
                    path.getBaseName().endsWith(
                        RECORDED_INPUTS_SUFFIX
                    )
                })
                .sorted(
                    java.util.Comparator.comparingLong<com.google.devtools.build.lib.vfs.Path?>(
                        java.util.function.ToLongFunction { path: com.google.devtools.build.lib.vfs.Path? ->
                            mtimes.computeIfAbsent(
                                path,
                                java.util.function.Function { path: com.google.devtools.build.lib.vfs.Path? ->
                                    getLastModifiedTimeOrZero(path)
                                })
                        })
                        .reversed()
                )
                .map<CandidateRepo?>(java.util.function.Function { recordedInputsFile: com.google.devtools.build.lib.vfs.Path? ->
                    CandidateRepo.Companion.fromRecordedInputsFile(
                        recordedInputsFile
                    )
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<CandidateRepo?>())
        } catch (e: IOException) {
            // This should only happen if `entryDir` doesn't exist yet or is not a directory. Either way,
            // don't outright fail; just treat it as if the cache is empty.
            return com.google.common.collect.ImmutableList.of<CandidateRepo?>()
        }
    }

    @Throws(IOException::class)
    private fun ensureTrashDir(): com.google.devtools.build.lib.vfs.Path {
        com.google.common.base.Preconditions.checkState(path != null)
        val trashDir: com.google.devtools.build.lib.vfs.Path = path.getChild(TRASH_PATH)
        trashDir.createDirectoryAndParents()
        return trashDir
    }

    /**
     * Moves a freshly fetched repo into the contents cache.
     * 
     * @return the new cache entry
     */
    @Throws(IOException::class)
    fun moveToCache(
        fetchedRepoDir: com.google.devtools.build.lib.vfs.Path,
        fetchedRepoMarkerFile: com.google.devtools.build.lib.vfs.Path,
        predeclaredInputHash: String?
    ): CandidateRepo {
        com.google.common.base.Preconditions.checkState(path != null)

        val entryDir: com.google.devtools.build.lib.vfs.Path = path.getRelative(predeclaredInputHash)
        val uniqueEntryName: String? = UUID.randomUUID().toString()
        val cacheRecordedInputsFile: com.google.devtools.build.lib.vfs.Path =
            entryDir.getChild(uniqueEntryName + RECORDED_INPUTS_SUFFIX)
        val cacheRepoDir: com.google.devtools.build.lib.vfs.Path = entryDir.getChild(uniqueEntryName)

        cacheRepoDir.deleteTree()
        cacheRepoDir.getParentDirectory().createDirectoryAndParents()
        // Move the fetched marker file to a temp location, so that if following operations fail, both
        // the fetched repo and the cache locations are considered out-of-date.
        val temporaryMarker: com.google.devtools.build.lib.vfs.Path =
            ensureTrashDir().getChild(UUID.randomUUID().toString())
        com.google.devtools.build.lib.vfs.FileSystemUtils.moveFile(fetchedRepoMarkerFile, temporaryMarker)
        // Now perform the move, and afterwards, restore the marker file.
        try {
            fetchedRepoDir.renameTo(cacheRepoDir)
        } catch (e: IOException) {
            cacheRepoDir.createDirectoryAndParents()
            com.google.devtools.build.lib.vfs.FileSystemUtils.moveTreesBelow(fetchedRepoDir, cacheRepoDir)
        }
        temporaryMarker.renameTo(cacheRecordedInputsFile)
        // Set up a symlink at the original fetched repo dir path.
        fetchedRepoDir.deleteTree()
        com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(fetchedRepoDir, cacheRepoDir)
        return CandidateRepo(cacheRecordedInputsFile, cacheRepoDir)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun acquireSharedLock() {
        com.google.common.base.Preconditions.checkState(path != null)
        com.google.common.base.Preconditions.checkState(sharedLock == null, "this process already has the shared lock")
        sharedLock = FileSystemLock.get(path.getRelative(LOCK_PATH), LockMode.SHARED)
    }

    @Throws(IOException::class)
    fun releaseSharedLock() {
        com.google.common.base.Preconditions.checkState(sharedLock != null)
        sharedLock.close()
        sharedLock = null
    }

    /**
     * Creates a garbage collection [IdleTask] that deletes cached repos who are last accessed
     * more than `maxAge` ago as well as duplicated repos, with an idle delay of `idleDelay`.
     * 
     * @param maxAge the maximum age of cached repos to keep in the cache. If zero, no repo will be
     * garbage collected due to age.
     */
    fun createGcIdleTask(
        maxAge: java.time.Duration,
        idleDelay: java.time.Duration
    ): com.google.devtools.build.lib.server.IdleTask {
        com.google.common.base.Preconditions.checkState(path != null)
        return object : com.google.devtools.build.lib.server.IdleTask() {
            override fun displayName(): String {
                return "Repo contents cache garbage collection"
            }

            override fun delay(): java.time.Duration {
                return idleDelay
            }

            @Throws(
                java.lang.InterruptedException::class,
                com.google.devtools.build.lib.server.IdleTaskException::class
            )
            override fun run() {
                try {
                    com.google.common.base.Preconditions.checkState(path != null)
                    FileSystemLock.tryGet(path.getRelative(LOCK_PATH), LockMode.EXCLUSIVE).use { lock ->
                        runGc(maxAge)
                    }
                    // Empty the trash dir outside the lock. No one is reading from these files, so it should
                    // be safe. At worst, multiple servers performing GC will try to delete the same files,
                    // but whatever.
                    path.getChild(TRASH_PATH).deleteTreesBelow()
                } catch (e: IOException) {
                    throw com.google.devtools.build.lib.server.IdleTaskException(e)
                }
            }
        }
    }

    @Throws(java.lang.InterruptedException::class, IOException::class)
    private fun runGc(maxAge: java.time.Duration) {
        path.setLastModifiedTime(com.google.devtools.build.lib.vfs.Path.NOW_SENTINEL_TIME)
        val cutoff: Instant =
            if (maxAge.isZero())
                Instant.MIN
            else
                Instant.ofEpochMilli(path.getLastModifiedTime()).minus(maxAge)
        val trashDir: com.google.devtools.build.lib.vfs.Path = ensureTrashDir()
        val sha256: com.google.common.hash.HashFunction = DigestHashFunction.SHA256.getHashFunction()

        for (dirent in path.readdir(Symlinks.NOFOLLOW)) {
            if (dirent.getType() != com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY || dirent.getName() == TRASH_PATH) {
                continue
            }
            // Sort all recorded input files by descending mtime, so that deduplication keeps around the
            // most recent entry.
            val recordedInputsFiles: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.vfs.Path?> =
                path.getChild(dirent.getName()).getDirectoryEntries().stream()
                    .filter(java.util.function.Predicate { file: com.google.devtools.build.lib.vfs.Path? ->
                        file.getBaseName().endsWith(
                            RECORDED_INPUTS_SUFFIX
                        )
                    })
                    .sorted(java.util.Comparator.comparingLong<com.google.devtools.build.lib.vfs.Path?>(java.util.function.ToLongFunction { path: com.google.devtools.build.lib.vfs.Path? ->
                        getLastModifiedTimeOrZero(
                            path
                        )
                    }).reversed())
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.vfs.Path?>())
            val seen: HashSet<com.google.common.hash.HashCode?> = HashSet<com.google.common.hash.HashCode?>()
            for (recordedInputsFile in recordedInputsFiles) {
                if (java.lang.Thread.interrupted()) {
                    throw java.lang.InterruptedException()
                }

                // In addition to deleting old entries, also remove identical entries. These may be created
                // when multiple Bazel servers fetch the same repo at the same time. The servers that have
                // their referenced entry deleted will roll over to the next entry on the next build.
                if (Instant.ofEpochMilli(recordedInputsFile.getLastModifiedTime()).isBefore(cutoff)
                    || !seen.add(
                        sha256.hashBytes(
                            com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(
                                recordedInputsFile
                            )
                        )
                    )
                ) {
                    recordedInputsFile.delete()
                    val repoDir: com.google.devtools.build.lib.vfs.Path =
                        CandidateRepo.Companion.fromRecordedInputsFile(recordedInputsFile).contentsDir
                    // Use a UUID to avoid clashes.
                    repoDir.renameTo(trashDir.getChild(UUID.randomUUID().toString()))
                }
            }
        }
    }

    companion object {
        const val RECORDED_INPUTS_SUFFIX: String = ".recorded_inputs"

        /**
         * The path to a "lock" file, relative to the root of the repo contents cache. While a shared lock
         * is held, no garbage collection should happen. While an exclusive lock is held, no reads should
         * happen.
         */
        const val LOCK_PATH: String = "gc_lock"

        /**
         * The path to a trash directory relative to the root of the repo contents cache.
         * 
         * 
         * Since deleting entire directories could take a bit of time, we create a trash directory
         * where we move the garbage directories to (which should be very fast). Then we can delete this
         * trash directory altogether at the end. This makes the GC process safe against being interrupted
         * in the middle (any undeleted trash will get deleted by the next GC). Also be sure to name this
         * trashDir something that couldn't ever be a predeclared inputs hash (starting with an underscore
         * should suffice).
         */
        const val TRASH_PATH: String = "_trash"

        private fun getLastModifiedTimeOrZero(path: com.google.devtools.build.lib.vfs.Path): Long {
            try {
                return path.getLastModifiedTime()
            } catch (e: IOException) {
                // If we can't read the mtime from the entry, it's broken and treated as outdated.
                return 0L
            }
        }
    }
}
