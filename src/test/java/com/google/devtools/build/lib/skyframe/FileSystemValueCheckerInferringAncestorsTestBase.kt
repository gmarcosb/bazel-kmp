// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileStateValue

class FileSystemValueCheckerInferringAncestorsTestBase {
    protected val scratch: Scratch = Scratch()
    protected val statedPaths: MutableList<String?> = java.util.ArrayList<String?>()
    protected var syscallCache: DefaultSyscallCache = DefaultSyscallCache.newBuilder().build()
    protected var root: Root? = null
    protected var inMemoryGraph: InMemoryGraph? = null
    protected var throwOnStat: java.lang.Exception? = null

    private var untrackedRoot: Root? = null

    @Before
    @Throws(IOException::class)
    fun setUpGraphAndRoot() {
        createGraph()
        val srcRootPath: Path = scratch.dir("/src")
        val srcRoot: PathFragment? = srcRootPath.asFragment()
        val trackingFileSystem: FileSystem =
            object : DelegateFileSystem(scratch.getFileSystem()) {
                @kotlin.jvm.Synchronized
                @Throws(IOException::class)
                public override fun statIfFound(path: PathFragment, followSymlinks: Boolean): FileStatus? {
                    if (throwOnStat != null) {
                        val toThrow: java.lang.Exception? = throwOnStat
                        throwOnStat = null
                        com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(
                            toThrow,
                            IOException::class.java
                        )
                        com.google.common.base.Throwables.throwIfUnchecked(toThrow)
                        throw java.lang.AssertionError("Unexpected exception type", toThrow)
                    }
                    statedPaths.add(path.relativeTo(srcRoot).toString())
                    return super.statIfFound(path, followSymlinks)
                }
            }
        root = Root.fromPath(trackingFileSystem.getPath(srcRoot))
        scratch.setWorkingDir("/src")
        untrackedRoot = Root.fromPath(srcRootPath)
    }

    @com.google.errorprone.annotations.ForOverride
    protected fun createGraph() {
        inMemoryGraph = InMemoryGraph.create()
    }

    @org.junit.After
    fun checkExceptionThrown() {
        Truth.assertThat(throwOnStat).isNull()
        syscallCache.clear()
    }

    protected fun fileStateValueKey(relativePath: String?): FileStateKey {
        return FileStateValue.key(
            RootedPath.toRootedPath(root, root.asPath().getRelative(relativePath))
        )
    }

    protected fun directoryListingStateValueKey(relativePath: String?): DirectoryListingStateValue.Key {
        return DirectoryListingStateValue.key(
            RootedPath.toRootedPath(root, root.asPath().getRelative(relativePath))
        )
    }

    @Throws(IOException::class)
    protected fun fileStateValue(relativePath: String?): FileStateValue {
        return FileStateValue.create(
            RootedPath.toRootedPath(
                untrackedRoot, untrackedRoot.asPath().asFragment().getRelative(relativePath)
            ),
            SyscallCache.NO_CACHE,  /* tsgm= */
            null
        )
    }

    companion object {
        protected fun directoryListingStateValue(vararg dirents: Dirent?): DirectoryListingStateValue {
            return DirectoryListingStateValue.create(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (dirents))
        }

        protected fun <T> assertIsSubsetOf(list: Iterable<T?>, vararg elements: T?) {
            val set: com.google.common.collect.ImmutableSet<T?> =
                com.google.common.collect.ImmutableSet.copyOf<T?>(elements)
            Truth.assertWithMessage("%s has elements from outside of %s", list, set)
                .that(set)
                .containsAtLeastElementsIn(list)
        }
    }
}
