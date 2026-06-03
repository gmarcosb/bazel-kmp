// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.skyframe.NodeEntry.DirtyType

/** A testing utility to keep track of evaluation.  */
class TrackingProgressReceiver(private val checkEvaluationResults: Boolean) : EvaluationProgressReceiver {
    /**
     * Callback to be executed on a next [.dirtied] or [.deleted] call. It will be
     * run once and is expected to be run if set.
     */
    private val nextInvalidationCallback: AtomicReference<java.lang.Runnable?> = AtomicReference<java.lang.Runnable?>()

    val dirty: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
    val deleted: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
    val enqueued: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
    val evaluated: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()

    public override fun dirtied(skyKey: SkyKey?, dirtyType: DirtyType?) {
        runInvalidationCallbackIfPresent()
        dirty.add(skyKey)
        com.google.common.base.Preconditions.checkState(!deleted.contains(skyKey), skyKey)
    }

    public override fun deleted(skyKey: SkyKey?) {
        runInvalidationCallbackIfPresent()
        dirty.remove(skyKey)
        deleted.add(skyKey)
    }

    private fun runInvalidationCallbackIfPresent() {
        val callback: java.lang.Runnable? = nextInvalidationCallback.getAndSet(null)
        if (callback != null) {
            callback.run()
        }
    }

    public override fun enqueueing(skyKey: SkyKey?) {
        enqueued.add(skyKey)
    }

    public override fun evaluated(
        skyKey: SkyKey?,
        state: EvaluationState,
        value: SkyValue?,
        error: ErrorInfo?,
        directDeps: GroupedDeps?
    ) {
        evaluated.add(skyKey)
        if (checkEvaluationResults && state.succeeded()) {
            deleted.remove(skyKey)
            if (!state.versionChanged()) {
                dirty.remove(skyKey)
            }
        }
    }

    fun clear() {
        dirty.clear()
        deleted.clear()
        enqueued.clear()
        evaluated.clear()
    }

    fun setNextInvalidationCallback(runnable: java.lang.Runnable?) {
        val oldCallback: java.lang.Runnable? = nextInvalidationCallback.getAndSet(runnable)
        com.google.common.base.Preconditions.checkState(
            oldCallback == null, "Overwriting a left-over callback: %s", oldCallback
        )
    }
}
