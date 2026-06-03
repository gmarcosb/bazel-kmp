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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.actions.ActionContext
import com.google.devtools.build.lib.actions.ActionContext.ActionContextRegistry
import com.google.devtools.build.lib.actions.SandboxedSpawnStrategy
import com.google.devtools.build.lib.actions.Spawn

/** Registry providing access to dynamic spawn strategies for both remote and local modes.  */
interface DynamicStrategyRegistry : ActionContext {
    /** Indicator for whether a strategy is meant for remote or local branch of dynamic execution.  */
    enum class DynamicMode(name: String) {
        REMOTE("remote"),
        LOCAL("local");

        private val name: String?

        init {
            this.name = name
        }

        override fun toString(): String {
            return name!!
        }

        fun other(): DynamicMode {
            return if (this == DynamicMode.REMOTE) DynamicMode.LOCAL else DynamicMode.REMOTE
        }
    }

    /**
     * Returns the spawn strategy implementations that [can execute][SpawnStrategy.canExec]
     * the given spawn in the order that they were registered for the provided dynamic mode.
     */
    fun getDynamicSpawnActionContexts(
        spawn: Spawn?, dynamicMode: DynamicMode?
    ): com.google.common.collect.ImmutableCollection<SandboxedSpawnStrategy?>?

    /**
     * Notifies all strategies applying to at least one mnemonic (including the empty all-catch one)
     * in this registry that they are [used][ActionContext.usedContext].
     * 
     * @param actionContextRegistry a complete registry containing all available action contexts
     */
    fun notifyUsedDynamic(actionContextRegistry: ActionContextRegistry?)
}
