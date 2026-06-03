// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions.cache

import com.google.devtools.build.lib.util.Fingerprint

/** Utility class for digests/metadata relating to the action cache.  */
object MetadataDigestUtils {
    /**
     * @param source the byte buffer source.
     * @return the digest from the given buffer.
     */
    @Throws(IOException::class)
    fun read(source: java.nio.ByteBuffer): ByteArray {
        val size: Int = VarInt.getVarInt(source)
        if (size < 0) {
            throw IOException("Negative digest size: " + size)
        }
        val bytes = ByteArray(size)
        source.get(bytes)
        return bytes
    }

    /** Write the digest to the output stream.  */
    @Throws(IOException::class)
    fun write(digest: ByteArray, sink: java.io.OutputStream) {
        VarInt.putVarInt(digest.size, sink)
        sink.write(digest)
    }

    /**
     * Computes an order-independent digest from the given (path, metadata) pairs.
     * 
     * @param mdMap A collection of (execPath, FileArtifactValue) pairs. Values may be null.
     */
    fun fromMetadata(mdMap: MutableMap<String?, FileArtifactValue?>): ByteArray? {
        var result: ByteArray? = ByteArray(1) // reserve the empty string
        // Profiling showed that MessageDigest engine instantiation was a hotspot, so create one
        // instance for this computation to amortize its cost.
        val fp: Fingerprint = Fingerprint()
        for (entry in mdMap.entries) {
            result =
                DigestUtils.combineUnordered(result, getDigest(fp, entry.key, entry.value))
        }
        return result
    }

    private fun getDigest(fp: Fingerprint, execPath: String?, md: FileArtifactValue?): ByteArray {
        fp.addString(execPath)
        if (md != null) {
            md.addTo(fp)
        }
        return fp.digestAndReset()
    }
}
