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
package com.google.devtools.build.lib.bazel.repository.decompressor

import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZUtils
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/** Decompresses an xz (LZMA) compressed file.  */
class XzFunction : CompressedFunction() {
    /**
     * Uses [XZCompressorInputStream] from Apache Commons Compress to decompress.
     * 
     * 
     * Why not use [XZInputStream] which is used in [TarXzFunction]? The
     * Apache Commons Compress libraries are wrappers around org.tukaani.xz.XZInputStream, so they
     * should be the same. Since we also use [ ], we keep consistency and use the Apache
     * wrapper consistently in this class.
     * 
     * @see [javadoc](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/compressors/xz/package-summary.html)
     */
    @Throws(IOException::class)
    override fun getDecompressorStream(compressedInputStream: BufferedInputStream?): InputStream {
        return XZCompressorInputStream(compressedInputStream)
    }

    override fun getUncompressedFileName(`in`: InputStream?, compressedFileName: String?): String? {
        return XZUtils.getUncompressedFileName(compressedFileName)
    }

    companion object {
        val INSTANCE: DecompressorValue.Decompressor = XzFunction()
    }
}
