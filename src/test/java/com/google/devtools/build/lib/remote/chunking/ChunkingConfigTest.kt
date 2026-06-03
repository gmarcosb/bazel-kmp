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

/** Tests for [ChunkingConfig].  */
@RunWith(JUnit4::class)
class ChunkingConfigTest {
    @org.junit.Test
    fun defaults_returnsExpectedValues() {
        val config: ChunkingConfig = ChunkingConfig.defaults()

        assertThat(config.avgChunkSize()).isEqualTo(512 * 1024)
        assertThat(config.normalizationLevel()).isEqualTo(2)
        assertThat(config.seed()).isEqualTo(0)
        Truth.assertThat(config.chunkingThreshold()).isEqualTo(512 * 1024 * 4)
    }

    @org.junit.Test
    fun minChunkSize_returnsQuarterOfAvg() {
        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)

        Truth.assertThat(config.minChunkSize()).isEqualTo(256)
    }

    @org.junit.Test
    fun maxChunkSize_returnsFourTimesAvg() {
        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)

        Truth.assertThat(config.maxChunkSize()).isEqualTo(4096)
    }

    @org.junit.Test
    fun chunkingThreshold_equalsMaxChunkSize() {
        val config: ChunkingConfig = ChunkingConfig(1024, 2, 0)

        Truth.assertThat(config.chunkingThreshold()).isEqualTo(config.maxChunkSize())
    }

    @org.junit.Test
    fun minAndMaxChunkSize_withDefaultConfig() {
        val config: ChunkingConfig = ChunkingConfig.defaults()

        Truth.assertThat(config.minChunkSize()).isEqualTo(128 * 1024)
        Truth.assertThat(config.maxChunkSize()).isEqualTo(2048 * 1024)
    }

    @org.junit.Test
    fun fromServerCapabilities_withoutCacheCapabilities_returnsNull() {
        val capabilities: ServerCapabilities? = ServerCapabilities.getDefaultInstance()

        val config: ChunkingConfig? = ChunkingConfig.fromServerCapabilities(capabilities)

        Truth.assertThat(config).isNull()
    }

    @org.junit.Test
    fun fromServerCapabilities_withoutFastCdcParams_returnsNull() {
        val capabilities: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setCacheCapabilities(CacheCapabilities.getDefaultInstance())
                .build()

        val config: ChunkingConfig? = ChunkingConfig.fromServerCapabilities(capabilities)

        Truth.assertThat(config).isNull()
    }

    @org.junit.Test
    fun fromServerCapabilities_withFastCdcParams_returnsConfig() {
        val capabilities: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .setFastCdc2020Params(
                            FastCdc2020Params.newBuilder()
                                .setAvgChunkSizeBytes(256 * 1024)
                                .setSeed(42)
                                .build()
                        )
                        .build()
                )
                .build()

        val config: ChunkingConfig? = ChunkingConfig.fromServerCapabilities(capabilities)

        Truth.assertThat(config).isNotNull()
        assertThat(config.avgChunkSize()).isEqualTo(256 * 1024)
        assertThat(config.seed()).isEqualTo(42)
        Truth.assertThat(config.chunkingThreshold()).isEqualTo(256 * 1024 * 4)
    }

    @org.junit.Test
    fun fromServerCapabilities_withDefaultFastCdcParams_returnsDefaults() {
        val capabilities: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .setFastCdc2020Params(
                            FastCdc2020Params.newBuilder()
                                .setAvgChunkSizeBytes(512 * 1024)
                                .setSeed(0)
                                .build()
                        )
                        .build()
                )
                .build()

        val config: ChunkingConfig? = ChunkingConfig.fromServerCapabilities(capabilities)

        Truth.assertThat(config).isEqualTo(ChunkingConfig.defaults())
    }

    @org.junit.Test
    fun fromServerCapabilities_nonPowerOfTwoAvgSize_fallsBackToDefault() {
        val capabilities: ServerCapabilities? =
            ServerCapabilities.newBuilder()
                .setCacheCapabilities(
                    CacheCapabilities.newBuilder()
                        .setFastCdc2020Params(
                            FastCdc2020Params.newBuilder().setAvgChunkSizeBytes(300 * 1024).build()
                        )
                        .build()
                )
                .build()

        val config: ChunkingConfig? = ChunkingConfig.fromServerCapabilities(capabilities)

        Truth.assertThat(config).isNotNull()
        assertThat(config.avgChunkSize()).isEqualTo(ChunkingConfig.DEFAULT_AVG_CHUNK_SIZE)
    }
}
