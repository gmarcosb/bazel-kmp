// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.lang.String

/**
 * Opens a .ar archive file. It ignores the prefix setting because these archives cannot contain
 * directories.
 */
class ArFunction : DecompressorValue.Decompressor {
    @Throws(InterruptedException::class, IOException::class)
    override fun decompress(descriptor: DecompressorDescriptor): Path? {
        if (Thread.interrupted()) {
            throw InterruptedException()
        }

        val renameFiles: MutableMap<String?, String?> = descriptor.renameFiles

        BufferedInputStream(descriptor.archivePath.getInputStream(), BUFFER_SIZE).use { decompressorStream ->
            val arStream = ArArchiveInputStream(decompressorStream)
            var entry: ArArchiveEntry?
            while ((arStream.getNextArEntry().also { entry = it }) != null) {
                var entryName = entry!!.getName()
                entryName = renameFiles.getOrDefault(entryName, entryName)
                val entryPathRelative = PathFragment.create(entryName)
                if (entryPathRelative.isAbsolute()) {
                    throw IOException(
                        String.format("Failed to extract %s, ar paths cannot be absolute", entryName)
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
                if (entry.isDirectory()) {
                    // ar archives don't contain any directory information, so this should never
                    // happen
                    continue
                } else {
                    // We do not have to worry about symlinks in .ar files - it's not supported
                    // by the .ar file format.
                    filePath.getOutputStream().use { out ->
                        ByteStreams.copy(arStream, out)
                    }
                    // Ensure that all files are at least user-readable. Some archives contain files that
                    // are not, but many other tools are working around this and thus mask these issues.
                    filePath.chmod(entry.getMode() or 256)
                    // entry.getLastModified() appears to be in seconds, so we need to convert
                    // it into milliseconds for setLastModifiedTime
                    filePath.setLastModifiedTime(entry.getLastModified() * 1000L)
                }
                if (Thread.interrupted()) {
                    throw InterruptedException()
                }
            }
        }
        return descriptor.destinationPath
    }

    companion object {
        val INSTANCE: DecompressorValue.Decompressor = ArFunction()

        // This is the same value as picked for .tar files, which appears to have worked well.
        private val BUFFER_SIZE = 32 * 1024
    }
}
