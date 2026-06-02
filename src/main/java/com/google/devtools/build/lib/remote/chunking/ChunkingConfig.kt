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

import build.bazel.remote.execution.v2.CacheCapabilities

/** Configuration for content-defined chunking. All sizes are in bytes.  */
@kotlin.jvm.JvmRecord
data class ChunkingConfig(@kotlin.jvm.JvmField val avgChunkSize: Int, @kotlin.jvm.JvmField val normalizationLevel: Int, @kotlin.jvm.JvmField val seed: Int) {
    fun minChunkSize(): Int {
        return avgChunkSize / 4
    }

    fun maxChunkSize(): Int {
        return avgChunkSize * 4
    }

    /** Blobs larger than this should be chunked. Equal to maxChunkSize().  */
    fun chunkingThreshold(): Long {
        return maxChunkSize().toLong()
    }

    companion object {
        val DEFAULT_AVG_CHUNK_SIZE: Int = 512 * 1024
        const val DEFAULT_NORMALIZATION_LEVEL: Int = 2
        const val DEFAULT_SEED: Int = 0

        fun defaults(): ChunkingConfig {
            return ChunkingConfig(DEFAULT_AVG_CHUNK_SIZE, DEFAULT_NORMALIZATION_LEVEL, DEFAULT_SEED)
        }

        fun fromServerCapabilities(capabilities: ServerCapabilities): ChunkingConfig? {
            if (!capabilities.hasCacheCapabilities()) {
                return null
            }
            val cacheCap: CacheCapabilities = capabilities.getCacheCapabilities()

            if (!cacheCap.hasFastCdc2020Params()) {
                return null
            }

            val params: FastCdc2020Params = cacheCap.getFastCdc2020Params()
            var avgSize: Int = DEFAULT_AVG_CHUNK_SIZE
            val configAvgSize: Long = params.getAvgChunkSizeBytes()
            if (configAvgSize >= 1024 && configAvgSize <= 1024 * 1024 && (configAvgSize and (configAvgSize - 1)) == 0L) {
                avgSize = configAvgSize.toInt()
            }
            val seed: Int = params.getSeed()

            return ChunkingConfig(avgSize, DEFAULT_NORMALIZATION_LEVEL, seed)
        }
    }
}
