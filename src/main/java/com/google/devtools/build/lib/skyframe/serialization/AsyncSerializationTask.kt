// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.SerializationResult
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus
import com.google.protobuf.ByteString

/**
 * A container for a serialization future and an optional profile recorder.
 * 
 * 
 * Used by [SharedValueSerializationContext.serializeToResultAsync].
 */
// actual implementations derive from AbstractFuture
interface AsyncSerializationTask

    : com.google.common.util.concurrent.ListenableFuture<SerializationResult<ByteString?>?>, java.lang.Runnable {
    /**
     * Registers a [WriteStatus] to trigger the commit of the profiling samples.
     * 
     * 
     * If `status` completes with `true`, the samples are recorded in the collector.
     * 
     * 
     * Note that if the `run` method has not yet finished, the underlying profiling samples
     * are in an undefined state. This should not be an issue in practice because `status` won't
     * be available until the bytes are available, and that can't happen before `run` completes.
     * 
     * @param status the status of uploading the bytes produced by this task to storage
     */
    fun registerWriteStatus(status: WriteStatus?)
}
