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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionExecutionMetadata

/**
 * Notifies that [SpawnRunner] failed to find a cache hit and acquired the resources to
 * execute. This MUST be posted before attempting to execute the subprocess.
 * 
 * 
 * Caching [SpawnRunner] implementations should only post this after a failed cache lookup,
 * but may post this if cache lookup and execution happen within the same step, e.g. as part of a
 * single RPC call with no mechanism to report cache misses.
 */
@kotlin.jvm.JvmRecord
data class SpawnExecutingEvent(val name: String?) : ProgressStatus {
    override fun postTo(
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        action: ActionExecutionMetadata?
    ) {
        eventHandler.post(RunningActionEvent(action, this.name))
    }

    init {
        java.util.Objects.requireNonNull<String?>(name, "name")
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun create(name: String?): SpawnExecutingEvent {
            return SpawnExecutingEvent(name)
        }
    }
}
