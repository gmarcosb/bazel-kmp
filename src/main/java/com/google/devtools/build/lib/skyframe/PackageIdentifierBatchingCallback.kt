// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.BatchCallback.SafeBatchCallback

/**
 * A callback for [ ][com.google.devtools.build.lib.pkgcache.RecursivePackageProvider.streamPackagesUnderDirectory]
 * that buffers the [PackageIdentifier] instances it receives into bounded-size batches that
 * it delivers to a supplied callback.
 * 
 * 
 * This callback must be [closed][.close] to deliver this final batch.
 */
@ThreadSafe
interface PackageIdentifierBatchingCallback

    : SafeBatchCallback<PackageIdentifier?>, java.lang.AutoCloseable {
    @Throws(java.lang.InterruptedException::class)
    override fun close()

    /** Factory for [PackageIdentifierBatchingCallback].  */
    interface Factory {
        fun create(
            batchResults: SafeBatchCallback<PackageIdentifier?>?, maxBatchSize: Int
        ): PackageIdentifierBatchingCallback?
    }
}
