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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.vfs.DigestHashFunction

/** Tests for [MapCodec].  */
@RunWith(JUnit4::class)
class MapCodecTest {
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val testPath: Path? = fs.getPath("/test")

    @org.junit.Test
    @Throws(IOException::class)
    fun createWriter_overwriteMissingFileWithEmpty() {
        TEST_CODEC.createWriter(testPath, 0x42,  /* overwrite= */true).use { out -> }
        assertByteContents(testPath, "00000000 20071105 00000000 00000042")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createWriter_overwriteMissingFileWithNonEmpty() {
        TEST_CODEC.createWriter(testPath, 0x42,  /* overwrite= */true).use { out ->
            out.writeEntry(0x12345678, -0x789abcdf)
            out.writeEntry(0xabcdef, null)
        }
        assertByteContents(
            testPath, "00000000 20071105 00000000 00000042 fe 12345678 01 87654321 fe 00abcdef 00"
        )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createWriter_overwriteExistingFile() {
        writeByteContents(testPath, "00000000 20071105 00000000 00000042 fe 12345678 01 87654321")

        TEST_CODEC.createWriter(testPath, 0x42,  /* overwrite= */true).use { out ->
            out.writeEntry(-0x789abcdf, 0x12345678)
        }
        assertByteContents(testPath, "00000000 20071105 00000000 00000042 fe 87654321 01 12345678")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun writer_appendToMissingFile() {
        TEST_CODEC.createWriter(testPath, 0x42,  /* overwrite= */false).use { out ->
            out.writeEntry(0x12345678, -0x789abcdf)
        }
        assertByteContents(testPath, "00000000 20071105 00000000 00000042 fe 12345678 01 87654321")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun writer_appendToExistingEmptyFile() {
        writeByteContents(testPath, "00000000 20071105 00000000 00000042")

        TEST_CODEC.createWriter(testPath, 0x42,  /* overwrite= */false).use { out ->
            out.writeEntry(-0x789abcdf, 0x12345678)
        }
        assertByteContents(testPath, "00000000 20071105 00000000 00000042 fe 87654321 01 12345678")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun writer_appendToExistingNonEmptyFile() {
        writeByteContents(testPath, "00000000 20071105 00000000 00000042 fe 12345678 01 87654321")

        TEST_CODEC.createWriter(testPath, 0x42,  /* overwrite= */false).use { out ->
            out.writeEntry(-0x789abcdf, 0x12345678)
        }
        assertByteContents(
            testPath,
            "00000000 20071105 00000000 00000042 fe 12345678 01 87654321 fe 87654321 01 12345678"
        )
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createReader_emptyFile() {
        writeByteContents(testPath, "00000000 20071105 00000000 00000042")

        TEST_CODEC.createReader(testPath, 0x42).use { `in` ->
            assertThat(`in`.readEntry()).isNull()
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createReader_nonEmptyFile() {
        writeByteContents(
            testPath, "00000000 20071105 00000000 00000042 fe 12345678 01 87654321 fe 00abcdef 00"
        )

        TEST_CODEC.createReader(testPath, 0x42).use { `in` ->
            assertThat(`in`.readEntry()).isEqualTo(Entry(0x12345678, -0x789abcdf))
            assertThat(`in`.readEntry()).isEqualTo(Entry(0xabcdef, null))
            assertThat(`in`.readEntry()).isNull()
        }
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createReader_missingFile() {
        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { TEST_CODEC.createReader(testPath, 0x42) })
        Truth.assertThat(e).hasMessageThat().contains("No such file or directory")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createReader_badMagic() {
        writeByteContents(testPath, "00000000 12345678 00000000 00000042")

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { TEST_CODEC.createReader(testPath, 0x42) })
        Truth.assertThat(e).hasMessageThat().contains("Bad magic number")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createReader_incompatibleVersion() {
        writeByteContents(testPath, "00000000 20071105 00000000 00000042")

        val e: IOException? = org.junit.Assert.assertThrows<IOException?>(
            IOException::class.java,
            org.junit.function.ThrowingRunnable { TEST_CODEC.createReader(testPath, 0x43) })
        Truth.assertThat(e).hasMessageThat().contains("Incompatible version")
    }

    @org.junit.Test
    @Throws(IOException::class)
    fun createReader_corruptedEntry() {
        writeByteContents(
            testPath,
            "00000000 20071105 00000000 00000042 fe 12345678 01 87654321 ff 11111111 01 22222222"
        )

        TEST_CODEC.createReader(testPath, 0x42).use { `in` ->
            assertThat(`in`.readEntry()).isEqualTo(Entry(0x12345678, -0x789abcdf))
            val e: IOException? = org.junit.Assert.assertThrows<IOException?>(IOException::class.java, `in`::readEntry)
            Truth.assertThat(e).hasMessageThat().contains("Corrupted entry")
        }
    }

    companion object {
        private val TEST_CODEC: MapCodec<Int?, Int?> = object : MapCodec<Int?, Int?>() {
            @Throws(IOException::class)
            protected override fun readKey(`in`: DataInput): Int {
                return `in`.readInt()
            }

            @Throws(IOException::class)
            protected override fun readValue(`in`: DataInput): Int {
                return `in`.readInt()
            }

            @Throws(IOException::class)
            protected override fun writeKey(key: Int, out: DataOutput) {
                out.writeInt(key)
            }

            @Throws(IOException::class)
            protected override fun writeValue(value: Int, out: DataOutput) {
                out.writeInt(value)
            }
        }

        @Throws(IOException::class)
        private fun writeByteContents(path: Path?, hex: String) {
            val content: ByteArray = com.google.common.io.BaseEncoding.base16().lowerCase().decode(hex.replace(" ", ""))
            FileSystemUtils.writeContent(path, content)
        }

        @Throws(IOException::class)
        private fun assertByteContents(path: Path?, hex: String) {
            val actual: String =
                com.google.common.io.BaseEncoding.base16().lowerCase().encode(FileSystemUtils.readContent(path))
            val expected: String? = hex.replace(" ", "")
            Truth.assertThat(actual).isEqualTo(expected)
        }
    }
}
