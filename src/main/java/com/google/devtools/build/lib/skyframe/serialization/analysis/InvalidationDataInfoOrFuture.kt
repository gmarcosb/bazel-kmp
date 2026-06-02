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

import com.google.devtools.build.lib.concurrent.SettableFutureKeyedValue

/**
 * Information about remotely stored invalidation data.
 * 
 * 
 * There are 3 distinct type families, associated with files, directory listings and nodes
 * (nested sets of files and directory listings).
 * 
 * 
 * Each family has 3 types, a constant type (no persisted data), information about stored
 * invalidation data or a future. Information about stored invalidation data always includes a cache
 * key and a write status.
 * 
 * 
 * In the case of [FileInvalidationData], a Max Transitive Source Version (MTSV) and fully
 * resolved path is also included. These fields are used for ancestor resolution.
 */
internal interface InvalidationDataInfoOrFuture {
    /** Non-future, immediate value sub-types of [InvalidationDataInfoOrFuture].  */
    interface InvalidationDataInfo : InvalidationDataInfoOrFuture


    /** Base implementation of a [InvalidationDataInfoOrFuture] value.  */
    class BaseInvalidationDataInfo<T> internal constructor(private val cacheKey: T?, writeStatus: WriteStatus?) {
        private val writeStatus: WriteStatus?

        init {
            this.writeStatus = writeStatus
        }

        /** Key for [com.google.devtools.build.lib.serialization.FingerprintValueService].  */
        fun cacheKey(): T? {
            return cacheKey
        }

        /** Transitively inclusive status of writing this data to the cache.  */
        fun writeStatus(): WriteStatus? {
            return writeStatus
        }
    }

    interface FileDataInfoOrFuture : InvalidationDataInfoOrFuture


    interface FileDataInfo : FileDataInfoOrFuture, InvalidationDataInfo


    /** The file doesn't change and isn't associated with any invalidation data.  */
    enum class ConstantFileData : FileDataInfo {
        CONSTANT_FILE
    }

    /** Information about transitive upload of invalidation data for a certain [FileKey].  */
    class FileInvalidationDataInfo internal constructor(
        cacheKey: String?,
        writeStatus: WriteStatus?,
        private val exists: Boolean,
        private val mtsv: Long,
        realPath: RootedPath?
    ) : BaseInvalidationDataInfo<String?>(cacheKey, writeStatus), FileDataInfo {
        private val realPath: RootedPath?

        init {
            this.realPath = realPath
        }

        /** True if the file exists.  */
        fun exists(): Boolean {
            return exists
        }

        /**
         * The MTSV.
         * 
         * 
         * Used by dependents to create parent references. This information is already incorporated
         * into the [.cacheKey] value.
         */
        fun mtsv(): Long {
            return mtsv
        }

        /**
         * The resolved real path.
         * 
         * 
         * Used for symlink resolution.
         */
        fun realPath(): RootedPath? {
            return realPath
        }
    }

    class FutureFileDataInfo
    internal constructor(
        key: com.google.devtools.build.lib.skyframe.FileKey?,
        consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.skyframe.FileKey?, FileDataInfo?>?
    ) : SettableFutureKeyedValue<FutureFileDataInfo?, com.google.devtools.build.lib.skyframe.FileKey?, FileDataInfo?>(
        key,
        consumer
    ), FileDataInfoOrFuture

    interface ListingDataInfoOrFuture : InvalidationDataInfoOrFuture


    interface ListingDataInfo : ListingDataInfoOrFuture, InvalidationDataInfo


    /** This listing doesn't change and isn't associated with invalidation data.  */
    enum class ConstantListingData : ListingDataInfo {
        CONSTANT_LISTING
    }

    /**
     * Information about transitive upload of invalidation data for a certain [ ].
     */
    class ListingInvalidationDataInfo internal constructor(cacheKey: String?, writeStatus: WriteStatus?) :
        BaseInvalidationDataInfo<String?>(cacheKey, writeStatus), ListingDataInfo

    class FutureListingDataInfo
    internal constructor(
        key: DirectoryListingKey?,
        consumer: java.util.function.BiConsumer<DirectoryListingKey?, ListingDataInfo?>?
    ) : SettableFutureKeyedValue<FutureListingDataInfo?, DirectoryListingKey?, ListingDataInfo?>(key, consumer),
        ListingDataInfoOrFuture

    interface NodeDataInfoOrFuture : InvalidationDataInfoOrFuture


    interface NodeDataInfo : NodeDataInfoOrFuture, InvalidationDataInfo


    enum class ConstantNodeData : NodeDataInfo {
        CONSTANT_NODE
    }

    /** Information about remotely persisted [AbstractNestedFileOpNodes].  */
    class NodeInvalidationDataInfo internal constructor(key: PackedFingerprint?, writeStatus: WriteStatus?) :
        BaseInvalidationDataInfo<PackedFingerprint?>(key, writeStatus), NodeDataInfo

    class FutureNodeDataInfo
    internal constructor(key: AbstractNestedFileOpNodes?) :
        SettableFutureKeyedValue<FutureNodeDataInfo?, AbstractNestedFileOpNodes?, NodeDataInfo?>(
            key,
            { key: AbstractNestedFileOpNodes, value: NodeDataInfo? -> setNodeDataInfo(key, value) }),
        NodeDataInfoOrFuture {
        companion object {
            private fun setNodeDataInfo(key: AbstractNestedFileOpNodes, value: NodeDataInfo?) {
                key.setSerializationScratch(value)
            }
        }
    }
}
