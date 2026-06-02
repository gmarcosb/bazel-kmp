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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.LocationExpander.LocationFunction

/** Unit tests for [LocationExpander.LocationFunction].  */
@RunWith(JUnit4::class)
class LocationFunctionTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun absoluteAndRelativeLabels() {
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", false).add("//foo", "/exec/src/bar").build()
        assertThat(func.apply("//foo", RepositoryMapping.EMPTY, null)).isEqualTo("src/bar")
        assertThat(func.apply(":foo", RepositoryMapping.EMPTY, null)).isEqualTo("src/bar")
        assertThat(func.apply("foo", RepositoryMapping.EMPTY, null)).isEqualTo("src/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathUnderExecRootUsesDotSlash() {
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", false).add("//foo", "/exec/bar").build()
        assertThat(func.apply("//foo", RepositoryMapping.EMPTY, null)).isEqualTo("./bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noSuchLabel() {
        val func: LocationFunction = LocationFunctionBuilder("//foo", false).build()
        val expected: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { func.apply("//bar", RepositoryMapping.EMPTY, null) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                "label '//bar:bar' in $(location) expression is not a declared prerequisite of this "
                        + "rule"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyList() {
        val func: LocationFunction = LocationFunctionBuilder("//foo", false).add("//foo").build()
        val expected: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { func.apply("//foo", RepositoryMapping.EMPTY, null) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .isEqualTo("label '//foo:foo' in $(location) expression expands to no files")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tooMany() {
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", false).add("//foo", "/exec/1", "/exec/2").build()
        val expected: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { func.apply("//foo", RepositoryMapping.EMPTY, null) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                ("label '//foo:foo' in $(location) expression expands to more than one file, "
                        + "please use $(locations //foo:foo) instead.  Files (at most 5 shown) are: "
                        + "[./1, ./2]")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noSuchLabelMultiple() {
        val func: LocationFunction = LocationFunctionBuilder("//foo", true).build()
        val expected: java.lang.IllegalStateException? =
            org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
                java.lang.IllegalStateException::class.java,
                org.junit.function.ThrowingRunnable { func.apply("//bar", RepositoryMapping.EMPTY, null) })
        Truth.assertThat(expected)
            .hasMessageThat()
            .isEqualTo(
                "label '//bar:bar' in $(locations) expression is not a declared prerequisite of this "
                        + "rule"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fileWithSpace() {
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", false).add("//foo", "/exec/file/with space").build()
        assertThat(func.apply("//foo", RepositoryMapping.EMPTY, null)).isEqualTo("'file/with space'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleFiles() {
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", true)
                .add("//foo", "/exec/foo/bar", "/exec/out/foo/foobar")
                .build()
        assertThat(func.apply("//foo", RepositoryMapping.EMPTY, null)).isEqualTo("foo/bar foo/foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filesWithSpace() {
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", true)
                .add("//foo", "/exec/file/with space", "/exec/file/with spaces ")
                .build()
        assertThat(func.apply("//foo", RepositoryMapping.EMPTY, null))
            .isEqualTo("'file/with space' 'file/with spaces '")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execPath() {
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", true)
                .setPathType(LocationFunction.PathType.EXEC)
                .add("//foo", "/exec/bar", "/exec/out/foobar")
                .build()
        assertThat(func.apply("//foo", RepositoryMapping.EMPTY, null)).isEqualTo("./bar out/foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun rlocationPath() {
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", true)
                .setPathType(LocationFunction.PathType.RLOCATION)
                .add("//foo", "/exec/bar", "/exec/out/foobar")
                .build()
        assertThat(func.apply("//foo", RepositoryMapping.EMPTY, "workspace"))
            .isEqualTo("workspace/bar workspace/foobar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun locationFunctionWithMappingReplace() {
        val b: RepositoryName = RepositoryName.create("b")
        val repositoryMapping: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(com.google.common.collect.ImmutableMap.of<K?, V?>("a", b), RepositoryName.MAIN)
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", false).add("@b//foo", "/exec/src/bar").build()
        assertThat(func.apply("@a//foo", repositoryMapping, null)).isEqualTo("src/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun locationFunctionWithMappingIgnoreRepo() {
        val b: RepositoryName = RepositoryName.create("b")
        val repositoryMapping: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RepositoryMapping.create(com.google.common.collect.ImmutableMap.of<K?, V?>("a", b), RepositoryName.MAIN)
        val func: LocationFunction =
            LocationFunctionBuilder("//foo", false).add("@@potato//foo", "/exec/src/bar").build()
        assertThat(func.apply("@@potato//foo", repositoryMapping, null)).isEqualTo("src/bar")
    }
}
