// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.genquery

import com.google.devtools.build.lib.rules.genquery.GenQueryOutputStream.GenQueryResult
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Tests for [GenQueryOutputStream].  */
@RunWith(TestParameterInjector::class)
class GenQueryOutputStreamTest {
    @TestParameter
    private val outputCompressed = false

    @Test
    @Throws(IOException::class)
    fun testSmallOutputMultibyteWrite() {
        runMultibyteWriteTest(
            "xyz".repeat(10000), GenQueryOutputStream.SimpleResult::class.java, outputCompressed
        )
    }

    @Test
    @Throws(IOException::class)
    fun testBigOutputMultibyteWrite() {
        runMultibyteWriteTest(
            "xyz".repeat(1000000),
            if (outputCompressed)
                GenQueryOutputStream.SimpleResult::class.java
            else
                GenQueryOutputStream.CompressedResultWithDecompressedOutput::class.java,
            outputCompressed
        )
    }

    @Test
    @Throws(IOException::class)
    fun testSmallOutputSingleByteWrites() {
        runSingleByteWriteTest(
            "xyz".repeat(10000), GenQueryOutputStream.SimpleResult::class.java, outputCompressed
        )
    }

    @Test
    @Throws(IOException::class)
    fun testBigOutputSingleByteWrites() {
        runSingleByteWriteTest(
            "xyz".repeat(1000000),
            if (outputCompressed)
                GenQueryOutputStream.SimpleResult::class.java
            else
                GenQueryOutputStream.CompressedResultWithDecompressedOutput::class.java,
            outputCompressed
        )
    }

    companion object {
        @Throws(IOException::class)
        private fun runMultibyteWriteTest(
            data: String, resultClass: Class<out GenQueryResult?>?, outputCompressed: Boolean
        ) {
            val underTest: GenQueryOutputStream = GenQueryOutputStream(outputCompressed)
            underTest.write(data.toByteArray(StandardCharsets.UTF_8))
            underTest.close()

            verifyGenQueryResult(underTest.getResult(), data, resultClass, outputCompressed)
        }

        @Throws(IOException::class)
        private fun runSingleByteWriteTest(
            data: String, resultClass: Class<out GenQueryResult?>?, outputCompressed: Boolean
        ) {
            val underTest: GenQueryOutputStream = GenQueryOutputStream(outputCompressed)
            for (b in data.toByteArray(StandardCharsets.UTF_8)) {
                underTest.write(b)
            }
            underTest.close()

            verifyGenQueryResult(underTest.getResult(), data, resultClass, outputCompressed)
        }

        @Throws(IOException::class)
        private fun verifyGenQueryResult(
            result: GenQueryOutputStream.GenQueryResult,
            data: String,
            resultClass: Class<out GenQueryResult?>?,
            outputCompressed: Boolean
        ) {
            assertThat(result).isInstanceOf(resultClass)

            if (outputCompressed) {
                // If result is actually compressed, also compress input data so that it is comparable to what
                // is outputted from GenQueryResult.
                val dataInByteString: ByteString = ByteString.copyFromUtf8(data)
                val compressedDataBytesOut: ByteString.Output = ByteString.newOutput()
                val gzipDataOut: GZIPOutputStream = GZIPOutputStream(compressedDataBytesOut)
                dataInByteString.writeTo(gzipDataOut)
                gzipDataOut.finish()
                val dataCompressedInByteString: ByteString = compressedDataBytesOut.toByteString()

                assertThat(result.bytes).isEqualTo(dataCompressedInByteString)

                val actualFingerprint: Fingerprint = Fingerprint()
                result.fingerprint(actualFingerprint)
                val expectFingerprint: Fingerprint = Fingerprint()
                expectFingerprint.addBytes(dataCompressedInByteString)
                assertThat(actualFingerprint.hexDigestAndReset())
                    .isEqualTo(expectFingerprint.hexDigestAndReset())

                val bytesOut = ByteArrayOutputStream()
                result.writeTo(bytesOut)
                Truth.assertThat(bytesOut.toByteArray()).isEqualTo(dataCompressedInByteString.toByteArray())
            } else {
                assertThat(result.bytes).isEqualTo(ByteString.copyFromUtf8(data))
                assertThat(result.size()).isEqualTo(data.length)

                val actualFingerprint: Fingerprint = Fingerprint()
                result.fingerprint(actualFingerprint)
                val expectFingerprint: Fingerprint = Fingerprint()
                expectFingerprint.addBytes(data.toByteArray(StandardCharsets.UTF_8))
                assertThat(actualFingerprint.hexDigestAndReset())
                    .isEqualTo(expectFingerprint.hexDigestAndReset())

                val bytesOut = ByteArrayOutputStream()
                result.writeTo(bytesOut)
                Truth.assertThat(String(bytesOut.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(data)
            }
        }
    }
}
