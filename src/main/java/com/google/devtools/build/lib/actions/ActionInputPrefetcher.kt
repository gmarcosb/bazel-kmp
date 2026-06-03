// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.ActionExecutionMetadata
import com.google.devtools.build.lib.actions.ActionInput
import com.google.devtools.build.lib.actions.InputMetadataProvider
import com.google.devtools.build.lib.actions.Spawn

/** Prefetches files to local disk.  */
interface ActionInputPrefetcher {
    /** Priority for the staging task.  */
    enum class Priority {
        /**
         * Critical priority tasks are tasks that are critical to the execution time e.g. staging files
         * for in-process actions.
         */
        CRITICAL,

        /**
         * High priority tasks are tasks that may have impact on the execution time e.g. staging outputs
         * that are inputs to local actions which will be executed later.
         */
        HIGH,

        /**
         * Medium priority tasks are tasks that may or may not have the impact on the execution time
         * e.g. staging inputs for local branch of dynamically scheduled actions.
         */
        MEDIUM,

        /**
         * Low priority tasks are tasks that don't have impact on the execution time e.g. staging
         * outputs of toplevel targets/aspects.
         */
        LOW,
    }

    /** The reason for prefetching.  */
    enum class Reason {
        /** The requested files are needed as inputs to the given action.  */
        INPUTS,

        /** The requested files are requested as outputs of the given action.  */
        OUTPUTS,
    }

    /**
     * Initiates best-effort prefetching of all given inputs.
     * 
     * 
     * For any path not under this prefetcher's control, the call should be a no-op.
     * 
     * 
     * Implementations that wish to operate on unexpanded inputs (tree artifacts, filesets,
     * runfiles) may call [Spawn.getInputFiles] if `spawn` is provided. Otherwise, `expandedInputs` supplies the [ expanded][com.google.devtools.build.lib.exec.SpawnInputExpander] inputs.
     * 
     * @return future success if prefetch is finished or [IOException].
     */
    fun prefetchFiles(
        action: ActionExecutionMetadata?,
        spawn: Spawn?,
        expandedInputs: java.util.function.Supplier<Iterable<out ActionInput?>?>?,
        metadataProvider: InputMetadataProvider?,
        priority: Priority?,
        reason: Reason?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?

    companion object {
        val NONE: ActionInputPrefetcher =
            ActionInputPrefetcher { action: ActionExecutionMetadata?, spawn: Spawn?, expandedInputs: java.util.function.Supplier<Iterable<out ActionInput?>?>?, metadataProvider: InputMetadataProvider?, priority: Priority?, reason: Reason? ->  // Do nothing.
                com.google.common.util.concurrent.Futures.immediateVoidFuture()
            }
    }
}
