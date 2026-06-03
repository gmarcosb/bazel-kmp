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
package com.google.devtools.build.lib.remote.zstd

import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase.write
import com.google.devtools.build.lib.remote.zstd.ZstdCompressingInputStream
import com.google.devtools.build.lib.remote.zstd.ZstdDecompressingOutputStream
import com.google.devtools.build.lib.remote.zstd.ZstdDecompressingOutputStream.write
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Setup
import java.io.ByteArrayInputStream

@BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
@org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Benchmark)
class ZstdBenchmark {
    @org.openjdk.jmh.annotations.Param("4096", "4194304")
    var size: Int = 0

    private var uncompressedData: ByteArray
    private var compressedData: ByteArray

    @Setup
    fun setup() {
        uncompressedData = ByteArray(size)
        for (i in 0..<size) {
            uncompressedData[i] = (i % 256).toByte()
        }
        try {
            val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            val zci: ZstdCompressingInputStream =
                ZstdCompressingInputStream(ByteArrayInputStream(uncompressedData))
            zci.transferTo(baos)
            compressedData = baos.toByteArray()
        } catch (e: java.lang.Exception) {
            throw java.lang.RuntimeException("Failed to compress data", e)
        }
    }

    @org.openjdk.jmh.annotations.Benchmark
    @Throws(java.lang.Exception::class)
    fun compress(): java.io.ByteArrayOutputStream {
        val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        ZstdCompressingInputStream(ByteArrayInputStream(uncompressedData)).use { zci ->
            zci.transferTo(baos)
        }
        return baos
    }

    @org.openjdk.jmh.annotations.Benchmark
    @Throws(java.lang.Exception::class)
    fun decompress(): java.io.ByteArrayOutputStream {
        val baos: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        ZstdDecompressingOutputStream(baos).use { zdos ->
            zdos.write(compressedData)
        }
        return baos
    }
}
