// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.EvaluationProgressReceiver
import com.google.devtools.build.skyframe.SkyKey

/** An [EvaluationProgressReceiver] that also tracks the state of inflight keys.  */
internal interface InflightTrackingProgressReceiver : EvaluationProgressReceiver {
    /** Called when a node is injected into the graph, and not evaluated.  */
    fun injected(skyKey: SkyKey?)

    /**
     * Called when a node was requested to be enqueued but wasn't because either an interrupt or an
     * error (in `--nokeep_going` mode) had occurred.
     */
    fun enqueueAfterError(skyKey: SkyKey?)

    /** Returns whether the given key is enqueued for evaluation.  */
    fun isInflight(skyKey: SkyKey?): Boolean

    /** Removes the given key from the set of inflight keys.  */
    fun removeFromInflight(skyKey: SkyKey?)

    /** Returns the set of all keys that are enqueued for evaluation, and resets the set to empty.  */
    val andClearInflightKeys: MutableSet<SkyKey>?
}
