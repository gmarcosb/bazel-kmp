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

import com.google.auto.service.AutoService
import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.bazel.repository.decompressor.DecompressorValue.Decompressor.CouldNotFindPrefixException
import com.google.devtools.build.lib.util.StringEncoding
import com.google.devtools.build.lib.vfs.FileSystemUtils
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.String
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.*
import java.nio.charset.spi.CharsetProvider
import java.util.*
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.Boolean
import kotlin.UnsupportedOperationException
import kotlin.arrayOfNulls

/**
 * Common code for unarchiving a compressed TAR file.
 * 
 * 
 * TAR file entries commonly use one of two formats: PAX, which uses UTF-8 encoding for all
 * strings, and USTAR, which does not specify an encoding. This class interprets USTAR headers as
 * latin-1, thus preserving the original bytes of the header without enforcing any particular
 * encoding. Internally, for file system operations, all strings are converted into Bazel's internal
 * representation of raw bytes stored as latin-1 strings.
 */
abstract class CompressedTarFunction : DecompressorValue.Decompressor {
    @Throws(IOException::class)
    protected abstract fun getDecompressorStream(compressedInputStream: BufferedInputStream?): InputStream?

    @Throws(InterruptedException::class, IOException::class)
    override fun decompress(descriptor: DecompressorDescriptor): Path? {
        if (Thread.interrupted()) {
            throw InterruptedException()
        }
        val prefix = descriptor.prefix
        val renameFiles: MutableMap<String?, String?> = descriptor.renameFiles
        var foundPrefix = false
        val availablePrefixes: MutableSet<String?> = HashSet<String?>()
        // Store link, target info of symlinks, we create them after regular files are extracted.
        val symlinks: MutableMap<Path?, PathFragment?> = HashMap<Path?, PathFragment?>()

        descriptor.archivePath.getInputStream().use { compressedInputStream ->
            getDecompressorStream(BufferedInputStream(compressedInputStream, BUFFER_SIZE)).use { decompressorStream ->
                // USTAR tar headers use an unspecified encoding whereas PAX tar headers always use UTF-8.
                // We can specify the encoding to use for USTAR headers, but the Charset used for PAX headers
                // is fixed to UTF-8. We thus specify a custom Charset for the former so that we can
                // distinguish between the two.
                val tarStream =
                    TarArchiveInputStream(decompressorStream, MarkedIso88591Charset.Companion.NAME)
                var entry: TarArchiveEntry?
                while ((tarStream.getNextTarEntry().also { entry = it }) != null) {
                    var entryName: String? = toRawBytesString(entry!!.getName())
                    entryName = renameFiles.getOrDefault(entryName, entryName)
                    val entryPath: StripPrefixedPath =
                        StripPrefixedPath.Companion.maybeDeprefix(
                            entryName.getBytes(StandardCharsets.ISO_8859_1),
                            prefix
                        )
                    foundPrefix = foundPrefix || entryPath.foundPrefix()

                    if (prefix.isPresent() && !foundPrefix) {
                        CouldNotFindPrefixException.Companion.maybeMakePrefixSuggestion(entryPath.getPathFragment())
                            .ifPresent(Consumer { e: String? -> availablePrefixes.add(e) })
                    }

                    if (entryPath.skip()) {
                        continue
                    }

                    var strippedRelativePath = entryPath.getPathFragment()
                    if (strippedRelativePath.isAbsolute()) {
                        throw IOException(
                            String.format(
                                "Failed to extract %s, tarred paths cannot be absolute", strippedRelativePath
                            )
                        )
                    }

                    strippedRelativePath = strippedRelativePath.stripComponents(descriptor.stripComponents)
                    if (strippedRelativePath == PathFragment.EMPTY_FRAGMENT) {
                        continue
                    }

                    val filePath = descriptor.destinationPath.getRelative(strippedRelativePath)
                    if (!filePath.startsWith(descriptor.destinationPath)) {
                        throw IOException(
                            String.format(
                                "Failed to extract %s, path is escaping the destination directory",
                                strippedRelativePath
                            )
                        )
                    }
                    filePath.getParentDirectory()!!.createDirectoryAndParents()
                    if (entry.isDirectory()) {
                        filePath.createDirectoryAndParents()
                    } else {
                        if (entry.isSymbolicLink() || entry.isLink()) {
                            val targetName: PathFragment =
                                StripPrefixedPath.Companion.maybeDeprefixSymlink(
                                    toRawBytesString(entry.getLinkName()).getBytes(StandardCharsets.ISO_8859_1),
                                    prefix,
                                    descriptor.destinationPath
                                )

                            val resolvedTargetPath =
                                (if (entry.isSymbolicLink())
                                    filePath.getParentDirectory()
                                else
                                    descriptor.destinationPath)!!
                                    .getRelative(targetName)
                            if (!targetName.isAbsolute()
                                && !resolvedTargetPath.startsWith(descriptor.destinationPath)
                            ) {
                                throw IOException(
                                    String.format(
                                        "Tar entries cannot refer to files outside of their directory: %s has a"
                                                + " link %s pointing to %s",
                                        descriptor.archivePath, entryName, targetName
                                    )
                                )
                            }

                            if (entry.isSymbolicLink()) {
                                symlinks.put(filePath, targetName)
                            } else {
                                if (filePath == resolvedTargetPath) {
                                    // The behavior here is semantically different, depending on whether the underlying
                                    // filesystem is case-sensitive or case-insensitive. However, it is effectively the
                                    // same: we drop the link entry.
                                    // * On a case-sensitive filesystem, this is a hardlink to itself, such as GNU tar
                                    //   creates when given repeated files. We do nothing since the link already exists.
                                    // * On a case-insensitive filesystem, we may be extracting a differently-cased
                                    //   hardlink to the same file (such as when extracting an archive created on a
                                    //   case-sensitive filesystem). GNU tar, for example, will drop the new link entry.
                                    //   BSD tar on MacOS X (by default case-insensitive) errors and aborts extraction.
                                } else {
                                    if (filePath.exists()) {
                                        filePath.delete()
                                    }
                                    FileSystemUtils.createHardLink(filePath, resolvedTargetPath)
                                }
                            }
                        } else {
                            filePath.getOutputStream().use { out ->
                                ByteStreams.copy(tarStream, out)
                            }
                            // Ensure that all files are at least user-readable. Some archives contain files that
                            // are not, but many other tools are working around this and thus mask these issues.
                            filePath.chmod(entry.getMode() or 256)

                            // This can only be done on real files, not links, or it will skip the reader to
                            // the next "real" file to try to find the mod time info.
                            val lastModified = entry.getLastModifiedDate()
                            filePath.setLastModifiedTime(lastModified.getTime())
                        }
                    }
                    if (Thread.interrupted()) {
                        throw InterruptedException()
                    }
                }

                for (symlink in symlinks.entrySet()) {
                    val linkPath: Path = symlink.getKey()
                    if (linkPath.exists()) {
                        linkPath.delete()
                    }
                    FileSystemUtils.ensureSymbolicLink(linkPath, symlink.getValue())
                }
                if (prefix.isPresent() && !foundPrefix) {
                    throw CouldNotFindPrefixException(prefix.get(), availablePrefixes)
                }
            }
        }
        return descriptor.destinationPath
    }

    /** A provider of [MarkedIso88591Charset]s.  */
    @AutoService(CharsetProvider::class)
    class MarkedIso88591CharsetProvider : CharsetProvider() {
        override fun charsets(): MutableIterator<Charset?>? {
            // This charset is only meant for internal use within CompressedTarFunction and thus should
            // not be discoverable.
            return Collections.emptyIterator<Charset?>()
        }

        override fun charsetForName(charsetName: kotlin.String?): Charset? {
            return if (MarkedIso88591Charset.Companion.NAME == charsetName) CHARSET else null
        }

        companion object {
            private val CHARSET: Charset = MarkedIso88591Charset()
        }
    }

    /**
     * A charset that decodes ISO-8859-1, i.e., produces a String that contains the raw decoded bytes,
     * and appends a marker to the end of the string to indicate that it was decoded with this
     * charset.
     */
    private class MarkedIso88591Charset : Charset(NAME, arrayOfNulls<kotlin.String>(0)) {
        override fun newDecoder(): CharsetDecoder {
            return object : CharsetDecoder(this, 1f, 1f) {
                override fun decodeLoop(`in`: ByteBuffer, out: CharBuffer): CoderResult? {
                    // A simple unoptimized ISO-8859-1 decoder.
                    while (`in`.hasRemaining()) {
                        if (!out.hasRemaining()) {
                            return CoderResult.OVERFLOW
                        }
                        out.put((`in`.get().toInt() and 0xFF).toChar())
                    }
                    return CoderResult.UNDERFLOW
                }

                override fun implFlush(out: CharBuffer): CoderResult? {
                    // Append the marker to the end of the buffer to indicate that it was decoded with this
                    // charset.
                    if (out.remaining() < NAME.length()) {
                        return CoderResult.OVERFLOW
                    }
                    out.put(NAME)
                    return CoderResult.UNDERFLOW
                }
            }
        }

        override fun newEncoder(): CharsetEncoder? {
            throw UnsupportedOperationException()
        }

        override fun contains(cs: Charset?): Boolean {
            return false
        }

        companion object {
            // The name
            // * must not collide with the name of any other charset.
            // * must not appear in archive entry names by chance.
            // * is internal to CompressedTarFunction.
            // This is best served by a cryptographically random UUID, generated at startup.
            private val NAME = UUID.randomUUID().toString()

            fun getRawBytesStringIfMarked(s: kotlin.String): Optional<kotlin.String?> {
                // Check for the marker in all positions as TarArchiveInputStream manipulates the raw name in
                // certain cases (for example, appending a '/' to directory names).
                if (s.contains(NAME)) {
                    return Optional.of<kotlin.String?>(s.replaceAll(NAME, ""))
                }
                return Optional.empty<kotlin.String?>()
            }
        }
    }

    companion object {
        private val BUFFER_SIZE = 32 * 1024

        /**
         * Returns a string that contains the raw bytes of the given string encoded in ISO-8859-1,
         * assuming that the given string was encoded with either UTF-8 or the special [ ].
         */
        private fun toRawBytesString(name: kotlin.String): kotlin.String? {
            // Marked strings are already encoded in ISO-8859-1. Other strings originate from PAX headers
            // and are thus Unicode.
            return MarkedIso88591Charset.Companion.getRawBytesStringIfMarked(name)
                .orElseGet(Supplier { StringEncoding.unicodeToInternal(name) })
        }
    }
}
