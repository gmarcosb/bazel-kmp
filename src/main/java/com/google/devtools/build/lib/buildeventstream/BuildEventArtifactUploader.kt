// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType
import java.io.IOException
import java.util.concurrent.ScheduledExecutorService

/** Uploads artifacts referenced by the Build Event Protocol (BEP).  */
interface BuildEventArtifactUploader : io.netty.util.ReferenceCounted {
    /**
     * Asynchronously uploads a set of files referenced by the protobuf representation of a [ ]. This method is expected to return quickly.
     * 
     * 
     * This method must not throw any exceptions.
     * 
     * 
     * Returns a future to a [PathConverter] that must provide a name for each uploaded file
     * as it should appear in the BEP.
     */
    fun upload(files: MutableMap<com.google.devtools.build.lib.vfs.Path?, LocalFile?>?): com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.buildeventstream.PathConverter?>?

    /** The context associated with an in-flight remote upload.  */
    interface UploadContext {
        /** The [OutputStream] to stream the file contents to.  */
        @kotlin.jvm.JvmField
        val outputStream: java.io.OutputStream?

        /** The future URI of the completed upload.  */
        fun uriFuture(): com.google.common.util.concurrent.ListenableFuture<String?>?
    }

    /**
     * Initiate a streaming upload to the remote storage.
     * 
     * 
     * If inputSupplier is null, the caller is expected to write to the [ ][UploadContext.getOutputStream]. If inputSupplier is non-null, [ ][UploadContext.getOutputStream] is null.
     */
    fun startUpload(
        type: LocalFileType?, inputSupplier: java.util.function.Supplier<java.io.InputStream?>?
    ): UploadContext {
        return EMPTY_UPLOAD
    }

    /**
     * Return true if the upload may be "slow". Examples of slowness include writes to remote storage.
     */
    fun mayBeSlow(): Boolean

    /**
     * Returns a [PathConverter] for the uploaded files, or `null` when the uploaded
     * failed.
     */
    fun uploadReferencedLocalFiles(
        localFiles: MutableCollection<LocalFile>
    ): com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.buildeventstream.PathConverter?>? {
        val localFileMap: MutableMap<com.google.devtools.build.lib.vfs.Path?, LocalFile?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<com.google.devtools.build.lib.vfs.Path?, LocalFile?>(
                localFiles.size()
            )
        for (localFile in localFiles) {
            // It is possible for targets to have duplicate artifacts (same path but different owners)
            // in their output groups. Since they didn't trigger an artifact conflict they are the
            // same file, so just skip either one
            localFileMap.putIfAbsent(localFile.path, localFile)
        }
        return upload(localFileMap)
    }

    /**
     * Blocks on the completion of pending remote uploads, enforcing the relevant timeout if
     * applicable.
     */
    fun waitForRemoteUploads(
        remoteUploads: MutableCollection<com.google.common.util.concurrent.ListenableFuture<String?>?>,
        timeoutExecutor: ScheduledExecutorService?
    ): com.google.common.util.concurrent.ListenableFuture<*> {
        return com.google.common.util.concurrent.Futures.allAsList<String?>(remoteUploads)
    }

    companion object {
        val EMPTY_UPLOAD: UploadContext = object : UploadContext {
            override fun getOutputStream(): java.io.OutputStream {
                return com.google.common.io.ByteStreams.nullOutputStream()
            }

            override fun uriFuture(): com.google.common.util.concurrent.ListenableFuture<String?> {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<String?>(IOException("No available uploader"))
            }
        }
    }
}
