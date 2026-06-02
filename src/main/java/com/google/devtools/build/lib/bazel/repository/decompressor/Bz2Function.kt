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

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2Utils
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/** Decompresses a bzip2 compressed file.  */
class Bz2Function : CompressedFunction() {
    @Throws(IOException::class)
    override fun getDecompressorStream(compressedInputStream: BufferedInputStream?): InputStream {
        return BZip2CompressorInputStream(compressedInputStream, true)
    }

    override fun getUncompressedFileName(`in`: InputStream?, compressedFileName: String?): String? {
        return BZip2Utils.getUncompressedFileName(compressedFileName)
    }

    companion object {
        val INSTANCE: DecompressorValue.Decompressor = Bz2Function()
    }
}
