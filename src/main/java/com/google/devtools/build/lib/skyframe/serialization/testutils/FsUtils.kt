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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.devtools.build.lib.vfs.DigestHashFunction
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.lib.vfs.Root
import com.google.devtools.build.lib.vfs.Root.RootCodecDependencies
import com.google.devtools.build.lib.vfs.RootedPath
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem

/** Common FileSystem related items for serialization tests.  */
object FsUtils {
    // Choice of digest function doesn't matter.
    @kotlin.jvm.JvmField
    val TEST_FILESYSTEM: InMemoryFileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    private val TEST_ROOT: Root = Root.fromPath(TEST_FILESYSTEM.getPath(PathFragment.create("/anywhere/at/all")))

    @kotlin.jvm.JvmField
    val TEST_ROOTED_PATH: RootedPath = RootedPath.toRootedPath(TEST_ROOT, PathFragment.create("all/at/anywhere"))

    /** Returns path relative to [.TEST_ROOTED_PATH].  */
    @kotlin.jvm.JvmStatic
    fun rootPathRelative(path: String?): PathFragment? {
        return TEST_ROOTED_PATH.getRootRelativePath().getRelative(path)
    }

    fun addDependencies(tester: com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester) {
        tester.addDependency<InMemoryFileSystem?>(
            com.google.devtools.build.lib.vfs.FileSystem::class.java,
            TEST_FILESYSTEM
        )
        tester.addDependency<RootCodecDependencies?>(
            RootCodecDependencies::class.java,
            RootCodecDependencies( /*likelyPopularRoot=*/TEST_ROOT)
        )
    }
}
