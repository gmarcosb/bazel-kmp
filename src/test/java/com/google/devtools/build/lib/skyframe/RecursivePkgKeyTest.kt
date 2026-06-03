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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/** Tests for [RecursivePkgKey].  */
@RunWith(JUnit4::class)
class RecursivePkgKeyTest : BuildViewTestCase() {
    private fun buildRecursivePkgKey(
        repository: RepositoryName?,
        rootRelativePath: PathFragment?,
        excludedPaths: com.google.common.collect.ImmutableSet<PathFragment?>?
    ): SkyKey {
        val rootedPath: RootedPath? = RootedPath.toRootedPath(Root.fromPath(rootDirectory), rootRelativePath)
        return RecursivePkgValue.key(repository, rootedPath, IgnoredSubdirectories.of(excludedPaths))
    }

    private fun invalidHelper(
        rootRelativePath: PathFragment?, excludedPaths: com.google.common.collect.ImmutableSet<PathFragment?>?
    ) {
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                buildRecursivePkgKey(
                    RepositoryName.MAIN,
                    rootRelativePath,
                    excludedPaths
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidRecursivePkgKeys() {
        buildRecursivePkgKey(
            RepositoryName.MAIN,
            PathFragment.create(""),
            com.google.common.collect.ImmutableSet.of<PathFragment?>()
        )
        buildRecursivePkgKey(
            RepositoryName.MAIN,
            PathFragment.create(""),
            com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("a"))
        )

        buildRecursivePkgKey(
            RepositoryName.MAIN,
            PathFragment.create("a"),
            com.google.common.collect.ImmutableSet.of<PathFragment?>()
        )
        buildRecursivePkgKey(
            RepositoryName.MAIN,
            PathFragment.create("a"),
            com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("a/b"))
        )

        buildRecursivePkgKey(
            RepositoryName.MAIN,
            PathFragment.create("a/b"),
            com.google.common.collect.ImmutableSet.of<PathFragment?>()
        )
        buildRecursivePkgKey(
            RepositoryName.MAIN,
            PathFragment.create("a/b"),
            com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("a/b/c"))
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidRecursivePkgKeys() {
        invalidHelper(PathFragment.create(""), com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("")))
        invalidHelper(PathFragment.create("a"), com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("a")))
        invalidHelper(PathFragment.create("a"), com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("b")))
        invalidHelper(
            PathFragment.create("a/b"),
            com.google.common.collect.ImmutableSet.of<E?>(PathFragment.create("a"))
        )
    }
}
