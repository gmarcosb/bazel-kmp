// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.bazel.repository.RepositoryFunctionException
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.lang.String
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.*
import kotlin.Exception
import kotlin.Int

/** Tests for [DecompressorValue].  */
@RunWith(JUnit4::class)
class DecompressorValueTest {
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

    @Test
    @Throws(Exception::class)
    fun testKnownFileExtensionsDoNotThrow() {
        var path: Path? = fs.getPath("/foo/.external-repositories/some-repo/bar.zip")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(ZipDecompressor::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.jar")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(ZipDecompressor::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.zip")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(ZipDecompressor::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.nupkg")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(ZipDecompressor::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.whl")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(ZipDecompressor::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.tar.gz")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(TarGzFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.tgz")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(TarGzFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.gz")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(GzFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.tar.xz")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(TarXzFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.txz")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(TarXzFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.xz")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(XzFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.tar.zst")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(TarZstFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.tzst")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(TarZstFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.zst")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(ZstFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.tar.bz2")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(TarBz2Function::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.tbz")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(TarBz2Function::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.bz2")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(Bz2Function::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.ar")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(ArFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.deb")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(ArFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.7z")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(SevenZDecompressor::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.br")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(BrFunction::class.java)
        path = fs.getPath("/foo/.external-repositories/some-repo/bar.baz.tar.br")
        assertThat(DecompressorValue.getDecompressor(path)).isInstanceOf(TarBrFunction::class.java)
    }

    @Test
    @Throws(Exception::class)
    fun testUnknownFileExtensionsThrow() {
        val zipPath: Path? = fs.getPath("/foo/.external-repositories/some-repo/bar.baz")
        val expected: RepositoryFunctionException? =
            Assert.assertThrows<T?>(
                RepositoryFunctionException::class.java,
                ThrowingRunnable { DecompressorValue.getDecompressor(zipPath) })
        assertThat(expected).hasMessageThat().contains("Expected a file with a .zip, .jar,")
    }

    @Test
    @Throws(IOException::class)
    fun httpBzlDocumentation() {
        // This test is specific to the Bazel runfiles structure and the open-source http.bzl.
        // Skip this test when running under Google's internal Blaze.
        Assume.assumeFalse(
            "Skipping httpBzlDocumentation test in Blaze environment.", TestConstants.PRODUCT_NAME == "blaze"
        )

        val filePath: String? = Runfiles.create().rlocation("_main/tools/build_defs/repo/http.bzl")
        val contents = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8)

        // Find where the archive formats variable is initialized and parse out.
        val startVarNameIndex: Int = contents.indexOf("SUPPORTED_ARCHIVE_FORMATS =")
        val startBracket: Int = contents.indexOf("[", startVarNameIndex)
        val endBracket: Int = contents.indexOf("]", startBracket)
        val formats: String = contents.substring(startBracket + 1, endBracket)
        val observedExtensions =
            Arrays.stream<String?>(formats.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray())
                .map<String?> { obj: String? -> obj.trim() }
                .filter { s: String? -> s.contains("\"") }
                .map<String?> { s: String? -> s.substring(1, s!!.length - 1) }
                .toList()

        val expectedExtensions =
            DecompressorValue.allSupportedExtensions( /* prefix= */"",  /* suffix= */"")
        if (expectedExtensions != observedExtensions) {
            val copyPasteCode =
                ("SUPPORTED_ARCHIVE_FORMATS = [\n"
                        + String.join(
                    "\n",
                    DecompressorValue.allSupportedExtensions( /* prefix= */
                        "    \"",  /* suffix= */"\","
                    )
                )
                        + "\n]")

            Assert.fail(
                kotlin.String.format(
                    """
              Supported archive formats list is out-dated.

              Expected:
              ${'\t'}%1${'$'}s
              Got:
              ${'\t'}%2${'$'}s

              Copy-paste string to replace in http.bzl:

              %3${'$'}s
              
              """.trimIndent(),
                    expectedExtensions, observedExtensions, copyPasteCode
                )
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun getDecompressorByType() {
        var decompressor: DecompressorValue.Decompressor? = DecompressorValue.getDecompressor("zip")
        Truth.assertThat(decompressor).isInstanceOf(ZipDecompressor::class.java)

        decompressor = DecompressorValue.getDecompressor("deb")
        Truth.assertThat(decompressor).isInstanceOf(ArFunction::class.java)

        val expected: RepositoryFunctionException? =
            Assert.assertThrows<T?>(
                RepositoryFunctionException::class.java, ThrowingRunnable { DecompressorValue.getDecompressor("baz") })
        assertThat(expected).hasMessageThat().contains("No decompressor found for type baz")
        assertThat(expected).hasMessageThat().contains("Available types are: zip, jar")
    }
}
