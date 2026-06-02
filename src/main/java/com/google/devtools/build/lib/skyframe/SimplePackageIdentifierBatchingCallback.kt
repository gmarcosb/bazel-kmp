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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * Simple implementation of [PackageIdentifierBatchingCallback] that naively shards a stream
 * of [PackageIdentifier] instances, in order, into fixed-size batches. The final batch may be
 * smaller than the others.
 */
class SimplePackageIdentifierBatchingCallback(batchResults: SafeBatchCallback<PackageIdentifier?>, batchSize: Int) :
    PackageIdentifierBatchingCallback {
    private val batchResults: SafeBatchCallback<PackageIdentifier?>
    private val batchSize: Int

    @javax.annotation.concurrent.GuardedBy("this")
    private var packageIdentifiers: com.google.common.collect.ImmutableList.Builder<PackageIdentifier?>? = null

    @javax.annotation.concurrent.GuardedBy("this")
    private var bufferedPackageIds = 0

    init {
        this.batchResults = batchResults
        this.batchSize = batchSize
        reset()
    }

    @kotlin.jvm.Synchronized
    @Throws(java.lang.InterruptedException::class)
    public override fun process(partialResult: Iterable<PackageIdentifier>) {
        for (path in partialResult) {
            packageIdentifiers.add(path)
            bufferedPackageIds++
            if (bufferedPackageIds >= this.batchSize) {
                flush()
            }
        }
    }

    @kotlin.jvm.Synchronized
    @Throws(java.lang.InterruptedException::class)
    override fun close() {
        flush()
    }

    @javax.annotation.concurrent.GuardedBy("this")
    @Throws(java.lang.InterruptedException::class)
    private fun flush() {
        if (bufferedPackageIds > 0) {
            batchResults.process(packageIdentifiers.build())
            reset()
        }
    }

    @javax.annotation.concurrent.GuardedBy("this")
    private fun reset() {
        packageIdentifiers =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<PackageIdentifier?>(batchSize)
        bufferedPackageIds = 0
    }
}
