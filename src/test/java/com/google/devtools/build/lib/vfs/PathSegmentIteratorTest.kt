// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for [PathSegmentIterator].  */
@RunWith(JUnit4::class)
class PathSegmentIteratorTest {
    @org.junit.Test
    fun emptyPath() {
        Truth.assertThat(segmentIterator("",  /*driveStrLength=*/0)).isEmpty()
    }

    @org.junit.Test
    fun relativePath() {
        Truth.assertThat(segmentIterator("this/is/a/relative/path",  /*driveStrLength=*/0))
            .containsExactly("this", "is", "a", "relative", "path")
            .inOrder()
    }

    @org.junit.Test
    fun root_unix() {
        Truth.assertThat(segmentIterator("/",  /*driveStrLength=*/1)).isEmpty()
    }

    @org.junit.Test
    fun root_windows() {
        Truth.assertThat(segmentIterator("C:/",  /*driveStrLength=*/3)).isEmpty()
    }

    @org.junit.Test
    fun absolutePath_unix() {
        Truth.assertThat(segmentIterator("/this/is/an/absolute/path",  /*driveStrLength=*/1))
            .containsExactly("this", "is", "an", "absolute", "path")
            .inOrder()
    }

    @org.junit.Test
    fun absolutePath_windows() {
        Truth.assertThat(segmentIterator("C:/this/is/an/absolute/path",  /*driveStrLength=*/3))
            .containsExactly("this", "is", "an", "absolute", "path")
            .inOrder()
    }

    @org.junit.Test
    fun noSuchElement() {
        val it: MutableIterator<String?> = PathSegmentIterator.create("some/path",  /*driveStrLength=*/0)
        it.next()
        it.next()
        org.junit.Assert.assertThrows<java.util.NoSuchElementException?>(
            java.util.NoSuchElementException::class.java,
            org.junit.function.ThrowingRunnable { it.next() })
    }

    companion object {
        private fun segmentIterator(normalizedPath: String?, driveStrLength: Int): Iterable<String?> {
            return Iterable { PathSegmentIterator.create(normalizedPath, driveStrLength) }
        }
    }
}
