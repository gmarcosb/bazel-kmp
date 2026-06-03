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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.util.FileType.HasFileType

/** Test for [FileType] and [FileTypeSet].  */
@RunWith(JUnit4::class)
class FileTypeTest {
    private class HasFileTypeImpl(private val path: String?) : HasFileType {
        public override fun filePathForFileTypeMatcher(): String? {
            return path
        }

        override fun toString(): String {
            return path!!
        }
    }

    @org.junit.Test
    fun simpleDotMatch() {
        assertThat(TEXT.matches("readme.txt")).isTrue()
    }

    @org.junit.Test
    fun doubleDotMatches() {
        assertThat(TEXT.matches("read.me.txt")).isTrue()
    }

    @org.junit.Test
    fun noExtensionMatches() {
        assertThat(FileType.NO_EXTENSION.matches("hello")).isTrue()
        assertThat(FileType.NO_EXTENSION.matches("/path/to/hello")).isTrue()
    }

    @org.junit.Test
    fun picksLastExtension() {
        assertThat(TEXT.matches("server.cfg.txt")).isTrue()
    }

    @org.junit.Test
    fun onlyExtensionStillMatches() {
        assertThat(TEXT.matches(".txt")).isTrue()
        assertTrueOnWindows(TEXT.matches(".TXT"))
    }

    @org.junit.Test
    fun handlesPathObjects() {
        val readme: Path? = InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/readme.txt")
        val readmeUppercase: Path? = InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/readme.TXT")

        assertThat(TEXT.matches(readme)).isTrue()
        assertTrueOnWindows(TEXT.matches(readmeUppercase))
    }

    @org.junit.Test
    fun handlesPathFragmentObjects() {
        val readme: PathFragment? = PathFragment.create("some/where/readme.txt")
        val readmeUppercase: PathFragment? = PathFragment.create("some/where/readme.TXT")

        assertThat(TEXT.matches(readme)).isTrue()
        assertTrueOnWindows(TEXT.matches(readmeUppercase))
    }

    @org.junit.Test
    fun fileTypeSetContains() {
        val allowedTypes: FileTypeSet = FileTypeSet.of(TEXT, HTML)

        assertThat(allowedTypes.matches("readme.txt")).isTrue()
        assertThat(allowedTypes.matches("style.css")).isFalse()
        assertTrueOnWindows(allowedTypes.matches("readme.TXT"))
    }

    private val artifacts: MutableList<HasFileType>
        get() = com.google.common.collect.Lists.newArrayList<E?>(
            HasFileTypeImpl("Foo.java"),
            HasFileTypeImpl("bar.cc"),
            HasFileTypeImpl("baz.py"),
            HasFileTypeImpl("Foobar.CC")
        )

    private fun filterAll(vararg fileTypes: FileType?): String {
        return com.google.common.base.Joiner.on(" ").join(FileType.filter(this.artifacts, fileTypes))
    }

    @org.junit.Test
    fun justJava() {
        Truth.assertThat(filterAll(JAVA_SOURCE)).isEqualTo("Foo.java")
    }

    @org.junit.Test
    fun javaAndCpp() {
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            Truth.assertThat(filterAll(JAVA_SOURCE, CPP_SOURCE)).isEqualTo("Foo.java bar.cc Foobar.CC")
        } else {
            Truth.assertThat(filterAll(JAVA_SOURCE, CPP_SOURCE)).isEqualTo("Foo.java bar.cc")
        }
    }

    @org.junit.Test
    fun allThree() {
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            Truth.assertThat(filterAll(JAVA_SOURCE, CPP_SOURCE, PYTHON_SOURCE))
                .isEqualTo("Foo.java bar.cc baz.py Foobar.CC")
        } else {
            Truth.assertThat(filterAll(JAVA_SOURCE, CPP_SOURCE, PYTHON_SOURCE))
                .isEqualTo("Foo.java bar.cc baz.py")
        }
    }

    private fun filename(name: String?): HasFileType {
        return HasFileType { name }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkingSingleWithTypePredicate() {
        val item: HasFileType = filename("config.txt")
        val itemUppercase: HasFileType = filename("config.TXT")

        assertThat(FileType.contains(item, TEXT)).isTrue()
        assertThat(FileType.contains(item, CFG)).isFalse()
        assertTrueOnWindows(FileType.contains(itemUppercase, TEXT))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkingListWithTypePredicate() {
        val unfiltered: com.google.common.collect.ImmutableList<HasFileType?> =
            com.google.common.collect.ImmutableList.of<HasFileType?>(
                filename("config.txt"),
                filename("index.HTML"),
                filename("README.txt")
            )

        assertThat(FileType.contains(unfiltered, TEXT)).isTrue()
        assertThat(FileType.contains(unfiltered, CFG)).isFalse()
        assertTrueOnWindows(FileType.contains(unfiltered, HTML))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filteringWithTypePredicate() {
        val unfiltered: com.google.common.collect.ImmutableList<HasFileType?> =
            com.google.common.collect.ImmutableList.of<HasFileType?>(
                filename("config.txt"),
                filename("index.html"),
                filename("README.txt"),
                filename("archive.zip"),
                filename("INFO.TXT")
            )

        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            assertThat(FileType.filter(unfiltered, TEXT))
                .containsExactly(unfiltered.get(0), unfiltered.get(2), unfiltered.get(4))
                .inOrder()
        } else {
            assertThat(FileType.filter(unfiltered, TEXT))
                .containsExactly(unfiltered.get(0), unfiltered.get(2))
                .inOrder()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filteringWithMatcherPredicate() {
        val unfiltered: com.google.common.collect.ImmutableList<HasFileType?> =
            com.google.common.collect.ImmutableList.of<HasFileType?>(
                filename("config.txt"),
                filename("index.html"),
                filename("README.txt"),
                filename("archive.zip"),
                filename("INFO.TXT")
            )

        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            assertThat(FileType.filter(unfiltered, TEXT::matches))
                .containsExactly(unfiltered.get(0), unfiltered.get(2), unfiltered.get(4))
                .inOrder()
        } else {
            assertThat(FileType.filter(unfiltered, TEXT::matches))
                .containsExactly(unfiltered.get(0), unfiltered.get(2))
                .inOrder()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filteringWithAlwaysFalse() {
        val unfiltered: com.google.common.collect.ImmutableList<HasFileType?> =
            com.google.common.collect.ImmutableList.of<HasFileType?>(
                filename("config.txt"),
                filename("index.html"),
                filename("binary"),
                filename("archive.zip"),
                filename("INFO.TXT")
            )

        assertThat(FileType.filter(unfiltered, FileTypeSet.NO_FILE)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filteringWithAlwaysTrue() {
        val unfiltered: com.google.common.collect.ImmutableList<HasFileType?> =
            com.google.common.collect.ImmutableList.of<HasFileType?>(
                filename("config.txt"),
                filename("index.html"),
                filename("binary"),
                filename("archive.zip"),
                filename("INFO.TXT")
            )

        assertThat(FileType.filter(unfiltered, FileTypeSet.ANY_FILE))
            .containsExactly(
                unfiltered.get(0),
                unfiltered.get(1),
                unfiltered.get(2),
                unfiltered.get(3),
                unfiltered.get(4)
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exclusionWithTypePredicate() {
        val unfiltered: com.google.common.collect.ImmutableList<HasFileType?> =
            com.google.common.collect.ImmutableList.of<HasFileType?>(
                filename("config.txt"),
                filename("index.html"),
                filename("README.txt"),
                filename("server.cfg"),
                filename("INFO.TXT")
            )

        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            assertThat(FileType.except(unfiltered, TEXT))
                .containsExactly(unfiltered.get(1), unfiltered.get(3))
                .inOrder()
        } else {
            assertThat(FileType.except(unfiltered, TEXT))
                .containsExactly(unfiltered.get(1), unfiltered.get(3), unfiltered.get(4))
                .inOrder()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun listFiltering() {
        val unfiltered: com.google.common.collect.ImmutableList<HasFileType?> =
            com.google.common.collect.ImmutableList.of<HasFileType?>(
                filename("config.txt"),
                filename("index.html"),
                filename("README.txt"),
                filename("server.cfg"),
                filename("CLIENT.CFG")
            )
        val filter: FileTypeSet? = FileTypeSet.of(HTML, CFG)

        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            assertThat(FileType.filterList(unfiltered, filter))
                .containsExactly(unfiltered.get(1), unfiltered.get(3), unfiltered.get(4))
                .inOrder()
        } else {
            assertThat(FileType.filterList(unfiltered, filter))
                .containsExactly(unfiltered.get(1), unfiltered.get(3))
                .inOrder()
        }
    }

    companion object {
        private val CFG: FileType? = FileType.of(".cfg")
        private val HTML: FileType? = FileType.of(".html")
        private val TEXT: FileType = FileType.of(".txt")
        private val CPP_SOURCE: FileType? = FileType.of(".cc", ".cpp", ".cxx", ".C")
        private val JAVA_SOURCE: FileType? = FileType.of(".java")
        private val PYTHON_SOURCE: FileType? = FileType.of(".py")

        private fun assertTrueOnWindows(condition: Boolean) {
            if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
                Truth.assertThat(condition).isTrue()
            } else {
                Truth.assertThat(condition).isFalse()
            }
        }
    }
}
