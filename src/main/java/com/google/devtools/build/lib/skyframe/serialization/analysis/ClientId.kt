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

/**
 * A unique identifier for a client that interacts with the AnalysisCacheService.
 * 
 * 
 * This identifier exists for purposes related to caching the state of the client itself
 * regardless of the particular requested keys.
 */
@com.google.errorprone.annotations.Immutable
interface ClientId {
    /**
     * A snapshot of the present workspace state as of this invocation.
     * 
     * @param workspaceId a unique string identifier of this workspace
     * @param snapshotVersion a monotonically incrementing number of the snapshot.
     */
    @kotlin.jvm.JvmRecord
    data class SnapshotClientId(val workspaceId: String?, val snapshotVersion: Int) : ClientId

    /**
     * The long version of the workspace for this invocation
     * 
     * @param evaluatingVersion a unique long identifier of this workspace
     */
    @kotlin.jvm.JvmRecord
    data class LongVersionClientId(val evaluatingVersion: Long) : ClientId
}
