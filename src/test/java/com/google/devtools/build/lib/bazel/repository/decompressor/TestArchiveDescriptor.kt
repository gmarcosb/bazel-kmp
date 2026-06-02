// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.decompressor

import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.vfs.FileSystem
import com.google.devtools.build.lib.vfs.util.FileSystems
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Helper class for working with test archive file.
 * 
 * 
 * The archive has the following structure
 * 
 * 
 * root_folder/ another_folder/ regularFile hardLinkFile hardlink to
 * root_folder/another_folder/regularFile relativeSymbolicLinkFile -> regularFile
 * absoluteSymbolicLinkFile -> /root_folder/another_folder/regularFile
 */
class TestArchiveDescriptor internal constructor(
    private val archiveName: String?,
    private val outDirName: String?,
    private val withHardLinks: Boolean
) {
    @Throws(IOException::class)
    fun createDescriptorBuilder(): DecompressorDescriptor.Builder {
        val testFs: FileSystem = FileSystems.getNativeFileSystem()

        // do not rely on TestConstants.JAVATESTS_ROOT end with slash, but ensure separators
        // are not duplicated
        val path: String =
            (TestConstants.JAVATESTS_ROOT + PATH_TO_TEST_ARCHIVE + archiveName).replace("//", "/")
        val tarballPath: Path? = testFs.getPath(Runfiles.preload().withSourceRepository("").rlocation(path))

        val workingDir: Path = testFs.getPath(File(TestUtils.tmpDir()).getCanonicalPath())
        val outDir: Path? = workingDir.getRelative(outDirName)

        return DecompressorDescriptor.builder().setDestinationPath(outDir)!!.setArchivePath(tarballPath)!!
    }

    /** Validate the content of the output directory  */
    @Throws(Exception::class)
    fun assertOutputFiles(rootOutputDir: Path, vararg relativePath: String?) {
        assertThat(rootOutputDir.asFragment().endsWith(PathFragment.create(outDirName))).isTrue()
        var outputDir: Path = rootOutputDir
        for (part in relativePath) {
            outputDir = outputDir.getRelative(part)
        }

        assertThat(outputDir.exists()).isTrue()
        assertThat(outputDir.getRelative(REGULAR_FILE_NAME).exists()).isTrue()
        assertThat(outputDir.getRelative(REGULAR_FILE_NAME).getFileSize()).isNotEqualTo(0)
        assertThat(outputDir.getRelative(REGULAR_FILE_NAME).isSymbolicLink()).isFalse()
        assertThat(outputDir.getRelative(RELATIVE_SYMBOLIC_LINK_FILE_NAME).exists()).isTrue()
        assertThat(outputDir.getRelative(RELATIVE_SYMBOLIC_LINK_FILE_NAME).getFileSize())
            .isNotEqualTo(0)
        assertThat(outputDir.getRelative(RELATIVE_SYMBOLIC_LINK_FILE_NAME).isSymbolicLink()).isTrue()
        assertThat(outputDir.getRelative(ABSOLUTE_SYMBOLIC_LINK_FILE_NAME).exists()).isTrue()
        assertThat(outputDir.getRelative(ABSOLUTE_SYMBOLIC_LINK_FILE_NAME).getFileSize())
            .isNotEqualTo(0)
        assertThat(outputDir.getRelative(ABSOLUTE_SYMBOLIC_LINK_FILE_NAME).isSymbolicLink()).isTrue()

        if (withHardLinks) {
            assertThat(outputDir.getRelative(HARD_LINK_FILE_NAME).exists()).isTrue()
            assertThat(outputDir.getRelative(HARD_LINK_FILE_NAME).getFileSize()).isNotEqualTo(0)
            assertThat(outputDir.getRelative(HARD_LINK_FILE_NAME).isSymbolicLink()).isFalse()
            Truth.assertThat(
                Files.isSameFile(
                    Paths.get(outputDir.getRelative(REGULAR_FILE_NAME).toString()),
                    Paths.get(outputDir.getRelative(HARD_LINK_FILE_NAME).toString())
                )
            )
                .isTrue()
        }
        Truth.assertThat(
            Files.isSameFile(
                Paths.get(outputDir.getRelative(REGULAR_FILE_NAME).toString()),
                Paths.get(
                    outputDir.getRelative(RELATIVE_SYMBOLIC_LINK_FILE_NAME).toString()
                )
            )
        )
            .isTrue()
        Truth.assertThat(
            Files.isSameFile(
                Paths.get(outputDir.getRelative(REGULAR_FILE_NAME).toString()),
                Paths.get(
                    outputDir.getRelative(ABSOLUTE_SYMBOLIC_LINK_FILE_NAME).toString()
                )
            )
        )
            .isTrue()
    }

    companion object {
        /* Regular file */
        private const val REGULAR_FILE_NAME = "regularFile"

        /* Hard link file, created by ln <REGULAR_FILE_NAME> <HARD_LINK_FILE_NAME> */
        private const val HARD_LINK_FILE_NAME = "hardLinkFile"

        /* Symbolic(Soft) link file, created by ln -s <REGULAR_FILE_NAME> <SYMBOLIC_LINK_FILE_NAME> */
        private const val RELATIVE_SYMBOLIC_LINK_FILE_NAME = "relativeSymbolicLinkFile"
        private const val ABSOLUTE_SYMBOLIC_LINK_FILE_NAME = "absoluteSymbolicLinkFile"
        private const val PATH_TO_TEST_ARCHIVE = "/com/google/devtools/build/lib/bazel/repository/decompressor/"

        const val ROOT_FOLDER_NAME: String = "root_folder"
        const val INNER_FOLDER_NAME: String = "another_folder"
    }
}
