// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.vfs.SymlinkAwareFileSystemTest
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import net.starlark.java.syntax.Location.file
import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tests for the [JavaIoFileSystem]. That file system by itself is not capable of creating
 * symlinks; use the unix one to create them, so that the test can check that the file system
 * handles their existence correctly.
 */
class JavaIoFileSystemTest : SymlinkAwareFileSystemTest() {
    public override fun getFreshFileSystem(digestHashFunction: DigestHashFunction?): FileSystem? {
        return JavaIoFileSystem(digestHashFunction)
    }

    // Tests are inherited from the FileSystemTest
    // JavaIoFileSystem incorrectly throws a FileNotFoundException for all IO errors. This means that
    // statIfFound incorrectly suppresses those errors.
    @org.junit.Test
    override fun testBadPermissionsThrowsExceptionOnStatIfFound() {
    }

    @Throws(IOException::class)
    override fun isHardLinked(a: Path, b: Path): Boolean {
        return java.nio.file.Files.readAttributes(
            Paths.get(a.toString()), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
        )
            .fileKey()
            .equals(
                java.nio.file.Files.readAttributes(
                    Paths.get(b.toString()), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                )
                    .fileKey()
            )
    }

    /**
     * This test has a large number of threads racing to create the same subdirectories.
     * 
     * 
     * We create N number of distinct directory trees, eg. the tree "0-0/0-1/0-2/0-3/0-4" followed
     * by the tree "1-0/1-1/1-2/1-3/1-4" etc. If there is race we should quickly get a deadlock.
     * 
     * 
     * A timeout of this test is likely because of a deadlock.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateDirectoriesThreadSafety() {
        val threadCount = 200
        val directoryCreationCount = 500 // We create this many sets of directories
        val subDirectoryCount = 5 // Each directory tree is this deep
        val executor: com.google.common.util.concurrent.ListeningExecutorService =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(threadCount))
        val futures: MutableList<com.google.common.util.concurrent.ListenableFuture<IOException?>?> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<IOException?>?>()
        for (threadIndex in 0..<threadCount) {
            futures.add(
                executor.submit<IOException?>(
                    java.util.concurrent.Callable {
                        try {
                            for (loopi in 0..<directoryCreationCount) {
                                val subDirs: MutableList<Path?> =
                                    getSubDirectories(xEmptyDirectory, loopi, subDirectoryCount)
                                val lastDir: Path? = com.google.common.collect.Iterables.getLast<Path?>(subDirs)
                                lastDir.createDirectoryAndParents()
                            }
                        } catch (e: IOException) {
                            return@submit e
                        }
                        null
                    })
            )
        }
        val all: com.google.common.util.concurrent.ListenableFuture<MutableList<IOException?>> =
            com.google.common.util.concurrent.Futures.allAsList<IOException?>(futures)
        // If the test times out here then there's likely to be a deadlock
        val exceptions: MutableList<IOException?> =
            all.get(com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS)
        val error: java.util.Optional<IOException?> =
            exceptions.stream().filter { obj: IOException? -> java.util.Objects.nonNull(obj) }.findFirst()
        if (error.isPresent()) {
            throw error.get()
        }
    }

    companion object {
        private fun getSubDirectories(base: Path, loopi: Int, subDirectoryCount: Int): MutableList<Path?> {
            var path: Path = base
            val subDirs: MutableList<Path?> = java.util.ArrayList<Path?>()
            for (subDirIndex in 0..<subDirectoryCount) {
                path = path.getChild(String.format("%d-%d", loopi, subDirIndex))
                subDirs.add(path)
            }
            return subDirs
        }
    }
}
