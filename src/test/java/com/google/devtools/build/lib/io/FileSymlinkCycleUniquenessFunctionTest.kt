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
package com.google.devtools.build.lib.io

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.vfs.DigestHashFunction
import org.junit.Test

@RunWith(JUnit4::class)
class FileSymlinkCycleUniquenessFunctionTest {
    @Test
    fun testHashCodeAndEqualsContract() {
        val root: Root? = Root.fromPath(InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/root"))
        val p1: RootedPath = RootedPath.toRootedPath(root, PathFragment.create("p1"))
        val p2: RootedPath = RootedPath.toRootedPath(root, PathFragment.create("p2"))
        val p3: RootedPath? = RootedPath.toRootedPath(root, PathFragment.create("p3"))
        val cycleA1: ImmutableList<RootedPath?> = ImmutableList.of<RootedPath?>(p1)
        val cycleB1: ImmutableList<RootedPath?> = ImmutableList.of<RootedPath?>(p2)
        val cycleC1: ImmutableList<RootedPath?> = ImmutableList.of<RootedPath?>(p1, p2, p3)
        val cycleC2: ImmutableList<RootedPath?> = ImmutableList.of<RootedPath?>(p2, p3, p1)
        val cycleC3: ImmutableList<RootedPath?> = ImmutableList.of<RootedPath?>(p3, p1, p2)
        EqualsTester()
            .addEqualityGroup(FileSymlinkCycleUniquenessFunction.key(cycleA1))
            .addEqualityGroup(FileSymlinkCycleUniquenessFunction.key(cycleB1))
            .addEqualityGroup(
                FileSymlinkCycleUniquenessFunction.key(cycleC1),
                FileSymlinkCycleUniquenessFunction.key(cycleC2),
                FileSymlinkCycleUniquenessFunction.key(cycleC3)
            )
            .testEquals()
    }
}
