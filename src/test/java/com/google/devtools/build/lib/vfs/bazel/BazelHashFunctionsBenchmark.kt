// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.vfs.bazel

import com.google.devtools.build.lib.vfs.bazel.BazelHashFunctions
import com.google.devtools.build.lib.vfs.bazel.BazelHashFunctions.ensureRegistered
import com.google.devtools.build.lib.vfs.bazel.Blake3HashFunction
import com.google.devtools.build.lib.vfs.bazel.Blake3Hasher.hash
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Setup

@BenchmarkMode(org.openjdk.jmh.annotations.Mode.Throughput)
@org.openjdk.jmh.annotations.State(org.openjdk.jmh.annotations.Scope.Benchmark)
class BazelHashFunctionsBenchmark {
    enum class HashFunctionType(hashFunction: com.google.common.hash.HashFunction) {
        BLAKE3(Blake3HashFunction()),
        SHA2_256(com.google.common.hash.Hashing.sha256());

        val hashFunction: com.google.common.hash.HashFunction

        init {
            this.hashFunction = hashFunction
        }
    }

    enum class Size {
        B,
        KB,
        MB,
        GB;

        val bytes: Int

        init {
            bytes = 1 shl (ordinal * 10)
        }
    }

    @org.openjdk.jmh.annotations.Param("BLAKE3", "SHA2_256")
    var type: HashFunctionType? = null

    @org.openjdk.jmh.annotations.Param("B", "KB", "MB", "GB")
    var size: Size? = null

    private var data: ByteArray

    @Setup(org.openjdk.jmh.annotations.Level.Iteration)
    fun setup() {
        data = ByteArray(size!!.bytes)
        java.security.SecureRandom().nextBytes(data)
    }

    @org.openjdk.jmh.annotations.Benchmark
    fun hashBytesOneShot(): com.google.common.hash.HashCode {
        return type!!.hashFunction.hashBytes(data)
    }

    companion object {
        init {
            BazelHashFunctions.ensureRegistered()
        }
    }
}
