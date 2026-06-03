// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.ActionExecutionContext

/**
 * Action to write a file whose contents are known at analysis time.
 * 
 * 
 * The output file is generally encoded as UTF-8, but by an unusual path. BUILD files and
 * directory entries, which are actually UTF-8, are misinterpreted by Bazel as Latin1, so that most
 * Strings within the build language use this unusual representation. FileWriteAction writes those
 * Strings out again as Latin1.
 * 
 * 
 * The contents may be lazily computed or compressed. If the object representing the contents is
 * a `String`, its length is greater than `COMPRESS_CHARS_THRESHOLD`, and compression is
 * enabled, then the gzipped bytestream of the contents will be stored in place of the string
 * itself. This compression is transparent and does not affect the output file.
 * 
 * 
 * Otherwise, if the object represents a lazy computation, it will not be forced until [ ][.getFileContents] is called. An example where this may come in handy is if the contents are the
 * concatenation of the string representations of a series of artifacts. Then the client code can
 * wrap a `List<Artifact>` in a [com.google.devtools.build.lib.util.OnDemandString],
 * which saves memory since the artifacts are shared objects whereas a string is not.
 * 
 * 
 * TODO(b/146554973): Change this implementation when that is addressed.
 * 
 * 
 * TODO(bazel-team): Choose a better name to distinguish this class from [ ].
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable // if fileContents is immutable
abstract class FileWriteAction private constructor(
    owner: ActionOwner?,
    inputs: NestedSet<Artifact?>?,
    primaryOutput: Artifact?,
    private val makeExecutable: Boolean,
    private val mnemonic: String?
) : AbstractFileWriteAction(owner, inputs, primaryOutput), FileContentsProvider {
    public override fun getFileContents(eventHandler: com.google.devtools.build.lib.events.EventHandler?): String? {
        return getFileContents()
    }

    override fun makeExecutable(): Boolean {
        return makeExecutable
    }

    override fun getMnemonic(): String? {
        return mnemonic
    }

    /**
     * Returns the string contents to be written.
     * 
     * 
     * Note that if the string is lazily computed or compressed, calling this method will force its
     * computation or decompression. No attempt is made by FileWriteAction to cache the result.
     * 
     * 
     * Note that the content is a not a normal Java String. When Bazel parses BUILD files, it
     * misinterprets the bytes as Latin1, so a code point with a 3-byte UTF-8 encoding will take 3
     * chars internally. To reverse this process, you must encode this string as Latin1, giving you
     * back the correct UTF-8 encoding of the original input.
     */
    abstract fun getFileContents(): String?

    public override fun getStarlarkContent(): String? {
        return getFileContents()
    }

    private class RegularFileWriteAction(
        owner: ActionOwner?,
        inputs: NestedSet<Artifact?>?,
        primaryOutput: Artifact?,
        makeExecutable: Boolean,
        mnemonic: String?,
        private val fileContents: CharSequence
    ) : FileWriteAction(owner, inputs, primaryOutput, makeExecutable, mnemonic) {
        override fun getFileContents(): String? {
            return fileContents.toString()
        }

        override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter {
            return DeterministicWriter { out -> out.write(StringUnsafe.getInternalStringBytes(getFileContents())) }
        }

        protected override fun computeKey(
            actionKeyContext: ActionKeyContext?,
            inputMetadataProvider: InputMetadataProvider?,
            fp: Fingerprint
        ) {
            fp.addString(GUID).addBoolean(makeExecutable()).addString(getFileContents())
        }

        companion object {
            private const val GUID = "332877c7-ca9f-4731-b387-54f620408522"
        }
    }

    private class CompressedFileWriteAction(
        owner: ActionOwner?,
        inputs: NestedSet<Artifact?>?,
        primaryOutput: Artifact?,
        makeExecutable: Boolean,
        mnemonic: String?,
        fileContents: String?
    ) : FileWriteAction(owner, inputs, primaryOutput, makeExecutable, mnemonic) {
        private val compressedBytes: ByteArray
        private val uncompressedSize: Int

        init {
            // Grab the string's internal byte array. Calling getBytes() makes a copy, which can cause
            // memory spikes resulting in OOMs (b/290807073). Do not mutate this!
            val dataToCompress: ByteArray = StringUnsafe.getInternalStringBytes(fileContents)

            // Empirically, compressed sizes range from roughly 1/100 to 3/4 of the uncompressed size.
            // Presize on the small end to avoid over-allocating memory.
            val byteStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream(dataToCompress.size / 100)

            try {
                GZIPOutputStream(byteStream, GZIP_BYTES_BUFFER).use { zipStream ->
                    zipStream.write(dataToCompress)
                }
            } catch (e: IOException) {
                // This should be impossible since we're writing to a byte array.
                throw java.lang.IllegalStateException(e)
            }

            this.compressedBytes = byteStream.toByteArray()
            this.uncompressedSize = dataToCompress.size
        }

        override fun getFileContents(): String {
            val uncompressedBytes = ByteArray(uncompressedSize)
            try {
                GZIPInputStream(ByteArrayInputStream(compressedBytes), GZIP_BYTES_BUFFER).use { zipStream ->
                    var read: Int
                    var totalRead = 0
                    while (totalRead < uncompressedSize
                        && ((zipStream.read(uncompressedBytes, totalRead, uncompressedSize - totalRead)
                            .also { read = it })
                                != -1)
                    ) {
                        totalRead += read
                    }
                    com.google.common.base.Preconditions.checkState(
                        totalRead == uncompressedSize,
                        "Corrupt byte buffer in FileWriteAction"
                    )
                }
            } catch (e: IOException) {
                // This should be impossible since we're reading from a byte array.
                throw java.lang.IllegalStateException(e)
            }

            return StringUnsafe.newInstance(uncompressedBytes, StringUnsafe.LATIN1)
        }

        override fun newDeterministicWriter(ctx: ActionExecutionContext?): DeterministicWriter {
            return DeterministicWriter { out ->
                GZIPInputStream(ByteArrayInputStream(compressedBytes), GZIP_BYTES_BUFFER).use { gzipIn ->
                    com.google.common.io.ByteStreams.copy(gzipIn, out)
                }
            }
        }

        protected override fun computeKey(
            actionKeyContext: ActionKeyContext?,
            inputMetadataProvider: InputMetadataProvider?,
            fp: Fingerprint
        ) {
            fp.addString(GUID).addBoolean(makeExecutable()).addBytes(compressedBytes)
        }

        companion object {
            private const val GUID = "5bfba914-2251-11ee-be56-0242ac120002"
            private const val GZIP_BYTES_BUFFER = 8192
        }
    }

    companion object {
        /** Minimum length (in chars) for content to be eligible for compression.  */
        private const val COMPRESS_CHARS_THRESHOLD = 256

        /**
         * Creates a FileWriteAction to write contents to the resulting artifact fileName in the genfiles
         * root underneath the package path.
         * 
         * @param ruleContext the ruleContext that will own the action of creating this file
         * @param fileName name of the file to create
         * @param contents data to write to file
         * @param executable flags that file should be marked executable
         * @return Artifact describing the file to create
         */
        fun createFile(
            ruleContext: RuleContext, fileName: String?, contents: CharSequence, executable: Boolean
        ): Artifact? {
            val scriptFileArtifact: Artifact? =
                ruleContext.getPackageRelativeArtifact(fileName, ruleContext.getGenfilesDirectory())
            ruleContext.registerAction(
                Companion.create(
                    ruleContext,
                    scriptFileArtifact,
                    contents,
                    executable,
                    AbstractFileWriteAction.Companion.MNEMONIC
                )
            )
            return scriptFileArtifact
        }

        /**
         * Creates a new FileWriteAction instance with inputs and empty content.
         * 
         * 
         * This is useful for producing an artifact that, if built, will ensure that the generating
         * actions for its inputs are run. The output file is non-executable.
         * 
         * @param owner the action owner
         * @param inputs the Artifacts that this Action depends on
         * @param output the Artifact that will be created by executing this Action
         */
        fun createEmptyWithInputs(
            owner: ActionOwner?, inputs: NestedSet<Artifact?>?, output: Artifact?
        ): FileWriteAction {
            return createInternal(
                owner,
                inputs,
                output,
                "",
                false,
                com.google.devtools.build.lib.analysis.actions.Compression.DISALLOW,
                AbstractFileWriteAction.Companion.MNEMONIC
            )
        }

        /**
         * Creates a new FileWriteAction instance with direct control over whether or not transparent
         * compression may be used.
         * 
         * @param owner the action owner
         * @param output the Artifact that will be created by executing this Action
         * @param fileContents the contents to be written to the file
         * @param makeExecutable whether the output file is made executable
         * @param allowCompression whether (transparent) compression is enabled
         */
        fun create(
            owner: ActionOwner?,
            output: Artifact?,
            fileContents: CharSequence,
            makeExecutable: Boolean,
            allowCompression: com.google.devtools.build.lib.analysis.actions.Compression?
        ): FileWriteAction {
            return createInternal(
                owner,
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                output,
                fileContents,
                makeExecutable,
                allowCompression,
                AbstractFileWriteAction.Companion.MNEMONIC
            )
        }

        /**
         * Creates a new FileWriteAction instance.
         * 
         * 
         * There are no inputs. No reference to the [ActionConstructionContext] will be
         * maintained.
         * 
         * @param context the action construction context
         * @param output the Artifact that will be created by executing this Action
         * @param fileContents the contents to be written to the file
         * @param makeExecutable whether the output file is made executable
         */
        fun create(
            context: ActionConstructionContext,
            output: Artifact?,
            fileContents: CharSequence,
            makeExecutable: Boolean
        ): FileWriteAction {
            return Companion.create(
                context,
                output,
                fileContents,
                makeExecutable,
                AbstractFileWriteAction.Companion.MNEMONIC
            )
        }

        /**
         * Creates a new FileWriteAction instance.
         * 
         * 
         * There are no inputs. No reference to the [ActionConstructionContext] will be
         * maintained.
         * 
         * @param context the action construction context
         * @param output the Artifact that will be created by executing this Action
         * @param fileContents the contents to be written to the file
         * @param makeExecutable whether the output file is made executable
         * @param mnemonic an optional custom mnemonic for the action, or null to use the default
         */
        fun create(
            context: ActionConstructionContext,
            output: Artifact?,
            fileContents: CharSequence,
            makeExecutable: Boolean,
            mnemonic: String?
        ): FileWriteAction {
            return createInternal(
                context.getActionOwner(),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                output,
                fileContents,
                makeExecutable,
                com.google.devtools.build.lib.analysis.actions.Compression.ALLOW,
                mnemonic
            )
        }

        private fun createInternal(
            owner: ActionOwner?,
            inputs: NestedSet<Artifact?>?,
            output: Artifact?,
            fileContents: CharSequence,
            makeExecutable: Boolean,
            allowCompression: com.google.devtools.build.lib.analysis.actions.Compression?,
            mnemonic: String?
        ): FileWriteAction {
            if (allowCompression == com.google.devtools.build.lib.analysis.actions.Compression.ALLOW && fileContents is String
                && fileContents.length() > COMPRESS_CHARS_THRESHOLD
            ) {
                return CompressedFileWriteAction(
                    owner, inputs, output, makeExecutable, mnemonic, fileContents
                )
            }
            return RegularFileWriteAction(
                owner, inputs, output, makeExecutable, mnemonic, fileContents
            )
        }
    }
}
