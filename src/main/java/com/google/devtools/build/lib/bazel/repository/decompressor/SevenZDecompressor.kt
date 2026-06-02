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

import com.google.common.base.Strings
import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.bazel.repository.RepositoryFunctionException
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorValue.Decompressor.CouldNotFindPrefixException
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.IOException
import java.lang.String
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Consumer

/**
 * Creates a repository by decompressing a 7-zip file. This implementation generally follows the
 * logic from [ZipDecompressor] with the exception that the 7z format does not support file
 * permissions or symbolic links.
 */
class SevenZDecompressor : DecompressorValue.Decompressor {
    /** Decompresses the file to directory [DecompressorDescriptor.destinationPath]  */
    @Throws(IOException::class, RepositoryFunctionException::class, InterruptedException::class)
    override fun decompress(descriptor: DecompressorDescriptor): Path? {
        val destinationDirectory = descriptor.destinationPath
        val prefix = descriptor.prefix
        val renameFiles = descriptor.renameFiles
        var foundPrefix = false

        SevenZFile.builder().setFile(descriptor.archivePath.getPathFile()).get().use { sevenZFile ->
            val entries: Iterable<SevenZArchiveEntry> = sevenZFile.getEntries()
            for (entry in entries) {
                var entryName = entry.getName()
                /*
         * From https://commons.apache.org/proper/commons-compress/examples.html
         *
         * <blockquote>
         *
         * Some 7z archives don't contain any names for the archive entries. The native 7zip tools
         * derive a default name from the name of the archive itself for such entries. Starting with
         * Compress 1.19 SevenZFile has an option to mimic this behavior, but by default unnamed
         * archive entries will return null from {@link SevenZArchiveEntry#getName}.
         *
         * </blockquote>
         *
         * The 7-zip command line will try to rename ALL nameless entries with the same default file
         * name. The user will be prompted if they want to overwrite a previously extracted nameless
         * file with the next nameless file. Since we don't have interactive prompting when doing
         * extractions, and don't know the correct behavior desired (overwrite the file with the
         * later entries or not), we will simply throw an error for ALL nameless entries. Maybe
         * there should be a flag/option to dictate the behavior, but it's probably too small of an
         * edge case.
         */
                if (Strings.isNullOrEmpty(entryName)) {
                    throw IOException("7z archive contains unnamed entry")
                }
                entryName = renameFiles.getOrDefault(entryName, entryName)
                val entryPath: StripPrefixedPath =
                    StripPrefixedPath.Companion.maybeDeprefix(entryName.getBytes(StandardCharsets.UTF_8), prefix)
                foundPrefix = foundPrefix || entryPath.foundPrefix()
                if (entryPath.skip()) {
                    continue
                }
                val pathFragment =
                    entryPath.getPathFragment().stripComponents(descriptor.stripComponents)
                if (pathFragment == PathFragment.EMPTY_FRAGMENT) {
                    continue
                }
                extract7zEntry(sevenZFile, entry, destinationDirectory, pathFragment)
            }
            if (prefix.isPresent() && !foundPrefix) {
                val prefixes: MutableSet<String?> = HashSet<String?>()
                for (entry in entries) {
                    val entryPath: StripPrefixedPath =
                        StripPrefixedPath.Companion.maybeDeprefix(
                            entry.getName().getBytes(StandardCharsets.UTF_8),
                            Optional.empty<String?>()
                        )
                    CouldNotFindPrefixException.Companion.maybeMakePrefixSuggestion(entryPath.getPathFragment())
                        .ifPresent(Consumer { e: String? -> prefixes.add(e) })
                }
                throw CouldNotFindPrefixException(prefix.get(), prefixes)
            }
        }
        return destinationDirectory
    }

    companion object {
        val INSTANCE: DecompressorValue.Decompressor = SevenZDecompressor()

        @Throws(IOException::class, InterruptedException::class)
        private fun extract7zEntry(
            sevenZFile: SevenZFile,
            entry: SevenZArchiveEntry,
            destinationDirectory: Path,
            strippedRelativePath: PathFragment
        ) {
            if (strippedRelativePath.isAbsolute()) {
                throw IOException(
                    String.format(
                        "Failed to extract %s, 7-zipped paths cannot be absolute", strippedRelativePath
                    )
                )
            }
            val outputPath = destinationDirectory.getRelative(strippedRelativePath)
            if (!outputPath.startsWith(destinationDirectory)) {
                throw IOException(
                    String.format(
                        "Failed to extract %s, path is escaping the destination directory",
                        strippedRelativePath
                    )
                )
            }
            outputPath.getParentDirectory()!!.createDirectoryAndParents()
            val isDirectory = entry.isDirectory()
            if (isDirectory) {
                outputPath.createDirectoryAndParents()
            } else {
                sevenZFile.getInputStream(entry).use { input ->
                    outputPath.getOutputStream().use { output ->
                        ByteStreams.copy(input, output)
                        if (Thread.interrupted()) {
                            throw InterruptedException()
                        }
                    }
                }
                if (entry.getHasLastModifiedDate()) {
                    outputPath.setLastModifiedTime(entry.getLastModifiedTime().toMillis())
                }
            }
        }
    }
}
