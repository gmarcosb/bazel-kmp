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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * A bipartite graph visitor which accumulates actions with matching mnemonics for a target.
 */
class PrintActionVisitor(
    actionGraph: ActionGraph?, target: ConfiguredTarget,
    actionMnemonicMatcher: com.google.common.base.Predicate<ActionAnalysisMetadata?>
) : ActionGraphVisitor(actionGraph) {
    private val target: ConfiguredTarget
    private val actions: MutableList<ActionAnalysisMetadata?>
    private val actionMnemonicMatcher: com.google.common.base.Predicate<ActionAnalysisMetadata?>
    private val targetConfigurationChecksum: String

    /**
     * Creates a new visitor for the actions associated with the given target that have a matching
     * mnemonic.
     */
    init {
        this.target = target
        this.actionMnemonicMatcher = actionMnemonicMatcher
        actions = com.google.common.collect.Lists.newArrayList<ActionAnalysisMetadata?>()
        targetConfigurationChecksum = target.getConfigurationChecksum()
    }

    protected override fun shouldVisit(action: ActionAnalysisMetadata): Boolean {
        val owner: ActionOwner? = action.getOwner()
        return owner != null && target.getLabel().equals(owner.getLabel())
                && targetConfigurationChecksum == owner.getConfigurationChecksum()
    }

    protected override fun visitAction(action: ActionAnalysisMetadata?) {
        if (actionMnemonicMatcher.apply(action)) {
            actions.add(action)
        }
    }

    /** Retrieves the collected actions since this method was last called and clears the list.  */
    fun getActions(): com.google.common.collect.ImmutableList<ActionAnalysisMetadata?> {
        return com.google.common.collect.ImmutableList.copyOf<ActionAnalysisMetadata?>(actions)
    }
}
