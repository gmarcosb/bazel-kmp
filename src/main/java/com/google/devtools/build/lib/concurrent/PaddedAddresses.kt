// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import com.google.devtools.build.lib.unsafe.UnsafeProvider

/** This class encapsulates creating padded addresses for 8-byte values.  */
internal object PaddedAddresses {
    /**
     * The target alignment bytes.
     * 
     * 
     * On x86, the cache line size is 64-bytes, but spatial prefetching may fetch two lines at a
     * time so interference is observable over a range of 128-bytes.
     */
    @com.google.common.annotations.VisibleForTesting
    const val ALIGNMENT: Long = 128

    /** The amount of padding needed between 8-byte objects to avoid interference.  */
    private val PADDING_WIDTH: Long = com.google.devtools.build.lib.concurrent.PaddedAddresses.ALIGNMENT - 8

    /**
     * Creates a base address for the specified number of entries.
     * 
     * 
     * The caller must call [Unsafe.freeMemory] on the returned address. Use of [ ] could be helpful.
     * 
     * @param count allocates a buffer large enough to accommodate this many aligned blocks.
     */
    // TODO: b/386384684 - remove Unsafe usage
    @kotlin.jvm.JvmStatic
    fun createPaddedBaseAddress(count: Int): Long {
        return UnsafeProvider.unsafe()
            .allocateMemory(count * com.google.devtools.build.lib.concurrent.PaddedAddresses.ALIGNMENT + com.google.devtools.build.lib.concurrent.PaddedAddresses.PADDING_WIDTH)
    }

    /**
     * Obtains an [.ALIGNMENT] aligned address from an 8-byte aligned base address and offset.
     * 
     * @param baseAddress an address obtained from [.createPaddedBaseAddress].
     * @param offset an offset less than the `count` parameter provided when creating the
     * address.
     */
    @kotlin.jvm.JvmStatic
    fun getAlignedAddress(baseAddress: Long, offset: Int): Long {
        val cacheLineAddress: Long =
            (baseAddress + com.google.devtools.build.lib.concurrent.PaddedAddresses.PADDING_WIDTH) and (com.google.devtools.build.lib.concurrent.PaddedAddresses.ALIGNMENT - 1).inv()
        return cacheLineAddress + offset * com.google.devtools.build.lib.concurrent.PaddedAddresses.ALIGNMENT
    }
}
