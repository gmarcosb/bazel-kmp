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
package com.google.devtools.build.lib.vfs

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.clock.BlazeClock.instance
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.Before
import java.nio.file.Path
import java.util.Collections
import java.util.stream.Collectors

/** Tests for [Path].  */
abstract class PathAbstractTest {
    private var fileSystem: FileSystem? = null

    @Before
    fun setup() {
        fileSystem =
            InMemoryFileSystem(com.google.devtools.build.lib.clock.BlazeClock.instance(), DigestHashFunction.SHA256)
    }

    @org.junit.Test
    fun testStripsTrailingSlash() {
        // compare string forms
        assertThat(create("/foo/bar/").getPathString()).isEqualTo("/foo/bar")
        // compare fragment forms
        assertThat(create("/foo/bar/")).isEqualTo(create("/foo/bar"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBasename() {
        assertThat(create("/foo/bar").getBaseName()).isEqualTo("bar")
        assertThat(create("/foo/").getBaseName()).isEqualTo("foo")
        assertThat(create("/foo").getBaseName()).isEqualTo("foo")
        assertThat(create("/").getBaseName()).isEmpty()
    }

    @org.junit.Test
    fun testNormalStringsDoNotAllocate() {
        val normal1 = "/a/b/hello.txt"
        assertThat(create(normal1).getPathString()).isSameInstanceAs(normal1)

        // Check our testing strategy
        val notNormal = "/a/../b"
        assertThat(create(notNormal).getPathString()).isNotSameInstanceAs(notNormal)
    }

    @org.junit.Test
    fun testComparableSortOrder() {
        val list: MutableList<Path?> =
            com.google.common.collect.Lists.newArrayList<Path?>(
                create("/zzz"),
                create("/ZZZ"),
                create("/ABC"),
                create("/aBc"),
                create("/AbC"),
                create("/abc")
            )
        Collections.sort<T?>(list)
        val result: MutableList<String?> = list.stream().map<Any?>(Path::getPathString).collect(Collectors.toList())

        Truth.assertThat(result).containsExactly("/ABC", "/AbC", "/ZZZ", "/aBc", "/abc", "/zzz").inOrder()
    }

    protected fun create(path: String?): Path {
        return Path.create(path, fileSystem)
    }
}
