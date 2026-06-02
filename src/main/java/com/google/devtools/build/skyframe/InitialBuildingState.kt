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

import com.google.devtools.build.skyframe.DirtyBuildingState
import com.google.devtools.build.skyframe.GroupedDeps
import com.google.devtools.build.skyframe.NodeEntry.DirtyType
import com.google.devtools.build.skyframe.SkyValue

/**
 * [DirtyBuildingState] for a node on its initial build or a [ ] that was [rewound][DirtyType.REWIND].
 */
internal open class InitialBuildingState : DirtyBuildingState(DirtyType.CHANGE) {
    val lastBuildDirectDeps: GroupedDeps?
        get() = null

    val numOfGroupsInLastBuildDirectDeps: Int
        get() = 0

    val lastBuildValue: SkyValue?
        get() = null

    protected val isIncremental: Boolean
        get() = false
}
