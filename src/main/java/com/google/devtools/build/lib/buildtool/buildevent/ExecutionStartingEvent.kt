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
package com.google.devtools.build.lib.buildtool.buildevent

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection

/**
 * This event is fired from [com.google.devtools.build.lib.buildtool.ExecutionTool] to
 * indicate that the execution phase of the build is starting.
 */
class ExecutionStartingEvent(targets: MutableCollection<out TransitiveInfoCollection?>) {
    private val targets: MutableCollection<TransitiveInfoCollection?>

    /**
     * Construct the event with a set of targets.
     * @param targets Remaining active targets.
     */
    init {
        this.targets = ImmutableList.copyOf<TransitiveInfoCollection?>(targets)
    }

    /**
     * @return The set of active targets remaining, which is a subset
     * of the targets in the user request.
     */
    fun getTargets(): MutableCollection<TransitiveInfoCollection?> {
        return targets
    }
}
