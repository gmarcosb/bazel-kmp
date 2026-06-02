// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.dynamic

import com.google.devtools.build.lib.actions.DynamicStrategyRegistry.DynamicMode
import com.google.devtools.build.lib.events.ExtendedEventHandler

/** Event transporting the data about winner in dynamic execution race.  */
class DynamicExecutionFinishedEvent(
    @kotlin.jvm.JvmField val mnemonic: String?,
    @kotlin.jvm.JvmField val localBranchName: String?,
    @kotlin.jvm.JvmField val remoteBranchName: String?,
    winnerBranchType: DynamicMode?
) : ExtendedEventHandler.Postable {
    private val winnerBranchType: DynamicMode?

    init {
        this.winnerBranchType = winnerBranchType
    }

    fun getWinnerBranchType(): DynamicMode? {
        return winnerBranchType
    }
}
