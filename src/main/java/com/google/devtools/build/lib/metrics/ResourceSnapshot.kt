// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.metrics

import java.time.Instant

/**
 * Contains a snapshot of the resource usage of multiple processes.
 * 
 * @param pidToMemoryInKb Overall memory consumption by all descendant processes including initial
 * process.
 * @param collectionTime Time when this snapshot was collected.
 */
class ResourceSnapshot(
    pidToMemoryInKb: com.google.common.collect.ImmutableMap<Long?, Int?>?,
    collectionTime: Instant?
) {
    val pidToMemoryInKb: com.google.common.collect.ImmutableMap<Long?, Int?>?
    val collectionTime: Instant?

    init {
        this.collectionTime = collectionTime
        this.pidToMemoryInKb = pidToMemoryInKb
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<Long?, Int?>?>(
            pidToMemoryInKb,
            "pidToMemoryInKb"
        )
        Instant > java.util.Objects.requireNonNull<Instant?>(collectionTime, "collectionTime")
    }

    companion object {
        fun create(
            pidToMemoryInKb: com.google.common.collect.ImmutableMap<Long?, Int?>?, collectionTime: Instant?
        ): ResourceSnapshot {
            return ResourceSnapshot(pidToMemoryInKb, collectionTime)
        }

        fun createEmpty(collectionTime: Instant?): ResourceSnapshot {
            return ResourceSnapshot(com.google.common.collect.ImmutableMap.of<Long?, Int?>(), collectionTime)
        }
    }
}
