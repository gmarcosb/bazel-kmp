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
package com.google.devtools.build.skyframe

/**
 * Encapsulates the two versions relevant to a [NodeEntry]: when it was last evaluated, and
 * when its value last changed.
 */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
interface NodeVersion {
    /**
     * Returns the last version at which a node's value changed.
     * 
     * 
     * In [NodeEntry.setValue] it may be determined that the value being set is the same as
     * the already-stored value. In that case, the last changed version will remain the same.
     */
    fun lastChanged(): com.google.devtools.build.skyframe.Version?

    /**
     * Returns the last version a [NodeEntry] was evaluated at, even if it re-evaluated to the
     * same value.
     * 
     * 
     * When a child signals a node with the last version it was changed at in [ ][NodeEntry.signalDep], the node need not re-evaluate if the child's version is [ ][Version.atMost] this version, even if [.lastChanged] is lower.
     */
    fun lastEvaluated(): com.google.devtools.build.skyframe.Version?

    /**
     * Basic implementation of [NodeVersion] for the case where [.lastChanged] and [ ][.lastEvaluated] are different versions.
     */
    class ChangePruned(
        lastChanged: com.google.devtools.build.skyframe.Version?,
        lastEvaluated: com.google.devtools.build.skyframe.Version?
    ) : NodeVersion {
        val lastChanged: com.google.devtools.build.skyframe.Version?
        val lastEvaluated: com.google.devtools.build.skyframe.Version?

        init {
            this.lastChanged = lastChanged
            this.lastEvaluated = lastEvaluated
        }
    }

    companion object {
        fun of(
            lastChanged: com.google.devtools.build.skyframe.Version,
            lastEvaluated: com.google.devtools.build.skyframe.Version?
        ): NodeVersion {
            if (lastChanged == lastEvaluated) {
                return lastChanged
            }
            return ChangePruned(lastChanged, lastEvaluated)
        }
    }
}
