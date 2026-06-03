// Copyright 2022 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.runtime.CommandLinePathFactory.CommandLinePathFactoryException

/** Tests for [CommandLinePathFactory].  */
@RunWith(JUnit4::class)
class CommandLinePathFactoryTest {
    private var filesystem: FileSystem? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun prepareFilesystem() {
        filesystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    }

    @Throws(java.lang.Exception::class)
    private fun createExecutable(path: String?) {
        com.google.common.base.Preconditions.checkNotNull<String?>(path)

        createExecutable(filesystem.getPath(path))
    }

    @Throws(java.lang.Exception::class)
    private fun createExecutable(path: Path?) {
        com.google.common.base.Preconditions.checkNotNull<Any?>(path)

        path.getParentDirectory().createDirectoryAndParents()
        path.getOutputStream().use { stream -> }
        path.setExecutable(true)
    }

    @org.junit.Test
    fun emptyPathIsRejected() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>())

        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    ""
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createFromAbsolutePath() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>())

        assertThat(factory.create(com.google.common.collect.ImmutableMap.of<K?, V?>(), "/absolute/path/1"))
            .isEqualTo(filesystem.getPath("/absolute/path/1"))
        assertThat(factory.create(com.google.common.collect.ImmutableMap.of<K?, V?>(), "/absolute/path/2"))
            .isEqualTo(filesystem.getPath("/absolute/path/2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createWithNamedRoot() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(
                filesystem,
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "workspace", filesystem.getPath("/path/to/workspace"),
                    "output_base", filesystem.getPath("/path/to/output/base")
                )
            )

        assertThat(factory.create(com.google.common.collect.ImmutableMap.of<K?, V?>(), "/absolute/path/1"))
            .isEqualTo(filesystem.getPath("/absolute/path/1"))
        assertThat(factory.create(com.google.common.collect.ImmutableMap.of<K?, V?>(), "/absolute/path/2"))
            .isEqualTo(filesystem.getPath("/absolute/path/2"))

        assertThat(factory.create(com.google.common.collect.ImmutableMap.of<K?, V?>(), "%workspace%/foo"))
            .isEqualTo(filesystem.getPath("/path/to/workspace/foo"))
        assertThat(factory.create(com.google.common.collect.ImmutableMap.of<K?, V?>(), "%workspace%/foo/bar"))
            .isEqualTo(filesystem.getPath("/path/to/workspace/foo/bar"))

        assertThat(factory.create(com.google.common.collect.ImmutableMap.of<K?, V?>(), "%output_base%/foo"))
            .isEqualTo(filesystem.getPath("/path/to/output/base/foo"))
        assertThat(factory.create(com.google.common.collect.ImmutableMap.of<K?, V?>(), "%output_base%/foo/bar"))
            .isEqualTo(filesystem.getPath("/path/to/output/base/foo/bar"))

        assertThat(factory.create(com.google.common.collect.ImmutableMap.of<K?, V?>(), "%workspace%//foo//bar"))
            .isEqualTo(filesystem.getPath("/path/to/workspace/foo/bar"))
    }

    @org.junit.Test
    fun pathLeakingOutsideOfRoot() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(
                filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>("a", filesystem.getPath("/path/to/a"))
            )

        org.junit.Assert.assertThrows<T?>(
            CommandLinePathFactoryException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    "%a%/../foo"
                )
            })
        org.junit.Assert.assertThrows<T?>(
            CommandLinePathFactoryException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    "%a%/b/../.."
                )
            })
    }

    @org.junit.Test
    fun unknownRoot() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(
                filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>("a", filesystem.getPath("/path/to/a"))
            )

        org.junit.Assert.assertThrows<T?>(
            CommandLinePathFactoryException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    "%workspace%/foo"
                )
            })
        org.junit.Assert.assertThrows<T?>(
            CommandLinePathFactoryException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    "%output_base%/foo"
                )
            })
    }

    @org.junit.Test
    fun relativePathWithMultipleSegments() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>())

        org.junit.Assert.assertThrows<T?>(
            CommandLinePathFactoryException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    "a/b"
                )
            })
        org.junit.Assert.assertThrows<T?>(
            CommandLinePathFactoryException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    "a/b/c/d"
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathLookup() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>())

        createExecutable("/bin/true")
        createExecutable("/bin/false")
        createExecutable("/usr/bin/foo-bar.exe")
        createExecutable("/usr/local/bin/baz")
        createExecutable("/home/yannic/bin/abc")
        createExecutable("/home/yannic/bin/true")

        val path: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "PATH", PATH_JOINER.join("/bin", "/usr/bin", "/usr/local/bin", "/home/yannic/bin")
            )
        assertThat(factory.create(path, "true")).isEqualTo(filesystem.getPath("/bin/true"))
        assertThat(factory.create(path, "false")).isEqualTo(filesystem.getPath("/bin/false"))
        assertThat(factory.create(path, "foo-bar.exe"))
            .isEqualTo(filesystem.getPath("/usr/bin/foo-bar.exe"))
        assertThat(factory.create(path, "baz")).isEqualTo(filesystem.getPath("/usr/local/bin/baz"))
        assertThat(factory.create(path, "abc")).isEqualTo(filesystem.getPath("/home/yannic/bin/abc"))

        // `.exe` is required.
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { factory.create(path, "foo-bar") })
    }

    @org.junit.Test
    fun pathLookupWithUndefinedPath() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>())

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    "a"
                )
            })
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(),
                    "foo"
                )
            })
    }

    @org.junit.Test
    fun pathLookupWithNonExistingDirectoryOnPath() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>())

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "PATH",
                        "/does/not/exist"
                    ), "a"
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathLookupWithExistingAndNonExistingDirectoryOnPath() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>())

        createExecutable("/bin/foo")
        createExecutable("/usr/bin/bar")
        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable {
                factory.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "PATH",
                        PATH_JOINER.join("/bin", "/does/not/exist", "/usr/bin")
                    ),
                    "a"
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pathLookupWithInvalidPath() {
        val factory: CommandLinePathFactory =
            CommandLinePathFactory(filesystem, com.google.common.collect.ImmutableMap.of<K?, V?>())

        createExecutable("/bin/true")
        val path: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>("PATH", PATH_JOINER.join("", ".", "/bin"))
        assertThat(factory.create(path, "true")).isEqualTo(filesystem.getPath("/bin/true"))
    }

    companion object {
        private val PATH_JOINER: com.google.common.base.Joiner =
            com.google.common.base.Joiner.on(java.io.File.pathSeparator)
    }
}
