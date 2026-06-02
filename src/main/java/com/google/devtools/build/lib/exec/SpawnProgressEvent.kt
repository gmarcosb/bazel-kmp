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
 * The [SpawnRunner] is making some progress.
 * 
 * @param progressId The id that uniquely determines the progress among all progress events for this
 * spawn.
 * @param progress Human readable description of the progress.
 * @param finished Whether the progress reported about is finished already.
 */
@kotlin.jvm.JvmRecord
data class SpawnProgressEvent(val progressId: String?, val progress: String?, val finished: Boolean) : ProgressStatus {
    override fun postTo(
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        action: ActionExecutionMetadata?
    ) {
        eventHandler.post(ActionProgressEvent.create(action, this.progressId, this.progress, this.finished))
    }

    init {
        java.util.Objects.requireNonNull<String?>(progressId, "progressId")
        java.util.Objects.requireNonNull<String?>(progress, "progress")
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun create(resourceId: String?, progress: String?, finished: Boolean): SpawnProgressEvent {
            return SpawnProgressEvent(resourceId, progress, finished)
        }
    }
}
