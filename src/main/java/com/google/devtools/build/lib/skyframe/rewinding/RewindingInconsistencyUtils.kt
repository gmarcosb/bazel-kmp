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
package com.google.devtools.build.lib.skyframe.rewinding

import com.google.devtools.build.lib.actions.ActionLookupData

/** Rewinding-related utilities used by [RewindableGraphInconsistencyReceiver].  */
object RewindingInconsistencyUtils {
    fun mayForceRebuildChildren(key: SkyKey?): Boolean {
        return key is ActionLookupData
                || key is ArtifactNestedSetKey
                || key is TopLevelActionLookupKeyWrapper
    }

    /** Returns whether the key specifies a node which may be rewound by a failed action.  */
    fun isRewindable(key: SkyKey?): Boolean {
        return key is ActionLookupData
                || key is ArtifactNestedSetKey
                || key is Artifact
    }

    /**
     * Returns whether the key specifies a node which depends on nodes which may be rewound.
     * 
     * 
     * Such a node may discover, while in-flight, that a dependency of theirs transitioned from
     * done to undone.
     */
    fun isTypeThatDependsOnRewindableNodes(key: SkyKey?): Boolean {
        return key is ActionLookupData
                || key is ArtifactNestedSetKey
                || key is ActionTemplateExpansionKey
                || key is Artifact
                || key is TargetCompletionKey
                || key is TestCompletionKey
                || key is AspectCompletionKey
    }
}
