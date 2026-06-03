// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.remote.worker

import com.google.devtools.build.lib.remote.util.Utils.getFromFuture

/** A [CombinedCache] backed by an [DiskCacheClient].  */
internal class OnDiskBlobStoreCache(cacheDir: Path, digestUtil: DigestUtil, remoteWorkerOptions: RemoteWorkerOptions) :
    CombinedCache( /* remoteCacheClient= */
        null,
        DiskCacheClient(cacheDir, digestUtil),  /* symlinkTemplate= */
        null,
        digestUtil,  /* chunkingEnabled= */
        false
    ) {
    private class DigestAndInvocation(digest: Digest?, invocationId: String?) {
        val digest: Digest?
        val invocationId: String?

        init {
            this.digest = digest
            this.invocationId = invocationId
        }
    }

    private val remoteWorkerOptions: RemoteWorkerOptions
    private val numberOfDownloadsPerDigestAndInvocation: ConcurrentHashMap<DigestAndInvocation?, Int?> =
        ConcurrentHashMap<DigestAndInvocation?, Int?>()

    init {
        this.remoteWorkerOptions = remoteWorkerOptions
    }

    val remoteCacheCapabilities: CacheCapabilities
        get() = CacheCapabilities.newBuilder()
            .setActionCacheUpdateCapabilities(
                ActionCacheUpdateCapabilities.newBuilder().setUpdateEnabled(true).build()
            )
            .setSymlinkAbsolutePathStrategy(SymlinkAbsolutePathStrategy.Value.ALLOWED)
            .build()

    /** If the given blob exists, updates its mtime and returns true. Otherwise, returns false.  */
    @Throws(IOException::class)
    fun refresh(digest: Digest?): Boolean {
        return diskCacheClient.refresh(diskCacheClient.toPath(digest, Store.CAS))
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun downloadTree(
        context: RemoteActionExecutionContext, rootDigest: Digest?, rootLocation: Path
    ) {
        rootLocation.createDirectoryAndParents()
        val directory: Directory = Directory.parseFrom(getFromFuture(downloadBlob(context, rootDigest)))
        val childrenSeen: HashSet<Path?> = HashSet<Path?>()
        for (file in directory.getFilesList()) {
            val dst: Path = rootLocation.getRelative(unicodeToInternal(file.getName()))
            if (!childrenSeen.add(dst)) {
                throw IOException("Duplicate child '%s' in directory %s".formatted(dst, directory))
            }
            getFromFuture(downloadFile(context, dst, file.getDigest()))
            dst.setExecutable(file.getIsExecutable())
        }
        for (symlink in directory.getSymlinksList()) {
            val dst: Path = rootLocation.getRelative(unicodeToInternal(symlink.getName()))
            if (!childrenSeen.add(dst)) {
                throw IOException("Duplicate child '%s' in directory %s".formatted(dst, directory))
            }
            // TODO(fmeum): The following line is not generally correct: The remote execution API allows
            //  for non-normalized symlink targets, but the normalization applied by PathFragment.create
            //  does not take directory symlinks into account. However, Bazel's file system API does not
            //  currently offer a way to specify a raw String as a symlink target.
            // https://github.com/bazelbuild/bazel/issues/14224
            dst.createSymbolicLink(PathFragment.create(unicodeToInternal(symlink.getTarget())))
        }
        for (child in directory.getDirectoriesList()) {
            val dst: Path = rootLocation.getRelative(unicodeToInternal(child.getName()))
            if (!childrenSeen.add(dst)) {
                throw IOException("Duplicate child '%s' in directory %s".formatted(dst, directory))
            }
            downloadTree(context, child.getDigest(), dst)
        }
    }

    public override fun downloadBlob(
        context: RemoteActionExecutionContext, digest: Digest?
    ): com.google.common.util.concurrent.ListenableFuture<ByteArray?>? {
        if (remoteWorkerOptions.getErrorOnDuplicateDownloads()) {
            // Only populate numberOfDownloadsPerDigestAndInvocation when fakeErrorForDuplicatedDownloads
            // is enabled to avoid unnecessary unbounded memory growth.
            val numberOfDownloads: Int =
                numberOfDownloadsPerDigestAndInvocation.merge(
                    DigestAndInvocation(digest, context.getRequestMetadata().getToolInvocationId()),
                    1
                ) { a: Int?, b: Int? -> java.lang.Integer.sum(a, b) }
            if (numberOfDownloads > 1) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<ByteArray?>(
                    IOException(
                        java.lang.String.format(
                            "Duplicate download of blob digest %s for invocation id %s",
                            DigestUtil.toString(digest),
                            context.getRequestMetadata().getToolInvocationId()
                        )
                    )
                )
            }
        }

        return super.downloadBlob(context, digest)
    }

    val digestUtil: DigestUtil

    val diskCacheClient: DiskCacheClient
        get() = com.google.common.base.Preconditions.checkNotNull<DiskCacheClient>(field)
}
