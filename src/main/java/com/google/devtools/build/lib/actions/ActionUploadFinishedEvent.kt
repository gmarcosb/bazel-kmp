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
package com.google.devtools.build.lib.actions

import build.bazel.remote.execution.v2.Digest

/**
 * The event fired when a resource is done uploading to a remote or disk cache upon completion of a
 * local action.
 * 
 * @param action Returns the associated action.
 * @param store Returns the [Store] that the resource belongs to.
 * @param digest Returns the [Digest] that uniquely identifies the resource.
 */
class ActionUploadFinishedEvent(action: ActionExecutionMetadata?, store: Store?, digest: Digest?) : Postable {
    val action: ActionExecutionMetadata?
    val store: Store?
    val digest: Digest?

    init {
        this.digest = digest
        this.store = store
        this.action = action
        java.util.Objects.requireNonNull<ActionExecutionMetadata?>(action, "action")
        java.util.Objects.requireNonNull<Any?>(store, "store")
        java.util.Objects.requireNonNull<Any?>(digest, "digest")
    }

    companion object {
        fun create(
            action: ActionExecutionMetadata?, store: Store?, digest: Digest?
        ): ActionUploadFinishedEvent {
            return ActionUploadFinishedEvent(action, store, digest)
        }
    }
}
