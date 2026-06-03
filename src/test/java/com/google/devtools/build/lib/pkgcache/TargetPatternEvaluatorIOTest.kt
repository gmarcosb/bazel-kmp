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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.cmdline.TargetParsingException

/** TargetPatternEvaluator tests that require a custom filesystem.  */
@RunWith(TestParameterInjector::class)
class TargetPatternEvaluatorIOTest : AbstractTargetPatternEvaluatorTest() {
    private open class Transformer {
        @Suppress("unused")
        @Throws(IOException::class)
        open fun stat(stat: FileStatus?, path: PathFragment?, followSymlinks: Boolean): FileStatus? {
            return stat
        }

        @Suppress("unused")
        @Throws(IOException::class)
        open fun readdir(
            readdir: MutableCollection<Dirent?>?, path: PathFragment?, followSymlinks: Boolean
        ): MutableCollection<Dirent?>? {
            return readdir
        }
    }

    private var transformer: Transformer =
        com.google.devtools.build.lib.pkgcache.TargetPatternEvaluatorIOTest.Transformer()

    protected override fun createFileSystem(): FileSystem? {
        return object : InMemoryFileSystem(DigestHashFunction.SHA256) {
            @Throws(IOException::class)
            public override fun stat(path: PathFragment, followSymlinks: Boolean): FileStatus {
                val defaultResult: FileStatus = super.stat(path, followSymlinks)
                return transformer.stat(defaultResult, path, followSymlinks)
            }

            public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus? {
                return statNullable(path, followSymlinks)
            }

            public override fun statNullable(path: PathFragment, followSymlinks: Boolean): FileStatus? {
                val defaultResult: FileStatus = super.statNullable(path, followSymlinks)
                try {
                    return transformer.stat(defaultResult, path, followSymlinks)
                } catch (e: IOException) {
                    return null
                }
            }

            @Throws(IOException::class)
            public override fun readdir(path: PathFragment?, followSymlinks: Boolean): MutableCollection<Dirent?>? {
                val defaultResult: MutableCollection<Dirent?>? = super.readdir(path, followSymlinks)
                return transformer.readdir(defaultResult, path, followSymlinks)
            }
        }
    }

    /**
     * Tests that a child with an inconsistent stat (first a directory, then not) is handled properly.
     * Even keep-going mode aborts eagerly in the face of inconsistent stats.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadStat(@TestParameter keepGoing: Boolean) {
        reporter.removeHandler(failFastHandler)
        // Given a package, "parent",
        val parent: Path = scratch.file("parent/BUILD", "sh_library(name = 'parent')").getParentDirectory()
        // And a child, "badstat",
        parent.getRelative("badstat").createDirectoryAndParents()

        // Such that badstat first reports that it is a directory, and then reports that it isn't,
        this.transformer = createInconsistentFileStateTransformer("parent/badstat")

        val e: TargetParsingException =
            org.junit.Assert.assertThrows<T>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parseTargetPatternList(
                        parser, reporter, com.google.common.collect.ImmutableList.of<E?>("//parent/..."), keepGoing
                    )
                })
        assertThat(e).hasMessageThat().contains("Inconsistent filesystem operations")
        assertThat(e.getDetailedExitCode().getFailureDetail().getPackageLoading().getCode())
            .isEqualTo(FailureDetails.PackageLoading.Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR)
    }

    /**
     * Tests that a child with an inconsistent stat (first a directory, then not) is handled properly
     * when given a path-as-target. Even keep-going mode aborts eagerly in the face of inconsistent
     * stats.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadStatPathAsTarget(@TestParameter keepGoing: Boolean) {
        reporter.removeHandler(failFastHandler)
        scratch.file("parent/BUILD", "sh_library(name = 'parent')").getParentDirectory()
        delegatingSyscallCache.setDelegate(com.google.devtools.build.lib.testutil.TestUtils.makeDisappearingFileCache("parent/BUILD"))
        val e: TargetParsingException =
            org.junit.Assert.assertThrows<T>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    parseTargetPatternList(
                        parser,
                        reporter,
                        com.google.common.collect.ImmutableList.of<E?>("parent"),
                        keepGoing
                    )
                })
        assertThat(e).hasMessageThat().contains("Inconsistent filesystem operations")
        assertThat(e.getDetailedExitCode().getFailureDetail().getPackageLoading().getCode())
            .isEqualTo(FailureDetails.PackageLoading.Code.TRANSIENT_INCONSISTENT_FILESYSTEM_ERROR)
    }

    /**
     * Tests that a package subdirectory that throws an IOException when it is listed via readdir does
     * not prevent evaluation of the remaining packages beneath a directory and the return of a
     * partial result.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadReaddirKeepGoing() {
        reporter.removeHandler(failFastHandler)
        // Given a package, "parent",
        val parent: Path = scratch.file("parent/BUILD", "filegroup(name = 'parent')").getParentDirectory()
        // And a child, "badstat",
        parent.getRelative("badstat").createDirectoryAndParents()

        // Such that badstat reports that it is a directory, but throws an error when its Dirents are
        // collected,
        this.transformer = createBadDirectoryListingTransformer("parent/badstat")

        // When we find all the targets beneath parent in keep_going mode, we get the valid target
        // parent:parent, even though processing badstat threw an IOException,
        assertThat(parseListKeepGoing("//parent/...").first)
            .containsExactlyElementsIn(labels("//parent:parent"))

        // And the TargetPatternEvaluator reported the expected ERROR event to the handler.
        assertContainsEvent(
            "Failed to list directory contents, for parent/badstat, skipping: Path ended in "
                    + "parent/badstat, so readdir failed",
            com.google.common.collect.ImmutableSet.of<E?>(com.google.devtools.build.lib.events.EventKind.ERROR)
        )
    }

    private fun createInconsistentFileStateTransformer(badPathSuffix: String?): Transformer {
        val isDirectory: AtomicBoolean = AtomicBoolean(true)
        return object : Transformer() {
            override fun stat(stat: FileStatus, path: PathFragment, followSymlinks: Boolean): FileStatus? {
                if (path.getPathString().endsWith(badPathSuffix)) {
                    return object : InMemoryContentInfo(com.google.devtools.build.lib.clock.BlazeClock.instance()) {
                        val isDirectory: Boolean
                            get() =// Trigger inconsistent filesystem exception.
                                isDirectory.getAndSet(false)

                        val isFile: Boolean
                            get() = stat.isFile

                        val isSpecialFile: Boolean
                            get() = stat.isSpecialFile

                        val isSymbolicLink: Boolean
                            get() = stat.isSymbolicLink

                        val size: Long
                            get() {
                                try {
                                    return stat.size
                                } catch (e: IOException) {
                                    throw java.lang.IllegalStateException(e)
                                }
                            }

                        @get:kotlin.jvm.Synchronized
                        val lastModifiedTime: Long
                            get() {
                                try {
                                    return stat.lastModifiedTime
                                } catch (e: IOException) {
                                    throw java.lang.IllegalStateException(e)
                                }
                            }

                        @get:kotlin.jvm.Synchronized
                        val lastChangeTime: Long
                            get() {
                                try {
                                    return stat.lastChangeTime
                                } catch (e: IOException) {
                                    throw java.lang.IllegalStateException(e)
                                }
                            }

                        val nodeId: Long
                            get() {
                                try {
                                    return stat.nodeId
                                } catch (e: IOException) {
                                    throw java.lang.IllegalStateException(e)
                                }
                            }
                    }
                }
                return stat
            }
        }
    }

    private fun createBadDirectoryListingTransformer(badPathSuffix: String?): Transformer {
        return object : Transformer() {
            @Throws(IOException::class)
            override fun readdir(
                readdir: MutableCollection<Dirent?>?, path: PathFragment, followSymlinks: Boolean
            ): MutableCollection<Dirent?>? {
                if (path.getPathString().endsWith(badPathSuffix)) {
                    throw IOException("Path ended in " + badPathSuffix + ", so readdir failed.")
                }
                return readdir
            }
        }
    }
}
