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
package com.google.devtools.build.lib.vfs.inmemoryfs

import com.google.devtools.build.lib.clock.BlazeClock.instance
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryDirectoryInfo
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryDirectoryInfo.addChild
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryDirectoryInfo.removeChild
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileInfo
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class InMemoryContentInfoTest {
    private var clock: com.google.devtools.build.lib.clock.Clock? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createClock() {
        clock = com.google.devtools.build.lib.clock.BlazeClock.instance()
    }

    @org.junit.Test
    fun testDirectoryCannotAddNullChild() {
        val directory: InMemoryDirectoryInfo = InMemoryDirectoryInfo(clock)

        org.junit.Assert.assertThrows<java.lang.NullPointerException?>(
            java.lang.NullPointerException::class.java,
            org.junit.function.ThrowingRunnable { directory.addChild("bar", null) })
    }

    @org.junit.Test
    fun testDirectoryCannotAddChildTwice() {
        val directory: InMemoryDirectoryInfo = InMemoryDirectoryInfo(clock)
        val otherFile: InMemoryFileInfo = InMemoryFileInfo(clock)
        directory.addChild("bar", otherFile)

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { directory.addChild("bar", otherFile) })
    }

    @org.junit.Test
    fun testDirectoryRemoveNonExistingChild() {
        val directory: InMemoryDirectoryInfo = InMemoryDirectoryInfo(clock)
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { directory.removeChild("bar") })
    }
}
