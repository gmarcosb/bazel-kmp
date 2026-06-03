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

import com.google.devtools.build.skyframe.NodeEntry.DirtyType

/**
 * Test utility that funnels both [.dirtied] and [.deleted] to a single [ ][.invalidated] method.
 */
abstract class InvalidationProgressReceiver : EvaluationProgressReceiver {
    public override fun dirtied(skyKey: SkyKey?, dirtyType: DirtyType?) {
        invalidated(skyKey, InvalidationState.DIRTY)
    }

    public override fun deleted(skyKey: SkyKey?) {
        invalidated(skyKey, InvalidationState.DELETED)
    }

    /** New state of the value entry after invalidation.  */
    enum class InvalidationState {
        /** The value is dirty, although it might get re-validated again.  */
        DIRTY,

        /** The value is dirty and got deleted, cannot get re-validated again.  */
        DELETED,
    }

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun invalidated(skyKey: SkyKey?, state: InvalidationState?)
}
