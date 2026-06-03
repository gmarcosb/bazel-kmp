// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote

import com.google.devtools.build.lib.actions.ActionInputMap

abstract class RemoteActionFileSystemTestBase {
    @Throws(IOException::class)
    protected abstract fun createActionFileSystem(
        inputs: ActionInputMap?, outputs: Iterable<Artifact?>?
    ): FileSystem

    @Throws(IOException::class)
    protected fun createActionFileSystem(inputs: ActionInputMap?): FileSystem {
        return createActionFileSystem(inputs, com.google.common.collect.ImmutableList.of<Artifact?>())
    }

    @Throws(IOException::class)
    protected fun createActionFileSystem(): FileSystem {
        return createActionFileSystem(ActionInputMap(0))
    }

    protected abstract fun getLocalFileSystem(actionFs: FileSystem?): FileSystem?

    protected abstract fun getRemoteFileSystem(actionFs: FileSystem?): FileSystem?

    protected abstract fun getOutputPath(outputRootRelativePath: String?): PathFragment

    @Throws(IOException::class)
    protected abstract fun writeLocalFile(actionFs: FileSystem?, path: PathFragment?, content: String?)

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(IOException::class)
    protected abstract fun injectRemoteFile(
        actionFs: FileSystem?, path: PathFragment?, content: String?
    ): FileArtifactValue?

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exists_fileDoesNotExist_returnsFalse() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        val exists: Boolean = actionFs.exists(path)

        Truth.assertThat(exists).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exists_localFile_returnsTrue() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        writeLocalFile(actionFs, path, "local contents")

        assertThat(actionFs.exists(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exists_remoteFile_returnsTrue() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        injectRemoteFile(actionFs, path, "remote contents")

        assertThat(actionFs.exists(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exists_localAndRemoteFile_returnsTrue() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        writeLocalFile(actionFs, path, "local contents")
        injectRemoteFile(actionFs, path, "remote contents")

        assertThat(actionFs.exists(path)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun delete_fileDoesNotExist_returnsFalse() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        val success: Boolean = actionFs.getPath(path).delete()

        Truth.assertThat(success).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun delete_localFile_succeeds() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        writeLocalFile(actionFs, path, "local contents")
        assertThat(getLocalFileSystem(actionFs).exists(path)).isTrue()

        val success: Boolean = actionFs.getPath(path).delete()

        Truth.assertThat(success).isTrue()
        assertThat(getLocalFileSystem(actionFs).exists(path)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun delete_remoteFile_succeeds() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote contents")
        assertThat(getRemoteFileSystem(actionFs).exists(path)).isTrue()

        val success: Boolean = actionFs.getPath(path).delete()

        Truth.assertThat(success).isTrue()
        assertThat(actionFs.exists(path)).isFalse()
        assertThat(getRemoteFileSystem(actionFs).exists(path)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun delete_localAndRemoteFile_succeeds() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        writeLocalFile(actionFs, path, "local contents")
        injectRemoteFile(actionFs, path, "remote contents")
        assertThat(getLocalFileSystem(actionFs).exists(path)).isTrue()
        assertThat(getRemoteFileSystem(actionFs).exists(path)).isTrue()

        val success: Boolean = actionFs.getPath(path).delete()

        Truth.assertThat(success).isTrue()
        assertThat(actionFs.exists(path)).isFalse()
        assertThat(getLocalFileSystem(actionFs).exists(path)).isFalse()
        assertThat(getRemoteFileSystem(actionFs).exists(path)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameTo_sourceFileDoesNotExist_throwError() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        val newPath: PathFragment = getOutputPath("file-new")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.renameTo(path, newPath) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameTo_targetDirectoryDoesNotExist_throwError() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        val newPath: PathFragment = getOutputPath("dir/file-new")
        writeLocalFile(actionFs, path, "local-content")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.renameTo(path, newPath) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameTo_onlyRemoteFile_renameRemoteFile() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")
        val newPath: PathFragment = getOutputPath("file-new")

        actionFs.renameTo(path, newPath)

        assertThat(actionFs.exists(path)).isFalse()
        assertThat(actionFs.exists(newPath)).isTrue()
        assertThat(getRemoteFileSystem(actionFs).exists(path)).isFalse()
        assertThat(getRemoteFileSystem(actionFs).exists(newPath)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun renameTo_localAndRemoteFile_renameBoth() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")
        writeLocalFile(actionFs, path, "local-content")
        val newPath: PathFragment = getOutputPath("file-new")

        actionFs.renameTo(path, newPath)

        assertThat(actionFs.exists(path)).isFalse()
        assertThat(actionFs.exists(newPath)).isTrue()
        assertThat(getRemoteFileSystem(actionFs).exists(path)).isFalse()
        assertThat(getRemoteFileSystem(actionFs).exists(newPath)).isTrue()
        assertThat(getLocalFileSystem(actionFs).exists(path)).isFalse()
        assertThat(getLocalFileSystem(actionFs).exists(newPath)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createDirectoryAndParents_createLocallyAndRemotely() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("dir")

        actionFs.createDirectoryAndParents(path)

        assertThat(getRemoteFileSystem(actionFs).getPath(path).isDirectory()).isTrue()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isDirectory()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun createDirectory_createLocallyAndRemotely() {
        val actionFs: FileSystem = createActionFileSystem()
        actionFs.createDirectoryAndParents(getOutputPath("parent"))
        val path: PathFragment = getOutputPath("parent/dir")

        actionFs.createDirectory(path)

        assertThat(getRemoteFileSystem(actionFs).getPath(path).isDirectory()).isTrue()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isDirectory()).isTrue()
    }

    internal interface DirectoryEntriesProvider {
        @Throws(IOException::class)
        fun getDirectoryEntries(path: Path?): com.google.common.collect.ImmutableList<String?>?
    }

    @Throws(IOException::class)
    private fun readdirNonEmptyLocalDirectoryReadFromLocal(
        directoryEntriesProvider: DirectoryEntriesProvider
    ) {
        val actionFs: FileSystem = createActionFileSystem()
        val dir: PathFragment = getOutputPath("parent/dir")
        actionFs.getPath(dir).createDirectoryAndParents()
        writeLocalFile(actionFs, dir.getChild("file1"), "content1")
        writeLocalFile(actionFs, dir.getChild("file2"), "content2")

        val entries: com.google.common.collect.ImmutableList<String?>? =
            directoryEntriesProvider.getDirectoryEntries(actionFs.getPath(dir))

        Truth.assertThat(entries).containsExactly("file1", "file2")
    }

    @Throws(IOException::class)
    private fun readdirNonEmptyInMemoryDirectoryReadFromMemory(
        directoryEntriesProvider: DirectoryEntriesProvider
    ) {
        val actionFs: FileSystem = createActionFileSystem()
        val dir: PathFragment = getOutputPath("parent/dir")
        actionFs.getPath(dir).createDirectoryAndParents()
        injectRemoteFile(actionFs, dir.getChild("file1"), "content1")
        injectRemoteFile(actionFs, dir.getChild("file2"), "content2")

        val entries: com.google.common.collect.ImmutableList<String?>? =
            directoryEntriesProvider.getDirectoryEntries(actionFs.getPath(dir))

        Truth.assertThat(entries).containsExactly("file1", "file2")
    }

    @Throws(IOException::class)
    private fun readdirNonEmptyLocalAndInMemoryDirectoryCombineThem(
        directoryEntriesProvider: DirectoryEntriesProvider
    ) {
        val actionFs: FileSystem = createActionFileSystem()
        val dir: PathFragment = getOutputPath("parent/dir")
        actionFs.getPath(dir).createDirectoryAndParents()
        writeLocalFile(actionFs, dir.getChild("file1"), "content1")
        writeLocalFile(actionFs, dir.getChild("file2"), "content2")
        injectRemoteFile(actionFs, dir.getChild("file2"), "content2inmemory")
        injectRemoteFile(actionFs, dir.getChild("file3"), "content3")

        val entries: com.google.common.collect.ImmutableList<String?>? =
            directoryEntriesProvider.getDirectoryEntries(actionFs.getPath(dir))

        Truth.assertThat(entries).containsExactly("file1", "file2", "file3")
    }

    @Throws(IOException::class)
    private fun readdirNothingThereThrowsFileNotFound(
        directoryEntriesProvider: DirectoryEntriesProvider
    ) {
        val actionFs: FileSystem = createActionFileSystem()
        val dir: PathFragment = getOutputPath("parent/dir")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { directoryEntriesProvider.getDirectoryEntries(actionFs.getPath(dir)) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun readdir_nonEmptyLocalDirectory_readFromLocal() {
        readdirNonEmptyLocalDirectoryReadFromLocal(
            DirectoryEntriesProvider { path: Path? ->
                path.readdir(Symlinks.FOLLOW).stream().map(Dirent::getName)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun readdir_nonEmptyInMemoryDirectory_readFromMemory() {
        readdirNonEmptyInMemoryDirectoryReadFromMemory(
            DirectoryEntriesProvider { path: Path? ->
                path.readdir(Symlinks.FOLLOW).stream().map(Dirent::getName)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun readdir_nonEmptyLocalAndInMemoryDirectory_combineThem() {
        readdirNonEmptyLocalAndInMemoryDirectoryCombineThem(
            DirectoryEntriesProvider { path: Path? ->
                path.readdir(Symlinks.FOLLOW).stream().map(Dirent::getName)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun readdir_nothingThere_throwsFileNotFound() {
        readdirNothingThereThrowsFileNotFound(
            DirectoryEntriesProvider { path: Path? ->
                path.readdir(Symlinks.FOLLOW).stream().map(Dirent::getName)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            })
    }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val directoryEntries_nonEmptyLocalDirectory_readFromLocal: Unit
        get() {
            readdirNonEmptyLocalDirectoryReadFromLocal(
                DirectoryEntriesProvider { path: Path? ->
                    path.getDirectoryEntries().stream().map(Path::getBaseName)
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                })
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val directoryEntries_nonEmptyInMemoryDirectory_readFromMemory: Unit
        get() {
            readdirNonEmptyInMemoryDirectoryReadFromMemory(
                DirectoryEntriesProvider { path: Path? ->
                    path.getDirectoryEntries().stream().map(Path::getBaseName)
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                })
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val directoryEntries_nonEmptyLocalAndInMemoryDirectory_combineThem: Unit
        get() {
            readdirNonEmptyLocalAndInMemoryDirectoryCombineThem(
                DirectoryEntriesProvider { path: Path? ->
                    path.getDirectoryEntries().stream().map(Path::getBaseName)
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                })
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val directoryEntries_nothingThere_throwsFileNotFound: Unit
        get() {
            readdirNothingThereThrowsFileNotFound(
                DirectoryEntriesProvider { path: Path? ->
                    path.getDirectoryEntries().stream().map(Path::getBaseName)
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                })
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isReadable_fileDoesNotExist_throwError: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")

            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { actionFs.getPath(path).isReadable() })
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isReadable_onlyRemoteFile_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")

            val readable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isReadable()

            assertThat(readable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isReadable_onlyRemoteDirectory_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("dir")
            getRemoteFileSystem(actionFs).createDirectoryAndParents(path)

            val readable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isReadable()

            assertThat(readable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isReadable_localReadableFile_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            writeLocalFile(actionFs, path, "local-content")

            val readable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isReadable()

            assertThat(readable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isReadable_localNonReadableFile_returnsFalse: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            writeLocalFile(actionFs, path, "local-content")
            getLocalFileSystem(actionFs).getPath(path).setReadable(false)

            val readable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isReadable()

            assertThat(readable).isFalse()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isReadable_localReadableFileAndRemoteFile_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")
            writeLocalFile(actionFs, path, "local-content")

            val readable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isReadable()

            assertThat(readable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isReadable_localNonReadableFileAndRemoteFile_returnsFalse: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")
            writeLocalFile(actionFs, path, "local-content")
            getLocalFileSystem(actionFs).getPath(path).setReadable(false)

            val readable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isReadable()

            assertThat(readable).isFalse()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isWritable_fileDoesNotExist_throwError: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")

            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { actionFs.getPath(path).isWritable() })
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isWritable_onlyRemoteFile_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")

            val writable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isWritable()

            assertThat(writable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isWritable_onlyRemoteDirectory_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("dir")
            getRemoteFileSystem(actionFs).createDirectoryAndParents(path)

            val writable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isWritable()

            assertThat(writable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isWritable_localWritableFile_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            writeLocalFile(actionFs, path, "local-content")

            val writable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isWritable()

            assertThat(writable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isWritable_localNonWritableFile_returnsFalse: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            writeLocalFile(actionFs, path, "local-content")
            getLocalFileSystem(actionFs).getPath(path).setWritable(false)

            val writable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isWritable()

            assertThat(writable).isFalse()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isWritable_localWritableFileAndRemoteFile_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")
            writeLocalFile(actionFs, path, "local-content")

            val writable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isWritable()

            assertThat(writable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isWritable_localNonWritableFileAndRemoteFile_returnsFalse: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")
            writeLocalFile(actionFs, path, "local-content")
            getLocalFileSystem(actionFs).getPath(path).setWritable(false)

            val writable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isWritable()

            assertThat(writable).isFalse()
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val isWritable_localNonWritableDirectoryAndRemoteDirectory_returnsFalse: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("dir")
            getRemoteFileSystem(actionFs).getPath(path).createDirectoryAndParents()
            getLocalFileSystem(actionFs).getPath(path).createDirectoryAndParents()
            getLocalFileSystem(actionFs).getPath(path).setWritable(false)

            val writable: Boolean = actionFs.getPath(path).isWritable()

            Truth.assertThat(writable).isFalse()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isExecutable_fileDoesNotExist_throwError: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")

            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { actionFs.getPath(path).isExecutable() })
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isExecutable_onlyRemoteFile_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")

            val executable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isExecutable()

            assertThat(executable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isExecutable_onlyRemoteDirecotry_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("dir")
            getRemoteFileSystem(actionFs).createDirectoryAndParents(path)

            val executable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isExecutable()

            assertThat(executable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isExecutable_localExecutableFile_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            writeLocalFile(actionFs, path, "local-content")
            getLocalFileSystem(actionFs).getPath(path).setExecutable(true)

            val executable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isExecutable()

            assertThat(executable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isExecutable_localNonExecutableFile_returnsFalse: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            writeLocalFile(actionFs, path, "local-content")

            val executable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isExecutable()

            assertThat(executable).isFalse()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isExecutable_localExecutableFileAndRemoteFile_returnsTrue: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")
            writeLocalFile(actionFs, path, "local-content")
            getLocalFileSystem(actionFs).getPath(path).setExecutable(true)

            val executable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isExecutable()

            assertThat(executable).isTrue()
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val isExecutable_localNonExecutableFileAndRemoteFile_returnsFalse: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")
            writeLocalFile(actionFs, path, "local-content")

            val executable: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).isExecutable()

            assertThat(executable).isFalse()
        }

    @org.junit.Test
    @Throws(IOException::class)
    fun setReadable_fileDoesNotExist_throwError() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.getPath(path).setReadable(false) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setReadable_onlyRemoteFile_remainsReadable() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")

        actionFs.getPath(path).setReadable(false)

        assertThat(actionFs.getPath(path).isReadable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setReadable_onlyRemoteDirecotry_remainsReadable() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("dir")
        getRemoteFileSystem(actionFs).createDirectoryAndParents(path)

        actionFs.getPath(path).setReadable(false)

        assertThat(actionFs.getPath(path).isReadable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setReadable_localFile_change() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(actionFs.getPath(path).isReadable()).isTrue()

        actionFs.getPath(path).setReadable(false)

        assertThat(actionFs.getPath(path).isReadable()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setReadable_localFileAndRemoteFile_changeLocal() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(actionFs.getPath(path).isReadable()).isTrue()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isReadable()).isTrue()

        actionFs.getPath(path).setReadable(false)

        assertThat(actionFs.getPath(path).isReadable()).isFalse()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isReadable()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setWritable_fileDoesNotExist_throwError() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.getPath(path).setWritable(false) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setWritable_onlyRemoteFile_remainsWritable() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")

        actionFs.getPath(path).setWritable(false)

        assertThat(actionFs.getPath(path).isWritable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setWritable_onlyRemoteDirecotry_remainsWritable() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("dir")
        getRemoteFileSystem(actionFs).createDirectoryAndParents(path)

        actionFs.getPath(path).setWritable(false)

        assertThat(actionFs.getPath(path).isWritable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setWritable_localFile_change() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(actionFs.getPath(path).isWritable()).isTrue()

        actionFs.getPath(path).setWritable(false)

        assertThat(actionFs.getPath(path).isWritable()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setWritable_localFileAndRemoteFile_changeLocal() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(actionFs.getPath(path).isWritable()).isTrue()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isWritable()).isTrue()

        actionFs.getPath(path).setWritable(false)

        assertThat(actionFs.getPath(path).isWritable()).isFalse()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isWritable()).isFalse()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setExecutable_fileDoesNotExist_throwError() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.getPath(path).setExecutable(false) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setExecutable_onlyRemoteFile_remainsExecutable() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")

        actionFs.getPath(path).setExecutable(false)

        assertThat(actionFs.getPath(path).isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setExecutable_onlyRemoteDirecotry_remainsExecutable() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("dir")
        getRemoteFileSystem(actionFs).createDirectoryAndParents(path)

        actionFs.getPath(path).setExecutable(false)

        assertThat(actionFs.getPath(path).isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setExecutable_localFile_change() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(actionFs.getPath(path).isExecutable()).isFalse()

        actionFs.getPath(path).setExecutable(true)

        assertThat(actionFs.getPath(path).isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setExecutable_localFileAndRemoteFile_changeLocal() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(actionFs.getPath(path).isExecutable()).isFalse()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isExecutable()).isFalse()

        actionFs.getPath(path).setExecutable(true)

        assertThat(actionFs.getPath(path).isExecutable()).isTrue()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chmod_fileDoesNotExist_throwError() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.getPath(path).chmod(0) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chmod_onlyRemoteFile_remainsSame() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")
        assertThat(actionFs.getPath(path).isReadable()).isTrue()
        assertThat(actionFs.getPath(path).isWritable()).isTrue()
        assertThat(actionFs.getPath(path).isExecutable()).isTrue()

        actionFs.getPath(path).chmod(0)

        assertThat(actionFs.getPath(path).isReadable()).isTrue()
        assertThat(actionFs.getPath(path).isWritable()).isTrue()
        assertThat(actionFs.getPath(path).isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chmod_onlyRemoteDirectory_remainsSame() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("dir")
        getRemoteFileSystem(actionFs).createDirectoryAndParents(path)
        assertThat(actionFs.getPath(path).isReadable()).isTrue()
        assertThat(actionFs.getPath(path).isWritable()).isTrue()
        assertThat(actionFs.getPath(path).isExecutable()).isTrue()

        actionFs.getPath(path).chmod(0)

        assertThat(actionFs.getPath(path).isReadable()).isTrue()
        assertThat(actionFs.getPath(path).isWritable()).isTrue()
        assertThat(actionFs.getPath(path).isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chmod_localFile_change() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(actionFs.getPath(path).isReadable()).isTrue()
        assertThat(actionFs.getPath(path).isWritable()).isTrue()
        assertThat(actionFs.getPath(path).isExecutable()).isFalse()

        actionFs.getPath(path).chmod(73)

        assertThat(actionFs.getPath(path).isReadable()).isFalse()
        assertThat(actionFs.getPath(path).isWritable()).isFalse()
        assertThat(actionFs.getPath(path).isExecutable()).isTrue()
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun chmod_localFileAndRemoteFile_changeLocal() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(actionFs.getPath(path).isReadable()).isTrue()
        assertThat(actionFs.getPath(path).isWritable()).isTrue()
        assertThat(actionFs.getPath(path).isExecutable()).isFalse()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isReadable()).isTrue()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isWritable()).isTrue()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isExecutable()).isFalse()

        actionFs.getPath(path).chmod(73)

        assertThat(actionFs.getPath(path).isReadable()).isFalse()
        assertThat(actionFs.getPath(path).isWritable()).isFalse()
        assertThat(actionFs.getPath(path).isExecutable()).isTrue()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isReadable()).isFalse()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isWritable()).isFalse()
        assertThat(getLocalFileSystem(actionFs).getPath(path).isExecutable()).isTrue()
    }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val lastModifiedTime_fileDoesNotExist_throwError: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")

            org.junit.Assert.assertThrows<FileNotFoundException?>(
                FileNotFoundException::class.java,
                org.junit.function.ThrowingRunnable { actionFs.getPath(path).getLastModifiedTime() })
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val lastModifiedTime_onlyRemoteFile_returnRemote: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")

            val mtime: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).getLastModifiedTime()

            assertThat(mtime).isEqualTo(getRemoteFileSystem(actionFs).getPath(path).getLastModifiedTime())
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val lastModifiedTime_onlyLocalFile_returnLocal: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            writeLocalFile(actionFs, path, "local-content")

            val mtime: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).getLastModifiedTime()

            assertThat(mtime).isEqualTo(getLocalFileSystem(actionFs).getPath(path).getLastModifiedTime())
        }

    @get:Throws(IOException::class)
    @get:org.junit.Test
    val lastModifiedTime_localAndRemoteFile_returnRemote: Unit
        get() {
            val actionFs: FileSystem = createActionFileSystem()
            val path: PathFragment = getOutputPath("file")
            injectRemoteFile(actionFs, path, "remote-content")
            writeLocalFile(actionFs, path, "local-content")

            val mtime: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                actionFs.getPath(path).getLastModifiedTime()

            assertThat(mtime).isEqualTo(getRemoteFileSystem(actionFs).getPath(path).getLastModifiedTime())
        }

    @org.junit.Test
    @Throws(IOException::class)
    fun setLastModifiedTime_fileDoesNotExist_throwError() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")

        org.junit.Assert.assertThrows<FileNotFoundException?>(
            FileNotFoundException::class.java,
            org.junit.function.ThrowingRunnable { actionFs.getPath(path).setLastModifiedTime(0) })
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setLastModifiedTime_onlyRemoteFile_successfullySet() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")
        assertThat(actionFs.getPath(path).getLastModifiedTime()).isNotEqualTo(0)

        actionFs.getPath(path).setLastModifiedTime(0)

        assertThat(actionFs.getPath(path).getLastModifiedTime()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setLastModifiedTime_onlyLocalFile_successfullySet() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(actionFs.getPath(path).getLastModifiedTime()).isNotEqualTo(0)

        actionFs.getPath(path).setLastModifiedTime(0)

        assertThat(actionFs.getPath(path).getLastModifiedTime()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun setLastModifiedTime_localAndRemoteFile_changeBoth() {
        val actionFs: FileSystem = createActionFileSystem()
        val path: PathFragment = getOutputPath("file")
        injectRemoteFile(actionFs, path, "remote-content")
        writeLocalFile(actionFs, path, "local-content")
        assertThat(getLocalFileSystem(actionFs).getPath(path).getLastModifiedTime()).isNotEqualTo(0)
        assertThat(getRemoteFileSystem(actionFs).getPath(path).getLastModifiedTime()).isNotEqualTo(0)

        actionFs.getPath(path).setLastModifiedTime(0)

        assertThat(getLocalFileSystem(actionFs).getPath(path).getLastModifiedTime()).isEqualTo(0)
        assertThat(getRemoteFileSystem(actionFs).getPath(path).getLastModifiedTime()).isEqualTo(0)
    }
}
