// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.decompressor

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.bazel.repository.RepositoryFunctionException
import com.google.devtools.build.lib.testutil.TestUtils
import com.google.devtools.build.lib.vfs.util.FileSystems
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Checks that Brotli compressed files can be decompressed.  */
@RunWith(JUnit4::class)
class BrFunctionTest {
    @Rule
    var name: TestName = TestName()

    @Test
    @Throws(IOException::class, InterruptedException::class, RepositoryFunctionException::class)
    fun decompressBrfile() {
        // Create an "archives" directory to hold compressed files and an "extracted" directory where
        // the extraction will occur.
        val tmpDir: String? = Paths.get(TestUtils.tmpDir()).resolve(name.getMethodName()).toString()
        Path.of(tmpDir).toFile().mkdirs()
        val archiveDir: File = Paths.get(tmpDir).resolve("archives").toFile()
        Truth.assertThat(archiveDir.mkdirs()).isTrue()
        val extractionDir: File = Paths.get(tmpDir).resolve("extracted").toFile()
        Truth.assertThat(extractionDir.mkdirs()).isTrue()

        // Write the example compressed brotli file to the archive directory.
        val os =
            Files.newOutputStream(Path.of(archiveDir.getPath()).resolve("file.br"))
        os.write(bazelBrBytes)
        os.close()

        // Decompress.
        val testFs: FileSystem = FileSystems.getNativeFileSystem()
        val descriptor: DecompressorDescriptor.Builder =
            DecompressorDescriptor.builder()
                .setDestinationPath(testFs.getPath(extractionDir.getCanonicalPath()))!!
                .setArchivePath(testFs.getPath(archiveDir.getCanonicalPath()).getRelative("file.br"))!!

        val fileDir: Path = decompress(descriptor.build())
        val files: ImmutableList<String?>? =
            fileDir.readdir(Symlinks.NOFOLLOW).stream().map(Dirent::getName)
                .collect(ImmutableList.toImmutableList<E?>())

        // The decompressed file with the correct name is there with the correct contents.
        Truth.assertThat(files).containsExactly("file")
        val pathFile: File = fileDir.getRelative("file").getPathFile()
        Truth.assertThat(Files.readString(pathFile.toPath())).contains("bazel")
    }

    companion object {
        /**
         * Byte array of a Brotli compressed file containing the word "bazel". You can generate the same
         * sequence of bytes with the following commands (sed part is linux-specific):
         * 
         * <pre>`$ echo "bazel" > file $ brotli file $ od -v -t d1 brotli.br | cut -c9- | sed 's/\([0-9]\+\)/\1,/g' `</pre>
         */
        private val bazelBrBytes = byteArrayOf(
            33, 20, 0, 4, 98, 97, 122, 101, 108, 10, 3,
        )
    }
}
