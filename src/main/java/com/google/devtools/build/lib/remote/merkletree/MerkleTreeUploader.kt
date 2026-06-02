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
package com.google.devtools.build.lib.remote.merkletree

import build.bazel.remote.execution.v2.Digest
import com.google.common.util.concurrent.ListenableFuture
import com.google.devtools.build.lib.vfs.Path

/** The basic cache operations needed to upload a [MerkleTree] and its associated blobs.  */
interface MerkleTreeUploader {
    /** Uploads an in-memory blob to the remote cache.  */
    fun uploadBlob(
        context: RemoteActionExecutionContext?, digest: Digest?, data: ByteArray?
    ): ListenableFuture<Void?>?

    /** Uploads a local file to the remote cache.  */
    fun uploadFile(
        context: RemoteActionExecutionContext?,
        remotePathResolver: RemotePathResolver?,
        digest: Digest?,
        path: Path?,
        force: Boolean
    ): ListenableFuture<Void?>?

    /** Uploads a virtual action input to the remote cache.  */
    fun uploadVirtualActionInput(
        context: RemoteActionExecutionContext?, digest: Digest?, virtualActionInput: VirtualActionInput?
    ): ListenableFuture<Void?>?

    /**
     * Ensures that all inputs as well as metadata protos in the given Merkle tree are present in the
     * remote cache by querying for and uploading missing blobs.
     * 
     * @param force if true, all blobs in the tree are uploaded even if they have already been
     * uploaded before.
     */
    @Throws(IOException::class, InterruptedException::class)
    fun ensureInputsPresent(
        context: RemoteActionExecutionContext?,
        merkleTree: Uploadable?,
        force: Boolean,
        remotePathResolver: RemotePathResolver?
    )
}
