// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.common.annotations.VisibleForTesting
import com.google.common.base.Preconditions
import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorValue.Decompressor.CouldNotFindPrefixException
import com.google.devtools.build.lib.vfs.FileSystemUtils
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.zip.ZipFileEntry
import com.google.devtools.build.zip.ZipReader
import com.google.devtools.build.zip.ZipReader.entries
import java.io.IOException
import java.lang.String
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.Consumer
import kotlin.ByteArray
import kotlin.Int
import kotlin.Long

/**
 * Creates a repository by decompressing a zip file.
 */
class ZipDecompressor private constructor() : DecompressorValue.Decompressor {
    /**
     * This unzips the zip file to directory [DecompressorDescriptor.destinationPath], which
     * by default is empty relative [to the calling external repository rule] path. The zip file is
     * expected to have the WORKSPACE file at the top level, e.g.:
     * 
     * <pre>
     * $ unzip -lf some-repo.zip
     * Archive:  ../repo.zip
     * Length      Date    Time    Name
     * ---------  ---------- -----   ----
     * 0  2014-11-20 15:50   WORKSPACE
     * 0  2014-11-20 16:10   foo/
     * 236  2014-11-20 15:52   foo/BUILD
     * ...
    </pre> * 
     */
    @Throws(IOException::class, InterruptedException::class)
    override fun decompress(descriptor: DecompressorDescriptor): Path? {
        val destinationDirectory = descriptor.destinationPath
        val prefix = descriptor.prefix
        val renameFiles: MutableMap<String?, String?> = descriptor.renameFiles
        var foundPrefix = false
        // Store link, target info of symlinks, we create them after regular files are extracted.
        val symlinks: MutableMap<Path?, PathFragment?> = HashMap<Path?, PathFragment?>()

        ZipReader(descriptor.archivePath.getPathFile()).use { reader ->
            val entries: MutableCollection<ZipFileEntry>? = reader.entries()
            for (entry in entries!!) {
                var entryName: String? = entry.getName()
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
                extractZipEntry(reader, entry, destinationDirectory, pathFragment, prefix, symlinks)
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
        for (symlink in symlinks.entrySet()) {
            FileSystemUtils.ensureSymbolicLink(symlink.getKey(), symlink.getValue())
        }

        return destinationDirectory
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: DecompressorValue.Decompressor = ZipDecompressor()
        private const val MAX_PATH_LENGTH: Long = 256

        private const val S_IFDIR = 16384
        private const val S_IFREG = 32768
        private const val S_IFLNK = 40960
        private const val EXECUTABLE_MASK = 493

        // source: https://docs.microsoft.com/en-us/windows/win32/fileio/file-attribute-constants
        @VisibleForTesting
        const val WINDOWS_FILE_ATTRIBUTE_DIRECTORY: Int = 0x10

        @VisibleForTesting
        const val WINDOWS_FILE_ATTRIBUTE_ARCHIVE: Int = 0x20

        @VisibleForTesting
        const val WINDOWS_FILE_ATTRIBUTE_NORMAL: Int = 0x80

        @Throws(IOException::class, InterruptedException::class)
        private fun extractZipEntry(
            reader: ZipReader,
            entry: ZipFileEntry,
            destinationDirectory: Path,
            strippedRelativePath: PathFragment,
            prefix: Optional<String?>?,
            symlinks: MutableMap<Path?, PathFragment?>
        ) {
            if (strippedRelativePath.isAbsolute()) {
                throw IOException(
                    String.format(
                        "Failed to extract %s, zipped paths cannot be absolute", strippedRelativePath
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
            val permissions: Int = getPermissions(entry.externalAttributes, entry.getName())
            outputPath.getParentDirectory()!!.createDirectoryAndParents()
            val isDirectory = (permissions and S_IFDIR) == S_IFDIR
            val isSymlink = (permissions and S_IFLNK) == S_IFLNK
            if (isDirectory) {
                outputPath.createDirectoryAndParents()
            } else if (isSymlink) {
                Preconditions.checkState(entry.getSize() < MAX_PATH_LENGTH)
                val buffer = ByteArray(entry.getSize().toInt())
                // For symlinks, the "compressed data" is actually the target name.
                val read = reader.getInputStream(entry).read(buffer)
                Preconditions.checkState(read == buffer.size)

                val target: PathFragment = StripPrefixedPath.Companion.createPathFragment(buffer)
                val targetPath = outputPath.getParentDirectory()!!.getRelative(target)
                if (!target.isAbsolute() && !targetPath.startsWith(destinationDirectory)) {
                    throw IOException(
                        ("Zip entries cannot refer to files outside of their directory: "
                                + reader.getFilename()
                                + " has a symlink "
                                + strippedRelativePath
                                + " pointing to "
                                + kotlin.String(buffer, StandardCharsets.UTF_8))
                    )
                }

                symlinks.put(
                    outputPath,
                    StripPrefixedPath.Companion.maybeDeprefixSymlink(buffer, prefix, destinationDirectory)
                )
            } else {
                reader.getInputStream(entry).use { input ->
                    outputPath.getOutputStream().use { output ->
                        ByteStreams.copy(input, output)
                        if (Thread.interrupted()) {
                            throw InterruptedException()
                        }
                    }
                }
                // Ensure that all files are at least user-readable. Some archives contain files that
                // are not, but many other tools are working around this and thus mask these issues.
                outputPath.chmod(permissions or 256)
                outputPath.setLastModifiedTime(entry.time)
            }
        }

        @kotlin.jvm.JvmStatic
        @VisibleForTesting
        @Throws(IOException::class)
        fun getPermissions(permissions: Int, path: kotlin.String): Int {
            // Sometimes zip files list directories as being "regular" executable files (i.e., 0100755).
            // I'm looking at you, Go AppEngine SDK 1.9.37 (see #1263 for details).
            if (path.endsWith("/")) {
                return S_IFDIR or EXECUTABLE_MASK
            }

            // Posix permissions are in the high-order 2 bytes of the external attributes. After this
            // operation, permissions holds 0100755 (or 040755 for directories).
            val shiftedPermissions = permissions ushr 16
            if (shiftedPermissions != 0) {
                return shiftedPermissions
            }

            // If this was zipped up on FAT, it won't have posix permissions set. Instead, this
            // checks if extra attributes is set to 0 for files. From
            // https://github.com/miloyip/rapidjson/archive/v1.0.2.zip, it looks like executables end up
            // with "normal" (posix) permissions (oddly), so they'll be handled above.
            val windowsPermission = permissions and 0xff
            if ((windowsPermission and WINDOWS_FILE_ATTRIBUTE_DIRECTORY)
                == WINDOWS_FILE_ATTRIBUTE_DIRECTORY
            ) {
                // Directory.
                return S_IFDIR or EXECUTABLE_MASK
            } else if (permissions == 0 || (windowsPermission and WINDOWS_FILE_ATTRIBUTE_ARCHIVE) == WINDOWS_FILE_ATTRIBUTE_ARCHIVE || (windowsPermission and WINDOWS_FILE_ATTRIBUTE_NORMAL) == WINDOWS_FILE_ATTRIBUTE_NORMAL) {
                // File.
                return S_IFREG or EXECUTABLE_MASK
            }

            // No idea.
            throw IOException(
                ("Unrecognized file mode for " + path + ": 0x"
                        + Integer.toHexString(permissions))
            )
        }
    }
}
