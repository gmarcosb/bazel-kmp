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

import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.bazel.repository.RepositoryFunctionException
import com.google.devtools.build.lib.util.Pair
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.skyframe.SkyFunctionException.Transience
import com.google.devtools.build.skyframe.SkyValue
import com.google.errorprone.annotations.CanIgnoreReturnValue
import net.starlark.java.eval.Starlark
import java.io.IOException
import java.lang.String
import java.nio.channels.ClosedByInterruptException
import java.util.*
import java.util.function.Function
import java.util.function.Predicate
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.plus

/**
 * The contents of decompressed archive.
 */
class DecompressorValue(private val directory: Path) : SkyValue {
    /** Implementation of a decompression algorithm.  */
    interface Decompressor {
        /** Exception reporting about absence of an expected prefix in an archive.  */
        class CouldNotFindPrefixException internal constructor(prefix: String, availablePrefixes: MutableSet<String?>) :
            IOException(
                prepareErrorMessage(prefix, availablePrefixes)
            ) {
            companion object {
                private fun prepareErrorMessage(prefix: String, availablePrefixes: MutableSet<String?>): String {
                    val error = "Prefix \"" + prefix + "\" was given, but not found in the archive. "
                    var suggestion = "Here are possible prefixes for this archive: "
                    var suggestionBody = ""

                    if (availablePrefixes.isEmpty()) {
                        suggestion =
                            ("We could not find any directory in this archive"
                                    + " (maybe there is no need for `strip_prefix`?)")
                    } else {
                        // Add a list of possible suggestion wrapped with `"` and separated by `, `.
                        suggestionBody = "\"" + String.join("\", \"", availablePrefixes) + "\"."
                    }

                    return error + suggestion + suggestionBody
                }

                fun maybeMakePrefixSuggestion(pathFragment: PathFragment): Optional<kotlin.String?> {
                    if (!pathFragment.isMultiSegment()) {
                        return Optional.empty<kotlin.String?>()
                    }
                    return Optional.of<kotlin.String?>(pathFragment.getSegment(0))
                }
            }
        }

        @Throws(IOException::class, RepositoryFunctionException::class, InterruptedException::class)
        fun decompress(descriptor: DecompressorDescriptor?): Path?
    }

    override fun equals(other: Any?): Boolean {
        return this === other
                || (other is DecompressorValue
                && directory == other.directory)
    }

    override fun hashCode(): Int {
        return directory.hashCode()
    }

    companion object {
        private val ZIP_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.of<kotlin.String?>("zip", "jar", "war", "aar", "nupkg", "whl")

        private val TAR_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("tar").build()

        private val TAR_GZIP_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("tar.gz").add("tgz").build()

        private val GZIP_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("gz").build()

        private val TAR_XZ_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("tar.xz").add("txz").build()

        private val XZ_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("xz").build()

        private val TAR_ZST_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("tar.zst").add("tzst").build()

        private val ZST_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("zst").build()

        private val TAR_BZ2_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("tar.bz2").add("tbz").build()

        private val BZ2_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("bz2").build()

        private val AR_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("ar").add("deb").build()

        private val SEVENZ_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("7z").build()

        private val TAR_BR_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("tar.br").build()

        private val BROTLI_FORMATS: ImmutableList<kotlin.String?> =
            ImmutableList.builder<kotlin.String?>().add("br").build()

        // List of supported compressor format file extensions with their corresponding Decompressor
        // instance. The order here is intentional and is the order in which a decompressor is searched
        // for.
        private val supportedFormats: ImmutableList<Pair<ImmutableList<kotlin.String?>?, Decompressor?>> =
            ImmutableList.builder<Pair<ImmutableList<kotlin.String?>?, Decompressor?>?>()
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        ZIP_FORMATS,
                        ZipDecompressor.Companion.INSTANCE
                    )
                )
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        TAR_FORMATS,
                        TarFunction.Companion.INSTANCE
                    )
                )
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        TAR_GZIP_FORMATS,
                        TarGzFunction.Companion.INSTANCE
                    )
                )
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        GZIP_FORMATS,
                        GzFunction.Companion.INSTANCE
                    )
                )
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        TAR_XZ_FORMATS,
                        TarXzFunction.Companion.INSTANCE
                    )
                )
                .add(Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(XZ_FORMATS, XzFunction.Companion.INSTANCE))
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        TAR_ZST_FORMATS,
                        TarZstFunction.Companion.INSTANCE
                    )
                )
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        ZST_FORMATS,
                        ZstFunction.Companion.INSTANCE
                    )
                )
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        TAR_BZ2_FORMATS,
                        TarBz2Function.Companion.INSTANCE
                    )
                )
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        BZ2_FORMATS,
                        Bz2Function.Companion.INSTANCE
                    )
                )
                .add(Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(AR_FORMATS, ArFunction.Companion.INSTANCE))
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        SEVENZ_FORMATS,
                        SevenZDecompressor.Companion.INSTANCE
                    )
                )
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        TAR_BR_FORMATS,
                        TarBrFunction.Companion.INSTANCE
                    )
                )
                .add(
                    Pair.of<ImmutableList<kotlin.String?>?, Decompressor?>(
                        BROTLI_FORMATS,
                        BrFunction.Companion.INSTANCE
                    )
                )
                .build()

        /**
         * Returns a human-readable string of supported decompressor extensions separated by commas.
         * 
         * 
         * The resulting string looks like:
         * 
         * 
         * `
         * [prefix][extension][suffix], [prefix][extension2][suffix] [conjunction] [prefix][extension3][suffix]
        ` * 
         * 
         * 
         * Examples:
         * 
         * 
         *  * No prefix/suffix and with the conjunction "and": `jar, zip, whl, tgz and ar`
         *  * Dot prefix and conjunction "or": `.jar, .zip, .whl, .tgz or .ar`
         *  * Quote+dot prefix, quote suffix and conjunction "or": `` 
         * `.jar`, `.zip`, `.whl`, `.tgz` or `.ar` ``
         * 
         */
        @kotlin.jvm.JvmStatic
        fun readableSupportedFormats(
            prefix: kotlin.String?,
            suffix: kotlin.String?,
            conjunction: kotlin.String?
        ): kotlin.String {
            val allExtensions: ImmutableList<kotlin.String?> = allSupportedExtensions(prefix, suffix)

            val commaSeparatedExtensions =
                String.join(", ", allExtensions.subList(0, allExtensions.size() - 1))

            return commaSeparatedExtensions + " " + conjunction + " " + allExtensions.getLast()
        }

        @kotlin.jvm.JvmStatic
        fun allSupportedExtensions(prefix: kotlin.String?, suffix: kotlin.String?): ImmutableList<kotlin.String?> {
            return supportedFormats.stream()
                .map<ImmutableList<kotlin.String?>?>(Function { format: Pair<ImmutableList<kotlin.String?>?, Decompressor?>? -> format!!.first })
                .flatMap<kotlin.String?>(Function { obj: ImmutableList<kotlin.String?>? -> obj!!.stream() })
                .map<kotlin.String?>(Function { type: kotlin.String? -> prefix + type + suffix })
                .collect(ImmutableList.toImmutableList<kotlin.String?>())
        }

        @kotlin.jvm.JvmStatic
        @VisibleForTesting
        @Throws(RepositoryFunctionException::class)
        fun getDecompressor(archivePath: Path): Decompressor? {
            val baseName = archivePath.getBaseName()
            // Return the corresponding decompressor if the archive's basename ends with a matching
            // extension. Eg. If the file ends in .tar.gz or .tgz, use the TarGzFunction decompressor.
            for (format in supportedFormats) {
                val fileExtensions = format.first
                val decompressor = format.second
                if (fileExtensions!!.stream().map<kotlin.String?>(Function { type: kotlin.String? -> "." + type })
                        .anyMatch(
                            Predicate { ext: kotlin.String? -> baseName.endsWith(ext) })
                ) {
                    return decompressor
                }
            }

            throw RepositoryFunctionException(
                Starlark.errorf(
                    "Expected a file with a %s suffix (got %s)",
                    readableSupportedFormats( /* prefix= */".",  /* suffix= */"",  /* conjunction= */"or"),
                    archivePath
                ),
                Transience.PERSISTENT
            )
        }

        /**
         * Returns a decompressor based on the file type extension.
         * 
         * 
         * Example: `getDecompressor("tar.gz")` returns [TarGzFunction].
         * 
         * 
         * The type should NOT have a dot prefix.
         * 
         * 
         * Bad Example: `getDecompressor(".tar.gz")` throws [RepositoryFunctionException].
         */
        @kotlin.jvm.JvmStatic
        @Throws(RepositoryFunctionException::class)
        fun getDecompressor(type: kotlin.String): Decompressor? {
            for (format in supportedFormats) {
                val fileExtensions = format.first
                val decompressor = format.second
                if (fileExtensions!!.stream()
                        .anyMatch(Predicate { anObject: kotlin.String? -> type.equals(anObject) })
                ) {
                    return decompressor
                }
            }
            throw RepositoryFunctionException(
                Starlark.errorf(
                    "No decompressor found for type %s. Available types are: %s",
                    type,
                    readableSupportedFormats( /* prefix= */"",  /* suffix= */"",  /* conjunction= */"or")
                ),
                Transience.PERSISTENT
            )
        }

        @kotlin.jvm.JvmStatic
        @CanIgnoreReturnValue
        @Throws(RepositoryFunctionException::class, InterruptedException::class)
        fun decompress(descriptor: DecompressorDescriptor): Path? {
            return Companion.decompress(descriptor,  /* forceDecompressorType= */Optional.empty<kotlin.String?>())
        }

        /**
         * Decompresses an archive according to [DecompressorDescriptor].
         * 
         * @param descriptor contains the information on the archive to decompress, where to decompress to
         * and any post-decompression actions to take.
         * @param forceDecompressorType interpret the archive as this type (eg. "zip" to treat the archive
         * file as a zipped file). If `forceDecompressorType` is given, the specified
         * decompressor will be used, otherwise, the decompressor will use the archive's file
         * extension to determine an appropriate decompressor.
         */
        @CanIgnoreReturnValue
        @Throws(RepositoryFunctionException::class, InterruptedException::class)
        fun decompress(
            descriptor: DecompressorDescriptor, forceDecompressorType: Optional<kotlin.String?>
        ): Path? {
            try {
                if (forceDecompressorType.isPresent()) {
                    return getDecompressor(forceDecompressorType.get())!!.decompress(descriptor)
                } else {
                    return getDecompressor(descriptor.archivePath)!!.decompress(descriptor)
                }
            } catch (e: ClosedByInterruptException) {
                val ie = InterruptedException()
                ie.initCause(e)
                throw ie
            } catch (e: IOException) {
                val destinationDirectory = descriptor.archivePath.getParentDirectory()
                throw RepositoryFunctionException(
                    IOException(
                        String.format(
                            "Error extracting %s to %s: %s",
                            descriptor.archivePath, destinationDirectory, e.getMessage()
                        ),
                        e
                    ),
                    Transience.TRANSIENT
                )
            }
        }
    }
}
