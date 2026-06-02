// Copyright 2019 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.skyframe.EvaluationProgressReceiver.EvaluationState
import com.google.devtools.build.skyframe.EvaluationProgressReceiver.NodeState
import com.google.devtools.build.skyframe.GroupedDeps
import com.google.devtools.build.skyframe.NodeEntry.DirtyType
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * Helper class to allow implementing [EvaluationProgressReceiver] implementations which
 * delegate to a bunch of other [EvaluationProgressReceiver]s.
 */
open class CompoundEvaluationProgressReceiverBase protected constructor(receivers: com.google.common.collect.ImmutableList<out EvaluationProgressReceiver>) :
    EvaluationProgressReceiver {
    protected val receivers: com.google.common.collect.ImmutableList<out EvaluationProgressReceiver>

    init {
        this.receivers = receivers
    }

    override fun dirtied(skyKey: SkyKey?, dirtyType: DirtyType?) {
        for (receiver in receivers) {
            receiver.dirtied(skyKey, dirtyType)
        }
    }

    override fun deleted(skyKey: SkyKey?) {
        for (receiver in receivers) {
            receiver.deleted(skyKey)
        }
    }

    override fun enqueueing(skyKey: SkyKey?) {
        for (receiver in receivers) {
            receiver.enqueueing(skyKey)
        }
    }

    override fun stateStarting(skyKey: SkyKey?, state: NodeState?) {
        for (receiver in receivers) {
            receiver.stateStarting(skyKey, state)
        }
    }

    override fun stateEnding(skyKey: SkyKey?, state: NodeState?) {
        for (receiver in receivers) {
            receiver.stateEnding(skyKey, state)
        }
    }

    override fun evaluated(
        skyKey: SkyKey?,
        state: EvaluationState?,
        newValue: SkyValue?,
        newError: com.google.devtools.build.skyframe.ErrorInfo?,
        directDeps: GroupedDeps?
    ) {
        for (receiver in receivers) {
            receiver.evaluated(skyKey, state, newValue, newError, directDeps)
        }
    }

    override fun changePruned(skyKey: SkyKey?) {
        for (receiver in receivers) {
            receiver.changePruned(skyKey)
        }
    }
}
