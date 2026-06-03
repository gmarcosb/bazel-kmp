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
package com.google.devtools.build.lib.rules.repository

import com.google.devtools.build.lib.rules.repository.RepoRecordedInput.WithValue.parse

/** Test class for [RepoRecordedInput].  */
@RunWith(JUnit4::class)
class RepoRecordedInputTest : BuildViewTestCase() {
    @org.junit.Test
    fun testMarkerFileEscaping() {
        assertMarkerFileEscaping(null)
        assertMarkerFileEscaping("\\0")
        assertMarkerFileEscaping("a\\0")
        assertMarkerFileEscaping("a b")
        assertMarkerFileEscaping("a b c")
        assertMarkerFileEscaping("a \\b")
        assertMarkerFileEscaping("a \\nb")
        assertMarkerFileEscaping("a \\\\nb")
        assertMarkerFileEscaping("a \\\nb")
        assertMarkerFileEscaping("a \nb")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFileValueToMarkerValue() {
        val path: RootedPath =
            RootedPath.toRootedPath(Root.fromPath(rootDirectory), scratch.file("foo", "bar"))

        // Digest should be returned if the FileValue has it.
        var fv: FileValue = RegularFileStateValueWithDigest(3, byteArrayOf(1, 2, 3, 4))
        assertThat(RepoRecordedInput.File.fileValueToMarkerValue(path, fv)).isEqualTo("01020304")

        // Digest should also be returned if the FileStateValue doesn't have it.
        val status: FileStatus = Mockito.mock<FileStatus>(FileStatus::class.java)
        Mockito.`when`<Any?>(status.lastChangeTime).thenReturn(100L)
        Mockito.`when`<Any?>(status.nodeId).thenReturn(200L)
        fv = RegularFileStateValueWithContentsProxy(3, FileContentsProxy.create(status))
        val expectedDigest: String =
            com.google.common.io.BaseEncoding.base16().lowerCase().encode(path.asPath().getDigest())
        assertThat(RepoRecordedInput.File.fileValueToMarkerValue(path, fv)).isEqualTo(expectedDigest)
    }

    @org.junit.Test
    fun testSplitIntoBatches() {
        assertThat(splitIntoBatches(com.google.common.collect.ImmutableList.of<E?>())).isEmpty()
        assertThat(
            splitIntoBatches(
                com.google.common.collect.ImmutableList.of<E?>(
                    parse("FILE:@@//foo:bar abc").orElseThrow(),
                    parse("FILE:@@//:baz cba").orElseThrow(),
                    parse("FILE:@@foo//:baz bac").orElseThrow(),
                    parse("ENV:KEY value").orElseThrow()
                )
            )
        )
            .containsExactly(
                com.google.common.collect.ImmutableList.of<E?>(
                    parse("FILE:@@//foo:bar abc").orElseThrow(),
                    parse("FILE:@@//:baz cba").orElseThrow()
                ),
                com.google.common.collect.ImmutableList.of<E?>(
                    parse("FILE:@@foo//:baz bac").orElseThrow(), parse("ENV:KEY value").orElseThrow()
                )
            )
    }

    companion object {
        private fun assertMarkerFileEscaping(testCase: String?) {
            val escaped: String? = RepoRecordedInput.escape(testCase)
            assertThat(RepoRecordedInput.unescape(escaped)).isEqualTo(testCase)
        }
    }
}
