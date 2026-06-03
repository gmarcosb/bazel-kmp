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
package com.google.devtools.build.lib.util.io

import com.google.common.truth.Truth
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Tests for [RecordOutputStream].  */
@RunWith(JUnit4::class)
class RecordOutputStreamTest {
    @Test
    @Throws(IOException::class)
    fun empty() {
        val baos = ByteArrayOutputStream()
        RecordOutputStream(baos).use { recordOut -> }
        Truth.assertThat(baos.toByteArray()).isEmpty()
    }

    @Test
    @Throws(IOException::class)
    fun write_singleRecord() {
        val baos = ByteArrayOutputStream()
        RecordOutputStream(baos).use { recordOut ->
            recordOut.write(byteArrayOf(0x12, 0x34, 0x56))
            recordOut.finishRecord()
        }
        Truth.assertThat(baos.toByteArray()).isEqualTo(byteArrayOf(0x12, 0x34, 0x56))
    }

    @Test
    @Throws(IOException::class)
    fun write_multipleRecords() {
        val baos = ByteArrayOutputStream()
        RecordOutputStream(baos).use { recordOut ->
            recordOut.write(byteArrayOf(0x12, 0x34, 0x56))
            recordOut.finishRecord()
            recordOut.write(byteArrayOf(0x21, 0x43, 0x65))
            recordOut.finishRecord()
        }
        Truth.assertThat(baos.toByteArray()).isEqualTo(byteArrayOf(0x12, 0x34, 0x56, 0x21, 0x43, 0x65))
    }

    @Test
    @Throws(IOException::class)
    fun write_largeRecord_singleWrite() {
        val record = ByteArray(65536)
        for (i in record.indices) {
            record[i] = i.toByte()
        }
        val baos = ByteArrayOutputStream()
        RecordOutputStream(baos).use { recordOut ->
            recordOut.write(record)
            recordOut.finishRecord()
        }
        Truth.assertThat(baos.toByteArray()).isEqualTo(record)
    }

    @Test
    @Throws(IOException::class)
    fun write_largeRecord_multipleWrites() {
        val record = ByteArray(65536)
        for (i in record.indices) {
            record[i] = i.toByte()
        }
        val baos = ByteArrayOutputStream()
        RecordOutputStream(baos).use { recordOut ->
            for (i in record.indices) {
                recordOut.write(record[i])
            }
            recordOut.finishRecord()
        }
        Truth.assertThat(baos.toByteArray()).isEqualTo(record)
    }

    @Test
    @Throws(IOException::class)
    fun flush_onlyCompleteRecords() {
        val baos = ByteArrayOutputStream()
        RecordOutputStream(baos).use { recordOut ->
            recordOut.write(byteArrayOf(0x12, 0x34))
            recordOut.finishRecord()
            recordOut.write(byteArrayOf(0x56, 0x78))
            recordOut.flush()
            Truth.assertThat(baos.toByteArray()).isEqualTo(byteArrayOf(0x12, 0x34))
            recordOut.write(byteArrayOf(0x21, 0x43))
            recordOut.finishRecord()
            recordOut.flush()
            Truth.assertThat(baos.toByteArray()).isEqualTo(byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x21, 0x43))
        }
    }

    @Test
    @Throws(IOException::class)
    fun close_onlyCompleteRecords() {
        val baos = ByteArrayOutputStream()
        RecordOutputStream(baos).use { recordOut ->
            recordOut.write(byteArrayOf(0x21, 0x34))
            recordOut.finishRecord()
            recordOut.write(byteArrayOf(0x56, 0x78))
        }
        Truth.assertThat(baos.toByteArray()).isEqualTo(byteArrayOf(0x21, 0x34))
    }
}
