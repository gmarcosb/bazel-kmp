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
package com.google.devtools.build.lib.remote.chunking

import com.google.devtools.build.lib.remote.util.DigestUtil

@BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
@org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Benchmark)
@Warmup(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@org.openjdk.jmh.annotations.Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
@Fork(3)
class FastCdcBenchmark {
    @org.openjdk.jmh.annotations.Param("1048576", "8388608", "67108864")
    var size: Int = 0

    private var data: ByteArray
    private var chunker: FastCdcChunker? = null

    @Setup(org.openjdk.jmh.annotations.Level.Iteration)
    fun setup() {
        BazelHashFunctions.ensureRegistered()
        data = ByteArray(size)
        java.security.SecureRandom().nextBytes(data)

        val digestUtil: DigestUtil = DigestUtil(SyscallCache.NO_CACHE, BazelHashFunctions.BLAKE3)
        val minSize = AVG_CHUNK_SIZE / 4
        val maxSize = AVG_CHUNK_SIZE * 4
        chunker = FastCdcChunker(minSize, AVG_CHUNK_SIZE, maxSize, 2, 0, digestUtil)
    }

    @org.openjdk.jmh.annotations.Benchmark
    @Throws(java.lang.Exception::class)
    fun chunkToDigests(): Any {
        return chunker.chunkToDigests(ByteArrayInputStream(data))
    }

    companion object {
        private val AVG_CHUNK_SIZE = 512 * 1024
    }
}
