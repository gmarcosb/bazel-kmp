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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum.InvalidChecksumException

/** Event that records the fact that a file has been downloaded from a remote registry.  */
internal class RegistryFileDownloadEvent(
  @kotlin.jvm.JvmField val uri: String?,
  checksum: java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?
) : com.google.devtools.build.lib.events.ExtendedEventHandler.Postable {
    val checksum: java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?

    init {
        this.checksum = checksum
    }

    companion object {
        fun create(uri: String?, content: java.util.Optional<ByteArray?>): RegistryFileDownloadEvent {
            return RegistryFileDownloadEvent(
                uri,
                content.map<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>(java.util.function.Function { bytes: ByteArray? ->
                    Companion.computeHash(bytes!!)
                })
            )
        }

        fun collectToMap(postables: MutableCollection<com.google.devtools.build.lib.events.ExtendedEventHandler.Postable?>): com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?> =
                com.google.common.collect.ImmutableMap.builder<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>()
            for (postable in postables) {
                if (postable is) {
                    builder.put(uri, checksum)
                }
            }
            return builder.buildKeepingLast()
        }

        private fun computeHash(bytes: ByteArray): com.google.devtools.build.lib.bazel.repository.downloader.Checksum {
            try {
                return com.google.devtools.build.lib.bazel.repository.downloader.Checksum.fromString(
                    DownloadCache.KeyType.SHA256, com.google.common.hash.Hashing.sha256().hashBytes(bytes).toString()
                )
            } catch (e: InvalidChecksumException) {
                // This can't happen since HashCode.toString() always returns a valid hash.
                throw java.lang.IllegalStateException(e)
            }
        }
    }
}
