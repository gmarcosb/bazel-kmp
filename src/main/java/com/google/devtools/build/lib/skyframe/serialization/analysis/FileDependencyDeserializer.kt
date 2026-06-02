// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.protobuf.ExtensionRegistry.getEmptyRegistry

/**
 * Deserializes dependency information persisted by [FileDependencySerializer].
 * 
 * 
 * Fetching a file dependency is a mostly linear asynchronous state machine that performs actions
 * then waits in an alternating manner.
 * 
 * 
 *  1. Request the data for a given key.
 *  1. [WaitForFileInvalidationData].
 *  1. Request the data for the parent directory (a recursive call).
 *  1. [WaitForParent].
 *  1. Process any symlinks, resolving symlink parents as needed.
 *  1. [WaitForSymlinkParent].
 *  1. Processing symlinks repeats for all the symlinks associated with an entry.
 * 
 * 
 * 
 * A similar, but simpler state machine is used for directory listings.
 * 
 * 
 *  1. Request the data for a given key.
 *  1. [WaitForListingInvalidationData].
 *  1. Request the file data corresponding to the directory (delegating to [       ][.getFileDependencies]).
 *  1. [WaitForListingFileDependencies].
 *  1. Create and cache the [ListingDependencies] instance.
 * 
 */
internal class FileDependencyDeserializer(executor: java.util.concurrent.Executor, fingerprinter: Fingerprinter) {
    private val executor: java.util.concurrent.Executor
    private val fingerprinter: Fingerprinter

    /**
     * A cache for [FileDependencies], primarily for deduplication.
     * 
     * 
     * The cache keys are as described at [FileInvalidationData]. We can potentially strip
     * the version information here, but keeping the version enables a single [ ] instance to be shared across disparate builds.
     * 
     * 
     * While in-flight, the value has type [FutureFileDependencies], which is replaced by
     * [FileDependencies] once the computation completes.
     * 
     * 
     * References to [FileDependencies] form DAGs where certain top-level entries are
     * retained by the `SkyValue`s that depend on them. When all such associated `SkyValue`s are invalidated, the dependency information becomes eligible for GC.
     */
    private val fileCache: DependencyMap<String?, FileDependenciesOrFuture?, FileDependencies?, FutureFileDependencies?> =
        DependencyMap<kotlin.String?, FileDependenciesOrFuture?, FileDependencies?, FutureFileDependencies?>(
            Caffeine.newBuilder().weakValues().build<String?, FileDependenciesOrFuture?>().asMap(),
            java.util.function.BiFunction { key: String?, consumer: java.util.function.BiConsumer<kotlin.String?, FileDependencies?>? ->
                FutureFileDependencies(
                    key,
                    consumer
                )
            },
            FutureFileDependencies::class.java,
            java.util.function.BiFunction { ownedFuture: FutureFileDependencies?, store: FingerprintValueStore? ->
                this@FileDependencyDeserializer.populateFutureFileDependencies(
                    ownedFuture!!,
                    store
                )
            })

    /**
     * A cache for [ListingDependencies], primarily for deduplication.
     * 
     * 
     * This follows the design of [.fileCache] but is for directory listings.
     */
    private val listingCache: DependencyMap<String?, ListingDependenciesOrFuture?, ListingDependencies?, FutureListingDependencies?> =
        DependencyMap<kotlin.String?, ListingDependenciesOrFuture?, ListingDependencies?, FutureListingDependencies?>(
            Caffeine.newBuilder()
                .weakValues()
                .build<String?, ListingDependenciesOrFuture?>()
                .asMap(),
            java.util.function.BiFunction { key: String?, consumer: java.util.function.BiConsumer<kotlin.String?, ListingDependencies?>? ->
                FutureListingDependencies(
                    key,
                    consumer
                )
            },
            FutureListingDependencies::class.java,
            java.util.function.BiFunction { ownedFuture: FutureListingDependencies?, store: FingerprintValueStore? ->
                this@FileDependencyDeserializer.populateFutureListingDependencies(
                    ownedFuture!!,
                    store
                )
            })

    private val nestedCache: DependencyMap<PackedFingerprint?, NestedDependenciesOrFuture?, NestedDependencies?, FutureNestedDependencies?> =
        DependencyMap<PackedFingerprint?, NestedDependenciesOrFuture?, NestedDependencies?, FutureNestedDependencies?>(
            Caffeine.newBuilder()
                .weakValues()
                .build<PackedFingerprint?, NestedDependenciesOrFuture?>()
                .asMap(),
            java.util.function.BiFunction { key: PackedFingerprint?, consumer: java.util.function.BiConsumer<PackedFingerprint?, NestedDependencies?>? ->
                FutureNestedDependencies(
                    key,
                    consumer
                )
            },
            FutureNestedDependencies::class.java,
            java.util.function.BiFunction { ownedFuture: FutureNestedDependencies?, store: FingerprintValueStore? ->
                this@FileDependencyDeserializer.populateFutureNestedDependencies(
                    ownedFuture!!,
                    store
                )
            })

    init {
        this.executor = executor
        this.fingerprinter = fingerprinter
    }

    internal interface FileDependenciesOrFuture

    /**
     * The main purpose of this class is to act as a [<].
     * 
     * 
     * Its specific type is explicitly visible to clients to allow them to cleanly distinguish it
     * as a permitted subtype of [FileDependenciesOrFuture].
     */
    internal class FutureFileDependencies
    private constructor(key: String?, consumer: java.util.function.BiConsumer<String?, FileDependencies?>?) :
        SettableFutureKeyedValue<FutureFileDependencies?, String?, FileDependencies?>(key, consumer),
        FileDependenciesOrFuture

    /**
     * Reconstitutes the set of file dependencies associated with `key`.
     * 
     * 
     * Performs lookups and parent resolution (recursively) and symlink resolution to obtain all
     * files associated with `key` represented as [FileDependencies].
     * 
     * @param key as described in [FileInvalidationData].
     * @return either an immediate [FileDependencies] instance or effectively a [     ][<] instance.
     */
    fun getFileDependencies(key: String?, store: FingerprintValueStore?): FileDependenciesOrFuture? {
        return fileCache.getValueOrFuture(key, store)
    }

    private fun populateFutureFileDependencies(
        ownedFuture: FutureFileDependencies, store: FingerprintValueStore
    ): FileDependenciesOrFuture? {
        return fetchInvalidationData<kotlin.String?, FileDependencies?, FutureFileDependencies?>(
            com.google.common.base.Function { cacheKey: String? -> this.getKeyBytes(cacheKey!!) },
            java.util.function.BiFunction { key: String?, store: FingerprintValueStore? ->
                WaitForFileInvalidationData(
                    key!!,
                    store
                )
            },
            ownedFuture,
            store
        )
    }

    internal interface ListingDependenciesOrFuture


    /**
     * The main purpose of this class is to act as a [<].
     * 
     * 
     * Its specific type is explicitly visible to clients to allow them to cleanly distinguish it
     * as a permitted subtype of [ListingDependenciesOrFuture].
     */
    internal class FutureListingDependencies
    private constructor(key: String?, consumer: java.util.function.BiConsumer<String?, ListingDependencies?>?) :
        SettableFutureKeyedValue<FutureListingDependencies?, String?, ListingDependencies?>(key, consumer),
        ListingDependenciesOrFuture

    /**
     * Deserializes the resolved directory listing information associated with `key`.
     * 
     * @param key should be as described at [DirectoryListingInvalidationData].
     * @return either an immediate [ListingDependencies] instance or effectively a [     ][<] instance.
     */
    fun getListingDependencies(key: String?, store: FingerprintValueStore?): ListingDependenciesOrFuture? {
        return listingCache.getValueOrFuture(key, store)
    }

    private fun populateFutureListingDependencies(
        ownedFuture: FutureListingDependencies, store: FingerprintValueStore
    ): ListingDependenciesOrFuture? {
        return fetchInvalidationData<kotlin.String?, ListingDependencies?, FutureListingDependencies?>(
            com.google.common.base.Function { cacheKey: String? -> this.getKeyBytes(cacheKey!!) },
            java.util.function.BiFunction { key: String?, store: FingerprintValueStore? ->
                WaitForListingInvalidationData(
                    key!!,
                    store
                )
            },
            ownedFuture,
            store
        )
    }

    internal interface NestedDependenciesOrFuture


    internal class FutureNestedDependencies
    private constructor(
        key: PackedFingerprint?,
        consumer: java.util.function.BiConsumer<PackedFingerprint?, NestedDependencies?>?
    ) : SettableFutureKeyedValue<FutureNestedDependencies?, PackedFingerprint?, NestedDependencies?>(key, consumer),
        NestedDependenciesOrFuture

    /**
     * Retrieves the nested dependency information associated with `key`.
     * 
     * 
     * Like the other implementations, this can be thought of as a simple state machine. There's
     * one explicit state represented by [WaitForNestedNodeBytes], which waits for the bytes
     * associated with `key`. There's a second implicit state that waits for child elements,
     * which may be files, listings or other nested nodes.
     * 
     * @param key is a fingerprint of the byte representation described at [     ][FileDependencySerializer.computeNodeBytes].
     */
    fun getNestedDependencies(
        key: PackedFingerprint?, store: FingerprintValueStore?
    ): NestedDependenciesOrFuture? {
        return nestedCache.getValueOrFuture(key, store)
    }

    private fun populateFutureNestedDependencies(
        ownedFuture: FutureNestedDependencies, store: FingerprintValueStore
    ): NestedDependenciesOrFuture? {
        return fetchInvalidationData<PackedFingerprint?, NestedDependencies?, FutureNestedDependencies?>(
            com.google.common.base.Functions.identity<PackedFingerprint?>(),
            java.util.function.BiFunction { unused: PackedFingerprint?, store: FingerprintValueStore? ->
                WaitForNestedNodeBytes(
                    unused,
                    store
                )
            },
            ownedFuture,
            store
        )
    }

    // ---------- Begin FileDependencies deserialization implementation ----------
    private inner class WaitForFileInvalidationData(private val key: String, store: FingerprintValueStore?) :
        com.google.common.util.concurrent.AsyncFunction<ByteArray?, FileDependencies?> {
        private val store: FingerprintValueStore?

        init {
            this.store = store
        }

        @Throws(InvalidProtocolBufferException::class)
        override fun apply(bytes: ByteArray?): com.google.common.util.concurrent.ListenableFuture<FileDependencies?> {
            if (bytes == null) {
                return com.google.common.util.concurrent.Futures.immediateFuture<FileDependencies?>(FileDependencies.Companion.newMissingInstance())
            }

            val data: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                FileInvalidationData.parseFrom(bytes, getEmptyRegistry())
            if (data.hasOverflowKey() && !data.getOverflowKey().equals(key)) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<FileDependencies?>(
                    com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                        java.lang.String.format(
                            "Non-matching overflow key. This is possible if there is a key fingerprint"
                                    + " collision. Expected %s got %s",
                            key, data
                        )
                    )
                )
            }

            val pathBegin: Int = key.indexOf(FileDependencyKeySupport.FILE_KEY_DELIMITER.toInt()) + 1
            val parentDirectoryEnd: Int = key.lastIndexOf(PathFragment.SEPARATOR_CHAR.code)

            // `parentDirectoryEnd` is the index of the last `/`. This can be -1 if there is no `/` in
            // the key, or it can be less than `pathBegin` if the only `/`s are in the version part of
            // the key (e.g. "Ly/APA:WORKSPACE"). In either case, there is no parent directory to
            // resolve.
            if (parentDirectoryEnd < pathBegin) {
                com.google.common.base.Preconditions.checkState(
                    !data.hasParentMtsv(), "no parent directory, but had parent MTSV %s, %s", key, data
                )
                return resolveParent(key, data, key.substring(pathBegin),  /* parentKey= */null, store)
            }

            val parentDirectory: String = key.substring(pathBegin, parentDirectoryEnd)
            val parentKey: String? =
                computeCacheKey(
                    parentDirectory,
                    if (data.hasParentMtsv()) data.getParentMtsv() else LongVersionGetter.MINIMAL,
                    FileDependencyKeySupport.FILE_KEY_DELIMITER
                )
            val basename: String = key.substring(parentDirectoryEnd + 1)
            return resolveParent(key, data, basename, parentKey, store)
        }
    }

    private fun resolveParent(
        key: String?,
        data: FileInvalidationData,
        basename: String?,
        parentKey: String?,
        store: FingerprintValueStore?
    ): com.google.common.util.concurrent.ListenableFuture<FileDependencies?> {
        val waitForParent = WaitForParent(key, data, basename, store)

        if (parentKey == null) {
            return waitForParent.apply( /* parentOrMissing= */null)
        }

        return when (getFileDependencies(parentKey, store)) {
            -> waitForParent.apply(parent)
            -> com.google.common.util.concurrent.Futures.transformAsync<FileDependencies?, FileDependencies?>(
                future,
                waitForParent,
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }
    }

    private inner class WaitForParent(
        private val key: String?,
        data: FileInvalidationData,
        basename: String?,
        store: FingerprintValueStore?
    ) : com.google.common.util.concurrent.AsyncFunction<FileDependencies?, FileDependencies?> {
        private val data: FileInvalidationData
        private val basename: String?
        private val store: FingerprintValueStore?

        init {
            this.data = data
            this.basename = basename
            this.store = store
        }

        override fun apply(parentOrMissing: FileDependencies?): com.google.common.util.concurrent.ListenableFuture<FileDependencies?> {
            val builder: com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies.Builder
            val parentDirectory: String?
            when (parentOrMissing) {
                null -> {
                    parentDirectory = null
                    builder = FileDependencies.Companion.builder(basename)
                }

                -> {
                    parentDirectory = parent.resolvedPath()
                    builder =
                        FileDependencies.Companion.builder(getRelative(parentDirectory, basename))
                            .addDependency(parent)
                }

                -> return com.google.common.util.concurrent.Futures.immediateFuture<FileDependencies?>(FileDependencies.Companion.newMissingInstance())
            }
            return processSymlinks(key, data,  /* symlinkIndex= */0, parentDirectory, builder, store)
        }
    }

    /**
     * Processes any symlinks that my be present in `data`.
     * 
     * @param key the main key that this symlink belongs to
     * @param parentDirectory the real directory containing the symlink
     */
    private fun processSymlinks(
        key: String?,
        data: FileInvalidationData,
        symlinkIndex: Int,
        parentDirectory: String?,  // null if root-level
        builder: com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies.Builder,
        store: FingerprintValueStore?
    ): com.google.common.util.concurrent.ListenableFuture<FileDependencies?> {
        if (symlinkIndex >= data.getSymlinksCount()) {
            return com.google.common.util.concurrent.Futures.immediateFuture<FileDependencies?>(builder.build())
        }

        val link: Symlink = data.getSymlinks(symlinkIndex)
        val linkContents: String? = link.getContents()
        com.google.common.base.Preconditions.checkState(
            OS.getDriveStrLength(linkContents) == 0,
            "expected symlink contents to be a relative path: %s",
            data
        )
        // Combines the parent directory of the link with its contents and normalizes.
        val normalizedLinkTarget = getRelativeAndNormalize(parentDirectory, linkContents)
        val normalizedLinkParent = getParentDirectory(normalizedLinkTarget)

        if (!doesSymlinkParentNeedResolution(parentDirectory, normalizedLinkParent)) {
            com.google.common.base.Preconditions.checkState(
                !link.hasParentMtsv(),
                "no resolution needed for data=%s, symlinkIndex=%s, parentDirectory=%s,"
                        + " normalizedLinkParent=%s but symlink had parent MTSV",
                data,
                symlinkIndex,
                parentDirectory,
                normalizedLinkParent
            )
            // Since `normalizedLinkParent` is already a real directory, `normalizedLinkTarget` is the
            // resolved symlink path.
            if (!normalizedLinkTarget.isEmpty()) { // Avoids adding root as a resolved path.
                builder.addPath(normalizedLinkTarget)
            }
            return processSymlinks(key, data, symlinkIndex + 1, normalizedLinkParent, builder, store)
        }

        val linkBasename: String = normalizedLinkTarget.substring(normalizedLinkParent.length() + 1)

        val newParentKey: String? =
            computeCacheKey(
                normalizedLinkParent,
                if (link.hasParentMtsv()) link.getParentMtsv() else LongVersionGetter.MINIMAL,
                FileDependencyKeySupport.FILE_KEY_DELIMITER
            )

        val waitForSymlinkParent =
            WaitForSymlinkParent(key, data, symlinkIndex, linkBasename, builder, store)

        return when (getFileDependencies(newParentKey, store)) {
            -> waitForSymlinkParent.apply(resolvedParent)
            -> com.google.common.util.concurrent.Futures.transformAsync<FileDependencies?, FileDependencies?>(
                future,
                waitForSymlinkParent,
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }
    }

    private inner class WaitForSymlinkParent(
        private val key: String?,
        data: FileInvalidationData,
        symlinkIndex: Int,
        linkBasename: String?,
        builder: com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies.Builder,
        store: FingerprintValueStore?
    ) : com.google.common.util.concurrent.AsyncFunction<FileDependencies?, FileDependencies?> {
        private val data: FileInvalidationData
        private val symlinkIndex: Int
        private val linkBasename: String?
        private val builder: com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencies.Builder
        private val store: FingerprintValueStore?

        init {
            this.data = data
            this.symlinkIndex = symlinkIndex
            this.linkBasename = linkBasename
            this.builder = builder
            this.store = store
        }

        override fun apply(parentOrMissing: FileDependencies): com.google.common.util.concurrent.ListenableFuture<FileDependencies?> {
            return when (parentOrMissing) {
                -> {
                    val parentPath: String? = parent.resolvedPath()
                    builder.addPath(getRelative(parentPath, linkBasename)).addDependency(parent)
                    processSymlinks(key, data, symlinkIndex + 1, parentPath, builder, store)
                }

                -> com.google.common.util.concurrent.Futures.immediateFuture<FileDependencies?>(FileDependencies.Companion.newMissingInstance())
            }
        }
    }

    // ---------- Begin ListingDependencies deserialization implementation ----------
    private inner class WaitForListingInvalidationData
        (private val key: String, store: FingerprintValueStore?) :
        com.google.common.util.concurrent.AsyncFunction<ByteArray?, ListingDependencies?> {
        private val store: FingerprintValueStore?

        init {
            this.store = store
        }

        @Throws(InvalidProtocolBufferException::class)
        override fun apply(bytes: ByteArray?): com.google.common.util.concurrent.ListenableFuture<ListingDependencies?> {
            if (bytes == null) {
                return com.google.common.util.concurrent.Futures.immediateFuture<ListingDependencies?>(
                    ListingDependencies.Companion.newMissingInstance()
                )
            }

            val data: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                DirectoryListingInvalidationData.parseFrom(bytes, getEmptyRegistry())
            if (data.hasOverflowKey() && !data.getOverflowKey().equals(key)) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<ListingDependencies?>(
                    com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                        java.lang.String.format(
                            "Non-matching overflow key. This is possible if there is a key fingerprint"
                                    + " collision. Expected %s got %s",
                            key, data
                        )
                    )
                )
            }

            val pathBegin: Int = key.indexOf(FileDependencyKeySupport.DIRECTORY_KEY_DELIMITER.toInt()) + 1

            val path: String = key.substring(pathBegin)
            if (path.isEmpty()) {
                return com.google.common.util.concurrent.Futures.immediateFuture<ListingDependencies?>(
                    ListingDependencies.Companion.from(
                        ROOT_FILE
                    )
                )
            }

            val fileKey: String? =
                computeCacheKey(
                    path,
                    if (data.hasFileMtsv()) data.getFileMtsv() else LongVersionGetter.MINIMAL,
                    FileDependencyKeySupport.FILE_KEY_DELIMITER
                )
            return when (getFileDependencies(fileKey, store)) {
                -> com.google.common.util.concurrent.Futures.immediateFuture<ListingDependencies?>(
                    ListingDependencies.Companion.from(
                        dependencies
                    )
                )

                -> com.google.common.util.concurrent.Futures.transform<FileDependencies?, ListingDependencies?>(
                    future,
                    com.google.common.base.Function { realDirectory: FileDependencies? ->
                        ListingDependencies.Companion.from(realDirectory)
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            }
        }
    }

    // ---------- Begin NestedDependencies deserialization implementation ----------
    private inner class WaitForNestedNodeBytes(unused: PackedFingerprint?, store: FingerprintValueStore?) :
        com.google.common.util.concurrent.AsyncFunction<ByteArray?, NestedDependencies?> {
        private val store: FingerprintValueStore?

        init {
            this.store = store
        }

        /**
         * Parses the `bytes` to create a [NestedDependencies] instance.
         * 
         * 
         * Refer to comment at [FileDependencySerializer.computeNodeBytes] for the data format.
         * Uses delegation for children, which might not be completely resolved.
         */
        override fun apply(bytes: ByteArray?): com.google.common.util.concurrent.ListenableFuture<NestedDependencies?> {
            if (bytes == null) {
                return com.google.common.util.concurrent.Futures.immediateFuture<NestedDependencies?>(NestedDependencies.Companion.newMissingInstance())
            }

            try {
                val usesZstdCompression: Boolean = MagicBytes.hasMagicBytes(bytes)
                val inputStream: java.io.InputStream?
                if (usesZstdCompression) {
                    val byteArrayInputStream: ByteArrayInputStream =
                        ByteArrayInputStream(bytes, 2, bytes.size - 2)
                    inputStream = ZstdInputStream(byteArrayInputStream, RecyclingBufferPool.INSTANCE)
                } else {
                    inputStream = ByteArrayInputStream(bytes)
                }
                inputStream.use {
                    val codedIn: CodedInputStream = CodedInputStream.newInstance(inputStream)
                    val nestedCount: Int = codedIn.readInt32()
                    val fileCount: Int = codedIn.readInt32()
                    val listingCount: Int = codedIn.readInt32()
                    val sourceCount: Int = codedIn.readInt32()

                    val elements: Array<FileSystemDependencies?> =
                        arrayOfNulls<FileSystemDependencies>(nestedCount + fileCount + listingCount)
                    val sources: Array<FileDependencies?> =
                        if (sourceCount > 0)
                            arrayOfNulls<FileDependencies>(sourceCount)
                        else
                            NestedDependencies.Companion.EMPTY_SOURCES
                    val countdown = PendingElementCountdown(elements, sources)

                    for (i in 0..<nestedCount) {
                        val key: PackedFingerprint = PackedFingerprint.readFrom(codedIn)
                        when (getNestedDependencies(key, store)) {
                            -> elements[i] = dependencies
                            -> {
                                countdown.registerPendingElement()
                                com.google.common.util.concurrent.Futures.addCallback<NestedDependencies?>(
                                    future,
                                    WaitingForElement(i, countdown),
                                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                                )
                            }
                        }
                    }

                    val nestedAndFileCount = nestedCount + fileCount
                    for (i in nestedCount..<nestedAndFileCount) {
                        val key: String? = codedIn.readString()
                        when (getFileDependencies(key, store)) {
                            -> elements[i] = dependencies
                            -> {
                                countdown.registerPendingElement()
                                com.google.common.util.concurrent.Futures.addCallback<FileDependencies?>(
                                    future,
                                    WaitingForElement(i, countdown),
                                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                                )
                            }
                        }
                    }

                    val total = nestedAndFileCount + listingCount
                    for (i in nestedAndFileCount..<total) {
                        val key: String? = codedIn.readString()
                        when (getListingDependencies(key, store)) {
                            -> elements[i] = dependencies
                            -> {
                                countdown.registerPendingElement()
                                com.google.common.util.concurrent.Futures.addCallback<ListingDependencies?>(
                                    future,
                                    WaitingForElement(i, countdown),
                                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                                )
                            }
                        }
                    }

                    for (i in 0..<sourceCount) {
                        val key: String? = codedIn.readString()
                        when (getFileDependencies(key, store)) {
                            -> sources[i] = dependencies
                            -> {
                                countdown.registerPendingElement()
                                com.google.common.util.concurrent.Futures.addCallback<FileDependencies?>(
                                    future,
                                    WaitingForSource(i, countdown),
                                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                                )
                            }
                        }
                    }
                    countdown.notifyInitializationDone()
                    return countdown
                }
            } catch (e: IOException) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<NestedDependencies?>(
                    com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                        "Error deserializing nested node",
                        e
                    )
                )
            }
        }
    }

    /**
     * A future that keeps track of the count of elements that still need to be set.
     * 
     * 
     * This future completes once all the elements are set.
     */
    private class PendingElementCountdown(elements: Array<FileSystemDependencies?>, sources: Array<FileDependencies?>) :
        QuiescingFuture<NestedDependencies?>(com.google.common.util.concurrent.MoreExecutors.directExecutor()) {
        private val elements: Array<FileSystemDependencies?>
        private val sources: Array<FileDependencies?>

        init {
            this.elements = elements
            this.sources = sources
        }

        fun registerPendingElement() {
            increment()
        }

        fun notifyInitializationDone() {
            decrement()
        }

        fun setPendingElement(index: Int, value: FileSystemDependencies?) {
            elements[index] = value
            decrement()
        }

        fun setSource(index: Int, value: FileDependencies?) {
            sources[index] = value
            decrement()
        }

        fun notifyFailure(e: Throwable?) {
            notifyException(e)
        }

        protected val value: NestedDependencies
            get() = NestedDependencies.Companion.from(elements, sources)
    }

    /**
     * Callback that populates the element at [.index] upon success.
     * 
     * 
     * Performs required bookkeeping for [PendingElementCountdown].
     */
    private class WaitingForElement(private val index: Int, private val countdown: PendingElementCountdown) :
        com.google.common.util.concurrent.FutureCallback<FileSystemDependencies?> {
        override fun onSuccess(dependencies: FileSystemDependencies?) {
            countdown.setPendingElement(index, dependencies)
        }

        override fun onFailure(t: Throwable) {
            countdown.notifyFailure(t)
        }
    }

    private class WaitingForSource(private val index: Int, private val countdown: PendingElementCountdown) :
        com.google.common.util.concurrent.FutureCallback<FileDependencies?> {
        override fun onSuccess(dependencies: FileDependencies?) {
            countdown.setSource(index, dependencies)
        }

        override fun onFailure(t: Throwable) {
            countdown.notifyFailure(t)
        }
    }

    // ---------- Begin shared helpers ----------
    private fun <KeyT, T, FutureT : SettableFutureKeyedValue<FutureT?, KeyT?, T?>?>
            fetchInvalidationData(
        keyConverter: com.google.common.base.Function<KeyT?, out KeyBytesProvider?>,
        waitFactory: java.util.function.BiFunction<KeyT?, FingerprintValueStore?, com.google.common.util.concurrent.AsyncFunction<ByteArray?, T?>?>,
        ownedFuture: FutureT?,
        store: FingerprintValueStore
    ): FutureT? {
        val key: KeyT? = ownedFuture.key()
        val futureBytes: com.google.common.util.concurrent.ListenableFuture<ByteArray?>
        try {
            futureBytes = store.get(keyConverter.apply(key))
        } catch (e: IOException) {
            return ownedFuture.failWith(e)
        }

        return ownedFuture.completeWith(
            com.google.common.util.concurrent.Futures.transformAsync<ByteArray?, T?>(
                futureBytes,
                waitFactory.apply(key, store),
                executor
            )
        )
    }

    private fun getKeyBytes(cacheKey: String): KeyBytesProvider? {
        if (cacheKey.length() > FileDependencyKeySupport.MAX_KEY_LENGTH) {
            return fingerprinter.fingerprint(cacheKey.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        }
        return com.google.devtools.build.lib.skyframe.serialization.StringKey(cacheKey)
    }

    private class DependencyMap<KeyT, ValueOrFutureT, ValueT : ValueOrFutureT?, FutureT : SettableFutureKeyedValue<FutureT?, KeyT?, ValueT?>?>
        (
        map: ConcurrentMap<KeyT?, ValueOrFutureT?>?,
        valueOrFutureFactory: java.util.function.BiFunction<KeyT?, java.util.function.BiConsumer<KeyT?, ValueT?>?, ValueOrFutureT?>?,
        futureType: java.lang.Class<FutureT?>?,
        populator: java.util.function.BiFunction<FutureT?, FingerprintValueStore?, ValueOrFutureT?>
    ) : AbstractValueOrFutureMap<KeyT?, ValueOrFutureT?, ValueT?, FutureT?>(map, valueOrFutureFactory, futureType) {
        private val populator: java.util.function.BiFunction<FutureT?, FingerprintValueStore?, ValueOrFutureT?>

        init {
            this.populator = populator
        }

        fun getValueOrFuture(key: KeyT?, store: FingerprintValueStore?): ValueOrFutureT? {
            val result: ValueOrFutureT? = getOrCreateValueForSubclasses(key)
            if (futureType().isInstance(result)) {
                val future: FutureT? = futureType().cast(result)
                if (future.tryTakeOwnership()) {
                    try {
                        return populator.apply(future, store)
                    } finally {
                        future.verifyComplete()
                    }
                }
            }
            return result
        }
    }

    companion object {
        private val OS: OsPathPolicy = OsPathPolicy.getFilePathOs()

        /** Singleton representing the root file.  */
        val ROOT_FILE: FileDependencies = FileDependencies.Companion.builder("").build()

        private fun getRelative(parentDirectory: String?, basename: String?): String? {
            if (parentDirectory == null) {
                return basename
            }
            return parentDirectory + PathFragment.SEPARATOR_CHAR + basename
        }

        private fun getRelativeAndNormalize(
            parentDirectory: String?, linkContents: String?
        ): String {
            val normalizationLevel: Int = OS.needsToNormalize(linkContents)
            return OS.normalize(getRelative(parentDirectory, linkContents), normalizationLevel)
        }

        // null if `path` is at the root level
        private fun getParentDirectory(path: String): String? {
            val lastSeparator: Int = path.lastIndexOf(PathFragment.SEPARATOR_CHAR.code)
            if (lastSeparator == -1) { // no separator
                return null
            }
            return path.substring(0, lastSeparator)
        }

        /**
         * Predicate specifying when a symlink parent directory needs further resolution.
         * 
         * 
         * A relative path specifier in symlink contents can modify the parent directory but it does
         * not always do so. For example, the symlink could point to a file in the same directory or the
         * symlink could point to a file in an ancestor directory. In both of these cases, the parent
         * directory is already fully resolved.
         * 
         * @param previousParent the parent of the actual symlink itself. Null if the parent is actually
         * the root directory.
         * @param newParent the parent directory after combining the symlink with `previousParent`.
         * Null if the result is the root directory.
         */
        private fun doesSymlinkParentNeedResolution(
            previousParent: String?, newParent: String?
        ): Boolean {
            if (newParent == null) {
                return false // Already root level. No parent resolution needed.
            }
            if (previousParent == null) {
                return true // No previousParent so resolution is needed.
            }
            // `newParent` is already a resolved path if it is the same as or an ancestor of the already
            // resolved `previousParent`.
            return !previousParent.startsWith(newParent)
        }
    }
}
