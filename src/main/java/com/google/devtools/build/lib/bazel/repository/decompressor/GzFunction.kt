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

import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipUtils
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/** Decompresses a gzip compressed file.  */
class GzFunction : CompressedFunction() {
    @Throws(IOException::class)
    public override fun getDecompressorStream(compressedInputStream: BufferedInputStream?): InputStream {
        return GzipCompressorInputStream(compressedInputStream, true)
    }

    public override fun getUncompressedFileName(`in`: InputStream, compressedFileName: String?): String? {
        val fileName = (`in` as GzipCompressorInputStream).getMetaData().getFileName()
        if (fileName != null && !fileName.isBlank()) {
            // filename should be the simple basename + ext, but convert to a PathFragment and run
            // getBaseName to ensure that any path separators and uplevel references are dropped.
            return PathFragment.create(fileName).getBaseName()
        }
        return GzipUtils.getUncompressedFileName(compressedFileName)
    }

    @Throws(IOException::class)
    public override fun setFileAttributes(`in`: InputStream, uncompressedFile: Path) {
        val metaData = (`in` as GzipCompressorInputStream).getMetaData()
        uncompressedFile.setLastModifiedTime(metaData.getModificationTime() * 1000)
    }

    companion object {
        val INSTANCE: DecompressorValue.Decompressor = GzFunction()
    }
}
