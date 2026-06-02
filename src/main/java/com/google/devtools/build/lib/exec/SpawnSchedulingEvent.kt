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
 * Notifies that [SpawnRunner] is waiting for local or remote resources to become available.
 */
@kotlin.jvm.JvmRecord
data class SpawnSchedulingEvent(val name: String?) : ProgressStatus {
    override fun postTo(
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        action: ActionExecutionMetadata?
    ) {
        eventHandler.post(SchedulingActionEvent(action, this.name))
    }

    init {
        java.util.Objects.requireNonNull<String?>(name, "name")
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun create(name: String?): SpawnSchedulingEvent {
            return SpawnSchedulingEvent(name)
        }
    }
}
