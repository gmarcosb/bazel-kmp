// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * SkyValue for `TopLevelAspectsKey` wraps a list of the `AspectValue` of the top level
 * aspects applied on the same top level target.
 */
class TopLevelAspectsValue(topLevelAspectsMap: com.google.common.collect.ImmutableMap<AspectKey?, AspectValue?>?) :
    ActionLookupValue {
    private val topLevelAspectsMap: com.google.common.collect.ImmutableMap<AspectKey?, AspectValue?>

    init {
        this.topLevelAspectsMap =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<AspectKey?, AspectValue?>>(
                topLevelAspectsMap
            )
    }

    fun getTopLevelAspectsMap(): com.google.common.collect.ImmutableMap<AspectKey?, AspectValue?> {
        return topLevelAspectsMap
    }

    val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>
        get() = com.google.common.collect.ImmutableList.of<ActionAnalysisMetadata?>()
}
