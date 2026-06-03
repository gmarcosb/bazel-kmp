// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile

/** Tests for [LocalFilesArtifactUploader]  */
@RunWith(JUnit4::class)
class LocalFilesArtifactUploaderTest {
    private val fileSystem: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val artifactUploader: LocalFilesArtifactUploader = LocalFilesArtifactUploader()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFile() {
        val file: Path = fileSystem.getPath("/test")
        // No need to create the file since LocalFileType.OUTPUT_FILE skips the filesystem check.
        val future: com.google.common.util.concurrent.ListenableFuture<PathConverter> =
            artifactUploader.upload(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    file,
                    LocalFile(file, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)
                )
            )
        val pathConverter: PathConverter = future.get()
        assertThat(pathConverter.apply(file)).isEqualTo("file:///test")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectory_notUploaded() {
        val file: Path = fileSystem.getPath("/test")
        // No need to create the file since LocalFileType.OUTPUT_DIRECTORY skips the filesystem check.
        val future: com.google.common.util.concurrent.ListenableFuture<PathConverter> =
            artifactUploader.upload(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    file,
                    LocalFile(file, LocalFileType.OUTPUT_DIRECTORY,  /* artifactMetadata= */null)
                )
            )
        val pathConverter: PathConverter = future.get()
        assertThat(pathConverter.apply(file)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymlink_notUploaded() {
        val file: Path = fileSystem.getPath("/test")
        // No need to create the file since LocalFileType.OUTPUT_FILE skips the filesystem check.
        val future: com.google.common.util.concurrent.ListenableFuture<PathConverter> =
            artifactUploader.upload(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    file,
                    LocalFile(file, LocalFileType.OUTPUT_SYMLINK,  /* artifactMetadata= */null)
                )
            )
        val pathConverter: PathConverter = future.get()
        assertThat(pathConverter.apply(file)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnknown_uploadedIfFile() {
        val file: Path = fileSystem.getPath("/test")
        val future: com.google.common.util.concurrent.ListenableFuture<PathConverter> =
            artifactUploader.upload(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    file,
                    LocalFile(file, LocalFileType.OUTPUT_FILE,  /* artifactMetadata= */null)
                )
            )
        val pathConverter: PathConverter = future.get()
        assertThat(pathConverter.apply(file)).isEqualTo("file:///test")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnknown_notUploadedIfDirectory() {
        val dir: Path = fileSystem.getPath("/test")
        val future: com.google.common.util.concurrent.ListenableFuture<PathConverter> =
            artifactUploader.upload(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    dir,
                    LocalFile(dir, LocalFileType.OUTPUT_DIRECTORY,  /* artifactMetadata= */null)
                )
            )
        val pathConverter: PathConverter = future.get()
        assertThat(pathConverter.apply(dir)).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnknown_notUploadedIfSymlink() {
        val dir: Path = fileSystem.getPath("/test")
        val future: com.google.common.util.concurrent.ListenableFuture<PathConverter> =
            artifactUploader.upload(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    dir,
                    LocalFile(dir, LocalFileType.OUTPUT_SYMLINK,  /* artifactMetadata= */null)
                )
            )
        val pathConverter: PathConverter = future.get()
        assertThat(pathConverter.apply(dir)).isNull()
    }
}
