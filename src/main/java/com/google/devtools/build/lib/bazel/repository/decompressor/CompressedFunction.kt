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
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.String

/**
 * Common code for decompressing a single compressed file (compressor formats).
 * 
 * 
 * Apache Commons Compress calls all formats that compress a single stream of data compressor
 * formats while all formats that collect multiple entries inside a single (potentially compressed)
 * archive are archiver formats. This class handles the former, compressor formats.
 * 
 * 
 * It ignores the [DecompressorDescriptor.prefix] and [ ][DecompressorDescriptor.stripComponents] setting because compressed files cannot contain
 * directories.
 */
abstract class CompressedFunction : DecompressorValue.Decompressor {
    @Throws(IOException::class)
    protected abstract fun getDecompressorStream(compressedInputStream: BufferedInputStream?): InputStream

    /**
     * Returns the uncompressed file name (eg. file.gz -> file). Some compressors have metadata that
     * stores the original name. If that's the case, the original name is used (eg. file.gz ->
     * originalName). Only a basename + ext should be passed in for the compressedFileName.
     */
    protected abstract fun getUncompressedFileName(
        `in`: InputStream?, compressedFileName: String?
    ): String?

    /**
     * Set custom file attributes, like last modified time, on the extracted file. Only certain
     * compressors support this.
     */
    @Throws(IOException::class)
    protected open fun setFileAttributes(`in`: InputStream?, uncompressedFile: Path?) {
    }

    @Throws(InterruptedException::class, IOException::class)
    override fun decompress(descriptor: DecompressorDescriptor): Path {
        if (Thread.interrupted()) {
            throw InterruptedException()
        }

        val renameFiles = descriptor.renameFiles
        getDecompressorStream(
            BufferedInputStream(descriptor.archivePath.getInputStream(), BUFFER_SIZE)
        ).use { decompressorStream ->
            var entryName =
                getUncompressedFileName(decompressorStream, descriptor.archivePath.getBaseName())
            entryName = renameFiles.getOrDefault(entryName, entryName)
            val entryPathRelative = PathFragment.create(entryName)
            if (entryPathRelative.isAbsolute()) {
                throw IOException(
                    String.format("Failed to extract %s, paths cannot be absolute", entryName)
                )
            }
            val filePath = descriptor.destinationPath.getRelative(entryPathRelative)
            if (!filePath.startsWith(descriptor.destinationPath)) {
                throw IOException(
                    String.format(
                        "Failed to extract %s, path is escaping the destination directory", entryName
                    )
                )
            }
            filePath.getParentDirectory()!!.createDirectoryAndParents()
            filePath.getOutputStream().use { out ->
                decompressorStream.transferTo(out)
            }
            setFileAttributes(decompressorStream, filePath)
            if (Thread.interrupted()) {
                throw InterruptedException()
            }
        }
        return descriptor.destinationPath
    }

    companion object {
        // This is the same value as picked for .tar files, which appears to have worked well.
        private val BUFFER_SIZE = 32 * 1024
    }
}
