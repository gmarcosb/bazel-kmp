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

import com.google.common.testing.EqualsTester
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.common.options.testing.ConverterTester.addEqualityGroup
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [ModifiedFileSet].  */
@RunWith(JUnit4::class)
class ModifiedFileSetTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHashCodeAndEqualsContract() {
        val fragA: PathFragment? = PathFragment.create("a")
        val fragB: PathFragment? = PathFragment.create("b")

        val empty1: ModifiedFileSet? = ModifiedFileSet.NOTHING_MODIFIED
        val empty2: ModifiedFileSet? = ModifiedFileSet.builder().build()
        val empty3: ModifiedFileSet? = ModifiedFileSet.builder().modifyAll(
            com.google.common.collect.ImmutableList.of<PathFragment?>()
        ).build()

        val nonEmpty1: ModifiedFileSet? = ModifiedFileSet.builder().modifyAll(
            com.google.common.collect.ImmutableList.of<E?>(fragA, fragB)
        ).build()
        val nonEmpty2: ModifiedFileSet? = ModifiedFileSet.builder().modifyAll(
            com.google.common.collect.ImmutableList.of<E?>(fragB, fragA)
        ).build()
        val nonEmpty3: ModifiedFileSet? = ModifiedFileSet.builder().modify(fragA).modify(fragB).build()
        val nonEmpty4: ModifiedFileSet? = ModifiedFileSet.builder().modify(fragB).modify(fragA).build()

        EqualsTester()
            .addEqualityGroup(empty1, empty2, empty3)
            .addEqualityGroup(nonEmpty1, nonEmpty2, nonEmpty3, nonEmpty4)
            .addEqualityGroup(ModifiedFileSet.EVERYTHING_MODIFIED)
            .addEqualityGroup(ModifiedFileSet.EVERYTHING_DELETED)
            .testEquals()
    }
}
