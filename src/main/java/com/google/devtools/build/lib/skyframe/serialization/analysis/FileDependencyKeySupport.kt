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
package com.google.devtools.build.lib.skyframe.serialization.analysis

/** Constants and methods supporting [FileInvalidationData] keys.  */
internal object FileDependencyKeySupport {
    const val MAX_KEY_LENGTH: Int = 250

    private val EMPTY_BYTES = ByteArray(0)

    /**
     * Neither [.FILE_KEY_DELIMITER] nor [.DIRECTORY_KEY_DELIMITER] are used in Base64,
     * making them good delimiters for the Base64-encoded version numbers.
     * 
     * 
     * See comment at [FileInvalidationData] for more details.
     */
    val FILE_KEY_DELIMITER: Byte = ':'.code.toByte()

    val DIRECTORY_KEY_DELIMITER: Byte = ';'.code.toByte()

    private val ENCODER: java.util.Base64.Encoder = java.util.Base64.getEncoder().withoutPadding()

    fun encodeMtsv(mtsv: Long): ByteArray {
        if (mtsv < 0) {
            com.google.common.base.Preconditions.checkArgument(mtsv == LongVersionGetter.MINIMAL, mtsv)
            return EMPTY_BYTES // BigInteger.toByteArray is never empty so this is unique.
        }
        // Uses a BigInteger to trim leading 0 bytes.
        return ENCODER.encode(BigInteger.valueOf(mtsv).toByteArray())
    }

    fun computeCacheKey(path: PathFragment, mtsv: Long, delimiter: Byte): String? {
        return FileDependencyKeySupport.computeCacheKey(path.getPathString(), mtsv, delimiter)
    }

    fun computeCacheKey(path: String?, mtsv: Long, delimiter: Byte): String? {
        val encodedMtsv = encodeMtsv(mtsv)
        val pathBytes: ByteArray = StringUnsafe.getInternalStringBytes(path)

        val keyBytes: ByteArray = java.util.Arrays.copyOf(encodedMtsv, encodedMtsv.size + 1 + pathBytes.size)
        keyBytes[encodedMtsv.size] = delimiter
        java.lang.System.arraycopy(pathBytes, 0, keyBytes, encodedMtsv.size + 1, pathBytes.size)

        // encodedMtsv is Base64-encoded and thus always ASCII-only, which means that it doesn't require
        // any reencoding to match the internal string encoding (see StringEncoding for details).
        return StringUnsafe.newInstance(keyBytes, StringUnsafe.LATIN1)
    }
}
